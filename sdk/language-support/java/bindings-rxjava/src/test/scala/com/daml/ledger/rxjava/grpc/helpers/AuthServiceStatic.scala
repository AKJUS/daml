// Copyright (c) 2025 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.ledger.rxjava.grpc.helpers

import com.digitalasset.canton.auth.{AuthService, ClaimSet}
import com.digitalasset.canton.tracing.TraceContext

import scala.concurrent.Future

/** An AuthService that matches the value of the `Authorization` HTTP header against
  * a static map of header values to [[ClaimSet.Claims]].
  *
  * Note: This AuthService is meant to be used for testing purposes only.
  */
final class AuthServiceStatic(claims: PartialFunction[String, ClaimSet]) extends AuthService {
  override def decodeToken(authToken: Option[String], serviceName: String)(implicit
      traceContext: TraceContext
  ): Future[ClaimSet] = {
    Future.successful(
      authToken match {
        case Some(header) =>
          claims.lift(header.stripPrefix("Bearer ")).getOrElse(ClaimSet.Unauthenticated)
        case _ => ClaimSet.Unauthenticated
      }
    )
  }
}

object AuthServiceStatic {
  def apply(claims: PartialFunction[String, ClaimSet]) = new AuthServiceStatic(claims)
}
