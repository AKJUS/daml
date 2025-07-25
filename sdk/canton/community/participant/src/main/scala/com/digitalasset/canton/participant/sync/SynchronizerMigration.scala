// Copyright (c) 2025 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.canton.participant.sync

import cats.data.EitherT
import cats.syntax.bifunctor.*
import cats.syntax.either.*
import cats.syntax.foldable.*
import cats.syntax.functor.*
import cats.syntax.traverse.*
import com.daml.nameof.NameOf.functionFullName
import com.daml.nonempty.NonEmpty
import com.digitalasset.base.error.{ErrorCategory, ErrorCode, Explanation}
import com.digitalasset.canton.SynchronizerAlias
import com.digitalasset.canton.common.sequencer.grpc.SequencerInfoLoader
import com.digitalasset.canton.config.ProcessingTimeout
import com.digitalasset.canton.error.{CantonError, ContextualizedCantonError, ParentCantonError}
import com.digitalasset.canton.lifecycle.{CloseContext, FlagCloseable, FutureUnlessShutdown}
import com.digitalasset.canton.logging.{ErrorLoggingContext, NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.participant.admin.inspection.SyncStateInspection
import com.digitalasset.canton.participant.admin.inspection.SyncStateInspection.{
  InFlightCount,
  SyncStateInspectionError,
}
import com.digitalasset.canton.participant.admin.repair.RepairService
import com.digitalasset.canton.participant.store.SynchronizerConnectionConfigStore
import com.digitalasset.canton.participant.sync.SyncServiceError.{
  MigrationErrors,
  SyncServiceUnknownSynchronizer,
}
import com.digitalasset.canton.participant.sync.SynchronizerMigrationError.InvalidArgument.{
  InvalidSynchronizerConfigStatuses,
  SourceSynchronizerIdUnknown,
}
import com.digitalasset.canton.participant.synchronizer.{
  SynchronizerAliasManager,
  SynchronizerConnectionConfig,
  SynchronizerRegistryError,
  SynchronizerRegistryHelpers,
}
import com.digitalasset.canton.sequencing.SequencerConnectionValidation
import com.digitalasset.canton.topology.{
  ConfiguredPhysicalSynchronizerId,
  KnownPhysicalSynchronizerId,
  PhysicalSynchronizerId,
  SynchronizerId,
}
import com.digitalasset.canton.tracing.{TraceContext, Traced}
import com.digitalasset.canton.util.ReassignmentTag.{Source, Target}
import com.digitalasset.canton.util.ShowUtil.*
import com.digitalasset.canton.util.{MonadUtil, ReassignmentTag, SameReassignmentType}

import scala.concurrent.ExecutionContext

sealed trait SynchronizerMigrationError
    extends Product
    with Serializable
    with ContextualizedCantonError

/** Migration of contracts from a source synchronizer to target synchronizer by re-associating them
  * in the participant's persistent store.
  */
class SynchronizerMigration(
    aliasManager: SynchronizerAliasManager,
    synchronizerConnectionConfigStore: SynchronizerConnectionConfigStore,
    inspection: SyncStateInspection,
    repair: RepairService,
    prepareSynchronizerConnection: Traced[SynchronizerAlias] => EitherT[
      FutureUnlessShutdown,
      SynchronizerMigrationError,
      Unit,
    ],
    sequencerInfoLoader: SequencerInfoLoader,
    override val timeouts: ProcessingTimeout,
    override val loggerFactory: NamedLoggerFactory,
)(implicit executionContext: ExecutionContext)
    extends NamedLogging
    with FlagCloseable {

  import com.digitalasset.canton.participant.sync.SynchronizerMigrationError.*

  private def getSynchronizerId(
      sourceAlias: SynchronizerAlias
  )(implicit
      traceContext: TraceContext
  ): EitherT[FutureUnlessShutdown, SynchronizerMigrationError, SynchronizerId] =
    EitherT.fromEither[FutureUnlessShutdown](
      aliasManager
        .synchronizerIdForAlias(sourceAlias)
        .toRight(
          SynchronizerMigrationError.InvalidArgument.SourceSynchronizerIdUnknown(sourceAlias)
        )
    )

  private def checkMigrationRequest(
      source: Source[SynchronizerAlias],
      target: Target[SynchronizerConnectionConfig],
      targetSynchronizerId: Target[SynchronizerId],
  )(implicit
      traceContext: TraceContext
  ): EitherT[FutureUnlessShutdown, SynchronizerMigrationError, Source[
    ConfiguredPhysicalSynchronizerId
  ]] = {
    logger.debug(s"Checking migration request from $source to ${target.unwrap.synchronizerAlias}")
    for {
      // check that target alias differs from source
      _ <- EitherT.cond[FutureUnlessShutdown](
        source.unwrap != target.unwrap.synchronizerAlias,
        (),
        InvalidArgument.SameSynchronizerAlias(source.unwrap),
      )

      // check that source synchronizer exists and has not been deactivated
      sourceConnectionE = synchronizerConnectionConfigStore
        .getAllFor(source.unwrap)
        .fold(
          err => SourceSynchronizerIdUnknown(err.alias).asLeft,
          connections => {
            if (connections.isEmpty)
              InvalidArgument.UnknownSourceSynchronizer(source).asLeft
            else {
              connections.filter(_.status.canMigrateFrom) match {
                case Nil => InvalidSynchronizerConfigStatuses(source, Nil).asLeft
                case Seq(connection) => connection.asRight
                case other =>
                  InvalidSynchronizerConfigStatuses(
                    source,
                    other.map(c => (c.configuredPSId, c.status)),
                  ).asLeft
              }
            }
          },
        )

      sourceConnection <- EitherT.fromEither[FutureUnlessShutdown](sourceConnectionE).map(Source(_))

      // check that synchronizer id (in config) matches observed synchronizer id
      _ <- target.unwrap.synchronizerId.traverse_ { expectedSynchronizerId =>
        EitherT.cond[FutureUnlessShutdown](
          expectedSynchronizerId.logical == targetSynchronizerId.unwrap,
          (),
          SynchronizerMigrationError.InvalidArgument
            .ExpectedSynchronizerIdsDiffer(
              target.map(_.synchronizerAlias),
              expectedSynchronizerId.logical,
              targetSynchronizerId,
            ),
        )
      }
      sourceSynchronizerId <- source.traverse(getSynchronizerId(_))
      _ <- EitherT.cond[FutureUnlessShutdown](
        sourceSynchronizerId.unwrap != targetSynchronizerId.unwrap,
        (),
        SynchronizerMigrationError.InvalidArgument.SourceAndTargetAreSame(
          sourceSynchronizerId
        ): SynchronizerMigrationError,
      )
    } yield sourceConnection.map(_.configuredPSId)
  }

  private def registerNewSynchronizer(
      target: Target[SynchronizerConnectionConfig],
      psid: PhysicalSynchronizerId,
  )(implicit
      traceContext: TraceContext
  ): EitherT[FutureUnlessShutdown, SynchronizerMigrationError, Unit] = {
    logger.debug(s"Registering new synchronizer ${target.unwrap.synchronizerAlias}")
    synchronizerConnectionConfigStore
      .put(
        target.unwrap,
        SynchronizerConnectionConfigStore.HardMigratingTarget,
        KnownPhysicalSynchronizerId(psid),
        // TODO(#26263) Ensure that this None is fine
        synchronizerPredecessor = None,
      )
      .leftMap[SynchronizerMigrationError](_ =>
        InternalError.DuplicateConfig(target.unwrap.synchronizerAlias)
      )
  }

  /** Checks whether the migration is possible:
    *   - Participant needs to be disconnected from both synchronizers.
    *   - No in-flight submission (except if `force = true`)
    *   - No dirty request (except if `force = true`)
    */
  def isSynchronizerMigrationPossible(
      source: Source[SynchronizerAlias],
      target: Target[SynchronizerConnectionConfig],
      force: Boolean,
  )(implicit
      traceContext: TraceContext
  ): EitherT[
    FutureUnlessShutdown,
    SyncServiceError,
    Target[SequencerInfoLoader.SequencerAggregatedInfo],
  ] =
    for {
      targetSynchronizerInfo <- target.traverse(synchronizerConnectionConfig =>
        synchronizeWithClosing(functionFullName)(
          sequencerInfoLoader
            .loadAndAggregateSequencerEndpoints(
              synchronizerConnectionConfig.synchronizerAlias,
              synchronizerConnectionConfig.synchronizerId,
              synchronizerConnectionConfig.sequencerConnections,
              SequencerConnectionValidation.Active,
            )(traceContext, CloseContext(this))
            .leftMap[SyncServiceError] { err =>
              val error = SynchronizerRegistryError.ConnectionErrors.FailedToConnectToSequencer
                .Error(SynchronizerRegistryError.fromSequencerInfoLoaderError(err).cause)
              SyncServiceError
                .SyncServiceFailedSynchronizerConnection(
                  synchronizerConnectionConfig.synchronizerAlias,
                  error,
                )
            }
        )
      )
      _ <- synchronizeWithClosing(functionFullName)(
        aliasManager
          .processHandshake(
            target.unwrap.synchronizerAlias,
            targetSynchronizerInfo.unwrap.synchronizerId,
          )
          .leftMap(SynchronizerRegistryHelpers.fromSynchronizerAliasManagerError)
          .leftMap[SyncServiceError](err =>
            SyncServiceError.SyncServiceFailedSynchronizerConnection(
              target.unwrap.synchronizerAlias,
              err,
            )
          )
      )

      inFlights <- synchronizeWithClosing(functionFullName)(
        countAllInFlight(source.unwrap).leftMap(_ =>
          SyncServiceUnknownSynchronizer.Error(source.unwrap)
        )
      )

      _ <-
        if (force) {
          if (inFlights.exists) {
            logger.info(
              s"Ignoring existing in-flight transactions on synchronizer with alias ${source.unwrap.unwrap} because of forced migration. This may lead to a ledger fork."
            )
          }
          EitherT.rightT[FutureUnlessShutdown, SyncServiceError](())
        } else
          EitherT
            .cond[FutureUnlessShutdown](
              !inFlights.exists,
              (),
              SyncServiceError.SyncServiceSynchronizerMustNotHaveInFlightTransactions
                .Error(source.unwrap),
            )
            .leftWiden[SyncServiceError]
    } yield targetSynchronizerInfo

  /** Count all in-flight transactions for the given alias. To be on the safe side, we sum over all
    * physical instances.
    */
  private def countAllInFlight(
      alias: SynchronizerAlias
  )(implicit traceContext: TraceContext): EitherT[FutureUnlessShutdown, String, InFlightCount] = {
    val psids = inspection.syncPersistentStateManager
      .synchronizerIdsForAlias(alias)
      .fold(Seq.empty[PhysicalSynchronizerId])(_.forgetNE.toSeq)

    MonadUtil
      .sequentialTraverse(psids)(inspection.countInFlight)
      .map(_.foldLeft(InFlightCount.zero)(_.+(_)))
  }

  /** Performs the synchronizer migration. Assumes that [[isSynchronizerMigrationPossible]] was
    * called before to check preconditions.
    */
  def migrateSynchronizer(
      source: Source[SynchronizerAlias],
      target: Target[SynchronizerConnectionConfig],
      targetPSId: Target[PhysicalSynchronizerId],
  )(implicit
      traceContext: TraceContext
  ): EitherT[FutureUnlessShutdown, SynchronizerMigrationError, Unit] = {
    def prepare(): EitherT[FutureUnlessShutdown, SynchronizerMigrationError, Source[
      ConfiguredPhysicalSynchronizerId
    ]] = {
      logger.debug(
        s"Preparing synchronizer migration from $source to ${target.unwrap.synchronizerAlias}"
      )
      for {
        // check that the request makes sense
        sourcePSId <- checkMigrationRequest(source, target, targetPSId.map(_.logical))
        // check if the target alias already exists.
        targetStatusO = target.traverse(config =>
          synchronizerConnectionConfigStore
            .get(config.synchronizerAlias, KnownPhysicalSynchronizerId(targetPSId.unwrap))
            .toOption
            .map(_.status)
        )
        // check if we are already active on the target synchronizer
        _ <- targetStatusO.fold {
          // synchronizer not yet configured, add the configuration
          registerNewSynchronizer(target, targetPSId.value)
        } { targetStatus =>
          logger.debug(s"Checking status of target synchronizer ${target.unwrap.synchronizerAlias}")
          EitherT.fromEither[FutureUnlessShutdown](
            for {
              // check target status
              _ <- Either.cond(
                targetStatus.unwrap.canMigrateTo,
                (),
                InvalidArgument.InvalidSynchronizerConfigStatus(
                  target.map(_.synchronizerAlias),
                  targetStatus,
                ),
              )
              // check stored alias if it exists
              _ <- aliasManager.synchronizerIdForAlias(target.unwrap.synchronizerAlias).traverse_ {
                storedSynchronizerId =>
                  Either.cond(
                    targetPSId.unwrap.logical == storedSynchronizerId,
                    (),
                    InvalidArgument.ExpectedSynchronizerIdsDiffer(
                      target.map(_.synchronizerAlias),
                      storedSynchronizerId,
                      targetPSId.map(_.logical),
                    ),
                  )
              }
            } yield ()
          )
        }
        _ <- updateSynchronizerStatus(
          target.map(_.synchronizerAlias),
          targetPSId.map(KnownPhysicalSynchronizerId(_): ConfiguredPhysicalSynchronizerId),
          SynchronizerConnectionConfigStore.HardMigratingTarget,
        )
        _ <- updateSynchronizerStatus(
          source,
          sourcePSId,
          SynchronizerConnectionConfigStore.HardMigratingSource,
        )
      } yield sourcePSId
    }

    for {
      sourcePSId <- synchronizeWithClosing(functionFullName)(prepare())
      sourceLSId <- source.traverse(getSynchronizerId(_))
      _ <- prepareSynchronizerConnection(Traced(target.unwrap.synchronizerAlias))
      _ <- migrateContracts(source, sourceLSId, targetPSId.map(_.logical))
      _ <- updateSynchronizerStatus(
        target.map(_.synchronizerAlias),
        targetPSId.map(KnownPhysicalSynchronizerId(_): ConfiguredPhysicalSynchronizerId),
        SynchronizerConnectionConfigStore.Active,
      )

      _ <- updateSynchronizerStatus(source, sourcePSId, SynchronizerConnectionConfigStore.Inactive)

    } yield ()
  }

  private def updateSynchronizerStatus[T[X] <: ReassignmentTag[X]: SameReassignmentType](
      alias: T[SynchronizerAlias],
      physicalSynchronizerId: T[ConfiguredPhysicalSynchronizerId],
      state: SynchronizerConnectionConfigStore.Status,
  )(implicit
      traceContext: TraceContext
  ): EitherT[FutureUnlessShutdown, SynchronizerMigrationError, Unit] = {
    logger.info(
      s"Changing status of synchronizer configuration ($alias, $physicalSynchronizerId) to $state"
    )
    synchronizerConnectionConfigStore
      .setStatus(alias.unwrap, physicalSynchronizerId.unwrap, state)
      .leftMap(err => SynchronizerMigrationError.InternalError.Generic(err.toString))
  }

  private def migrateContracts(
      sourceAlias: Source[SynchronizerAlias],
      source: Source[SynchronizerId],
      target: Target[SynchronizerId],
  )(implicit
      traceContext: TraceContext
  ): EitherT[FutureUnlessShutdown, SynchronizerMigrationError, Unit] =
    for {
      // load all contracts on source synchronizer
      acs <- synchronizeWithClosing(functionFullName)(
        inspection
          .findAcs(sourceAlias.unwrap)
          .leftMap[SynchronizerMigrationError](err =>
            SynchronizerMigrationError.InternalError.FailedReadingAcs(sourceAlias.unwrap, err)
          )
      )
      _ = logger.info(
        s"Found ${acs.size} contracts in the ACS of $sourceAlias that need to be migrated"
      )
      _ <- NonEmpty
        .from(acs.keys.toSeq.distinct) match {
        case None => EitherT.right[SynchronizerMigrationError](FutureUnlessShutdown.unit)
        case Some(contractIds) =>
          // move contracts from one synchronizer to the other synchronizer using repair service in batches of batchSize
          synchronizeWithClosing(functionFullName)(
            repair.changeAssignation(
              contractIds.map((_, None)),
              source,
              target,
              skipInactive = true,
            )
          )
            .leftMap[SynchronizerMigrationError](
              SynchronizerMigrationError.InternalError
                .FailedMigratingContracts(sourceAlias.unwrap, _)
            )
      }
    } yield ()

}

object SynchronizerMigrationError extends MigrationErrors() {

  @Explanation(
    "This error results when invalid arguments are passed to the migration command."
  )
  object InvalidArgument
      extends ErrorCode(
        "INVALID_SYNCHRONIZER_MIGRATION_REQUEST",
        ErrorCategory.InvalidGivenCurrentSystemStateOther,
      ) {
    final case class SameSynchronizerAlias(synchronizerAlias: SynchronizerAlias)(implicit
        val loggingContext: ErrorLoggingContext
    ) extends CantonError.Impl(cause = "Source synchronizer must differ from target synchronizer.")
        with SynchronizerMigrationError
    final case class UnknownSourceSynchronizer(synchronizer: Source[SynchronizerAlias])(implicit
        val loggingContext: ErrorLoggingContext
    ) extends CantonError.Impl(cause = s"Source synchronizer $synchronizer is unknown.")
        with SynchronizerMigrationError

    final case class SourceSynchronizerIdUnknown(source: SynchronizerAlias)(implicit
        val loggingContext: ErrorLoggingContext
    ) extends CantonError.Impl(
          cause =
            s"Source synchronizer $source has no synchronizer id stored: it's completely empty"
        )
        with SynchronizerMigrationError

    final case class InvalidSynchronizerConfigStatus[
        T[X] <: ReassignmentTag[X]: SameReassignmentType
    ](
        synchronizer: T[SynchronizerAlias],
        status: T[SynchronizerConnectionConfigStore.Status],
    )(implicit
        val loggingContext: ErrorLoggingContext
    ) extends CantonError.Impl(
          cause =
            s"The synchronizer configuration state of $synchronizer is in an invalid state for the requested migration $status"
        )
        with SynchronizerMigrationError

    final case class InvalidSynchronizerConfigStatuses[
        T[X] <: ReassignmentTag[X]: SameReassignmentType
    ](
        synchronizer: T[SynchronizerAlias],
        statuses: Seq[(ConfiguredPhysicalSynchronizerId, SynchronizerConnectionConfigStore.Status)],
    )(implicit
        val loggingContext: ErrorLoggingContext
    ) extends CantonError.Impl(
          cause =
            s"Expecting a single connection to be migrated from for $synchronizer but found: ${statuses
                .mkString(", ")}"
        )
        with SynchronizerMigrationError

    final case class ExpectedSynchronizerIdsDiffer(
        alias: Target[SynchronizerAlias],
        expected: SynchronizerId,
        remote: Target[SynchronizerId],
    )(implicit
        val loggingContext: ErrorLoggingContext
    ) extends CantonError.Impl(
          cause = show"The synchronizer id for $alias was expected to be $expected, but is $remote"
        )
        with SynchronizerMigrationError

    final case class SourceAndTargetAreSame(source: Source[SynchronizerId])(implicit
        val loggingContext: ErrorLoggingContext
    ) extends CantonError.Impl(
          cause =
            show"The target synchronizer id needs to be different from the source synchronizer id"
        )
        with SynchronizerMigrationError
  }

  final case class MigrationParentError(
      synchronizerAlias: SynchronizerAlias,
      parent: SyncServiceError,
  )(implicit
      val loggingContext: ErrorLoggingContext
  ) extends SynchronizerMigrationError
      with ParentCantonError[SyncServiceError] {

    override def logOnCreation: Boolean = false
    override def mixinContext: Map[String, String] = Map("synchronizer" -> synchronizerAlias.unwrap)

  }

  object InternalError
      extends ErrorCode(
        "BROKEN_SYNCHRONIZER_MIGRATION",
        ErrorCategory.SystemInternalAssumptionViolated,
      ) {
    final case class DuplicateConfig(alias: SynchronizerAlias)(implicit
        val loggingContext: ErrorLoggingContext
    ) extends CantonError.Impl(
          cause = show"The synchronizer alias $alias was already present, but shouldn't be"
        )
        with SynchronizerMigrationError

    final case class FailedReadingAcs(source: SynchronizerAlias, err: SyncStateInspectionError)(
        implicit val loggingContext: ErrorLoggingContext
    ) extends CantonError.Impl(
          cause = show"Failed reading the ACS"
        )
        with SynchronizerMigrationError

    final case class FailedMigratingContracts(source: SynchronizerAlias, err: String)(implicit
        val loggingContext: ErrorLoggingContext
    ) extends CantonError.Impl(
          cause = show"Migrating the ACS to the new synchronizer failed unexpectedly!"
        )
        with SynchronizerMigrationError

    final case class Generic(reason: String)(implicit
        val loggingContext: ErrorLoggingContext
    ) extends CantonError.Impl(
          cause = show"Failure during migration"
        )
        with SynchronizerMigrationError

  }

}
