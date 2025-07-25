// Copyright (c) 2025 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.canton.http.endpoints

import com.daml.jwt.Jwt
import com.daml.logging.LoggingContextOf
import com.daml.logging.LoggingContextOf.withEnrichedLoggingContext
import com.digitalasset.canton.http.Endpoints.{ET, IntoEndpointsError}
import com.digitalasset.canton.http.EndpointsCompanion.*
import com.digitalasset.canton.http.json.*
import com.digitalasset.canton.http.json.v1.ContractsService.SearchResult
import com.digitalasset.canton.http.json.v1.{ContractsService, RouteSetup}
import com.digitalasset.canton.http.metrics.HttpApiMetrics
import com.digitalasset.canton.http.util.FutureUtil.{either, eitherT}
import com.digitalasset.canton.http.util.JwtParties.*
import com.digitalasset.canton.http.util.Logging.{InstanceUUID, RequestID}
import com.digitalasset.canton.http.{
  ActiveContract,
  FetchRequest,
  GetActiveContractsRequest,
  JwtPayload,
  OkResponse,
  SyncResponse,
  json,
}
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.tracing.NoTracing
import com.digitalasset.daml.lf.value.Value as LfValue
import org.apache.pekko.NotUsed
import org.apache.pekko.http.scaladsl.model.*
import org.apache.pekko.stream.scaladsl.{Flow, Source}
import scalaz.std.scalaFuture.*
import scalaz.syntax.std.option.*
import scalaz.syntax.traverse.*
import scalaz.{-\/, EitherT, \/, \/-}
import spray.json.*

import scala.concurrent.{ExecutionContext, Future}

private[http] final class ContractList(
    routeSetup: RouteSetup,
    decoder: ApiJsonDecoder,
    contractsService: ContractsService,
    val loggerFactory: NamedLoggerFactory,
)(implicit ec: ExecutionContext)
    extends NamedLogging
    with NoTracing {
  import ContractList.*
  import routeSetup.*, RouteSetup.*
  import com.digitalasset.canton.http.json.JsonProtocol.*
  import com.digitalasset.canton.http.util.ErrorOps.*

  def fetch(req: HttpRequest)(implicit
      lc: LoggingContextOf[InstanceUUID with RequestID],
      ec: ExecutionContext,
      metrics: HttpApiMetrics,
  ): ET[SyncResponse[JsValue]] =
    for {
      parseAndDecodeTimer <- getParseAndDecodeTimerCtx()
      input <- inputJsValAndJwtPayload(req): ET[(Jwt, JwtPayload, JsValue)]

      (jwt, jwtPayload, reqBody) = input

      jsVal <- withJwtPayloadLoggingContext(jwtPayload) { _ => implicit lc =>
        logger.debug(s"/v1/fetch reqBody: $reqBody, ${lc.makeString}")
        for {
          fr <-
            either(
              SprayJson
                .decode[FetchRequest[JsValue]](reqBody)
                .liftErr[Error](InvalidUserInput.apply)
            )
              .flatMap(
                _.traverseLocator(
                  decoder
                    .decodeContractLocatorKey(_, jwt)
                    .liftErr(InvalidUserInput.apply)
                )
              ): ET[FetchRequest[LfValue]]
          _ <- EitherT.pure(parseAndDecodeTimer.stop())
          _ = logger.debug(s"/v1/fetch fr: $fr, ${lc.makeString}")

          _ <- either(ensureReadAsAllowedByJwt(fr.readAs, jwtPayload))
          ac <- contractsService.lookup(jwt, jwtPayload, fr)

          jsVal <- either(
            ac.cata(x => toJsValue(x), \/-(JsNull))
          ): ET[JsValue]
        } yield jsVal
      }
    } yield OkResponse(jsVal)

  def retrieveAll(req: HttpRequest)(implicit
      lc: LoggingContextOf[InstanceUUID with RequestID],
      metrics: HttpApiMetrics,
  ): Future[Error \/ SearchResult[Error \/ JsValue]] = for {
    parseAndDecodeTimer <- Future(
      metrics.incomingJsonParsingAndValidationTimer.startAsync()
    )
    res <- inputAndJwtPayload[JwtPayload](req).run.map {
      _.map { case (jwt, jwtPayload, _) =>
        parseAndDecodeTimer.stop()
        withJwtPayloadLoggingContext(jwtPayload) { _ => implicit lc =>
          val result: SearchResult[
            ContractsService.Error \/ ActiveContract.ResolvedCtTyId[LfValue]
          ] =
            contractsService.retrieveAll(jwt, jwtPayload)

          SyncResponse.covariant.map(result) { source =>
            source
              .via(handleSourceFailure)
              .map(_.flatMap(lfAcToJsValue)): Source[Error \/ JsValue, NotUsed]
          }
        }
      }
    }
  } yield res

  def query(req: HttpRequest)(implicit
      lc: LoggingContextOf[InstanceUUID with RequestID],
      metrics: HttpApiMetrics,
  ): Future[Error \/ SearchResult[Error \/ JsValue]] = {
    for {
      it <- inputAndJwtPayload[JwtPayload](req).leftMap(identity[Error])
      (jwt, jwtPayload, reqBody) = it
      res <- withJwtPayloadLoggingContext(jwtPayload) { _ => implicit lc =>
        val res = for {
          cmd <- SprayJson
            .decode[GetActiveContractsRequest](reqBody)
            .liftErr[Error](InvalidUserInput.apply)
          _ <- ensureReadAsAllowedByJwt(cmd.readAs, jwtPayload)
        } yield withEnrichedLoggingContext(
          LoggingContextOf.label[GetActiveContractsRequest],
          "cmd" -> cmd.toString,
        ).run { implicit lc =>
          logger.debug(s"Processing a query request, ${lc.makeString}")
          contractsService
            .search(jwt, jwtPayload, cmd)
            .map(
              SyncResponse.covariant.map(_)(
                _.via(handleSourceFailure)
                  .map(_.flatMap(toJsValue[ActiveContract.ResolvedCtTyId[JsValue]](_)))
              )
            )
        }
        eitherT(res.sequence)
      }
    } yield res
  }.run

  private def handleSourceFailure[E, A](implicit
      E: IntoEndpointsError[E]
  ): Flow[E \/ A, Error \/ A, NotUsed] =
    Flow
      .fromFunction((_: E \/ A).leftMap(E.run))
      .recover(Error.fromThrowable andThen (-\/(_)))
}

private[endpoints] object ContractList {
  import json.JsonProtocol.*
  import com.digitalasset.canton.http.util.ErrorOps.*

  private def lfValueToJsValue(a: LfValue): Error \/ JsValue =
    \/.attempt(LfValueCodec.apiValueToJsValue(a))(identity).liftErr(ServerError.fromMsg)

  private def lfAcToJsValue(a: ActiveContract.ResolvedCtTyId[LfValue]): Error \/ JsValue =
    for {
      b <- a.traverse(lfValueToJsValue): Error \/ ActiveContract.ResolvedCtTyId[JsValue]
      c <- toJsValue(b)
    } yield c

  private def toJsValue[A: JsonWriter](a: A): Error \/ JsValue =
    SprayJson.encode(a).liftErr(ServerError.fromMsg)
}
