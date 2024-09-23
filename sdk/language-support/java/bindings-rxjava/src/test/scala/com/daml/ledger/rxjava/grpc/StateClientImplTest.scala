// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.ledger.rxjava.grpc

import java.util.concurrent.TimeUnit
import com.daml.ledger.rxjava._
import com.daml.ledger.rxjava.grpc.helpers.TransactionGenerator.nonEmptyLedgerContent
import com.daml.ledger.rxjava.grpc.helpers.{DataLayerHelpers, LedgerServices, TestConfiguration}
import com.digitalasset.canton.platform.ApiOffset
import io.reactivex.Observable
import org.scalacheck.Shrink.shrinkAny // disable shrinking for Gen.nonEmptyListOf (see https://github.com/typelevel/scalacheck/issues/129)
import org.scalatestplus.scalacheck.ScalaCheckDrivenPropertyChecks
import org.scalatest.OptionValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.concurrent.ExecutionContext
import scala.jdk.CollectionConverters._
import scala.jdk.OptionConverters._

class StateClientImplTest
    extends AnyFlatSpec
    with ScalaCheckDrivenPropertyChecks
    with Matchers
    with AuthMatchers
    with OptionValues
    with DataLayerHelpers {

  val ledgerServices = new LedgerServices("state-service-ledger")
  implicit val ec: ExecutionContext = ledgerServices.executionContext

  behavior of "[1.1] StateClientImpl.getActiveContracts"

  it should "support the empty ACS" in {
    ledgerServices.withACSClient(Observable.empty(), Observable.empty()) { (acsClient, _) =>
      val acs = acsClient
        .getActiveContracts(filterNothing, true)
        .timeout(TestConfiguration.timeoutInSeconds, TimeUnit.SECONDS)
      acs.blockingIterable().asScala.size shouldBe 0
    }
  }

  it should "support ACS with one element" in {
    ledgerServices.withACSClient(
      Observable.fromArray(genGetActiveContractsResponse),
      Observable.empty(),
    ) { (acsClient, _) =>
      val acs = acsClient
        .getActiveContracts(filterNothing, true)
        .timeout(TestConfiguration.timeoutInSeconds, TimeUnit.SECONDS)
      acs.blockingIterable().asScala.size shouldBe 1
    }
  }

  it should "support ACS with 10 elements" in {
    val acsResponses = List.fill(10)(genGetActiveContractsResponse)
    ledgerServices.withACSClient(Observable.fromArray(acsResponses: _*), Observable.empty()) {
      (acsClient, _) =>
        val acs = acsClient
          .getActiveContracts(filterNothing, true)
          .timeout(TestConfiguration.timeoutInSeconds, TimeUnit.SECONDS)
        acs.blockingIterable().asScala.size shouldBe 10
    }
  }

  behavior of "[1.2] StateClientImpl.getActiveContracts"

  it should "pass the transaction filter and the verbose flag to the ledger" in {
    ledgerServices.withACSClient(Observable.empty(), Observable.empty()) { (acsClient, acsImpl) =>
      val verbose = true
      acsClient
        .getActiveContracts(filterNothing, verbose)
        .timeout(TestConfiguration.timeoutInSeconds, TimeUnit.SECONDS)
        .blockingIterable()
        .asScala
        .size
      acsImpl.getLastRequest.value.getFilter.filtersByParty shouldBe filterNothing.getPartyToFilters.asScala
      acsImpl.getLastRequest.value.verbose shouldBe verbose
    }
  }

  behavior of "[1.3] StateClientImpl.getActiveContracts"

  "StateClientImpl.getActiveContracts" should "fail with insufficient authorization" in {
    ledgerServices.withACSClient(Observable.empty(), Observable.empty(), mockedAuthService) {
      (acsClient, _) =>
        expectUnauthenticated {
          acsClient
            .getActiveContracts(filterFor(someParty), false, emptyToken)
            .timeout(TestConfiguration.timeoutInSeconds, TimeUnit.SECONDS)
            .blockingIterable()
            .asScala
            .size
        }
    }
  }

  "StateClientImpl.getActiveContracts" should "succeed with sufficient authorization" in {
    ledgerServices.withACSClient(Observable.empty(), Observable.empty(), mockedAuthService) {
      (acsClient, _) =>
        acsClient
          .getActiveContracts(filterFor(someParty), false, somePartyReadToken)
          .timeout(TestConfiguration.timeoutInSeconds, TimeUnit.SECONDS)
          .blockingIterable()
          .asScala
          .size shouldEqual 0
    }
  }

  behavior of "[1.3] StateClientImpl.getLedgerEnd"

  it should "provide ledger end from the ledger" in forAll(nonEmptyLedgerContent) {
    case (ledgerContent, transactions) =>
      ledgerServices.withACSClient(
        Observable.empty(),
        Observable.fromIterable(ledgerContent.asJava),
      ) { (stateClient, _) =>
        println(transactions.last.getOffset)
        val expectedOffset = ApiOffset.assertFromStringToLongO(transactions.last.getOffset)
        stateClient.getLedgerEnd.blockingGet() shouldBe expectedOffset.toJava
      }
  }

  it should "provide participant begin from empty ledger" in
    ledgerServices.withACSClient(Observable.empty(), Observable.empty()) { (transactionClient, _) =>
      transactionClient.getLedgerEnd.blockingGet() shouldBe empty
    }
}
