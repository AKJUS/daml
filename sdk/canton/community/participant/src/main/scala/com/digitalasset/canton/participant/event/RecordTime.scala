// Copyright (c) 2025 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.canton.participant.event

import com.digitalasset.canton.RepairCounter
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.ledger.participant.state.SynchronizerIndex
import com.digitalasset.canton.logging.pretty.{Pretty, PrettyPrinting}
import com.digitalasset.canton.participant.util.TimeOfChange
import com.digitalasset.canton.topology.processing.EffectiveTime

/** Canton-internal record time
  * @param timestamp
  *   ACS change timestamp
  * @param tieBreaker
  *   ordering tie-breaker for changes that have the same timestamp (currently, happens only with
  *   repair requests)
  *
  * Value of the `tieBreaker`:
  *   - Requests (regular as well as repair requests) use the repair counter as `tieBreaker`.
  *   - Empty ACS changes (ticks, received ACS commitments, time proofs) use `Long.MinValue`
  */
final case class RecordTime(timestamp: CantonTimestamp, tieBreaker: Long) extends PrettyPrinting {
  override lazy val pretty: Pretty[RecordTime] = prettyOfClass(
    param("timestamp", _.timestamp),
    param("tieBreaker", _.tieBreaker),
  )

  /** Note that there is no guarantee that this will result in a time of change with an existing
    * repair counter.
    */
  def toTimeOfChange: TimeOfChange =
    TimeOfChange.negativeCounterToNone(timestamp, RepairCounter(tieBreaker))
}

object RecordTime {
  val lowestTiebreaker: Long = Long.MinValue

  val MinValue: RecordTime = RecordTime(CantonTimestamp.MinValue, lowestTiebreaker)

  implicit val recordTimeOrdering: Ordering[RecordTime] =
    Ordering.by(rt => (rt.timestamp -> rt.tieBreaker))

  def fromTimeOfChange(toc: TimeOfChange): RecordTime =
    TimeOfChange.withMinAsNoneRepairCounter(toc) { case (ts, rc) =>
      RecordTime(ts, rc.unwrap)
    }

  def fromSynchronizerIndex(synchronizerIndex: SynchronizerIndex): RecordTime =
    fromTimeOfChange(TimeOfChange.fromSynchronizerIndex(synchronizerIndex))

  def apply(timestamp: EffectiveTime, tieBreaker: Long): RecordTime =
    RecordTime(timestamp.value, tieBreaker)
}
