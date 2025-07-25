// Copyright (c) 2025 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.canton.participant.admin.repair

import cats.Eval
import cats.data.{EitherT, OptionT}
import cats.syntax.either.*
import cats.syntax.foldable.*
import cats.syntax.parallel.*
import cats.syntax.traverse.*
import com.daml.nonempty.NonEmpty
import com.digitalasset.canton.*
import com.digitalasset.canton.config.ProcessingTimeout
import com.digitalasset.canton.config.RequireTypes.PositiveInt
import com.digitalasset.canton.crypto.SyncCryptoApiParticipantProvider
import com.digitalasset.canton.data.{CantonTimestamp, LedgerTimeBoundaries}
import com.digitalasset.canton.discard.Implicits.DiscardOps
import com.digitalasset.canton.ledger.participant.state.{RepairUpdate, TransactionMeta, Update}
import com.digitalasset.canton.lifecycle.{
  FlagCloseable,
  FutureUnlessShutdown,
  HasCloseContext,
  LifeCycle,
}
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.networking.grpc.CantonGrpcUtil.GrpcErrors
import com.digitalasset.canton.participant.ParticipantNodeParameters
import com.digitalasset.canton.participant.admin.PackageDependencyResolver
import com.digitalasset.canton.participant.admin.data.RepairContract
import com.digitalasset.canton.participant.admin.repair.RepairService.ContractToAdd
import com.digitalasset.canton.participant.event.RecordTime
import com.digitalasset.canton.participant.ledger.api.LedgerApiIndexer
import com.digitalasset.canton.participant.protocol.ContractAuthenticator
import com.digitalasset.canton.participant.store.*
import com.digitalasset.canton.participant.sync.{
  ConnectedSynchronizersLookup,
  SyncEphemeralStateFactory,
  SyncPersistentStateLookup,
}
import com.digitalasset.canton.participant.synchronizer.SynchronizerAliasManager
import com.digitalasset.canton.participant.util.TimeOfChange
import com.digitalasset.canton.protocol.{LfChoiceName, *}
import com.digitalasset.canton.store.SequencedEventStore
import com.digitalasset.canton.topology.{ParticipantId, PhysicalSynchronizerId, SynchronizerId}
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.*
import com.digitalasset.canton.util.PekkoUtil.FutureQueue
import com.digitalasset.canton.util.ReassignmentTag.{Source, Target}
import com.digitalasset.canton.util.ShowUtil.*
import com.digitalasset.canton.util.retry.AllExceptionRetryPolicy
import com.digitalasset.daml.lf.CantonOnly
import com.digitalasset.daml.lf.data.{Bytes, ImmArray}
import com.digitalasset.daml.lf.transaction.CreationTime
import com.google.common.annotations.VisibleForTesting
import org.slf4j.event.Level

import scala.Ordered.orderingToOrdered
import scala.concurrent.duration.DurationInt
import scala.concurrent.{ExecutionContext, Future}

/** Implements the repair commands. Repair commands work only if the participant has disconnected
  * from the affected synchronizers. Additionally for repair commands, which change the Ledger API
  * events: all synchronizers needs to be disconnected, and the indexer is switched to repair mode.
  * Every individual repair command is executed transactionally, i.e., either all its effects are
  * applied or none. This is achieved by the repair-indexer only changing the Synchronizer Indexes
  * for the affected synchronizers after all previous operations were successful, and the emitted
  * Update events are all persisted. In case of an error during repair, or crash during repair: on
  * node and synchronizer recovery all the changes will be purged. During a repair operation (as
  * synchronizers are disconnected) no new events are visible on the Ledger API, neither the ones
  * added by the ongoing repair. As the repair operation successfully finished new events (if any)
  * will become visible on the Ledger API - Ledger End and synchronizer indexes change, open tailing
  * streams start emitting the repair events if applicable.
  *
  * @param executionQueue
  *   Sequential execution queue on which repair actions must be run. This queue is shared with the
  *   CantonSyncService, which uses it for synchronizer connections. Sharing it ensures that we
  *   cannot connect to the synchronizer while a repair action is running and vice versa. It also
  *   ensure only one repair runs at a time. This ensures concurrent activity among repair
  *   operations does not corrupt state.
  */
final class RepairService(
    participantId: ParticipantId,
    syncCrypto: SyncCryptoApiParticipantProvider,
    packageDependencyResolver: PackageDependencyResolver,
    contractAuthenticator: ContractAuthenticator,
    contractStore: Eval[ContractStore],
    ledgerApiIndexer: Eval[LedgerApiIndexer],
    aliasManager: SynchronizerAliasManager,
    parameters: ParticipantNodeParameters,
    syncPersistentStateLookup: SyncPersistentStateLookup,
    connectedSynchronizersLookup: ConnectedSynchronizersLookup,
    @VisibleForTesting
    private[canton] val executionQueue: SimpleExecutionQueue,
    protected val loggerFactory: NamedLoggerFactory,
)(implicit ec: ExecutionContext)
    extends NamedLogging
    with FlagCloseable
    with HasCloseContext {

  private type MissingContract = ContractInstance
  private type MissingAssignment =
    (LfContractId, Source[SynchronizerId], ReassignmentCounter, TimeOfChange)
  private type MissingAdd = (LfContractId, ReassignmentCounter, TimeOfChange)
  private type MissingPurge = (LfContractId, TimeOfChange)

  override protected def timeouts: ProcessingTimeout = parameters.processingTimeouts

  private def synchronizerNotConnected(
      psid: PhysicalSynchronizerId
  ): EitherT[FutureUnlessShutdown, String, Unit] =
    EitherT.cond(
      !connectedSynchronizersLookup.isConnected(psid),
      (),
      s"Participant is still connected to synchronizer $psid",
    )

  private def contractToAdd(
      repairContract: RepairContract,
      ignoreAlreadyAdded: Boolean,
      acsState: Option[ActiveContractStore.Status],
      storedContract: Option[SerializableContract],
  )(implicit
      traceContext: TraceContext
  ): EitherT[FutureUnlessShutdown, String, Option[ContractToAdd]] = {
    val contractId = repairContract.contract.contractId

    def addContract(
        reassigningFrom: Option[Source[SynchronizerId]]
    ): EitherT[FutureUnlessShutdown, String, Option[ContractToAdd]] = Right(
      Option(
        ContractToAdd(repairContract.contract, repairContract.reassignmentCounter, reassigningFrom)
      )
    ).toEitherT[FutureUnlessShutdown]

    acsState match {
      case None => addContract(reassigningFrom = None)

      case Some(ActiveContractStore.Active(_)) =>
        if (ignoreAlreadyAdded) {
          logger.debug(s"Skipping contract $contractId because it is already active")
          for {
            contractAlreadyThere <- EitherT.fromEither[FutureUnlessShutdown](
              storedContract.toRight {
                s"Contract ${repairContract.contract.contractId} is active but is not found in the stores"
              }
            )
            _ <- EitherTUtil.condUnitET[FutureUnlessShutdown](
              contractAlreadyThere == repairContract.contract,
              log(
                s"Contract $contractId exists in synchronizer, but does not match with contract being added. "
                  + s"Existing contract is $contractAlreadyThere while contract supposed to be added ${repairContract.contract}"
              ),
            )
          } yield None
        } else {
          EitherT.leftT {
            log(
              s"A contract with $contractId is already active. Set ignoreAlreadyAdded = true to skip active contracts."
            )
          }
        }
      case Some(ActiveContractStore.Archived) =>
        EitherT.leftT(
          log(
            s"Cannot add previously archived contract ${repairContract.contract.contractId} as archived contracts cannot become active."
          )
        )
      case Some(ActiveContractStore.Purged) => addContract(reassigningFrom = None)
      case Some(ActiveContractStore.ReassignedAway(targetSynchronizer, reassignmentCounter)) =>
        log(
          s"Marking contract ${repairContract.contract.contractId} previously unassigned targetting $targetSynchronizer as " +
            s"assigned from $targetSynchronizer (even though contract may have been reassigned to yet another synchronizer since)."
        ).discard

        val isReassignmentCounterIncreasing =
          repairContract.reassignmentCounter > reassignmentCounter

        if (isReassignmentCounterIncreasing) {
          addContract(reassigningFrom = Option(Source(targetSynchronizer.unwrap)))
        } else {
          EitherT.leftT(
            log(
              s"The reassignment counter ${repairContract.reassignmentCounter} of the contract " +
                s"${repairContract.contract.contractId} needs to be strictly larger than the reassignment counter " +
                s"$reassignmentCounter at the time of the unassignment."
            )
          )
        }

    }
  }

  /** Prepare contract for add, including re-computing metadata
    * @param repairContract
    *   Contract to be added
    * @param acsState
    *   If the contract is known, its status
    * @param storedContract
    *   If the contract already exist in the ContractStore, the stored copy
    */
  private def readRepairContractCurrentState(
      repairContract: RepairContract,
      acsState: Option[ActiveContractStore.Status],
      storedContract: Option[SerializableContract],
      ignoreAlreadyAdded: Boolean,
  )(implicit
      traceContext: TraceContext
  ): EitherT[FutureUnlessShutdown, String, Option[ContractToAdd]] =
    for {
      _ <- EitherT
        .fromEither[FutureUnlessShutdown](
          contractAuthenticator.authenticateSerializable(repairContract.contract)
        )
        .leftMap(e =>
          log(s"Failed to authenticate contract with id: ${repairContract.contract.contractId}: $e")
        )
      contractToAdd <- contractToAdd(
        repairContract,
        ignoreAlreadyAdded = ignoreAlreadyAdded,
        acsState = acsState,
        storedContract = storedContract,
      )
    } yield contractToAdd

  // The repair request gets inserted at the reprocessing starting point.
  // We use the prenextTimestamp such that a regular request is always the first request for a given timestamp.
  // This is needed for causality tracking, which cannot use a tie breaker on timestamps.
  //
  // If this repair request succeeds, it will advance the clean RequestIndex to this time of change.
  // That's why it is important that there are no inflight validation requests before the repair request.
  private def readSynchronizerData(
      synchronizerId: SynchronizerId,
      synchronizerAlias: SynchronizerAlias,
  )(implicit
      traceContext: TraceContext
  ): EitherT[FutureUnlessShutdown, String, RepairRequest.SynchronizerData] =
    for {
      psid <- EitherT.fromEither[FutureUnlessShutdown](
        syncPersistentStateLookup
          .latestKnownPSId(synchronizerId)
          .toRight(s"Unable to resolve $synchronizerId to a physical synchronizer id")
      )

      _ = logger.debug(s"Using $psid as $synchronizerId")

      persistentState <- EitherT.fromEither[FutureUnlessShutdown](
        lookUpSynchronizerPersistence(psid)
      )
      synchronizerIndex <- EitherT
        .right(
          ledgerApiIndexer.value.ledgerApiStore.value.cleanSynchronizerIndex(synchronizerId)
        )
      topologyFactory <- syncPersistentStateLookup
        .topologyFactoryFor(psid)
        .toRight(s"No topology factory for synchronizer $synchronizerAlias")
        .toEitherT[FutureUnlessShutdown]

      topologySnapshot = topologyFactory.createTopologySnapshot(
        SyncEphemeralStateFactory.currentRecordTime(synchronizerIndex),
        packageDependencyResolver,
        preferCaching = true,
      )
      synchronizerParameters <- OptionT(persistentState.parameterStore.lastParameters)
        .toRight(log(s"No static synchronizer parameters found for $synchronizerAlias"))
    } yield RepairRequest.SynchronizerData(
      psid,
      synchronizerAlias,
      topologySnapshot,
      persistentState,
      synchronizerParameters,
      SyncEphemeralStateFactory.currentRecordTime(synchronizerIndex),
      SyncEphemeralStateFactory.nextRepairCounter(synchronizerIndex),
      synchronizerIndex,
    )

  /** Participant repair utility for manually adding contracts to a synchronizer in an offline
    * fashion.
    *
    * @param synchronizerAlias
    *   alias of synchronizer to add contracts to. The synchronizer needs to be configured, but
    *   disconnected to prevent race conditions.
    * @param contracts
    *   contracts to add. Relevant pieces of each contract: create-arguments (LfThinContractInst),
    *   template-id (LfThinContractInst), contractId, ledgerCreateTime, salt (to be added to
    *   SerializableContract), and witnesses, SerializableContract.metadata is only validated, but
    *   otherwise ignored as stakeholder and signatories can be recomputed from contracts.
    * @param ignoreAlreadyAdded
    *   whether to ignore and skip over contracts already added/present in the synchronizer. Setting
    *   this to true (at least on retries) enables writing idempotent repair scripts.
    * @param ignoreStakeholderCheck
    *   do not check for stakeholder presence for the given parties
    * @param workflowIdPrefix
    *   If present, each transaction generated for added contracts will have a workflow ID whose
    *   prefix is the one set and the suffix is a sequential number and the number of transactions
    *   generated as part of the addition (e.g. `import-foo-1-2`, `import-foo-2-2`)
    */
  def addContracts(
      synchronizerAlias: SynchronizerAlias,
      contracts: Seq[RepairContract],
      ignoreAlreadyAdded: Boolean,
      ignoreStakeholderCheck: Boolean,
      workflowIdPrefix: Option[String] = None,
  )(implicit traceContext: TraceContext): Either[String, Unit] = {
    logger.info(
      s"Adding ${contracts.length} contracts to synchronizer $synchronizerAlias with ignoreAlreadyAdded=$ignoreAlreadyAdded and ignoreStakeholderCheck=$ignoreStakeholderCheck"
    )
    if (contracts.isEmpty) {
      Either.right(logger.info("No contracts to add specified"))
    } else {
      runConsecutiveAndAwaitUS(
        "repair.add",
        withRepairIndexer { repairIndexer =>
          (for {
            synchronizerId <- EitherT.fromEither[FutureUnlessShutdown](
              aliasManager
                .synchronizerIdForAlias(synchronizerAlias)
                .toRight(s"Could not find $synchronizerAlias")
            )

            synchronizer <- readSynchronizerData(synchronizerId, synchronizerAlias)

            contractStates <- EitherT.right[String](
              readContractAcsStates(
                synchronizer.persistentState,
                contracts.map(_.contract.contractId),
              )
            )

            contractInstances <-
              logOnFailureWithInfoLevel(
                contractStore.value.lookupManyUncached(contracts.map(_.contract.contractId)),
                "Unable to lookup contracts in contract store",
              ).map(_.flatten)

            storedContracts <- EitherT.fromEither[FutureUnlessShutdown](
              contractInstances
                .traverse { contract =>
                  SerializableContract
                    .fromLfFatContractInst(contract.inst)
                    .map(c => c.contractId -> c)
                }
                .map(_.toMap)
            )

            filteredContracts <- contracts.zip(contractStates).parTraverseFilter {
              case (contract, acsState) =>
                readRepairContractCurrentState(
                  repairContract = contract,
                  acsState = acsState,
                  storedContract = storedContracts.get(contract.contract.contractId),
                  ignoreAlreadyAdded = ignoreAlreadyAdded,
                )
            }

            contractsByCreation = filteredContracts
              .groupBy(_.contract.ledgerCreateTime)
              .toList
              .sortBy { case (ledgerCreateTime, _) => ledgerCreateTime.time }

            _ <- PositiveInt
              .create(contractsByCreation.size)
              .fold(
                _ =>
                  EitherT.rightT[FutureUnlessShutdown, String](
                    logger.info("No contract needs to be added")
                  ),
                groupCount => {
                  val workflowIds = workflowIdsFromPrefix(workflowIdPrefix, groupCount)
                  for {
                    repair <- initRepairRequestAndVerifyPreconditions(
                      synchronizer = synchronizer,
                      repairCountersToAllocate = groupCount,
                    )

                    allStakeholders = filteredContracts
                      .flatMap(_.contract.metadata.stakeholders)
                      .toSet

                    allHostedStakeholders <- EitherT.right(
                      repair.synchronizer.topologySnapshot
                        .hostedOn(allStakeholders, participantId)
                        .map(_.keySet)
                    )

                    _ <- addContractsCheck(
                      repair,
                      allHostedStakeholders = allHostedStakeholders,
                      ignoreStakeholderCheck = ignoreStakeholderCheck,
                      filteredContracts,
                    )

                    contractsToAdd = repair.timesOfRepair.zip(contractsByCreation)

                    _ = logger.debug(s"Publishing ${filteredContracts.size} added contracts")

                    contractsWithTimeOfChange = contractsToAdd.flatMap { case (tor, (_, cs)) =>
                      cs.map(_ -> tor.toToc)
                    }

                    _ <- persistAddContracts(
                      repair,
                      contractsToAdd = contractsWithTimeOfChange,
                      storedContracts = storedContracts,
                    )

                    // Commit and publish added contracts via the indexer to the ledger api.
                    _ <- EitherT.right[String](
                      writeContractsAddedEvents(
                        repair,
                        contractsToAdd,
                        workflowIds,
                        repairIndexer,
                      )
                    )
                  } yield ()
                },
              )
          } yield ()).mapK(FutureUnlessShutdown.failOnShutdownToAbortExceptionK("addContracts"))
        },
      )
    }
  }

  private def workflowIdsFromPrefix(
      prefix: Option[String],
      n: PositiveInt,
  ): Iterator[Option[LfWorkflowId]] =
    prefix.fold(
      Iterator.continually(Option.empty[LfWorkflowId])
    )(prefix =>
      1.to(n.value)
        .map(i => Some(LfWorkflowId.assertFromString(s"$prefix-$i-${n.value}")))
        .iterator
    )

  /** Participant repair utility for manually purging (archiving) contracts in an offline fashion.
    *
    * @param synchronizerAlias
    *   alias of synchronizer to purge contracts from. The synchronizer needs to be configured, but
    *   disconnected to prevent race conditions.
    * @param contractIds
    *   lf contract ids of contracts to purge
    * @param ignoreAlreadyPurged
    *   whether to ignore already purged contracts.
    */
  def purgeContracts(
      synchronizerAlias: SynchronizerAlias,
      contractIds: NonEmpty[Seq[LfContractId]],
      ignoreAlreadyPurged: Boolean,
  )(implicit traceContext: TraceContext): Either[String, Unit] = {
    logger.info(
      s"Purging ${contractIds.length} contracts from $synchronizerAlias with ignoreAlreadyPurged=$ignoreAlreadyPurged"
    )
    runConsecutiveAndAwaitUS(
      "repair.purge",
      withRepairIndexer { repairIndexer =>
        (for {
          synchronizerId <- EitherT.fromEither[FutureUnlessShutdown](
            aliasManager
              .synchronizerIdForAlias(synchronizerAlias)
              .toRight(s"Could not find $synchronizerAlias")
          )

          repair <- initRepairRequestAndVerifyPreconditions(synchronizerId)

          contractStates <- EitherT.right[String](
            readContractAcsStates(
              repair.synchronizer.persistentState,
              contractIds,
            )
          )

          contractInstances <-
            logOnFailureWithInfoLevel(
              contractStore.value.lookupManyUncached(contractIds),
              "Unable to lookup contracts in contract store",
            ).map(_.flatten)

          storedContracts <- EitherT.fromEither[FutureUnlessShutdown](
            contractInstances
              .traverse { contract =>
                SerializableContract
                  .fromLfFatContractInst(contract.inst)
                  .map(c => c.contractId -> c)
              }
              .map(_.toMap)
          )

          toc = repair.tryExactlyOneTimeOfRepair.toToc

          operationsE = contractIds
            .zip(contractStates)
            .foldMapM { case (cid, acsStatus) =>
              val storedContract = storedContracts.get(cid)
              computePurgeOperations(toc, ignoreAlreadyPurged)(cid, acsStatus, storedContract)
                .map { case (missingPurge, missingAssignment) =>
                  (storedContract.toList, missingPurge, missingAssignment)
                }
            }
          operations <- EitherT.fromEither[FutureUnlessShutdown](operationsE)

          (contractsToPublishUpstream, missingPurges, missingAssignments) = operations

          // Update the stores
          _ <- repair.synchronizer.persistentState.activeContractStore
            .purgeContracts(missingPurges)
            .toEitherTWithNonaborts
            .leftMap(e =>
              log(s"Failed to purge contracts $missingAssignments in ActiveContractStore: $e")
            )

          _ <- repair.synchronizer.persistentState.activeContractStore
            .assignContracts(missingAssignments)
            .toEitherTWithNonaborts
            .leftMap(e =>
              log(
                s"Failed to assign contracts $missingAssignments in ActiveContractStore: $e"
              )
            )

          // Commit and publish purged contracts via the indexer to the ledger api.
          _ <- EitherTUtil.rightUS[String, Unit](
            writeContractsPurgedEvent(contractsToPublishUpstream, repair, repairIndexer)
          )
        } yield ()).mapK(FutureUnlessShutdown.failOnShutdownToAbortExceptionK("purgeContracts"))
      },
    )
  }

  /** Change the assignation of a contract from one synchronizer to another
    *
    * This function here allows us to manually insert a unassignment/assignment into the respective
    * journals in order to change the assignation of a contract from one synchronizer to another.
    * The procedure will result in a consistent state if and only if all the counter parties run the
    * same command. Failure to do so, will results in participants reporting errors and possibly
    * break.
    *
    * @param contracts
    *   Contracts whose assignation should be changed. The reassignment counter is by default
    *   incremented by one. A non-empty reassignment counter allows to override the default behavior
    *   with the provided counter.
    * @param skipInactive
    *   If true, then the migration will skip contracts in the contractId list that are inactive
    */
  def changeAssignation(
      contracts: NonEmpty[Seq[(LfContractId, Option[ReassignmentCounter])]],
      sourceSynchronizer: Source[SynchronizerId],
      targetSynchronizer: Target[SynchronizerId],
      skipInactive: Boolean,
  )(implicit traceContext: TraceContext): EitherT[FutureUnlessShutdown, String, Unit] = {
    val contractsCount = PositiveInt.tryCreate(contracts.size)
    for {
      _ <- EitherTUtil.condUnitET[FutureUnlessShutdown](
        sourceSynchronizer.unwrap != targetSynchronizer.unwrap,
        "Source must differ from target synchronizer!",
      )

      repairSource <- sourceSynchronizer.traverse(
        initRepairRequestAndVerifyPreconditions(
          _,
          contractsCount,
        )
      )

      repairTarget <- targetSynchronizer.traverse(
        initRepairRequestAndVerifyPreconditions(
          _,
          contractsCount,
        )
      )

      _ <- withRepairIndexer { repairIndexer =>
        val changeAssignation = new ChangeAssignation(
          repairSource,
          repairTarget,
          participantId,
          syncCrypto,
          repairIndexer,
          contractStore.value,
          loggerFactory,
        )
        (for {
          changeAssignationData <- EitherT.rightT[FutureUnlessShutdown, String](
            ChangeAssignation.Data.from(contracts.forgetNE, changeAssignation)
          )
          // Note the following purposely fails if any contract fails which results in not all contracts being processed.
          _ <- changeAssignation
            .changeAssignation(changeAssignationData, skipInactive)
            .map(_ => Seq[Unit]())

        } yield ()).mapK(FutureUnlessShutdown.failOnShutdownToAbortExceptionK("changeAssignation"))
      }
    } yield ()
  }

  def ignoreEvents(
      synchronizerId: PhysicalSynchronizerId,
      fromInclusive: SequencerCounter,
      toInclusive: SequencerCounter,
      force: Boolean,
  )(implicit
      traceContext: TraceContext
  ): EitherT[FutureUnlessShutdown, String, Unit] = {
    logger.info(s"Ignoring sequenced events from $fromInclusive to $toInclusive (force = $force).")
    runConsecutive(
      "repair.skip_messages",
      for {
        _ <- synchronizerNotConnected(synchronizerId)
        _ <- performIfRangeSuitableForIgnoreOperations(synchronizerId, fromInclusive, force)(
          _.ignoreEvents(fromInclusive, toInclusive).leftMap(_.toString)
        )
      } yield (),
    )
  }

  /** Rollback the Unassignment. The contract is re-assigned to the source synchronizer. The
    * reassignment counter is increased by two. The contract is inserted into the contract store on
    * the target synchronizer if it is not already there. Additionally, we publish the reassignment
    * events.
    */
  def rollbackUnassignment(
      reassignmentId: ReassignmentId,
      source: Source[SynchronizerId],
      target: Target[SynchronizerId],
  )(implicit context: TraceContext): EitherT[FutureUnlessShutdown, String, Unit] =
    withRepairIndexer { repairIndexer =>
      (for {
        sourceRepairRequest <- source.traverse(initRepairRequestAndVerifyPreconditions(_))
        targetRepairRequest <- target.traverse(initRepairRequestAndVerifyPreconditions(_))
        reassignmentData <-
          targetRepairRequest.unwrap.synchronizer.persistentState.reassignmentStore
            .lookup(reassignmentId)
            .leftMap(_.message)

        changeAssignation = new ChangeAssignation(
          sourceRepairRequest,
          targetRepairRequest,
          participantId,
          syncCrypto,
          repairIndexer,
          contractStore.value,
          loggerFactory,
        )

        unassignmentData = ChangeAssignation.Data.from(reassignmentData, changeAssignation)
        _ <- changeAssignation.completeUnassigned(unassignmentData)

        changeAssignationBack = new ChangeAssignation(
          Source(targetRepairRequest.unwrap),
          Target(sourceRepairRequest.unwrap),
          participantId,
          syncCrypto,
          repairIndexer,
          contractStore.value,
          loggerFactory,
        )
        contractIdsData <- EitherT.fromEither[FutureUnlessShutdown](
          ChangeAssignation.Data
            .from[Seq[(LfContractId, Option[ReassignmentCounter])]](
              reassignmentData.contractsBatch.contractIds.map(_ -> None).toSeq,
              changeAssignationBack,
            )
            .incrementRepairCounter
        )
        _ <- changeAssignationBack.changeAssignation(
          contractIdsData,
          skipInactive = false,
        )
      } yield ()).mapK(FutureUnlessShutdown.failOnShutdownToAbortExceptionK("rollbackUnassignment"))
    }

  private def performIfRangeSuitableForIgnoreOperations[T](
      psid: PhysicalSynchronizerId,
      from: SequencerCounter,
      force: Boolean,
  )(
      action: SequencedEventStore => EitherT[FutureUnlessShutdown, String, T]
  )(implicit traceContext: TraceContext): EitherT[FutureUnlessShutdown, String, T] =
    for {
      persistentState <- EitherT.fromEither[FutureUnlessShutdown](
        lookUpSynchronizerPersistence(psid)
      )
      _ <- EitherT.right(
        ledgerApiIndexer.value
          .ensureNoProcessingForSynchronizer(psid.logical)
      )
      synchronizerIndex <- EitherT.right(
        ledgerApiIndexer.value.ledgerApiStore.value.cleanSynchronizerIndex(psid.logical)
      )

      synchronizerPredecessor <- EitherT
        .fromEither[FutureUnlessShutdown](
          syncPersistentStateLookup
            .connectionConfig(psid)
            .toRight(s"Cannot find connection config for $psid")
        )
        .map(_.predecessor)

      startingPoints <- EitherT.right(
        SyncEphemeralStateFactory.startingPoints(
          persistentState.requestJournalStore,
          persistentState.sequencedEventStore,
          synchronizerIndex,
          synchronizerPredecessor,
        )
      )
      _ <- EitherTUtil
        .condUnitET[FutureUnlessShutdown](
          force || startingPoints.processing.nextSequencerCounter <= from,
          show"Unable to modify events between $from (inclusive) and ${startingPoints.processing.nextSequencerCounter} (exclusive), " +
            """as they have already been processed. Enable "force" to modify them nevertheless.""",
        )
      res <- action(persistentState.sequencedEventStore)
    } yield res

  def unignoreEvents(
      synchronizerId: PhysicalSynchronizerId,
      fromInclusive: SequencerCounter,
      toInclusive: SequencerCounter,
      force: Boolean,
  )(implicit traceContext: TraceContext): EitherT[FutureUnlessShutdown, String, Unit] = {
    logger.info(
      s"Unignoring sequenced events from $fromInclusive to $toInclusive (force = $force)."
    )
    runConsecutive(
      "repair.unskip_messages",
      for {
        _ <- synchronizerNotConnected(synchronizerId)
        _ <- performIfRangeSuitableForIgnoreOperations(synchronizerId, fromInclusive, force)(
          sequencedEventStore =>
            sequencedEventStore.unignoreEvents(fromInclusive, toInclusive).leftMap(_.toString)
        )
      } yield (),
    )
  }

  /** Checks that the contracts can be added (packages known, stakeholders hosted, ...)
    * @param allHostedStakeholders
    *   All parties that are a stakeholder of one of the contracts and are hosted locally
    */
  private def addContractsCheck(
      repair: RepairRequest,
      allHostedStakeholders: Set[LfPartyId],
      ignoreStakeholderCheck: Boolean,
      contracts: Seq[ContractToAdd],
  )(implicit traceContext: TraceContext): EitherT[FutureUnlessShutdown, String, Unit] =
    for {
      // All referenced templates known and vetted
      _packagesVetted <- contracts
        .map(
          _.contract.rawContractInstance.contractInstance.unversioned.template.packageId
        )
        .distinct
        .parTraverse_(packageKnown)

      _ <- contracts.parTraverse_(
        addContractChecks(
          repair,
          allHostedStakeholders,
          ignoreStakeholderCheck = ignoreStakeholderCheck,
        )
      )
    } yield ()

  /** Checks that one contract can be added (stakeholders hosted, ...)
    * @param allHostedStakeholders
    *   Relevant locally hosted parties
    */
  private def addContractChecks(
      repair: RepairRequest,
      allHostedStakeholders: Set[LfPartyId],
      ignoreStakeholderCheck: Boolean,
  )(
      contractToAdd: ContractToAdd
  )(implicit
      traceContext: TraceContext
  ): EitherT[FutureUnlessShutdown, String, Unit] = {
    val topologySnapshot = repair.synchronizer.topologySnapshot
    val contract = contractToAdd.contract
    val contractId = contractToAdd.cid
    for {
      _warnOnEmptyMaintainers <- EitherT.cond[FutureUnlessShutdown](
        !contract.metadata.maybeKeyWithMaintainers.exists(_.maintainers.isEmpty),
        (),
        log(s"Contract $contractId has key without maintainers."),
      )

      _ <-
        if (ignoreStakeholderCheck) EitherT.rightT[FutureUnlessShutdown, String](())
        else {
          val localStakeholders =
            allHostedStakeholders.intersect(contractToAdd.contract.metadata.stakeholders)

          for {
            // At least one stakeholder is hosted locally
            _ <- EitherTUtil.condUnitET[FutureUnlessShutdown](
              localStakeholders.nonEmpty,
              s"Contract ${contract.contractId} has stakeholders ${contract.metadata.stakeholders} but none of them are hosted locally",
            )

            // All stakeholders exist on the synchronizer
            _ <- topologySnapshot
              .allHaveActiveParticipants(contract.metadata.stakeholders)
              .leftMap { missingStakeholders =>
                log(
                  s"Synchronizer ${repair.synchronizer.alias} missing stakeholders $missingStakeholders of contract ${contract.contractId}"
                )
              }
          } yield ()
        }
    } yield ()
  }

  private def logOnFailureWithInfoLevel[T](f: FutureUnlessShutdown[T], errorMessage: => String)(
      implicit traceContext: TraceContext
  ): EitherT[FutureUnlessShutdown, String, T] =
    EitherT.right(
      FutureUnlessShutdownUtil.logOnFailureUnlessShutdown(f, errorMessage, level = Level.INFO)
    )

  /** Actual persistence work
    * @param repair
    *   Repair request
    * @param contractsToAdd
    *   Contracts to be added
    * @param storedContracts
    *   Contracts that already exists in the store
    */
  private def persistAddContracts(
      repair: RepairRequest,
      contractsToAdd: Seq[(ContractToAdd, TimeOfChange)],
      storedContracts: Map[LfContractId, SerializableContract],
  )(implicit
      traceContext: TraceContext
  ): EitherT[FutureUnlessShutdown, String, Unit] =
    for {
      // We compute first which changes we need to persist
      missingContracts <- contractsToAdd
        .parTraverseFilter[EitherT[FutureUnlessShutdown, String, *], MissingContract] {
          case (contractToAdd, _) =>
            storedContracts.get(contractToAdd.cid) match {
              case None =>
                EitherT.fromEither[FutureUnlessShutdown](
                  ContractInstance(contractToAdd.contract).map(c => Some(c))
                )
              case Some(storedContract) =>
                EitherTUtil
                  .condUnitET[FutureUnlessShutdown](
                    storedContract == contractToAdd.contract,
                    s"Contract ${contractToAdd.cid} already exists in the contract store, but differs from contract to be created. Contract to be created $contractToAdd versus existing contract $storedContract.",
                  )
                  .map(_ => Option.empty[MissingContract])
            }
        }

      (missingAssignments, missingAdds) = contractsToAdd.foldLeft(
        (Seq.empty[MissingAssignment], Seq.empty[MissingAdd])
      ) { case ((missingAssignments, missingAdds), (contract, toc)) =>
        contract.reassigningFrom match {
          case Some(sourceSynchronizerId) =>
            val newAssignment =
              (contract.cid, sourceSynchronizerId, contract.reassignmentCounter, toc)
            (newAssignment +: missingAssignments, missingAdds)

          case None =>
            val newAdd = (contract.cid, contract.reassignmentCounter, toc)
            (missingAssignments, newAdd +: missingAdds)
        }
      }

      // Now, we update the stores
      _ <- logOnFailureWithInfoLevel(
        contractStore.value.storeContracts(missingContracts),
        "Unable to store missing contracts",
      )

      _ <- repair.synchronizer.persistentState.activeContractStore
        .markContractsAdded(missingAdds)
        .toEitherTWithNonaborts
        .leftMap(e =>
          log(
            s"Failed to add contracts ${missingAdds.map { case (cid, _, _) => cid }} in ActiveContractStore: $e"
          )
        )

      _ <- repair.synchronizer.persistentState.activeContractStore
        .assignContracts(missingAssignments)
        .toEitherTWithNonaborts
        .leftMap(e =>
          log(
            s"Failed to assign ${missingAssignments.map { case (cid, _, _, _) => cid }} in ActiveContractStore: $e"
          )
        )
    } yield ()

  /** For the given contract, returns the operations (purge, assignment) to perform
    * @param acsStatus
    *   Status of the contract
    * @param storedContractO
    *   Instance of the contract
    */
  private def computePurgeOperations(toc: TimeOfChange, ignoreAlreadyPurged: Boolean)(
      cid: LfContractId,
      acsStatus: Option[ActiveContractStore.Status],
      storedContractO: Option[SerializableContract],
  )(implicit
      traceContext: TraceContext
  ): Either[String, (Seq[MissingPurge], Seq[MissingAssignment])] = {
    def ignoreOrError(reason: String): Either[String, (Seq[MissingPurge], Seq[MissingAssignment])] =
      Either.cond(
        ignoreAlreadyPurged,
        (Nil, Nil),
        log(
          s"Contract $cid cannot be purged: $reason. Set ignoreAlreadyPurged = true to skip non-existing contracts."
        ),
      )

    // Not checking that the participant hosts a stakeholder as we might be cleaning up contracts
    // on behalf of stakeholders no longer around.
    acsStatus match {
      case None => ignoreOrError("unknown contract")
      case Some(ActiveContractStore.Active(_)) =>
        for {
          _contract <- Either
            .fromOption(
              storedContractO,
              log(show"Active contract $cid not found in contract store"),
            )
        } yield {
          (Seq[MissingPurge]((cid, toc)), Seq.empty[MissingAssignment])
        }
      case Some(ActiveContractStore.Archived) => ignoreOrError("archived contract")
      case Some(ActiveContractStore.Purged) => ignoreOrError("purged contract")
      case Some(ActiveContractStore.ReassignedAway(targetSynchronizer, reassignmentCounter)) =>
        log(
          s"Purging contract $cid previously marked as reassigned to $targetSynchronizer. " +
            s"Marking contract as assigned from $targetSynchronizer (even though contract may have since been reassigned to yet another synchronizer) and subsequently as archived."
        ).discard

        reassignmentCounter.increment.map { newReassignmentCounter =>
          (
            Seq[MissingPurge]((cid, toc)),
            Seq[MissingAssignment](
              (
                cid,
                Source(targetSynchronizer.unwrap),
                newReassignmentCounter,
                toc,
              )
            ),
          )
        }
    }
  }

  private def toArchive(c: SerializableContract): LfNodeExercises = LfNodeExercises(
    targetCoid = c.contractId,
    templateId = c.rawContractInstance.contractInstance.unversioned.template,
    packageName = c.rawContractInstance.contractInstance.unversioned.packageName,
    interfaceId = None,
    choiceId = LfChoiceName.assertFromString("Archive"),
    consuming = true,
    actingParties = c.metadata.signatories,
    chosenValue = c.rawContractInstance.contractInstance.unversioned.arg,
    stakeholders = c.metadata.stakeholders,
    signatories = c.metadata.signatories,
    choiceObservers = Set.empty[LfPartyId], // default archive choice has no choice observers
    choiceAuthorizers = None, // default (signatories + actingParties)
    children = ImmArray.empty[LfNodeId],
    exerciseResult = Some(LfValue.ValueNone),
    keyOpt = c.metadata.maybeKeyWithMaintainers,
    byKey = false,
    version = c.rawContractInstance.contractInstance.version,
  )

  private def writeContractsPurgedEvent(
      contracts: Seq[SerializableContract],
      repair: RepairRequest,
      repairIndexer: FutureQueue[RepairUpdate],
  )(implicit traceContext: TraceContext): Future[Unit] = {
    val nodeIds = LazyList.from(0).map(LfNodeId)
    val txNodes = nodeIds.zip(contracts.map(toArchive)).toMap
    val update = Update.RepairTransactionAccepted(
      transactionMeta = TransactionMeta(
        ledgerEffectiveTime = repair.timestamp.toLf,
        workflowId = None,
        preparationTime = repair.timestamp.toLf,
        submissionSeed = Update.noOpSeed,
        timeBoundaries = LedgerTimeBoundaries.unconstrained,
        optUsedPackages = None,
        optNodeSeeds = None,
        optByKeyNodes = None,
      ),
      transaction = LfCommittedTransaction(
        CantonOnly.lfVersionedTransaction(
          nodes = txNodes,
          roots = ImmArray.from(nodeIds.take(txNodes.size)),
        )
      ),
      updateId = repair.transactionId.tryAsLedgerTransactionId,
      contractMetadata = Map.empty,
      synchronizerId = repair.synchronizer.psid.logical,
      repairCounter = repair.tryExactlyOneRepairCounter,
      recordTime = repair.timestamp,
    )
    // not waiting for Update.persisted, since CommitRepair anyway will be waited for at the end
    repairIndexer.offer(update).map(_ => ())
  }

  private def prepareAddedEvents(
      repair: RepairRequest,
      repairCounter: RepairCounter,
      ledgerCreateTime: CreationTime.CreatedAt,
      contractsAdded: Seq[ContractToAdd],
      workflowIdProvider: () => Option[LfWorkflowId],
  )(implicit traceContext: TraceContext): RepairUpdate = {
    val contractMetadata = contractsAdded.view.map { c =>
      val cId = c.contract.contractId
      cId -> c.driverMetadata(CantonContractIdVersion.tryCantonContractIdVersion(cId))
    }.toMap
    val nodeIds = LazyList.from(0).map(LfNodeId)
    val txNodes = nodeIds.zip(contractsAdded.map(_.contract.toLf)).toMap
    Update.RepairTransactionAccepted(
      transactionMeta = TransactionMeta(
        ledgerEffectiveTime = ledgerCreateTime.time,
        workflowId = workflowIdProvider(),
        preparationTime = repair.timestamp.toLf,
        submissionSeed = Update.noOpSeed,
        timeBoundaries = LedgerTimeBoundaries.unconstrained,
        optUsedPackages = None,
        optNodeSeeds = None,
        optByKeyNodes = None,
      ),
      transaction = LfCommittedTransaction(
        CantonOnly.lfVersionedTransaction(
          nodes = txNodes,
          roots = ImmArray.from(nodeIds.take(txNodes.size)),
        )
      ),
      updateId = randomTransactionId(syncCrypto).tryAsLedgerTransactionId,
      contractMetadata = contractMetadata,
      synchronizerId = repair.synchronizer.psid.logical,
      repairCounter = repairCounter,
      recordTime = repair.timestamp,
    )
  }

  private def writeContractsAddedEvents(
      repair: RepairRequest,
      contractsAdded: Seq[(TimeOfRepair, (CreationTime.CreatedAt, Seq[ContractToAdd]))],
      workflowIds: Iterator[Option[LfWorkflowId]],
      repairIndexer: FutureQueue[RepairUpdate],
  )(implicit traceContext: TraceContext): FutureUnlessShutdown[Unit] =
    FutureUnlessShutdown.outcomeF(MonadUtil.sequentialTraverse_(contractsAdded) {
      case (timeOfChange, (timestamp, contractsToAdd)) =>
        // not waiting for Update.persisted, since CommitRepair anyway will be waited for at the end
        repairIndexer
          .offer(
            prepareAddedEvents(
              repair,
              timeOfChange.repairCounter,
              timestamp,
              contractsToAdd,
              () => workflowIds.next(),
            )
          )
          .map(_ => ())
    })

  private def packageKnown(
      lfPackageId: LfPackageId
  )(implicit traceContext: TraceContext): EitherT[FutureUnlessShutdown, String, Unit] =
    for {
      _packageVetted <- packageDependencyResolver
        .getPackageDescription(lfPackageId)
        .toRight(
          log(s"Failed to locate package $lfPackageId")
        )
    } yield ()

  /** Allows to wait until clean sequencer index has progressed up to a certain timestamp */
  def awaitCleanSequencerTimestamp(
      synchronizerId: SynchronizerId,
      timestamp: CantonTimestamp,
  )(implicit traceContext: TraceContext): EitherT[FutureUnlessShutdown, String, Unit] = {
    def check(): FutureUnlessShutdown[Either[String, Unit]] =
      ledgerApiIndexer.value.ledgerApiStore.value
        .cleanSynchronizerIndex(synchronizerId)
        .map(SyncEphemeralStateFactory.lastSequencerTimestamp)
        .map { lastSequencerTimestamp =>
          if (lastSequencerTimestamp >= timestamp) {
            logger.debug(
              s"Clean sequencer index reached $lastSequencerTimestamp, clearing $timestamp"
            )
            Either.unit
          } else {
            val errMsg =
              s"Clean sequencer index is still at $lastSequencerTimestamp which is not yet $timestamp"
            logger.debug(errMsg)
            Left(errMsg)
          }
        }
    EitherT(
      retry
        .Pause(
          logger,
          this,
          retry.Forever,
          50.milliseconds,
          s"awaiting clean-head for=$synchronizerId at ts=$timestamp",
        )
        .unlessShutdown(
          check(),
          AllExceptionRetryPolicy,
        )
    )
  }

  private def repairCounterSequence(
      fromInclusive: RepairCounter,
      length: PositiveInt,
  ): Either[String, NonEmpty[Seq[RepairCounter]]] =
    for {
      rcs <- Seq
        .iterate(fromInclusive.asRight[String], length.value)(_.flatMap(_.increment))
        .sequence
      ne <- NonEmpty.from(rcs).toRight("generated an empty collection with PositiveInt length")
    } yield ne

  /** Repair commands are inserted where processing starts again upon reconnection.
    *
    * @param synchronizerId
    *   The ID of the synchronizer for which the request is valid
    * @param requestCountersToAllocate
    *   The number of repair counters to allocate in order to fulfill the request
    */
  private def initRepairRequestAndVerifyPreconditions(
      synchronizerId: SynchronizerId,
      repairCountersToAllocate: PositiveInt = PositiveInt.one,
  )(implicit traceContext: TraceContext): EitherT[FutureUnlessShutdown, String, RepairRequest] =
    for {
      synchronizerAlias <- EitherT.fromEither[FutureUnlessShutdown](
        aliasManager
          .aliasForSynchronizerId(synchronizerId)
          .toRight(s"synchronizer alias for $synchronizerId not found")
      )
      synchronizerData <- readSynchronizerData(synchronizerId, synchronizerAlias)
      repairRequest <- initRepairRequestAndVerifyPreconditions(
        synchronizerData,
        repairCountersToAllocate,
      )
    } yield repairRequest

  private def initRepairRequestAndVerifyPreconditions(
      synchronizer: RepairRequest.SynchronizerData,
      repairCountersToAllocate: PositiveInt,
  )(implicit traceContext: TraceContext): EitherT[FutureUnlessShutdown, String, RepairRequest] = {
    val rtRepair = RecordTime.fromTimeOfChange(
      TimeOfChange(
        synchronizer.currentRecordTime,
        Some(synchronizer.nextRepairCounter),
      )
    )
    logger
      .debug(
        s"Starting repair request on (${synchronizer.persistentState}, ${synchronizer.alias}) at $rtRepair."
      )
    for {
      _ <- EitherT
        .right(
          SyncEphemeralStateFactory
            .cleanupPersistentState(synchronizer.persistentState, synchronizer.synchronizerIndex)
        )

      incrementalAcsSnapshotWatermark <- EitherT.right(
        synchronizer.persistentState.acsCommitmentStore.runningCommitments.watermark
      )
      _ <- EitherT.cond[FutureUnlessShutdown](
        rtRepair > incrementalAcsSnapshotWatermark,
        (),
        log(
          s"""Cannot apply a repair command as the incremental acs snapshot is already at $incrementalAcsSnapshotWatermark
             |and the repair command would be assigned a record time of $rtRepair.
             |Reconnect to the synchronizer to reprocess inflight validation requests and retry repair afterwards.""".stripMargin
        ),
      )
      repairCounters <- EitherT.fromEither[FutureUnlessShutdown](
        repairCounterSequence(
          synchronizer.nextRepairCounter,
          repairCountersToAllocate,
        )
      )
    } yield RepairRequest(synchronizer, randomTransactionId(syncCrypto), repairCounters)
  }

  /** Read the ACS state for each contract in cids
    * @return
    *   The list of states or an error if one of the states cannot be read. Note that the returned
    *   Seq has same ordering and cardinality of cids
    */
  private def readContractAcsStates(
      persistentState: SyncPersistentState,
      cids: Seq[LfContractId],
  )(implicit
      traceContext: TraceContext
  ): FutureUnlessShutdown[Seq[Option[ActiveContractStore.Status]]] =
    persistentState.activeContractStore
      .fetchStates(cids)
      .map { states =>
        cids.map(cid => states.get(cid).map(_.status))
      }

  // Looks up synchronizer persistence erroring if synchronizer is based on in-memory persistence for which repair is not supported.
  private def lookUpSynchronizerPersistence(
      psid: PhysicalSynchronizerId
  )(implicit
      traceContext: TraceContext
  ): Either[String, SyncPersistentState] =
    for {
      dp <- syncPersistentStateLookup
        .get(psid)
        .toRight(log(s"Could not find persistent state for $psid"))
      _ <- Either.cond(
        !dp.isMemory,
        (),
        log(
          s"$psid is in memory which is not supported by repair. Use db persistence."
        ),
      )
    } yield dp

  private def runConsecutiveAndAwaitUS[B](
      description: String,
      code: => EitherT[FutureUnlessShutdown, String, B],
  )(implicit
      traceContext: TraceContext
  ): Either[String, B] = {
    logger.info(s"Queuing $description")

    // repair commands can take an unbounded amount of time
    parameters.processingTimeouts.unbounded.awaitUS(description) {
      logger.info(s"Queuing $description")
      executionQueue.executeEUS(code, description).value
    }
  }.onShutdown(throw GrpcErrors.AbortedDueToShutdown.Error().asGrpcError)

  private def runConsecutive[B](
      description: String,
      code: => EitherT[FutureUnlessShutdown, String, B],
  )(implicit
      traceContext: TraceContext
  ): EitherT[FutureUnlessShutdown, String, B] = {
    logger.info(s"Queuing $description")
    executionQueue.executeEUS(code, description)
  }

  private def log(message: String)(implicit traceContext: TraceContext): String = {
    // consider errors user errors and log them on the server side as info:
    logger.info(message)
    message
  }

  override protected def onClosed(): Unit = LifeCycle.close(executionQueue)(logger)

  private def withRepairIndexer(code: FutureQueue[RepairUpdate] => EitherT[Future, String, Unit])(
      implicit traceContext: TraceContext
  ): EitherT[FutureUnlessShutdown, String, Unit] =
    if (connectedSynchronizersLookup.isConnectedToAny) {
      EitherT.leftT[FutureUnlessShutdown, Unit](
        "There are still synchronizers connected. Please disconnect all synchronizers."
      )
    } else {
      ledgerApiIndexer.value.withRepairIndexer(code)
    }
}

object RepairService {

  private final case class ContractToAdd(
      contract: SerializableContract,
      reassignmentCounter: ReassignmentCounter,
      reassigningFrom: Option[Source[SynchronizerId]],
  ) {
    def cid: LfContractId = contract.contractId

    def driverMetadata(contractIdVersion: CantonContractIdVersion): Bytes =
      DriverContractMetadata(contract.contractSalt).toLfBytes(contractIdVersion)

  }
}
