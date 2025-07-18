// Copyright (c) 2025 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.canton.synchronizer.sequencer.block.bftordering.core.modules.p2p.data.db

import com.daml.nameof.NameOf.functionFullName
import com.digitalasset.canton.lifecycle.FutureUnlessShutdown
import com.digitalasset.canton.resource.DbStorage
import com.digitalasset.canton.store.db.{DbTest, H2Test, PostgresTest}
import com.digitalasset.canton.synchronizer.sequencer.block.bftordering.BftSequencerBaseTest
import com.digitalasset.canton.synchronizer.sequencer.block.bftordering.core.modules.p2p.data.P2PEndpointsStoreTest
import com.digitalasset.canton.tracing.TraceContext
import org.scalatest.wordspec.AsyncWordSpec

trait DbP2PEndpointsStoreTest
    extends AsyncWordSpec
    with BftSequencerBaseTest
    with P2PEndpointsStoreTest {
  this: DbTest =>

  override def cleanDb(
      storage: DbStorage
  )(implicit traceContext: TraceContext): FutureUnlessShutdown[Unit] = {
    import storage.api.*
    storage.update(
      DBIO.seq(
        sqlu"truncate table ord_p2p_endpoints"
      ),
      functionFullName,
    )
  }

  "DbP2pEndpointsStore" should {
    // This storage maintains information provided through configuration admin console and is not required
    //  nor meant to be idempotent; this excludes the idempotent testing wrapper normally present for DB tests.
    lazy val nonIdempotentStorage = storage.underlying
    behave like p2pEndpointsStore(() =>
      new DbP2PEndpointsStore(nonIdempotentStorage, timeouts, loggerFactory)(executionContext)
    )
  }
}

class DbP2pEndpointsStoreH2Test extends DbP2PEndpointsStoreTest with H2Test

class DbP2PEndpointsStorePostgresTest extends DbP2PEndpointsStoreTest with PostgresTest
