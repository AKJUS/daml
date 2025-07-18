// Copyright (c) 2025 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.canton.participant.protocol

import com.digitalasset.canton.data.ViewType
import com.digitalasset.canton.data.ViewType.TransactionViewType
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.participant.event.RecordOrderPublisher
import com.digitalasset.canton.participant.metrics.ConnectedSynchronizerMetrics
import com.digitalasset.canton.participant.protocol.MessageDispatcher.{
  ParticipantTopologyProcessor,
  RequestProcessors,
}
import com.digitalasset.canton.participant.protocol.conflictdetection.RequestTracker
import com.digitalasset.canton.participant.protocol.submission.InFlightSubmissionSynchronizerTracker
import com.digitalasset.canton.participant.pruning.AcsCommitmentProcessor
import com.digitalasset.canton.sequencing.traffic.TrafficControlProcessor
import com.digitalasset.canton.topology.{ParticipantId, PhysicalSynchronizerId}
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.ExecutionContext

object ParallelMessageDispatcherFactory
    extends MessageDispatcher.Factory[ParallelMessageDispatcher] {
  // Process transactions and reassignments asynchronously
  private def processAsynchronously(viewType: ViewType): Boolean =
    viewType == TransactionViewType || viewType == ViewType.UnassignmentViewType || viewType == ViewType.AssignmentViewType

  override def create(
      psid: PhysicalSynchronizerId,
      participantId: ParticipantId,
      requestTracker: RequestTracker,
      requestProcessors: RequestProcessors,
      topologyProcessor: ParticipantTopologyProcessor,
      trafficProcessor: TrafficControlProcessor,
      acsCommitmentProcessor: AcsCommitmentProcessor.ProcessorType,
      requestCounterAllocator: RequestCounterAllocator,
      recordOrderPublisher: RecordOrderPublisher,
      badRootHashMessagesRequestProcessor: BadRootHashMessagesRequestProcessor,
      inFlightSubmissionSynchronizerTracker: InFlightSubmissionSynchronizerTracker,
      loggerFactory: NamedLoggerFactory,
      metrics: ConnectedSynchronizerMetrics,
  )(implicit ec: ExecutionContext, tracer: Tracer): ParallelMessageDispatcher =
    new ParallelMessageDispatcher(
      psid,
      participantId,
      requestTracker,
      requestProcessors,
      topologyProcessor,
      trafficProcessor,
      acsCommitmentProcessor,
      requestCounterAllocator,
      recordOrderPublisher,
      badRootHashMessagesRequestProcessor,
      inFlightSubmissionSynchronizerTracker,
      processAsynchronously,
      loggerFactory,
      metrics,
    )
}
