// Copyright (c) 2025 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.canton.platform.store.backend.common

import anorm.SqlParser.{array, byteArray, int}
import anorm.{RowParser, ~}
import com.digitalasset.canton.data.Offset
import com.digitalasset.canton.platform.store.backend.ContractStorageBackend
import com.digitalasset.canton.platform.store.backend.ContractStorageBackend.{
  RawArchivedContract,
  RawCreatedContract,
}
import com.digitalasset.canton.platform.store.backend.Conversions.{
  OffsetToStatement,
  contractId,
  timestampFromMicros,
}
import com.digitalasset.canton.platform.store.backend.common.ComposableQuery.SqlStringInterpolation
import com.digitalasset.canton.platform.store.interfaces.LedgerDaoContractsReader.{
  KeyAssigned,
  KeyState,
  KeyUnassigned,
}
import com.digitalasset.canton.platform.store.interning.StringInterning
import com.digitalasset.canton.platform.{ContractId, Key}

import java.sql.Connection

class ContractStorageBackendTemplate(
    queryStrategy: QueryStrategy,
    stringInterning: StringInterning,
) extends ContractStorageBackend {

  override def supportsBatchKeyStateLookups: Boolean = false

  override def keyStates(keys: Seq[Key], validAt: Offset)(
      connection: Connection
  ): Map[Key, KeyState] = keys.map(key => key -> keyState(key, validAt)(connection)).toMap

  override def keyState(key: Key, validAt: Offset)(connection: Connection): KeyState = {
    val resultParser = (contractId("contract_id") ~ array[Int]("flat_event_witnesses")).map {
      case cId ~ stakeholders =>
        KeyAssigned(cId, stakeholders.view.map(stringInterning.party.externalize).toSet)
    }.singleOpt
    import com.digitalasset.canton.platform.store.backend.Conversions.HashToStatement
    SQL"""
         WITH last_contract_key_create AS (
                SELECT lapi_events_create.*
                  FROM lapi_events_create
                 WHERE create_key_hash = ${key.hash}
                   AND event_offset <= $validAt
                   AND cardinality(flat_event_witnesses) > 0 -- exclude participant divulgence and transients
                 ORDER BY event_sequential_id DESC
                 FETCH NEXT 1 ROW ONLY
              )
         SELECT contract_id, flat_event_witnesses
           FROM last_contract_key_create
         WHERE NOT EXISTS
                (SELECT 1
                   FROM lapi_events_consuming_exercise
                  WHERE
                    contract_id = last_contract_key_create.contract_id
                    AND event_offset <= $validAt
                )"""
      .as(resultParser)(connection)
      .getOrElse(KeyUnassigned)
  }

  private val archivedContractRowParser: RowParser[(ContractId, RawArchivedContract)] =
    (contractId("contract_id") ~ array[Int]("flat_event_witnesses"))
      .map { case coid ~ flatEventWitnesses =>
        coid -> RawArchivedContract(
          flatEventWitnesses = flatEventWitnesses.view
            .map(stringInterning.party.externalize)
            .toSet
        )
      }

  override def archivedContracts(contractIds: Seq[ContractId], before: Offset)(
      connection: Connection
  ): Map[ContractId, RawArchivedContract] =
    if (contractIds.isEmpty) Map.empty
    else {
      SQL"""
       SELECT contract_id, flat_event_witnesses
       FROM lapi_events_consuming_exercise
       WHERE
         contract_id ${queryStrategy.anyOfBinary(contractIds.map(_.toBytes.toByteArray))}
         AND event_offset <= $before
         AND cardinality(flat_event_witnesses) > 0 -- exclude participant divulgence and transients"""
        .as(archivedContractRowParser.*)(connection)
        .toMap
    }

  private val rawCreatedContractRowParser
      : RowParser[(ContractId, ContractStorageBackend.RawCreatedContract)] =
    (contractId("contract_id")
      ~ int("template_id")
      ~ int("package_name")
      ~ array[Int]("flat_event_witnesses")
      ~ byteArray("create_argument")
      ~ int("create_argument_compression").?
      ~ timestampFromMicros("ledger_effective_time")
      ~ array[Int]("create_signatories")
      ~ byteArray("create_key_value").?
      ~ int("create_key_value_compression").?
      ~ array[Int]("create_key_maintainers").?
      ~ byteArray("driver_metadata"))
      .map {
        case coid ~ internedTemplateId ~ internedPackageName ~ flatEventWitnesses ~ createArgument ~ createArgumentCompression ~ ledgerEffectiveTime ~ signatories ~ createKey ~ createKeyCompression ~ keyMaintainers ~ driverMetadata =>
          coid -> RawCreatedContract(
            templateId = stringInterning.templateId.unsafe.externalize(internedTemplateId),
            packageName = stringInterning.packageName.unsafe.externalize(internedPackageName),
            flatEventWitnesses =
              flatEventWitnesses.view.map(stringInterning.party.externalize).toSet,
            createArgument = createArgument,
            createArgumentCompression = createArgumentCompression,
            ledgerEffectiveTime = ledgerEffectiveTime,
            signatories = signatories.view.map(i => stringInterning.party.externalize(i)).toSet,
            createKey = createKey,
            createKeyCompression = createKeyCompression,
            keyMaintainers =
              keyMaintainers.map(_.view.map(i => stringInterning.party.externalize(i)).toSet),
            driverMetadata = driverMetadata,
          )
      }

  override def createdContracts(contractIds: Seq[ContractId], before: Offset)(
      connection: Connection
  ): Map[ContractId, RawCreatedContract] =
    if (contractIds.isEmpty) Map.empty
    else {
      SQL"""
         SELECT
           contract_id,
           template_id,
           package_name,
           flat_event_witnesses,
           create_argument,
           create_argument_compression,
           ledger_effective_time,
           create_signatories,
           create_key_value,
           create_key_value_compression,
           create_key_maintainers,
           driver_metadata
         FROM lapi_events_create
         WHERE
           contract_id ${queryStrategy.anyOfBinary(contractIds.map(_.toBytes.toByteArray))}
           AND event_offset <= $before
           AND cardinality(flat_event_witnesses) > 0 -- exclude participant divulgence and transients"""
        .as(rawCreatedContractRowParser.*)(connection)
        .toMap
    }

  override def assignedContracts(
      contractIds: Seq[ContractId],
      before: Offset,
  )(
      connection: Connection
  ): Map[ContractId, RawCreatedContract] =
    if (contractIds.isEmpty) Map.empty
    else {
      SQL"""
         WITH min_event_sequential_ids_of_assign AS (
             SELECT MIN(event_sequential_id) min_event_sequential_id
             FROM lapi_events_assign
             WHERE
               contract_id ${queryStrategy.anyOfBinary(contractIds.map(_.toBytes.toByteArray))}
               AND event_offset <= $before
             GROUP BY contract_id
           )
         SELECT
           contract_id,
           template_id,
           package_name,
           flat_event_witnesses,
           create_argument,
           create_argument_compression,
           ledger_effective_time,
           create_signatories,
           create_key_value,
           create_key_value_compression,
           create_key_maintainers,
           driver_metadata
         FROM lapi_events_assign, min_event_sequential_ids_of_assign
         WHERE
           event_sequential_id = min_event_sequential_ids_of_assign.min_event_sequential_id"""
        .as(rawCreatedContractRowParser.*)(connection)
        .toMap
    }
}
