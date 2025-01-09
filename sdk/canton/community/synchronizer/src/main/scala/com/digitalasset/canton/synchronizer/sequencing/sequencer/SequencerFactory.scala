// Copyright (c) 2025 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.canton.synchronizer.sequencing.sequencer

import cats.data.EitherT
import com.digitalasset.canton.concurrent.FutureSupervisor
import com.digitalasset.canton.config.{CachingConfigs, ProcessingTimeout}
import com.digitalasset.canton.crypto.SynchronizerSyncCryptoClient
import com.digitalasset.canton.environment.CantonNodeParameters
import com.digitalasset.canton.lifecycle.{FlagCloseable, FutureUnlessShutdown, HasCloseContext}
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.resource.Storage
import com.digitalasset.canton.synchronizer.block.SequencerDriver
import com.digitalasset.canton.synchronizer.metrics.SequencerMetrics
import com.digitalasset.canton.synchronizer.sequencing.sequencer.block.DriverBlockSequencerFactory
import com.digitalasset.canton.synchronizer.sequencing.sequencer.block.bftordering.core.driver.BftSequencerFactory
import com.digitalasset.canton.synchronizer.sequencing.sequencer.store.SequencerStore
import com.digitalasset.canton.synchronizer.sequencing.sequencer.traffic.SequencerTrafficConfig
import com.digitalasset.canton.time.Clock
import com.digitalasset.canton.topology.{SequencerId, SynchronizerId}
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.version.ProtocolVersion
import io.opentelemetry.api.trace.Tracer
import org.apache.pekko.stream.Materializer

import java.util.concurrent.ScheduledExecutorService
import scala.concurrent.ExecutionContext

trait SequencerFactory extends FlagCloseable with HasCloseContext {

  def initialize(
      initialState: SequencerInitialState,
      sequencerId: SequencerId,
  )(implicit
      ec: ExecutionContext,
      traceContext: TraceContext,
  ): EitherT[FutureUnlessShutdown, String, Unit]

  def create(
      synchronizerId: SynchronizerId,
      sequencerId: SequencerId,
      clock: Clock,
      driverClock: Clock, // this clock is only used in tests, otherwise can the same clock as above can be passed
      domainSyncCryptoApi: SynchronizerSyncCryptoClient,
      futureSupervisor: FutureSupervisor,
      trafficConfig: SequencerTrafficConfig,
      runtimeReady: FutureUnlessShutdown[Unit],
      sequencerSnapshot: Option[SequencerSnapshot],
  )(implicit
      traceContext: TraceContext,
      tracer: Tracer,
      actorMaterializer: Materializer,
  ): FutureUnlessShutdown[Sequencer]
}

abstract class DatabaseSequencerFactory(
    config: DatabaseSequencerConfig,
    storage: Storage,
    cachingConfigs: CachingConfigs,
    override val timeouts: ProcessingTimeout,
    protocolVersion: ProtocolVersion,
    sequencerId: SequencerId,
    blockSequencerMode: Boolean,
)(implicit ec: ExecutionContext)
    extends SequencerFactory
    with NamedLogging {

  val sequencerStore: SequencerStore =
    SequencerStore(
      storage = storage,
      protocolVersion = protocolVersion,
      maxBufferedEventsSize = config.writer.maxBufferedEventsSize,
      timeouts = timeouts,
      loggerFactory = loggerFactory,
      sequencerMember = sequencerId,
      blockSequencerMode = blockSequencerMode,
      cachingConfigs = cachingConfigs,
      // Overriding the store's close context with the writers, so that when the writer gets closed, the store
      // stops retrying forever
      overrideCloseContext = Some(this.closeContext),
    )

  override def initialize(
      initialState: SequencerInitialState,
      sequencerId: SequencerId,
  )(implicit
      ec: ExecutionContext,
      traceContext: TraceContext,
  ): EitherT[FutureUnlessShutdown, String, Unit] =
    sequencerStore.initializeFromSnapshot(initialState)
}

class CommunityDatabaseSequencerFactory(
    config: DatabaseSequencerConfig,
    metrics: SequencerMetrics,
    storage: Storage,
    sequencerProtocolVersion: ProtocolVersion,
    sequencerId: SequencerId,
    nodeParameters: CantonNodeParameters,
    override val loggerFactory: NamedLoggerFactory,
)(implicit ec: ExecutionContext)
    extends DatabaseSequencerFactory(
      config,
      storage,
      nodeParameters.cachingConfigs,
      nodeParameters.processingTimeouts,
      sequencerProtocolVersion,
      sequencerId,
      blockSequencerMode = false,
    ) {

  override def create(
      synchronizerId: SynchronizerId,
      sequencerId: SequencerId,
      clock: Clock,
      driverClock: Clock,
      domainSyncCryptoApi: SynchronizerSyncCryptoClient,
      futureSupervisor: FutureSupervisor,
      trafficConfig: SequencerTrafficConfig,
      runtimeReady: FutureUnlessShutdown[Unit],
      sequencerSnapshot: Option[SequencerSnapshot],
  )(implicit
      traceContext: TraceContext,
      tracer: Tracer,
      actorMaterializer: Materializer,
  ): FutureUnlessShutdown[Sequencer] = {
    val sequencer = DatabaseSequencer.single(
      config,
      None,
      nodeParameters.processingTimeouts,
      storage,
      sequencerStore,
      clock,
      synchronizerId,
      sequencerId,
      sequencerProtocolVersion,
      domainSyncCryptoApi,
      metrics,
      loggerFactory,
    )

    FutureUnlessShutdown.pure(
      config.testingInterceptor.map(_(clock)(sequencer)(ec)).getOrElse(sequencer)
    )
  }

}

/** Artificial interface for dependency injection
  */
trait MkSequencerFactory {

  def apply(
      protocolVersion: ProtocolVersion,
      health: Option[SequencerHealthConfig],
      clock: Clock,
      scheduler: ScheduledExecutorService,
      metrics: SequencerMetrics,
      storage: Storage,
      sequencerId: SequencerId,
      nodeParameters: CantonNodeParameters,
      futureSupervisor: FutureSupervisor,
      loggerFactory: NamedLoggerFactory,
  )(
      sequencerConfig: SequencerConfig
  )(implicit ececutionContext: ExecutionContext): SequencerFactory

}

object CommunitySequencerFactory extends MkSequencerFactory {
  override def apply(
      protocolVersion: ProtocolVersion,
      health: Option[SequencerHealthConfig],
      clock: Clock,
      scheduler: ScheduledExecutorService,
      metrics: SequencerMetrics,
      storage: Storage,
      sequencerId: SequencerId,
      nodeParameters: CantonNodeParameters,
      futureSupervisor: FutureSupervisor,
      loggerFactory: NamedLoggerFactory,
  )(sequencerConfig: SequencerConfig)(implicit
      executionContext: ExecutionContext
  ): SequencerFactory = sequencerConfig match {
    case communityDbConfig: CommunitySequencerConfig.Database =>
      new CommunityDatabaseSequencerFactory(
        communityDbConfig,
        metrics,
        storage,
        protocolVersion,
        sequencerId,
        nodeParameters,
        loggerFactory,
      )

    case CommunitySequencerConfig.BftSequencer(blockSequencerConfig, config) =>
      new BftSequencerFactory(
        config,
        blockSequencerConfig,
        health,
        storage,
        protocolVersion,
        sequencerId,
        nodeParameters,
        metrics,
        loggerFactory,
        blockSequencerConfig.testingInterceptor,
      )

    case CommunitySequencerConfig.External(
          sequencerType,
          blockSequencerConfig,
          config,
        ) =>
      DriverBlockSequencerFactory(
        sequencerType,
        SequencerDriver.DriverApiVersion,
        config,
        blockSequencerConfig,
        health,
        storage,
        protocolVersion,
        sequencerId,
        nodeParameters,
        metrics,
        loggerFactory,
        blockSequencerConfig.testingInterceptor,
      )

    case config: SequencerConfig =>
      throw new UnsupportedOperationException(s"Invalid config type ${config.getClass}")
  }
}