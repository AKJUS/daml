// Copyright (c) 2025 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.ledger.javaapi.data

import com.daml.ledger.javaapi.data.Generators.*
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.scalacheck.ScalaCheckDrivenPropertyChecks

import scala.annotation.nowarn

// TODO(#23504) remove
@nowarn("cat=deprecation")
class SubmitAndWaitForTransactionTreeResponseSpec
    extends AnyFlatSpec
    with Matchers
    with ScalaCheckDrivenPropertyChecks {

  "SubmitAndWaitForTransactionTreeResponse.fromProto" should "convert Protoc-generated instances to data instances" in forAll(
    submitAndWaitForTransactionTreeResponseGen
  ) { response =>
    val converted =
      SubmitAndWaitForTransactionTreeResponse.fromProto(response)
    SubmitAndWaitForTransactionTreeResponse.fromProto(converted.toProto) shouldEqual converted
  }
}
