// Copyright (c) 2025 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.canton.http.json.v2

import com.daml.ledger.api.v2 as lapi
import com.daml.ledger.api.v2.admin.object_meta.ObjectMeta
import com.daml.ledger.api.v2.trace_context.TraceContext
import com.daml.ledger.api.v2.transaction_filter.TransactionShape
import com.daml.ledger.api.v2.value.Identifier
import com.daml.ledger.api.v2.{offset_checkpoint, reassignment, state_service, transaction_filter}
import com.digitalasset.base.error.utils.DecodedCantonError
import com.digitalasset.base.error.{DamlErrorWithDefiniteAnswer, RpcError}
import com.digitalasset.canton.http.json.v2.CirceRelaxedCodec.deriveRelaxedCodec
import com.digitalasset.canton.http.json.v2.JsSchema.DirectScalaPbRwImplicits.*
import com.digitalasset.canton.http.json.v2.JsSchema.JsEvent.{CreatedEvent, ExercisedEvent}
import com.google.protobuf
import com.google.protobuf.field_mask.FieldMask
import com.google.protobuf.struct.Struct
import com.google.protobuf.util.JsonFormat
import io.circe.generic.extras.Configuration
import io.circe.generic.extras.semiauto.deriveConfiguredCodec
import io.circe.generic.semiauto.deriveCodec
import io.circe.{Codec, Decoder, Encoder, Json}
import scalapb.{GeneratedEnumCompanion, UnknownFieldSet}
import sttp.tapir.CodecFormat.TextPlain
import sttp.tapir.Schema.SName
import sttp.tapir.SchemaType.SProduct
import sttp.tapir.generic.Derived
import sttp.tapir.generic.auto.*
import sttp.tapir.{DecodeResult, Schema, SchemaType}

import java.time.Instant
import java.util.Base64
import scala.annotation.nowarn
import scala.concurrent.duration.Duration
import scala.util.Try

/** JSON wrappers that do not belong to a particular service */
object JsSchema {

  implicit val config: Configuration = Configuration.default.copy(
    useDefaults = true
  )

  def stringEncoderForEnum[T <: scalapb.GeneratedEnum](): Encoder[T] =
    Encoder.encodeString.contramap[T](shape => shape.companion.fromValue(shape.value).name)

  def stringDecoderForEnum[T <: scalapb.GeneratedEnum]()(implicit
      enumCompanion: GeneratedEnumCompanion[T]
  ): Decoder[T] =
    Decoder.decodeString.emap { v =>
      enumCompanion
        .fromName(v)
        .toRight(
          s"Unrecognized enum value $v. Supported values: ${enumCompanion.values.map(_.name).mkString("[", ", ", "]")}"
        )
    }

  final case class JsTransaction(
      updateId: String,
      commandId: String,
      workflowId: String,
      effectiveAt: com.google.protobuf.timestamp.Timestamp,
      events: Seq[JsEvent.Event],
      offset: Long,
      synchronizerId: String,
      traceContext: Option[TraceContext],
      recordTime: com.google.protobuf.timestamp.Timestamp,
      externalTransactionHash: Option[String],
  )

  final case class JsTransactionTree(
      updateId: String,
      commandId: String,
      workflowId: String,
      effectiveAt: Option[protobuf.timestamp.Timestamp],
      offset: Long,
      eventsById: Map[Int, JsTreeEvent.TreeEvent],
      synchronizerId: String,
      traceContext: Option[TraceContext],
      recordTime: protobuf.timestamp.Timestamp,
  )

  final case class JsInterfaceView(
      interfaceId: Identifier,
      viewStatus: com.google.rpc.status.Status,
      viewValue: Option[Json],
  )
  object JsReassignmentEvent {
    sealed trait JsReassignmentEvent

    final case class JsAssignmentEvent(
        source: String,
        target: String,
        reassignmentId: String,
        submitter: String,
        reassignmentCounter: Long,
        createdEvent: CreatedEvent,
    ) extends JsReassignmentEvent

    final case class JsUnassignedEvent(value: reassignment.UnassignedEvent)
        extends JsReassignmentEvent

  }

  final case class JsReassignment(
      updateId: String,
      commandId: String,
      workflowId: String,
      offset: Long,
      events: Seq[JsReassignmentEvent.JsReassignmentEvent],
      traceContext: Option[com.daml.ledger.api.v2.trace_context.TraceContext],
      recordTime: com.google.protobuf.timestamp.Timestamp,
  )

  object JsServicesCommonCodecs {
    implicit val jsTransactionRW: Codec[JsTransaction] = deriveConfiguredCodec

    implicit val unassignedEventRW: Codec[reassignment.UnassignedEvent] = deriveRelaxedCodec

    implicit val wildcardFilterRW: Codec[transaction_filter.WildcardFilter] =
      deriveRelaxedCodec
    implicit val templateFilterRW: Codec[transaction_filter.TemplateFilter] =
      deriveRelaxedCodec
    implicit val interfaceFilterRW: Codec[transaction_filter.InterfaceFilter] =
      deriveRelaxedCodec

    implicit val identifierFilterWildcardRW
        : Codec[transaction_filter.CumulativeFilter.IdentifierFilter.WildcardFilter] =
      deriveRelaxedCodec

    implicit val identifierFilterTemplateRW
        : Codec[transaction_filter.CumulativeFilter.IdentifierFilter.TemplateFilter] =
      deriveRelaxedCodec
    implicit val identifierFilterInterfaceRW
        : Codec[transaction_filter.CumulativeFilter.IdentifierFilter.InterfaceFilter] =
      deriveRelaxedCodec

    implicit val identifierFilterRW: Codec[transaction_filter.CumulativeFilter.IdentifierFilter] =
      deriveConfiguredCodec // ADT

    implicit val cumulativeFilterRW: Codec[transaction_filter.CumulativeFilter] =
      deriveRelaxedCodec

    implicit val filtersRW: Codec[transaction_filter.Filters] = deriveRelaxedCodec
    // TODO(#23504) remove
    @nowarn("cat=deprecation")
    implicit val transactionFilterRW: Codec[transaction_filter.TransactionFilter] =
      deriveRelaxedCodec
    implicit val eventFormatRW: Codec[transaction_filter.EventFormat] = deriveRelaxedCodec

    implicit val transactionShapeEncoder: Encoder[TransactionShape] =
      stringEncoderForEnum()

    implicit val transactionShapeDecoder: Decoder[TransactionShape] =
      stringDecoderForEnum()

    implicit val transactionFormatRW: Codec[transaction_filter.TransactionFormat] =
      deriveRelaxedCodec

    implicit val jsReassignment: Codec[JsReassignment] = deriveConfiguredCodec

    implicit val jsReassignmentEventRW: Codec[JsReassignmentEvent.JsReassignmentEvent] =
      deriveConfiguredCodec

    implicit val jsReassignmentEventJsUnassignedEventRW
        : Codec[JsReassignmentEvent.JsUnassignedEvent] =
      deriveConfiguredCodec

    implicit val jsReassignmentEventJsAssignedEventRW
        : Codec[JsReassignmentEvent.JsAssignmentEvent] =
      deriveConfiguredCodec

    implicit val participantPermissionEncoder: Encoder[state_service.ParticipantPermission] =
      stringEncoderForEnum()

    implicit val participantPermissionDecoder: Decoder[state_service.ParticipantPermission] =
      stringDecoderForEnum()

    implicit val jsPrefetchContractKeyRW: Codec[js.PrefetchContractKey] = deriveConfiguredCodec

    implicit val unrecognizedShape: Schema[transaction_filter.TransactionShape.Unrecognized] =
      Schema.derived

    implicit val participantPermissionSchema: Schema[state_service.ParticipantPermission] =
      Schema.string

    implicit val transactionShapeSchema: Schema[transaction_filter.TransactionShape] = Schema.string

    implicit val identifierFilterSchema
        : Schema[transaction_filter.CumulativeFilter.IdentifierFilter] =
      Schema.oneOfWrapped

    implicit val filtersByPartyMapSchema: Schema[Map[String, transaction_filter.Filters]] =
      Schema.schemaForMap[transaction_filter.Filters]

    implicit val eventFormatSchema: Schema[transaction_filter.EventFormat] =
      Schema.derived

    @SuppressWarnings(Array("org.wartremover.warts.Product", "org.wartremover.warts.Serializable"))
    implicit val jsReassignmentEventSchema: Schema[JsReassignmentEvent.JsReassignmentEvent] =
      Schema.oneOfWrapped

    implicit val topologyTransactionParticipantAuthorizationAddedSchema
        : Schema[lapi.topology_transaction.ParticipantAuthorizationAdded] =
      Schema.derived
    implicit val topologyTransactionParticipantAuthorizationChangedSchema
        : Schema[lapi.topology_transaction.ParticipantAuthorizationChanged] =
      Schema.derived
    implicit val topologyTransactionParticipantAuthorizationRevokedSchema
        : Schema[lapi.topology_transaction.ParticipantAuthorizationRevoked] =
      Schema.derived

    @SuppressWarnings(Array("org.wartremover.warts.Product", "org.wartremover.warts.Serializable"))
    implicit val topologyEventEventSchema: Schema[lapi.topology_transaction.TopologyEvent.Event] =
      Schema.oneOfWrapped.name(
        SName("TopologyEventEvent")
      ) // Name selected manually otherwise it is generated as Event1

  }

  object JsEvent {
    sealed trait Event

    final case class CreatedEvent(
        offset: Long,
        nodeId: Int,
        contractId: String,
        templateId: Identifier,
        contractKey: Option[Json],
        createArgument: Option[Json],
        createdEventBlob: protobuf.ByteString,
        interfaceViews: Seq[JsInterfaceView],
        witnessParties: Seq[String],
        signatories: Seq[String],
        observers: Seq[String],
        createdAt: protobuf.timestamp.Timestamp,
        packageName: String,
    ) extends Event

    final case class ArchivedEvent(
        offset: Long,
        nodeId: Int,
        contractId: String,
        templateId: Identifier,
        witnessParties: Seq[String],
        packageName: String,
        implementedInterfaces: Seq[Identifier],
    ) extends Event

    final case class ExercisedEvent(
        offset: Long,
        nodeId: Int,
        contractId: String,
        templateId: Identifier,
        interfaceId: Option[Identifier],
        choice: String,
        choiceArgument: Json,
        actingParties: Seq[String],
        consuming: Boolean,
        witnessParties: Seq[String],
        lastDescendantNodeId: Int,
        exerciseResult: Json,
        packageName: String,
        implementedInterfaces: Seq[Identifier],
    ) extends Event
  }

  object JsTreeEvent {
    sealed trait TreeEvent

    final case class CreatedTreeEvent(value: CreatedEvent) extends TreeEvent

    final case class ExercisedTreeEvent(value: ExercisedEvent) extends TreeEvent
  }

  final case class JsCantonError(
      code: String,
      cause: String,
      correlationId: Option[String],
      traceId: Option[String],
      context: Map[String, String],
      resources: Seq[(String, String)],
      errorCategory: Int,
      grpcCodeValue: Option[Int],
      retryInfo: Option[Duration],
      definiteAnswer: Option[Boolean],
  )

  object JsCantonError {
    import DirectScalaPbRwImplicits.*
    implicit val rw: Codec[JsCantonError] = deriveCodec
    val ledgerApiErrorContext: String = "ledger_api_error"
    val tokenProblemError: (String, String) = (ledgerApiErrorContext -> "invalid token")

    def fromErrorCode(damlError: RpcError): JsCantonError = JsCantonError(
      code = damlError.code.id,
      cause = damlError.cause,
      correlationId = damlError.correlationId,
      traceId = damlError.traceId,
      context = damlError.context,
      resources = damlError.resources.map { case (k, v) => (k.asString, v) },
      errorCategory = damlError.code.category.asInt,
      grpcCodeValue = damlError.code.category.grpcCode.map(_.value()),
      retryInfo = damlError.code.category.retryable.map(_.duration),
      definiteAnswer = damlError match {
        case errorWithDefiniteAnswer: DamlErrorWithDefiniteAnswer =>
          Some(errorWithDefiniteAnswer.definiteAnswer)
        case _ => None
      },
    )

    def fromDecodedCantonError(decodedCantonError: DecodedCantonError): JsCantonError =
      JsCantonError(
        code = decodedCantonError.code.id,
        errorCategory = decodedCantonError.code.category.asInt,
        grpcCodeValue = decodedCantonError.code.category.grpcCode.map(_.value()),
        cause = decodedCantonError.cause,
        correlationId = decodedCantonError.correlationId,
        traceId = decodedCantonError.traceId,
        context = decodedCantonError.context,
        resources = decodedCantonError.resources.map { case (k, v) => (k.toString, v) },
        retryInfo = decodedCantonError.code.category.retryable.map(_.duration),
        definiteAnswer = decodedCantonError.definiteAnswerO,
      )

  }
  object DirectScalaPbRwImplicits {
    import sttp.tapir.json.circe.*
    import sttp.tapir.generic.auto.*

    implicit val om: Codec[ObjectMeta] = deriveRelaxedCodec
    implicit val traceContext: Codec[TraceContext] = deriveRelaxedCodec
    implicit val encodeDuration: Encoder[Duration] =
      Encoder.encodeString.contramap[Duration](_.toString)

    implicit val decodeDuration: Decoder[Duration] = Decoder.decodeString.emapTry { str =>
      Try(Duration.create(str))
    }

    implicit val encodeByteString: Encoder[protobuf.ByteString] =
      Encoder.encodeString.contramap[protobuf.ByteString] { v =>
        Base64.getEncoder.encodeToString(v.toByteArray)
      }
    implicit val decodeByteString: Decoder[protobuf.ByteString] = Decoder.decodeString.emapTry {
      str =>
        Try(protobuf.ByteString.copyFrom(Base64.getDecoder.decode(str)))
    }

    // proto classes use default values
    implicit val durationRW: Codec[protobuf.duration.Duration] = deriveConfiguredCodec

    implicit val field: Codec[scalapb.UnknownFieldSet.Field] = deriveConfiguredCodec
    implicit val unknownFieldSet: Codec[scalapb.UnknownFieldSet] = deriveConfiguredCodec

    implicit val fieldMask: Codec[FieldMask] = deriveConfiguredCodec

    implicit val encodeTimestamp: Encoder[protobuf.timestamp.Timestamp] =
      Encoder.encodeInstant.contramap[protobuf.timestamp.Timestamp] { timestamp =>
        Instant.ofEpochSecond(timestamp.seconds, timestamp.nanos.toLong)
      }

    implicit val decodeTimestamp: Decoder[protobuf.timestamp.Timestamp] =
      Decoder.decodeInstant.map(v => protobuf.timestamp.Timestamp(v.getEpochSecond, v.getNano))

    implicit val timestampCodec: sttp.tapir.Codec[String, protobuf.timestamp.Timestamp, TextPlain] =
      sttp.tapir.Codec.instant.mapDecode { (time: Instant) =>
        DecodeResult.Value(protobuf.timestamp.Timestamp(time.getEpochSecond, time.getNano))
      }(timestamp => Instant.ofEpochSecond(timestamp.seconds, timestamp.nanos.toLong))

    implicit val decodeStruct: Decoder[protobuf.struct.Struct] =
      Decoder.decodeJson.map { json =>
        val builder = protobuf.Struct.newBuilder
        JsonFormat.parser.ignoringUnknownFields.merge(json.toString(), builder)
        protobuf.struct.Struct.fromJavaProto(builder.build)
      }

    implicit val encodeStruct: Encoder[protobuf.struct.Struct] =
      Encoder.encodeJson.contramap { struct =>
        val printer = JsonFormat.printer()
        val jsonString = printer.print(Struct.toJavaProto(struct))
        io.circe.parser.parse(jsonString) match {
          case Right(json) => json
          case Left(error) => throw new IllegalStateException(s"Failed to parse JSON: $error")
        }
      }

    implicit val encodeIdentifier: Encoder[com.daml.ledger.api.v2.value.Identifier] =
      Encoder.encodeString.contramap { identifier =>
        IdentifierConverter.toJson(identifier)
      }

    implicit val decodeIdentifier: Decoder[com.daml.ledger.api.v2.value.Identifier] =
      Decoder.decodeString.map(IdentifierConverter.fromJson)

    implicit val jsEvent: Codec[JsEvent.Event] = deriveConfiguredCodec
    implicit val jsCreatedEvent: Codec[JsEvent.CreatedEvent] = deriveConfiguredCodec
    implicit val jsArchivedEvent: Codec[JsEvent.ArchivedEvent] = deriveConfiguredCodec
    implicit val jsExercisedEvent: Codec[JsEvent.ExercisedEvent] = deriveConfiguredCodec

    implicit val any: Codec[com.google.protobuf.any.Any] = deriveConfiguredCodec

    implicit val jsInterfaceView: Codec[JsInterfaceView] = deriveConfiguredCodec

    implicit val jsTransactionTree: Codec[JsTransactionTree] = deriveConfiguredCodec

    implicit val jsSubmitAndWaitForTransactionTreeResponse
        : Codec[JsSubmitAndWaitForTransactionTreeResponse] = deriveConfiguredCodec
    implicit val jsTreeEvent: Codec[JsTreeEvent.TreeEvent] = deriveConfiguredCodec
    implicit val jsExercisedTreeEvent: Codec[JsTreeEvent.ExercisedTreeEvent] = deriveConfiguredCodec
    implicit val jsCreatedTreeEvent: Codec[JsTreeEvent.CreatedTreeEvent] = deriveConfiguredCodec

    implicit val offsetCheckpoint: Codec[offset_checkpoint.OffsetCheckpoint] = deriveRelaxedCodec
    implicit val offsetCheckpointSynchronizerTime: Codec[offset_checkpoint.SynchronizerTime] =
      deriveRelaxedCodec

    implicit val grpcStatusEncoder: Encoder[com.google.rpc.status.Status] = Encoder.instance {
      status =>
        import io.circe.syntax.*
        Json.obj(
          "code" -> status.code.asJson,
          "message" -> status.message.asJson,
          "details" -> status.details.asJson,
        )
    }

    implicit val grpcStatusDecoder: Decoder[com.google.rpc.status.Status] = Decoder.instance {
      cursor =>
        for {
          code <- cursor.downField("code").as[Int]
          message <- cursor.downField("message").as[String]
          details <- cursor.downField("details").as[Seq[com.google.protobuf.any.Any]]
        } yield com.google.rpc.status.Status(code, message, details)
    }

    // Schema mappings are added to align generated tapir docs with a circe mapping of ADTs
    implicit val byteStringSchema: Schema[protobuf.ByteString] = Schema(
      SchemaType.SString()
    )

    implicit val unknownFieldSetMapSchema: Schema[Map[Int, UnknownFieldSet.Field]] =
      Schema.schemaForMap[Int, UnknownFieldSet.Field](_.toString)
    implicit val unknownFieldSetSchema: Schema[scalapb.UnknownFieldSet] =
      Schema.derived[scalapb.UnknownFieldSet]

    implicit val identifierSchema: Schema[com.daml.ledger.api.v2.value.Identifier] = Schema.string

    implicit val durationSchema: Schema[protobuf.duration.Duration] =
      implicitly[Derived[Schema[protobuf.duration.Duration]]].value
        .modify(_.unknownFields)(
          _.copy(isOptional = true).description(
            "This field is automatically added as part of protobuf to json mapping"
          )
        )

    implicit val timestampSchema: Schema[protobuf.timestamp.Timestamp] = Schema.string

    implicit val structSchema: Schema[protobuf.struct.Struct] = Schema.any

    implicit val anySchema: Schema[com.google.protobuf.any.Any] =
      Schema.derived.name(Some(SName("ProtoAny")))

    implicit val statusSchema: Schema[com.google.rpc.status.Status] = {
      val baseSchema = Schema.derived[com.google.rpc.status.Status]
      // modified to preserve backwards compatibility with existing clients
      val adjustedType = baseSchema.schemaType match {
        case SProduct(fields) =>
          SProduct(
            fields.filter(
              _.name.name != "unknownFields"
            ) // This field is not needed and was introduced to generated code by mistake
          )
        case _ => sys.error("Error in JsSchema openapi generation expected SProduct")
      }
      baseSchema
        .name(Some(SName("JsStatus")))
        .copy(schemaType = adjustedType)

    }

    implicit val jsEventCreatedSchema: Schema[JsEvent.CreatedEvent] = Schema.derived

    @SuppressWarnings(Array("org.wartremover.warts.Product", "org.wartremover.warts.Serializable"))
    implicit val jsEventSchema: Schema[JsEvent.Event] =
      Schema.oneOfWrapped

    @SuppressWarnings(Array("org.wartremover.warts.Product", "org.wartremover.warts.Serializable"))
    implicit val jsTreeEventSchema: Schema[JsTreeEvent.TreeEvent] =
      Schema.oneOfWrapped

    implicit val eventsByIdSchema: Schema[Map[Int, JsTreeEvent.TreeEvent]] =
      Schema.schemaForMap[Int, JsTreeEvent.TreeEvent](_.toString)

    implicit val jsTransactionTreeSchema: Schema[JsTransactionTree] =
      Schema.derived

    implicit val identifierFilterSchema
        : Schema[transaction_filter.CumulativeFilter.IdentifierFilter] =
      Schema.oneOfWrapped

    implicit val valueSchema: Schema[com.google.protobuf.struct.Value] = Schema.any

  }
}
