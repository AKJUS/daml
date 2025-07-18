// Copyright (c) 2025 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.canton.concurrent

import com.daml.nonempty.NonEmpty
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.discard.Implicits.*
import com.digitalasset.canton.{BaseTest, HasExecutionContext, config}
import org.scalatest.wordspec.AnyWordSpec

import scala.concurrent.duration.{DurationInt, DurationLong}
import scala.concurrent.{Future, Promise}
import scala.util.Success

trait FutureSupervisorTest extends AnyWordSpec with BaseTest with HasExecutionContext {

  def futureSupervisor(supervisor: FutureSupervisor): Unit = {

    "support a lot of concurrent supervisions" in {
      // Ensure that there is no quadratic algorithm anywhere in the supervision code
      val count = 1000000

      val supervisedCompleted = (1 to count).toList.map { i =>
        futureSupervisor.supervised(s"test-$i-completed")(Future.successful(i))
      }
      Future.sequence(supervisedCompleted).futureValue

      val promise = Promise[Unit]()
      val supervisedIncomplete = (1 to count).toList.map { i =>
        futureSupervisor.supervised(s"test-$i-incomplete")(promise.future)
      }
      promise.success(())
      Future.sequence(supervisedIncomplete).futureValue
    }

    "supervising a completed promise's future is a no-op" in {
      val promise = new SupervisedPromise[Unit]("to be completed immediately", supervisor)

      promise.trySuccess(())
      promise.future.value shouldBe Some(Success(()))
    }

    "repeated calls to supervised promises are cached" in {
      val promise = new SupervisedPromise[CantonTimestamp]("future is cached", supervisor)

      val fut1 = promise.future
      val fut2 = promise.future

      fut1 shouldBe fut2

      val timestamp = CantonTimestamp.now()
      promise.trySuccess(timestamp)

      val fut3 = promise.future

      fut3 shouldBe fut1
      fut3.value shouldBe Some(Success(timestamp))
    }
  }
}

class NoOpFutureSupervisorTest extends FutureSupervisorTest {
  "NoOpFutureSupervisor" should {
    behave like futureSupervisor(FutureSupervisor.Noop)
  }
}

class FutureSupervisorImplTest extends FutureSupervisorTest {

  import FutureSupervisorImplTest.*

  "FutureSupervisorImpl" should {
    behave like futureSupervisor(
      new FutureSupervisor.Impl(config.NonNegativeDuration.ofSeconds(10))(scheduledExecutor())
    )

    "complain about a slow future" in {
      val supervisor =
        new FutureSupervisor.Impl(config.NonNegativeDuration.ofMillis(10))(scheduledExecutor())
      val promise = Promise[Unit]()
      val fut = loggerFactory.assertLoggedWarningsAndErrorsSeq(
        {
          val fut = supervisor.supervised("slow-future")(promise.future)
          eventually() {
            loggerFactory.fetchRecordedLogEntries should not be empty
          }
          fut
        },
        {
          // If the test is running slow we might get the warning multiple times
          logEntries =>
            logEntries should not be empty
            forAll(logEntries) {
              _.warningMessage should include("slow-future has not completed after")
            }
        },
      )
      promise.success(())
      fut.futureValue
    }

    "stop supervision if the future is discarded" in {
      val supervisor =
        new FutureSupervisor.Impl(config.NonNegativeDuration.ofMillis(10))(scheduledExecutor())
      loggerFactory.assertLoggedWarningsAndErrorsSeq(
        {
          supervisor
            .supervised("discarded-future") {
              // Do not assign this to a val so that no reference to the future is kept anywhere and it can be GC'ed.
              Promise[Unit]().future
            }
            .discard[Future[Unit]]
          // Normally, the future should still be scheduled. However, it is possible that the GC has already run
          // and so there's nothing to do to simulate
          NonEmpty.from(supervisor.inspectScheduled) match {
            case None =>
              logger.debug("No scheduled future found: Was probably already GC'ed.")
            case Some(scheduled) =>
              logger.debug("Simulating garbage collection on the weak reference.")
              scheduled.head1.fut.underlying.clear()
          }
          // Wait until the schedule is removed
          eventually() {
            supervisor.inspectScheduled shouldBe Seq.empty
          }
        },
        // We don't know how long it takes for the GC simulation to run. So there may be any number of warnings about the slow future.
        forAll(_) {
          _.warningMessage should include("discarded-future has not completed after")
        },
      )
    }

    "scale for many supervised futures" in {
      // Another (stricter) test ensuring that there is no quadratic algorithm anywhere in the supervision code
      val supervisor =
        new FutureSupervisor.Impl(
          config.NonNegativeDuration.ofSeconds(ScaleTestFutureTimeout.toSeconds)
        )(scheduledExecutor())
      val promise = Promise[Unit]()
      val startTime = System.nanoTime

      val supervisedFutures = (1 to ScaleTestTotalNumberOfFutures).toList.map { i =>
        supervisor.supervised(s"test-$i")(promise.future)
      }
      supervisor.inspectScheduled.size shouldBe ScaleTestTotalNumberOfFutures
      promise.success(())
      Future.sequence(supervisedFutures).futureValue

      val totalTime = (System.nanoTime - startTime).nanos
      if (totalTime > ScaleTestTotalTimeout) {
        fail(
          s"Sequenced future took longer (${totalTime.toMillis} milliseconds) to complete than " +
            s"the total scale test timeout of $ScaleTestTotalTimeout"
        )
      }

      eventually() {
        supervisor.inspectScheduled shouldBe Seq.empty
      }
    }
  }
}

object FutureSupervisorImplTest {
  private val ScaleTestTotalNumberOfFutures = 100000
  private val ScaleTestFutureTimeout = 1.second
  // Use a bigger timeout for completing the entire scale test
  private val ScaleTestTotalTimeout = 5.seconds
}
