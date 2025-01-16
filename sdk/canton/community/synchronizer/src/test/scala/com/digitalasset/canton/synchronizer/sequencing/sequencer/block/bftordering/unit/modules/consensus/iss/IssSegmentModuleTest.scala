// Copyright (c) 2025 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.canton.synchronizer.sequencing.sequencer.block.bftordering.unit.modules.consensus.iss

import com.daml.metrics.api.MetricsContext
import com.digitalasset.canton.crypto.{Hash, HashAlgorithm, HashPurpose}
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.synchronizer.metrics.SequencerMetrics
import com.digitalasset.canton.synchronizer.sequencing.sequencer.block.bftordering.core.BftSequencerBaseTest.FakeSigner
import com.digitalasset.canton.synchronizer.sequencing.sequencer.block.bftordering.core.modules.consensus.iss.EpochState.Epoch
import com.digitalasset.canton.synchronizer.sequencing.sequencer.block.bftordering.core.modules.consensus.iss.IssConsensusModule.DefaultEpochLength
import com.digitalasset.canton.synchronizer.sequencing.sequencer.block.bftordering.core.modules.consensus.iss.data.EpochStore.EpochInProgress
import com.digitalasset.canton.synchronizer.sequencing.sequencer.block.bftordering.core.modules.consensus.iss.data.Genesis.GenesisEpoch
import com.digitalasset.canton.synchronizer.sequencing.sequencer.block.bftordering.core.modules.consensus.iss.data.{
  EpochStore,
  Genesis,
}
import com.digitalasset.canton.synchronizer.sequencing.sequencer.block.bftordering.core.modules.consensus.iss.leaders.SimpleLeaderSelectionPolicy
import com.digitalasset.canton.synchronizer.sequencing.sequencer.block.bftordering.core.modules.consensus.iss.{
  EpochMetricsAccumulator,
  IssSegmentModule,
  SegmentState,
}
import com.digitalasset.canton.synchronizer.sequencing.sequencer.block.bftordering.fakeSequencerId
import com.digitalasset.canton.synchronizer.sequencing.sequencer.block.bftordering.framework.ModuleRef
import com.digitalasset.canton.synchronizer.sequencing.sequencer.block.bftordering.framework.data.NumberIdentifiers.{
  BlockNumber,
  EpochLength,
  EpochNumber,
  ViewNumber,
}
import com.digitalasset.canton.synchronizer.sequencing.sequencer.block.bftordering.framework.data.SignedMessage
import com.digitalasset.canton.synchronizer.sequencing.sequencer.block.bftordering.framework.data.availability.{
  BatchId,
  OrderingBlock,
  ProofOfAvailability,
}
import com.digitalasset.canton.synchronizer.sequencing.sequencer.block.bftordering.framework.data.bfttime.CanonicalCommitSet
import com.digitalasset.canton.synchronizer.sequencing.sequencer.block.bftordering.framework.data.ordering.iss.{
  BlockMetadata,
  EpochInfo,
}
import com.digitalasset.canton.synchronizer.sequencing.sequencer.block.bftordering.framework.data.ordering.{
  CommitCertificate,
  ConsensusCertificate,
  OrderedBlock,
  PrepareCertificate,
}
import com.digitalasset.canton.synchronizer.sequencing.sequencer.block.bftordering.framework.data.topology.{
  Membership,
  OrderingTopology,
}
import com.digitalasset.canton.synchronizer.sequencing.sequencer.block.bftordering.framework.modules.Consensus.CatchUpMessage.SegmentCancelledEpoch
import com.digitalasset.canton.synchronizer.sequencing.sequencer.block.bftordering.framework.modules.Consensus.ConsensusMessage.SegmentCompletedEpoch
import com.digitalasset.canton.synchronizer.sequencing.sequencer.block.bftordering.framework.modules.ConsensusSegment.ConsensusMessage.*
import com.digitalasset.canton.synchronizer.sequencing.sequencer.block.bftordering.framework.modules.{
  Availability,
  Consensus,
  ConsensusSegment,
  P2PNetworkOut,
}
import com.digitalasset.canton.synchronizer.sequencing.sequencer.block.bftordering.unit.modules.*
import com.digitalasset.canton.synchronizer.sequencing.sequencer.block.bftordering.unit.modules.UnitTestContext.DelayCount
import com.digitalasset.canton.time.SimClock
import com.digitalasset.canton.topology.SequencerId
import com.digitalasset.canton.{BaseTest, HasExecutionContext}
import com.google.protobuf.ByteString
import org.scalatest.wordspec.AsyncWordSpec

import java.util.concurrent.atomic.AtomicReference
import scala.collection.mutable.ArrayBuffer

class IssSegmentModuleTest extends AsyncWordSpec with BaseTest with HasExecutionContext {

  import IssSegmentModuleTest.*

  private val clock = new SimClock(loggerFactory = loggerFactory)
  private val cancelCell =
    new AtomicReference[Option[(DelayCount, ConsensusSegment.Message)]](None)
  implicit val context: FakeTimerCellUnitTestContext[ConsensusSegment.Message] =
    FakeTimerCellUnitTestContext(cancelCell)

  // Common val for 1-node networks in tests below
  private val block0Metadata1Node = BlockMetadata.mk(EpochNumber.First, BlockNumber.First)
  private val block0PrePrepare1Node = PrePrepare
    .create(
      block0Metadata1Node,
      ViewNumber.First,
      clock.now,
      OrderingBlock.empty,
      CanonicalCommitSet.empty,
      selfId,
    )
    .fakeSign
  private val expectedBlock0Prepare1Node =
    prepareFromPrePrepare(block0PrePrepare1Node.message)(from = selfId)
  private val expectedBlock0Commit1Node =
    commitFromPrePrepare(block0PrePrepare1Node.message)(from = selfId)
  private val expectedOrderedBlock0For1Node = orderedBlockFromPrePrepare(
    block0PrePrepare1Node.message
  )

  private val SecondEpochNumber = EpochNumber(EpochNumber.First + 1)
  private val SecondEpochInfo = EpochInfo(
    SecondEpochNumber,
    BlockNumber(10L),
    DefaultEpochLength,
    Genesis.GenesisTopologyActivationTime,
  )
  private val block9Commits1Node = Seq(
    Commit
      .create(
        BlockMetadata.mk(SecondEpochNumber, BlockNumber(9L)),
        ViewNumber.First,
        Hash.digest(HashPurpose.BftOrderingPbftBlock, ByteString.EMPTY, HashAlgorithm.Sha256),
        CantonTimestamp.MaxValue,
        selfId,
      )
      .fakeSign
  )
  private val block10Metadata1Node = BlockMetadata.mk(SecondEpochNumber, BlockNumber(10L))
  private val block10PrePrepare1Node = PrePrepare
    .create(
      block10Metadata1Node,
      ViewNumber.First,
      clock.now,
      OrderingBlock(oneRequestOrderingBlock.proofs),
      CanonicalCommitSet(block9Commits1Node.toSet),
      selfId,
    )
    .fakeSign
  private val expectedBlock10Prepare1Node =
    prepareFromPrePrepare(block10PrePrepare1Node.message)(from = selfId)
  private val expectedBlock10Commit1Node =
    commitFromPrePrepare(block10PrePrepare1Node.message)(from = selfId)
  private val expectedOrderedBlock10For1Node = orderedBlockFromPrePrepare(
    block10PrePrepare1Node.message
  )

  private val SecondViewNumber = ViewNumber(ViewNumber.First + 1)
  private val bottomBlock0 =
    bottomBlock(
      BlockMetadata.mk(SecondEpochNumber, BlockNumber(10L)),
      SecondViewNumber,
      clock.now,
      from = selfId,
    )
  private val bottomBlock1 =
    bottomBlock(
      BlockMetadata.mk(SecondEpochNumber, 11L),
      SecondViewNumber,
      clock.now,
      from = selfId,
    )
  private val bottomBlock2 =
    bottomBlock(
      BlockMetadata.mk(SecondEpochNumber, 12L),
      SecondViewNumber,
      clock.now,
      from = selfId,
    )
  private val prepareBottomBlock0 = prepareFromPrePrepare(bottomBlock0.message)(from = selfId)
  private val commitBottomBlock0 = commitFromPrePrepare(bottomBlock0.message)(from = selfId)
  private val viewChange1Node1BlockNoProgress = ViewChange
    .create(
      block10Metadata1Node,
      segmentIndex = 0,
      SecondViewNumber,
      clock.now,
      consensusCerts = Seq.empty,
      from = selfId,
    )
    .fakeSign
  private val newView1Node1BlockNoProgress = NewView.create(
    block10Metadata1Node,
    segmentIndex = 0,
    SecondViewNumber,
    clock.now,
    viewChanges = Seq(viewChange1Node1BlockNoProgress),
    prePrepares = Seq(bottomBlock0),
    from = selfId,
  )

  // Common val for 4-node networks in tests below
  private val blockOrder4Nodes: Seq[SequencerId] =
    Iterator.continually(allPeers).flatten.take(DefaultEpochLength.toInt).toSeq
  private val blockMetadata4Nodes = blockOrder4Nodes.zipWithIndex.map { case (_, blockNum) =>
    BlockMetadata.mk(SecondEpochNumber, DefaultEpochLength + blockNum.toLong)
  }

  "IssSegmentModule" when {

    "receiving an epoch completion message" should {
      "close and notify the parent" in {
        implicit val context: ProgrammableUnitTestContext[ConsensusSegment.Message] =
          new ProgrammableUnitTestContext
        val parentCell =
          new AtomicReference[Option[Consensus.Message[ProgrammableUnitTestEnv]]](None)
        val consensus = createIssSegmentModule[ProgrammableUnitTestEnv](parentModuleRef =
          fakeCellModule(parentCell)
        )()

        // Upon receiving an EpochCompletion message, the module should simply close
        consensus.receive(CompletedEpoch(EpochNumber.First))

        context.runCloseAction()

        parentCell.get() shouldBe Some(SegmentCompletedEpoch(BlockNumber.First, EpochNumber.First))
      }
    }

    "receiving an epoch cancellation message" should {
      "close and notify the parent" in {
        implicit val context: ProgrammableUnitTestContext[ConsensusSegment.Message] =
          new ProgrammableUnitTestContext
        val parentCell =
          new AtomicReference[Option[Consensus.Message[ProgrammableUnitTestEnv]]](None)
        val consensus = createIssSegmentModule[ProgrammableUnitTestEnv](parentModuleRef =
          fakeCellModule(parentCell)
        )()

        // Upon receiving an EpochCompletion message, the module should simply close
        consensus.receive(CancelEpoch(EpochNumber.First))

        context.runCloseAction()

        parentCell.get() shouldBe Some(SegmentCancelledEpoch)
      }
    }

    "started via explicit signal" should {

      "sequence the first local segment's block in a 1-node network" in {
        context.reset()
        val availabilityCell =
          new AtomicReference[Option[Availability.Message[FakeTimerCellUnitTestEnv]]](None)
        val p2pBuffer = new ArrayBuffer[P2PNetworkOut.Message](defaultBufferSize)
        val parentCell =
          new AtomicReference[Option[Consensus.Message[FakeTimerCellUnitTestEnv]]](None)
        val consensus = createIssSegmentModule[FakeTimerCellUnitTestEnv](
          availabilityModuleRef = fakeCellModule(availabilityCell),
          parentModuleRef = fakeCellModule(parentCell),
          p2pNetworkOutModuleRef = fakeRecordingModule(p2pBuffer),
        )()
        cancelCell.get() shouldBe empty

        // Upon receiving a Start signal, Consensus should start ordering an empty block in the local segment.
        //  For a 1-node network, the block immediately reaches the `committed` state, and Consensus produces
        //  a PrePrepare and Commit for the P2PNetworkOut module
        consensus.receive(ConsensusSegment.Start)
        consensus.receive(
          ConsensusSegment.ConsensusMessage.BlockProposal(OrderingBlock.empty, EpochNumber.First)
        )
        consensus.receive(
          ConsensusSegment.ConsensusMessage.PrePrepareStored(block0Metadata1Node, ViewNumber.First)
        )
        consensus.receive(
          ConsensusSegment.ConsensusMessage.PreparesStored(block0Metadata1Node, ViewNumber.First)
        )
        p2pBuffer should contain theSameElementsInOrderAs Seq[P2PNetworkOut.Message](
          P2PNetworkOut.Multicast(
            P2PNetworkOut.BftOrderingNetworkMessage.ConsensusMessage(
              block0PrePrepare1Node
            ),
            Set.empty,
          ),
          P2PNetworkOut.Multicast(
            P2PNetworkOut.BftOrderingNetworkMessage.ConsensusMessage(
              expectedBlock0Prepare1Node
            ),
            Set.empty,
          ),
          P2PNetworkOut.Multicast(
            P2PNetworkOut.BftOrderingNetworkMessage.ConsensusMessage(
              expectedBlock0Commit1Node
            ),
            Set.empty,
          ),
        )

        // Upon receiving storage confirmation from the consensus store, Consensus should forward
        //  the orderedBlock to the Output module, and request a new proposal from Availability, since
        //  there are still more slots to assign in the local Segment for this epoch
        val orderedBlockStored = ConsensusSegment.Internal.OrderedBlockStored(
          expectedOrderedBlock0For1Node,
          Seq(expectedBlock0Commit1Node),
          ViewNumber.First,
        )
        consensus.receive(orderedBlockStored)
        parentCell.get() shouldBe defined
        parentCell.get().foreach { msg =>
          msg shouldBe Consensus.ConsensusMessage.BlockOrdered(
            expectedOrderedBlock0For1Node,
            Seq(expectedBlock0Commit1Node),
          )
        }
        inside(availabilityCell.get()) {
          case Some(Availability.Consensus.CreateProposal(o, _, e, ackO)) =>
            o.peers shouldBe Set(selfId)
            e shouldBe EpochNumber.First
            ackO shouldBe None
        }
        cancelCell.get() should matchPattern { case Some((2, _: PbftNormalTimeout)) => }
      }

      "sequence a local segment's block from the second epoch in a 1-node network" in {
        context.reset()
        val availabilityCell =
          new AtomicReference[Option[Availability.Message[FakeTimerCellUnitTestEnv]]](None)
        val p2pBuffer = new ArrayBuffer[P2PNetworkOut.Message](defaultBufferSize)
        val parentCell =
          new AtomicReference[Option[Consensus.Message[FakeTimerCellUnitTestEnv]]](None)
        val consensus = createIssSegmentModule[FakeTimerCellUnitTestEnv](
          availabilityModuleRef = fakeCellModule(availabilityCell),
          parentModuleRef = fakeCellModule(parentCell),
          p2pNetworkOutModuleRef = fakeRecordingModule(p2pBuffer),
          latestCompletedEpochLastCommits = block9Commits1Node,
        )(epochInfo = SecondEpochInfo)
        cancelCell.get() shouldBe empty

        // Upon receiving a Start signal (in a non-first epoch), Consensus should ask for a Proposal from Availability
        consensus.receive(ConsensusSegment.Start)
        inside(availabilityCell.get()) {
          case Some(Availability.Consensus.CreateProposal(o, _, e, ackO)) =>
            o.peers shouldBe Set(selfId)
            e shouldBe SecondEpochNumber
            ackO shouldBe None
        }
        availabilityCell.set(None)
        cancelCell.get() should matchPattern { case Some((1, _: PbftNormalTimeout)) => }

        // Upon receiving a proposal, Consensus should start ordering a block in the local segment.
        //   For a 1-node network, the block immediately reaches the `committed` state, and
        //   Consensus produces a PrePrepare and Commit for the P2PNetworkOut module
        consensus.receive(
          ConsensusSegment.ConsensusMessage
            .BlockProposal(oneRequestOrderingBlock, SecondEpochNumber)
        )
        consensus.receive(
          ConsensusSegment.ConsensusMessage.PrePrepareStored(block10Metadata1Node, ViewNumber.First)
        )
        consensus.receive(
          ConsensusSegment.ConsensusMessage.PreparesStored(block10Metadata1Node, ViewNumber.First)
        )
        p2pBuffer should contain theSameElementsInOrderAs Seq[P2PNetworkOut.Message](
          P2PNetworkOut.Multicast(
            P2PNetworkOut.BftOrderingNetworkMessage.ConsensusMessage(
              block10PrePrepare1Node
            ),
            Set.empty,
          ),
          P2PNetworkOut.Multicast(
            P2PNetworkOut.BftOrderingNetworkMessage.ConsensusMessage(
              expectedBlock10Prepare1Node
            ),
            Set.empty,
          ),
          P2PNetworkOut.Multicast(
            P2PNetworkOut.BftOrderingNetworkMessage.ConsensusMessage(
              expectedBlock10Commit1Node
            ),
            Set.empty,
          ),
        )

        // Upon receiving storage confirmation from the consensus store, Consensus should forward
        // the orderedBlock to the Output module, and request a new proposal from Availability, since
        // there are still more slots to assign in the local Segment for this epoch
        val orderedBlockStored = ConsensusSegment.Internal.OrderedBlockStored(
          expectedOrderedBlock10For1Node,
          Seq(expectedBlock10Commit1Node),
          ViewNumber.First,
        )
        consensus.receive(orderedBlockStored)
        parentCell.get() shouldBe defined
        parentCell.get().foreach { msg =>
          msg shouldBe Consensus.ConsensusMessage.BlockOrdered(
            expectedOrderedBlock10For1Node,
            Seq(expectedBlock10Commit1Node),
          )
        }
        inside(availabilityCell.get()) {
          case Some(Availability.Consensus.CreateProposal(o, _, e, ackO)) =>
            o.peers shouldBe Set(selfId)
            e shouldBe SecondEpochNumber
            ackO shouldBe Some(Availability.Consensus.Ack(Seq(aBatchId)))
        }
        cancelCell.get() should matchPattern { case Some((2, _: PbftNormalTimeout)) => }
      }

      // Same test scenario as 1-node network above, but with a 4-node network and mocked peer votes
      "sequence a local segment's block in a 4-node network" in {
        context.reset()
        val availabilityBuffer =
          new ArrayBuffer[Availability.Message[FakeTimerCellUnitTestEnv]](defaultBufferSize)
        val availabilityRef = fakeRecordingModule(availabilityBuffer)
        val parentBuffer =
          new ArrayBuffer[Consensus.Message[FakeTimerCellUnitTestEnv]](defaultBufferSize)
        val parentRef = fakeRecordingModule(parentBuffer)
        val p2pBuffer = new ArrayBuffer[P2PNetworkOut.Message](defaultBufferSize)
        val p2pNetworkRef = fakeRecordingModule(p2pBuffer)

        val consensus = createIssSegmentModule[FakeTimerCellUnitTestEnv](
          availabilityModuleRef = availabilityRef,
          parentModuleRef = parentRef,
          p2pNetworkOutModuleRef = p2pNetworkRef,
          otherPeers = otherPeers.toSet,
        )(epochInfo = SecondEpochInfo)

        // Consensus.Start message from Network module(s) should trigger request for proposal
        consensus.receive(ConsensusSegment.Start)
        inside(availabilityBuffer.toSeq) {
          case Seq(Availability.Consensus.CreateProposal(t, _, e, ackO)) =>
            t shouldBe fullTopology
            e shouldBe SecondEpochNumber
            ackO shouldBe None
        }
        availabilityBuffer.clear()
        cancelCell.get() should matchPattern { case Some((1, _: PbftNormalTimeout)) => }

        // Proposal from Availability should trigger PrePrepare and Prepare sent for first block in local segment
        consensus.receive(
          ConsensusSegment.ConsensusMessage
            .BlockProposal(oneRequestOrderingBlock, SecondEpochNumber)
        )
        val blockMetadata = blockMetadata4Nodes(blockOrder4Nodes.indexOf(selfId))
        val expectedPrePrepare = PrePrepare.create(
          blockMetadata,
          ViewNumber.First,
          clock.now,
          OrderingBlock(oneRequestOrderingBlock.proofs),
          CanonicalCommitSet.empty,
          selfId,
        )
        def basePrepare(from: SequencerId) = prepareFromPrePrepare(expectedPrePrepare)(from = from)
        def baseCommit(from: SequencerId) = commitFromPrePrepare(expectedPrePrepare)(from = from)
        p2pBuffer should contain theSameElementsInOrderAs Seq[P2PNetworkOut.Message](
          P2PNetworkOut.Multicast(
            P2PNetworkOut.BftOrderingNetworkMessage.ConsensusMessage(expectedPrePrepare.fakeSign),
            otherPeers.toSet,
          ),
          P2PNetworkOut.Multicast(
            P2PNetworkOut.BftOrderingNetworkMessage.ConsensusMessage(
              basePrepare(from = selfId)
            ),
            otherPeers.toSet,
          ),
        )
        p2pBuffer.clear()

        // Prepares from follower peers should trigger sending Commit for the first block
        consensus.receive(
          ConsensusSegment.ConsensusMessage.PrePrepareStored(blockMetadata, ViewNumber.First)
        )
        consensus.receive(PbftSignedNetworkMessage(basePrepare(from = otherPeers(0))))
        consensus.receive(PbftSignedNetworkMessage(basePrepare(from = otherPeers(1))))
        p2pBuffer should contain theSameElementsInOrderAs Seq[P2PNetworkOut.Message](
          P2PNetworkOut.Multicast(
            P2PNetworkOut.BftOrderingNetworkMessage.ConsensusMessage(
              baseCommit(from = selfId)
            ),
            otherPeers.toSet,
          )
        )
        p2pBuffer.clear()

        // Commits from follower peers and confirmation of block stored should complete the block,
        //   forward the block to Output, and request a new proposal from Availability
        consensus.receive(
          ConsensusSegment.ConsensusMessage.PreparesStored(blockMetadata, ViewNumber.First)
        )
        consensus.receive(PbftSignedNetworkMessage(baseCommit(from = otherPeers(0))))
        consensus.receive(PbftSignedNetworkMessage(baseCommit(from = otherPeers(1))))
        val expectedOrderedBlock = orderedBlockFromPrePrepare(expectedPrePrepare)
        val orderedBlockStored = ConsensusSegment.Internal.OrderedBlockStored(
          expectedOrderedBlock,
          Seq(baseCommit(fakeSequencerId("toBeReplaced"))),
          ViewNumber.First,
        )
        consensus.receive(orderedBlockStored)
        parentBuffer should contain only Consensus.ConsensusMessage.BlockOrdered(
          expectedOrderedBlock,
          Seq(baseCommit(fakeSequencerId("toBeReplaced"))),
        )

        inside(availabilityBuffer.toSeq) {
          case Seq(Availability.Consensus.CreateProposal(t, _, e, ackO)) =>
            t shouldBe fullTopology
            e shouldBe SecondEpochNumber
            ackO shouldBe Some(Availability.Consensus.Ack(Seq(aBatchId)))
        }
        cancelCell.get() should matchPattern { case Some((2, _: PbftNormalTimeout)) => }
      }

      "sequence another peer's segment block in a 4-node network" in {
        context.reset()
        val availabilityBuffer =
          new ArrayBuffer[Availability.Message[FakeTimerCellUnitTestEnv]](defaultBufferSize)
        val availabilityRef = fakeRecordingModule(availabilityBuffer)
        val parentBuffer =
          new ArrayBuffer[Consensus.Message[FakeTimerCellUnitTestEnv]](defaultBufferSize)
        val parentRef = fakeRecordingModule(parentBuffer)
        val p2pBuffer = new ArrayBuffer[P2PNetworkOut.Message](defaultBufferSize)
        val p2pNetworkRef = fakeRecordingModule(p2pBuffer)
        val remotePeer = otherPeers(0)

        val consensus = createIssSegmentModule[FakeTimerCellUnitTestEnv](
          availabilityModuleRef = availabilityRef,
          parentModuleRef = parentRef,
          p2pNetworkOutModuleRef = p2pNetworkRef,
          otherPeers = otherPeers.toSet,
          leader = remotePeer,
        )(epochInfo = SecondEpochInfo)

        // Consensus.Start message from Network module(s) triggers request for proposal, but this
        // test focuses on another peer's segment, so we simply clear the buffer and ignore
        consensus.receive(ConsensusSegment.Start)
        availabilityBuffer.clear()
        cancelCell.get() should matchPattern { case Some((1, _: PbftNormalTimeout)) => }

        // Mock a PrePrepare coming from another node; upon receipt, should trigger Prepare multicast
        val blockMetadata = blockMetadata4Nodes(blockOrder4Nodes.indexOf(remotePeer))
        val remotePrePrepare = PrePrepare
          .create(
            blockMetadata,
            ViewNumber.First,
            clock.now,
            OrderingBlock(oneRequestOrderingBlock.proofs),
            CanonicalCommitSet.empty,
            remotePeer,
          )
          .fakeSign
        def basePrepare(from: SequencerId) =
          prepareFromPrePrepare(remotePrePrepare.message)(from = from)
        def baseCommit(from: SequencerId = remotePeer) =
          commitFromPrePrepare(remotePrePrepare.message)(from = from)
        consensus.receive(PbftSignedNetworkMessage(remotePrePrepare))
        p2pBuffer should contain theSameElementsInOrderAs Seq[P2PNetworkOut.Message](
          P2PNetworkOut.Multicast(
            P2PNetworkOut.BftOrderingNetworkMessage.ConsensusMessage(
              basePrepare(from = selfId)
            ),
            otherPeers.toSet,
          )
        )
        p2pBuffer.clear()

        // Upon receiving enough other Prepares, module should multicast a Commit
        consensus.receive(
          ConsensusSegment.ConsensusMessage.PrePrepareStored(blockMetadata, ViewNumber.First)
        )
        consensus.receive(PbftSignedNetworkMessage(basePrepare(from = remotePeer)))
        consensus.receive(PbftSignedNetworkMessage(basePrepare(from = otherPeers(1))))
        p2pBuffer should contain theSameElementsInOrderAs Seq[P2PNetworkOut.Message](
          P2PNetworkOut.Multicast(
            P2PNetworkOut.BftOrderingNetworkMessage.ConsensusMessage(
              baseCommit(from = selfId)
            ),
            otherPeers.toSet,
          )
        )
        p2pBuffer.clear()

        // Upon receiving enough other Commits, and upon confirming block storage, block should be
        // complete and forward to Output
        consensus.receive(
          ConsensusSegment.ConsensusMessage.PreparesStored(blockMetadata, ViewNumber.First)
        )
        consensus.receive(PbftSignedNetworkMessage(baseCommit(from = otherPeers(0))))
        consensus.receive(PbftSignedNetworkMessage(baseCommit(from = otherPeers(1))))
        val expectedOrderedBlock = orderedBlockFromPrePrepare(remotePrePrepare.message)
        val commits = Seq(
          baseCommit(),
          baseCommit(from = otherPeers(0)),
          baseCommit(from = otherPeers(1)),
        )
        consensus.receive(
          ConsensusSegment.Internal.OrderedBlockStored(
            expectedOrderedBlock,
            commits,
            ViewNumber.First,
          )
        )
        parentBuffer should contain only Consensus.ConsensusMessage.BlockOrdered(
          expectedOrderedBlock,
          commits,
        )

        // And in this test, NO additional proposal should be requested, as only a remote segment made progress
        availabilityBuffer shouldBe empty
        cancelCell.get() should matchPattern { case Some((2, _: PbftNormalTimeout)) => }
      }

      "start and complete a view change in a 1-node network" in {
        context.reset()
        val availabilityBuffer =
          new ArrayBuffer[Availability.Message[FakeTimerCellUnitTestEnv]](defaultBufferSize)
        val availabilityRef = fakeRecordingModule(availabilityBuffer)
        val parentBuffer =
          new ArrayBuffer[Consensus.Message[FakeTimerCellUnitTestEnv]](defaultBufferSize)
        val parentRef = fakeRecordingModule(parentBuffer)
        val p2pBuffer = new ArrayBuffer[P2PNetworkOut.Message](defaultBufferSize)
        val p2pNetworkRef = fakeRecordingModule(p2pBuffer)
        val epochLength = EpochLength(3L)
        val consensus =
          createIssSegmentModule[FakeTimerCellUnitTestEnv](
            availabilityModuleRef = availabilityRef,
            parentModuleRef = parentRef,
            p2pNetworkOutModuleRef = p2pNetworkRef,
            latestCompletedEpochLastCommits = block9Commits1Node,
            epochLength = epochLength,
          )(epochInfo = SecondEpochInfo.copy(length = epochLength))

        // Initially, there are no delayedEvents
        cancelCell.get() shouldBe empty

        // Upon receiving a Start signal, Consensus should ask for a Proposal from Availability
        consensus.receive(ConsensusSegment.Start)
        availabilityBuffer.clear()
        // delayCount should be 1, we're waiting for the first block to be ordered
        delayCount(cancelCell) shouldBe 1

        // Complete the first block, but leave the 2nd and 3rd block incomplete (not started)
        consensus.receive(
          ConsensusSegment.ConsensusMessage
            .BlockProposal(oneRequestOrderingBlock, SecondEpochNumber)
        )
        consensus.receive(
          ConsensusSegment.ConsensusMessage.PrePrepareStored(block10Metadata1Node, ViewNumber.First)
        )
        consensus.receive(
          ConsensusSegment.ConsensusMessage.PreparesStored(block10Metadata1Node, ViewNumber.First)
        )
        consensus.receive(
          ConsensusSegment.Internal.OrderedBlockStored(
            expectedOrderedBlock10For1Node,
            Seq(expectedBlock10Commit1Node),
            ViewNumber.First,
          )
        )
        parentBuffer should contain only Consensus.ConsensusMessage.BlockOrdered(
          expectedOrderedBlock10For1Node,
          Seq(expectedBlock10Commit1Node),
        )

        inside(availabilityBuffer.toSeq) {
          case Seq(Availability.Consensus.CreateProposal(t, _, e, ackO)) =>
            t.peers shouldBe Set(selfId)
            e shouldBe SecondEpochNumber
            ackO shouldBe Some(Availability.Consensus.Ack(Seq(aBatchId)))
        }
        p2pBuffer.clear()
        availabilityBuffer.clear()
        parentBuffer.clear()
        // delayCount should be 2; we're waiting for the second block to be ordered
        delayCount(cancelCell) shouldBe 2

        // With Consensus waiting for a proposal for block 2, simulate a view change timeout
        consensus.receive(
          ConsensusSegment.ConsensusMessage
            .PbftNormalTimeout(block10Metadata1Node, ViewNumber.First)
        )
        val nextView = SecondViewNumber
        val expectedViewChange = ViewChange
          .create(
            block10Metadata1Node,
            segmentIndex = 0,
            viewNumber = nextView,
            clock.now,
            consensusCerts = Seq(
              CommitCertificate(
                block10PrePrepare1Node,
                Seq(commitFromPrePrepare(block10PrePrepare1Node.message)(from = selfId)),
              )
            ),
            from = selfId,
          )
          .fakeSign
        val expectedNewView = NewView
          .create(
            block10Metadata1Node,
            segmentIndex = 0,
            viewNumber = nextView,
            clock.now,
            viewChanges = Seq(expectedViewChange),
            prePrepares = Seq(block10PrePrepare1Node, bottomBlock1, bottomBlock2),
            from = selfId,
          )
          .fakeSign
        // View Change should complete immediately in a 1-node network, resulting in:
        //    ViewChange, NewView, and Commit for each of the incomplete 2 blocks in the segment
        //      - block1 and block2: bottom blocks
        p2pBuffer should contain theSameElementsInOrderAs Seq[P2PNetworkOut.Message](
          P2PNetworkOut.Multicast(
            P2PNetworkOut.BftOrderingNetworkMessage.ConsensusMessage(expectedViewChange),
            Set.empty,
          ),
          P2PNetworkOut.Multicast(
            P2PNetworkOut.BftOrderingNetworkMessage.ConsensusMessage(expectedNewView),
            Set.empty,
          ),
          P2PNetworkOut.Multicast(
            P2PNetworkOut.BftOrderingNetworkMessage.ConsensusMessage(
              prepareFromPrePrepare(bottomBlock1.message)(from = selfId)
            ),
            Set.empty,
          ),
          P2PNetworkOut.Multicast(
            P2PNetworkOut.BftOrderingNetworkMessage.ConsensusMessage(
              prepareFromPrePrepare(bottomBlock2.message)(from = selfId)
            ),
            Set.empty,
          ),
        )
        p2pBuffer.clear()
        // delayCount should be 4:
        //   - delayCount=3 for nested view change timer
        //   - delayCount=4 when view change completed; expecting consensus to resume on incomplete blocks
        delayCount(cancelCell) shouldBe 4

        consensus.receive(
          ConsensusSegment.ConsensusMessage
            .NewViewStored(BlockMetadata.mk(EpochNumber.First, BlockNumber.First), nextView)
        )

        p2pBuffer should contain theSameElementsInOrderAs Seq[P2PNetworkOut.Message](
          P2PNetworkOut.Multicast(
            P2PNetworkOut.BftOrderingNetworkMessage.ConsensusMessage(
              commitFromPrePrepare(bottomBlock1.message)(from = selfId)
            ),
            Set.empty,
          ),
          P2PNetworkOut.Multicast(
            P2PNetworkOut.BftOrderingNetworkMessage.ConsensusMessage(
              commitFromPrePrepare(bottomBlock2.message)(from = selfId)
            ),
            Set.empty,
          ),
        )
        p2pBuffer.clear()

        // Following the view change, need to confirm storage of last 2 blocks, which will then forward to Output
        // To keep things interesting, we confirm them out of order, as this can happen with DB in practice
        consensus.receive(
          ConsensusSegment.ConsensusMessage
            .PrePrepareStored(bottomBlock2.message.blockMetadata, nextView)
        )
        consensus.receive(
          ConsensusSegment.ConsensusMessage
            .PreparesStored(bottomBlock2.message.blockMetadata, nextView)
        )
        consensus.receive(
          ConsensusSegment.Internal.OrderedBlockStored(
            orderedBlockFromPrePrepare(bottomBlock2.message),
            Seq(commitFromPrePrepare(bottomBlock2.message)(from = selfId)),
            nextView,
          )
        )
        // after each non-final segment block confirmation, expect delayCount to advance; here, it should be 5
        delayCount(cancelCell) shouldBe 5

        consensus.receive(
          ConsensusSegment.ConsensusMessage
            .PreparesStored(bottomBlock1.message.blockMetadata, nextView)
        )
        consensus.receive(
          ConsensusSegment.Internal.OrderedBlockStored(
            orderedBlockFromPrePrepare(bottomBlock1.message),
            Seq(commitFromPrePrepare(bottomBlock1.message)(from = selfId)),
            nextView,
          )
        )
        // since this was the last block in the epoch; no additional delayedEvent occurs
        delayCount(cancelCell) shouldBe 5

        parentBuffer should contain theSameElementsInOrderAs Seq(
          Consensus.ConsensusMessage.BlockOrdered(
            orderedBlockFromPrePrepare(bottomBlock2.message),
            Seq(commitFromPrePrepare(bottomBlock2.message)(from = selfId)),
          ),
          Consensus.ConsensusMessage.BlockOrdered(
            orderedBlockFromPrePrepare(bottomBlock1.message),
            Seq(commitFromPrePrepare(bottomBlock1.message)(from = selfId)),
          ),
        )

        parentBuffer.clear()

        // Despite completing blocks, no initiatePull is sent to Availability because the view change
        // occurred before the blocks were completed, and moreSlotsToAssign will return false when
        // completing blocks with segment.view > 0
        availabilityBuffer shouldBe empty
      }

      "schedule the nested view change timeout during a view change attempt" in {
        context.reset()
        val parentBuffer =
          new ArrayBuffer[Consensus.Message[FakeTimerCellUnitTestEnv]](defaultBufferSize)
        val parentRef = fakeRecordingModule(parentBuffer)
        val p2pBuffer = new ArrayBuffer[P2PNetworkOut.Message](defaultBufferSize)
        val p2pNetworkRef = fakeRecordingModule(p2pBuffer)

        val consensus = createIssSegmentModule[FakeTimerCellUnitTestEnv](
          parentModuleRef = parentRef,
          p2pNetworkOutModuleRef = p2pNetworkRef,
          otherPeers = otherPeers.toSet,
        )(epochInfo = SecondEpochInfo)

        // Consensus.Start message from Network module(s) triggers an empty block ordering
        consensus.receive(ConsensusSegment.Start)
        p2pBuffer.clear()
        cancelCell.get() shouldBe defined

        // Start first view change (from view0 to view1) for local segment
        val blockMetadata = blockMetadata4Nodes(blockOrder4Nodes.indexOf(selfId))
        consensus.receive(
          ConsensusSegment.ConsensusMessage.PbftNormalTimeout(blockMetadata, ViewNumber.First)
        )
        val nextView = SecondViewNumber
        def expectedViewChange(from: SequencerId = selfId) = ViewChange
          .create(
            blockMetadata,
            segmentIndex = blockOrder4Nodes.indexOf(selfId),
            viewNumber = nextView,
            clock.now,
            consensusCerts = Seq.empty,
            from = from,
          )
          .fakeSign

        // Save the number of delayedEvents (delayCount) so far for comparison with nested view change
        val delayCountAtStartOfViewChange = delayCount(cancelCell)

        // After the local timeout, we expect a multicasted ViewChange msg from local node
        p2pBuffer should contain theSameElementsInOrderAs Seq[P2PNetworkOut.Message](
          P2PNetworkOut.Multicast(
            P2PNetworkOut.BftOrderingNetworkMessage.ConsensusMessage(expectedViewChange()),
            otherPeers.toSet,
          )
        )
        p2pBuffer.clear()
        // Before reaching 2f+1 total view change messages, no additional delayedEvents yet
        delayCount(cancelCell) shouldBe delayCountAtStartOfViewChange
        cancelCell.get() should matchPattern { case Some((_, _: PbftNormalTimeout)) => }

        // Next, simulate receiving additional view change message to reach 2f+1 total
        // This view change message is still one short of the strong quorum
        consensus.receive(PbftSignedNetworkMessage(expectedViewChange(from = otherPeers(0))))
        p2pBuffer shouldBe empty
        delayCount(cancelCell) shouldBe delayCountAtStartOfViewChange

        // This view change message reaches strong quorum; now we expect nested timer to be set
        consensus.receive(PbftSignedNetworkMessage(expectedViewChange(from = otherPeers(1))))
        p2pBuffer shouldBe empty
        delayCount(cancelCell) should be > delayCountAtStartOfViewChange
        cancelCell.get() should matchPattern { case Some((_, _: PbftNestedViewChangeTimeout)) => }
      }

      "ignore proposals that arrive after view change" in {
        val availabilityBuffer =
          new ArrayBuffer[Availability.Message[FakeTimerCellUnitTestEnv]](defaultBufferSize)
        val parentBuffer =
          new ArrayBuffer[Consensus.Message[FakeTimerCellUnitTestEnv]](defaultBufferSize)
        val p2pBuffer = new ArrayBuffer[P2PNetworkOut.Message](defaultBufferSize)
        val epochLength = EpochLength(1L)
        val consensus = createIssSegmentModule[FakeTimerCellUnitTestEnv](
          availabilityModuleRef = fakeRecordingModule(availabilityBuffer),
          parentModuleRef = fakeRecordingModule(parentBuffer),
          p2pNetworkOutModuleRef = fakeRecordingModule(p2pBuffer),
          epochLength = epochLength,
        )(epochInfo = SecondEpochInfo.copy(length = epochLength))

        // Upon receiving a Start signal, Consensus should ask for a Proposal from Availability.
        // Availability will "respond" to this request with a proposal, but only AFTER the view
        // change starts (below) and BEFORE the next epoch starts
        consensus.receive(ConsensusSegment.Start)
        inside(availabilityBuffer.toSeq) {
          case Seq(Availability.Consensus.CreateProposal(t, _, e, ackO)) =>
            t.peers shouldBe Set(selfId)
            e shouldBe SecondEpochNumber
            ackO shouldBe None
        }
        availabilityBuffer.clear()

        // Simulate a view change timeout (the sole block0 has not started yet)
        consensus.receive(
          ConsensusSegment.ConsensusMessage
            .PbftNormalTimeout(block10Metadata1Node, ViewNumber.First)
        )

        // View Change should complete immediately in a 1-node network, resulting in:
        //    ViewChange, NewView, and Commit for bottom block0:
        p2pBuffer should contain theSameElementsInOrderAs Seq[P2PNetworkOut.Message](
          P2PNetworkOut.Multicast(
            P2PNetworkOut.BftOrderingNetworkMessage.ConsensusMessage(
              viewChange1Node1BlockNoProgress
            ),
            Set.empty,
          ),
          P2PNetworkOut.Multicast(
            P2PNetworkOut.BftOrderingNetworkMessage.ConsensusMessage(
              newView1Node1BlockNoProgress.fakeSign
            ),
            Set.empty,
          ),
          P2PNetworkOut.Multicast(
            P2PNetworkOut.BftOrderingNetworkMessage.ConsensusMessage(prepareBottomBlock0),
            Set.empty,
          ),
        )
        p2pBuffer.clear()

        consensus.receive(
          ConsensusSegment.ConsensusMessage
            .NewViewStored(BlockMetadata.mk(EpochNumber.First, BlockNumber.First), SecondViewNumber)
        )

        p2pBuffer should contain only P2PNetworkOut.Multicast(
          P2PNetworkOut.BftOrderingNetworkMessage.ConsensusMessage(commitBottomBlock0),
          Set.empty,
        )
        p2pBuffer.clear()

        // Bottom block0 is stored and forwarded to the Output module
        val orderedBlock0 = orderedBlockFromPrePrepare(bottomBlock0.message)
        consensus.receive(
          ConsensusSegment.ConsensusMessage
            .PreparesStored(commitBottomBlock0.message.blockMetadata, SecondViewNumber)
        )
        consensus.receive(
          ConsensusSegment.Internal
            .OrderedBlockStored(orderedBlock0, Seq(commitBottomBlock0), SecondViewNumber)
        )
        parentBuffer should contain only Consensus.ConsensusMessage.BlockOrdered(
          orderedBlock0,
          Seq(commitBottomBlock0),
        )

        parentBuffer.clear()

        // No initiatePull should be sent to Availability (no more slots to assign AND view > 0)
        availabilityBuffer shouldBe empty

        // Availability sends proposal to Consensus before epoch is complete, which should
        // be completely ignored
        consensus.receive(
          ConsensusSegment.ConsensusMessage
            .BlockProposal(oneRequestOrderingBlock, EpochNumber.First)
        )
        p2pBuffer shouldBe empty
      }

      "ignore proposals from old epochs and use ones from new epoch" in {
        val availabilityBuffer =
          new ArrayBuffer[Availability.Message[FakeTimerCellUnitTestEnv]](defaultBufferSize)
        val parentBuffer =
          new ArrayBuffer[Consensus.Message[FakeTimerCellUnitTestEnv]](defaultBufferSize)
        val p2pBuffer = new ArrayBuffer[P2PNetworkOut.Message](defaultBufferSize)
        val epochLength = EpochLength(1L)
        val consensus =
          createIssSegmentModule[FakeTimerCellUnitTestEnv](
            availabilityModuleRef = fakeRecordingModule(availabilityBuffer),
            parentModuleRef = fakeRecordingModule(parentBuffer),
            p2pNetworkOutModuleRef = fakeRecordingModule(p2pBuffer),
            latestCompletedEpochLastCommits = block9Commits1Node,
            epochLength = epochLength,
          )(epochInfo = SecondEpochInfo.copy(length = epochLength))

        consensus.receive(ConsensusSegment.Start)
        availabilityBuffer.clear()

        // Let's assume that in the previous epoch we initiated a pull, entered a view change and finished the view change
        // and the epoch before receiving the proposal.
        // There are now up to 2 outstanding initiatePulls. One from before the view change (in epoch0), and one
        // from the new epoch1 starting. Here, we mock Availability answering both back-to-back with proposals.
        // The one from epoch0 should be ignored because we are now in epoch1
        val epoch1PrePrepare = block10PrePrepare1Node
        val epoch1Prepare = prepareFromPrePrepare(epoch1PrePrepare.message)(from = selfId)
        val epoch1Commit = commitFromPrePrepare(epoch1PrePrepare.message)(from = selfId)
        val epoch1OrderedBlock = orderedBlockFromPrePrepare(epoch1PrePrepare.message)

        // this one is just ignored for being from an old epoch
        consensus.receive(
          ConsensusSegment.ConsensusMessage
            .BlockProposal(oneRequestOrderingBlock, EpochNumber.First)
        )
        p2pBuffer should be(empty)

        // this one with the right epoch number will be used
        consensus.receive(
          ConsensusSegment.ConsensusMessage
            .BlockProposal(oneRequestOrderingBlock, SecondEpochNumber)
        )
        consensus.receive(
          ConsensusSegment.ConsensusMessage.PrePrepareStored(block10Metadata1Node, ViewNumber.First)
        )
        consensus.receive(
          ConsensusSegment.ConsensusMessage.PreparesStored(block10Metadata1Node, ViewNumber.First)
        )
        p2pBuffer should contain theSameElementsInOrderAs Seq[P2PNetworkOut.Message](
          P2PNetworkOut.Multicast(
            P2PNetworkOut.BftOrderingNetworkMessage.ConsensusMessage(epoch1PrePrepare),
            Set.empty,
          ),
          P2PNetworkOut.Multicast(
            P2PNetworkOut.BftOrderingNetworkMessage.ConsensusMessage(epoch1Prepare),
            Set.empty,
          ),
          P2PNetworkOut.Multicast(
            P2PNetworkOut.BftOrderingNetworkMessage.ConsensusMessage(epoch1Commit),
            Set.empty,
          ),
        )
        p2pBuffer.clear()

        // Finish block1 and epoch1
        consensus.receive(
          ConsensusSegment.Internal
            .OrderedBlockStored(epoch1OrderedBlock, Seq(epoch1Commit), ViewNumber.First)
        )
        parentBuffer should contain theSameElementsInOrderAs Seq(
          Consensus.ConsensusMessage.BlockOrdered(epoch1OrderedBlock, Seq(epoch1Commit))
        )
        parentBuffer.clear()
        availabilityBuffer should contain only Availability.Consensus.Ack(Seq(aBatchId))
      }

      "persist outgoing PbftNetworkMessages before attempting to send them" in {
        val availabilityBuffer =
          new ArrayBuffer[Availability.Message[ProgrammableUnitTestEnv]](defaultBufferSize)
        val parentBuffer =
          new ArrayBuffer[Consensus.Message[ProgrammableUnitTestEnv]](defaultBufferSize)
        val p2pBuffer = new ArrayBuffer[P2PNetworkOut.Message](defaultBufferSize)
        val remotePeer = otherPeers(0)
        val store = new InMemoryUnitTestEpochStore[ProgrammableUnitTestEnv]()

        implicit val context: ProgrammableUnitTestContext[ConsensusSegment.Message] =
          new ProgrammableUnitTestContext(resolveAwaits = true)

        val consensus = createIssSegmentModule[ProgrammableUnitTestEnv](
          availabilityModuleRef = fakeRecordingModule(availabilityBuffer),
          parentModuleRef = fakeRecordingModule(parentBuffer),
          p2pNetworkOutModuleRef = fakeRecordingModule(p2pBuffer),
          otherPeers = otherPeers.toSet,
          leader = remotePeer,
          storeMessages = true,
          epochStore = store,
        )(epochInfo = SecondEpochInfo)

        // Start segment submodule and verify viewChangeTimeout is set
        consensus.receive(ConsensusSegment.Start)
        availabilityBuffer.clear()

        // Create PrePrepare, Prepare, and Commit messages for later
        val remotePrePrepare = PrePrepare
          .create(
            blockMetadata4Nodes(blockOrder4Nodes.indexOf(remotePeer)),
            ViewNumber.First,
            clock.now,
            OrderingBlock(oneRequestOrderingBlock.proofs),
            CanonicalCommitSet.empty,
            remotePeer,
          )
          .fakeSign
        def basePrepare(from: SequencerId) =
          prepareFromPrePrepare(remotePrePrepare.message)(from = from)
        def baseCommit(from: SequencerId) =
          commitFromPrePrepare(remotePrePrepare.message)(from = from)
        val epochInfo = EpochInfo.mk(
          remotePrePrepare.message.blockMetadata.epochNumber,
          remotePrePrepare.message.blockMetadata.blockNumber,
          DefaultEpochLength,
        )

        // Mock a PrePrepare received from another node; should call pipeToSelf to store PrePrepare
        consensus.receive(PbftSignedNetworkMessage(remotePrePrepare))
        p2pBuffer shouldBe empty
        context.blockingAwait(store.loadEpochProgress(epochInfo)) shouldBe EpochInProgress(
          Seq.empty,
          Seq.empty,
        )

        // Run the pipeToSelf, should then store the PrePrepare and send the Prepare
        val prePrareStored =
          PrePrepareStored(remotePrePrepare.message.blockMetadata, ViewNumber.First)
        context.runPipedMessages() should contain only prePrareStored
        context.blockingAwait(store.loadEpochProgress(epochInfo)) shouldBe EpochInProgress(
          Seq.empty,
          pbftMessagesForIncompleteBlocks = Seq[SignedMessage[PbftNetworkMessage]](
            remotePrePrepare
          ),
        )
        p2pBuffer should contain theSameElementsInOrderAs Seq[P2PNetworkOut.Message](
          P2PNetworkOut.Multicast(
            P2PNetworkOut.BftOrderingNetworkMessage.ConsensusMessage(
              basePrepare(from = selfId)
            ),
            otherPeers.toSet,
          )
        )
        p2pBuffer.clear()
        consensus.receive(prePrareStored)

        // Upon receiving enough other Prepares, should call pipeToSelf to store Seq(Prepare)
        consensus.receive(PbftSignedNetworkMessage(basePrepare(from = remotePeer)))
        consensus.receive(PbftSignedNetworkMessage(basePrepare(from = otherPeers(1))))

        // Run the pipeToSelf, should then store and send the Prepare
        val preparesStored =
          PreparesStored(remotePrePrepare.message.blockMetadata, ViewNumber.First)
        context.runPipedMessages() should contain only preparesStored
        val progress = context.blockingAwait(store.loadEpochProgress(epochInfo))
        progress shouldBe EpochInProgress(
          Seq.empty,
          pbftMessagesForIncompleteBlocks = Seq[SignedMessage[PbftNetworkMessage]](
            remotePrePrepare,
            basePrepare(from = remotePeer),
            basePrepare(from = otherPeers(1)),
            basePrepare(from = selfId),
          ),
        )
        p2pBuffer should contain theSameElementsInOrderAs Seq[P2PNetworkOut.Message](
          P2PNetworkOut.Multicast(
            P2PNetworkOut.BftOrderingNetworkMessage.ConsensusMessage(
              baseCommit(from = selfId)
            ),
            otherPeers.toSet,
          )
        )
        p2pBuffer.clear()
        consensus.receive(preparesStored)

        // Upon receiving enough other Commits, should call pipeToSelf to addOrderedBlock
        consensus.receive(PbftSignedNetworkMessage(baseCommit(from = remotePeer)))
        consensus.receive(PbftSignedNetworkMessage(baseCommit(from = otherPeers(1))))

        // Run the pipeToSelf, should then add to completed blocks and prune inProgress
        val events = context.runPipedMessages()
        val commits = Seq(
          baseCommit(from = remotePeer),
          baseCommit(from = otherPeers(1)),
          baseCommit(from = selfId),
        )
        context.blockingAwait(store.loadEpochProgress(epochInfo)) shouldBe EpochInProgress(
          completedBlocks = Seq[EpochStore.Block](
            EpochStore.Block(
              epochNumber = epochInfo.number,
              blockNumber = epochInfo.startBlockNumber,
              CommitCertificate(prePrepare = remotePrePrepare, commits = commits),
            )
          ),
          pbftMessagesForIncompleteBlocks = Seq.empty,
        )

        // Execute the self-addressed internal OrderedBlockStored event
        val expectedOrderedBlock = orderedBlockFromPrePrepare(remotePrePrepare.message)
        events should have size 1
        events shouldBe Seq(
          ConsensusSegment.Internal
            .OrderedBlockStored(expectedOrderedBlock, commits, remotePrePrepare.message.viewNumber)
        )
        events.foreach(consensus.receive)
        parentBuffer should contain only Consensus.ConsensusMessage.BlockOrdered(
          expectedOrderedBlock,
          commits,
        )
      }

      // CRASH-RECOVERY TESTS
      val blockMetadata = blockMetadata4Nodes(blockOrder4Nodes.indexOf(selfId))
      val prePrepare = PrePrepare.create(
        blockMetadata,
        ViewNumber.First,
        clock.now,
        OrderingBlock(oneRequestOrderingBlock.proofs),
        CanonicalCommitSet.empty,
        selfId,
      )
      def basePrepare(from: SequencerId) = prepareFromPrePrepare(prePrepare)(from = from)
      def baseCommit(from: SequencerId) = commitFromPrePrepare(prePrepare)(from = from)

      "function after a crash from a pre-stored pre-prepare" in {
        implicit val context: ProgrammableUnitTestContext[ConsensusSegment.Message] =
          new ProgrammableUnitTestContext(resolveAwaits = true)

        val initialMessages =
          EpochStore.EpochInProgress(pbftMessagesForIncompleteBlocks = Seq(prePrepare.fakeSign))

        val p2pBuffer = new ArrayBuffer[P2PNetworkOut.Message](defaultBufferSize)
        val consensus = createIssSegmentModule[ProgrammableUnitTestEnv](
          p2pNetworkOutModuleRef = fakeRecordingModule(p2pBuffer),
          epochInProgress = initialMessages,
          otherPeers = otherPeers.toSet,
        )(epochInfo = SecondEpochInfo)

        consensus.receive(ConsensusSegment.Start)
        context.runPipedMessages() shouldBe empty

        p2pBuffer should contain theSameElementsInOrderAs Seq[P2PNetworkOut.Message](
          P2PNetworkOut.Multicast(
            P2PNetworkOut.BftOrderingNetworkMessage.ConsensusMessage(prePrepare.fakeSign),
            otherPeers.toSet,
          ),
          P2PNetworkOut.Multicast(
            P2PNetworkOut.BftOrderingNetworkMessage.ConsensusMessage(
              prepareFromPrePrepare(prePrepare)(from = selfId)
            ),
            otherPeers.toSet,
          ),
        )
        p2pBuffer.clear()

        // after starting from initial pre-prepare, we should be able to process prepares and create a commit
        consensus.receive(PbftSignedNetworkMessage(basePrepare(from = otherPeers(0))))
        consensus.receive(PbftSignedNetworkMessage(basePrepare(from = otherPeers(1))))
        p2pBuffer should contain theSameElementsInOrderAs Seq[P2PNetworkOut.Message](
          P2PNetworkOut.Multicast(
            P2PNetworkOut.BftOrderingNetworkMessage.ConsensusMessage(
              baseCommit(from = selfId)
            ),
            otherPeers.toSet,
          )
        )
      }

      "function after a crash from pre-stored pre-prepare and prepares" in {
        implicit val context: ProgrammableUnitTestContext[ConsensusSegment.Message] =
          new ProgrammableUnitTestContext(resolveAwaits = true)

        val myPrepare = prepareFromPrePrepare(prePrepare)(from = selfId)
        val prepares = List[SignedMessage[PbftNetworkMessage]](
          myPrepare,
          basePrepare(from = otherPeers(0)),
          basePrepare(from = otherPeers(1)),
        )

        val initialMessages =
          EpochStore.EpochInProgress(pbftMessagesForIncompleteBlocks =
            prePrepare.fakeSign :: prepares
          )

        val parentBuffer =
          new ArrayBuffer[Consensus.Message[ProgrammableUnitTestEnv]](defaultBufferSize)

        val p2pBuffer = new ArrayBuffer[P2PNetworkOut.Message](defaultBufferSize)
        val consensus = createIssSegmentModule[ProgrammableUnitTestEnv](
          p2pNetworkOutModuleRef = fakeRecordingModule(p2pBuffer),
          parentModuleRef = fakeRecordingModule(parentBuffer),
          epochInProgress = initialMessages,
          otherPeers = otherPeers.toSet,
        )(epochInfo = SecondEpochInfo)

        consensus.receive(ConsensusSegment.Start)
        context.runPipedMessages() shouldBe empty

        p2pBuffer should contain theSameElementsInOrderAs Seq[P2PNetworkOut.Message](
          P2PNetworkOut.Multicast(
            P2PNetworkOut.BftOrderingNetworkMessage.ConsensusMessage(prePrepare.fakeSign),
            otherPeers.toSet,
          ),
          P2PNetworkOut.Multicast(
            P2PNetworkOut.BftOrderingNetworkMessage.ConsensusMessage(myPrepare),
            otherPeers.toSet,
          ),
          P2PNetworkOut.Multicast(
            P2PNetworkOut.BftOrderingNetworkMessage.ConsensusMessage(
              baseCommit(from = selfId)
            ),
            otherPeers.toSet,
          ),
        )
        p2pBuffer.clear()

        consensus.receive(PbftSignedNetworkMessage(baseCommit(from = otherPeers(0))))
        consensus.receive(PbftSignedNetworkMessage(baseCommit(from = otherPeers(1))))
        val expectedOrderedBlock = orderedBlockFromPrePrepare(prePrepare)
        val commits = Seq(
          baseCommit(from = otherPeers(0)),
          baseCommit(from = otherPeers(1)),
          baseCommit(from = selfId),
        )
        val orderedBlockStored = ConsensusSegment.Internal.OrderedBlockStored(
          expectedOrderedBlock,
          commits,
          ViewNumber.First,
        )

        val pipedMessages = context.runPipedMessages()
        pipedMessages should contain only orderedBlockStored

        pipedMessages.foreach(consensus.receive)
        parentBuffer should contain only Consensus.ConsensusMessage.BlockOrdered(
          expectedOrderedBlock,
          commits,
        )
      }

      "take initial messages into account for prepare certificate" in {
        implicit val context: ProgrammableUnitTestContext[ConsensusSegment.Message] =
          new ProgrammableUnitTestContext(resolveAwaits = true)

        val myPrepare = prepareFromPrePrepare(prePrepare)(from = selfId)
        val prepares = List(
          basePrepare(from = otherPeers(0)),
          basePrepare(from = otherPeers(1)),
          myPrepare,
        )

        val initialMessages =
          EpochStore.EpochInProgress(pbftMessagesForIncompleteBlocks =
            prePrepare.fakeSign :: (prepares: List[SignedMessage[PbftNetworkMessage]])
          )

        val p2pBuffer = new ArrayBuffer[P2PNetworkOut.Message](defaultBufferSize)
        val consensus = createIssSegmentModule[ProgrammableUnitTestEnv](
          p2pNetworkOutModuleRef = fakeRecordingModule(p2pBuffer),
          epochInProgress = initialMessages,
          otherPeers = otherPeers.toSet,
        )(epochInfo = SecondEpochInfo)

        consensus.receive(ConsensusSegment.Start)
        context.runPipedMessages() shouldBe empty

        consensus.receive(
          ConsensusSegment.ConsensusMessage.PbftNormalTimeout(blockMetadata, ViewNumber.First)
        )

        // should take initial messages into account for prepare certificate
        val viewChange = ViewChange
          .create(
            blockMetadata,
            segmentIndex = blockOrder4Nodes.indexOf(selfId),
            viewNumber = SecondViewNumber,
            clock.now,
            consensusCerts =
              Seq[ConsensusCertificate](PrepareCertificate(prePrepare.fakeSign, prepares)),
            from = selfId,
          )
          .fakeSign

        p2pBuffer should contain(
          P2PNetworkOut.Multicast(
            P2PNetworkOut.BftOrderingNetworkMessage.ConsensusMessage(viewChange),
            otherPeers.toSet,
          )
        )
      }

      "properly handle pre-stored new-view message" in {
        implicit val context: ProgrammableUnitTestContext[ConsensusSegment.Message] =
          new ProgrammableUnitTestContext(resolveAwaits = true)

        val myPrepare = prepareFromPrePrepare(prePrepare)(from = selfId)
        val prepares = List(
          basePrepare(from = otherPeers(0)),
          basePrepare(from = otherPeers(1)),
          myPrepare,
        )

        val bottomBlock1 =
          bottomBlock(
            blockMetadata4Nodes(blockOrder4Nodes.indexOf(selfId) + allPeers.size),
            SecondViewNumber,
            clock.now,
            from = otherPeers(0),
          )

        val emptyViewChanges = (0 until 2).map(i =>
          ViewChange
            .create(
              blockMetadata,
              segmentIndex = blockOrder4Nodes.indexOf(selfId),
              viewNumber = SecondViewNumber,
              clock.now,
              consensusCerts = Seq.empty,
              from = otherPeers(i),
            )
            .fakeSign
        )

        val newView =
          NewView.create(
            blockMetadata,
            segmentIndex = blockOrder4Nodes.indexOf(selfId),
            viewNumber = SecondViewNumber,
            clock.now,
            ViewChange
              .create(
                blockMetadata,
                segmentIndex = blockOrder4Nodes.indexOf(selfId),
                viewNumber = SecondViewNumber,
                clock.now,
                consensusCerts =
                  Seq[ConsensusCertificate](PrepareCertificate(prePrepare.fakeSign, prepares)),
                from = selfId,
              )
              .fakeSign
              +: emptyViewChanges,
            Seq(prePrepare.fakeSign, bottomBlock1),
            from = otherPeers(0),
          )

        val initialMessages =
          EpochStore.EpochInProgress(pbftMessagesForIncompleteBlocks =
            List[SignedMessage[PbftNetworkMessage]](
              prePrepare.fakeSign,
              newView.fakeSign,
            ) ++ (prepares: List[
              SignedMessage[PbftNetworkMessage]
            ])
          )

        val p2pBuffer = new ArrayBuffer[P2PNetworkOut.Message](defaultBufferSize)
        val consensus = createIssSegmentModule[ProgrammableUnitTestEnv](
          p2pNetworkOutModuleRef = fakeRecordingModule(p2pBuffer),
          epochInProgress = initialMessages,
          otherPeers = otherPeers.toSet,
          leader = selfId,
        )(epochInfo = SecondEpochInfo)

        consensus.receive(ConsensusSegment.Start)
        context.runPipedMessages() shouldBe empty

        p2pBuffer should contain theSameElementsInOrderAs Seq[P2PNetworkOut.Message](
          P2PNetworkOut.Multicast(
            P2PNetworkOut.BftOrderingNetworkMessage.ConsensusMessage(
              prepareFromPrePrepare(prePrepare)(from = selfId, viewNumber = SecondViewNumber)
            ),
            otherPeers.toSet,
          ),
          P2PNetworkOut.Multicast(
            P2PNetworkOut.BftOrderingNetworkMessage.ConsensusMessage(
              prepareFromPrePrepare(bottomBlock1.message)(from = selfId)
            ),
            otherPeers.toSet,
          ),
        )
        p2pBuffer.clear()

        // after starting from initial-prepare, we should be able to process prepares and create a commit
        def basePrepareNextView(from: SequencerId) =
          prepareFromPrePrepare(prePrepare)(from = from, viewNumber = SecondViewNumber)
        consensus.receive(PbftSignedNetworkMessage(basePrepareNextView(from = otherPeers(0))))
        consensus.receive(PbftSignedNetworkMessage(basePrepareNextView(from = otherPeers(1))))
        p2pBuffer should contain theSameElementsInOrderAs Seq[P2PNetworkOut.Message](
          P2PNetworkOut.Multicast(
            P2PNetworkOut.BftOrderingNetworkMessage.ConsensusMessage(
              commitFromPrePrepare(prePrepare)(from = selfId, viewNumber = SecondViewNumber)
            ),
            otherPeers.toSet,
          )
        )
      }
    }
  }

  def createIssSegmentModule[E <: BaseIgnoringUnitTestEnv[E]](
      availabilityModuleRef: ModuleRef[Availability.Message[E]] =
        fakeIgnoringModule[Availability.Message[E]],
      p2pNetworkOutModuleRef: ModuleRef[P2PNetworkOut.Message] =
        fakeIgnoringModule[P2PNetworkOut.Message],
      parentModuleRef: ModuleRef[Consensus.Message[E]] = fakeIgnoringModule[Consensus.Message[E]],
      leader: SequencerId = selfId,
      epochLength: EpochLength = DefaultEpochLength,
      latestCompletedEpochLastCommits: Seq[SignedMessage[Commit]] = GenesisEpoch.lastBlockCommits,
      otherPeers: Set[SequencerId] = Set.empty,
      storeMessages: Boolean = false,
      epochStore: EpochStore[E] = new InMemoryUnitTestEpochStore[E](),
      epochInProgress: EpochStore.EpochInProgress = EpochStore.EpochInProgress(),
  )(
      epochInfo: EpochInfo =
        GenesisEpoch.info.next(epochLength, Genesis.GenesisTopologyActivationTime)
  ): IssSegmentModule[E] = {
    val epoch = {
      val initialMembership = Membership(selfId, otherPeers = otherPeers)
      Epoch(
        epochInfo,
        initialMembership,
        SimpleLeaderSelectionPolicy,
      )
    }
    val segmentState = {
      val segment = epoch.segments.find(_.originalLeader == leader).getOrElse(fail(""))
      new SegmentState(
        segment,
        epochInfo.number,
        epoch.membership,
        epoch.leaders,
        clock,
        epochInProgress.completedBlocks,
        fail(_),
        SequencerMetrics.noop(getClass.getSimpleName).bftOrdering,
        loggerFactory,
      )(MetricsContext.Empty)
    }
    new IssSegmentModule[E](
      epoch,
      segmentState,
      new EpochMetricsAccumulator(),
      storePbftMessages = storeMessages,
      epochStore,
      clock,
      fakeCryptoProvider,
      latestCompletedEpochLastCommits,
      epochInProgress,
      parentModuleRef,
      availabilityModuleRef,
      p2pNetworkOutModuleRef,
      timeouts,
      loggerFactory,
    )
  }

  private def delayCount(
      cell: AtomicReference[Option[(DelayCount, ConsensusSegment.Message)]]
  ): DelayCount =
    cell.get().getOrElse(fail())._1
}

private object IssSegmentModuleTest {
  private val defaultBufferSize = 5
  private val selfId = fakeSequencerId("self")
  private val otherPeers: IndexedSeq[SequencerId] = (1 to 3).map { index =>
    fakeSequencerId(
      s"peer$index"
    )
  }
  private val allPeers = (selfId +: otherPeers).sorted
  private val fullTopology = OrderingTopology(allPeers.toSet)
  private val aBatchId = BatchId.createForTesting("A batch id")
  private val oneRequestOrderingBlock = OrderingBlock(Seq(ProofOfAvailability(aBatchId, Seq.empty)))

  def prepareFromPrePrepare(prePrepare: PrePrepare)(
      viewNumber: ViewNumber = prePrepare.viewNumber,
      from: SequencerId = fakeSequencerId("toBeReplaced"),
  ): SignedMessage[Prepare] =
    Prepare
      .create(
        prePrepare.blockMetadata,
        viewNumber,
        prePrepare.hash,
        prePrepare.localTimestamp,
        from,
      )
      .fakeSign

  def commitFromPrePrepare(prePrepare: PrePrepare)(
      viewNumber: ViewNumber = prePrepare.viewNumber,
      from: SequencerId = fakeSequencerId("toBeReplaced"),
  ): SignedMessage[Commit] =
    Commit
      .create(
        prePrepare.blockMetadata,
        viewNumber,
        prePrepare.hash,
        prePrepare.localTimestamp,
        from,
      )
      .fakeSign

  def orderedBlockFromPrePrepare(prePrepare: PrePrepare): OrderedBlock =
    OrderedBlock(
      prePrepare.blockMetadata,
      prePrepare.block.proofs,
      prePrepare.canonicalCommitSet,
    )

  def bottomBlock(
      blockMetadata: BlockMetadata,
      view: ViewNumber,
      now: CantonTimestamp,
      from: SequencerId,
  ): SignedMessage[PrePrepare] =
    PrePrepare
      .create(
        blockMetadata,
        view,
        now,
        OrderingBlock(Seq.empty),
        CanonicalCommitSet(Set.empty),
        from,
      )
      .fakeSign

  def nextRoundRobinPeer(currentPeer: SequencerId): SequencerId = {
    val currentIndex = allPeers.indexOf(currentPeer)
    allPeers((currentIndex + 1) % allPeers.size)
  }

  def nextRoundRobinPeerExcludingSelf(currentPeer: SequencerId): SequencerId = {
    val nextPeer = nextRoundRobinPeer(currentPeer)
    if (nextPeer == selfId) nextRoundRobinPeer(nextPeer)
    else nextPeer
  }
}
