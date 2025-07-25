// Copyright (c) 2025 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.canton.time

import com.digitalasset.canton.config.ProcessingTimeout
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.discard.Implicits.DiscardOps
import com.digitalasset.canton.lifecycle.{FlagCloseable, FutureUnlessShutdown, UnlessShutdown}
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.ErrorUtil

import java.util.{ConcurrentModificationException, PriorityQueue}
import scala.annotation.tailrec
import scala.concurrent.{Promise, blocking}
import scala.jdk.CollectionConverters.*

/** Utility to implement a time awaiter
  */
final class TimeAwaiter(
    val getCurrentKnownTime: () => CantonTimestamp,
    override val timeouts: ProcessingTimeout,
    override val loggerFactory: NamedLoggerFactory,
) extends FlagCloseable
    with NamedLogging {

  private class ShutdownAwareAwaiting {
    val promise: Promise[UnlessShutdown[Unit]] = Promise[UnlessShutdown[Unit]]()
    def shutdown(): Boolean = {
      promise.trySuccess(UnlessShutdown.AbortedDueToShutdown).discard
      true
    }
    def success(): Unit = promise.trySuccess(UnlessShutdown.unit).discard
  }

  override def onClosed(): Unit =
    blocking(awaitTimestampFuturesLock.synchronized {
      awaitTimestampFutures.iterator().asScala.foreach(_._2.shutdown().discard[Boolean])
    })

  def awaitKnownTimestamp(
      timestamp: CantonTimestamp
  )(implicit traceContext: TraceContext): Option[FutureUnlessShutdown[Unit]] = {
    val current = getCurrentKnownTime()
    if (current >= timestamp) None
    else
      synchronizeWithClosingSync(s"await known timestamp at $timestamp") {
        logger.debug(
          s"Starting time awaiter for timestamp $timestamp. Current known time is $current."
        )
        awaitKnownTimestampGen(timestamp, new ShutdownAwareAwaiting())
      }.map(awaiter => Some(FutureUnlessShutdown(awaiter.promise.future)))
        .onShutdown(Some(FutureUnlessShutdown.abortedDueToShutdown))
  }

  private def awaitKnownTimestampGen[T](
      timestamp: CantonTimestamp,
      create: => ShutdownAwareAwaiting,
  )(implicit traceContext: TraceContext): ShutdownAwareAwaiting = {
    val awaiter = create
    blocking(awaitTimestampFuturesLock.synchronized {
      awaitTimestampFutures.offer(timestamp -> awaiter).discard
    })
    // If the timestamp has been advanced while we're inserting into the priority queue,
    // make sure that we're completing the future.
    val newCurrent = getCurrentKnownTime()
    if (newCurrent >= timestamp) notifyAwaitedFutures(newCurrent)
    awaiter
  }

  /** Queue of timestamps that are being awaited on, ordered by timestamp. Access is synchronized
    * via [[awaitTimestampFuturesLock]].
    */
  private val awaitTimestampFutures: PriorityQueue[(CantonTimestamp, ShutdownAwareAwaiting)] =
    new PriorityQueue[(CantonTimestamp, ShutdownAwareAwaiting)](
      Ordering.by[(CantonTimestamp, ShutdownAwareAwaiting), CantonTimestamp](_._1)
    )
  private val awaitTimestampFuturesLock: AnyRef = new Object()

  def notifyAwaitedFutures(
      upToInclusive: CantonTimestamp
  )(implicit traceContext: TraceContext): Unit = {
    @tailrec def go(): Unit = Option(awaitTimestampFutures.peek()) match {
      case Some(peeked @ (timestamp, awaiter)) if timestamp <= upToInclusive =>
        val polled = awaitTimestampFutures.poll()
        // Thanks to the synchronization, the priority queue cannot be modified concurrently,
        // but let's be paranoid and check.
        if (peeked ne polled) {
          ErrorUtil.internalError(
            new ConcurrentModificationException(
              s"Insufficient synchronization in time awaiter. Peek returned $peeked, polled returned $polled"
            )
          )
        }
        logger.debug(s"Completing time awaiter for timestamp $timestamp")
        awaiter.success()
        go()
      case _ =>
    }

    blocking(awaitTimestampFuturesLock.synchronized {
      go()
    })
  }

}
