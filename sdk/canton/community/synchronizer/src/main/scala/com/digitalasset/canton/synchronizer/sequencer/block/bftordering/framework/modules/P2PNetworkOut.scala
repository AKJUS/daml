// Copyright (c) 2025 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.canton.synchronizer.sequencer.block.bftordering.framework.modules

import com.digitalasset.canton.synchronizer.sequencer.block.bftordering.admin.SequencerBftAdminData.PeerNetworkStatus
import com.digitalasset.canton.synchronizer.sequencer.block.bftordering.bindings.p2p.grpc.P2PGrpcNetworking.P2PEndpoint
import com.digitalasset.canton.synchronizer.sequencer.block.bftordering.framework.data.BftOrderingIdentifiers.BftNodeId
import com.digitalasset.canton.synchronizer.sequencer.block.bftordering.framework.data.SignedMessage
import com.digitalasset.canton.synchronizer.sequencer.block.bftordering.framework.modules.dependencies.P2PNetworkOutModuleDependencies
import com.digitalasset.canton.synchronizer.sequencer.block.bftordering.framework.{
  Env,
  Module,
  P2PNetworkRefFactory,
}
import com.digitalasset.canton.synchronizer.sequencing.sequencer.bftordering.v30
import com.digitalasset.canton.synchronizer.sequencing.sequencer.bftordering.v30.BftOrderingServiceReceiveRequest

object P2PNetworkOut {

  sealed trait Message extends Product

  case object Start extends Message

  sealed trait Internal extends Message
  object Internal {
    final case class Connect(endpoint: P2PEndpoint) extends Internal
    final case class Disconnect(endpointId: P2PEndpoint.Id) extends Internal
  }

  sealed trait Network extends Message
  object Network {
    final case class Connected(endpointId: P2PEndpoint.Id) extends Network
    final case class Disconnected(endpointId: P2PEndpoint.Id) extends Network
    final case class Authenticated(endpointId: P2PEndpoint.Id, node: BftNodeId) extends Network
  }

  sealed trait Admin extends Message
  object Admin {
    final case class AddEndpoint(
        endpoint: P2PEndpoint,
        callback: Boolean => Unit,
    ) extends Admin
    final case class RemoveEndpoint(
        endpointId: P2PEndpoint.Id,
        callback: Boolean => Unit,
    ) extends Admin
    final case class GetStatus(
        callback: PeerNetworkStatus => Unit,
        endpointIds: Option[Iterable[P2PEndpoint.Id]] = None,
    ) extends Admin
  }

  sealed trait BftOrderingNetworkMessage {
    def toProto: v30.BftOrderingMessageBody
  }

  object BftOrderingNetworkMessage {
    final case class AvailabilityMessage(
        signedMessage: SignedMessage[Availability.RemoteProtocolMessage]
    ) extends BftOrderingNetworkMessage {
      override def toProto: v30.BftOrderingMessageBody = v30.BftOrderingMessageBody(
        v30.BftOrderingMessageBody.Message.AvailabilityMessage(signedMessage.toProtoV1)
      )
    }

    final case class ConsensusMessage(
        signedMessage: SignedMessage[ConsensusSegment.ConsensusMessage.PbftNetworkMessage]
    ) extends BftOrderingNetworkMessage {
      override def toProto: v30.BftOrderingMessageBody = v30.BftOrderingMessageBody(
        v30.BftOrderingMessageBody.Message.ConsensusMessage(signedMessage.toProtoV1)
      )
    }

    final case class RetransmissionMessage(
        message: Consensus.RetransmissionsMessage.RetransmissionsNetworkMessage
    ) extends BftOrderingNetworkMessage {
      override def toProto: v30.BftOrderingMessageBody = v30.BftOrderingMessageBody(
        v30.BftOrderingMessageBody.Message.RetransmissionMessage(message.toProto)
      )
    }

    final case class StateTransferMessage(
        signedMessage: SignedMessage[Consensus.StateTransferMessage.StateTransferNetworkMessage]
    ) extends BftOrderingNetworkMessage {
      override def toProto: v30.BftOrderingMessageBody = v30.BftOrderingMessageBody(
        v30.BftOrderingMessageBody.Message.StateTransferMessage(signedMessage.toProtoV1)
      )
    }

    final case object Empty extends BftOrderingNetworkMessage {
      override def toProto: v30.BftOrderingMessageBody =
        v30.BftOrderingMessageBody(v30.BftOrderingMessageBody.Message.Empty)
    }
  }

  final case class Multicast(
      message: BftOrderingNetworkMessage,
      to: Set[BftNodeId],
  ) extends Message

  def send(
      message: BftOrderingNetworkMessage,
      to: BftNodeId,
  ): Multicast =
    Multicast(message, Set(to))
}

trait P2PNetworkOut[
    E <: Env[E],
    P2PNetworkRefFactoryT <: P2PNetworkRefFactory[E, BftOrderingServiceReceiveRequest],
] extends Module[E, P2PNetworkOut.Message] {
  val dependencies: P2PNetworkOutModuleDependencies[E, P2PNetworkRefFactoryT]
}
