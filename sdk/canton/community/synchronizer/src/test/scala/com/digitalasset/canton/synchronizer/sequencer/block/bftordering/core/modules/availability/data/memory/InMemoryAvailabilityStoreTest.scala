// Copyright (c) 2025 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.canton.synchronizer.sequencer.block.bftordering.core.modules.availability.data.memory

import com.digitalasset.canton.synchronizer.sequencer.block.bftordering.bindings.pekko.PekkoModuleSystem
import com.digitalasset.canton.synchronizer.sequencer.block.bftordering.core.modules.availability.data.{
  AvailabilityStore,
  AvailabilityStoreTest,
}

class InMemoryAvailabilityStoreTest extends AvailabilityStoreTest {
  override def createStore(): AvailabilityStore[PekkoModuleSystem.PekkoEnv] =
    new InMemoryAvailabilityStore()
}
