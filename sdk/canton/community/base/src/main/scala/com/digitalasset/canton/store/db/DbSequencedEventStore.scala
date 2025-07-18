// Copyright (c) 2025 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.canton.store.db

import cats.data.EitherT
import cats.syntax.either.*
import cats.syntax.functor.*
import com.daml.nameof.NameOf.functionFullName
import com.daml.nonempty.NonEmpty
import com.digitalasset.canton.SequencerCounter
import com.digitalasset.canton.config.CantonRequireTypes.String3
import com.digitalasset.canton.config.ProcessingTimeout
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.lifecycle.{CloseContext, FutureUnlessShutdown}
import com.digitalasset.canton.logging.*
import com.digitalasset.canton.resource.{DbStorage, DbStore}
import com.digitalasset.canton.sequencing.protocol.{SequencedEvent, SignedContent}
import com.digitalasset.canton.sequencing.{OrdinarySerializedEvent, PossiblyIgnoredSerializedEvent}
import com.digitalasset.canton.store.*
import com.digitalasset.canton.store.SequencedEventStore.CounterAndTimestamp
import com.digitalasset.canton.store.db.DbSequencedEventStore.*
import com.digitalasset.canton.tracing.{SerializableTraceContext, TraceContext}
import com.digitalasset.canton.util.EitherTUtil
import slick.jdbc.{GetResult, SetParameter}

import scala.concurrent.ExecutionContext

class DbSequencedEventStore(
    override protected val storage: DbStorage,
    physicalSynchronizerIdx: IndexedPhysicalSynchronizer,
    override protected val timeouts: ProcessingTimeout,
    override protected val loggerFactory: NamedLoggerFactory,
)(implicit val ec: ExecutionContext)
    extends SequencedEventStore
    with DbStore
    with DbPrunableByTime[IndexedPhysicalSynchronizer] {

  override protected[this] implicit def setParameterIndexedSynchronizer
      : SetParameter[IndexedPhysicalSynchronizer] = IndexedString.setParameterIndexedString
  override protected[this] def partitionColumn: String = "physical_synchronizer_idx"

  private val protocolVersion = physicalSynchronizerIdx.synchronizerId.protocolVersion
  override protected[this] val partitionKey: IndexedPhysicalSynchronizer = physicalSynchronizerIdx

  override protected[this] def pruning_status_table: String = "common_sequenced_event_store_pruning"

  import com.digitalasset.canton.store.SequencedEventStore.*
  import storage.api.*
  import storage.converters.*

  implicit val getResultPossiblyIgnoredSequencedEvent: GetResult[PossiblyIgnoredSerializedEvent] =
    GetResult { r =>
      val typ = r.<<[SequencedEventDbType]
      val sequencerCounter = r.<<[SequencerCounter]
      val timestamp = r.<<[CantonTimestamp]
      val eventBytes = r.<<[Array[Byte]]
      val traceContext: TraceContext = r.<<[SerializableTraceContext].unwrap
      val ignore = r.<<[Boolean]

      typ match {
        case SequencedEventDbType.IgnoredEvent =>
          IgnoredSequencedEvent(timestamp, sequencerCounter, None)(
            traceContext
          )
        case _ =>
          val signedEvent = SignedContent
            .fromTrustedByteArray(eventBytes)
            .flatMap(
              _.deserializeContent(SequencedEvent.fromByteString(protocolVersion, _))
            )
            .valueOr(err =>
              throw new DbDeserializationException(s"Failed to deserialize sequenced event: $err")
            )
          if (ignore) {
            IgnoredSequencedEvent(
              timestamp,
              sequencerCounter,
              Some(signedEvent),
            )(
              traceContext
            )
          } else {
            OrdinarySequencedEvent(sequencerCounter, signedEvent)(
              traceContext
            )
          }
      }
    }

  private implicit val traceContextSetParameter: SetParameter[SerializableTraceContext] =
    SerializableTraceContext.getVersionedSetParameter(protocolVersion)

  override protected def storeEventsInternal(
      eventsNE: NonEmpty[Seq[OrdinarySerializedEvent]]
  )(implicit
      traceContext: TraceContext,
      closeContext: CloseContext,
  ): FutureUnlessShutdown[Unit] =
    storage.queryAndUpdate(bulkInsertQuery(eventsNE), functionFullName)(traceContext, closeContext)

  private def bulkInsertQuery(
      events: Seq[PossiblyIgnoredSerializedEvent]
  )(implicit traceContext: TraceContext): DBIOAction[Unit, NoStream, Effect.All] = {
    val insertSql =
      "insert into common_sequenced_events (physical_synchronizer_idx, ts, sequenced_event, type, sequencer_counter, trace_context, ignore) " +
        "values (?, ?, ?, ?, ?, ?, ?) " +
        "on conflict do nothing"
    DbStorage.bulkOperation_(insertSql, events, storage.profile) { pp => event =>
      pp >> partitionKey
      pp >> event.timestamp
      pp >> event.underlyingEventBytes
      pp >> event.dbType
      pp >> event.counter
      pp >> SerializableTraceContext(event.traceContext)
      pp >> event.isIgnored
    }
  }

  override def find(criterion: SequencedEventStore.SearchCriterion)(implicit
      traceContext: TraceContext
  ): EitherT[FutureUnlessShutdown, SequencedEventNotFoundError, PossiblyIgnoredSerializedEvent] = {
    val query = criterion match {
      case ByTimestamp(timestamp) =>
        // The implementation assumes that we timestamps on sequenced events increases monotonically with the sequencer counter
        // It therefore is fine to take the first event that we find.
        sql"""select type, sequencer_counter, ts, sequenced_event, trace_context, ignore from common_sequenced_events
                where physical_synchronizer_idx = $partitionKey and ts = $timestamp"""
      case LatestUpto(inclusive) =>
        sql"""select type, sequencer_counter, ts, sequenced_event, trace_context, ignore from common_sequenced_events
                where physical_synchronizer_idx = $partitionKey and ts <= $inclusive
                order by ts desc #${storage.limit(1)}"""
    }

    storage
      .querySingle(
        query.as[PossiblyIgnoredSerializedEvent].headOption,
        functionFullName,
      )
      .toRight(SequencedEventNotFoundError(criterion))
  }

  override def findRange(criterion: SequencedEventStore.RangeCriterion, limit: Option[Int])(implicit
      traceContext: TraceContext
  ): EitherT[FutureUnlessShutdown, SequencedEventRangeOverlapsWithPruning, Seq[
    PossiblyIgnoredSerializedEvent
  ]] =
    EitherT {
      criterion match {
        case ByTimestampRange(lowerInclusive, upperInclusive) =>
          for {
            events <- storage.query(
              sql"""select type, sequencer_counter, ts, sequenced_event, trace_context, ignore from common_sequenced_events
                    where physical_synchronizer_idx = $partitionKey and $lowerInclusive <= ts  and ts <= $upperInclusive
                    order by ts #${limit.fold("")(storage.limit(_))}"""
                .as[PossiblyIgnoredSerializedEvent],
              functionFullName,
            )
            // check for pruning after we've read the events so that we certainly catch the case
            // if pruning is started while we're reading (as we're not using snapshot isolation here)
            pruningO <- pruningStatus
          } yield pruningO match {
            case Some(pruningStatus) if pruningStatus.timestamp >= lowerInclusive =>
              Left(SequencedEventRangeOverlapsWithPruning(criterion, pruningStatus, events))
            case _ =>
              Right(events)
          }
      }
    }

  override def sequencedEvents(
      limit: Option[Int] = None
  )(implicit
      traceContext: TraceContext
  ): FutureUnlessShutdown[Seq[PossiblyIgnoredSerializedEvent]] =
    storage.query(
      sql"""select type, sequencer_counter, ts, sequenced_event, trace_context, ignore from common_sequenced_events
              where physical_synchronizer_idx = $partitionKey
              order by ts #${limit.fold("")(storage.limit(_))}"""
        .as[PossiblyIgnoredSerializedEvent],
      functionFullName,
    )

  override protected[canton] def doPrune(
      untilInclusive: CantonTimestamp,
      lastPruning: Option[CantonTimestamp],
  )(implicit traceContext: TraceContext): FutureUnlessShutdown[Int] = {
    val query =
      sqlu"delete from common_sequenced_events where physical_synchronizer_idx = $partitionKey and ts <= $untilInclusive"
    storage
      .queryAndUpdate(query, functionFullName)
      .map { nrPruned =>
        logger.info(
          s"Pruned at least $nrPruned entries from the sequenced event store of physical_synchronizer_idx $partitionKey older or equal to $untilInclusive"
        )
        nrPruned
      }
  }

  override protected def ignoreEventsInternal(
      fromInclusive: SequencerCounter,
      toInclusive: SequencerCounter,
  )(implicit
      traceContext: TraceContext
  ): EitherT[FutureUnlessShutdown, ChangeWouldResultInGap, Unit] =
    withLock(functionFullName) {
      for {
        _ <- appendEmptyIgnoredEvents(fromInclusive, toInclusive)
        _ <- EitherT.right(setIgnoreStatus(fromInclusive, toInclusive, ignore = true))
      } yield ()
    }

  private def appendEmptyIgnoredEvents(
      fromInclusive: SequencerCounter,
      untilInclusive: SequencerCounter,
  )(implicit
      traceContext: TraceContext
  ): EitherT[FutureUnlessShutdown, ChangeWouldResultInGap, Unit] =
    for {
      lastSequencerCounterAndTimestampO <- EitherT.right(
        storage.query(
          sql"""select sequencer_counter, ts from common_sequenced_events where physical_synchronizer_idx = $partitionKey
               order by sequencer_counter desc #${storage.limit(1)}"""
            .as[(SequencerCounter, CantonTimestamp)]
            .headOption,
          functionFullName,
        )
      )

      (firstSc, firstTs) = lastSequencerCounterAndTimestampO match {
        case Some((lastSc, lastTs)) => (lastSc + 1, lastTs.immediateSuccessor)
        case None =>
          // Starting with MinValue.immediateSuccessor, because elsewhere we assume that MinValue is a strict lower bound on event timestamps.
          (fromInclusive, CantonTimestamp.MinValue.immediateSuccessor)
      }

      _ <- EitherTUtil.condUnitET[FutureUnlessShutdown](
        fromInclusive <= firstSc || fromInclusive > untilInclusive,
        ChangeWouldResultInGap(firstSc, fromInclusive - 1),
      )

      events = ((firstSc max fromInclusive) to untilInclusive).map { sc =>
        val ts = firstTs.addMicros(sc - firstSc)
        IgnoredSequencedEvent(ts, sc, None)(traceContext)
      }

      _ <- EitherT.right(
        storage.queryAndUpdate(bulkInsertQuery(events), functionFullName)
      )
    } yield ()

  private def setIgnoreStatus(
      fromInclusive: SequencerCounter,
      toInclusive: SequencerCounter,
      ignore: Boolean,
  )(implicit
      traceContext: TraceContext
  ): FutureUnlessShutdown[Unit] =
    storage.update_(
      sqlu"update common_sequenced_events set ignore = $ignore where physical_synchronizer_idx = $partitionKey and $fromInclusive <= sequencer_counter and sequencer_counter <= $toInclusive",
      functionFullName,
    )

  override protected def unignoreEventsInternal(
      fromInclusive: SequencerCounter,
      toInclusive: SequencerCounter,
  )(implicit
      traceContext: TraceContext
  ): EitherT[FutureUnlessShutdown, ChangeWouldResultInGap, Unit] =
    withLock(functionFullName) {
      for {
        _ <- deleteEmptyIgnoredEvents(fromInclusive, toInclusive)
        _ <- EitherT.right(setIgnoreStatus(fromInclusive, toInclusive, ignore = false))
      } yield ()
    }

  private def deleteEmptyIgnoredEvents(from: SequencerCounter, to: SequencerCounter)(implicit
      traceContext: TraceContext
  ): EitherT[FutureUnlessShutdown, ChangeWouldResultInGap, Unit] =
    for {
      lastNonEmptyEventSequencerCounter <- EitherT.right(
        storage.query(
          sql"""select sequencer_counter from common_sequenced_events
              where physical_synchronizer_idx = $partitionKey and type != ${SequencedEventDbType.IgnoredEvent}
              order by sequencer_counter desc #${storage.limit(1)}"""
            .as[SequencerCounter]
            .headOption,
          functionFullName,
        )
      )

      fromEffective = lastNonEmptyEventSequencerCounter.fold(from)(c => (c + 1) max from)

      lastSequencerCounter <- EitherT.right(
        storage.query(
          sql"""select sequencer_counter from common_sequenced_events
              where physical_synchronizer_idx = $partitionKey
              order by sequencer_counter desc #${storage.limit(1)}"""
            .as[SequencerCounter]
            .headOption,
          functionFullName,
        )
      )

      _ <- EitherTUtil.condUnitET[FutureUnlessShutdown](
        lastSequencerCounter.forall(_ <= to) || fromEffective > to,
        ChangeWouldResultInGap(fromEffective, to),
      )

      _ <- EitherT.right(
        storage.update(
          sqlu"""delete from common_sequenced_events
               where physical_synchronizer_idx = $partitionKey and type = ${SequencedEventDbType.IgnoredEvent}
                 and $fromEffective <= sequencer_counter and sequencer_counter <= $to""",
          functionFullName,
        )
      )
    } yield ()

  override protected def deleteInternal(
      fromInclusive: SequencerCounter
  )(implicit traceContext: TraceContext): FutureUnlessShutdown[Unit] =
    storage.update_(
      sqlu"delete from common_sequenced_events where physical_synchronizer_idx = $partitionKey and sequencer_counter >= $fromInclusive",
      functionFullName,
    )

  override def traceContext(sequencedTimestamp: CantonTimestamp)(implicit
      traceContext: TraceContext
  ): FutureUnlessShutdown[Option[TraceContext]] = {
    val query =
      sql"""select trace_context
            from common_sequenced_events
             where physical_synchronizer_idx = $partitionKey
               and ts = $sequencedTimestamp"""
    storage
      .querySingle(
        query.as[SerializableTraceContext].headOption,
        functionFullName,
      )
      .map(_.unwrap)
      .value
  }

  override protected[this] def fetchLastCounterAndTimestamp(implicit
      traceContext: TraceContext
  ): FutureUnlessShutdown[Option[CounterAndTimestamp]] = {
    val query =
      sql"""select sequencer_counter, ts
            from common_sequenced_events
            where physical_synchronizer_idx = $partitionKey
            order by sequencer_counter desc
            limit 1"""
    storage
      .querySingle(
        query.as[CounterAndTimestamp].headOption,
        functionFullName,
      )
      .value
  }
}

object DbSequencedEventStore {
  sealed trait SequencedEventDbType {
    val name: String3
  }

  object SequencedEventDbType {

    case object Deliver extends SequencedEventDbType {
      override val name: String3 = String3.tryCreate("del")
    }

    case object DeliverError extends SequencedEventDbType {
      override val name: String3 = String3.tryCreate("err")
    }

    case object IgnoredEvent extends SequencedEventDbType {
      override val name: String3 = String3.tryCreate("ign")
    }

    implicit val setParameterSequencedEventType: SetParameter[SequencedEventDbType] = (v, pp) =>
      pp >> v.name

    implicit val getResultSequencedEventType: GetResult[SequencedEventDbType] = GetResult(r =>
      r.nextString() match {
        case Deliver.name.str => Deliver
        case DeliverError.name.str => DeliverError
        case IgnoredEvent.name.str => IgnoredEvent
        case unknown =>
          throw new DbDeserializationException(s"Unknown sequenced event type [$unknown]")
      }
    )
  }

  implicit val getResultCounterAndTimestamp: GetResult[CounterAndTimestamp] =
    GetResult(r => CounterAndTimestamp(r.<<, r.<<))
}
