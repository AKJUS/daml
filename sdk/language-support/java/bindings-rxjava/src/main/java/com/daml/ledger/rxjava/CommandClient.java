// Copyright (c) 2025 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.ledger.rxjava;

import com.daml.ledger.javaapi.data.CommandsSubmission;
import com.daml.ledger.javaapi.data.Transaction;
import com.daml.ledger.javaapi.data.TransactionFormat;
import com.daml.ledger.javaapi.data.TransactionTree;
import com.daml.ledger.javaapi.data.UpdateSubmission;
import io.reactivex.Single;
import org.checkerframework.checker.nullness.qual.NonNull;

/** An RxJava version of {@link com.daml.ledger.api.v2.CommandServiceGrpc} */
@Deprecated
public interface CommandClient {
  Single<String> submitAndWait(CommandsSubmission submission);

  Single<Transaction> submitAndWaitForTransaction(
      CommandsSubmission submission, TransactionFormat transactionFormat);

  // Method will be removed in 3.4
  @Deprecated
  Single<TransactionTree> submitAndWaitForTransactionTree(CommandsSubmission submission);

  <U> Single<U> submitAndWaitForResult(
      @NonNull UpdateSubmission<U> submission, TransactionFormat transactionFormat);
}
