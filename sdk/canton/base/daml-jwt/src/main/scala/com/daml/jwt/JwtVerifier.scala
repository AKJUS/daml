// Copyright (c) 2025 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.jwt

import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.interfaces.RSAKeyProvider
import scalaz.\/
import scalaz.syntax.show.*
import scalaz.syntax.traverse.*

import java.io.File
import java.security.interfaces.{ECPublicKey, RSAPublicKey}

abstract class JwtVerifierBase {
  def verify(jwt: Jwt): Error \/ DecodedJwt[String]
}

class JwtVerifier(val verifier: com.auth0.jwt.interfaces.JWTVerifier) extends JwtVerifierBase {

  def verify(jwt: Jwt): Error \/ DecodedJwt[String] =
    // The auth0 library verification already fails if the token has expired,
    // but we still need to do manual expiration checks in ongoing streams
    \/.attempt(verifier.verify(jwt.value))(e => Error(Symbol("verify"), e.getMessage))
      // TODO (i26199)- possible place to add expiration time check
      .map(a => DecodedJwt(header = a.getHeader, payload = a.getPayload))
      .flatMap(base64Decode)

  private def base64Decode(jwt: DecodedJwt[String]): Error \/ DecodedJwt[String] =
    jwt.traverse(Base64.decode).leftMap(e => Error(Symbol("base64Decode"), e.shows))

}

// HMAC256 validator factory
object HMAC256Verifier extends Leeway {
  def apply(
      secret: String,
      jwtTimestampLeeway: Option[JwtTimestampLeeway] = None,
  ): Error \/ JwtVerifier =
    \/.attempt {
      val algorithm = Algorithm.HMAC256(secret)
      val verifier = getVerifier(algorithm, jwtTimestampLeeway)
      new JwtVerifier(verifier)
    }(e => Error(Symbol("HMAC256"), e.getMessage))
}

// ECDSA validator factory
object ECDSAVerifier extends Leeway {
  def apply(
      algorithm: Algorithm,
      jwtTimestampLeeway: Option[JwtTimestampLeeway] = None,
  ): Error \/ JwtVerifier =
    \/.attempt {
      val verifier = getVerifier(algorithm, jwtTimestampLeeway)
      new JwtVerifier(verifier)
    }(e => Error(Symbol(algorithm.getName), e.getMessage))

  def fromCrtFile(
      path: String,
      algorithmPublicKey: ECPublicKey => Algorithm,
      jwtTimestampLeeway: Option[JwtTimestampLeeway] = None,
  ): Error \/ JwtVerifier =
    for {
      key <- \/.fromEither(
        KeyUtils
          .readECPublicKeyFromCrt(new File(path))
          .toEither
      )
        .leftMap(e => Error(Symbol("fromCrtFile"), e.getMessage))
      verifier <- ECDSAVerifier(algorithmPublicKey(key), jwtTimestampLeeway)
    } yield verifier
}

// RSA256 validator factory
object RSA256Verifier extends Leeway {
  def apply(
      publicKey: RSAPublicKey,
      jwtTimestampLeeway: Option[JwtTimestampLeeway] = None,
  ): Error \/ JwtVerifier =
    \/.attempt {

      val algorithm = Algorithm.RSA256(publicKey, null)
      val verifier = getVerifier(algorithm, jwtTimestampLeeway)
      new JwtVerifier(verifier)
    }(e => Error(Symbol("RSA256"), e.getMessage))

  def apply(keyProvider: RSAKeyProvider): Error \/ JwtVerifier =
    \/.attempt {

      val algorithm = Algorithm.RSA256(keyProvider)
      val verifier = getVerifier(algorithm)
      new JwtVerifier(verifier)
    }(e => Error(Symbol("RSA256"), e.getMessage))

  def apply(
      keyProvider: RSAKeyProvider,
      jwtTimestampLeeway: Option[JwtTimestampLeeway],
  ): Error \/ JwtVerifier =
    \/.attempt {

      val algorithm = Algorithm.RSA256(keyProvider)
      val verifier = getVerifier(algorithm, jwtTimestampLeeway)
      new JwtVerifier(verifier)
    }(e => Error(Symbol("RSA256"), e.getMessage))

  /** Create a RSA256 validator with the key loaded from the given file. The file is assumed to be a
    * X509 encoded certificate. These typically have the .crt file extension.
    */
  def fromCrtFile(
      path: String,
      jwtTimestampLeeway: Option[JwtTimestampLeeway] = None,
  ): Error \/ JwtVerifier =
    for {
      rsaKey <- \/.fromEither(
        KeyUtils
          .readRSAPublicKeyFromCrt(new File(path))
          .toEither
      )
        .leftMap(e => Error(Symbol("fromCrtFile"), e.getMessage))
      verifier <- RSA256Verifier.apply(rsaKey, jwtTimestampLeeway)
    } yield verifier
}
