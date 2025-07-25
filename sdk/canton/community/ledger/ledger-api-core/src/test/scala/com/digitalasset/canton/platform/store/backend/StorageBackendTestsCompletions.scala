// Copyright (c) 2025 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.canton.platform.store.backend

import com.digitalasset.canton.data.{CantonTimestamp, Offset}
import com.digitalasset.canton.platform.indexer.parallel.{PostPublishData, PublishSource}
import com.digitalasset.canton.topology.SynchronizerId
import com.digitalasset.canton.tracing.SerializableTraceContextConverter.SerializableTraceContextExtension
import com.digitalasset.canton.tracing.{SerializableTraceContext, TraceContext}
import com.digitalasset.daml.lf.data.Ref
import com.digitalasset.daml.lf.data.Time.Timestamp
import com.google.protobuf.duration.Duration
import org.scalatest.Inside
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.util.UUID

private[backend] trait StorageBackendTestsCompletions
    extends Matchers
    with Inside
    with StorageBackendSpec {
  this: AnyFlatSpec =>

  behavior of "StorageBackend (completions)"

  import StorageBackendTestValues.*

  it should "correctly find completions by offset range" in {
    TraceContext.withNewTraceContext("test") { aTraceContext =>
      val party = someParty
      val userId = someUserId
      val emptyTraceContext = SerializableTraceContext(TraceContext.empty).toDamlProto.toByteArray
      val serializableTraceContext = SerializableTraceContext(aTraceContext).toDamlProto.toByteArray

      val dtos = Vector(
        dtoCompletion(offset(1), submitters = Set(party)),
        dtoCompletion(offset(2), submitters = Set(party), traceContext = emptyTraceContext),
        dtoCompletion(
          offset(3),
          submitters = Set(party),
          traceContext = serializableTraceContext,
        ),
      )

      executeSql(backend.parameter.initializeParameters(someIdentityParams, loggerFactory))
      executeSql(ingest(dtos, _))
      executeSql(updateLedgerEnd(offset(3), 3L))
      val completions0to2 = executeSql(
        backend.completion
          .commandCompletions(
            Offset.firstOffset,
            offset(2),
            userId,
            Set(party),
            limit = 10,
          )
      )
      val completions1to2 = executeSql(
        backend.completion
          .commandCompletions(
            offset(2),
            offset(2),
            userId,
            Set(party),
            limit = 10,
          )
      )
      val completions0to9 = executeSql(
        backend.completion
          .commandCompletions(
            Offset.firstOffset,
            offset(9),
            userId,
            Set(party),
            limit = 10,
          )
      )

      completions0to2 should have length 2
      completions1to2 should have length 1
      completions0to9 should have length 3

      completions0to9.head.completionResponse.completion.map(_.traceContext) shouldBe Some(None)
      completions0to9(1).completionResponse.completion.map(_.traceContext) shouldBe Some(None)
      completions0to9(2).completionResponse.completion.map(_.traceContext) shouldBe Some(
        Some(SerializableTraceContext(aTraceContext).toDamlProto)
      )
    }
  }

  it should "correctly persist and retrieve user IDs" in {
    val party = someParty
    val userId = someUserId

    val dtos = Vector(
      dtoCompletion(offset(1), submitters = Set(party))
    )

    executeSql(backend.parameter.initializeParameters(someIdentityParams, loggerFactory))
    executeSql(ingest(dtos, _))
    executeSql(updateLedgerEnd(offset(1), 1L))

    val completions = executeSql(
      backend.completion
        .commandCompletions(
          Offset.firstOffset,
          offset(1),
          userId,
          Set(party),
          limit = 10,
        )
    )

    completions should not be empty
    completions.head.completionResponse.completion should not be empty
    completions.head.completionResponse.completion.toList.head.userId should be(
      userId
    )
  }

  it should "correctly persist and retrieve submission IDs" in {
    val party = someParty
    val submissionId = Some(someSubmissionId)

    val dtos = Vector(
      dtoCompletion(offset(1), submitters = Set(party), submissionId = submissionId),
      dtoCompletion(offset(2), submitters = Set(party), submissionId = None),
    )

    executeSql(backend.parameter.initializeParameters(someIdentityParams, loggerFactory))
    executeSql(ingest(dtos, _))
    executeSql(updateLedgerEnd(offset(2), 2L))
    val completions = executeSql(
      backend.completion
        .commandCompletions(
          Offset.firstOffset,
          offset(2),
          someUserId,
          Set(party),
          limit = 10,
        )
    ).toList

    completions should have length 2
    inside(completions) { case List(completionWithSubmissionId, completionWithoutSubmissionId) =>
      completionWithSubmissionId.completionResponse.completion should not be empty
      completionWithSubmissionId.completionResponse.completion.toList.head.submissionId should be(
        someSubmissionId
      )
      completionWithoutSubmissionId.completionResponse.completion should not be empty
      completionWithoutSubmissionId.completionResponse.completion.toList.head.submissionId should be(
        ""
      )
    }
  }

  it should "correctly persist and retrieve command deduplication offsets" in {
    val party = someParty
    val anOffset = 1L

    val dtos = Vector(
      dtoCompletion(
        offset(1),
        submitters = Set(party),
        deduplicationOffset = Some(anOffset),
      ),
      dtoCompletion(offset(2), submitters = Set(party), deduplicationOffset = None),
    )

    executeSql(backend.parameter.initializeParameters(someIdentityParams, loggerFactory))
    executeSql(ingest(dtos, _))

    executeSql(updateLedgerEnd(offset(2), 2L))
    val completions = executeSql(
      backend.completion
        .commandCompletions(
          Offset.firstOffset,
          offset(2),
          someUserId,
          Set(party),
          limit = 10,
        )
    ).toList

    completions should have length 2
    inside(completions) {
      case List(completionWithDeduplicationOffset, completionWithoutDeduplicationOffset) =>
        completionWithDeduplicationOffset.completionResponse.completion should not be empty
        completionWithDeduplicationOffset.completionResponse.completion.toList.head.deduplicationPeriod.deduplicationOffset should be(
          Some(anOffset)
        )
        completionWithoutDeduplicationOffset.completionResponse.completion should not be empty
        completionWithoutDeduplicationOffset.completionResponse.completion.toList.head.deduplicationPeriod.deduplicationOffset should not be defined
    }
  }

  it should "correctly persist and retrieve command deduplication durations" in {
    val party = someParty
    val seconds = 100L
    val nanos = 10
    val expectedDuration = Duration.of(seconds, nanos)

    val dtos = Vector(
      dtoCompletion(
        offset(1),
        submitters = Set(party),
        deduplicationDurationSeconds = Some(seconds),
        deduplicationDurationNanos = Some(nanos),
      ),
      dtoCompletion(
        offset(2),
        submitters = Set(party),
        deduplicationDurationSeconds = None,
        deduplicationDurationNanos = None,
      ),
    )

    executeSql(backend.parameter.initializeParameters(someIdentityParams, loggerFactory))
    executeSql(ingest(dtos, _))

    executeSql(updateLedgerEnd(offset(2), 2L))
    val completions = executeSql(
      backend.completion
        .commandCompletions(
          Offset.firstOffset,
          offset(2),
          someUserId,
          Set(party),
          limit = 10,
        )
    ).toList

    completions should have length 2
    inside(completions) {
      case List(completionWithDeduplicationOffset, completionWithoutDeduplicationOffset) =>
        completionWithDeduplicationOffset.completionResponse.completion should not be empty
        completionWithDeduplicationOffset.completionResponse.completion.toList.head.deduplicationPeriod.deduplicationDuration should be(
          Some(expectedDuration)
        )
        completionWithoutDeduplicationOffset.completionResponse.completion should not be empty
        completionWithoutDeduplicationOffset.completionResponse.completion.toList.head.deduplicationPeriod.deduplicationDuration should not be defined
    }
  }

  it should "correctly persist and retrieve submitters/act_as" in {
    val party = someParty
    val party2 = someParty2
    val party3 = someParty3

    val dtos = Vector(
      dtoCompletion(
        offset(1),
        submitters = Set(party, party2, party3),
      ),
      dtoCompletion(
        offset(2),
        submitters = Set(party),
      ),
    )

    executeSql(backend.parameter.initializeParameters(someIdentityParams, loggerFactory))
    executeSql(ingest(dtos, _))

    executeSql(updateLedgerEnd(offset(2), 2L))
    val completions = executeSql(
      backend.completion
        .commandCompletions(
          Offset.firstOffset,
          offset(2),
          someUserId,
          Set(party, party2),
          limit = 10,
        )
    ).toList

    completions should have length 2
    inside(completions) { case List(completion1, completion2) =>
      completion1.completionResponse.completion should not be empty
      completion1.completionResponse.completion.toList.head.actAs.toSet should be(
        Set(party, party2)
      )
      completion2.completionResponse.completion should not be empty
      completion2.completionResponse.completion.toList.head.actAs.toSet should be(
        Set(party)
      )
    }
  }

  it should "fail on broken command deduplication durations in DB" in {
    val party = someParty
    val seconds = 100L
    val nanos = 10

    val expectedErrorMessage =
      "One of deduplication duration seconds and nanos has been provided " +
        "but they must be either both provided or both absent"

    val dtos1 = Vector(
      dtoCompletion(
        offset(1),
        submitters = Set(party),
        deduplicationDurationSeconds = Some(seconds),
        deduplicationDurationNanos = None,
      )
    )

    executeSql(backend.parameter.initializeParameters(someIdentityParams, loggerFactory))
    executeSql(ingest(dtos1, _))
    executeSql(updateLedgerEnd(offset(1), 1L))
    val caught = intercept[IllegalArgumentException](
      executeSql(
        backend.completion.commandCompletions(
          Offset.firstOffset,
          offset(1),
          someUserId,
          Set(party),
          limit = 10,
        )
      )
    )

    caught.getMessage should be(expectedErrorMessage)

    val dtos2 = Vector(
      dtoCompletion(
        offset(2),
        submitters = Set(party),
        deduplicationDurationSeconds = None,
        deduplicationDurationNanos = Some(nanos),
      )
    )

    executeSql(ingest(dtos2, _))
    executeSql(updateLedgerEnd(offset(2), 2L))
    val caught2 = intercept[IllegalArgumentException](
      executeSql(
        backend.completion.commandCompletions(
          offset(2),
          offset(2),
          someUserId,
          Set(party),
          limit = 10,
        )
      )
    )
    caught2.getMessage should be(expectedErrorMessage)
  }

  it should "correctly retrieve completions for post processing recovery" in {
    val messageUuid = UUID.randomUUID()
    val commandId = UUID.randomUUID().toString
    val publicationTime = Timestamp.now()
    val recordTime = Timestamp.now().addMicros(15)
    val submissionId = UUID.randomUUID().toString
    val dtos = Vector(
      dtoCompletion(
        offset(1)
      ),
      dtoCompletion(
        offset = offset(2),
        submitters = Set(someParty),
        commandId = commandId,
        userId = "userid1",
        submissionId = Some(submissionId),
        synchronizerId = "x::synchronizer1",
        messageUuid = Some(messageUuid.toString),
        publicationTime = publicationTime,
        isTransaction = true,
      ),
      dtoCompletion(
        offset = offset(5),
        submitters = Set(someParty),
        commandId = commandId,
        userId = "userid1",
        submissionId = Some(submissionId),
        synchronizerId = "x::synchronizer1",
        messageUuid = Some(messageUuid.toString),
        publicationTime = publicationTime,
        isTransaction = false,
      ),
      dtoCompletion(
        offset = offset(9),
        submitters = Set(someParty),
        commandId = commandId,
        userId = "userid1",
        submissionId = Some(submissionId),
        synchronizerId = "x::synchronizer1",
        recordTime = recordTime,
        messageUuid = None,
        updateId = None,
        publicationTime = publicationTime,
        isTransaction = true,
      ),
    )

    executeSql(backend.parameter.initializeParameters(someIdentityParams, loggerFactory))
    executeSql(ingest(dtos, _))
    executeSql(
      backend.completion.commandCompletionsForRecovery(offset(2), offset(10))
    ) shouldBe Vector(
      PostPublishData(
        submissionSynchronizerId = SynchronizerId.tryFromString("x::synchronizer1"),
        publishSource = PublishSource.Local(messageUuid),
        userId = Ref.UserId.assertFromString("userid1"),
        commandId = Ref.CommandId.assertFromString(commandId),
        actAs = Set(someParty),
        offset = offset(2),
        publicationTime = CantonTimestamp(publicationTime),
        submissionId = Some(Ref.SubmissionId.assertFromString(submissionId)),
        accepted = true,
        traceContext = TraceContext.empty,
      ),
      PostPublishData(
        submissionSynchronizerId = SynchronizerId.tryFromString("x::synchronizer1"),
        publishSource = PublishSource.Sequencer(
          sequencerTimestamp = CantonTimestamp(recordTime)
        ),
        userId = Ref.UserId.assertFromString("userid1"),
        commandId = Ref.CommandId.assertFromString(commandId),
        actAs = Set(someParty),
        offset = offset(9),
        publicationTime = CantonTimestamp(publicationTime),
        submissionId = Some(Ref.SubmissionId.assertFromString(submissionId)),
        accepted = false,
        traceContext = TraceContext.empty,
      ),
    )
  }
}
