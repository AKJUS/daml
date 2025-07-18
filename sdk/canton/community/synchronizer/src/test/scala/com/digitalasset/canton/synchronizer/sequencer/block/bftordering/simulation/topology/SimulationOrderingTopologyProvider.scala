// Copyright (c) 2025 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.canton.synchronizer.sequencer.block.bftordering.simulation.topology

import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.protocol.DynamicSynchronizerParameters
import com.digitalasset.canton.sequencing.protocol.MaxRequestSizeToDeserialize
import com.digitalasset.canton.synchronizer.sequencer.block.bftordering.bindings.canton.crypto.FingerprintKeyId
import com.digitalasset.canton.synchronizer.sequencer.block.bftordering.bindings.p2p.grpc.P2PGrpcNetworking.P2PEndpoint
import com.digitalasset.canton.synchronizer.sequencer.block.bftordering.core.integration.canton.crypto.CryptoProvider
import com.digitalasset.canton.synchronizer.sequencer.block.bftordering.core.integration.canton.topology.{
  OrderingTopologyProvider,
  TopologyActivationTime,
}
import com.digitalasset.canton.synchronizer.sequencer.block.bftordering.endpointToTestBftNodeId
import com.digitalasset.canton.synchronizer.sequencer.block.bftordering.framework.data.BftOrderingIdentifiers.BftNodeId
import com.digitalasset.canton.synchronizer.sequencer.block.bftordering.framework.data.topology.OrderingTopology.NodeTopologyInfo
import com.digitalasset.canton.synchronizer.sequencer.block.bftordering.framework.data.topology.{
  OrderingTopology,
  SequencingParameters,
}
import com.digitalasset.canton.synchronizer.sequencer.block.bftordering.framework.simulation.SimulationModuleSystem.SimulationEnv
import com.digitalasset.canton.synchronizer.sequencer.block.bftordering.framework.simulation.future.SimulationFuture
import com.digitalasset.canton.tracing.TraceContext

import scala.util.Success

class SimulationOrderingTopologyProvider(
    thisNode: BftNodeId,
    getEndpointsToTopologyData: () => Map[P2PEndpoint, NodeSimulationTopologyData],
    loggerFactory: NamedLoggerFactory,
) extends OrderingTopologyProvider[SimulationEnv] {

  override def getOrderingTopologyAt(activationTime: TopologyActivationTime)(implicit
      traceContext: TraceContext
  ): SimulationFuture[Option[(OrderingTopology, CryptoProvider[SimulationEnv])]] =
    SimulationFuture(s"getOrderingTopologyAt($activationTime)") { () =>
      val activeSequencerTopologyData =
        getEndpointsToTopologyData().view
          .filter { case (_, topologyData) =>
            topologyData.onboardingTime.value <= activationTime.value
            && topologyData.offboardingTime.forall(activationTime.value <= _)
          }
          .map { case (endpoint, topologyData) =>
            endpointToTestBftNodeId(endpoint) -> topologyData
          }
          .toMap

      val topology =
        OrderingTopology(
          activeSequencerTopologyData.view.mapValues { simulationTopologyData =>
            NodeTopologyInfo(
              activationTime = simulationTopologyData.onboardingTime,
              keyIds = simulationTopologyData
                .keysForTimestamp(activationTime.value)
                .view
                .map(keyPair => FingerprintKeyId.toBftKeyId(keyPair.publicKey.id))
                .toSet,
            )
          }.toMap,
          SequencingParameters.Default,
          MaxRequestSizeToDeserialize.Limit(
            DynamicSynchronizerParameters.defaultMaxRequestSize.value
          ),
          activationTime,
          // Switch the value deterministically so that we trigger all code paths.
          areTherePendingCantonTopologyChanges = activationTime.value.toMicros % 2 == 0,
        )
      Success(
        Some(
          topology -> SimulationCryptoProvider.create(
            thisNode,
            activeSequencerTopologyData,
            activationTime.value,
            loggerFactory,
          )
        )
      )
    }
}
