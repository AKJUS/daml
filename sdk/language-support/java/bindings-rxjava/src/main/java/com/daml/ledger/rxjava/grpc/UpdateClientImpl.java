// Copyright (c) 2025 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.ledger.rxjava.grpc;

import com.daml.grpc.adapter.ExecutionSequencerFactory;
import com.daml.ledger.api.v2.UpdateServiceGrpc;
import com.daml.ledger.api.v2.UpdateServiceOuterClass;
import com.daml.ledger.javaapi.data.*;
import com.daml.ledger.rxjava.UpdateClient;
import com.daml.ledger.rxjava.grpc.helpers.StubHelper;
import com.daml.ledger.rxjava.util.ClientPublisherFlowable;
import io.grpc.Channel;
import io.reactivex.Flowable;
import io.reactivex.Single;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Future;

public final class UpdateClientImpl implements UpdateClient {
  private final UpdateServiceGrpc.UpdateServiceStub serviceStub;
  private final UpdateServiceGrpc.UpdateServiceFutureStub serviceFutureStub;
  private final ExecutionSequencerFactory sequencerFactory;

  public UpdateClientImpl(
      Channel channel, ExecutionSequencerFactory sequencerFactory, Optional<String> accessToken) {
    this.sequencerFactory = sequencerFactory;
    this.serviceStub = StubHelper.authenticating(UpdateServiceGrpc.newStub(channel), accessToken);
    this.serviceFutureStub =
        StubHelper.authenticating(UpdateServiceGrpc.newFutureStub(channel), accessToken);
  }

  private static <T> Iterable<T> toIterable(Optional<T> o) {
    return o.map(Collections::singleton).orElseGet(Collections::emptySet);
  }

  private Flowable<Transaction> extractTransactions(
      UpdateServiceOuterClass.GetUpdatesRequest request, Optional<String> accessToken) {
    return ClientPublisherFlowable.create(
            request,
            StubHelper.authenticating(this.serviceStub, accessToken)::getUpdates,
            sequencerFactory)
        .map(GetUpdatesResponse::fromProto)
        .map(GetUpdatesResponse::getTransaction)
        .concatMapIterable(UpdateClientImpl::toIterable);
  }

  // TransactionFilter will be removed in 3.4, remove
  @SuppressWarnings("deprecation")
  private Flowable<Transaction> getTransactions(
      Long begin,
      Optional<Long> end,
      TransactionFilter filter,
      boolean verbose,
      Optional<String> accessToken) {
    UpdateServiceOuterClass.GetUpdatesRequest request =
        new GetUpdatesRequest(begin, end, filter, verbose).toProto();
    return extractTransactions(request, accessToken);
  }

  // TransactionFilter will be removed in 3.4, remove
  @Deprecated
  @Override
  public Flowable<Transaction> getTransactions(
      Long begin, Optional<Long> end, TransactionFilter filter, boolean verbose) {
    return getTransactions(begin, end, filter, verbose, Optional.empty());
  }

  // TransactionFilter will be removed in 3.4, remove
  @Deprecated
  @Override
  public Flowable<Transaction> getTransactions(
      Long begin,
      Optional<Long> end,
      TransactionFilter filter,
      boolean verbose,
      String accessToken) {
    return getTransactions(begin, end, filter, verbose, Optional.of(accessToken));
  }

  private Flowable<Transaction> getTransactions(
      ContractFilter<?> contractFilter,
      Long begin,
      Optional<Long> end,
      Set<String> parties,
      boolean verbose,
      Optional<String> accessToken) {
    TransactionFormat transactionFormat =
        contractFilter.withVerbose(verbose).transactionFormat(Optional.of(parties));
    return getTransactions(begin, end, transactionFormat, accessToken);
  }

  @Override
  public Flowable<Transaction> getTransactions(
      ContractFilter<?> contractFilter,
      Long begin,
      Optional<Long> end,
      Set<String> parties,
      boolean verbose) {
    return getTransactions(contractFilter, begin, end, parties, verbose, Optional.empty());
  }

  private Flowable<Transaction> getTransactions(
      Long begin,
      Optional<Long> end,
      TransactionFormat transactionFormat,
      Optional<String> accessToken) {
    UpdateFormat updateFormat =
        new UpdateFormat(Optional.of(transactionFormat), Optional.empty(), Optional.empty());
    UpdateServiceOuterClass.GetUpdatesRequest request =
        new GetUpdatesRequest(begin, end, updateFormat).toProto();
    return extractTransactions(request, accessToken);
  }

  @Override
  public Flowable<Transaction> getTransactions(
      Long begin, Optional<Long> end, TransactionFormat transactionFormat) {
    return getTransactions(begin, end, transactionFormat, Optional.empty());
  }

  @Override
  public Flowable<Transaction> getTransactions(
      Long begin, Optional<Long> end, TransactionFormat transactionFormat, String accessToken) {
    return getTransactions(begin, end, transactionFormat, Optional.of(accessToken));
  }

  // TransactionTree will be removed in 3.4, remove
  @SuppressWarnings("deprecation")
  private Flowable<TransactionTree> extractTransactionTrees(
      UpdateServiceOuterClass.GetUpdatesRequest request, Optional<String> accessToken) {
    return ClientPublisherFlowable.create(
            request,
            StubHelper.authenticating(this.serviceStub, accessToken)::getUpdateTrees,
            sequencerFactory)
        .map(GetUpdateTreesResponse::fromProto)
        .map(GetUpdateTreesResponse::getTransactionTree)
        .concatMapIterable(UpdateClientImpl::toIterable);
  }

  // TransactionTree will be removed in 3.4, remove
  @SuppressWarnings("deprecation")
  private Flowable<TransactionTree> getTransactionsTrees(
      Long begin,
      Optional<Long> end,
      TransactionFilter filter,
      boolean verbose,
      Optional<String> accessToken) {
    UpdateServiceOuterClass.GetUpdatesRequest request =
        new GetUpdatesRequest(begin, end, filter, verbose).toProtoLegacy();
    return extractTransactionTrees(request, accessToken);
  }

  // Method will be removed in 3.4
  @Deprecated
  @Override
  public Flowable<TransactionTree> getTransactionsTrees(
      Long begin, Optional<Long> end, TransactionFilter filter, boolean verbose) {
    return getTransactionsTrees(begin, end, filter, verbose, Optional.empty());
  }

  // Method will be removed in 3.4
  @Deprecated
  @Override
  public Flowable<TransactionTree> getTransactionsTrees(
      Long begin,
      Optional<Long> end,
      TransactionFilter filter,
      boolean verbose,
      String accessToken) {
    return getTransactionsTrees(begin, end, filter, verbose, Optional.of(accessToken));
  }

  // TransactionTree will be removed in 3.4, remove
  @SuppressWarnings("deprecation")
  private Single<TransactionTree> extractTransactionTree(
      Future<UpdateServiceOuterClass.GetTransactionTreeResponse> future) {
    return Single.fromFuture(future)
        .map(GetTransactionTreeResponse::fromProto)
        .map(GetTransactionTreeResponse::getTransactionTree);
  }

  // Method will be removed in 3.4, remove
  @SuppressWarnings("deprecation")
  private Single<TransactionTree> getTransactionTreeByOffset(
      Long offset, Set<String> requestingParties, Optional<String> accessToken) {
    UpdateServiceOuterClass.GetTransactionByOffsetRequest request =
        UpdateServiceOuterClass.GetTransactionByOffsetRequest.newBuilder()
            .setOffset(offset)
            .addAllRequestingParties(requestingParties)
            .build();
    return extractTransactionTree(
        StubHelper.authenticating(this.serviceFutureStub, accessToken)
            .getTransactionTreeByOffset(request));
  }

  // Method will be removed in 3.4, use getTransactionByOffset
  @Deprecated
  @Override
  public Single<TransactionTree> getTransactionTreeByOffset(
      Long offset, Set<String> requestingParties) {
    return getTransactionTreeByOffset(offset, requestingParties, Optional.empty());
  }

  // Method will be removed in 3.4, use getTransactionByOffset
  @Deprecated
  @Override
  public Single<TransactionTree> getTransactionTreeByOffset(
      Long offset, Set<String> requestingParties, String accessToken) {
    return getTransactionTreeByOffset(offset, requestingParties, Optional.of(accessToken));
  }

  // Method will be removed in 3.4, remove
  @SuppressWarnings("deprecation")
  private Single<TransactionTree> getTransactionTreeById(
      String transactionId, Set<String> requestingParties, Optional<String> accessToken) {
    UpdateServiceOuterClass.GetTransactionByIdRequest request =
        UpdateServiceOuterClass.GetTransactionByIdRequest.newBuilder()
            .setUpdateId(transactionId)
            .addAllRequestingParties(requestingParties)
            .build();
    return extractTransactionTree(
        StubHelper.authenticating(this.serviceFutureStub, accessToken)
            .getTransactionTreeById(request));
  }

  // Method will be removed in 3.4, use getTransactionById instead
  @Deprecated
  @Override
  public Single<TransactionTree> getTransactionTreeById(
      String transactionId, Set<String> requestingParties) {
    return getTransactionTreeById(transactionId, requestingParties, Optional.empty());
  }

  // Method will be removed in 3.4, use getTransactionById instead
  @Deprecated
  @Override
  public Single<TransactionTree> getTransactionTreeById(
      String transactionId, Set<String> requestingParties, String accessToken) {
    return getTransactionTreeById(transactionId, requestingParties, Optional.of(accessToken));
  }

  private Single<Transaction> extractTransactionFromUpdate(
      Future<UpdateServiceOuterClass.GetUpdateResponse> future) {
    return Single.fromFuture(future)
        .map(UpdateServiceOuterClass.GetUpdateResponse::getTransaction)
        .map(Transaction::fromProto);
  }

  private TransactionFormat getTransactionFormat(Set<String> requestingParties) {
    Map<String, Filter> partyFilters =
        requestingParties.stream()
            .collect(
                java.util.stream.Collectors.toMap(
                    party -> party,
                    party ->
                        new CumulativeFilter(
                            Map.of(),
                            Map.of(),
                            Optional.of(Filter.Wildcard.HIDE_CREATED_EVENT_BLOB))));
    EventFormat eventFormat = new EventFormat(partyFilters, Optional.empty(), true);

    return new TransactionFormat(eventFormat, TransactionShape.ACS_DELTA);
  }

  private Single<Transaction> getTransactionByOffset(
      Long offset, TransactionFormat transactionFormat, Optional<String> accessToken) {
    UpdateServiceOuterClass.GetUpdateByOffsetRequest request =
        UpdateServiceOuterClass.GetUpdateByOffsetRequest.newBuilder()
            .setOffset(offset)
            .setUpdateFormat(
                new UpdateFormat(Optional.of(transactionFormat), Optional.empty(), Optional.empty())
                    .toProto())
            .build();
    return extractTransactionFromUpdate(
        StubHelper.authenticating(this.serviceFutureStub, accessToken).getUpdateByOffset(request));
  }

  @Override
  public Single<Transaction> getTransactionByOffset(Long offset, Set<String> requestingParties) {
    return getTransactionByOffset(
        offset, getTransactionFormat(requestingParties), Optional.empty());
  }

  @Override
  public Single<Transaction> getTransactionByOffset(
      Long offset, Set<String> requestingParties, String accessToken) {
    return getTransactionByOffset(
        offset, getTransactionFormat(requestingParties), Optional.of(accessToken));
  }

  @Override
  public Single<Transaction> getTransactionByOffset(
      Long offset, TransactionFormat transactionFormat) {
    return getTransactionByOffset(offset, transactionFormat, Optional.empty());
  }

  @Override
  public Single<Transaction> getTransactionByOffset(
      Long offset, TransactionFormat transactionFormat, String accessToken) {
    return getTransactionByOffset(offset, transactionFormat, Optional.of(accessToken));
  }

  private Single<Transaction> getTransactionById(
      String transactionId, TransactionFormat transactionFormat, Optional<String> accessToken) {

    UpdateServiceOuterClass.GetUpdateByIdRequest request =
        UpdateServiceOuterClass.GetUpdateByIdRequest.newBuilder()
            .setUpdateId(transactionId)
            .setUpdateFormat(
                new UpdateFormat(Optional.of(transactionFormat), Optional.empty(), Optional.empty())
                    .toProto())
            .build();
    return extractTransactionFromUpdate(
        StubHelper.authenticating(this.serviceFutureStub, accessToken).getUpdateById(request));
  }

  @Override
  public Single<Transaction> getTransactionById(
      String transactionId, Set<String> requestingParties) {
    return getTransactionById(
        transactionId, getTransactionFormat(requestingParties), Optional.empty());
  }

  @Override
  public Single<Transaction> getTransactionById(
      String transactionId, Set<String> requestingParties, String accessToken) {
    return getTransactionById(
        transactionId, getTransactionFormat(requestingParties), Optional.of(accessToken));
  }

  @Override
  public Single<Transaction> getTransactionById(
      String transactionId, TransactionFormat transactionFormat) {
    return getTransactionById(transactionId, transactionFormat, Optional.empty());
  }

  @Override
  public Single<Transaction> getTransactionById(
      String transactionId, TransactionFormat transactionFormat, String accessToken) {
    return getTransactionById(transactionId, transactionFormat, Optional.of(accessToken));
  }
}
