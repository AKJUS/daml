// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.canton.domain.sequencing.sequencer.block.bftordering.core.topology

import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.topology.processing.EffectiveTime

/** A timestamp that makes a topology change visible from Canton's topology client point of view. */
final case class TopologyActivationTime(value: CantonTimestamp)

object TopologyActivationTime {

  /** Computes the activation time for a topology change effective at `effectiveTime`, i.e.,
    *  the timestamp of the first topology snapshot that includes such a topology change.
    *  In Canton, the effective time, AKA `validFrom`, of a topology change, is computed as its sequencing time plus
    *  an `epsilon` that is greater or equal to dynamic domain parameter `delay` (it can be greater due to adjustments
    *  to ensure that effective order is the same as sequencing order even if the `delay` changes).
    *  A topology snapshot at time `t` includes by definition all topology changes with effective time `et` < `t`.
    */
  def fromEffectiveTime(effectiveTime: EffectiveTime): TopologyActivationTime =
    TopologyActivationTime(effectiveTime.value.immediateSuccessor)
}