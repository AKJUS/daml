// Copyright (c) 2025 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.canton.participant.protocol.conflictdetection

import cats.data.{Chain, NonEmptyChain}
import cats.syntax.either.*
import cats.syntax.foldable.*
import cats.syntax.functor.*
import cats.syntax.parallel.*
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.data.CantonTimestamp.{Epoch, ofEpochMilli}
import com.digitalasset.canton.lifecycle.{CloseContext, FutureUnlessShutdown, UnlessShutdown}
import com.digitalasset.canton.logging.pretty.PrettyPrinting
import com.digitalasset.canton.participant.protocol.conflictdetection.ConflictDetector.*
import com.digitalasset.canton.participant.protocol.conflictdetection.LockableState.{
  LockCounter,
  PendingActivenessCheckCounter,
  PendingWriteCounter,
}
import com.digitalasset.canton.participant.protocol.conflictdetection.LockableStates.ConflictDetectionStoreAccessError
import com.digitalasset.canton.participant.protocol.conflictdetection.RequestTracker.{
  AcsError,
  InvalidCommitSet,
  ReassignmentsStoreError,
}
import com.digitalasset.canton.participant.store.*
import com.digitalasset.canton.participant.store.ActiveContractStore.{
  Active,
  Archived,
  ChangeAfterArchival,
  ContractState as AcsContractState,
  DoubleContractArchival,
  DoubleContractCreation,
  ReassignedAway,
  Status,
}
import com.digitalasset.canton.participant.store.ReassignmentStore.{
  ReassignmentAlreadyCompleted,
  ReassignmentCompleted,
  UnknownReassignmentId,
}
import com.digitalasset.canton.participant.store.memory.{
  InMemoryReassignmentStore,
  ReassignmentCache,
  ReassignmentCacheTest,
}
import com.digitalasset.canton.participant.util.{StateChange, TimeOfChange, TimeOfRequest}
import com.digitalasset.canton.protocol.{ExampleTransactionFactory, LfContractId, ReassignmentId}
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.ReassignmentTag.Target
import com.digitalasset.canton.util.{Checked, CheckedT}
import com.digitalasset.canton.version.HasTestCloseContext
import com.digitalasset.canton.{
  BaseTest,
  HasExecutorService,
  InUS,
  ReassignmentCounter,
  RequestCounter,
}
import org.scalactic.source
import org.scalatest.Assertion
import org.scalatest.wordspec.AsyncWordSpec

import java.time.Instant
import scala.concurrent.Promise
import scala.language.implicitConversions
import scala.util.{Failure, Success}

@SuppressWarnings(Array("org.wartremover.warts.IsInstanceOf"))
final class ConflictDetectorTest
    extends AsyncWordSpec
    with BaseTest
    with HasExecutorService
    with ConflictDetectionHelpers
    with InUS {

  import ConflictDetectionHelpers.*
  import ReassignmentStoreTest.*

  private val coid00: LfContractId = ExampleTransactionFactory.suffixedId(0, 0)
  private val coid01: LfContractId = ExampleTransactionFactory.suffixedId(0, 1)
  private val coid10: LfContractId = ExampleTransactionFactory.suffixedId(1, 0)
  private val coid11: LfContractId = ExampleTransactionFactory.suffixedId(1, 1)
  private val coid20: LfContractId = ExampleTransactionFactory.suffixedId(2, 0)
  private val coid21: LfContractId = ExampleTransactionFactory.suffixedId(2, 1)
  private val coid22: LfContractId = ExampleTransactionFactory.suffixedId(2, 2)

  private val reassignment2 = ReassignmentId.tryCreate("0002")

  private val initialReassignmentCounter: ReassignmentCounter = ReassignmentCounter.Genesis
  private val reassignmentCounter1 = initialReassignmentCounter + 1
  private val reassignmentCounter2 = initialReassignmentCounter + 2

  private val active = Active(initialReassignmentCounter)

  private def defaultReassignmentCache: ReassignmentCache =
    new ReassignmentCache(
      new InMemoryReassignmentStore(targetSynchronizerId, loggerFactory),
      futureSupervisor,
      timeouts,
      loggerFactory,
    )

  private def mkCd(
      acs: ActiveContractStore = mkEmptyAcs(),
      reassignmentCache: ReassignmentCache = defaultReassignmentCache,
  ): ConflictDetector =
    new ConflictDetector(
      acs,
      reassignmentCache,
      loggerFactory,
      true,
      parallelExecutionContext,
      exitOnFatalFailures = true,
      timeouts,
      futureSupervisor,
    )

  "ConflictDetector" should {

    "handle the empty request with the empty store" inUS {
      val cd = mkCd()
      for {
        _ <- singleCRwithTR(
          cd,
          RequestCounter(0),
          ActivenessSet.empty,
          mkActivenessResult(),
          CommitSet.empty,
          Epoch,
        )
      } yield succeed
    }

    "handle a single request" inUS {
      val rc = RequestCounter(10)
      val ts = ofEpochMilli(1)
      val tor = TimeOfRequest(rc, ts)
      val torN5 = TimeOfRequest(rc - 5L, Epoch)

      for {
        acs <- mkAcs(
          (coid00, torN5, active),
          (coid01, torN5, active),
        )
        cd = mkCd(acs)

        actSet = mkActivenessSet(
          deact = Set(coid00),
          useOnly = Set(coid01),
          create = Set(coid10),
          prior = Set(coid00, coid10),
        )
        actRes = mkActivenessResult(prior = Map(coid00 -> Some(active), coid10 -> None))
        commitSet = mkCommitSet(
          arch = Set(coid00),
          create = Set(coid10),
        )
        _ <- singleCRwithTR(cd, rc, actSet, actRes, commitSet, ts)

        _ <- checkContractState(acs, coid00, Archived -> tor)("consumed contract is archived")
        _ <- checkContractState(acs, coid01, active -> torN5)("fetched contract remains active")
        _ <- checkContractState(acs, coid10, active -> tor)("created contract is active")
      } yield succeed
    }

    "report nonexistent contracts" inUS {
      val rc = RequestCounter(10)
      val ts = CantonTimestamp.assertFromInstant(Instant.parse("2029-01-01T00:00:00.00Z"))
      val tor = TimeOfRequest(rc, ts)
      val torN5 = TimeOfRequest(rc - 5L, Epoch)
      val torN3 = TimeOfRequest(rc - 3L, ofEpochMilli(1))
      for {
        acs <- mkAcs(
          (coid00, torN5, active),
          (coid01, torN5, active),
          (coid00, torN3, Archived),
        )
        cd = mkCd(acs = acs)

        actSet = mkActivenessSet(
          deact = Set(coid00, coid01, coid10),
          useOnly = Set(coid21),
          create = Set(coid11),
        )
        actRes = mkActivenessResult(
          unknown = Set(coid10, coid21),
          notActive = Map(coid00 -> Archived),
        )
        _ <- singleCRwithTR(
          cd,
          rc,
          actSet,
          actRes,
          mkCommitSet(arch = Set(coid01), create = Set(coid11)),
          ts,
        )
        _ <- checkContractState(acs, coid00, (Archived, torN3))(
          "archived contract remains archived"
        )
        _ <- checkContractState(acs, coid01, (Archived, tor))(
          "active contract gets archived at commit time"
        )
        _ <- checkContractState(acs, coid11, (active, tor))(
          "contract 11 gets created at request time"
        )
      } yield succeed
    }

    "complain about failing ACS reads" inUS {
      val cd = mkCd(acs = new ThrowingAcs[RuntimeException](msg => new RuntimeException(msg)))
      for {
        failure <- cd
          .registerActivenessSet(RequestCounter(0), mkActivenessSet(deact = Set(coid00)))
          .failed

      } yield assert(failure.isInstanceOf[ConflictDetectionStoreAccessError])
    }

    "complain about requests in-flight" inUS {
      val cd = mkCd()
      for {
        _ <- cd.registerActivenessSet(RequestCounter(0), ActivenessSet.empty)
        _ <- loggerFactory.assertInternalErrorAsyncUS[IllegalConflictDetectionStateException](
          cd.registerActivenessSet(RequestCounter(0), ActivenessSet.empty),
          _.getMessage shouldBe "Request 0 is already in-flight.",
        )
        cr <- cd.checkActivenessAndLock(RequestCounter(0))
        _ <- loggerFactory.assertInternalErrorAsyncUS[IllegalConflictDetectionStateException](
          cd.registerActivenessSet(RequestCounter(0), ActivenessSet.empty),
          _.getMessage shouldBe "Request 0 is already in-flight.",
        )
        _ <- loggerFactory.assertInternalErrorAsyncUS[IllegalConflictDetectionStateException](
          cd.checkActivenessAndLock(RequestCounter(0)),
          _.getMessage shouldBe "Request 0 has no pending activeness check.",
        )
        fin <- cd
          .finalizeRequest(CommitSet.empty, TimeOfRequest(RequestCounter(0), Epoch))
          .flatten

      } yield {
        cr shouldBe mkActivenessResult()
        fin shouldBe Either.unit
      }
    }

    "complain about request without prefetching" inUS {
      val cd = mkCd()
      for {
        _ <- loggerFactory.assertInternalErrorAsyncUS[IllegalConflictDetectionStateException](
          cd.checkActivenessAndLock(RequestCounter(0)),
          _.getMessage shouldBe "Request 0 has no pending activeness check.",
        )
      } yield succeed
    }

    "complain about nonexistent requests at finalization" inUS {
      val cd = mkCd()
      for {
        error <- cd
          .finalizeRequest(CommitSet.empty, TimeOfRequest(RequestCounter(0), Epoch))
          .failed

      } yield assert(error.isInstanceOf[IllegalArgumentException])
    }

    "complain about requests in-flight while the changes are written" inUS {
      val rc = RequestCounter(0)

      for {
        rawAcs <- mkAcs()
        acs = new HookedAcs(rawAcs)
        cd = mkCd(acs)

        cr <- prefetchAndCheck(cd, rc, mkActivenessSet(create = Set(coid00)))
        _ = acs.setCreateAddHook { _ =>
          // Insert the same request with a different activeness set while the ACS updates happen
          loggerFactory
            .assertInternalErrorAsyncUS[IllegalConflictDetectionStateException](
              cd.registerActivenessSet(rc, ActivenessSet.empty),
              _.getMessage shouldBe "Request 0 is already in-flight.",
            )
            .void
        }
        fin <- cd
          .finalizeRequest(mkCommitSet(create = Set(coid00)), TimeOfRequest(rc, Epoch))
          .flatten

      } yield {
        cr shouldBe mkActivenessResult()
        fin shouldBe Either.unit
      }
    }

    "lock created and archived contracts and updated keys" inUS {
      val rc = RequestCounter(10)
      val tor1 = TimeOfRequest(rc - 5L, Epoch)
      val tor2 = TimeOfRequest(rc - 1L, ofEpochMilli(1))
      for {
        rawAcs <- mkAcs(
          (coid00, tor1, active),
          (coid01, tor1, active),
          (coid10, tor2, active),
          (coid11, tor1, active),
          (coid10, tor2, Archived),
        )
        acs = new HookedAcs(rawAcs)(parallelExecutionContext)
        cd = mkCd(acs)

        actSet = mkActivenessSet(
          deact = Set(coid00, coid01, coid10),
          useOnly = Set(coid11, coid20),
          create = Set(coid21, coid22),
        )
        cr <- prefetchAndCheck(cd, rc, actSet)

        _ = assert(
          cr == mkActivenessResult(unknown = Set(coid20), notActive = Map(coid10 -> Archived))
        )
        _ = checkContractState(cd, coid00, active, tor1, 0, 1, 0)(s"lock contract $coid00")
        _ = checkContractState(cd, coid01, active, tor1, 0, 1, 0)(s"lock contract $coid01")
        _ = checkContractState(cd, coid10, Archived, tor2, 0, 1, 0)(
          s"lock archived contract $coid10"
        )
        _ = checkContractStateAbsent(cd, coid11)(s"evict used only contract $coid11")
        _ = checkContractStateAbsent(cd, coid20)(s"do not keep non-existent contract $coid20")
        _ = checkContractState(cd, coid21, 0, 1, 0)(s"lock contract $coid21 for creation")
        _ = checkContractState(cd, coid22, 0, 1, 0)(s"lock contract $coid22 for creation")

        tor = TimeOfRequest(rc, ofEpochMilli(2))
        _ = acs.setCreateAddHook { contracts =>
          FutureUnlessShutdown.pure {
            assert(
              contracts.toSet == Set(
                (coid21, initialReassignmentCounter, TimeOfChange(ofEpochMilli(2)))
              )
            )
            checkContractState(cd, coid21, active, tor, 0, 0, 1)(s"Contract $coid01 is active")
            checkContractStateAbsent(cd, coid22)(
              s"Rolled-back creation for contract $coid22 is evicted"
            )
          }
        }
        _ = acs.setArchivePurgeHook { contracts =>
          FutureUnlessShutdown.pure {
            assert(contracts.toSet == Set[(?, TimeOfChange)]((coid00, tor)))
            checkContractState(cd, coid00, Archived, tor, 0, 0, 1)(
              s"Contract $coid00 is archived with pending write"
            )
            checkContractStateAbsent(cd, coid01)(s"Non-archived contract $coid01 is evicted.")
          }
        }
        fin <- cd
          .finalizeRequest(
            mkCommitSet(
              arch = Set(coid00),
              create = Set(coid21),
            ),
            tor,
          )
          .flatten

        _ = assert(fin == Either.unit)

        _ = checkContractStateAbsent(cd, coid00)(s"evict archived contract $coid00")
        _ = checkContractStateAbsent(cd, coid01)(s"evict unlocked non-archived contract $coid01")
        _ = checkContractStateAbsent(cd, coid21)(s"evict created contract $coid21")
      } yield succeed
    }

    "rollback archival while contract is being created" inUS {
      val rc = RequestCounter(10)
      val ts = ofEpochMilli(10)
      val tor = TimeOfRequest(rc, ts)
      val cd = mkCd()
      val actSet0 =
        mkActivenessSet(create = Set(coid00, coid01))
      val commitSet0 =
        mkCommitSet(create = Set(coid00, coid01))
      val actSet1 =
        mkActivenessSet(deact = Set(coid00))
      for {
        cr0 <- prefetchAndCheck(cd, rc, actSet0)
        _ = cr0 shouldBe mkActivenessResult()
        cr1 <- prefetchAndCheck(cd, rc + 1L, actSet1)
        _ = cr1 shouldBe mkActivenessResult(locked = Set(coid00))
        _ = checkContractState(cd, coid00, 0, 1 + 1, 0)(
          s"Nonexistent contract $coid00 is locked for activation and deactivation"
        )

        fin1 <- cd
          .finalizeRequest(CommitSet.empty, TimeOfRequest(rc + 1, ts.plusMillis(1)))
          .flatten

        _ = assert(fin1 == Either.unit)
        _ = checkContractState(cd, coid00, 0, 1, 0)(
          s"Rollback of request ${rc + 1} releases the deactivation lock."
        )

        fin0 <- cd.finalizeRequest(commitSet0, tor).flatten
        _ = fin0 shouldBe Either.unit
        _ = forEvery(Seq(coid00, coid01)) { coid =>
          checkContractStateAbsent(cd, coid)(s"created contract $coid is evicted")
        }
      } yield succeed
    }

    "detect conflicts" inUS {
      val rc = RequestCounter(10)
      val ts = CantonTimestamp.assertFromInstant(Instant.parse("2050-10-11T00:00:10.00Z"))
      val tor = TimeOfRequest(rc, ts)
      val tor0 = TimeOfRequest(RequestCounter(0), ts.minusMillis(10))
      val tor1 = TimeOfRequest(RequestCounter(1), ts.minusMillis(5))
      val tor2 = TimeOfRequest(RequestCounter(2), ts.minusMillis(1))
      for {
        rawAcs <- mkAcs(
          (coid00, tor0, active),
          (coid01, tor0, active),
          (coid01, tor1, Archived),
          (coid10, tor2, active),
          (coid11, tor2, active),
        )
        acs = new HookedAcs(rawAcs)(parallelExecutionContext)
        cd = mkCd(acs)

        // Activeness check for the first request
        actSet1 = mkActivenessSet(
          deact = Set(coid00, coid11),
          useOnly = Set(coid10),
          create = Set(coid20, coid21),
        )
        cr1 <- prefetchAndCheck(cd, rc, actSet1)
        _ = cr1 shouldBe mkActivenessResult()
        _ = checkContractState(cd, coid00, active, tor0, 0, 1, 0)(
          s"locked consumed contract $coid00"
        )
        _ = checkContractState(cd, coid11, active, tor2, 0, 1, 0)(
          s"locked consumed contract $coid01"
        )
        _ = checkContractStateAbsent(cd, coid10)(s"evict used-only contract $coid10")

        // Prefetch a third request
        actSet3 = mkActivenessSet(
          deact = Set(coid00, coid11),
          useOnly = Set(coid01, coid10, coid21),
          prior = Set(coid01, coid21),
        )
        actRes3 = mkActivenessResult(
          locked = Set(coid00, coid10, coid21),
          notActive = Map(coid01 -> Archived, coid11 -> Archived),
          prior = Map(coid01 -> Some(Archived)),
        )
        _ <- cd.registerActivenessSet(rc + 2, actSet3)

        // Activeness check for the second request
        rc2 = rc + 1L
        actSet2 = mkActivenessSet(
          deact = Set(coid00, coid10, coid20, coid21),
          useOnly = Set(coid11, coid01),
          prior = Set(coid00, coid10, coid20),
        )
        actRes2 = mkActivenessResult(
          locked = Set(coid00, coid11, coid20, coid21),
          notActive = Map(coid01 -> Archived),
          prior = Map(coid10 -> Some(active)),
        )
        cr2 <- prefetchAndCheck(cd, rc2, actSet2)
        _ = assert(cr2 == actRes2)
        _ = checkContractState(cd, coid00, active, tor0, 1, 2, 0)(s"locked $coid00 twice")
        _ = checkContractState(cd, coid10, active, tor2, 1, 1, 0)(
          s"locked $coid10 once by request $rc2"
        )
        _ = checkContractState(cd, coid11, active, tor2, 1, 1, 0)(
          s"used-only contract $coid11 remains locked once"
        )
        _ = checkContractState(cd, coid01, Archived, tor1, 1, 0, 0)(
          s"keep inactive contract $coid01 with pending activeness check"
        )
        _ = checkContractState(cd, coid20, 0, 1 + 1, 0)(s"Contract $coid20 in creation is locked")
        _ = checkContractState(cd, coid21, 1, 1 + 1, 0)(s"Contract $coid21 in creation is locked")

        // Check that the in-memory states of contracts are as expected after finalizing the first request, but before the updates are persisted
        _ = acs.setCreateAddHook { _ =>
          FutureUnlessShutdown.pure {
            checkContractState(cd, coid20, active, tor, 0, 1, 1)(s"Contract $coid20 remains locked")
            checkContractState(cd, coid21, 1, 1, 0)(
              s"Contract $coid21 is rolled back and remains locked"
            )
          }
        }
        _ = acs.setArchivePurgeHook { _ =>
          FutureUnlessShutdown.pure {
            checkContractState(cd, coid00, active, tor0, 1, 1, 0)(s"$coid00 remains locked once")
            checkContractState(cd, coid11, Archived, tor, 1, 0, 1)(s"$coid11 is being archived")
          }
        }
        commitSet1 = mkCommitSet(
          arch = Set(coid11),
          create = Set(coid20),
        )
        fin1 <- cd.finalizeRequest(commitSet1, tor).flatten
        _ = assert(fin1 == Either.unit)
        _ = checkContractState(cd, coid00, active, tor0, 1, 1, 0)(s"$coid00 remains locked once")
        _ = checkContractState(cd, coid11, Archived, tor, 1, 0, 0)(
          s"Archived $coid11 remains due to pending activation check"
        )
        _ = checkContractState(cd, coid20, active, tor, 0, 1, 0)(
          s"Created contract $coid20 remains locked"
        )
        _ = checkContractState(cd, coid21, 1, 1, 0)(s"Rolled back $coid21 remains locked")

        // Activeness check for the third request
        cr3 <- cd.checkActivenessAndLock(rc + 2)
        _ = assert(cr3 == actRes3)
        _ = checkContractState(cd, coid00, active, tor0, 0, 2, 0)(
          s"Contract $coid00 is locked twice"
        )
        _ = checkContractStateAbsent(cd, coid01)(s"Inactive contract $coid01 is not kept in memory")
        _ = checkContractState(cd, coid11, Archived, tor, 0, 1, 0)(
          s"Archived contract $coid11 is locked nevertheless"
        )
        _ = checkContractState(cd, coid10, active, tor2, 0, 1, 0)(
          s"Used-only contract $coid10 remains locked once"
        )
      } yield succeed
    }

    "detect duplicate creates" inUS {
      val tor0 = TimeOfRequest(RequestCounter(0), Epoch)
      val tor1 = TimeOfRequest(RequestCounter(1), ofEpochMilli(1))
      for {
        rawAcs <- mkAcs((coid00, tor0, active))
        acs = new HookedAcs(rawAcs)(parallelExecutionContext)
        cd = mkCd(acs)

        // Activeness check for the first request
        actSet0 = mkActivenessSet(
          create = Set(coid00, coid01, coid11)
        )
        cr0 <- prefetchAndCheck(cd, RequestCounter(1), actSet0)
        _ = assert(
          cr0 == mkActivenessResult(notFresh = Set(coid00))
        )
        _ = checkContractState(cd, coid00, active, tor0, 0, 1, 0)(
          s"lock for activation the existing contract $coid00"
        )
        _ = checkContractState(cd, coid01, 0, 1, 0)(
          s"lock non-existing contract $coid01 for creation"
        )

        // Activeness check for the second request
        actSet1 = mkActivenessSet(
          deact = Set(coid11),
          create = Set(coid01, coid10),
        )
        actRes1 = mkActivenessResult(locked = Set(coid11, coid01))
        cr1 <- prefetchAndCheck(cd, RequestCounter(2), actSet1)
        _ = assert(cr1 == actRes1)
        _ = checkContractState(cd, coid01, 0, 2, 0)(
          s"Contract $coid01 is locked twice for activation"
        )
        _ = checkContractState(cd, coid10, 0, 1, 0)(s"lock contract $coid10 for creation")
        _ = checkContractState(cd, coid11, 0, 1 + 1, 0)(
          s"locked-for-creation contract $coid11 is locked for deactivation"
        )

        // Finalize first request and make sure that the in-memory states are up to date while the ACS updates are being written
        _ = acs.setCreateAddHook { _ =>
          FutureUnlessShutdown.pure {
            checkContractState(cd, coid01, active, tor1, 0, 1, 1)(
              s"Contract $coid01 is being created"
            )
            checkContractState(cd, coid11, 0, 1, 0)(s"Rolled-back contract $coid11 remains locked")
          }
        }
        fin1 <- cd
          .finalizeRequest(mkCommitSet(create = Set(coid01)), tor1)
          .flatten
        _ = assert(fin1 == Either.unit)
      } yield succeed
    }

    "support transient contracts" inUS {
      val rc = RequestCounter(0)
      val ts = ofEpochMilli(100)
      val tor = TimeOfRequest(rc, ts)
      for {
        acs <- mkAcs()
        cd = mkCd(acs)
        actSet = mkActivenessSet(
          create = Set(coid00, coid01, coid10, coid11),
          prior = Set(coid00),
        )
        actRes = mkActivenessResult(prior = Map(coid00 -> None))
        commitSet = mkCommitSet(
          arch = Set(coid00, coid10),
          create = Set(coid01, coid00),
        )
        _ <- singleCRwithTR(cd, rc, actSet, actRes, commitSet, ts)

        _ <- checkContractState(acs, coid00, (Archived, tor))(
          s"transient contract $coid00 is archived"
        )
        _ <- checkContractState(acs, coid01, (active, tor))(s"contract $coid01 is created")
        _ <- checkContractState(acs, coid10, (Archived, tor))(
          s"contract $coid10 is archived, but not created"
        )
        _ <- checkContractState(acs, coid11, None)(s"contract $coid11 does not exist")
      } yield succeed
    }

    "handle double archival" inUS {
      val rc = RequestCounter(10)
      val ts = ofEpochMilli(100)
      val ts1 = ts.plusMillis(1)
      val ts2 = ts.plusMillis(3)

      val tor = TimeOfRequest(rc, ts)
      val tor0 = TimeOfRequest(RequestCounter(0), Epoch)
      val tor1 = TimeOfRequest(rc + 1, ts1)
      val tor2 = TimeOfRequest(rc + 2, ts2)

      for {
        rawAcs <- mkAcs(
          (coid00, tor0, active),
          (coid01, tor0, active),
          (coid10, tor0, active),
          (coid11, tor0, active),
          (coid20, tor0, active),
        )
        acs = new HookedAcs(rawAcs)(parallelExecutionContext)
        cd = mkCd(acs)

        // Prefetch three requests in reversed order
        _ <- cd.registerActivenessSet(rc + 2L, mkActivenessSet(deact = Set(coid10)))
        _ <- cd
          .registerActivenessSet(
            rc + 1,
            mkActivenessSet(deact = Set(coid00, coid10, coid11, coid20)),
          )

        _ <- cd
          .registerActivenessSet(
            rc,
            mkActivenessSet(deact = Set(coid00, coid01, coid10, coid20)),
          )

        // Activeness check for first request
        cr0 <- cd.checkActivenessAndLock(rc)
        _ = cr0 shouldBe mkActivenessResult()

        // Activeness check for second request
        cr1 <- cd.checkActivenessAndLock(rc + 1)
        _ = assert(cr1 == mkActivenessResult(locked = Set(coid00, coid10, coid20)))
        _ = Seq((coid00, 2), (coid10, 2), (coid01, 1), (coid11, 1), (coid20, 2)).foreach {
          case (coid, locks) =>
            checkContractState(cd, coid, active, tor0, if (coid == coid10) 1 else 0, locks, 0)(
              s"Contract $coid is locked by $locks requests"
            )
        }

        // Finalize second request
        _ = acs.setArchivePurgeHook { _ =>
          FutureUnlessShutdown.pure {
            checkContractState(cd, coid00, Archived, tor1, 0, 1, 1)(
              s"Archival for $coid00 retains the lock for the other request"
            )
            checkContractState(cd, coid11, Archived, tor1, 0, 0, 1)(
              s"Contract $coid11 is being archived"
            )
            checkContractState(cd, coid10, active, tor0, 1, 1, 0)(
              s"Lock on $coid10 is released once"
            )
          }
        }
        fin1 <- cd
          .finalizeRequest(mkCommitSet(arch = Set(coid00, coid11, coid20)), tor1)
          .flatten

        _ = assert(fin1 == Either.unit)
        _ <- List(coid00 -> 1, coid11 -> 0, coid20 -> 1).parTraverse_ { case (coid, locks) =>
          if (locks > 0) {
            checkContractState(cd, coid, Archived, tor1, 0, locks, 0)(
              s"Archived contract $coid is retained because of more locks"
            )
          } else {
            checkContractStateAbsent(cd, coid)(s"Archived contract $coid is evicted")
          }
          checkContractState(acs, coid, (Archived, tor1))(
            s"contract $coid is archived by request ${rc + 1L}"
          )
        }

        // Activeness check for third request
        cr2 <- cd.checkActivenessAndLock(rc + 2L)
        _ = assert(cr2 == mkActivenessResult(locked = Set(coid10)))

        // Finalize first request
        _ = acs.setArchivePurgeHook { _ =>
          FutureUnlessShutdown.pure {
            checkContractState(cd, coid00, Archived, tor1, 0, 0, 1)(
              s"Double archived contract $coid00 has a pending write"
            )
            checkContractState(cd, coid01, Archived, tor, 0, 0, 1)(
              s"Contract $coid01 is being archived"
            )
            checkContractState(cd, coid10, Archived, tor, 0, 1, 1)(
              s"Contract $coid10 is being archived"
            )
            checkContractStateAbsent(cd, coid20)(
              s"Unlocking the archived contract $coid20 leaves it evicted"
            )
          }
        }
        fin0 <- cd
          .finalizeRequest(mkCommitSet(arch = Set(coid00, coid01, coid10)), tor)
          .flatten

        _ = assert(
          fin0 == Left(NonEmptyChain(AcsError(DoubleContractArchival(coid00, tor1, tor)))),
          s"double archival of $coid00 is reported",
        )
        _ <- checkContractState(acs, coid00, (Archived, tor1))(
          s"contract $coid00 is double archived by request $rc"
        )
        _ <- List(coid01, coid10).parTraverse_ { coid =>
          checkContractState(acs, coid, (Archived, tor))(s"contract $coid is archived as usual")
        }

        // Finalize third request
        fin2 <- cd.finalizeRequest(mkCommitSet(arch = Set(coid10)), tor2).flatten
        _ = assert(
          fin2 == Left(NonEmptyChain(AcsError(DoubleContractArchival(coid10, tor, tor2)))),
          s"double archival of $coid01 is reported",
        )
        _ <- checkContractState(acs, coid10, (Archived, tor2))(
          s"contract archival for $coid10 is overwritten"
        )
      } yield succeed
    }

    "lock inactive contracts for deactivation" inUS {
      val tor0 = TimeOfRequest(RequestCounter(0), Epoch)
      val tor1 = TimeOfRequest(RequestCounter(1), ofEpochMilli(1))
      for {
        acs <- mkAcs(
          (coid00, tor0, active),
          (coid01, tor0, active),
          (coid01, tor0, Archived),
        )
        cd = mkCd(acs)

        cr0 <- prefetchAndCheck(cd, tor1.rc, mkActivenessSet(deact = Set(coid00, coid01)))
        _ = assert(cr0 == mkActivenessResult(notActive = Map(coid01 -> Archived)))
        _ = checkContractState(cd, coid01, Archived, tor0, 0, 1, 0)(
          s"Archived contract $coid01 is locked."
        )
        fin0 <- cd
          .finalizeRequest(mkCommitSet(arch = Set(coid00, coid01)), tor1)
          .flatten

        _ = assert(fin0 == Left(Chain.one(AcsError(DoubleContractArchival(coid01, tor0, tor1)))))
        _ = checkContractStateAbsent(cd, coid01)(s"Double archived contract remains archived")
        _ <- checkContractState(acs, coid00, (Archived, tor1))(s"contract $coid00 gets archived")
      } yield succeed
    }

    "lock existing contracts for activation" inUS {
      val tor0 = TimeOfRequest(RequestCounter(0), Epoch)
      val tor1 = TimeOfRequest(RequestCounter(1), ofEpochMilli(2))
      for {
        acs <- mkAcs((coid00, tor0, active), (coid01, tor0, active), (coid01, tor0, Archived))
        cd = mkCd(acs)

        cr1 <- prefetchAndCheck(cd, tor1.rc, mkActivenessSet(create = Set(coid01, coid10)))
        _ = assert(cr1 == mkActivenessResult(notFresh = Set(coid01)))
        _ = checkContractState(cd, coid01, Archived, tor0, 0, 1, 0)(
          s"Existing contract $coid01 is locked."
        )
        fin1 <- cd
          .finalizeRequest(mkCommitSet(create = Set(coid01, coid10)), tor1)
          .flatten

        _ = assert(
          fin1.left.value.toList.toSet == Set(
            AcsError(DoubleContractCreation(coid01, tor0, tor1)),
            AcsError(ChangeAfterArchival(coid01, tor0, tor1)),
          )
        )
        _ <- checkContractState(acs, coid10, (active, tor1))(s"contract $coid10 is created")
      } yield succeed
    }

    "complain about invalid commit set" inUS {
      def checkInvalidCommitSet(cd: ConflictDetector, rc: RequestCounter, ts: CantonTimestamp)(
          activenessSet: ActivenessSet,
          commitSet: CommitSet,
      )(clue: String): FutureUnlessShutdown[Assertion] =
        for {
          cr <- prefetchAndCheck(cd, rc, activenessSet)
          _ = assert(cr == mkActivenessResult(), clue)
          error <- loggerFactory.suppressWarningsAndErrors {
            cd.finalizeRequest(commitSet, TimeOfRequest(rc, ts)).flatten.transform {
              case Failure(t) => Success(UnlessShutdown.Outcome(t))
              case Success(_v) => Failure(new NoSuchElementException(s"Future did not fail. $clue"))
            }
          }
        } yield assert(error.isInstanceOf[InvalidCommitSet])

      val tor0 = TimeOfRequest(RequestCounter(0), Epoch)
      for {
        acs <- mkAcs((coid00, tor0, active))
        reassignmentCacheAndIds <- mkReassignmentCache(loggerFactory)(
          (sourceSynchronizer1, mediator1),
          (sourceSynchronizer2, mediator2),
        )
        (reassignmentCache, reassignmentIds) = reassignmentCacheAndIds
        cd = mkCd(acs, reassignmentCache)

        _ <- checkInvalidCommitSet(cd, RequestCounter(1), ofEpochMilli(2))(
          mkActivenessSet(deact = Set(coid00)),
          mkCommitSet(arch = Set(coid00, coid10)),
        )("Archive non-locked contract")
        _ <- checkContractState(acs, coid00, (active, tor0))(s"contract $coid00 remains active")

        _ <- checkInvalidCommitSet(cd, RequestCounter(2), ofEpochMilli(2))(
          mkActivenessSet(create = Set(coid01)),
          mkCommitSet(create = Set(coid01, coid10)),
        )("Create non-locked contract")
        _ <- checkContractState(acs, coid01, None)(s"contract $coid01 remains non-existent")

        _ <- checkInvalidCommitSet(cd, RequestCounter(3), ofEpochMilli(3))(
          mkActivenessSet(
            assign = Set(coid01),
            reassignmentIds = Set(reassignmentIds(0), reassignmentIds(1)),
          ),
          mkCommitSet(assign =
            Map(
              coid00 -> (sourceSynchronizer1, reassignmentIds(0)),
              coid01 -> (sourceSynchronizer2, reassignmentIds(1)),
            )
          ),
        )("Assigned contract not locked.")

        _ <- checkInvalidCommitSet(cd, RequestCounter(4), ofEpochMilli(4))(
          mkActivenessSet(useOnly = Set(coid00)),
          mkCommitSet(unassign =
            Map(coid00 -> (sourceSynchronizer1.unwrap -> reassignmentCounter1))
          ),
        )("Unassigned contract only used, not locked.")
      } yield succeed
    }

    "opportunistic follow-up" inUS {
      val rc = RequestCounter(10)
      val ts = ofEpochMilli(10)
      val tor0 = TimeOfRequest(rc, ts)
      val tor1 = TimeOfRequest(rc + 1L, ts.plusMillis(1))

      for {
        rawAcs <- mkAcs()
        acs = new HookedAcs(rawAcs)(parallelExecutionContext)
        cd = mkCd(acs)

        // Activeness check of first request
        actSet0 = mkActivenessSet(create = Set(coid00, coid01, coid10))
        cr0 <- prefetchAndCheck(cd, rc, actSet0)
        _ = cr0 shouldBe mkActivenessResult()

        // Activeness check of second request
        cr1 <- prefetchAndCheck(
          cd,
          rc + 1,
          mkActivenessSet(deact = Set(coid00, coid10), prior = Set(coid00, coid10)),
        )
        _ = cr1 shouldBe mkActivenessResult(locked = Set(coid00, coid10))
        _ = Seq(coid00 -> 1, coid01 -> 0, coid10 -> 1).foreach { case (coid, deactivations) =>
          checkContractState(cd, coid, 0, 1 + deactivations, 0)(
            s"Contract $coid in creation is locked $deactivations times"
          )
        }

        // Finalize second request
        fin1 <- cd
          .finalizeRequest(mkCommitSet(arch = Set(coid00, coid10)), tor1)
          .flatten

        _ = fin1 shouldBe Either.unit
        _ <- List(coid00 -> 0, coid10 -> 0).parTraverse_ { case (coid, deactivationLocks) =>
          checkContractState(cd, coid, Archived, tor1, 0, 1 + deactivationLocks, 0)(
            s"contract $coid archived by opportunistic follow-up still locked"
          )
          checkContractState(acs, coid, (Archived, tor1))(s"contract $coid is archived")
        }

        // Finalize first request
        _ = acs.setCreateAddHook { _ =>
          FutureUnlessShutdown.pure {
            checkContractState(cd, coid00, Archived, tor1, 0, 0, 1)(
              s"Contract $coid00 has a pending creation, but remains archived"
            )
            checkContractState(cd, coid10, Archived, tor1, 0, 0, 1)(
              s"Transient contract $coid10 has one pending writes."
            )
            checkContractState(cd, coid01, active, tor0, 0, 0, 1)(
              s"Contract $coid01 is being created."
            )
          }
        }
        commitSet0 = mkCommitSet(create = Set(coid00, coid01, coid10), arch = Set(coid10))
        fin0 <- cd.finalizeRequest(commitSet0, tor0).flatten
        _ = fin0 shouldBe Left(NonEmptyChain(AcsError(DoubleContractArchival(coid10, tor1, tor0))))
        _ <- checkContractState(acs, coid00, (Archived, tor1))(s"contract $coid00 remains archived")
        _ <- checkContractState(acs, coid01, (active, tor0))(s"contract $coid01 is active")
        _ <- checkContractState(acs, coid10, (Archived, tor1))(
          s"transient contract $coid10 is archived twice"
        )
      } yield succeed
    }

    "create a rolled back contract after it has been evicted" inUS {
      val tor1 = TimeOfRequest(RequestCounter(1), ofEpochMilli(1))
      for {
        acs <- mkAcs()
        cd = mkCd(acs)

        cr0 <- prefetchAndCheck(cd, RequestCounter(0), mkActivenessSet(create = Set(coid00)))
        _ = cr0 shouldBe mkActivenessResult()
        fin0 <- cd
          .finalizeRequest(CommitSet.empty, TimeOfRequest(RequestCounter(0), Epoch))
          .flatten

        _ = assert(fin0 == Either.unit)
        _ = checkContractStateAbsent(cd, coid00)(s"Rolled back contract $coid00 is evicted")

        // Re-creating rolled-back contract coid00
        cr1 <- prefetchAndCheck(cd, RequestCounter(1), mkActivenessSet(create = Set(coid00)))
        _ = cr1 shouldBe mkActivenessResult()
        fin1 <- cd.finalizeRequest(mkCommitSet(create = Set(coid00)), tor1).flatten
        _ = fin1 shouldBe Either.unit
        _ <- checkContractState(acs, coid00, (active, tor1))(s"Contract $coid00 created")
      } yield succeed
    }

    "cannot create a rolled back contract before it is evicted" inUS {
      val cd = mkCd()
      for {
        cr0 <- prefetchAndCheck(cd, RequestCounter(0), mkActivenessSet(create = Set(coid00)))
        _ = cr0 shouldBe mkActivenessResult()

        cr1 <- prefetchAndCheck(cd, RequestCounter(1), mkActivenessSet(deact = Set(coid00)))
        _ = assert(cr1 == mkActivenessResult(locked = Set(coid00)))

        fin0 <- cd
          .finalizeRequest(CommitSet.empty, TimeOfRequest(RequestCounter(0), Epoch))
          .flatten

        _ = assert(fin0 == Either.unit)
        _ = checkContractState(cd, coid00, 0, 1, 0)(s"Rolled back contract $coid00 is locked")

        cr2 <- prefetchAndCheck(cd, RequestCounter(2), mkActivenessSet(create = Set(coid00)))
        _ = assert(
          cr2 == mkActivenessResult(locked = Set(coid00)),
          s"Rolled-back contract $coid00 cannot be re-created",
        )
      } yield succeed
    }

    "interleave ACS updates with further requests" inUS {
      val torN1 = TimeOfRequest(RequestCounter(-1), Epoch)
      val tor0 = TimeOfRequest(RequestCounter(0), ofEpochMilli(1))
      val tor1 = TimeOfRequest(RequestCounter(1), ofEpochMilli(2))
      val tor3 = TimeOfRequest(RequestCounter(3), ofEpochMilli(4))

      val actSet0 = mkActivenessSet(
        create = Set(coid10, coid11, coid20, coid22),
        deact = Set(coid00, coid01, coid21),
      )
      val actRes0 = mkActivenessResult(locked = Set(coid20, coid11, coid00, coid10))
      val commitSet0 = mkCommitSet(
        arch = Set(coid00, coid01, coid20, coid11),
        create = Set(coid10, coid20, coid22),
      )

      val actSet1 = mkActivenessSet(deact = Set(coid20, coid11, coid10, coid00))
      val commitSet1 = mkCommitSet(arch = Set(coid00, coid20, coid11))

      val actSet2 = mkActivenessSet(
        create = Set(coid10),
        deact = Set(coid00, coid21, coid20, coid11, coid22),
        prior = Set(coid21, coid22),
      )
      val actRes2 = mkActivenessResult(
        locked = Set(coid00, coid11, coid20),
        notFresh = Set(coid10),
        prior = Map(coid21 -> Some(active), coid22 -> Some(active)),
      )

      val actSet3 = mkActivenessSet(deact = Set(coid20))

      val actRes3 = mkActivenessResult(locked = Set(coid20))

      val finF1Complete = Promise[Unit]()

      def finalizeForthRequest(cd: ConflictDetector) =
        for {
          fin3 <- cd.finalizeRequest(mkCommitSet(arch = Set(coid20)), tor3).flatten
          _ <-
            FutureUnlessShutdown.outcomeF(
              finF1Complete.future
            ) // Delay ACs updates until outer finalizeRequest future has completed
        } yield {
          assert(fin3 == Either.unit)
          checkContractState(cd, coid20, Archived, tor3, 0, 1, 2)(s"Contract $coid20 is archived")
          ()
        }

      val finF0Complete = Promise[Unit]()

      def storeHookRequest0(cd: ConflictDetector, acs: HookedAcs) = {
        // This runs while request 0's ACS updates are written
        checkContractState(cd, coid00, Archived, tor0, 0, 1, 1)(
          s"Contract $coid00 being archived remains locked."
        )
        checkContractState(cd, coid01, Archived, tor0, 0, 0, 1)(
          s"Contract $coid01 has a pending archival."
        )
        checkContractState(cd, coid10, active, tor0, 0, 1, 1)(
          s"Contract $coid10 has an opportunistic follow-up."
        )
        checkContractState(cd, coid11, Archived, tor0, 0, 1, 1)(
          s"Rolled-back transient contract $coid11 remains locked."
        )
        checkContractState(cd, coid20, Archived, tor0, 0, 2, 1)(
          s"Transient contract $coid20 remains locked twice."
        )
        checkContractStateAbsent(cd, coid21)(s"Contract $coid21 is evicted.")
        checkContractState(cd, coid22, active, tor0, 0, 0, 1)(s"Contract $coid22 is being created.")

        // Run another request while the updates are in-flight
        for {
          // Activeness check for the third request
          cr2 <- prefetchAndCheck(cd, RequestCounter(2), actSet2)
          _ = assert(cr2 == actRes2)
          _ = checkContractState(cd, coid00, Archived, tor0, 0, 2, 1)(
            s"Contract $coid00 is locked once more."
          )
          _ = checkContractState(cd, coid10, active, tor0, 0, 1 + 1, 1)(
            s"Contract $coid10 in creation is locked for activation again."
          )
          _ = checkContractState(cd, coid11, Archived, tor0, 0, 2, 1)(
            s"contract $coid11 is locked once more."
          )
          _ = checkContractState(cd, coid20, Archived, tor0, 0, 3, 1)(
            s"Contract $coid20 is locked three times."
          )
          _ = checkContractState(cd, coid21, active, torN1, 0, 1, 0)(s"Contract $coid21 is locked.")
          _ = checkContractState(cd, coid22, active, tor0, 0, 1, 1)(
            s"Created contract $coid22 is locked."
          )

          // Finalize the second request
          _ = acs.setArchivePurgeHook(_ =>
            finalizeForthRequest(cd)
          ) // This runs while request 1's ACS updates are written
          finF1 <- cd.finalizeRequest(commitSet1, tor1)
          _ = finF1Complete.success(())
          fin1 <- finF1
          _ = assert(
            fin1 == Left(NonEmptyChain(AcsError(DoubleContractArchival(coid20, tor3, tor1))))
          )
          _ = checkContractState(cd, coid00, Archived, tor1, 0, 1, 1)(
            s"Archived contract $coid00 remains locked."
          )
          _ = checkContractState(cd, coid10, active, tor0, 0, 1, 1)(
            s"Writing the creation for contract $coid10 is pending."
          )
          _ = checkContractState(cd, coid11, Archived, tor1, 0, 1, 1)(
            s"Archived contract $coid11 has still a pending write."
          )
          _ = checkContractState(cd, coid20, Archived, tor3, 0, 1, 1)(
            s"Contract $coid20 has its archival not updated."
          )
          _ <-
            FutureUnlessShutdown.outcomeF(
              finF0Complete.future
            ) // Delay ACS updates until the outer finalizeRequest future has completed
        } yield ()
      }

      for {
        rawAcs <- mkAcs(
          (coid00, torN1, active),
          (coid01, torN1, active),
          (coid21, torN1, active),
        )
        acs = new HookedAcs(rawAcs)(parallelExecutionContext)
        cd = mkCd(acs)

        // Activeness check for first request
        cr0 <- prefetchAndCheck(cd, RequestCounter(0), actSet0)
        _ = cr0 shouldBe mkActivenessResult()

        // Activeness check for second request
        cr1 <- prefetchAndCheck(cd, RequestCounter(1), actSet1)
        _ = cr1 shouldBe actRes0

        _ = Seq(
          (coid00, Some((active, torN1)), 0, 2),
          (coid01, Some((active, torN1)), 0, 1),
          (coid10, None, 1, 1),
          (coid11, None, 1, 1),
          (coid20, None, 1, 1),
          (coid21, Some((active, torN1)), 0, 1),
          (coid22, None, 1, 0),
        ).foreach {
          case (coid, Some((status, version)), activationLock, deactivationLock) =>
            checkContractState(cd, coid, status, version, 0, activationLock + deactivationLock, 0)(
              s"State of existing contract $coid"
            )
          case (coid, None, activationLock, deactivationLock) =>
            checkContractState(cd, coid, 0, activationLock + deactivationLock, 0)(
              s"State of nonexistent contract $coid"
            )
        }

        // Activeness check for fourth request
        cr3 <- prefetchAndCheck(cd, RequestCounter(3), actSet3)
        _ = cr3 shouldBe actRes3

        // Finalize the first request and do a lot of stuff while the updates are being written
        _ = acs.setArchivePurgeHook(_ => storeHookRequest0(cd, acs))
        finF0 <- cd.finalizeRequest(commitSet0, tor0)
        _ = finF0Complete.success(())
        fin0 <- finF0
        _ =
          fin0.leftMap(_.toList.toSet) shouldBe Left(
            Set(
              AcsError(DoubleContractArchival(coid00, tor1, tor0)),
              AcsError(DoubleContractArchival(coid11, tor1, tor0)),
              AcsError(DoubleContractArchival(coid20, tor1, tor0)),
            )
          )
        _ = checkContractState(cd, coid00, Archived, tor1, 0, 1, 0)(
          s"Contract $coid00 remains locked."
        )
        _ = checkContractStateAbsent(cd, coid01)(s"Contract $coid01 has been evicted.")
        _ = checkContractState(cd, coid10, active, tor0, 0, 1, 0)(
          s"Contract $coid10 remains locked."
        )
        _ = checkContractState(cd, coid11, Archived, tor1, 0, 1, 0)(
          s"Contract $coid11 remains locked."
        )
        _ = checkContractState(cd, coid20, Archived, tor3, 0, 1, 0)(
          s"Contract $coid20 remains locked."
        )
        _ = checkContractState(cd, coid21, active, torN1, 0, 1, 0)(
          s"Contract $coid21 remains locked."
        )
        _ = checkContractState(cd, coid22, active, tor0, 0, 1, 0)(
          s"Contract $coid22 remains locked."
        )
      } yield succeed
    }

    "assign unknown contracts" inUS {
      for {
        acs <- mkAcs()
        reassignmentCacheAndIds <- mkReassignmentCache(loggerFactory)(
          (sourceSynchronizer1, mediator1),
          (sourceSynchronizer2, mediator2),
        )
        (reassignmentCache, reassignmentIds) = reassignmentCacheAndIds
        cd = mkCd(acs, reassignmentCache)
        ts = ofEpochMilli(1)
        actSet = mkActivenessSet(
          assign = Set(coid00, coid01, coid10),
          reassignmentIds = Set(reassignmentIds(0)),
        ) // omit reassignment2 to mimick a non-reassigning participant
        assignment <- prefetchAndCheck(cd, RequestCounter(0), actSet)
        _ = assignment shouldBe mkActivenessResult()
        _ = Seq(coid00, coid01, coid10).foreach { coid =>
          checkContractState(cd, coid00, 0, 1, 0)(s"Contract $coid is locked for activation.")
        }
        commitSet = mkCommitSet(assign =
          Map(
            coid00 -> (sourceSynchronizer1, reassignmentIds(0)),
            coid01 -> (sourceSynchronizer2, reassignmentIds(1)),
          )
        )
        tor = TimeOfRequest(RequestCounter(0), ts)
        finTxIn <- cd.finalizeRequest(commitSet, tor).flatten
        _ = assert(finTxIn == Either.unit)
        _ = Seq(coid00, coid01, coid10).foreach { coid =>
          checkContractStateAbsent(cd, coid)(s"Contract $coid is evicted.")
        }
        fetch00 <- acs.fetchState(coid00)
        fetch01 <- acs.fetchState(coid01)
        fetch10 <- acs.fetchState(coid10)
        lookup1 <- reassignmentCache.lookup(reassignmentIds(0)).value
        lookup2 <- reassignmentCache.lookup(reassignmentIds(1)).value
      } yield {
        assert(
          fetch00.contains(AcsContractState(active, tor)),
          s"Contract $coid00 is active.",
        )
        assert(
          fetch01.contains(AcsContractState(active, tor)),
          s"Contract $coid01 is active.",
        )
        assert(fetch10.isEmpty, s"Contract $coid10 remains unknown.")
        assert(
          lookup1 == Left(ReassignmentCompleted(reassignmentIds(0), tor.timestamp)),
          s"$reassignmentIds(0 completed",
        )
        assert(
          lookup2.exists(_.reassignmentId == reassignmentIds(1)),
          s"${reassignmentIds(1)} has not been completed",
        )
      }
    }

    "assign a known contract" inUS {
      val tor0 = TimeOfRequest(RequestCounter(0), Epoch)
      for {
        acs <- mkAcs(
          (coid00, tor0, Archived),
          (coid01, tor0, ReassignedAway(targetSynchronizer1, initialReassignmentCounter)),
        )
        reassignmentCacheAndIds <- mkReassignmentCache(loggerFactory)(
          (sourceSynchronizer1, mediator1)
        ) // Omit reassignment2 to mimic a non-reassigning participant
        (reassignmentCache, reassignmentIds) = reassignmentCacheAndIds
        cd = mkCd(acs, reassignmentCache)
        ts = ofEpochMilli(1)
        tor1 = TimeOfRequest(RequestCounter(1), ts)
        actSet = mkActivenessSet(
          assign = Set(coid00, coid01),
          reassignmentIds = Set(reassignmentIds(0)),
          prior = Set(coid00, coid01),
        )
        commitSet = mkCommitSet(assign =
          Map(
            coid00 -> (sourceSynchronizer1, reassignmentIds(0)),
            coid01 -> (sourceSynchronizer2, reassignment2),
          )
        )
        actRes <- prefetchAndCheck(cd, RequestCounter(1), actSet)
        fin <- cd.finalizeRequest(commitSet, tor1).flatten
        fetch00 <- acs.fetchState(coid00)
        fetch01 <- acs.fetchState(coid01)
        lookup1 <- reassignmentCache.lookup(reassignmentIds(0)).value
        lookup2 <- reassignmentCache.lookup(reassignment2).value
      } yield {
        assert(
          actRes == mkActivenessResult(
            notFree = Map(coid00 -> Archived),
            prior = Map(
              coid00 -> Some(Archived),
              coid01 -> Some(ReassignedAway(targetSynchronizer1, initialReassignmentCounter)),
            ),
          ),
          s"Report that $coid00 was already archived.",
        )
        assert(
          fin == Left(NonEmptyChain(AcsError(ChangeAfterArchival(coid00, tor0, tor1)))),
          s"Report assignment after archival.",
        )
        assert(
          fetch00.contains(AcsContractState(active, tor1)),
          s"Contract $coid00 is assigned.",
        )
        assert(
          fetch01.contains(AcsContractState(active, tor1)),
          s"Contract $coid01 is assigned.",
        )
        assert(
          lookup1 == Left(ReassignmentCompleted(reassignmentIds(0), tor1.timestamp)),
          s"${reassignmentIds(0)} completed",
        )
        assert(
          lookup2 == Left(UnknownReassignmentId(reassignment2)),
          s"$reassignment2 does not exist",
        )
      }
    }

    "unassign several contracts" inUS {
      val tor0 = TimeOfRequest(RequestCounter(0), Epoch)
      val tor = TimeOfRequest(RequestCounter(1), ofEpochMilli(1))
      for {
        acs <- mkAcs((coid00, tor0, active), (coid01, tor0, active))
        cd = mkCd(acs)
        activenessSet = mkActivenessSet(deact = Set(coid00, coid01), prior = Set(coid00, coid01))
        actRes = mkActivenessResult(prior = Map(coid00 -> Some(active), coid01 -> Some(active)))
        commitSet = mkCommitSet(unassign =
          Map(
            coid00 -> (synchronizer1 -> reassignmentCounter1),
            coid01 -> (synchronizer2 -> reassignmentCounter2),
          )
        )
        _ <- singleCRwithTR(cd, tor.rc, activenessSet, actRes, commitSet, tor.timestamp)
        fetch00 <- acs.fetchState(coid00)
        fetch01 <- acs.fetchState(coid01)
      } yield {
        assert(
          fetch00.contains(
            AcsContractState(
              ReassignedAway(targetSynchronizer1, reassignmentCounter1),
              tor,
            )
          ),
          s"Contract $coid00 reassigned to $sourceSynchronizer1.",
        )
        assert(
          fetch01.contains(
            AcsContractState(
              ReassignedAway(targetSynchronizer2, reassignmentCounter2),
              tor,
            )
          ),
          s"Contract $coid01 reassigned to $synchronizer2.",
        )
      }
    }

    "mix reassignments with creations and archivals" inUS {
      val tor0 = TimeOfRequest(RequestCounter(0), Epoch)
      val tor = TimeOfRequest(RequestCounter(1), ofEpochMilli(1))
      for {
        acs <- mkAcs(
          (coid00, tor0, active),
          (coid11, tor0, active),
        )
        _ <- acs
          .assignContract(coid01, tor0, sourceSynchronizer1, reassignmentCounter1)
          .value

        reassignmentCacheAndIds <- mkReassignmentCache(loggerFactory)(
          (sourceSynchronizer2, mediator2)
        )
        (reassignmentCache, reassignmentIds) = reassignmentCacheAndIds
        cd = mkCd(acs, reassignmentCache)
        activenessSet = mkActivenessSet(
          deact = Set(coid00, coid01),
          create = Set(coid10),
          assign = Set(coid20),
          reassignmentIds = Set(reassignmentIds(0)),
          useOnly = Set(coid11),
          prior = Set(coid01, coid00, coid11),
        )
        actRes = mkActivenessResult(prior =
          Map(
            coid00 -> Some(active),
            coid01 -> Some(Active(reassignmentCounter1)),
            coid11 -> Some(active),
          )
        )
        commitSet = mkCommitSet(
          arch = Set(coid00),
          unassign = Map(coid01 -> (synchronizer1 -> reassignmentCounter2)),
          assign = Map(coid20 -> (sourceSynchronizer2, reassignmentIds(0))),
          create = Set(coid10),
        )
        _ <- singleCRwithTR(cd, tor.rc, activenessSet, actRes, commitSet, tor.timestamp)
        fetch00 <- acs.fetchState(coid00)
        fetch01 <- acs.fetchState(coid01)
        fetch10 <- acs.fetchState(coid10)
        fetch11 <- acs.fetchState(coid11)
        fetch20 <- acs.fetchState(coid20)
        lookup2 <- reassignmentCache.lookup(reassignmentIds(0)).value
      } yield {
        assert(
          fetch00.contains(AcsContractState(Archived, tor)),
          s"Contract $coid00 is archived.",
        )
        assert(
          fetch01.contains(
            AcsContractState(
              ReassignedAway(targetSynchronizer1, reassignmentCounter2),
              tor,
            )
          ),
          s"Contract $coid01 is reassigned to $targetSynchronizer1.",
        )
        assert(
          fetch10.contains(AcsContractState(active, tor)),
          s"Contract $coid10 is created.",
        )
        assert(
          fetch11.contains(AcsContractState(active, tor0)),
          s"Contract $coid11 remains active.",
        )
        assert(
          fetch20.contains(AcsContractState(active, tor)),
          s"Contract $coid20 is assigned.",
        )
        assert(
          lookup2 == Left(
            ReassignmentCompleted(reassignmentIds(0), tor.timestamp)
          ),
          s"${reassignmentIds(0)} completed",
        )
      }
    }

    "allow repurposing the activation locks" inUS {
      val tor = TimeOfRequest(RequestCounter(0), Epoch)
      for {
        acs <- mkAcs()
        reassignmentCacheAndIds <- mkReassignmentCache(loggerFactory)(
          (sourceSynchronizer1, mediator1)
        )
        (reassignmentCache, reassignmentIds) = reassignmentCacheAndIds
        cd = mkCd(acs, reassignmentCache)
        activenessSet = mkActivenessSet(
          create = Set(coid00),
          assign = Set(coid01),
          reassignmentIds = Set(reassignmentIds(0)),
        )
        commitSet = mkCommitSet(
          create = Set(coid01),
          assign = Map(coid00 -> (sourceSynchronizer1, reassignmentIds(0))),
        )
        _ <- singleCRwithTR(
          cd,
          tor.rc,
          activenessSet,
          mkActivenessResult(),
          commitSet,
          tor.timestamp,
        )
        fetch00 <- acs.fetchState(coid00)
        fetch01 <- acs.fetchState(coid01)
      } yield {
        assert(
          fetch00.contains(AcsContractState(active, tor)),
          s"Contract $coid00 is assigned.",
        )
        assert(
          fetch01.contains(AcsContractState(active, tor)),
          s"Contract $coid01 is created.",
        )
      }
    }

    "support transient contracts with reassignments" inUS {
      val tor = TimeOfRequest(RequestCounter(0), Epoch)
      for {
        acs <- mkAcs()
        reassignmentCacheAndIds <- mkReassignmentCache(loggerFactory)(
          (sourceSynchronizer1, mediator1),
          (sourceSynchronizer2, mediator2),
        )
        (reassignmentCache, reassignmentIds) = reassignmentCacheAndIds
        cd = mkCd(acs, reassignmentCache)
        activenessSet = mkActivenessSet(
          assign = Set(coid10, coid11),
          reassignmentIds = Set(reassignmentIds(0), reassignmentIds(1)),
          create = Set(coid20),
        )
        commitSet = mkCommitSet(
          create = Set(coid20),
          assign = Map(
            coid10 -> (sourceSynchronizer2, reassignmentIds(1)),
            coid11 -> (sourceSynchronizer1, reassignmentIds(0)),
          ),
          unassign = Map(
            coid20 -> (synchronizer1 -> reassignmentCounter1),
            coid11 -> (synchronizer2 -> reassignmentCounter2),
          ),
          arch = Set(coid10),
        )
        _ <- singleCRwithTR(
          cd,
          tor.rc,
          activenessSet,
          mkActivenessResult(),
          commitSet,
          tor.timestamp,
        )
        fetch10 <- acs.fetchState(coid10)
        fetch11 <- acs.fetchState(coid11)
        fetch20 <- acs.fetchState(coid20)
      } yield {
        assert(
          fetch10.contains(AcsContractState(Archived, tor)),
          s"Contract $coid10 is archived",
        )
        assert(
          fetch11.contains(
            AcsContractState(
              ReassignedAway(targetSynchronizer2, reassignmentCounter2),
              tor,
            )
          ),
          s"Contract $coid11 is reassigned to $targetSynchronizer2",
        )
        assert(
          fetch20.contains(
            AcsContractState(
              ReassignedAway(targetSynchronizer1, reassignmentCounter1),
              tor,
            )
          ),
          s"Contract $coid20 is reassigned to $targetSynchronizer1",
        )
      }
    }

    "double spend a reassigned-away contract" inUS {
      val tor0 = TimeOfRequest(RequestCounter(0), Epoch)
      val tor = TimeOfRequest(RequestCounter(1), ofEpochMilli(1))
      for {
        acs <- mkAcs((coid00, tor0, ReassignedAway(targetSynchronizer1, reassignmentCounter1)))
        cd = mkCd(acs)
        actRes1 <- prefetchAndCheck(
          cd,
          RequestCounter(1),
          mkActivenessSet(deact = Set(coid00), prior = Set(coid00)),
        )
        fin1 <- cd
          .finalizeRequest(
            mkCommitSet(unassign = Map(coid00 -> (synchronizer2 -> reassignmentCounter2))),
            tor,
          )
          .flatten

        fetch00 <- acs.fetchState(coid00)
      } yield {
        assert(
          actRes1 == mkActivenessResult(
            notActive = Map(coid00 -> ReassignedAway(targetSynchronizer1, reassignmentCounter1)),
            prior = Map(coid00 -> Some(ReassignedAway(targetSynchronizer1, reassignmentCounter1))),
          )
        )
        assert(fin1 == Either.unit)
        assert(
          fetch00.contains(
            AcsContractState(
              ReassignedAway(targetSynchronizer2, reassignmentCounter2),
              tor,
            )
          )
        )
      }
    }

    "double assignment of a contract" inUS {
      val tor = TimeOfRequest(RequestCounter(1), ofEpochMilli(1000))
      for {
        acs <- mkAcs()
        reassignmentCacheAndIds <- mkReassignmentCache(loggerFactory)(
          (sourceSynchronizer1, mediator1),
          (sourceSynchronizer2, mediator2),
        )
        (reassignmentCache, reassignmentIds) = reassignmentCacheAndIds
        cd = mkCd(acs, reassignmentCache)
        actSet1 = mkActivenessSet(assign = Set(coid00), reassignmentIds = Set(reassignmentIds(0)))
        actSet2 = mkActivenessSet(assign = Set(coid00), reassignmentIds = Set(reassignmentIds(1)))
        commitSet1 = mkCommitSet(assign = Map(coid00 -> (sourceSynchronizer1, reassignmentIds(0))))
        commitSet2 = mkCommitSet(assign = Map(coid00 -> (sourceSynchronizer2, reassignmentIds(1))))
        _ <- singleCRwithTR(
          cd,
          RequestCounter(0),
          actSet1,
          mkActivenessResult(),
          commitSet1,
          Epoch,
        )
        actRes2 <- prefetchAndCheck(cd, RequestCounter(1), actSet2)
        fin2 <- cd
          .finalizeRequest(commitSet2, tor)
          .flatten

        fetch00 <- acs.fetchState(coid00)
      } yield {
        assert(
          actRes2 == mkActivenessResult(notFree = Map(coid00 -> active)),
          s"double activation is reported",
        )
        assert(fin2 == Either.unit)
        assert(fetch00.contains(AcsContractState(active, tor)))
      }
    }

    "handle double activations and double deactivations at the same timestamp" inUS {
      val tor0 = TimeOfRequest(RequestCounter(0), Epoch)
      for {
        acs <- mkAcs((coid00, tor0, active), (coid01, tor0, active))
        reassignmentCacheAndIds <- mkReassignmentCache(loggerFactory)(
          (sourceSynchronizer2, mediator2)
        )
        (reassignmentCache, reassignmentIds) = reassignmentCacheAndIds
        cd = mkCd(acs, reassignmentCache)
        actSet1 = mkActivenessSet(create = Set(coid10), deact = Set(coid00, coid01))
        commitSet1 = mkCommitSet(
          create = Set(coid10),
          arch = Set(coid00),
          unassign = Map(coid01 -> (synchronizer1 -> reassignmentCounter1)),
        )
        actSet2 = mkActivenessSet(
          assign = Set(coid10),
          deact = Set(coid00, coid01),
          reassignmentIds = Set(reassignmentIds(0)),
        )
        commitSet2 = mkCommitSet(
          assign = Map(coid10 -> (sourceSynchronizer2, reassignmentIds(0))),
          unassign = Map(
            coid00 -> (synchronizer2 -> reassignmentCounter1),
            coid01 -> (synchronizer2 -> reassignmentCounter2),
          ),
        )
        ts = ofEpochMilli(1000)
        // tor1 and tor2 are at different timestamps as the request counter does not affect
        // the order in the backing active contract store and because the canton protorol
        // timestamps and request counters are mutually strictly monotonic.
        tor2 = TimeOfRequest(RequestCounter(2), ts.immediateSuccessor)
        tor1 = TimeOfRequest(RequestCounter(1), ts)
        actRes1 <- prefetchAndCheck(cd, RequestCounter(1), actSet1)
        _ = assert(actRes1 == mkActivenessResult())
        actRes2 <- prefetchAndCheck(cd, RequestCounter(2), actSet2)
        _ = assert(actRes2 == mkActivenessResult(locked = Set(coid00, coid01, coid10)))
        fin2 <- cd.finalizeRequest(commitSet2, tor2).flatten
        fin1 <- cd.finalizeRequest(commitSet1, tor1).flatten
      } yield {
        assert(fin2 == Either.unit, s"First commit goes through")
        fin1
          .leftOrFail("Double (de)activations are reported.")
          .toList should contain(AcsError(ChangeAfterArchival(coid00, tor1, tor2)))

      }
    }

    "detect contract conflicts between assignments" inUS {
      for {
        reassignmentCacheAndIds <- mkReassignmentCache(loggerFactory)(
          (sourceSynchronizer1, mediator1),
          (sourceSynchronizer2, mediator2),
        )
        (reassignmentCache, reassignmentIds) = reassignmentCacheAndIds
        cd = mkCd(reassignmentCache = reassignmentCache)
        actRes1 <- prefetchAndCheck(
          cd,
          RequestCounter(0),
          mkActivenessSet(assign = Set(coid00), reassignmentIds = Set(reassignmentIds(0))),
        )
        actRes2 <- prefetchAndCheck(
          cd,
          RequestCounter(1),
          mkActivenessSet(assign = Set(coid00), reassignmentIds = Set(reassignmentIds(1))),
        )
      } yield {
        assert(actRes1 == mkActivenessResult())
        assert(actRes2 == mkActivenessResult(locked = Set(coid00)))
        checkContractState(cd, coid00, 0, 2, 0)(s"activation lock held twice")
      }
    }

    "detect conflicts between assignments and creates" inUS {
      for {
        reassignmentCacheAndIds <- mkReassignmentCache(loggerFactory)(
          (sourceSynchronizer1, mediator1),
          (sourceSynchronizer2, mediator2),
        )
        (reassignmentCache, reassignmentIds) = reassignmentCacheAndIds
        cd = mkCd(reassignmentCache = reassignmentCache)
        actSet1 = mkActivenessSet(
          assign = Set(coid00),
          create = Set(coid01),
          reassignmentIds = Set(reassignmentIds(0)),
        )
        actRes1 <- prefetchAndCheck(cd, RequestCounter(0), actSet1)
        actSet2 = mkActivenessSet(
          assign = Set(coid01),
          create = Set(coid00),
          reassignmentIds = Set(reassignmentIds(1)),
        )
        actRes2 <- prefetchAndCheck(cd, RequestCounter(1), actSet2)
      } yield {
        assert(actRes1 == mkActivenessResult())
        assert(actRes2 == mkActivenessResult(locked = Set(coid01, coid00)))
      }
    }

    "detect conflicts between racing assignments" inUS {
      val reassignmentStore =
        new InMemoryReassignmentStore(
          Target(ReassignmentStoreTest.indexedTargetSynchronizer.synchronizerId),
          loggerFactory,
        )
      val hookedStore = new ReassignmentCacheTest.HookReassignmentStore(reassignmentStore)
      for {
        reassignmentCacheAndIds <- mkReassignmentCache(loggerFactory, hookedStore)(
          (sourceSynchronizer1, mediator1)
        )
        (reassignmentCache, reassignmentIds) = reassignmentCacheAndIds
        cd = mkCd(reassignmentCache = reassignmentCache)
        actSet = mkActivenessSet(assign = Set(coid00), reassignmentIds = Set(reassignmentIds(0)))
        _actRes <- prefetchAndCheck(cd, RequestCounter(0), actSet)
        commitSet = mkCommitSet(assign = Map(coid00 -> (sourceSynchronizer1, reassignmentIds(0))))
        tor = TimeOfRequest(RequestCounter(0), ofEpochMilli(1))
        tor2 = TimeOfRequest(RequestCounter(2), ofEpochMilli(3))
        promise = Promise[Either[NonEmptyChain[RequestTracker.RequestTrackerStoreError], Unit]]()
        _ = hookedStore.preComplete { (_, _) =>
          // This runs after committing the request, but before the reassignment store is updated
          val actSetOut = mkActivenessSet(deact = Set(coid00))
          val commitSetOut =
            mkCommitSet(unassign = Map(coid00 -> (synchronizer1 -> reassignmentCounter1)))
          CheckedT((for {
            _ <- singleCRwithTR(
              cd,
              RequestCounter(1),
              actSetOut,
              mkActivenessResult(),
              commitSetOut,
              ofEpochMilli(2),
            )
            actRes2 <- prefetchAndCheck(cd, RequestCounter(2), actSet)
            _ = promise.completeWith(cd.finalizeRequest(commitSet, tor2).flatten.failOnShutdown)
          } yield {
            assert(
              actRes2 == mkActivenessResult(inactiveReassignments = Set(reassignmentIds(0))),
              s"Double assignment ${reassignmentIds(0)}",
            )
            Checked.result(())
          }).failOnShutdown)
        }
        fin1 <- cd.finalizeRequest(commitSet, tor).flatten
        fin2 <- FutureUnlessShutdown.outcomeF(promise.future)
      } yield {
        assert(fin1 == Either.unit, "First assignment succeeds")
        fin2
          .leftOrFail(s"Reassignment ${reassignmentIds(0)} was already completed")
          .toList should contain(
          ReassignmentsStoreError(ReassignmentAlreadyCompleted(reassignmentIds(0), tor2.timestamp))
        )
      }
    }

    "work with pruning" inUS {
      val tor0 = TimeOfRequest(RequestCounter(0), Epoch)
      val tor1 = TimeOfRequest(RequestCounter(1), ofEpochMilli(1))
      val tor2 = TimeOfRequest(RequestCounter(2), ofEpochMilli(2))
      val tor3 = TimeOfRequest(RequestCounter(3), ofEpochMilli(3))
      implicit val closeContext: CloseContext = HasTestCloseContext.makeTestCloseContext(logger)
      for {
        acs <- mkAcs((coid00, tor0, active), (coid01, tor0, active))
        cd = mkCd(acs)

        actSet1 = mkActivenessSet(deact = Set(coid00, coid01))
        cr1 <- prefetchAndCheck(cd, RequestCounter(1), actSet1)
        _ = assert(cr1 == mkActivenessResult())

        actSet2 = mkActivenessSet(deact = Set(coid00, coid01))
        cr2 <- prefetchAndCheck(cd, RequestCounter(2), actSet2)
        _ = assert(
          cr2 == mkActivenessResult(locked = Set(coid00, coid01))
        )

        actSet3 = actSet2
        cr2 <- prefetchAndCheck(cd, RequestCounter(3), actSet3)
        _ = assert(
          cr2 == mkActivenessResult(locked = Set(coid00, coid01))
        )

        commitSet1 = mkCommitSet(
          arch = Set(coid00),
          unassign = Map(coid01 -> (synchronizer1 -> reassignmentCounter1)),
        )
        fin1 <- cd.finalizeRequest(commitSet1, tor1).flatten
        _ = assert(fin1 == Either.unit)

        _ <- acs.prune(tor1.timestamp)

        _ <- checkContractState(acs, coid00, None)(s"$coid00 has been pruned")
        _ <- checkContractState(acs, coid01, None)(s"$coid01 has been pruned")

        // Triggers invariant checking if invariant checking is enabled
        fin2 <- cd.finalizeRequest(CommitSet.empty, tor2).flatten

        // Triggers invariant checking if invariant checking is enabled
        fin3 <- cd.finalizeRequest(CommitSet.empty, tor3).flatten
      } yield {
        assert(fin2 == Either.unit)
        assert(fin3 == Either.unit)
      }
    }

    "interleave pre-fetching" inUS {
      val torN1 = TimeOfRequest(RequestCounter(-1), Epoch)
      val actSet0 = mkActivenessSet(create = Set(coid10), deact = Set(coid00, coid01))
      val actSet1 = mkActivenessSet(deact = Set(coid21))

      def setFetchHook(acs: HookedAcs, cd: ConflictDetector): Unit =
        acs.setFetchHook { _ =>
          // This runs while the contracts for the first request are prefetched from the ACS
          // Prefetch second request with distinct contracts
          cd.registerActivenessSet(RequestCounter(1), actSet1)
        }

      for {
        rawAcs <- mkAcs(
          (coid00, torN1, active),
          (coid01, torN1, active),
          (coid21, torN1, active),
        )
        acs = new HookedAcs(rawAcs)(parallelExecutionContext)
        cd = mkCd(acs)

        _ = setFetchHook(acs, cd)
        _ <- cd.registerActivenessSet(RequestCounter(0), actSet0)

        // Activeness check for the first request
        cr0 <- cd.checkActivenessAndLock(RequestCounter(0))
        _ = assert(cr0 == mkActivenessResult())

        // Activeness check for second request
        cr1 <- cd.checkActivenessAndLock(RequestCounter(1))
        _ = assert(cr1 == mkActivenessResult())

        _ = Seq(
          (coid00, Some((active, torN1)), 0, 1),
          (coid01, Some((active, torN1)), 0, 1),
          (coid10, None, 1, 0),
          (coid21, Some((active, torN1)), 0, 1),
        ).foreach {
          case (coid, Some((status, version)), activationLock, deactivationLock) =>
            checkContractState(cd, coid, status, version, 0, activationLock + deactivationLock, 0)(
              s"State of existing contract $coid"
            )
          case (coid, None, activationLock, deactivationLock) =>
            checkContractState(cd, coid, 0, activationLock + deactivationLock, 0)(
              s"State of nonexistent contract $coid"
            )
        }
      } yield succeed
    }

    "detect non-prefetched states" inUS {
      def setFetchHook(acs: HookedAcs, cd: ConflictDetector): Unit =
        acs.setFetchHook { _ =>
          // This runs while the contracts for the request are prefetched from the ACS
          // Trigger checking already now to provoke an error
          cd.checkActivenessAndLock(RequestCounter(0)).void
        }

      for {
        rawAcs <- mkAcs()
        acs = new HookedAcs(rawAcs)(parallelExecutionContext)
        cd = mkCd(acs)

        _ = setFetchHook(acs, cd)
        _ <- FutureUnlessShutdown.outcomeF(
          loggerFactory.assertThrowsAndLogsAsync[ConflictDetectionStoreAccessError](
            cd.registerActivenessSet(
              RequestCounter(0),
              mkActivenessSet(create = Set(coid00, coid01)),
            ).failOnShutdown,
            _ => succeed,
            entry => {
              entry.errorMessage should include("An internal error has occurred.")
              val cause = entry.throwable.value
              cause shouldBe a[IllegalConflictDetectionStateException]
              cause.getMessage should include(s"Request 0 has outstanding pre-fetches:")
            },
          )
        )
      } yield succeed
    }
  }

  def checkContractState(acs: ActiveContractStore, coid: LfContractId, cs: (Status, TimeOfRequest))(
      clue: String
  ): FutureUnlessShutdown[Assertion] =
    checkContractState(
      acs,
      coid,
      Some(AcsContractState(cs._1, cs._2.timestamp, repairCounterO = None)),
    )(clue)

  def checkContractState(
      acs: ActiveContractStore,
      coid: LfContractId,
      state: Option[AcsContractState],
  )(clue: String): FutureUnlessShutdown[Assertion] =
    acs.fetchState(coid).map(result => assert(result == state, clue))

  private[this] def mkState[A <: PrettyPrinting](
      state: Option[StateChange[A]],
      pendingActivenessCount: Int,
      locks: Int,
      pendingWriteCount: Int,
  ): ImmutableLockableState[A] =
    ImmutableContractState(
      Some(state),
      PendingActivenessCheckCounter.assertFromInt(pendingActivenessCount),
      LockCounter.assertFromInt(locks),
      PendingWriteCounter.assertFromInt(pendingWriteCount),
    )

  private def checkContractState(
      cd: ConflictDetector,
      coid: LfContractId,
      status: Status,
      tor: TimeOfRequest,
      pendingActivenessCount: Int,
      locks: Int,
      pendingWriteCount: Int,
  )(clue: String): Assertion = {
    val expected = mkState(
      Some(ActiveContractStore.ContractState(status, tor.timestamp, repairCounterO = None)),
      pendingActivenessCount,
      locks,
      pendingWriteCount,
    )
    assert(cd.getInternalContractState(coid).contains(expected), clue)
  }

  private def checkContractState(
      cd: ConflictDetector,
      coid: LfContractId,
      pendingActivenessCount: Int,
      locks: Int,
      pendingWriteCount: Int,
  )(clue: String): Assertion = {
    val expected = mkState(None, pendingActivenessCount, locks, pendingWriteCount)
    assert(cd.getInternalContractState(coid).contains(expected), clue)
  }

  private def checkContractStateAbsent(cd: ConflictDetector, coid: LfContractId)(clue: String)(
      implicit pos: source.Position
  ): Assertion =
    assert(cd.getInternalContractState(coid).isEmpty, clue)

  private def prefetchAndCheck(
      cd: ConflictDetector,
      rc: RequestCounter,
      activenessSet: ActivenessSet,
  ): FutureUnlessShutdown[ActivenessResult] =
    cd.registerActivenessSet(rc, activenessSet)
      .flatMap(_ => cd.checkActivenessAndLock(rc))

  private def singleCRwithTR(
      cd: ConflictDetector,
      rc: RequestCounter,
      activenessSet: ActivenessSet,
      activenessResult: ActivenessResult,
      commitSet: CommitSet,
      recordTime: CantonTimestamp,
  ): FutureUnlessShutdown[Assertion] =
    for {
      cr <- prefetchAndCheck(cd, rc, activenessSet)
      fin <- cd.finalizeRequest(commitSet, TimeOfRequest(rc, recordTime)).flatten
    } yield {
      assert(cr == activenessResult, "activeness check reports the correct result")
      assert(fin == Either.unit)
    }

  private implicit class ConflictDetectionStoreOps[K, A <: PrettyPrinting](
      store: ConflictDetectionStore[K, A]
  ) {
    def fetchState(
        id: K
    )(implicit traceContext: TraceContext): FutureUnlessShutdown[Option[StateChange[A]]] =
      store
        .fetchStates(Seq(id))
        .map(_.get(id))
  }

  private implicit def convertTimeOfRequestToTimeOfChange(tor: TimeOfRequest): TimeOfChange =
    TimeOfChange(tor.timestamp)
}
