// Copyright (c) 2025 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.canton.synchronizer.sequencer.block.bftordering.core.modules.consensus.iss

import com.daml.metrics.api.MetricsContext
import com.digitalasset.canton.synchronizer.metrics.SequencerMetrics
import com.digitalasset.canton.synchronizer.sequencer.block.bftordering.BftSequencerBaseTest
import com.digitalasset.canton.synchronizer.sequencer.block.bftordering.BftSequencerBaseTest.FakeSigner
import com.digitalasset.canton.synchronizer.sequencer.block.bftordering.core.modules.consensus.iss.SegmentState.computeLeaderOfView
import com.digitalasset.canton.synchronizer.sequencer.block.bftordering.framework.data.BftOrderingIdentifiers.{
  BftNodeId,
  BlockNumber,
  ViewNumber,
}
import com.digitalasset.canton.synchronizer.sequencer.block.bftordering.framework.data.SignedMessage
import com.digitalasset.canton.synchronizer.sequencer.block.bftordering.framework.modules.ConsensusSegment.ConsensusMessage.ViewChange
import org.scalatest.wordspec.AsyncWordSpec
import org.slf4j.event.Level.{INFO, WARN}

class PbftViewChangeStateTest extends AsyncWordSpec with BftSequencerBaseTest {

  import SegmentStateTest.*

  private implicit val mc: MetricsContext = MetricsContext.Empty
  private val metrics = SequencerMetrics.noop(getClass.getSimpleName).bftOrdering

  "PbftViewChangeState" when {
    "storing messages" should {
      "store only valid View Change messages" in {
        val nextView = ViewNumber(ViewNumber.First + 1L)
        val originalLeaderIndex = allIds.indexOf(myId)
        val nextLeader = computeLeaderOfView(nextView, originalLeaderIndex, allIds)

        val vcState = new PbftViewChangeState(
          fullMembership,
          leader = nextLeader,
          view = nextView,
          slotNumbers,
          metrics,
          loggerFactory,
        )

        val vc1 = createViewChange(nextView, myId, myId, slotNumbers.map(_ -> ViewNumber.First))

        // Fresh view change message should process correctly
        assertNoLogs(vcState.processMessage(vc1)) shouldBe true

        // Duplicate should be rejected
        assertLogs(
          vcState.processMessage(vc1),
          log => {
            log.level shouldBe INFO
            log.message should include("already exists")
          },
        ) shouldBe false
      }

      "store only valid New View messages" in {
        val nextView = ViewNumber(ViewNumber.First + 1)
        val originalLeaderIndex = allIds.indexOf(myId)
        val nextLeader = computeLeaderOfView(nextView, originalLeaderIndex, allIds)

        val vcState = new PbftViewChangeState(
          fullMembership,
          leader = nextLeader,
          view = nextView,
          slotNumbers,
          metrics,
          loggerFactory,
        )

        // Create strong quorum number of View Change messages, and extract PrePrepares from one of them
        val vcSet = allIds
          .take(fullMembership.orderingTopology.strongQuorum)
          .map(node =>
            createViewChange(nextView, node, myId, slotNumbers.map(_ -> ViewNumber.First))
          )
        val ppSeq = vcSet(0).message.consensusCerts.map(_.prePrepare)

        // Receiving a New View message from the incorrect leader fails
        val wrongNextLeader =
          computeLeaderOfView(ViewNumber(nextView + 1), originalLeaderIndex, allIds)
        val wrongNewView = createNewView(nextView, wrongNextLeader, vcSet, ppSeq)
        assertLogs(
          vcState.processMessage(wrongNewView),
          log => {
            log.level shouldBe WARN
            log.message should include("but the leader of view")
          },
        ) shouldBe false

        // Receiving a New View message from the correct leader succeeds
        val correctNewView = createNewView(nextView, nextLeader, vcSet, ppSeq)
        assertNoLogs(vcState.processMessage(correctNewView)) shouldBe true

        // Duplicate New View message are rejected
        assertLogs(
          vcState.processMessage(correctNewView),
          log => {
            log.level shouldBe INFO
            log.message should include("already exists; ignoring new")
          },
        ) shouldBe false
      }
    }

    "constructing a New View message (as a non-original leader)" should {
      class SystemState(
          viewNumbersPerNode: Seq[Map[Long, Long]],
          val nextView: ViewNumber = ViewNumber(ViewNumber.First + 1),
          val originalLeader: BftNodeId = otherId3,
      ) {
        val originalLeaderIndex: Int = allIds.indexOf(originalLeader)
        val nextLeader: BftNodeId = computeLeaderOfView(nextView, originalLeaderIndex, allIds)
        val vcState = new PbftViewChangeState(
          fullMembership,
          leader = nextLeader,
          view = nextView,
          slotNumbers,
          metrics,
          loggerFactory,
        )
        val vcSet: IndexedSeq[SignedMessage[ViewChange]] = createViewChangeSet(
          nextView,
          originalLeader,
          viewNumbersPerNode,
        )
      }

      "produce a New View with no bottom blocks when all slots have an original view prepare cert" in {
        val systemState = new SystemState(
          (0 until fullMembership.orderingTopology.strongQuorum)
            .map(_ => slotNumbers.map(_.toLong -> ViewNumber.First.toLong).toMap.forgetNE)
        )
        import systemState.*

        vcSet.foreach(vcState.processMessage)
        vcState.shouldCreateNewView shouldBe true

        val maybePrePrepares = vcState.constructPrePreparesForNewView(blockMetaData)
        val prePrepares = maybePrePrepares.collect { case Right(r) => r }

        prePrepares.size should be(maybePrePrepares.size)

        prePrepares.foreach(_.message.viewNumber shouldBe ViewNumber.First)
        prePrepares.foreach(_.from shouldBe originalLeader)
        prePrepares should have size slotNumbers.size.toLong
      }

      "produce a New View with all bottom blocks when no slots have any prepare cert" in {
        val systemState = new SystemState(
          (0 until fullMembership.orderingTopology.strongQuorum)
            .map(_ => Map.empty[Long, Long])
        )
        import systemState.*

        vcSet.foreach(vcState.processMessage)
        vcState.shouldCreateNewView shouldBe true

        val maybePrePrepares = vcState.constructPrePreparesForNewView(blockMetaData)
        val nv = vcState.createNewViewMessage(
          blockMetaData,
          maybePrePrepares.map {
            case Right(r) => r
            case Left(toSign) => toSign.fakeSign
          },
          fail(_),
        )

        nv.prePrepares.size should be(maybePrePrepares.size)

        nv.prePrepares.foreach(_.message.viewNumber shouldBe nextView)
        nv.prePrepares.foreach(_.from shouldBe nextLeader)
        nv.prePrepares should have size slotNumbers.size.toLong
      }

      "produce a New View with the highest-view PrePrepare (w/ valid cert) for each slot" in {
        val systemState = new SystemState(
          Seq(
            Map(BlockNumber.First -> ViewNumber.First, 4L -> 1L, 8L -> 2L),
            Map(BlockNumber.First -> ViewNumber.First, 4L -> 1L),
            Map(BlockNumber.First -> ViewNumber.First, 4L -> ViewNumber.First),
          ).map(_.map { case (k, v) => BlockNumber(k) -> ViewNumber(v) }),
          nextView = ViewNumber(3L),
          originalLeader = otherId1,
        )
        import systemState.*

        vcSet.foreach(vcState.processMessage)
        vcState.shouldCreateNewView shouldBe true

        val maybePrePrepares = vcState.constructPrePreparesForNewView(blockMetaData)
        val prePrepares = maybePrePrepares.collect { case Right(r) => r }

        prePrepares.size should be(maybePrePrepares.size)
        prePrepares.map(pp =>
          pp.from -> pp.message.viewNumber
        ) should contain theSameElementsInOrderAs Seq(
          originalLeader -> 0,
          otherId2 -> 1,
          otherId3 -> 2,
        )
        prePrepares should have size slotNumbers.size.toLong
      }

      "produce a New View with highest-view PrePrepare for each slot, including bottom blocks" in {
        val systemState = new SystemState(
          Seq(
            Map(BlockNumber.First -> ViewNumber.First, 4L -> 1),
            Map(BlockNumber.First -> ViewNumber.First, 4L -> 1),
            Map(BlockNumber.First -> ViewNumber.First, 4L -> 0),
          ),
          nextView = ViewNumber(3L),
          originalLeader = otherId1,
        )
        import systemState.*

        vcSet.foreach(vcState.processMessage)
        vcState.shouldCreateNewView shouldBe true

        val maybePrePrepares = vcState.constructPrePreparesForNewView(blockMetaData)
        val prePrepares = maybePrePrepares.map {
          case Right(r) => r
          case Left(l) => l.fakeSign
        }

        prePrepares.size should be(maybePrePrepares.size)
        prePrepares.map(pp =>
          pp.from -> pp.message.viewNumber
        ) should contain theSameElementsInOrderAs Seq(
          originalLeader -> 0,
          otherId2 -> 1,
          myId -> 3,
        )
        prePrepares should have size slotNumbers.size.toLong
      }

      "produce a New View with the same set of ViewChange messages used to SignPrePrepares" in {
        val systemState = new SystemState(
          Seq(
            Map.empty[Long, Long],
            Map.empty[Long, Long],
            Map.empty[Long, Long],
            Map(BlockNumber.First -> ViewNumber.First),
          )
        )
        import systemState.*

        // separate 3f+1 total view change messages into (extra, 2f+1) sets
        val (extraVC, quorumVC) = vcSet.splitAt(1)

        // process the 2f+1 view change messages
        quorumVC.foreach(vcState.processMessage)
        vcState.shouldCreateNewView shouldBe true

        val maybePrePrepares = vcState.constructPrePreparesForNewView(blockMetaData)
        val prePrepares = maybePrePrepares.map {
          case Right(r) => r
          case Left(l) => l.fakeSign
        }

        prePrepares.size should be(maybePrePrepares.size)
        prePrepares.map(pp =>
          pp.from -> pp.message.viewNumber
        ) should contain theSameElementsInOrderAs Seq(
          originalLeader -> 0,
          myId -> 1,
          myId -> 1,
        )
        prePrepares should have size slotNumbers.size.toLong

        // process the last remaining extra view change message
        extraVC.foreach(vcState.processMessage)

        val newView =
          vcState.createNewViewMessage(blockMetaData, prePrepares, fail(_))

        // NewView.viewChanges should match the original quorumVC (unaffected by extraVC)
        newView.viewChanges shouldBe quorumVC
      }
    }
  }
}
