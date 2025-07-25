// Copyright (c) 2025 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.ledger.rxjava.grpc;

import static com.daml.ledger.javaapi.data.EventUtils.firstExercisedEvent;
import static com.daml.ledger.javaapi.data.EventUtils.singleCreatedEvent;

import com.daml.ledger.api.v2.CommandServiceGrpc;
import com.daml.ledger.api.v2.CommandServiceOuterClass;
import com.daml.ledger.javaapi.data.*;
import com.daml.ledger.javaapi.data.codegen.Created;
import com.daml.ledger.javaapi.data.codegen.Exercised;
import com.daml.ledger.javaapi.data.codegen.Update;
import com.daml.ledger.rxjava.CommandClient;
import com.daml.ledger.rxjava.grpc.helpers.StubHelper;
import io.grpc.Channel;
import io.reactivex.Single;
import java.util.Optional;
import org.checkerframework.checker.nullness.qual.NonNull;

public class CommandClientImpl implements CommandClient {

  private final CommandServiceGrpc.CommandServiceFutureStub serviceStub;

  public CommandClientImpl(@NonNull Channel channel, @NonNull Optional<String> accessToken) {
    this.serviceStub =
        StubHelper.authenticating(CommandServiceGrpc.newFutureStub(channel), accessToken);
  }

  @Override
  public Single<String> submitAndWait(CommandsSubmission submission) {
    CommandServiceOuterClass.SubmitAndWaitRequest request =
        SubmitAndWaitRequest.toProto(submission);
    return Single.fromFuture(
            StubHelper.authenticating(this.serviceStub, submission.getAccessToken())
                .submitAndWait(request))
        .map(CommandServiceOuterClass.SubmitAndWaitResponse::getUpdateId);
  }

  @Override
  public Single<Transaction> submitAndWaitForTransaction(
      CommandsSubmission submission, TransactionFormat transactionFormat) {
    CommandServiceOuterClass.SubmitAndWaitForTransactionRequest request =
        new SubmitAndWaitForTransactionRequest(submission, transactionFormat).toProto();

    return Single.fromFuture(
            StubHelper.authenticating(this.serviceStub, submission.getAccessToken())
                .submitAndWaitForTransaction(request))
        .map(CommandServiceOuterClass.SubmitAndWaitForTransactionResponse::getTransaction)
        .map(Transaction::fromProto);
  }

  // Method will be removed in 3.4
  @Deprecated
  @Override
  public Single<TransactionTree> submitAndWaitForTransactionTree(CommandsSubmission submission) {
    CommandServiceOuterClass.SubmitAndWaitRequest request =
        SubmitAndWaitRequest.toProto(submission);

    return Single.fromFuture(
            StubHelper.authenticating(this.serviceStub, submission.getAccessToken())
                .submitAndWaitForTransactionTree(request))
        .map(CommandServiceOuterClass.SubmitAndWaitForTransactionTreeResponse::getTransaction)
        .map(TransactionTree::fromProto);
  }

  @Override
  public <U> Single<U> submitAndWaitForResult(
      @NonNull UpdateSubmission<U> submission, TransactionFormat transactionFormat) {
    return submission
        .getUpdate()
        .foldUpdate(
            new Update.FoldUpdate<>() {
              @Override
              public <CtId> Single<U> created(Update.CreateUpdate<CtId, U> create) {
                var transaction =
                    submitAndWaitForTransaction(
                        submission.toCommandsSubmission(), transactionFormat);
                return transaction.map(
                    tx -> {
                      var createdEvent = singleCreatedEvent(tx.getEvents());
                      return create.k.apply(
                          Created.fromEvent(create.createdContractId, createdEvent));
                    });
              }

              @Override
              public <R> Single<U> exercised(Update.ExerciseUpdate<R, U> exercise) {
                var transactionTree =
                    submitAndWaitForTransactionTree(submission.toCommandsSubmission());
                return transactionTree.map(
                    txTree -> {
                      var exercisedEvent = firstExercisedEvent(txTree);
                      return exercise.k.apply(
                          Exercised.fromEvent(exercise.returnTypeDecoder, exercisedEvent));
                    });
              }
            });
  }
}
