// Copyright (c) 2025 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.canton.sequencing.client

import cats.syntax.either.*
import cats.syntax.option.*
import com.digitalasset.canton.config.ProcessingTimeout
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.health.{AtomicHealthComponent, ComponentHealthState}
import com.digitalasset.canton.lifecycle.{FlagCloseable, HasRunOnClosing, OnShutdownRunner}
import com.digitalasset.canton.logging.pretty.{Pretty, PrettyPrinting}
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging, TracedLogger}
import com.digitalasset.canton.sequencing.SequencedSerializedEvent
import com.digitalasset.canton.sequencing.SequencerAggregatorPekko.HasSequencerSubscriptionFactoryPekko
import com.digitalasset.canton.sequencing.client.ResilientSequencerSubscription.LostSequencerSubscription
import com.digitalasset.canton.sequencing.client.transports.SequencerClientTransportPekko
import com.digitalasset.canton.sequencing.protocol.SubscriptionRequest
import com.digitalasset.canton.topology.{Member, SequencerId}
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.PekkoUtil.{RetrySourcePolicy, WithKillSwitch}
import com.digitalasset.canton.util.Thereafter.syntax.*
import com.digitalasset.canton.util.{LoggerUtil, PekkoUtil}
import com.digitalasset.canton.version.ProtocolVersion
import org.apache.pekko.Done
import org.apache.pekko.stream.scaladsl.Source
import org.apache.pekko.stream.{AbruptStageTerminationException, KillSwitch, Materializer}

import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{ExecutionContext, Future}

/** Attempts to create a resilient [[SequencerSubscriptionPekko]] for the [[SequencerClient]] by
  * creating underlying subscriptions using the [[SequencerSubscriptionFactoryPekko]] and then
  * recreating them if they fail with a reason that is deemed retryable. If a subscription is closed
  * or fails with a reason that is not retryable the failure will be passed downstream from this
  * subscription. We determine whether an error is retryable by calling the
  * [[SubscriptionErrorRetryPolicy]] of the supplied [[SequencerSubscriptionFactoryPekko]]. We also
  * will delay recreating subscriptions by an interval determined by the
  * [[com.digitalasset.canton.sequencing.client.SubscriptionRetryDelayRule]]. The recreated
  * subscription starts at the last event received, or at the starting counter that was given
  * initially if no event was received at all.
  *
  * The emitted events stutter whenever the subscription is recreated.
  */
class ResilientSequencerSubscriberPekko[E](
    retryDelayRule: SubscriptionRetryDelayRule,
    subscriptionFactory: SequencerSubscriptionFactoryPekko[E],
    protected override val timeouts: ProcessingTimeout,
    protected override val loggerFactory: NamedLoggerFactory,
)(implicit materializer: Materializer)
    extends FlagCloseable
    with NamedLogging {
  import ResilientSequencerSubscriberPekko.*

  /** Start running the resilient sequencer subscription from the given counter */
  def subscribeFrom(startingTimestamp: Option[CantonTimestamp])(implicit
      traceContext: TraceContext
  ): SequencerSubscriptionPekko[E] = {

    logger.debug(s"Starting resilient sequencer subscription from counter $startingTimestamp")
    val startingTimestampString = startingTimestamp.map(_.toString).getOrElse("beginning")
    val onShutdownRunner = new OnShutdownRunner.PureOnShutdownRunner(logger)
    val sequencerId = subscriptionFactory.sequencerId
    val health = new ResilientSequencerSubscriptionHealth(
      s"sequencer-subscription-for-$sequencerId-starting-at-$startingTimestampString",
      sequencerId,
      onShutdownRunner,
      logger,
    )
    val initial =
      RestartSourceConfig(startingTimestamp, retryDelayRule.initialDelay, health)(traceContext)
    val source = PekkoUtil
      .restartSource("resilient-sequencer-subscription", initial, mkSource, policy)
      // Filter out retried errors
      .filter {
        case WithKillSwitch(Left(triaged)) => !triaged.retryable
        case WithKillSwitch(Right(_)) => true
      }
      .map(_.map(_.leftMap(_.error)))
      .mapMaterializedValue { case (killSwitch, doneF) =>
        implicit val ec: ExecutionContext = materializer.executionContext
        val closedHealthF = doneF.thereafter { _ =>
          // A `restartSource` may be materialized at most once anyway,
          // so it's OK to use a shared OnShutdownRunner and HealthComponent here
          onShutdownRunner.close()
        }
        (killSwitch, closedHealthF)
      }
    SequencerSubscriptionPekko(source, health)
  }

  private val policy: RetrySourcePolicy[
    RestartSourceConfig,
    Either[TriagedError[E], SequencedSerializedEvent],
  ] =
    new RetrySourcePolicy[RestartSourceConfig, Either[TriagedError[E], SequencedSerializedEvent]] {
      override def shouldRetry(
          lastState: RestartSourceConfig,
          lastEmittedElement: Option[Either[TriagedError[E], SequencedSerializedEvent]],
          lastFailure: Option[Throwable],
      ): Option[(FiniteDuration, RestartSourceConfig)] = {
        implicit val traceContext: TraceContext = lastState.traceContext
        val retryPolicy = subscriptionFactory.retryPolicy
        val hasReceivedEvent = lastEmittedElement.exists {
          case Left(err) => err.hasReceivedElements
          case Right(_) => true
        }
        val canRetry = lastFailure match {
          case None =>
            lastEmittedElement match {
              case Some(Right(_)) => false
              case Some(Left(err)) =>
                val canRetry = err.retryable
                if (!canRetry)
                  logger.warn(
                    s"Closing resilient sequencer subscription due to error: ${err.error}"
                  )
                canRetry
              case None =>
                logger.info("The sequencer subscription has been terminated by the server.")
                false
            }
          case Some(ex: AbruptStageTerminationException) =>
            logger.debug("Giving up on resilient sequencer subscription due to shutdown", ex)
            false
          case Some(ex) =>
            val canRetry = retryPolicy.retryOnException(ex)
            if (canRetry) {
              logger.warn(
                s"The sequencer subscription encountered an exception and will be restarted",
                ex,
              )
              true
            } else {
              logger.error(
                "Closing resilient sequencer subscription due to exception",
                ex,
              )
              false
            }
        }
        Option.when(canRetry) {
          val currentDelay = lastState.delay
          val logMessage =
            s"Waiting ${LoggerUtil.roundDurationForHumans(currentDelay)} before reconnecting"
          if (currentDelay < retryDelayRule.warnDelayDuration) {
            logger.debug(logMessage)
          } else if (lastState.health.isFailed) {
            logger.info(logMessage)
          } else {
            val error =
              LostSequencerSubscription.Warn(subscriptionFactory.sequencerId, _logOnCreation = true)
            lastState.health.failureOccurred(error)
          }

          val nextStartingTimestamp = lastEmittedElement.fold(lastState.startingTimestamp)(
            _.fold(_.lastEventTimestamp, _.timestamp.some)
          )
          val newDelay = retryDelayRule.nextDelay(currentDelay, hasReceivedEvent)
          currentDelay -> lastState.copy(
            startingTimestamp = nextStartingTimestamp,
            delay = newDelay,
          )
        }
      }
    }

  private def mkSource(
      config: RestartSourceConfig
  ): Source[Either[TriagedError[E], SequencedSerializedEvent], (KillSwitch, Future[Done])] = {
    implicit val traceContext: TraceContext = config.traceContext
    val startingTimestamp = config.startingTimestamp
    val startingTimestampString = startingTimestamp.map(_.toString).getOrElse("the beginning")
    logger.debug(s"Starting new sequencer subscription from $startingTimestampString")
    subscriptionFactory
      .create(startingTimestamp)
      .source
      .statefulMap(() => TriageState(hasPreviouslyReceivedEvents = false, startingTimestamp))(
        triageError(config.health),
        _ => None,
      )
  }

  private def triageError(health: ResilientSequencerSubscriptionHealth)(
      state: TriageState,
      elementWithKillSwitch: WithKillSwitch[Either[E, SequencedSerializedEvent]],
  )(implicit
      traceContext: TraceContext
  ): (TriageState, Either[TriagedError[E], SequencedSerializedEvent]) = {
    val element = elementWithKillSwitch.value
    val TriageState(hasPreviouslyReceivedEvents, lastEventTimestamp) = state
    val hasReceivedEvents = hasPreviouslyReceivedEvents || element.isRight
    // Resolve to healthy when we get a new element again
    if (!hasPreviouslyReceivedEvents && element.isRight) {
      health.resolveUnhealthy()
    }
    val triaged = element.leftMap { err =>
      val canRetry = subscriptionFactory.retryPolicy.retryOnError(err, hasReceivedEvents)
      TriagedError(canRetry, hasReceivedEvents, lastEventTimestamp, err)
    }
    val currentStartingTimestamp = element.fold(_ => lastEventTimestamp, _.timestamp.some)
    val newState = TriageState(hasReceivedEvents, currentStartingTimestamp)
    (newState, triaged)
  }
}

object ResilientSequencerSubscriberPekko {

  /** @param startingCounter
    *   The counter to start the next subscription from
    * @param delay
    *   If the next subscription fails with a retryable error, how long should we wait before
    *   starting a new subscription?
    */
  private[ResilientSequencerSubscriberPekko] final case class RestartSourceConfig(
      startingTimestamp: Option[CantonTimestamp],
      delay: FiniteDuration,
      health: ResilientSequencerSubscriptionHealth,
  )(val traceContext: TraceContext)
      extends PrettyPrinting {
    override protected def pretty: Pretty[RestartSourceConfig.this.type] = prettyOfClass(
      param("starting timestamp", _.startingTimestamp)
    )

    def copy(
        startingTimestamp: Option[CantonTimestamp] = this.startingTimestamp,
        delay: FiniteDuration = this.delay,
        health: ResilientSequencerSubscriptionHealth = this.health,
    ): RestartSourceConfig = RestartSourceConfig(startingTimestamp, delay, health)(traceContext)
  }

  private final case class TriagedError[+E](
      retryable: Boolean,
      hasReceivedElements: Boolean,
      lastEventTimestamp: Option[CantonTimestamp],
      error: E,
  )

  def factory[E](
      sequencerID: SequencerId,
      retryDelayRule: SubscriptionRetryDelayRule,
      subscriptionFactory: SequencerSubscriptionFactoryPekko[E],
      timeouts: ProcessingTimeout,
      loggerFactory: NamedLoggerFactory,
  )(implicit
      materializer: Materializer
  ): SequencerSubscriptionFactoryPekko[E] = {
    val subscriber = new ResilientSequencerSubscriberPekko[E](
      retryDelayRule,
      subscriptionFactory,
      timeouts,
      loggerFactory,
    )
    new SequencerSubscriptionFactoryPekko[E] {
      override def sequencerId: SequencerId = sequencerID

      override def create(startingTimestamp: Option[CantonTimestamp])(implicit
          traceContext: TraceContext
      ): SequencerSubscriptionPekko[E] = subscriber.subscribeFrom(startingTimestamp)

      override val retryPolicy: SubscriptionErrorRetryPolicyPekko[E] =
        SubscriptionErrorRetryPolicyPekko.never
    }
  }

  private final case class TriageState(
      hasPreviouslyReceivedEvents: Boolean,
      lastEventTimestamp: Option[CantonTimestamp],
  )

  private class ResilientSequencerSubscriptionHealth(
      override val name: String,
      sequencerId: SequencerId,
      override protected val associatedHasRunOnClosing: HasRunOnClosing,
      override protected val logger: TracedLogger,
  ) extends AtomicHealthComponent {
    override protected def initialHealthState: ComponentHealthState = ComponentHealthState.Ok()
    override def closingState: ComponentHealthState =
      ComponentHealthState.failed(s"Disconnected from sequencer $sequencerId")
  }
}

trait SequencerSubscriptionFactoryPekko[E] extends HasSequencerSubscriptionFactoryPekko[E] {

  /** The ID of the sequencer this factory creates subscriptions to */
  def sequencerId: SequencerId

  def create(
      startingTimestamp: Option[CantonTimestamp]
  )(implicit traceContext: TraceContext): SequencerSubscriptionPekko[E]

  def retryPolicy: SubscriptionErrorRetryPolicyPekko[E]

  override def subscriptionFactory: this.type = this
}

object SequencerSubscriptionFactoryPekko {

  /** Creates a [[SequencerSubscriptionFactoryPekko]] for a [[ResilientSequencerSubscriberPekko]]
    * that uses an underlying gRPC transport. Changes to the underlying gRPC transport are not
    * supported by the [[ResilientSequencerSubscriberPekko]]; these can be done via the sequencer
    * aggregator.
    */
  def fromTransport[E](
      sequencerID: SequencerId,
      transport: SequencerClientTransportPekko.Aux[E],
      member: Member,
      protocolVersion: ProtocolVersion,
  ): SequencerSubscriptionFactoryPekko[E] =
    new SequencerSubscriptionFactoryPekko[E] {
      override def sequencerId: SequencerId = sequencerID

      override def create(startingTimestamp: Option[CantonTimestamp])(implicit
          traceContext: TraceContext
      ): SequencerSubscriptionPekko[E] = {
        val request = SubscriptionRequest(member, startingTimestamp, protocolVersion)
        transport.subscribe(request)
      }

      override val retryPolicy: SubscriptionErrorRetryPolicyPekko[E] =
        transport.subscriptionRetryPolicyPekko
    }
}
