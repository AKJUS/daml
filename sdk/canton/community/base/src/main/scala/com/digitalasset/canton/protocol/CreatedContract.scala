// Copyright (c) 2025 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.canton.protocol

import cats.syntax.either.*
import com.digitalasset.canton.ProtoDeserializationError.{ContractDeserializationError, OtherError}
import com.digitalasset.canton.logging.pretty.{Pretty, PrettyPrinting}
import com.digitalasset.canton.serialization.ProtoConverter.ParsingResult
import com.google.common.annotations.VisibleForTesting
import monocle.Lens
import monocle.macros.GenLens

/** @param consumedInCore
  *   Whether the contract is consumed in the core of the view.
  *   [[com.digitalasset.canton.protocol.WellFormedTransaction]] checks that a created contract can
  *   only be used in the same or deeper rollback scopes as the create, so if `rolledBack` is true
  *   then `consumedInCore` is false.
  * @param rolledBack
  *   Whether the contract creation has a different rollback scope than the view.
  */
final case class CreatedContract private (
    contract: NewContractInstance,
    consumedInCore: Boolean,
    rolledBack: Boolean,
) extends PrettyPrinting {

  // Note that on behalf of rolledBack contracts we still send the SerializableContract along with the contract instance
  // mainly to support DAMLe.reinterpret on behalf of a top-level CreateActionDescription under a rollback node because
  // we need the contract instance to construct the LfCreateCommand.
  def toProtoV30: v30.CreatedContract =
    v30.CreatedContract(
      contract = contract.encoded,
      consumedInCore = consumedInCore,
      rolledBack = rolledBack,
    )

  override protected def pretty: Pretty[CreatedContract] = prettyOfClass(
    unnamedParam(_.contract),
    paramIfTrue("consumed in core", _.consumedInCore),
    paramIfTrue("rolled back", _.rolledBack),
  )

}

object CreatedContract {
  def create(
      contract: NewContractInstance,
      consumedInCore: Boolean,
      rolledBack: Boolean,
  ): Either[String, CreatedContract] =
    CantonContractIdVersion
      .extractCantonContractIdVersion(contract.contractId)
      .leftMap(err => s"Encountered invalid Canton contract id: ${err.toString}")
      .map(_ => new CreatedContract(contract, consumedInCore, rolledBack))

  def tryCreate(
      contract: NewContractInstance,
      consumedInCore: Boolean,
      rolledBack: Boolean,
  ): CreatedContract =
    create(
      contract = contract,
      consumedInCore = consumedInCore,
      rolledBack = rolledBack,
    ).valueOr(err => throw new IllegalArgumentException(err))

  def fromProtoV30(
      createdContractP: v30.CreatedContract
  ): ParsingResult[CreatedContract] = {
    val v30.CreatedContract(contractP, consumedInCore, rolledBack) =
      createdContractP

    for {
      contract <- ContractInstance
        .decodeCreated(contractP)
        .leftMap(err => ContractDeserializationError(err))
      createdContract <- create(
        contract = contract,
        consumedInCore = consumedInCore,
        rolledBack = rolledBack,
      ).leftMap(OtherError.apply)
    } yield createdContract
  }

  @VisibleForTesting
  val contractUnsafe: Lens[CreatedContract, NewContractInstance] =
    GenLens[CreatedContract](_.contract)
}

/** @param consumedInView
  *   Whether the contract is consumed in the view.
  *   [[com.digitalasset.canton.protocol.WellFormedTransaction]] checks that a created contract can
  *   only be used in the same or deeper rollback scopes as the create, so if `rolledBack` is true
  *   then `consumedInView` is false.
  * @param rolledBack
  *   Whether the contract creation has a different rollback scope than the view.
  */
final case class CreatedContractInView(
    contract: NewContractInstance,
    consumedInView: Boolean,
    rolledBack: Boolean,
)
object CreatedContractInView {
  def fromCreatedContract(created: CreatedContract): CreatedContractInView =
    CreatedContractInView(
      created.contract,
      consumedInView = created.consumedInCore,
      created.rolledBack,
    )
}
