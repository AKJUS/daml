// Copyright (c) 2025 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.canton.console.commands

import com.digitalasset.canton.admin.api.client.commands.MediatorAdministrationCommands.{
  Initialize,
  LocatePruningTimestampCommand,
  Prune,
}
import com.digitalasset.canton.admin.api.client.commands.{
  MediatorInspectionCommands,
  PruningSchedulerCommands,
  SynchronizerTimeCommands,
}
import com.digitalasset.canton.config.NonNegativeDuration
import com.digitalasset.canton.config.RequireTypes.PositiveInt
import com.digitalasset.canton.console.{
  AdminCommandRunner,
  ConsoleEnvironment,
  FeatureFlag,
  FeatureFlagFilter,
  Help,
  Helpful,
  MediatorReference,
}
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.mediator.admin.v30
import com.digitalasset.canton.mediator.admin.v30.Verdict
import com.digitalasset.canton.networking.grpc.RecordingStreamObserver
import com.digitalasset.canton.sequencing.{SequencerConnectionValidation, SequencerConnections}
import com.digitalasset.canton.time.NonNegativeFiniteDuration
import com.digitalasset.canton.topology.PhysicalSynchronizerId
import com.digitalasset.canton.tracing.NoTracing

import scala.concurrent.duration.FiniteDuration

class MediatorTestingGroup(
    runner: AdminCommandRunner,
    val consoleEnvironment: ConsoleEnvironment,
    val loggerFactory: NamedLoggerFactory,
) extends FeatureFlagFilter
    with Helpful {

  @Help.Summary("Fetch the current time from the synchronizer", FeatureFlag.Testing)
  def fetch_synchronizer_time(
      timeout: NonNegativeDuration = consoleEnvironment.commandTimeouts.ledgerCommand
  ): CantonTimestamp =
    check(FeatureFlag.Testing) {
      consoleEnvironment.run {
        runner.adminCommand(
          SynchronizerTimeCommands.FetchTime(None, NonNegativeFiniteDuration.Zero, timeout)
        )
      }.timestamp
    }

  @Help.Summary("Await for the given time to be reached on the synchronizer", FeatureFlag.Testing)
  def await_synchronizer_time(time: CantonTimestamp, timeout: NonNegativeDuration): Unit =
    check(FeatureFlag.Testing) {
      consoleEnvironment.run {
        runner.adminCommand(
          SynchronizerTimeCommands.AwaitTime(None, time, timeout)
        )
      }
    }
}

class MediatorPruningAdministrationGroup(
    runner: AdminCommandRunner,
    consoleEnvironment: ConsoleEnvironment,
    loggerFactory: NamedLoggerFactory,
) extends PruningSchedulerAdministration(
      runner,
      consoleEnvironment,
      new PruningSchedulerCommands[
        v30.MediatorAdministrationServiceGrpc.MediatorAdministrationServiceStub
      ](
        v30.MediatorAdministrationServiceGrpc.stub,
        _.setSchedule(_),
        _.clearSchedule(_),
        _.setCron(_),
        _.setMaxDuration(_),
        _.setRetention(_),
        _.getSchedule(_),
      ),
      loggerFactory,
    )
    with Helpful {

  @Help.Summary(
    "Prune the mediator of unnecessary data while keeping data for the default retention period"
  )
  @Help.Description(
    """Removes unnecessary data from the Mediator that is earlier than the default retention period.
          |The default retention period is set in the configuration of the canton node running this
          |command under `parameters.retention-period-defaults.mediator`."""
  )
  def prune(): Unit = {
    val defaultRetention =
      consoleEnvironment.environment.config.parameters.retentionPeriodDefaults.mediator
    prune_with_retention_period(defaultRetention.underlying)
  }

  @Help.Summary(
    "Prune the mediator of unnecessary data while keeping data for the provided retention period"
  )
  def prune_with_retention_period(retentionPeriod: FiniteDuration): Unit = {
    import scala.jdk.DurationConverters.*
    val pruneUpTo = consoleEnvironment.environment.clock.now.minus(retentionPeriod.toJava)
    prune_at(pruneUpTo)
  }

  @Help.Summary("Prune the mediator of unnecessary data up to and including the given timestamp")
  def prune_at(timestamp: CantonTimestamp): Unit = consoleEnvironment.run {
    runner.adminCommand(Prune(timestamp))
  }

  @Help.Summary("Obtain a timestamp at or near the beginning of mediator state")
  @Help.Description(
    """This command provides insight into the current state of mediator pruning when called with
      |the default value of `index` 1.
      |When pruning the mediator manually via `prune_at` and with the intent to prune in batches, specify
      |a value such as 1000 to obtain a pruning timestamp that corresponds to the "end" of the batch."""
  )
  def locate_pruning_timestamp(
      index: PositiveInt = PositiveInt.tryCreate(1)
  ): Option[CantonTimestamp] =
    consoleEnvironment.run {
      runner.adminCommand(LocatePruningTimestampCommand(index))
    }

}

class MediatorSetupGroup(node: MediatorReference) extends ConsoleCommandGroup.Impl(node) {
  @Help.Summary("Assign a mediator to a synchronizer")
  def assign(
      synchronizerId: PhysicalSynchronizerId,
      sequencerConnections: SequencerConnections,
      sequencerConnectionValidation: SequencerConnectionValidation =
        SequencerConnectionValidation.All,
      waitForReady: Boolean = true,
  ): Unit = {
    if (waitForReady) node.health.wait_for_ready_for_initialization()

    consoleEnvironment.run {
      runner.adminCommand(
        Initialize(
          synchronizerId,
          sequencerConnections,
          sequencerConnectionValidation,
        )
      )
    }
  }

}

class MediatorInspectionGroup(
    runner: AdminCommandRunner,
    override protected val consoleEnvironment: ConsoleEnvironment,
    override protected val name: String,
    override protected val loggerFactory: NamedLoggerFactory,
) extends NoTracing
    with NamedLogging
    with StreamingCommandHelper {
  def verdicts(
      fromRecordTimeOfRequestExclusive: CantonTimestamp,
      maxItems: PositiveInt,
      timeout: NonNegativeDuration = consoleEnvironment.commandTimeouts.bounded,
  ): Seq[Verdict] = {
    val observer = new RecordingStreamObserver[v30.Verdict](completeAfter = maxItems)
    val cmd = MediatorInspectionCommands.MediatorVerdicts(
      mostRecentlyReceivedRecordTimeOfRequest = Some(fromRecordTimeOfRequestExclusive),
      observer,
    )
    mkResult(
      consoleEnvironment.run(runner.adminCommand(cmd)),
      s"fetch mediator verdicts starting from $fromRecordTimeOfRequestExclusive",
      observer,
      timeout,
    )
  }
}
