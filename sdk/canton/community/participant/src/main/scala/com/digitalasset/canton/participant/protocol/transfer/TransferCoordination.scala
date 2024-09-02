// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.canton.participant.protocol.transfer

import cats.data.EitherT
import cats.instances.future.catsStdInstancesForFuture
import cats.syntax.functor.*
import com.digitalasset.canton.config.RequireTypes.NonNegativeInt
import com.digitalasset.canton.crypto.{DomainSnapshotSyncCryptoApi, SyncCryptoApiProvider}
import com.digitalasset.canton.data.{CantonTimestamp, TransferSubmitterMetadata}
import com.digitalasset.canton.lifecycle.FutureUnlessShutdown
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.participant.protocol.transfer.TransferProcessingSteps.{
  ApplicationShutdown,
  TransferProcessorError,
  TransferStoreFailed,
  UnknownDomain,
}
import com.digitalasset.canton.participant.store.TransferStore
import com.digitalasset.canton.participant.sync.SyncDomainPersistentStateManager
import com.digitalasset.canton.protocol.*
import com.digitalasset.canton.protocol.messages.DeliveredTransferOutResult
import com.digitalasset.canton.sequencing.protocol.TimeProof
import com.digitalasset.canton.time.DomainTimeTracker
import com.digitalasset.canton.topology.DomainId
import com.digitalasset.canton.tracing.{TraceContext, Traced}
import com.digitalasset.canton.util.OptionUtil
import com.digitalasset.canton.version.ProtocolVersion
import com.digitalasset.canton.version.Transfer.TargetProtocolVersion

import scala.concurrent.{ExecutionContext, Future}

class TransferCoordination(
    transferStoreFor: TargetDomainId => Either[TransferProcessorError, TransferStore],
    recentTimeProofFor: RecentTimeProofProvider,
    inSubmissionById: DomainId => Option[TransferSubmissionHandle],
    val protocolVersionFor: Traced[DomainId] => Option[ProtocolVersion],
    syncCryptoApi: SyncCryptoApiProvider,
    override val loggerFactory: NamedLoggerFactory,
)(implicit ec: ExecutionContext)
    extends NamedLogging {

  private[transfer] def awaitDomainTime(domain: DomainId, timestamp: CantonTimestamp)(implicit
      traceContext: TraceContext
  ): EitherT[Future, UnknownDomain, Unit] =
    inSubmissionById(domain) match {
      case Some(handle) =>
        handle.timeTracker.requestTick(timestamp, immediately = true)
        EitherT.right(handle.timeTracker.awaitTick(timestamp).map(_.void).getOrElse(Future.unit))
      case None =>
        EitherT.leftT(
          UnknownDomain(domain, s"Unable to find domain when awaiting domain time $timestamp.")
        )
    }

  /** Returns a future that completes when a snapshot can be taken on the given domain for the given timestamp.
    *
    * This is used when a transfer-in blocks for the identity state at the transfer-out. For more general uses,
    * `awaitTimestamp` should be preferred as it triggers the progression of time on `domain` by requesting a tick.
    */
  private[transfer] def awaitTransferOutTimestamp(
      domain: SourceDomainId,
      staticDomainParameters: StaticDomainParameters,
      timestamp: CantonTimestamp,
  )(implicit
      traceContext: TraceContext
  ): Either[UnknownDomain, Future[Unit]] =
    syncCryptoApi
      .forDomain(domain.unwrap, staticDomainParameters)
      .toRight(UnknownDomain(domain.unwrap, "When transfer-in waits for transfer-out timestamp"))
      .map(_.awaitTimestamp(timestamp).getOrElse(Future.unit))

  /** Returns a future that completes when it is safe to take an identity snapshot for the given `timestamp` on the given `domain`.
    * [[scala.None$]] indicates that this point has already been reached before the call.
    * [[scala.Left$]] if the `domain` is unknown or the participant is not connected to the domain.
    */
  private[transfer] def awaitTimestamp(
      domain: DomainId,
      staticDomainParameters: StaticDomainParameters,
      timestamp: CantonTimestamp,
  )(implicit
      traceContext: TraceContext
  ): Either[TransferProcessorError, Option[Future[Unit]]] =
    OptionUtil
      .zipWith(syncCryptoApi.forDomain(domain, staticDomainParameters), inSubmissionById(domain)) {
        (cryptoApi, handle) =>
          // we request a tick immediately only if the clock is a SimClock
          handle.timeTracker.requestTick(timestamp, immediately = true)
          cryptoApi.awaitTimestamp(timestamp)
      }
      .toRight(UnknownDomain(domain, "When waiting for timestamp"))

  /** Similar to [[awaitTimestamp]] but lifted into an [[EitherT]]
    *
    * @param onImmediate A callback that will be invoked if no wait was actually needed
    */
  private[transfer] def awaitTimestamp(
      domain: DomainId,
      staticDomainParameters: StaticDomainParameters,
      timestamp: CantonTimestamp,
      onImmediate: => Future[Unit],
  )(implicit
      traceContext: TraceContext
  ): EitherT[Future, TransferProcessorError, Unit] =
    for {
      timeout <- EitherT.fromEither[Future](
        awaitTimestamp(domain, staticDomainParameters, timestamp)
      )
      _ <- EitherT.right[TransferProcessorError](timeout.getOrElse(onImmediate))
    } yield ()

  private[transfer] def awaitTimestampUS(
      domain: DomainId,
      staticDomainParameters: StaticDomainParameters,
      timestamp: CantonTimestamp,
      onImmediate: => Future[Unit],
  )(implicit
      traceContext: TraceContext
  ): EitherT[FutureUnlessShutdown, TransferProcessorError, Unit] =
    for {
      wait <- EitherT.fromEither[FutureUnlessShutdown](
        awaitTimestamp(domain, staticDomainParameters, timestamp)
      )
      _ <- EitherT.right(FutureUnlessShutdown.outcomeF(wait.getOrElse(onImmediate)))
    } yield ()

  /** Submits a transfer-in. Used by the [[TransferOutProcessingSteps]] to automatically trigger the submission of a
    * transfer-in after the exclusivity timeout.
    */
  private[transfer] def transferIn(
      targetDomain: TargetDomainId,
      submitterMetadata: TransferSubmitterMetadata,
      reassignmentId: ReassignmentId,
  )(implicit
      traceContext: TraceContext
  ): EitherT[Future, TransferProcessorError, TransferInProcessingSteps.SubmissionResult] = {
    logger.debug(s"Triggering automatic transfer-in of transfer `$reassignmentId`")

    for {
      inSubmission <- EitherT.fromEither[Future](
        inSubmissionById(targetDomain.unwrap).toRight(
          UnknownDomain(targetDomain.unwrap, "When submitting assignment")
        )
      )
      submissionResult <- inSubmission
        .submitTransferIn(
          submitterMetadata,
          reassignmentId,
        )
        .mapK(FutureUnlessShutdown.outcomeK)
        .semiflatMap(Predef.identity)
        .onShutdown(Left(ApplicationShutdown))
    } yield submissionResult
  }

  /** Returns a [[crypto.DomainSnapshotSyncCryptoApi]] for the given `domain` at the given timestamp.
    * The returned future fails with [[java.lang.IllegalArgumentException]] if the `domain` has not progressed far enough
    * such that it can compute the snapshot. Use [[awaitTimestamp]] to ensure progression to `timestamp`.
    */
  private[transfer] def cryptoSnapshot(
      domain: DomainId,
      staticDomainParameters: StaticDomainParameters,
      timestamp: CantonTimestamp,
  )(implicit
      traceContext: TraceContext
  ): EitherT[Future, TransferProcessorError, DomainSnapshotSyncCryptoApi] =
    EitherT
      .fromEither[Future](
        syncCryptoApi
          .forDomain(domain, staticDomainParameters)
          .toRight(UnknownDomain(domain, "When getting crypto snapshot"): TransferProcessorError)
      )
      .semiflatMap(_.snapshot(timestamp))

  private[transfer] def awaitTimestampAndGetCryptoSnapshot(
      domain: DomainId,
      staticDomainParameters: StaticDomainParameters,
      timestamp: CantonTimestamp,
  )(implicit
      traceContext: TraceContext
  ): EitherT[FutureUnlessShutdown, TransferProcessorError, DomainSnapshotSyncCryptoApi] =
    for {
      _ <- awaitTimestampUS(
        domain,
        staticDomainParameters,
        timestamp,
        Future.unit,
      )
      snapshot <- cryptoSnapshot(domain, staticDomainParameters, timestamp).mapK(
        FutureUnlessShutdown.outcomeK
      )
    } yield snapshot

  private[transfer] def getTimeProofAndSnapshot(
      targetDomain: TargetDomainId,
      staticDomainParameters: StaticDomainParameters,
  )(implicit
      traceContext: TraceContext
  ): EitherT[
    FutureUnlessShutdown,
    TransferProcessorError,
    (TimeProof, DomainSnapshotSyncCryptoApi),
  ] =
    for {
      timeProof <- recentTimeProofFor.get(targetDomain, staticDomainParameters)
      // Since events are stored before they are processed, we wait just to be sure.
      targetCrypto <- awaitTimestampAndGetCryptoSnapshot(
        targetDomain.unwrap,
        staticDomainParameters,
        timeProof.timestamp,
      )
    } yield (timeProof, targetCrypto)

  /** Stores the given transfer data on the target domain. */
  private[transfer] def addTransferOutRequest(
      transferData: TransferData
  )(implicit
      traceContext: TraceContext
  ): EitherT[FutureUnlessShutdown, TransferProcessorError, Unit] =
    for {
      transferStore <- EitherT.fromEither[FutureUnlessShutdown](
        transferStoreFor(transferData.targetDomain)
      )
      _ <- transferStore
        .addTransfer(transferData)
        .leftMap[TransferProcessorError](TransferStoreFailed(transferData.reassignmentId, _))
    } yield ()

  /** Adds the transfer-out result to the transfer stored on the given domain. */
  private[transfer] def addTransferOutResult(
      domain: TargetDomainId,
      transferOutResult: DeliveredTransferOutResult,
  )(implicit
      traceContext: TraceContext
  ): EitherT[FutureUnlessShutdown, TransferProcessorError, Unit] =
    for {
      transferStore <- EitherT.fromEither[FutureUnlessShutdown](transferStoreFor(domain))
      _ <- transferStore
        .addTransferOutResult(transferOutResult)
        .leftMap[TransferProcessorError](TransferStoreFailed(transferOutResult.reassignmentId, _))
    } yield ()

  /** Removes the given [[com.digitalasset.canton.protocol.ReassignmentId]] from the given [[com.digitalasset.canton.topology.DomainId]]'s [[store.TransferStore]]. */
  private[transfer] def deleteTransfer(
      targetDomain: TargetDomainId,
      reassignmentId: ReassignmentId,
  )(implicit
      traceContext: TraceContext
  ): EitherT[Future, TransferProcessorError, Unit] =
    for {
      transferStore <- EitherT.fromEither[Future](transferStoreFor(targetDomain))
      _ <- EitherT.right[TransferProcessorError](transferStore.deleteTransfer(reassignmentId))
    } yield ()
}

object TransferCoordination {
  def apply(
      transferTimeProofFreshnessProportion: NonNegativeInt,
      syncDomainPersistentStateManager: SyncDomainPersistentStateManager,
      submissionHandles: DomainId => Option[TransferSubmissionHandle],
      syncCryptoApi: SyncCryptoApiProvider,
      loggerFactory: NamedLoggerFactory,
  )(implicit ec: ExecutionContext): TransferCoordination = {
    def domainDataFor(domain: TargetDomainId): Either[UnknownDomain, TransferStore] =
      syncDomainPersistentStateManager
        .get(domain.unwrap)
        .map(_.transferStore)
        .toRight(UnknownDomain(domain.unwrap, "looking for persistent state"))

    val domainProtocolVersionGetter: Traced[DomainId] => Option[ProtocolVersion] =
      (tracedDomainId: Traced[DomainId]) =>
        syncDomainPersistentStateManager.protocolVersionFor(tracedDomainId.value)

    val recentTimeProofProvider = new RecentTimeProofProvider(
      submissionHandles,
      syncCryptoApi,
      loggerFactory,
      transferTimeProofFreshnessProportion,
    )

    new TransferCoordination(
      transferStoreFor = domainDataFor,
      recentTimeProofFor = recentTimeProofProvider,
      inSubmissionById = submissionHandles,
      protocolVersionFor = domainProtocolVersionGetter,
      syncCryptoApi = syncCryptoApi,
      loggerFactory = loggerFactory,
    )
  }
}

trait TransferSubmissionHandle {
  def timeTracker: DomainTimeTracker

  def submitTransferOut(
      submitterMetadata: TransferSubmitterMetadata,
      contractId: LfContractId,
      targetDomain: TargetDomainId,
      targetProtocolVersion: TargetProtocolVersion,
  )(implicit
      traceContext: TraceContext
  ): EitherT[Future, TransferProcessorError, FutureUnlessShutdown[
    TransferOutProcessingSteps.SubmissionResult
  ]]

  def submitTransferIn(
      submitterMetadata: TransferSubmitterMetadata,
      reassignmentId: ReassignmentId,
  )(implicit
      traceContext: TraceContext
  ): EitherT[Future, TransferProcessorError, FutureUnlessShutdown[
    TransferInProcessingSteps.SubmissionResult
  ]]
}
