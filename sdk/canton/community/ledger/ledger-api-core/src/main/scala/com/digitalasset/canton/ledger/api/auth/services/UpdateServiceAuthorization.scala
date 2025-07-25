// Copyright (c) 2025 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.canton.ledger.api.auth.services

import com.daml.ledger.api.v2.update_service.*
import com.daml.ledger.api.v2.update_service.UpdateServiceGrpc.UpdateService
import com.digitalasset.canton.auth.{Authorizer, RequiredClaim}
import com.digitalasset.canton.ledger.api.ProxyCloseable
import com.digitalasset.canton.ledger.api.auth.RequiredClaims
import com.digitalasset.canton.ledger.api.auth.services.UpdateServiceAuthorization.{
  getTransactionByIdClaims,
  getTransactionByOffsetClaims,
  getUpdatesClaims,
}
import com.digitalasset.canton.ledger.api.grpc.GrpcApiService
import io.grpc.ServerServiceDefinition
import io.grpc.stub.StreamObserver

import scala.annotation.nowarn
import scala.concurrent.{ExecutionContext, Future}

final class UpdateServiceAuthorization(
    protected val service: UpdateService with AutoCloseable,
    private val authorizer: Authorizer,
)(implicit executionContext: ExecutionContext)
    extends UpdateService
    with ProxyCloseable
    with GrpcApiService {

  override def bindService(): ServerServiceDefinition =
    UpdateServiceGrpc.bindService(this, executionContext)

  override def getUpdates(
      request: GetUpdatesRequest,
      responseObserver: StreamObserver[GetUpdatesResponse],
  ): Unit =
    authorizer.stream(service.getUpdates)(
      getUpdatesClaims(request)*
    )(request, responseObserver)

  // TODO(#23504) remove when TransactionTrees are removed from the API
  @nowarn("cat=deprecation")
  override def getUpdateTrees(
      request: GetUpdatesRequest,
      responseObserver: StreamObserver[GetUpdateTreesResponse],
  ): Unit =
    authorizer.stream(service.getUpdateTrees)(
      getUpdatesClaims(request)*
    )(request, responseObserver)

  // TODO(#23504) remove when TransactionTrees are removed from the API
  @nowarn("cat=deprecation")
  override def getTransactionTreeByOffset(
      request: GetTransactionByOffsetRequest
  ): Future[GetTransactionTreeResponse] =
    authorizer.rpc(service.getTransactionTreeByOffset)(
      RequiredClaims.readAsForAllParties[GetTransactionByOffsetRequest](request.requestingParties)*
    )(request)

  // TODO(#23504) remove when TransactionTrees are removed from the API
  @nowarn("cat=deprecation")
  override def getTransactionTreeById(
      request: GetTransactionByIdRequest
  ): Future[GetTransactionTreeResponse] =
    authorizer.rpc(service.getTransactionTreeById)(
      RequiredClaims.readAsForAllParties[GetTransactionByIdRequest](request.requestingParties)*
    )(request)

  // TODO(#23504) remove when getTransactionByOffset is removed from the API
  @nowarn("cat=deprecation")
  override def getTransactionByOffset(
      request: GetTransactionByOffsetRequest
  ): Future[GetTransactionResponse] =
    authorizer.rpc(service.getTransactionByOffset)(
      getTransactionByOffsetClaims(request)*
    )(request)

  // TODO(#23504) remove when getTransactionById is removed from the API
  @nowarn("cat=deprecation")
  override def getTransactionById(
      request: GetTransactionByIdRequest
  ): Future[GetTransactionResponse] =
    authorizer.rpc(service.getTransactionById)(
      getTransactionByIdClaims(request)*
    )(request)

  override def getUpdateByOffset(
      request: GetUpdateByOffsetRequest
  ): Future[GetUpdateResponse] =
    authorizer.rpc(service.getUpdateByOffset)(
      request.updateFormat.toList.flatMap(
        RequiredClaims.updateFormatClaims[GetUpdateByOffsetRequest]
      )*
    )(request)

  override def getUpdateById(
      request: GetUpdateByIdRequest
  ): Future[GetUpdateResponse] =
    authorizer.rpc(service.getUpdateById)(
      request.updateFormat.toList.flatMap(
        RequiredClaims.updateFormatClaims[GetUpdateByIdRequest]
      )*
    )(request)
}

object UpdateServiceAuthorization {

  // TODO(#23504) remove checking filter when it is removed from GetUpdatesRequest
  @nowarn("cat=deprecation")
  def getUpdatesClaims(request: GetUpdatesRequest): List[RequiredClaim[GetUpdatesRequest]] =
    request.updateFormat.toList.flatMap(
      RequiredClaims.updateFormatClaims[GetUpdatesRequest]
    ) ::: request.filter.toList.flatMap(RequiredClaims.transactionFilterClaims[GetUpdatesRequest])

  // TODO(#23504) remove when getTransactionByOffset is removed from the API
  @nowarn("cat=deprecation")
  def getTransactionByOffsetClaims(
      request: GetTransactionByOffsetRequest
  ): List[RequiredClaim[GetTransactionByOffsetRequest]] =
    request.transactionFormat.toList
      .flatMap(
        RequiredClaims.transactionFormatClaims[GetTransactionByOffsetRequest]
      ) ::: RequiredClaims.readAsForAllParties[GetTransactionByOffsetRequest](
      request.requestingParties
    )

  // TODO(#23504) remove when getTransactionById is removed from the API
  @nowarn("cat=deprecation")
  def getTransactionByIdClaims(
      request: GetTransactionByIdRequest
  ): List[RequiredClaim[GetTransactionByIdRequest]] =
    request.transactionFormat.toList
      .flatMap(
        RequiredClaims.transactionFormatClaims[GetTransactionByIdRequest]
      ) ::: RequiredClaims.readAsForAllParties[GetTransactionByIdRequest](
      request.requestingParties
    )
}
