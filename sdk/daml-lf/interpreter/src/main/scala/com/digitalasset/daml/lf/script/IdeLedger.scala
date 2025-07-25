// Copyright (c) 2025 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.daml.lf
package script

import com.digitalasset.daml.lf.data.Ref._
import com.digitalasset.daml.lf.data.{Bytes, Time}
import com.digitalasset.daml.lf.ledger._
import com.digitalasset.daml.lf.transaction.{
  BlindingInfo,
  CommittedTransaction,
  CreationTime,
  FatContractInstance,
  GlobalKey,
  Node,
  NodeId,
  Transaction => Tx,
}
import com.digitalasset.daml.lf.value.Value
import Value._
import com.daml.scalautil.Statement.discard

import scala.collection.immutable

/** An in-memory representation of a ledger for scripts */
object IdeLedger {

  final case class TransactionId(index: Int) extends Ordered[TransactionId] {
    def next: TransactionId = TransactionId(index + 1)
    // The resulting LedgerString is at most 11 chars long
    val id: LedgerString = LedgerString.fromLong(index.toLong)
    override def compare(that: TransactionId): Int = index compare that.index
  }

  /** Errors */
  final case class LedgerException(err: Error)
      extends RuntimeException(err.toString, null, true, false)

  sealed abstract class Error extends Product with Serializable
  final case class ErrorLedgerCrash(reason: String) extends Error

  def crash(reason: String) =
    throwLedgerError(ErrorLedgerCrash(reason))

  def throwLedgerError(err: Error) =
    throw LedgerException(err)

  /** A transaction as it is committed to the ledger.
    *
    * NOTE (SM): This should correspond quite closely to a core
    * transaction. I'm purposely calling it differently to facilitate
    * the discussion when comparing this code to legacy-code for
    * building core transactions.
    *
    * @param committer   The committer
    * @param effectiveAt The time at which this transaction is effective.
    * @param roots       The root nodes of the resulting transaction.
    * @param nodes       All nodes that are part of this transaction.
    * @param disclosures Transaction nodes that must be disclosed to
    *                    individual parties to make this transaction
    *                    valid.
    *
    *                    NOTE (SM): I'm explicitly using the term
    *                    'disclosure' here, as it is more neutral than
    *                    divulgence. I think we can also adapt our
    *                    vocabulary such that we call the disclosures
    *                    happening due to post-commit validation
    *                    'implicit disclosures'.
    */
  final case class RichTransaction(
      actAs: Set[Party],
      readAs: Set[Party],
      effectiveAt: Time.Timestamp,
      transactionOffset: Long,
      transaction: CommittedTransaction,
      blindingInfo: BlindingInfo,
  )

  object RichTransaction {

    /** Translate an EnrichedTransaction to a RichTransaction. EnrichedTransaction's contain local
      * node id's and contain additional information in the most detailed form suitable for different
      * consumers. The RichTransaction is the transaction that we serialize in the sandbox to compare
      * different ledgers. All relative and absolute node id's are translated to absolute node id's of
      * the package format.
      */
    private[lf] def apply(
        actAs: Set[Party],
        readAs: Set[Party],
        effectiveAt: Time.Timestamp,
        transactionOffset: Long,
        transaction: CommittedTransaction,
    ): RichTransaction = {
      val blindingInfo =
        BlindingTransaction.calculateBlindingInfo(transaction)
      new RichTransaction(
        actAs = actAs,
        readAs = readAs,
        effectiveAt = effectiveAt,
        transactionOffset = transactionOffset,
        transaction = transaction,
        blindingInfo = blindingInfo,
      )
    }

  }

  /** Script step representing the actions executed in a script. */
  sealed abstract class ScriptStep extends Product with Serializable

  final case class Commit(
      txId: TransactionId,
      richTransaction: RichTransaction,
      optLocation: Option[Location],
  ) extends ScriptStep

  final case class PassTime(dtMicros: Long) extends ScriptStep

  final case class AssertMustFail(
      actAs: Set[Party],
      readAs: Set[Party],
      optLocation: Option[Location],
      time: Time.Timestamp,
      txid: TransactionId,
  ) extends ScriptStep

  final case class SubmissionFailed(
      actAs: Set[Party],
      readAs: Set[Party],
      optLocation: Option[Location],
      time: Time.Timestamp,
      txid: TransactionId,
  ) extends ScriptStep

  final case class Disclosure(
      since: TransactionId,
      explicit: Boolean,
  )

  // ----------------------------------------------------------------
  // Node information
  // ----------------------------------------------------------------

  /** Node information that we cache to support the efficient
    * consumption of the data stored in the ledger.
    *
    * @param node           The node itself. Repeated here to avoid having to
    *                       look it up
    * @param transaction    The transaction that inserted this node.
    * @param effectiveAt    The time at which this node is effective.
    *
    *                       NOTE (SM): we denormalize this for speed, as
    *                       otherwise we'd have to lookup that
    *                       information on the transaction every time we
    *                       need to check for whether a contract is
    *                       active.
    * @param observingSince A mapping from parties that can see this
    *                       node to the transaction in which the node
    *                       became first visible.
    * @param referencedBy   All nodes referencing this node, which are
    *                       either 'NodeExercises' or 'NodeEnsureActive'
    *                       nodes.
    * @param consumedBy     The node consuming this node, provided such a
    *                       node exists. Consumption under a rollback
    *                       is not included here even for contracts created
    *                       under a rollback node.
    */
  final case class LedgerNodeInfo(
      node: Node,
      optLocation: Option[Location],
      transaction: TransactionId,
      effectiveAt: Time.Timestamp,
      disclosures: Map[Party, Disclosure],
      referencedBy: Set[EventId],
      consumedBy: Option[EventId],
  ) {

    def addDisclosures(newDisclosures: Map[Party, Disclosure]): LedgerNodeInfo = {
      // NOTE(MH): Earlier disclosures take precedence (`++` is right biased).
      copy(disclosures = newDisclosures ++ disclosures)
    }

    def toFatContractInstance: Option[FatContractInstance] =
      node match {
        case create: Node.Create =>
          Some(
            FatContractInstance.fromCreateNode(
              create,
              CreationTime.CreatedAt(effectiveAt),
              Bytes.Empty,
            )
          )
        case _ =>
          None
      }
  }

  type LedgerNodeInfos = Map[EventId, LedgerNodeInfo]

  /*
   * Result from lookupGlobalContract. We provide detailed information why a lookup
   * could fail in order to construct good error messages.
   */
  sealed abstract class LookupResult extends Product with Serializable

  final case class LookupOk(
      coinst: FatContractInstance
  ) extends LookupResult
  final case class LookupContractNotFound(coid: ContractId) extends LookupResult

  final case class LookupContractNotEffective(
      coid: ContractId,
      templateId: Identifier,
      effectiveAt: Time.Timestamp,
  ) extends LookupResult
  final case class LookupContractNotActive(
      coid: ContractId,
      templateId: Identifier,
      consumedBy: Option[EventId],
  ) extends LookupResult
  final case class LookupContractNotVisible(
      coid: ContractId,
      templateId: Identifier,
      observers: Set[Party],
      stakeholders: Set[Party],
  ) extends LookupResult

  sealed abstract class CommitError extends Product with Serializable
  object CommitError {
    final case class UniqueKeyViolation(
        error: IdeLedger.UniqueKeyViolation
    ) extends CommitError
  }

  /** Updates the ledger to reflect that `committer` committed the
    * transaction `tr` resulting from running the
    * update-expression at time `effectiveAt`.
    */
  def commitTransaction(
      actAs: Set[Party],
      readAs: Set[Party],
      effectiveAt: Time.Timestamp,
      optLocation: Option[Location],
      tx: CommittedTransaction,
      locationInfo: Map[NodeId, Location],
      l: IdeLedger,
  ): Either[CommitError, CommitResult] = {
    // transactionId is small enough (< 20 chars), so we do no exceed the 255
    // chars limit when concatenate in EventId#toLedgerString method.
    val transactionOffset = l.scriptStepId.index.toLong
    val richTr = RichTransaction(actAs, readAs, effectiveAt, transactionOffset, tx)
    processTransaction(l.scriptStepId, richTr, locationInfo, l.ledgerData) match {
      case Left(err) => Left(CommitError.UniqueKeyViolation(err))
      case Right(updatedCache) =>
        Right(
          CommitResult(
            l.copy(
              scriptSteps = l.scriptSteps + (l.scriptStepId.index -> Commit(
                l.scriptStepId,
                richTr,
                optLocation,
              )),
              scriptStepId = l.scriptStepId.next,
              ledgerData = updatedCache,
            ),
            l.scriptStepId,
            richTr,
          )
        )
    }
  }

  /** The initial ledger */
  def initialLedger(t0: Time.Timestamp): IdeLedger =
    IdeLedger(
      currentTime = t0,
      scriptStepId = TransactionId(0),
      scriptSteps = immutable.IntMap.empty,
      ledgerData = LedgerData.empty,
    )

  /** Result of committing a transaction is the new ledger,
    * and the enriched transaction.
    */
  final case class CommitResult(
      newLedger: IdeLedger,
      transactionId: TransactionId,
      richTransaction: RichTransaction,
  )

  // ----------------------------------------------------------------------------
  // Enriching transactions with disclosure information
  // ----------------------------------------------------------------------------

  def collectCoids(value: VersionedValue): Set[ContractId] =
    collectCoids(value.unversioned)

  /** Collect all contract ids appearing in a value
    */
  def collectCoids(value: Value): Set[ContractId] = {
    val coids = Set.newBuilder[ContractId]
    def collect(v: Value): Unit =
      v match {
        case ValueRecord(tycon @ _, fs) =>
          fs.foreach { case (_, v) =>
            collect(v)
          }
        case ValueVariant(_, _, arg) => collect(arg)
        case _: ValueEnum => ()
        case ValueList(vs) =>
          vs.foreach(collect)
        case ValueContractId(coid) =>
          discard(coids += coid)
        case _: ValueCidlessLeaf => ()
        case ValueOptional(mbV) => mbV.foreach(collect)
        case ValueTextMap(map) => map.values.foreach(collect)
        case ValueGenMap(entries) =>
          entries.foreach { case (k, v) =>
            collect(k)
            collect(v)
          }
      }

    collect(value)
    coids.result()
  }

  // ----------------------------------------------------------------
  // Cache for active contracts and nodes
  // ----------------------------------------------------------------

  object LedgerData {
    lazy val empty = LedgerData(Set.empty, Map.empty, Map.empty, Map.empty)
  }

  /** @param activeContracts The contracts that are active in the
    *                        current state of the ledger.
    * @param nodeInfos       Node information used to efficiently navigate
    *                        the transaction graph
    */
  final case class LedgerData(
      activeContracts: Set[ContractId],
      nodeInfos: LedgerNodeInfos,
      activeKeys: Map[GlobalKey, ContractId],
      coidToNodeId: Map[ContractId, EventId],
  ) {
    def updateLedgerNodeInfo(
        coid: ContractId
    )(f: (LedgerNodeInfo) => LedgerNodeInfo): LedgerData =
      coidToNodeId.get(coid).map(updateLedgerNodeInfo(_)(f)).getOrElse(this)

    def updateLedgerNodeInfo(
        nodeId: EventId
    )(f: (LedgerNodeInfo) => LedgerNodeInfo): LedgerData =
      copy(
        nodeInfos = nodeInfos
          .get(nodeId)
          .map(ni => nodeInfos.updated(nodeId, f(ni)))
          .getOrElse(nodeInfos)
      )

    def createdIn(coid: ContractId, nodeId: EventId): LedgerData =
      copy(coidToNodeId = coidToNodeId + (coid -> nodeId))

  }

  final case class UniqueKeyViolation(gk: GlobalKey)

  /** Functions for updating the ledger with new transactional information.
    *
    * @param trId transaction identity
    * @param richTr (enriched) transaction
    * @param locationInfo location map
    */
  class TransactionProcessor(
      trId: TransactionId,
      richTr: RichTransaction,
      locationInfo: Map[NodeId, Location],
  ) {

    def duplicateKeyCheck(ledgerData: LedgerData): Either[UniqueKeyViolation, Unit] = {
      val inactiveKeys = richTr.transaction.contractKeyInputs
        .fold(error => crash(s"$error: inconsistent transaction"), identity)
        .collect { case (key, _: Tx.KeyInactive) =>
          key
        }

      inactiveKeys.find(ledgerData.activeKeys.contains(_)) match {
        case Some(duplicateKey) =>
          Left(UniqueKeyViolation(duplicateKey))

        case None =>
          Right(())
      }
    }

    def addNewLedgerNodes(historicalLedgerData: LedgerData): LedgerData =
      richTr.transaction.transaction.fold[LedgerData](historicalLedgerData) {
        case (ledgerData, (nodeId, node)) =>
          val eventId = EventId(trId.index.toLong, nodeId)
          val newLedgerNodeInfo = LedgerNodeInfo(
            node = node,
            optLocation = locationInfo.get(nodeId),
            transaction = trId,
            effectiveAt = richTr.effectiveAt,
            // Following fields will be updated by additional calls to node processing code
            disclosures = Map.empty,
            referencedBy = Set.empty,
            consumedBy = None,
          )

          ledgerData.copy(nodeInfos = ledgerData.nodeInfos + (eventId -> newLedgerNodeInfo))
      }

    def createdInAndReferenceByUpdates(historicalLedgerData: LedgerData): LedgerData =
      richTr.transaction.transaction.fold[LedgerData](historicalLedgerData) {
        case (ledgerData, (nodeId, createNode: Node.Create)) =>
          ledgerData.createdIn(createNode.coid, EventId(trId.index.toLong, nodeId))

        case (ledgerData, (nodeId, exerciseNode: Node.Exercise)) =>
          ledgerData.updateLedgerNodeInfo(exerciseNode.targetCoid)(ledgerNodeInfo =>
            ledgerNodeInfo.copy(referencedBy =
              ledgerNodeInfo.referencedBy + EventId(trId.index.toLong, nodeId)
            )
          )

        case (ledgerData, (nodeId, fetchNode: Node.Fetch)) =>
          ledgerData.updateLedgerNodeInfo(fetchNode.coid)(ledgerNodeInfo =>
            ledgerNodeInfo.copy(referencedBy =
              ledgerNodeInfo.referencedBy + EventId(trId.index.toLong, nodeId)
            )
          )

        case (ledgerData, (nodeId, lookupNode: Node.LookupByKey)) =>
          lookupNode.result match {
            case None =>
              ledgerData

            case Some(referencedCoid) =>
              ledgerData.updateLedgerNodeInfo(referencedCoid)(ledgerNodeInfo =>
                ledgerNodeInfo.copy(referencedBy =
                  ledgerNodeInfo.referencedBy + EventId(trId.index.toLong, nodeId)
                )
              )
          }

        case (ledgerData, (_, _: Node)) =>
          ledgerData
      }

    def consumedByUpdates(ledgerData: LedgerData): LedgerData = {
      var ledgerDataResult = ledgerData

      for ((contractId, nodeId) <- richTr.transaction.transaction.consumedBy) {
        ledgerDataResult = ledgerDataResult.updateLedgerNodeInfo(contractId) { ledgerNodeInfo =>
          ledgerNodeInfo.copy(consumedBy = Some(EventId(trId.index.toLong, nodeId)))
        }
      }

      ledgerDataResult
    }

    def activeContractAndKeyUpdates(ledgerData: LedgerData): LedgerData = {
      ledgerData.copy(
        activeContracts =
          ledgerData.activeContracts ++ richTr.transaction.localContracts.keySet -- richTr.transaction.inactiveContracts,
        activeKeys = richTr.transaction.updatedContractKeys.foldLeft(ledgerData.activeKeys) {
          case (activeKeys, (key, Some(cid))) =>
            activeKeys + (key -> cid)

          case (activeKeys, (key, None)) =>
            activeKeys - key
        },
      )
    }

    def disclosureUpdates(ledgerData: LedgerData): LedgerData = {
      // NOTE(MH): Since `addDisclosures` is biased towards existing
      // disclosures, we need to add the "stronger" explicit ones first.
      richTr.blindingInfo.disclosure.foldLeft(ledgerData) { case (cacheP, (nodeId, witnesses)) =>
        cacheP.updateLedgerNodeInfo(EventId(richTr.transactionOffset, nodeId))(
          _.addDisclosures(witnesses.map(_ -> Disclosure(since = trId, explicit = true)).toMap)
        )
      }
    }

    def divulgenceUpdates(ledgerData: LedgerData): LedgerData = {
      richTr.blindingInfo.divulgence.foldLeft(ledgerData) { case (cacheP, (coid, divulgees)) =>
        cacheP.updateLedgerNodeInfo(ledgerData.coidToNodeId(coid))(
          _.addDisclosures(divulgees.map(_ -> Disclosure(since = trId, explicit = false)).toMap)
        )
      }
    }
  }

  /** Update the ledger (which records information on all historical transactions) with new transaction information.
    *
    * @param trId transaction identity
    * @param richTr (enriched) transaction
    * @param locationInfo location map
    * @param ledgerData ledger recording all historical transaction that have been processed
    * @return updated ledger with new transaction information
    */
  private[this] def processTransaction(
      trId: TransactionId,
      richTr: RichTransaction,
      locationInfo: Map[NodeId, Location],
      ledgerData: LedgerData,
  ): Either[UniqueKeyViolation, LedgerData] = {

    val processor: TransactionProcessor = new TransactionProcessor(trId, richTr, locationInfo)

    for {
      _ <- processor.duplicateKeyCheck(ledgerData)
    } yield {
      // Update ledger data with new transaction node information *before* performing any other updates
      var cachedLedgerData: LedgerData = processor.addNewLedgerNodes(ledgerData)

      // Update ledger data with any new created in and referenced by information
      cachedLedgerData = processor.createdInAndReferenceByUpdates(cachedLedgerData)
      // Update ledger data with any new consumed by information
      cachedLedgerData = processor.consumedByUpdates(cachedLedgerData)
      // Update ledger data with any new active contract information
      cachedLedgerData = processor.activeContractAndKeyUpdates(cachedLedgerData)
      // Update ledger data with any new disclosure information
      cachedLedgerData = processor.disclosureUpdates(cachedLedgerData)
      // Update ledger data with any new divulgence information
      cachedLedgerData = processor.divulgenceUpdates(cachedLedgerData)

      cachedLedgerData
    }
  }
}

// ----------------------------------------------------------------
// The ledger
// ----------------------------------------------------------------

/** @param currentTime        The current time of the ledger.
  *
  *                           NOTE (SM): transactions can be added with any
  *                           ledger-effective time, as the code for
  *                           checking whether a contract instance is
  *                           active always nexplicitly checks that the
  *                           ledger-effective time ordering is maintained.
  *
  * @param scriptStepId The identitity for the next
  *                           transaction to be inserted. These
  *                           identities are allocated consecutively
  *                           from 1 to 'maxBound :: Int'.
  * @param scriptSteps      Script steps that were executed.
  * @param ledgerData              Cache for the ledger.
  */
final case class IdeLedger(
    currentTime: Time.Timestamp,
    scriptStepId: IdeLedger.TransactionId,
    scriptSteps: immutable.IntMap[IdeLedger.ScriptStep],
    ledgerData: IdeLedger.LedgerData,
) {

  import IdeLedger._

  /** moves the current time of the ledger by the relative time `dt`. */
  def passTime(dtMicros: Long): IdeLedger = copy(
    currentTime = currentTime.addMicros(dtMicros),
    scriptSteps = scriptSteps + (scriptStepId.index -> PassTime(dtMicros)),
    scriptStepId = scriptStepId.next,
  )

  def insertAssertMustFail(
      actAs: Set[Party],
      readAs: Set[Party],
      optLocation: Option[Location],
  ): IdeLedger = {
    val id = scriptStepId
    val effAt = currentTime
    val newIMS = scriptSteps + (id.index -> AssertMustFail(actAs, readAs, optLocation, effAt, id))
    copy(
      scriptSteps = newIMS,
      scriptStepId = scriptStepId.next,
    )
  }

  def insertSubmissionFailed(
      actAs: Set[Party],
      readAs: Set[Party],
      optLocation: Option[Location],
  ): IdeLedger = {
    val id = scriptStepId
    val effAt = currentTime
    val newIMS =
      scriptSteps + (id.index -> SubmissionFailed(actAs, readAs, optLocation, effAt, id))
    copy(
      scriptSteps = newIMS,
      scriptStepId = scriptStepId.next,
    )
  }

  def query(
      actAs: Set[Party],
      readAs: Set[Party],
      effectiveAt: Time.Timestamp,
  ): Seq[LookupOk] = {
    ledgerData.activeContracts.toList
      .map(cid => lookupGlobalContract(actAs, readAs, effectiveAt, cid))
      .collect { case l @ LookupOk(_) => l }
  }

  /** Focusing on a specific view of the ledger, lookup the
    * contract-instance associated to a specific contract-id.
    */
  def lookupGlobalContract(
      actAs: Set[Party],
      readAs: Set[Party],
      effectiveAt: Time.Timestamp,
      coid: ContractId,
  ): LookupResult = {
    ledgerData.coidToNodeId.get(coid).flatMap(ledgerData.nodeInfos.get) match {
      case None => LookupContractNotFound(coid)
      case Some(info) =>
        info.toFatContractInstance match {
          case Some(contract) =>
            // The upcast to CreationTime works around https://github.com/scala/bug/issues/9837
            val isEffective = (contract.createdAt: CreationTime) match {
              case CreationTime.Now => true
              case CreationTime.CreatedAt(createdAt) => createdAt <= effectiveAt
            }
            if (!isEffective)
              LookupContractNotEffective(coid, contract.templateId, info.effectiveAt)
            else if (!ledgerData.activeContracts.contains(coid))
              LookupContractNotActive(
                coid,
                contract.templateId,
                info.consumedBy,
              )
            else if (((actAs union readAs) intersect contract.stakeholders).isEmpty)
              LookupContractNotVisible(
                coid,
                contract.templateId,
                info.disclosures.keys.toSet,
                contract.stakeholders,
              )
            else
              LookupOk(contract)

          case None =>
            LookupContractNotFound(coid)
        }
    }
  }

  // Given a ledger and the node index of a node in a partial transaction
  // turn it into a event id that can be used in script error messages.
  def ptxEventId(nodeIdx: NodeId): EventId =
    EventId(scriptStepId.index.toLong, nodeIdx)
}
