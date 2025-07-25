// Copyright (c) 2025 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.canton.crypto.provider.symbolic

import cats.data.EitherT
import com.daml.nonempty.NonEmpty
import com.digitalasset.canton.concurrent.DirectExecutionContext
import com.digitalasset.canton.config.ProcessingTimeout
import com.digitalasset.canton.crypto.*
import com.digitalasset.canton.crypto.store.memory.{
  InMemoryCryptoPrivateStore,
  InMemoryCryptoPublicStore,
}
import com.digitalasset.canton.crypto.store.{CryptoPrivateStore, CryptoPublicStore}
import com.digitalasset.canton.lifecycle.FutureUnlessShutdown
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.version.ReleaseProtocolVersion
import com.google.protobuf.ByteString

import scala.concurrent.ExecutionContext

class SymbolicCrypto(
    pureCrypto: SymbolicPureCrypto,
    privateCrypto: SymbolicPrivateCrypto,
    cryptoPrivateStore: CryptoPrivateStore,
    cryptoPublicStore: CryptoPublicStore,
    timeouts: ProcessingTimeout,
    loggerFactory: NamedLoggerFactory,
)(implicit ec: ExecutionContext)
    extends Crypto(
      pureCrypto,
      privateCrypto,
      cryptoPrivateStore,
      cryptoPublicStore,
      timeouts,
      loggerFactory,
    ) {

  private def processE[E, A](
      description: String
  )(fn: TraceContext => EitherT[FutureUnlessShutdown, E, A]): A =
    process(description)(fn(_).valueOr(err => sys.error(s"Failed operation $description: $err")))

  private def process[A](description: String)(fn: TraceContext => FutureUnlessShutdown[A]): A =
    TraceContext.withNewTraceContext("process") { implicit traceContext =>
      timeouts.default.await(description) {
        fn(traceContext)
          .onShutdown(sys.error("aborted due to shutdown"))
      }
    }

  /** Generates a new symbolic signing keypair and stores the public key in the public store */
  def generateSymbolicSigningKey(
      name: Option[String] = None,
      usage: NonEmpty[Set[SigningKeyUsage]],
  ): SigningPublicKey =
    processE("generate symbolic signing key") { implicit traceContext =>
      // We don't care about the signing key scheme in symbolic crypto
      generateSigningKey(usage = usage, name = name.map(KeyName.tryCreate))
    }

  /** Generates a new symbolic signing keypair but does not store it in the public store */
  def newSymbolicSigningKeyPair(
      usage: NonEmpty[Set[SigningKeyUsage]]
  ): SigningKeyPair =
    processE("generate symbolic signing keypair") { implicit traceContext =>
      // We don't care about the signing key scheme in symbolic crypto
      privateCrypto
        .generateSigningKeypair(SigningKeySpec.EcCurve25519, usage)
    }

  def generateSymbolicEncryptionKey(
      name: Option[String] = None
  ): EncryptionPublicKey =
    processE("generate symbolic encryption key") { implicit traceContext =>
      // We don't care about the encryption key specification in symbolic crypto
      generateEncryptionKey(name = name.map(KeyName.tryCreate))
    }

  def newSymbolicEncryptionKeyPair(): EncryptionKeyPair =
    processE("generate symbolic encryption keypair") { implicit traceContext =>
      // We don't care about the encryption key specification in symbolic crypto
      privateCrypto
        .generateEncryptionKeypair(privateCrypto.encryptionKeySpecs.default)
    }

  def sign(
      hash: Hash,
      signingKeyId: Fingerprint,
      usage: NonEmpty[Set[SigningKeyUsage]],
  ): Signature =
    processE("symbolic signing") { implicit traceContext =>
      privateCrypto.sign(hash, signingKeyId, usage)
    }

  def setRandomKeysFlag(newValue: Boolean): Unit =
    privateCrypto.setRandomKeysFlag(newValue)
}

object SymbolicCrypto {

  def signature(signature: ByteString, signedBy: Fingerprint): Signature =
    SymbolicPureCrypto.createSignature(signature, signedBy, 0xffffffff)

  def emptySignature: Signature =
    signature(ByteString.EMPTY, Fingerprint.create(ByteString.EMPTY))

  def create(
      releaseProtocolVersion: ReleaseProtocolVersion,
      timeouts: ProcessingTimeout,
      loggerFactory: NamedLoggerFactory,
  ): SymbolicCrypto = {
    implicit val ec: ExecutionContext =
      DirectExecutionContext(loggerFactory.getLogger(this.getClass))

    val pureCrypto = new SymbolicPureCrypto()
    val cryptoPublicStore = new InMemoryCryptoPublicStore(loggerFactory)
    val cryptoPrivateStore = new InMemoryCryptoPrivateStore(releaseProtocolVersion, loggerFactory)
    val privateCrypto =
      new SymbolicPrivateCrypto(pureCrypto, cryptoPrivateStore, timeouts, loggerFactory)

    new SymbolicCrypto(
      pureCrypto,
      privateCrypto,
      cryptoPrivateStore,
      cryptoPublicStore,
      timeouts,
      loggerFactory,
    )
  }

}
