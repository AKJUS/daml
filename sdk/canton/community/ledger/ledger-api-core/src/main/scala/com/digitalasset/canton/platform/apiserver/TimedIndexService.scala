// Copyright (c) 2025 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.canton.platform.apiserver

import com.daml.ledger.api.v2.command_completion_service.CompletionStreamResponse
import com.daml.ledger.api.v2.event_query_service.GetEventsByContractIdResponse
import com.daml.ledger.api.v2.state_service.GetActiveContractsResponse
import com.daml.ledger.api.v2.update_service.{
  GetTransactionResponse,
  GetTransactionTreeResponse,
  GetUpdateResponse,
  GetUpdateTreesResponse,
  GetUpdatesResponse,
}
import com.daml.metrics.Timed
import com.digitalasset.canton.data.Offset
import com.digitalasset.canton.ledger.api.health.HealthStatus
import com.digitalasset.canton.ledger.api.{EventFormat, TransactionFormat, UpdateFormat, UpdateId}
import com.digitalasset.canton.ledger.participant.state.index.*
import com.digitalasset.canton.logging.LoggingContextWithTrace
import com.digitalasset.canton.metrics.LedgerApiServerMetrics
import com.digitalasset.canton.platform.*
import com.digitalasset.canton.platform.store.backend.common.UpdatePointwiseQueries.LookupKey
import com.digitalasset.daml.lf.data.Ref
import com.digitalasset.daml.lf.data.Ref.Party
import com.digitalasset.daml.lf.transaction.GlobalKey
import com.digitalasset.daml.lf.value.Value
import com.digitalasset.daml.lf.value.Value.ContractId
import org.apache.pekko.NotUsed
import org.apache.pekko.stream.scaladsl.Source

import scala.annotation.nowarn
import scala.concurrent.Future

// TODO(#23504) remove deprecation warning suppression
@nowarn("cat=deprecation")
final class TimedIndexService(delegate: IndexService, metrics: LedgerApiServerMetrics)
    extends IndexService {

  override def currentLedgerEnd(): Future[Option[Offset]] =
    Timed.future(metrics.services.index.currentLedgerEnd, delegate.currentLedgerEnd())

  override def getCompletions(
      begin: Option[Offset],
      userId: Ref.UserId,
      parties: Set[Ref.Party],
  )(implicit loggingContext: LoggingContextWithTrace): Source[CompletionStreamResponse, NotUsed] =
    Timed.source(
      metrics.services.index.getCompletions,
      delegate.getCompletions(begin, userId, parties),
    )

  override def updates(
      begin: Option[Offset],
      endAt: Option[Offset],
      updateFormat: UpdateFormat,
  )(implicit loggingContext: LoggingContextWithTrace): Source[GetUpdatesResponse, NotUsed] =
    Timed.source(
      metrics.services.index.transactions,
      delegate.updates(begin, endAt, updateFormat),
    )

  override def transactionTrees(
      begin: Option[Offset],
      endAt: Option[Offset],
      eventFormat: EventFormat,
  )(implicit loggingContext: LoggingContextWithTrace): Source[GetUpdateTreesResponse, NotUsed] =
    Timed.source(
      metrics.services.index.transactionTrees,
      delegate.transactionTrees(begin, endAt, eventFormat),
    )

  override def getTransactionById(
      updateId: UpdateId,
      transactionFormat: TransactionFormat,
  )(implicit loggingContext: LoggingContextWithTrace): Future[Option[GetTransactionResponse]] =
    Timed.future(
      metrics.services.index.getTransactionById,
      delegate.getTransactionById(updateId, transactionFormat),
    )

  override def getTransactionTreeById(
      updateId: UpdateId,
      requestingParties: Set[Ref.Party],
  )(implicit loggingContext: LoggingContextWithTrace): Future[Option[GetTransactionTreeResponse]] =
    Timed.future(
      metrics.services.index.getTransactionTreeById,
      delegate.getTransactionTreeById(updateId, requestingParties),
    )

  def getTransactionByOffset(
      offset: Offset,
      transactionFormat: TransactionFormat,
  )(implicit loggingContext: LoggingContextWithTrace): Future[Option[GetTransactionResponse]] =
    Timed.future(
      metrics.services.index.getTransactionByOffset,
      delegate.getTransactionByOffset(offset, transactionFormat),
    )

  def getTransactionTreeByOffset(
      offset: Offset,
      requestingParties: Set[Ref.Party],
  )(implicit loggingContext: LoggingContextWithTrace): Future[Option[GetTransactionTreeResponse]] =
    Timed.future(
      metrics.services.index.getTransactionTreeByOffset,
      delegate.getTransactionTreeByOffset(offset, requestingParties),
    )

  def getUpdateBy(
      lookupKey: LookupKey,
      updateFormat: UpdateFormat,
  )(implicit loggingContext: LoggingContextWithTrace): Future[Option[GetUpdateResponse]] =
    Timed.future(
      metrics.services.index.getUpdateByOffset,
      delegate.getUpdateBy(lookupKey, updateFormat),
    )

  override def getActiveContracts(
      filter: EventFormat,
      activeAt: Option[Offset],
  )(implicit loggingContext: LoggingContextWithTrace): Source[GetActiveContractsResponse, NotUsed] =
    Timed.source(
      metrics.services.index.getActiveContracts,
      delegate.getActiveContracts(filter, activeAt),
    )

  override def lookupActiveContract(
      readers: Set[Ref.Party],
      contractId: Value.ContractId,
  )(implicit
      loggingContext: LoggingContextWithTrace
  ): Future[Option[FatContract]] =
    Timed.future(
      metrics.services.index.lookupActiveContract,
      delegate.lookupActiveContract(readers, contractId),
    )

  override def lookupContractKey(
      readers: Set[Ref.Party],
      key: GlobalKey,
  )(implicit loggingContext: LoggingContextWithTrace): Future[Option[Value.ContractId]] =
    Timed.future(
      metrics.services.index.lookupContractKey,
      delegate.lookupContractKey(readers, key),
    )

  override def lookupMaximumLedgerTimeAfterInterpretation(
      ids: Set[Value.ContractId]
  )(implicit loggingContext: LoggingContextWithTrace): Future[MaximumLedgerTime] =
    Timed.future(
      metrics.services.index.lookupMaximumLedgerTime,
      delegate.lookupMaximumLedgerTimeAfterInterpretation(ids),
    )

  override def getParticipantId(): Future[Ref.ParticipantId] =
    Timed.future(metrics.services.index.getParticipantId, delegate.getParticipantId())

  override def getParties(parties: Seq[Ref.Party])(implicit
      loggingContext: LoggingContextWithTrace
  ): Future[List[IndexerPartyDetails]] =
    Timed.future(metrics.services.index.getParties, delegate.getParties(parties))

  override def listKnownParties(
      fromExcl: Option[Party],
      maxResults: Int,
  )(implicit
      loggingContext: LoggingContextWithTrace
  ): Future[List[IndexerPartyDetails]] =
    Timed.future(
      metrics.services.index.listKnownParties,
      delegate.listKnownParties(fromExcl, maxResults),
    )

  override def prune(
      pruneUpToInclusive: Offset,
      pruneAllDivulgedContracts: Boolean,
      incompletReassignmentOffsets: Vector[Offset],
  )(implicit loggingContext: LoggingContextWithTrace): Future[Unit] =
    Timed.future(
      metrics.services.index.prune,
      delegate.prune(pruneUpToInclusive, pruneAllDivulgedContracts, incompletReassignmentOffsets),
    )

  override def currentHealth(): HealthStatus =
    delegate.currentHealth()

  override def lookupContractState(contractId: Value.ContractId)(implicit
      loggingContext: LoggingContextWithTrace
  ): Future[ContractState] =
    Timed.future(
      metrics.services.index.lookupContractState,
      delegate.lookupContractState(contractId),
    )

  override def latestPrunedOffsets()(implicit
      loggingContext: LoggingContextWithTrace
  ): Future[(Option[Offset], Option[Offset])] =
    Timed.future(metrics.services.index.latestPrunedOffsets, delegate.latestPrunedOffsets())

  override def getEventsByContractId(
      contractId: ContractId,
      eventFormat: EventFormat,
  )(implicit loggingContext: LoggingContextWithTrace): Future[GetEventsByContractIdResponse] =
    Timed.future(
      metrics.services.index.getEventsByContractId,
      delegate.getEventsByContractId(contractId, eventFormat),
    )

  // TODO(i16065): Re-enable getEventsByContractKey tests
//  override def getEventsByContractKey(
//      contractKey: Value,
//      templateId: Ref.Identifier,
//      requestingParties: Set[Ref.Party],
//      endExclusiveSeqId: Option[Long],
//  )(implicit loggingContext: LoggingContextWithTrace): Future[GetEventsByContractKeyResponse] =
//    Timed.future(
//      metrics.services.index.getEventsByContractKey,
//      delegate.getEventsByContractKey(
//        contractKey,
//        templateId,
//        requestingParties,
//        endExclusiveSeqId,
//      ),
//    )
}
