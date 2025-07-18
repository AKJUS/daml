// Copyright (c) 2025 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.canton.ledger.api

import com.daml.ledger.api.v2.transaction.{Transaction, TransactionTree}
import com.daml.tracing.SpanAttribute
import org.scalatest.matchers.should.Matchers.*
import org.scalatest.wordspec.AnyWordSpec

import scala.annotation.nowarn

// TODO(#23504) remove tests for TransactionTrees when TransactionTree is removed
@nowarn("cat=deprecation")
class TraceIdentifiersTest extends AnyWordSpec {
  val expected = Map(
    (SpanAttribute.TransactionId, "transaction-id"),
    (SpanAttribute.CommandId, "command-id"),
    (SpanAttribute.WorkflowId, "workflow-id"),
    (SpanAttribute.Offset, "12345678"),
  )

  "extract identifiers from Transaction" should {
    "set non-empty values" in {
      val observed = TraceIdentifiers.fromTransaction(
        Transaction(
          "transaction-id",
          "command-id",
          "workflow-id",
          None,
          Seq(),
          12345678L,
          "",
          None,
          None,
          None,
        )
      )
      observed shouldEqual expected
    }

    "not set empty values" in {
      val observed =
        TraceIdentifiers.fromTransaction(Transaction.defaultInstance)
      observed shouldBe empty
    }
  }

  "extract identifiers from TransactionTree" should {
    "set non-empty values" in {
      val observed = TraceIdentifiers.fromTransactionTree(
        TransactionTree(
          "transaction-id",
          "command-id",
          "workflow-id",
          None,
          12345678L,
          Map(),
          "",
          None,
          None,
        )
      )
      observed shouldEqual expected
    }

    "not set empty values" in {
      val observed =
        TraceIdentifiers.fromTransaction(Transaction.defaultInstance)
      observed shouldBe empty
    }
  }
}
