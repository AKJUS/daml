// Copyright (c) 2025 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.tls

import java.nio.file.Paths
import scala.util.Try

object TlsConfigurationCli {

  type Setter[T, B] = (B => B, T) => T
  def parse[C](parser: scopt.OptionParser[C], colSpacer: String)(
      setter: Setter[C, TlsConfiguration]
  ): Unit = {
    def enableSet(tlsUp: TlsConfiguration => TlsConfiguration, c: C) =
      setter(tlsc => tlsUp(tlsc.copy(enabled = true)), c)

    import parser.opt

    opt[String]("pem")
      .optional()
      .text("TLS: The pem file to be used as the private key.")
      .validate(validatePath(_, "The file specified via --pem does not exist"))
      .action { (path, c) =>
        enableSet(_.copy(privateKeyFile = Some(Paths.get(path).toFile)), c)
      }: Unit

    opt[String]("crt")
      .optional()
      .text(
        s"TLS: The crt file to be used as the cert chain.\n$colSpacer" +
          s"Required for client authentication."
      )
      .validate(validatePath(_, "The file specified via --crt does not exist"))
      .action { (path, c) =>
        enableSet(_.copy(certChainFile = Some(Paths.get(path).toFile)), c)
      }: Unit

    opt[String]("cacrt")
      .optional()
      .text("TLS: The crt file to be used as the trusted root CA.")
      .validate(validatePath(_, "The file specified via --cacrt does not exist"))
      .action { (path, c) =>
        enableSet(_.copy(trustCollectionFile = Some(Paths.get(path).toFile)), c)
      }: Unit

    // allows you to enable tls without any special certs,
    // i.e., tls without client auth with the default root certs.
    // If any certificates are set tls is enabled implicitly and
    // this is redundant.
    opt[Unit]("tls")
      .optional()
      .text("TLS: Enable tls. This is redundant if --pem, --crt or --cacrt are set")
      .action((_, c) => enableSet(identity, c)): Unit
    ()
  }

  private def validatePath(path: String, message: String): Either[String, Unit] = {
    val valid = Try(Paths.get(path).toFile.canRead).getOrElse(false)
    Either.cond(valid, (), message)
  }
}
