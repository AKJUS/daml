// Copyright (c) 2025 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.canton.platform.store.dao

import com.daml.ledger.api.v2.event.CreatedEvent
import com.daml.ledger.api.v2.state_service.GetActiveContractsResponse
import com.digitalasset.canton.ledger.api.util.LfEngineToApi
import com.digitalasset.canton.platform.TemplatePartiesFilter
import com.digitalasset.canton.platform.store.dao.EventProjectionProperties.{
  Projection,
  UseOriginalViewPackageId,
}
import com.digitalasset.daml.lf.data.Ref.{Identifier, Party}
import org.apache.pekko.NotUsed
import org.apache.pekko.stream.scaladsl.{Sink, Source}
import org.scalatest.*
import org.scalatest.flatspec.AsyncFlatSpec
import org.scalatest.matchers.should.Matchers

import java.util.UUID
import scala.concurrent.Future

private[dao] trait JdbcLedgerDaoActiveContractsSpec
    extends OptionValues
    with Inside
    with Inspectors
    with LoneElement {
  this: AsyncFlatSpec with Matchers with JdbcLedgerDaoSuite =>

  behavior of "JdbcLedgerDao (getActiveContracts)"

  it should "serve the correct contracts after a series of transactions" in {
    for {
      before <- ledgerDao.lookupLedgerEnd()
      (_, t1) <- store(singleCreate)
      (_, t2) <- store(singleCreate)
      (_, _) <- store(singleExercise(nonTransient(t2).loneElement))
      (_, _) <- store(fullyTransient())
      (_, t5) <- store(singleCreate)
      (_, t6) <- store(singleCreate)
      after <- ledgerDao.lookupLedgerEnd()
      activeContractsBefore <- activeContractsOf(
        ledgerDao.updateReader
          .getActiveContracts(
            activeAt = before.map(_.lastOffset),
            filter = TemplatePartiesFilter(Map.empty, Some(Set(alice, bob, charlie))),
            eventProjectionProperties = EventProjectionProperties(
              verbose = true
            )(interfaceViewPackageUpgrade = UseOriginalViewPackageId),
          )
      )
      activeContractsAfter <- activeContractsOf(
        ledgerDao.updateReader
          .getActiveContracts(
            activeAt = after.map(_.lastOffset),
            filter = TemplatePartiesFilter(Map.empty, Some(Set(alice, bob, charlie))),
            eventProjectionProperties = EventProjectionProperties(
              verbose = true
            )(interfaceViewPackageUpgrade = UseOriginalViewPackageId),
          )
      )
    } yield {
      val activeContracts = activeContractsAfter.toSet.diff(activeContractsBefore.toSet)
      activeContracts should have size 3
      activeContracts.map(_.contractId) shouldBe Set(
        nonTransient(t1).loneElement.coid,
        nonTransient(t5).loneElement.coid,
        nonTransient(t6).loneElement.coid,
      )
    }
  }

  it should "serve a stable result based on the input offset" in {
    for {
      ledgerEnd <- ledgerDao.lookupLedgerEnd()
      offset = ledgerEnd.map(_.lastOffset)
      activeContractsBefore <- activeContractsOf(
        ledgerDao.updateReader
          .getActiveContracts(
            activeAt = offset,
            filter = TemplatePartiesFilter(Map.empty, Some(Set(alice, bob, charlie))),
            eventProjectionProperties = EventProjectionProperties(
              verbose = true
            )(interfaceViewPackageUpgrade = UseOriginalViewPackageId),
          )
      )
      (_, _) <- store(singleCreate)
      (_, c) <- store(singleCreate)
      (_, _) <- store(singleExercise(nonTransient(c).loneElement))
      (_, _) <- store(fullyTransient())
      (_, _) <- store(singleCreate)
      (_, _) <- store(singleCreate)
      activeContractsAfter <- activeContractsOf(
        ledgerDao.updateReader
          .getActiveContracts(
            activeAt = offset,
            filter = TemplatePartiesFilter(Map.empty, Some(Set(alice, bob, charlie))),
            eventProjectionProperties = EventProjectionProperties(
              verbose = true
            )(interfaceViewPackageUpgrade = UseOriginalViewPackageId),
          )
      )
    } yield {
      activeContractsAfter.toSet.diff(activeContractsBefore.toSet) should have size 0
    }
  }

  it should "filter correctly for a single party" in {
    val party1 = Party.assertFromString(UUID.randomUUID.toString)
    val party2 = Party.assertFromString(UUID.randomUUID.toString)
    for {
      _ <- store(
        multipleCreates(
          operator = "operator",
          signatoriesAndTemplates = Seq(
            (party1, someTemplateId, someContractArgument),
            (party2, otherTemplateId, otherContractArgument),
            (party1, otherTemplateId, otherContractArgument),
          ),
        )
      )
      ledgerEnd <- ledgerDao.lookupLedgerEnd()
      result <- activeContractsOf(
        ledgerDao.updateReader
          .getActiveContracts(
            activeAt = ledgerEnd.map(_.lastOffset),
            filter =
              TemplatePartiesFilter(Map(otherTemplateId -> Some(Set(party1))), Some(Set.empty)),
            eventProjectionProperties = EventProjectionProperties(
              verbose = true,
              witnessTemplateProjections = Map(Some(party1) -> Map(otherTemplateId -> Projection())),
            )(interfaceViewPackageUpgrade = UseOriginalViewPackageId),
          )
      )
    } yield {
      val create = result.loneElement
      create.witnessParties.loneElement shouldBe party1
      create.templateId.value shouldBe LfEngineToApi.toApiIdentifier(otherTemplateId)
    }
  }

  it should "filter correctly by multiple parties with the same template" in {
    val party1 = Party.assertFromString(UUID.randomUUID.toString)
    val party2 = Party.assertFromString(UUID.randomUUID.toString)
    for {
      _ <- store(
        multipleCreates(
          operator = "operator",
          signatoriesAndTemplates = Seq(
            (party1, someTemplateId, someContractArgument),
            (party2, otherTemplateId, otherContractArgument),
            (party1, otherTemplateId, otherContractArgument),
          ),
        )
      )
      ledgerEnd <- ledgerDao.lookupLedgerEnd()
      result <- activeContractsOf(
        ledgerDao.updateReader
          .getActiveContracts(
            activeAt = ledgerEnd.map(_.lastOffset),
            filter = TemplatePartiesFilter(
              Map(
                otherTemplateId -> Some(Set(party1, party2))
              ),
              Some(Set.empty),
            ),
            eventProjectionProperties = EventProjectionProperties(
              verbose = true,
              witnessTemplateProjections = Map(
                Some(party1) -> Map(otherTemplateId -> Projection()),
                Some(party2) -> Map(otherTemplateId -> Projection()),
              ),
            )(interfaceViewPackageUpgrade = UseOriginalViewPackageId),
          )
      )
    } yield {
      val activeContracts = result.toArray
      activeContracts should have length 2

      val create1 = activeContracts(0)
      create1.witnessParties.loneElement shouldBe party2
      create1.templateId.value shouldBe LfEngineToApi.toApiIdentifier(otherTemplateId)

      val create2 = activeContracts(1)
      create2.witnessParties.loneElement shouldBe party1
      create2.templateId.value shouldBe LfEngineToApi.toApiIdentifier(otherTemplateId)
    }
  }

  it should "filter correctly by party-wildcard with the same template" in {
    val party1 = Party.assertFromString(UUID.randomUUID.toString)
    val party2 = Party.assertFromString(UUID.randomUUID.toString)
    for {
      _ <- store(
        multipleCreates(
          operator = "operator",
          signatoriesAndTemplates = Seq(
            (party1, someTemplateId, someContractArgument),
            (party2, otherTemplateId2, otherContractArgument),
            (party1, otherTemplateId2, otherContractArgument),
          ),
        )
      )
      ledgerEnd <- ledgerDao.lookupLedgerEnd()
      result <- activeContractsOf(
        ledgerDao.updateReader
          .getActiveContracts(
            activeAt = ledgerEnd.map(_.lastOffset),
            filter = TemplatePartiesFilter(
              Map(
                otherTemplateId2 -> None
              ),
              Some(Set.empty),
            ),
            eventProjectionProperties = EventProjectionProperties(
              verbose = true,
              witnessTemplateProjections = Map(
                Some(party1) -> Map(otherTemplateId2 -> Projection()),
                Some(party2) -> Map(otherTemplateId2 -> Projection()),
              ),
            )(interfaceViewPackageUpgrade = UseOriginalViewPackageId),
          )
      )
    } yield {
      val activeContracts = result.toArray
      activeContracts should have length 2

      val create1 = activeContracts(0)
      create1.witnessParties.loneElement shouldBe party2
      create1.templateId.value shouldBe LfEngineToApi.toApiIdentifier(otherTemplateId2)

      val create2 = activeContracts(1)
      create2.witnessParties.loneElement shouldBe party1
      create2.templateId.value shouldBe LfEngineToApi.toApiIdentifier(otherTemplateId2)
    }
  }

  it should "filter correctly by multiple parties with different templates" in {
    val party1 = Party.assertFromString(UUID.randomUUID.toString)
    val party2 = Party.assertFromString(UUID.randomUUID.toString)
    for {
      _ <- store(
        multipleCreates(
          operator = "operator",
          signatoriesAndTemplates = Seq(
            (party1, someTemplateId, someContractArgument),
            (party2, otherTemplateId, otherContractArgument),
            (party1, otherTemplateId, otherContractArgument),
          ),
        )
      )
      ledgerEnd <- ledgerDao.lookupLedgerEnd()
      result <- activeContractsOf(
        ledgerDao.updateReader
          .getActiveContracts(
            activeAt = ledgerEnd.map(_.lastOffset),
            filter = TemplatePartiesFilter(
              Map(
                someTemplateId -> Some(Set(party1)),
                otherTemplateId -> Some(Set(party2)),
              ),
              Some(Set.empty),
            ),
            eventProjectionProperties = EventProjectionProperties(
              verbose = true,
              witnessTemplateProjections = Map(
                Some(party1) -> Map(otherTemplateId -> Projection()),
                Some(party2) -> Map(otherTemplateId -> Projection()),
              ),
            )(interfaceViewPackageUpgrade = UseOriginalViewPackageId),
          )
      )
    } yield {
      val activeContracts = result.toArray
      activeContracts should have length 2

      val create2 = activeContracts(0)
      create2.witnessParties.loneElement shouldBe party1
      create2.templateId.value shouldBe LfEngineToApi.toApiIdentifier(someTemplateId)

      val create1 = activeContracts(1)
      create1.witnessParties.loneElement shouldBe party2
      create1.templateId.value shouldBe LfEngineToApi.toApiIdentifier(otherTemplateId)
    }
  }

  it should "filter correctly by party-wildcard with different templates" in {
    val party1 = Party.assertFromString(UUID.randomUUID.toString)
    val party2 = Party.assertFromString(UUID.randomUUID.toString)
    for {
      _ <- store(
        multipleCreates(
          operator = "operator",
          signatoriesAndTemplates = Seq(
            (party1, otherTemplateId3, someContractArgument),
            (party2, otherTemplateId4, otherContractArgument),
            (party1, otherTemplateId4, otherContractArgument),
          ),
        )
      )
      ledgerEnd <- ledgerDao.lookupLedgerEnd()
      result <- activeContractsOf(
        ledgerDao.updateReader
          .getActiveContracts(
            activeAt = ledgerEnd.map(_.lastOffset),
            filter = TemplatePartiesFilter(
              Map(
                otherTemplateId3 -> None,
                otherTemplateId4 -> None,
              ),
              Some(Set.empty),
            ),
            eventProjectionProperties = EventProjectionProperties(
              verbose = false,
              witnessTemplateProjections = Map(
                None -> Map(otherTemplateId3 -> Projection()),
                None -> Map(otherTemplateId4 -> Projection()),
              ),
            )(interfaceViewPackageUpgrade = UseOriginalViewPackageId),
          )
      )
    } yield {
      val activeContracts = result.toArray
      activeContracts should have length 3

      val create2 = activeContracts(0)
      create2.witnessParties.loneElement shouldBe party1
      create2.templateId.value shouldBe LfEngineToApi.toApiIdentifier(otherTemplateId3)

      val create1 = activeContracts(1)
      create1.witnessParties.loneElement shouldBe party2
      create1.templateId.value shouldBe LfEngineToApi.toApiIdentifier(otherTemplateId4)
    }
  }

  it should "filter correctly by multiple parties with different template and wildcards" in {
    val party1 = Party.assertFromString(UUID.randomUUID.toString)
    val party2 = Party.assertFromString(UUID.randomUUID.toString)
    for {
      _ <- store(
        multipleCreates(
          operator = "operator",
          signatoriesAndTemplates = Seq(
            (party1, someTemplateId, someContractArgument),
            (party2, otherTemplateId, otherContractArgument),
            (party1, otherTemplateId, otherContractArgument),
          ),
        )
      )
      ledgerEnd <- ledgerDao.lookupLedgerEnd()
      result <- activeContractsOf(
        ledgerDao.updateReader
          .getActiveContracts(
            activeAt = ledgerEnd.map(_.lastOffset),
            filter = TemplatePartiesFilter(
              Map(
                someTemplateId -> Some(Set(party1))
              ),
              Some(Set(party2)),
            ),
            eventProjectionProperties = EventProjectionProperties(
              verbose = true,
              witnessTemplateProjections = Map(
                Some(party1) -> Map(someTemplateId -> Projection())
              ),
            )(interfaceViewPackageUpgrade = UseOriginalViewPackageId),
          )
      )
    } yield {
      val activeContracts = result.toArray
      activeContracts should have length 2

      val create2 = activeContracts(0)
      create2.witnessParties.loneElement shouldBe party1
      create2.templateId.value shouldBe LfEngineToApi.toApiIdentifier(someTemplateId)

      val create1 = activeContracts(1)
      create1.witnessParties.loneElement shouldBe party2
      create1.templateId.value shouldBe LfEngineToApi.toApiIdentifier(otherTemplateId)
    }
  }

  it should "filter correctly by multiple parties with different template and party- and template- wildcards" in {
    val party1 = Party.assertFromString(UUID.randomUUID.toString)
    val party2 = Party.assertFromString(UUID.randomUUID.toString)
    for {
      _ <- store(
        multipleCreates(
          operator = "operator",
          signatoriesAndTemplates = Seq(
            (party1, otherTemplateId5, otherContractArgument5),
            (party2, otherTemplateId, otherContractArgument),
            (party1, otherTemplateId, otherContractArgument),
          ),
        )
      )
      ledgerEnd <- ledgerDao.lookupLedgerEnd()
      result <- activeContractsOf(
        ledgerDao.updateReader
          .getActiveContracts(
            activeAt = ledgerEnd.map(_.lastOffset),
            filter = TemplatePartiesFilter(
              relation = Map(
                otherTemplateId5 -> None
              ),
              templateWildcardParties = Some(Set(party2)),
            ),
            eventProjectionProperties = EventProjectionProperties(
              verbose = true,
              witnessTemplateProjections = Map(
                Some(party1) -> Map(otherTemplateId5 -> Projection())
              ),
            )(interfaceViewPackageUpgrade = UseOriginalViewPackageId),
          )
      )
    } yield {
      val activeContracts = result.toArray
      activeContracts should have length 2

      val create2 = activeContracts(0)
      create2.witnessParties.loneElement shouldBe party1
      create2.templateId.value shouldBe LfEngineToApi.toApiIdentifier(otherTemplateId5)

      val create1 = activeContracts(1)
      create1.witnessParties.loneElement shouldBe party2
      create1.templateId.value shouldBe LfEngineToApi.toApiIdentifier(otherTemplateId)
    }
  }

  it should "filter correctly with unknown parties and templates" in {
    val party1 = Party.assertFromString(UUID.randomUUID.toString)
    val party2 = Party.assertFromString(UUID.randomUUID.toString)

    // Adding an unknown party and/or template to the filter should not
    // affect the results
    val unknownParty = Party.assertFromString(UUID.randomUUID.toString)
    val unknownTemplate = Identifier.assertFromString("pkg:Mod:Template")

    for {
      _ <- store(
        multipleCreates(
          operator = "operator",
          signatoriesAndTemplates = Seq(
            (party1, someTemplateId, someContractArgument),
            (party2, otherTemplateId, otherContractArgument),
            (party1, otherTemplateId, otherContractArgument),
          ),
        )
      )
      ledgerEnd <- ledgerDao.lookupLedgerEnd()
      result <- activeContractsOf(
        ledgerDao.updateReader
          .getActiveContracts(
            activeAt = ledgerEnd.map(_.lastOffset),
            filter = TemplatePartiesFilter(
              Map(
                someTemplateId -> Some(Set(party1))
              ),
              Some(Set(party2)),
            ),
            eventProjectionProperties = EventProjectionProperties(
              verbose = true,
              witnessTemplateProjections = Map(
                Some(party1) -> Map(someTemplateId -> Projection())
              ),
            )(interfaceViewPackageUpgrade = UseOriginalViewPackageId),
          )
      )
      resultUnknownParty <- activeContractsOf(
        ledgerDao.updateReader
          .getActiveContracts(
            activeAt = ledgerEnd.map(_.lastOffset),
            filter = TemplatePartiesFilter(
              Map(
                someTemplateId -> Some(Set(party1))
              ),
              Some(Set(party2, unknownParty)),
            ),
            eventProjectionProperties = EventProjectionProperties(
              verbose = true,
              witnessTemplateProjections = Map(
                Some(party1) -> Map(someTemplateId -> Projection())
              ),
            )(interfaceViewPackageUpgrade = UseOriginalViewPackageId),
          )
      )
      resultUnknownTemplate <- activeContractsOf(
        ledgerDao.updateReader
          .getActiveContracts(
            activeAt = ledgerEnd.map(_.lastOffset),
            filter = TemplatePartiesFilter(
              Map(
                someTemplateId -> Some(Set(party1)),
                unknownTemplate -> Some(Set(party1)),
              ),
              Some(Set(party2)),
            ),
            eventProjectionProperties = EventProjectionProperties(
              verbose = true,
              witnessTemplateProjections = Map(
                Some(party1) -> Map(
                  someTemplateId -> Projection(),
                  unknownTemplate -> Projection(),
                )
              ),
            )(interfaceViewPackageUpgrade = UseOriginalViewPackageId),
          )
      )
      resultUnknownTemplatePartyWildcard <- activeContractsOf(
        ledgerDao.updateReader
          .getActiveContracts(
            activeAt = ledgerEnd.map(_.lastOffset),
            filter = TemplatePartiesFilter(
              relation = Map(
                someTemplateId -> Some(Set(party1)),
                unknownTemplate -> None,
              ),
              templateWildcardParties = Some(Set(party2)),
            ),
            eventProjectionProperties = EventProjectionProperties(
              verbose = true,
              witnessTemplateProjections = Map(
                Some(party1) -> Map(
                  someTemplateId -> Projection()
                ),
                None -> Map(
                  unknownTemplate -> Projection()
                ),
              ),
            )(interfaceViewPackageUpgrade = UseOriginalViewPackageId),
          )
      )
      resultUnknownPartyAndTemplate <- activeContractsOf(
        ledgerDao.updateReader
          .getActiveContracts(
            activeAt = ledgerEnd.map(_.lastOffset),
            filter = TemplatePartiesFilter(
              Map(
                someTemplateId -> Some(Set(party1)),
                unknownTemplate -> Some(Set(party1)),
              ),
              Some(Set(party2, unknownParty)),
            ),
            eventProjectionProperties = EventProjectionProperties(
              verbose = true,
              witnessTemplateProjections = Map(
                Some(party1) -> Map(
                  someTemplateId -> Projection(),
                  unknownTemplate -> Projection(),
                )
              ),
            )(interfaceViewPackageUpgrade = UseOriginalViewPackageId),
          )
      )
      resultUnknownsOnly <- activeContractsOf(
        ledgerDao.updateReader
          .getActiveContracts(
            activeAt = ledgerEnd.map(_.lastOffset),
            filter = TemplatePartiesFilter(
              Map(
                unknownTemplate -> Some(Set(unknownParty))
              ),
              Some(Set.empty),
            ),
            eventProjectionProperties = EventProjectionProperties(
              verbose = true,
              witnessTemplateProjections = Map(
                Some(unknownParty) -> Map(
                  unknownTemplate -> Projection()
                )
              ),
            )(interfaceViewPackageUpgrade = UseOriginalViewPackageId),
          )
      )
    } yield {
      result should have length 2
      resultUnknownParty should contain theSameElementsAs result
      resultUnknownTemplate should contain theSameElementsAs result
      resultUnknownTemplatePartyWildcard should contain theSameElementsAs result
      resultUnknownPartyAndTemplate should contain theSameElementsAs result

      resultUnknownsOnly shouldBe empty
    }
  }

  it should "not set the offset" in {
    for {
      _ <- store(singleCreate)
      _ <- store(singleCreate)
      ledgerEnd <- ledgerDao.lookupLedgerEnd()
      activeContracts <- ledgerDao.updateReader
        .getActiveContracts(
          activeAt = ledgerEnd.map(_.lastOffset),
          filter = TemplatePartiesFilter(Map.empty, Some(Set(alice))),
          eventProjectionProperties = EventProjectionProperties(
            verbose = true
          )(interfaceViewPackageUpgrade = UseOriginalViewPackageId),
        )
        .runWith(Sink.seq)

    } yield {
      activeContracts should not be empty
    }
  }

  it should "serve the correct events" in {
    for {
      before <- ledgerDao.lookupLedgerEnd()
      (offset1, t1) <- store(singleCreate)
      after <- ledgerDao.lookupLedgerEnd()
      activeContractsBefore <- activeContractsOf(
        ledgerDao.updateReader
          .getActiveContracts(
            activeAt = before.map(_.lastOffset),
            filter = TemplatePartiesFilter(Map.empty, Some(Set(alice, bob, charlie))),
            eventProjectionProperties = EventProjectionProperties(
              verbose = true
            )(interfaceViewPackageUpgrade = UseOriginalViewPackageId),
          )
      )
      activeContractsAfter <- activeContractsOf(
        ledgerDao.updateReader
          .getActiveContracts(
            activeAt = after.map(_.lastOffset),
            filter = TemplatePartiesFilter(Map.empty, Some(Set(alice, bob, charlie))),
            eventProjectionProperties = EventProjectionProperties(
              verbose = true
            )(interfaceViewPackageUpgrade = UseOriginalViewPackageId),
          )
      )
    } yield {
      val activeContract = activeContractsAfter.toSet.diff(activeContractsBefore.toSet).loneElement
      activeContract.offset shouldBe offset1.unwrap
      activeContract.nodeId shouldBe 0
    }
  }

  private def activeContractsOf(
      source: Source[GetActiveContractsResponse, NotUsed]
  ): Future[Seq[CreatedEvent]] =
    source.runWith(Sink.seq).map(_.flatMap(_.contractEntry.activeContract.flatMap(_.createdEvent)))
}
