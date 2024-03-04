// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.canton.crypto.provider.tink

import cats.syntax.either.*
import cats.syntax.foldable.*
import com.digitalasset.canton.config.CommunityCryptoProvider.Tink
import com.digitalasset.canton.crypto.CryptoPureApiError.KeyParseAndValidateError
import com.digitalasset.canton.crypto.HkdfError.{HkdfHmacError, HkdfInternalError}
import com.digitalasset.canton.crypto.*
import com.digitalasset.canton.crypto.provider.CryptoKeyConverter
import com.digitalasset.canton.serialization.DeserializationError
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.ErrorUtil
import com.digitalasset.canton.util.ShowUtil.*
import com.digitalasset.canton.version.{HasVersionedToByteString, ProtocolVersion}
import com.google.crypto.tink
import com.google.crypto.tink.aead.AeadKeyTemplates
import com.google.crypto.tink.config.TinkConfig
import com.google.crypto.tink.subtle.{Hkdf, Random}
import com.google.crypto.tink.{KeysetHandle, proto as tinkproto}
import com.google.protobuf.ByteString

import java.security.GeneralSecurityException
import scala.collection.concurrent.TrieMap
import scala.reflect.{ClassTag, classTag}

class TinkPureCrypto private (
    keyConverter: CryptoKeyConverter,
    override val defaultSymmetricKeyScheme: SymmetricKeyScheme,
    override val defaultHashAlgorithm: HashAlgorithm,
) extends CryptoPureApi {

  // TODO(#15632): Make these real caches with an eviction rule
  // Cache for the public and private keyset deserialization result.
  // Note: We do not cache symmetric keys as we generate random keys for each new view.
  private val publicKeysetCache
      : TrieMap[Fingerprint, Either[KeyParseAndValidateError, KeysetHandle]] =
    TrieMap.empty
  private val privateKeysetCache
      : TrieMap[Fingerprint, Either[KeyParseAndValidateError, KeysetHandle]] =
    TrieMap.empty

  /** Parses and converts a public key to a tink public key handle.
    * We store the deserialization result in a cache.
    *
    * @return Either an error or the converted public keyset handle
    */
  private def parseAndGetPublicKey[E](
      publicKey: PublicKey,
      errFn: String => E,
  ): Either[E, KeysetHandle] = {
    val keyFormat = publicKey.format

    def getFromCacheOrDeserializeKey: Either[E, KeysetHandle] =
      // if the public key is already in cache it already has been deserialized and validated
      publicKeysetCache
        .getOrElseUpdate(
          publicKey.id, {
            for {
              // convert key to Tink format so that we can deserialize it
              tinkPublicKey <- keyConverter
                .convert(publicKey, CryptoKeyFormat.Tink)
                .leftMap(KeyParseAndValidateError)
              handle <- TinkKeyFormat
                .deserializeHandle(tinkPublicKey.key)
                .leftMap(err => KeyParseAndValidateError(s"Deserialization error: $err"))
            } yield handle
          },
        )
        .leftMap(err => errFn(s"Failed to deserialize ${publicKey.format} public key: $err"))

    if (Tink.supportedCryptoKeyFormats.contains(keyFormat))
      getFromCacheOrDeserializeKey
    else Left(errFn(s"$keyFormat key format not supported"))
  }

  /** Parses and converts a private or symmetric key to a tink keyset handle.
    * We store the deserialization result in a cache.
    *
    * @return Either an error or the converted tink keyset handle
    */
  private def parseAndGetPrivateKey[E](
      privateKey: CryptoKey,
      errFn: String => E,
  ): Either[E, KeysetHandle] =
    (for {
      // we are using Tink as the provider so we expect all private keys to be in Tink format
      _ <- CryptoKeyValidation.ensureFormat(
        privateKey.format,
        Set(CryptoKeyFormat.Tink),
        KeyParseAndValidateError,
      )
      keysetHandle <- privateKey match {
        case key: PrivateKey =>
          privateKeysetCache
            .getOrElseUpdate(
              key.id,
              TinkKeyFormat
                .deserializeHandle(privateKey.key)
                .leftMap(err => KeyParseAndValidateError(s"Deserialization error: $err")),
            )
        case _: SymmetricKey =>
          TinkKeyFormat
            .deserializeHandle(privateKey.key)
            .leftMap(err => KeyParseAndValidateError(s"Deserialization error: $err"))
        case _ => Left(KeyParseAndValidateError("Key is not a private or symmetric key"))
      }
    } yield keysetHandle)
      .leftMap(err => errFn(s"Failed to deserialize ${privateKey.format} private key: $err"))

  @SuppressWarnings(Array("org.wartremover.warts.AsInstanceOf"))
  private def getPrimitive[P: ClassTag, E](
      keysetHandle: KeysetHandle,
      errFn: String => E,
  ): Either[E, P] =
    Either
      .catchOnly[GeneralSecurityException](
        keysetHandle.getPrimitive(classTag[P].runtimeClass.asInstanceOf[Class[P]])
      )
      .leftMap(err => errFn(show"Failed to get primitive: $err"))

  private def encryptWith[M <: HasVersionedToByteString](
      message: M,
      encrypt: Array[Byte] => Array[Byte],
      version: ProtocolVersion,
  ): Either[EncryptionError, Encrypted[M]] = {
    val bytes = message.toByteString(version).toByteArray
    Either
      .catchOnly[GeneralSecurityException](encrypt(bytes))
      .bimap(
        err => EncryptionError.FailedToEncrypt(ErrorUtil.messageWithStacktrace(err)),
        enc => new Encrypted[M](ByteString.copyFrom(enc)),
      )
  }

  def encryptDeterministicWith[M <: HasVersionedToByteString](
      message: M,
      publicKey: EncryptionPublicKey,
      version: ProtocolVersion,
  )(implicit traceContext: TraceContext): Either[EncryptionError, AsymmetricEncrypted[M]] =
    Left(
      EncryptionError.UnsupportedSchemeForDeterministicEncryption(
        "Tink does not support deterministic encryption"
      )
    )

  private def decryptWith[M](
      encrypted: Encrypted[M],
      decrypt: Array[Byte] => Array[Byte],
      deserialize: ByteString => Either[DeserializationError, M],
  ): Either[DecryptionError, M] = decryptWith(encrypted.ciphertext, decrypt, deserialize)

  private def decryptWith[M](
      ciphertext: ByteString,
      decrypt: Array[Byte] => Array[Byte],
      deserialize: ByteString => Either[DeserializationError, M],
  ): Either[DecryptionError, M] =
    Either
      .catchOnly[GeneralSecurityException](decrypt(ciphertext.toByteArray))
      .leftMap(err => DecryptionError.FailedToDecrypt(ErrorUtil.messageWithStacktrace(err)))
      .flatMap(plain =>
        deserialize(ByteString.copyFrom(plain)).leftMap(DecryptionError.FailedToDeserialize)
      )

  /** Generates a random symmetric key */
  override def generateSymmetricKey(
      scheme: SymmetricKeyScheme = defaultSymmetricKeyScheme
  ): Either[EncryptionKeyGenerationError, SymmetricKey] = {
    val keyTemplate = scheme match {
      case SymmetricKeyScheme.Aes128Gcm => AeadKeyTemplates.AES128_GCM
    }

    Either
      .catchOnly[GeneralSecurityException](KeysetHandle.generateNew(keyTemplate))
      .bimap(
        EncryptionKeyGenerationError.GeneralError,
        { keysetHandle =>
          val key = TinkKeyFormat.serializeHandle(keysetHandle)
          SymmetricKey(CryptoKeyFormat.Tink, key, scheme)
        },
      )
  }

  override def createSymmetricKey(
      bytes: SecureRandomness,
      scheme: SymmetricKeyScheme,
  ): Either[EncryptionKeyCreationError, SymmetricKey] = {
    val keyData = scheme match {
      case SymmetricKeyScheme.Aes128Gcm =>
        val key = tinkproto.AesGcmKey
          .newBuilder()
          .setVersion(0)
          .setKeyValue(bytes.unwrap)
          .build()

        tinkproto.KeyData
          .newBuilder()
          .setTypeUrl("type.googleapis.com/google.crypto.tink.AesGcmKey")
          .setValue(key.toByteString)
          .setKeyMaterialType(tinkproto.KeyData.KeyMaterialType.SYMMETRIC)
          .build()
    }

    val keyId = 0
    val key = tinkproto.Keyset.Key
      .newBuilder()
      .setKeyData(keyData)
      .setStatus(tinkproto.KeyStatusType.ENABLED)
      .setKeyId(keyId)
      .setOutputPrefixType(tinkproto.OutputPrefixType.RAW)
      .build()

    val keyset = tinkproto.Keyset.newBuilder().setPrimaryKeyId(keyId).addKey(key).build()

    for {
      keysetHandle <- TinkKeyFormat
        .deserializeHandle(keyset.toByteString)
        .leftMap(err => EncryptionKeyCreationError.InternalConversionError(err.toString))
    } yield {
      SymmetricKey(
        CryptoKeyFormat.Tink,
        TinkKeyFormat.serializeHandle(keysetHandle),
        scheme,
      )
    }
  }

  /** Encrypts the given bytes with the given symmetric key */
  override def encryptWith[M <: HasVersionedToByteString](
      message: M,
      symmetricKey: SymmetricKey,
      version: ProtocolVersion,
  ): Either[EncryptionError, Encrypted[M]] =
    for {
      keysetHandle <- parseAndGetPrivateKey(
        symmetricKey,
        EncryptionError.InvalidSymmetricKey,
      )
      aead <- getPrimitive[tink.Aead, EncryptionError](
        keysetHandle,
        EncryptionError.InvalidSymmetricKey,
      )
      encrypted <- encryptWith(message, in => aead.encrypt(in, Array[Byte]()), version)
    } yield encrypted

  /** Decrypts a message encrypted using `encryptWith` */
  override def decryptWith[M](encrypted: Encrypted[M], symmetricKey: SymmetricKey)(
      deserialize: ByteString => Either[DeserializationError, M]
  ): Either[DecryptionError, M] =
    for {
      keysetHandle <- parseAndGetPrivateKey(
        symmetricKey,
        DecryptionError.InvalidSymmetricKey,
      )
      aead <- getPrimitive[tink.Aead, DecryptionError](
        keysetHandle,
        DecryptionError.InvalidSymmetricKey,
      )
      msg <- decryptWith(encrypted, in => aead.decrypt(in, Array[Byte]()), deserialize)
    } yield msg

  /** Encrypts the given message using the given public key. */
  override def encryptWith[M <: HasVersionedToByteString](
      message: M,
      publicKey: EncryptionPublicKey,
      version: ProtocolVersion,
  ): Either[EncryptionError, AsymmetricEncrypted[M]] =
    for {
      keysetHandle <- parseAndGetPublicKey(publicKey, EncryptionError.InvalidEncryptionKey)
        .leftMap(err => EncryptionError.InvalidEncryptionKey(err.show))
      hybrid <- getPrimitive[tink.HybridEncrypt, EncryptionError](
        keysetHandle,
        EncryptionError.InvalidEncryptionKey,
      )
      encrypted <- encryptWith(message, in => hybrid.encrypt(in, Array[Byte]()), version)
    } yield AsymmetricEncrypted(encrypted.ciphertext, publicKey.fingerprint)

  override protected def decryptWithInternal[M](
      encrypted: AsymmetricEncrypted[M],
      privateKey: EncryptionPrivateKey,
  )(
      deserialize: ByteString => Either[DeserializationError, M]
  ): Either[DecryptionError, M] =
    for {
      keysetHandle <- parseAndGetPrivateKey(
        privateKey,
        DecryptionError.InvalidEncryptionKey,
      )
      hybrid <- getPrimitive[tink.HybridDecrypt, DecryptionError](
        keysetHandle,
        DecryptionError.InvalidEncryptionKey,
      )
      msg <- decryptWith(encrypted.ciphertext, in => hybrid.decrypt(in, Array[Byte]()), deserialize)
    } yield msg

  override protected[crypto] def sign(
      bytes: ByteString,
      signingKey: SigningPrivateKey,
  ): Either[SigningError, Signature] =
    for {
      keysetHandle <- parseAndGetPrivateKey(
        signingKey,
        SigningError.InvalidSigningKey,
      )
      verify <- getPrimitive[tink.PublicKeySign, SigningError](
        keysetHandle,
        SigningError.InvalidSigningKey,
      )
      signatureBytes <- Either
        .catchOnly[GeneralSecurityException](verify.sign(bytes.toByteArray))
        .leftMap(err => SigningError.FailedToSign(ErrorUtil.messageWithStacktrace(err)))
      signature = new Signature(
        SignatureFormat.Raw,
        ByteString.copyFrom(signatureBytes),
        signingKey.id,
      )
    } yield signature

  /** Confirms if the provided signature is a valid signature of the payload using the public key.
    * Will always deem the signature invalid if the public key is not a signature key.
    */
  override def verifySignature(
      bytes: ByteString,
      publicKey: SigningPublicKey,
      signature: Signature,
  ): Either[SignatureCheckError, Unit] =
    for {
      _ <- Either.cond(
        signature.signedBy == publicKey.id,
        (),
        SignatureCheckError.SignatureWithWrongKey(
          s"Signature signed by ${signature.signedBy} instead of ${publicKey.id}"
        ),
      )
      keysetHandle <- parseAndGetPublicKey(publicKey, SignatureCheckError.InvalidKeyError)
      verify <- getPrimitive[tink.PublicKeyVerify, SignatureCheckError](
        keysetHandle,
        SignatureCheckError.InvalidKeyError,
      )
      _ <- Either
        .catchOnly[GeneralSecurityException](
          verify.verify(signature.unwrap.toByteArray, bytes.toByteArray)
        )
        .leftMap(err =>
          SignatureCheckError
            .InvalidSignature(signature, bytes, show"Failed to verify signature: $err")
        )
    } yield ()

  override protected def computeHkdfInternal(
      keyMaterial: ByteString,
      outputBytes: Int,
      info: HkdfInfo,
      salt: ByteString,
      algorithm: HmacAlgorithm,
  ): Either[HkdfError, SecureRandomness] = {
    Either
      .catchOnly[GeneralSecurityException] {
        Hkdf.computeHkdf(
          algorithm.name,
          keyMaterial.toByteArray,
          salt.toByteArray,
          info.bytes.toByteArray,
          outputBytes,
        )
      }
      .leftMap(err => show"Failed to compute HKDF with Tink: $err")
      .flatMap { hkdfOutput =>
        SecureRandomness
          .fromByteString(outputBytes)(ByteString.copyFrom(hkdfOutput))
          .leftMap(err => s"Invalid output from HKDF: $err")
      }
      .leftMap(HkdfInternalError)
  }

  override protected def hkdfExpandInternal(
      keyMaterial: SecureRandomness,
      outputBytes: Int,
      info: HkdfInfo,
      algorithm: HmacAlgorithm,
  ): Either[HkdfError, SecureRandomness] = {
    // NOTE: Tink does not expose only the expand phase, thus we have to implement it ourselves
    val hashBytes = algorithm.hashAlgorithm.length
    for {
      prk <- HmacSecret.create(keyMaterial.unwrap).leftMap(HkdfHmacError)
      nrChunks = scala.math.ceil(outputBytes.toDouble / hashBytes).toInt
      outputAndLast <- (1 to nrChunks).toList
        .foldM(ByteString.EMPTY -> ByteString.EMPTY) { case ((out, last), chunk) =>
          val chunkByte = ByteString.copyFrom(Array[Byte](chunk.toByte))
          hmacWithSecret(prk, last.concat(info.bytes).concat(chunkByte), algorithm)
            .bimap(HkdfHmacError, hmac => out.concat(hmac.unwrap) -> hmac.unwrap)
        }
      (out, _) = outputAndLast
    } yield SecureRandomness(out.substring(0, outputBytes))
  }

  override protected def generateRandomBytes(length: Int): Array[Byte] = Random.randBytes(length)

  override protected def defaultPbkdfScheme: PbkdfScheme =
    throw new UnsupportedOperationException(
      "Unsupported crypto operation: PBKDF schemes not supported"
    )

  override def deriveSymmetricKey(
      password: String,
      symmetricKeyScheme: SymmetricKeyScheme,
      scheme: PbkdfScheme,
      saltO: Option[SecureRandomness],
  ): Either[PasswordBasedEncryptionError, PasswordBasedEncryptionKey] =
    throw new UnsupportedOperationException(
      "Unsupported crypto operation: derive symmetric key from password"
    )
}

object TinkPureCrypto {

  def create(
      cryptoKeyConverter: CryptoKeyConverter,
      defaultSymmetricKeyScheme: SymmetricKeyScheme,
      defaultHashAlgorithm: HashAlgorithm,
  ): Either[String, TinkPureCrypto] =
    Either
      .catchOnly[GeneralSecurityException](TinkConfig.register())
      .bimap(
        err => show"Failed to initialize tink: $err",
        _ => new TinkPureCrypto(cryptoKeyConverter, defaultSymmetricKeyScheme, defaultHashAlgorithm),
      )

}
