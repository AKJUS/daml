// Copyright (c) 2025 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.canton.synchronizer.sequencing.service

import cats.data.EitherT
import com.digitalasset.canton.crypto.provider.symbolic.SymbolicCrypto
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.lifecycle.FutureUnlessShutdown
import com.digitalasset.canton.sequencer.api.v30
import com.digitalasset.canton.sequencing.*
import com.digitalasset.canton.sequencing.SequencerTestUtils.MockMessageContent
import com.digitalasset.canton.sequencing.client.SequencerSubscription
import com.digitalasset.canton.sequencing.client.SequencerSubscriptionError.SequencedEventError
import com.digitalasset.canton.sequencing.protocol.*
import com.digitalasset.canton.sequencing.traffic.TrafficReceipt
import com.digitalasset.canton.store.SequencedEventStore.SequencedEventWithTraceContext
import com.digitalasset.canton.synchronizer.sequencer.errors.CreateSubscriptionError
import com.digitalasset.canton.topology.{
  DefaultTestIdentities,
  ParticipantId,
  SynchronizerId,
  UniqueIdentifier,
}
import com.digitalasset.canton.tracing.SerializableTraceContext
import com.digitalasset.canton.{BaseTest, HasExecutionContext}
import io.grpc.stub.ServerCallStreamObserver
import org.scalatest.wordspec.AnyWordSpec

import scala.concurrent.Await
import scala.concurrent.duration.*

class GrpcManagedSubscriptionTest extends AnyWordSpec with BaseTest with HasExecutionContext {

  @SuppressWarnings(Array("org.wartremover.warts.Var", "org.wartremover.warts.Null"))
  private class Env {
    val sequencerSubscription = mock[SequencerSubscription[SequencedEventError]]
    val synchronizerId = SynchronizerId(
      UniqueIdentifier.tryFromProtoPrimitive("da::default")
    ).toPhysical
    var handler: Option[SequencedEventOrErrorHandler[SequencedEventError]] = None
    val member = ParticipantId(DefaultTestIdentities.uid)
    val observer = mock[ServerCallStreamObserver[v30.SubscriptionResponse]]

    def createSequencerSubscription(
        newHandler: SequencedEventOrErrorHandler[SequencedEventError]
    ): EitherT[FutureUnlessShutdown, CreateSubscriptionError, SequencerSubscription[
      SequencedEventError
    ]] = {
      handler = Some(newHandler)
      EitherT.rightT[FutureUnlessShutdown, CreateSubscriptionError](sequencerSubscription)
    }

    def deliver(): Unit = {
      val message = MockMessageContent.toByteString
      val event = SignedContent(
        Deliver.create(
          None,
          CantonTimestamp.Epoch,
          synchronizerId,
          Some(MessageId.tryCreate("test-deliver")),
          Batch(
            List(
              ClosedEnvelope
                .create(message, Recipients.cc(member), Seq.empty, testedProtocolVersion)
            ),
            testedProtocolVersion,
          ),
          None,
          Option.empty[TrafficReceipt],
        ),
        SymbolicCrypto.emptySignature,
        None,
        testedProtocolVersion,
      )
      handler.fold(fail("handler not registered"))(h =>
        Await.result(h(Right(SequencedEventWithTraceContext(event)(traceContext))), 5.seconds)
      )
    }

    private def toSubscriptionResponseV30(event: SequencedSerializedEvent) =
      v30.SubscriptionResponse(
        signedSequencedEvent = event.signedEvent.toByteString,
        Some(SerializableTraceContext(event.traceContext).toProtoV30),
      )

    def createManagedSubscription() = {
      val subscription = new GrpcManagedSubscription(
        createSequencerSubscription,
        observer,
        member,
        None,
        timeouts,
        loggerFactory,
        toSubscriptionResponseV30,
      )
      subscription.initialize().futureValueUS
      subscription
    }
  }

  "GrpcManagedSubscription" should {
    "send received events" in new Env {
      createManagedSubscription()
      deliver()
      verify(observer).onNext(any[v30.SubscriptionResponse])
    }

    "if closed externally the observer is completed, the subscription is closed, but the closed callback is not called" in new Env {
      val subscription = createManagedSubscription()
      subscription.close()
      verify(sequencerSubscription).close()
      verify(observer).onCompleted()
    }
  }
}
