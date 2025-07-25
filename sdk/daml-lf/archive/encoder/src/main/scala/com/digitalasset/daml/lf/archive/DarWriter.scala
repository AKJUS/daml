// Copyright (c) 2025 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.daml.lf.archive

import com.digitalasset.daml.lf.data.Bytes

import java.io.{ByteArrayOutputStream, FileOutputStream, OutputStream}
import java.nio.file.Path
import java.util.zip.{ZipEntry, ZipOutputStream}
import scalaz.syntax.traverse._

object DarWriter {
  private val manifestPath = "META-INF/MANIFEST.MF"

  /** Write the DAR to the given path.
    */
  def encode(sdkVersion: String, dar: Dar[(String, Bytes)], path: Path): Unit = {
    val out = new FileOutputStream(path.toFile)
    encode(sdkVersion, dar, out)
  }

  /** Write the DAR to the given output stream.
    *      The output stream will be closed afterwards.
    */
  def encode(sdkVersion: String, dar: Dar[(String, Bytes)], out: OutputStream): Unit = {
    val zipOut = new ZipOutputStream(out)
    zipOut.putNextEntry(new ZipEntry(manifestPath))
    val bytes = new ByteArrayOutputStream()
    DarManifestWriter.encode(sdkVersion, dar.map(_._1)).write(bytes)
    bytes.close
    zipOut.write(bytes.toByteArray)
    zipOut.closeEntry()
    dar.all.foreach { case (path, bs) =>
      zipOut.putNextEntry(new ZipEntry(path))
      zipOut.write(bs.toByteArray)
      zipOut.closeEntry
    }
    zipOut.close
  }
}
