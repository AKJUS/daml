// Copyright (c) 2025 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.canton.synchronizer.sequencer.block.bftordering.core.modules.consensus.iss

import com.daml.metrics.api.MetricsContext
import com.digitalasset.canton.crypto.SignatureCheckError
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.synchronizer.metrics.SequencerMetrics
import com.digitalasset.canton.synchronizer.sequencer.block.bftordering.BftSequencerBaseTest
import com.digitalasset.canton.synchronizer.sequencer.block.bftordering.BftSequencerBaseTest.FakeSigner
import com.digitalasset.canton.synchronizer.sequencer.block.bftordering.core.integration.canton.crypto.CryptoProvider
import com.digitalasset.canton.synchronizer.sequencer.block.bftordering.core.integration.canton.crypto.CryptoProvider.AuthenticatedMessageType
import com.digitalasset.canton.synchronizer.sequencer.block.bftordering.core.integration.canton.topology.TopologyActivationTime
import com.digitalasset.canton.synchronizer.sequencer.block.bftordering.core.modules.consensus.iss.retransmissions.{
  EpochStatusBuilder,
  RetransmissionsManager,
}
import com.digitalasset.canton.synchronizer.sequencer.block.bftordering.core.modules.{
  ProgrammableUnitTestContext,
  ProgrammableUnitTestEnv,
}
import com.digitalasset.canton.synchronizer.sequencer.block.bftordering.framework.ModuleRef
import com.digitalasset.canton.synchronizer.sequencer.block.bftordering.framework.data.BftOrderingIdentifiers.*
import com.digitalasset.canton.synchronizer.sequencer.block.bftordering.framework.data.availability.OrderingBlock
import com.digitalasset.canton.synchronizer.sequencer.block.bftordering.framework.data.bfttime.CanonicalCommitSet
import com.digitalasset.canton.synchronizer.sequencer.block.bftordering.framework.data.ordering.CommitCertificate
import com.digitalasset.canton.synchronizer.sequencer.block.bftordering.framework.data.ordering.iss.{
  BlockMetadata,
  EpochInfo,
}
import com.digitalasset.canton.synchronizer.sequencer.block.bftordering.framework.data.topology.{
  Membership,
  OrderingTopology,
  OrderingTopologyInfo,
}
import com.digitalasset.canton.synchronizer.sequencer.block.bftordering.framework.modules.ConsensusSegment.ConsensusMessage.PrePrepare
import com.digitalasset.canton.synchronizer.sequencer.block.bftordering.framework.modules.ConsensusStatus.BlockStatus
import com.digitalasset.canton.synchronizer.sequencer.block.bftordering.framework.modules.{
  Consensus,
  ConsensusStatus,
  P2PNetworkOut,
}
import com.digitalasset.canton.time.SimClock
import org.scalatest.wordspec.AnyWordSpec
import org.slf4j.event.Level

class RetransmissionsManagerTest extends AnyWordSpec with BftSequencerBaseTest {
  private val self = BftNodeId("self")
  private val other1 = BftNodeId("other1")
  private val others = Set(other1)
  private val allIds: Seq[BftNodeId] = (self +: others.toSeq).sorted

  private val membership = Membership.forTesting(self, others)
  private val epochInfo: EpochInfo = EpochInfo(
    EpochNumber.First,
    BlockNumber.First,
    EpochLength(1),
    TopologyActivationTime(CantonTimestamp.Epoch),
  )
  private val epoch = EpochState.Epoch(
    epochInfo,
    membership,
    membership,
  )

  private def orderingTopologyInfo(
      cryptoProvider: CryptoProvider[ProgrammableUnitTestEnv]
  ): OrderingTopologyInfo[ProgrammableUnitTestEnv] = {
    val topology = OrderingTopology.forTesting(allIds.toSet)
    OrderingTopologyInfo[ProgrammableUnitTestEnv](
      self,
      topology,
      cryptoProvider,
      allIds,
      topology,
      cryptoProvider,
      allIds,
    )
  }

  private val segmentStatus0 = Consensus.RetransmissionsMessage.SegmentStatus(
    EpochNumber.First,
    segmentIndex = 0,
    ConsensusStatus.SegmentStatus.Complete,
  )
  private val segmentStatus1 = Consensus.RetransmissionsMessage.SegmentStatus(
    EpochNumber.First,
    segmentIndex = 1,
    ConsensusStatus.SegmentStatus.Complete,
  )

  private val retransmissionRequest =
    Consensus.RetransmissionsMessage.RetransmissionRequest(
      ConsensusStatus.EpochStatus
        .create(
          self,
          EpochNumber.First,
          Seq(
            ConsensusStatus.SegmentStatus.Complete,
            ConsensusStatus.SegmentStatus.Complete,
          ),
        )
        .fakeSign
    )

  private val validRetransmissionRequest =
    Consensus.RetransmissionsMessage.RetransmissionRequest(
      ConsensusStatus.EpochStatus
        .create(
          self,
          EpochNumber.First,
          Seq(
            ConsensusStatus.SegmentStatus.InProgress(
              ViewNumber.First,
              Seq(BlockStatus.InProgress(prePrepared = false, Seq(false, false), Seq(false, false))),
            )
          ),
        )
        .fakeSign
    )

  private val epochStatus =
    ConsensusStatus.EpochStatus.create(
      other1,
      EpochNumber.First,
      Seq(
        ConsensusStatus.SegmentStatus.InProgress(
          ViewNumber.First,
          Seq(ConsensusStatus.BlockStatus.InProgress(prePrepared = false, Seq.empty, Seq.empty)),
        )
      ),
    )

  private val metrics = SequencerMetrics.noop(getClass.getSimpleName).bftOrdering

  def verifySentRequestNRetransmissionRequests(
      cryptoProvider: CryptoProvider[ProgrammableUnitTestEnv],
      networkOut: ModuleRef[P2PNetworkOut.Message],
      wantedNumberOfInvocations: Int,
  ): Unit = {
    verify(cryptoProvider, times(wantedNumberOfInvocations)).signMessage(
      retransmissionRequest.signedEpochStatus.message,
      AuthenticatedMessageType.BftSignedRetransmissionMessage,
    )
    verify(networkOut, times(wantedNumberOfInvocations)).asyncSend(
      P2PNetworkOut.Multicast(
        P2PNetworkOut.BftOrderingNetworkMessage.RetransmissionMessage(
          retransmissionRequest
        ),
        others,
      )
    )
  }

  "RetransmissionsManager" should {
    "send request upon epoch start" in {
      val networkOut = mock[ModuleRef[P2PNetworkOut.Message]]
      implicit val context
          : ProgrammableUnitTestContext[Consensus.Message[ProgrammableUnitTestEnv]] =
        new ProgrammableUnitTestContext()
      val manager = createManager(networkOut)

      val epochState = mock[EpochState[ProgrammableUnitTestEnv]]

      when(epochState.epoch).thenReturn(epoch)
      when(epochState.requestSegmentStatuses())
        .thenReturn(new EpochStatusBuilder(self, EpochNumber.First, 1 + others.size))

      manager.startEpoch(epochState)

      val cryptoProvider = spy(ProgrammableUnitTestEnv.noSignatureCryptoProvider)

      manager.handleMessage(orderingTopologyInfo(cryptoProvider), segmentStatus0)

      context.runPipedMessages() shouldBe List()
      verifyZeroInteractions(cryptoProvider)

      manager.handleMessage(orderingTopologyInfo(cryptoProvider), segmentStatus1)

      context.runPipedMessages() shouldBe List()

      verifySentRequestNRetransmissionRequests(
        cryptoProvider,
        networkOut,
        wantedNumberOfInvocations = 1,
      )
    }

    "not request retransmissions in a one node topology" in {
      val networkOut = mock[ModuleRef[P2PNetworkOut.Message]]

      val manager = createManager(networkOut)

      val epochState = mock[EpochState[ProgrammableUnitTestEnv]]
      when(epochState.epoch).thenReturn(
        EpochState.Epoch(
          epochInfo,
          Membership.forTesting(self),
          Membership.forTesting(self),
        )
      )

      manager.startEpoch(epochState)

      verify(epochState, never).requestSegmentStatuses()
    }

    "have round robin work across changing memberships" in {
      val other1 = BftNodeId("other1")
      val other2 = BftNodeId("other2")
      val other3 = BftNodeId("other3")
      val membership1 = Membership.forTesting(self, Set(other1, other2))
      val membership2 = Membership.forTesting(self, Set(other1, other2, other3))
      val membership3 = Membership.forTesting(self, Set(other1, other3))
      val soloMembership = Membership.forTesting(self)

      val roundRobin = new RetransmissionsManager.NodeRoundRobin()

      roundRobin.nextNode(membership1) shouldBe (other1)
      roundRobin.nextNode(membership1) shouldBe (other2)
      roundRobin.nextNode(membership1) shouldBe (other1)

      roundRobin.nextNode(membership2) shouldBe (other2)
      roundRobin.nextNode(membership2) shouldBe (other3)

      roundRobin.nextNode(membership3) shouldBe (other1)
      roundRobin.nextNode(membership3) shouldBe (other3)

      // We normally avoid returning self, since we don't want to request from self
      // but in a one node network, this is the only node to return from this method.
      // However, in that case, this method would not be called anyway since retransmissions
      // won't be requested from self.
      roundRobin.nextNode(soloMembership) shouldBe self
    }

    "verify network messages" when {
      "continue process if verification is successful" in {
        val networkOut = mock[ModuleRef[P2PNetworkOut.Message]]
        implicit val context
            : ProgrammableUnitTestContext[Consensus.Message[ProgrammableUnitTestEnv]] =
          new ProgrammableUnitTestContext[Consensus.Message[ProgrammableUnitTestEnv]]()
        val manager = createManager(networkOut)

        val cryptoProvider = mock[CryptoProvider[ProgrammableUnitTestEnv]]

        val message = validRetransmissionRequest
        val epochState = mock[EpochState[ProgrammableUnitTestEnv]]
        when(epochState.epoch).thenReturn(epoch)
        manager.startEpoch(epochState)

        when(
          cryptoProvider.verifySignedMessage(
            message.signedEpochStatus,
            AuthenticatedMessageType.BftSignedRetransmissionMessage,
          )
        ).thenReturn(() => Right(()))

        manager.handleMessage(
          orderingTopologyInfo(cryptoProvider),
          Consensus.RetransmissionsMessage.UnverifiedNetworkMessage(message),
        )

        context.runPipedMessages() shouldBe List(
          Consensus.RetransmissionsMessage.VerifiedNetworkMessage(message)
        )
      }

      "not even check signature if basic validation does not pass" in {
        val networkOut = mock[ModuleRef[P2PNetworkOut.Message]]
        implicit val context
            : ProgrammableUnitTestContext[Consensus.Message[ProgrammableUnitTestEnv]] =
          new ProgrammableUnitTestContext[Consensus.Message[ProgrammableUnitTestEnv]]()
        val manager = createManager(networkOut)

        val cryptoProvider = mock[CryptoProvider[ProgrammableUnitTestEnv]]

        val message = retransmissionRequest

        manager.handleMessage(
          orderingTopologyInfo(cryptoProvider),
          Consensus.RetransmissionsMessage.UnverifiedNetworkMessage(message),
        )

        // manager has not started any epochs yet, so it cannot process the request
        // so we don't even check the signature
        context.runPipedMessages() shouldBe empty
      }
    }

    "drop message if verification failed" in {
      val networkOut = mock[ModuleRef[P2PNetworkOut.Message]]
      implicit val context
          : ProgrammableUnitTestContext[Consensus.Message[ProgrammableUnitTestEnv]] =
        new ProgrammableUnitTestContext[Consensus.Message[ProgrammableUnitTestEnv]]()
      val manager = createManager(networkOut)

      val cryptoProvider = mock[CryptoProvider[ProgrammableUnitTestEnv]]

      val message = validRetransmissionRequest
      val epochState = mock[EpochState[ProgrammableUnitTestEnv]]
      when(epochState.epoch).thenReturn(epoch)
      manager.startEpoch(epochState)

      when(
        cryptoProvider.verifySignedMessage(
          message.signedEpochStatus,
          AuthenticatedMessageType.BftSignedRetransmissionMessage,
        )
      ).thenReturn(() => Left(SignatureCheckError.InvalidKeyError("failed to verify")))

      manager.handleMessage(
        orderingTopologyInfo(cryptoProvider),
        Consensus.RetransmissionsMessage.UnverifiedNetworkMessage(message),
      )

      assertLogs(
        context.runPipedMessages() shouldBe List(),
        logMessage => logMessage.level shouldBe Level.INFO,
      )
    }

    "not send epoch status if epoch ends" in {
      val networkOut = mock[ModuleRef[P2PNetworkOut.Message]]
      implicit val context
          : ProgrammableUnitTestContext[Consensus.Message[ProgrammableUnitTestEnv]] =
        new ProgrammableUnitTestContext()
      val manager = createManager(networkOut)

      val epochState = mock[EpochState[ProgrammableUnitTestEnv]]

      when(epochState.epoch).thenReturn(epoch)
      when(epochState.requestSegmentStatuses())
        .thenReturn(new EpochStatusBuilder(self, EpochNumber.First, 1 + others.size))

      val cryptoProvider = spy(ProgrammableUnitTestEnv.noSignatureCryptoProvider)

      manager.startEpoch(epochState)

      manager.handleMessage(orderingTopologyInfo(cryptoProvider), segmentStatus0)

      // since epoch ended, segment status for the current epoch will not be broadcast
      manager.epochEnded(Seq.empty)

      manager.handleMessage(orderingTopologyInfo(cryptoProvider), segmentStatus1)
      manager.handleMessage(orderingTopologyInfo(cryptoProvider), segmentStatus0)

      context.runPipedMessages() shouldBe List()
      verifyZeroInteractions(cryptoProvider)
      verifyZeroInteractions(networkOut)
    }

    "schedule new status broadcast after finishing one" in {
      val networkOut = mock[ModuleRef[P2PNetworkOut.Message]]
      implicit val context
          : ProgrammableUnitTestContext[Consensus.Message[ProgrammableUnitTestEnv]] =
        new ProgrammableUnitTestContext()
      val manager = createManager(networkOut)

      val epochState = mock[EpochState[ProgrammableUnitTestEnv]]

      when(epochState.epoch).thenReturn(epoch)
      def builder = new EpochStatusBuilder(self, EpochNumber.First, 1 + others.size)
      when(epochState.requestSegmentStatuses())
        .thenReturn(builder, builder) // we'll need 2 fresh builders in this test

      val cryptoProvider = spy(ProgrammableUnitTestEnv.noSignatureCryptoProvider)

      manager.startEpoch(epochState)

      context.lastDelayedMessage shouldBe empty

      manager.handleMessage(orderingTopologyInfo(cryptoProvider), segmentStatus0)
      manager.handleMessage(orderingTopologyInfo(cryptoProvider), segmentStatus1)
      context.runPipedMessages() shouldBe List()

      verifySentRequestNRetransmissionRequests(
        cryptoProvider,
        networkOut,
        wantedNumberOfInvocations = 1,
      )

      val periodicMsg = Consensus.RetransmissionsMessage.PeriodicStatusBroadcast

      context.lastDelayedMessage should contain((1, periodicMsg))

      manager.handleMessage(orderingTopologyInfo(cryptoProvider), periodicMsg)

      manager.handleMessage(orderingTopologyInfo(cryptoProvider), segmentStatus0)

      context.runPipedMessages() shouldBe List()
      verifySentRequestNRetransmissionRequests(
        cryptoProvider,
        networkOut,
        wantedNumberOfInvocations = 1,
      )

      context.lastCancelledEvent shouldBe empty

      manager.handleMessage(orderingTopologyInfo(cryptoProvider), segmentStatus1)

      context.runPipedMessages() shouldBe List()
      verifySentRequestNRetransmissionRequests(
        cryptoProvider,
        networkOut,
        wantedNumberOfInvocations = 2,
      )

      // after completing broadcasting the request, a new broadcast gets scheduled
      context.lastCancelledEvent.map(_._1) should contain(1)
      context.lastDelayedMessage should contain((2, periodicMsg))

      // ending the epoch cancels the scheduling of the next broadcast
      manager.epochEnded(Seq.empty)
      context.lastCancelledEvent.map(_._1) should contain(2)
    }

    "process retransmission request of same epoch using the epoch state" in {
      implicit val context
          : ProgrammableUnitTestContext[Consensus.Message[ProgrammableUnitTestEnv]] =
        new ProgrammableUnitTestContext()

      val epochState = mock[EpochState[ProgrammableUnitTestEnv]]
      when(epochState.epoch).thenReturn(epoch)
      doNothing.when(epochState).processRetransmissionsRequest(epochStatus)

      val cryptoProvider = spy(ProgrammableUnitTestEnv.noSignatureCryptoProvider)
      val manager = createManager(mock[ModuleRef[P2PNetworkOut.Message]])

      manager.startEpoch(epochState)

      manager.handleMessage(
        orderingTopologyInfo(cryptoProvider),
        Consensus.RetransmissionsMessage.VerifiedNetworkMessage(
          Consensus.RetransmissionsMessage.RetransmissionRequest(epochStatus.fakeSign)
        ),
      )

      verify(epochState, times(1)).processRetransmissionsRequest(epochStatus)
    }

    "process retransmission request using past commit certificates if epoch has ended" in {
      implicit val context
          : ProgrammableUnitTestContext[Consensus.Message[ProgrammableUnitTestEnv]] =
        new ProgrammableUnitTestContext()

      val epochState = mock[EpochState[ProgrammableUnitTestEnv]]
      when(epochState.epoch).thenReturn(epoch)

      val cryptoProvider = spy(ProgrammableUnitTestEnv.noSignatureCryptoProvider)
      val networkOut = mock[ModuleRef[P2PNetworkOut.Message]]
      val manager = createManager(networkOut)

      val commitCertificate = CommitCertificate(
        PrePrepare
          .create(
            BlockMetadata.mk(EpochNumber.First, BlockNumber.First),
            ViewNumber.First,
            OrderingBlock(Seq()),
            CanonicalCommitSet.empty,
            from = self,
          )
          .fakeSign,
        Seq.empty,
      )

      manager.startEpoch(epochState)
      manager.epochEnded(Seq(commitCertificate))

      manager.handleMessage(
        orderingTopologyInfo(cryptoProvider),
        Consensus.RetransmissionsMessage.VerifiedNetworkMessage(
          Consensus.RetransmissionsMessage.RetransmissionRequest(epochStatus.fakeSign)
        ),
      )

      context.runPipedMessages() shouldBe empty

      val retransmissionResponse =
        Consensus.RetransmissionsMessage.RetransmissionResponse(
          self,
          Seq(commitCertificate),
        )

      verify(networkOut, times(1)).asyncSend(
        P2PNetworkOut.Multicast(
          P2PNetworkOut.BftOrderingNetworkMessage.RetransmissionMessage(
            retransmissionResponse
          ),
          Set(other1),
        )
      )
    }
  }

  private def createManager(
      networkOut: ModuleRef[P2PNetworkOut.Message]
  ): RetransmissionsManager[ProgrammableUnitTestEnv] = {
    implicit val metricsContext: MetricsContext = MetricsContext.Empty
    new RetransmissionsManager[ProgrammableUnitTestEnv](
      self,
      networkOut,
      fail(_),
      previousEpochsCommitCerts = Map.empty,
      metrics,
      new SimClock(loggerFactory = loggerFactory),
      loggerFactory,
    )
  }
}
