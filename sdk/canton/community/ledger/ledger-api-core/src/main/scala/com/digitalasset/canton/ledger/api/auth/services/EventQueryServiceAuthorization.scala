// Copyright (c) 2025 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.canton.ledger.api.auth.services

import com.daml.ledger.api.v2.event_query_service.EventQueryServiceGrpc.EventQueryService
import com.daml.ledger.api.v2.event_query_service.{
  EventQueryServiceGrpc,
  GetEventsByContractIdRequest,
  GetEventsByContractIdResponse,
}
import com.digitalasset.canton.auth.{Authorizer, RequiredClaim}
import com.digitalasset.canton.ledger.api.ProxyCloseable
import com.digitalasset.canton.ledger.api.auth.RequiredClaims
import com.digitalasset.canton.ledger.api.auth.services.EventQueryServiceAuthorization.getEventsByContractIdClaims
import com.digitalasset.canton.ledger.api.grpc.GrpcApiService
import io.grpc.ServerServiceDefinition

import scala.annotation.nowarn
import scala.concurrent.{ExecutionContext, Future}

final class EventQueryServiceAuthorization(
    protected val service: EventQueryService with AutoCloseable,
    private val authorizer: Authorizer,
)(implicit executionContext: ExecutionContext)
    extends EventQueryService
    with ProxyCloseable
    with GrpcApiService {

  override def getEventsByContractId(
      request: GetEventsByContractIdRequest
  ): Future[GetEventsByContractIdResponse] =
    authorizer.rpc(service.getEventsByContractId)(
      getEventsByContractIdClaims(request)*
    )(request)

  override def bindService(): ServerServiceDefinition =
    EventQueryServiceGrpc.bindService(this, executionContext)
}

object EventQueryServiceAuthorization {
  // TODO(#23504) remove checking requestingParties when they are removed from GerEventsByContractIdRequest
  @nowarn("cat=deprecation")
  def getEventsByContractIdClaims(
      request: GetEventsByContractIdRequest
  ): List[RequiredClaim[GetEventsByContractIdRequest]] =
    RequiredClaims.readAsForAllParties[GetEventsByContractIdRequest](request.requestingParties) :::
      request.eventFormat.toList.flatMap(
        RequiredClaims.eventFormatClaims[GetEventsByContractIdRequest]
      )
}
