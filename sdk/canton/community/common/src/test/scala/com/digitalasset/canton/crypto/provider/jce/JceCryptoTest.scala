// Copyright (c) 2025 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.canton.crypto.provider.jce

import com.digitalasset.canton.config
import com.digitalasset.canton.config.CryptoProvider.Jce
import com.digitalasset.canton.config.{CachingConfigs, CryptoConfig, PositiveFiniteDuration}
import com.digitalasset.canton.crypto.*
import com.digitalasset.canton.crypto.CryptoTestHelper.TestMessage
import com.digitalasset.canton.crypto.SigningKeySpec.EcSecp256k1
import com.digitalasset.canton.crypto.kms.CommunityKmsFactory
import com.digitalasset.canton.crypto.store.CryptoPrivateStoreFactory
import com.digitalasset.canton.lifecycle.FutureUnlessShutdown
import com.digitalasset.canton.resource.MemoryStorage
import com.digitalasset.canton.tracing.NoReportingTracerProvider
import com.google.protobuf.ByteString
import monocle.macros.syntax.lens.*
import org.scalatest.wordspec.AsyncWordSpec

class JceCryptoTest
    extends AsyncWordSpec
    with SigningTest
    with EncryptionTest
    with PrivateKeySerializationTest
    with PasswordBasedEncryptionTest
    with RandomTest
    with PublicKeyValidationTest
    with CryptoKeyFormatMigrationTest {

  "JceCrypto" can {

    // use a short duration to verify that a Java key is removed from the cache promptly
    lazy val javaKeyCacheDuration = PositiveFiniteDuration.ofSeconds(4)

    def jceCrypto(): FutureUnlessShutdown[Crypto] =
      Crypto
        .create(
          CryptoConfig(provider = Jce),
          CachingConfigs.defaultSessionEncryptionKeyCacheConfig
            .focus(_.senderCache.expireAfterTimeout)
            .replace(javaKeyCacheDuration),
          CachingConfigs.defaultPublicKeyConversionCache.copy(expireAfterAccess =
            config.NonNegativeFiniteDuration(javaKeyCacheDuration.underlying)
          ),
          new MemoryStorage(loggerFactory, timeouts),
          CryptoPrivateStoreFactory.withoutKms(wallClock, parallelExecutionContext),
          CommunityKmsFactory, // Does not matter for the test as we do not use KMS
          testedReleaseProtocolVersion,
          nonStandardConfig = false,
          futureSupervisor,
          wallClock,
          executionContext,
          timeouts,
          loggerFactory,
          NoReportingTracerProvider,
        )
        .valueOrFail("failed to create crypto")

    behave like migrationTest(
      // No legacy keys for secp256k1
      Jce.signingKeys.supported.filterNot(_ == EcSecp256k1),
      Jce.encryptionKeys.supported,
      jceCrypto(),
    )

    behave like signingProvider(
      Jce.signingKeys.supported,
      Jce.signingAlgorithms.supported,
      Jce.supportedSignatureFormats,
      jceCrypto(),
    )
    behave like encryptionProvider(
      Jce.encryptionAlgorithms.supported,
      Jce.symmetric.supported,
      jceCrypto(),
    )
    behave like privateKeySerializerProvider(
      Jce.signingKeys.supported,
      Jce.encryptionKeys.supported,
      jceCrypto(),
    )

    forAll(
      Jce.encryptionAlgorithms.supported.filter(_.supportDeterministicEncryption)
    ) { encryptionAlgorithmSpec =>
      forAll(encryptionAlgorithmSpec.supportedEncryptionKeySpecs.forgetNE) { keySpec =>
        s"Deterministic hybrid encrypt " +
          s"with $encryptionAlgorithmSpec and a $keySpec key" should {

            val newCrypto = jceCrypto()

            behave like hybridEncrypt(
              keySpec,
              (message, publicKey) =>
                newCrypto.map(crypto =>
                  crypto.pureCrypto.encryptDeterministicWith(
                    message,
                    publicKey,
                    encryptionAlgorithmSpec,
                  )
                ),
              newCrypto,
            )

            "yield the same ciphertext for the same encryption" in {
              val message = TestMessage(ByteString.copyFromUtf8("foobar"))
              for {
                crypto <- jceCrypto()
                publicKey <- getEncryptionPublicKey(crypto, keySpec)
                encrypted1 = crypto.pureCrypto
                  .encryptDeterministicWith(
                    message,
                    publicKey,
                    encryptionAlgorithmSpec,
                  )
                  .valueOrFail("encrypt")
                _ = assert(message.bytes != encrypted1.ciphertext)
                encrypted2 = crypto.pureCrypto
                  .encryptDeterministicWith(
                    message,
                    publicKey,
                    encryptionAlgorithmSpec,
                  )
                  .valueOrFail("encrypt")
                _ = assert(message.bytes != encrypted2.ciphertext)
              } yield encrypted1.ciphertext shouldEqual encrypted2.ciphertext
            }
          }
      }
    }

    behave like randomnessProvider(jceCrypto().map(_.pureCrypto))

    behave like pbeProvider(
      Jce.pbkdf.valueOrFail("no PBKDF schemes configured").supported,
      Jce.symmetric.supported,
      jceCrypto().map(_.pureCrypto),
    )

    behave like keyValidationProvider(
      Jce.signingKeys.supported,
      Jce.encryptionKeys.supported,
      Jce.supportedCryptoKeyFormats,
      jceCrypto().failOnShutdown,
      javaKeyCacheDuration,
    )
  }
}
