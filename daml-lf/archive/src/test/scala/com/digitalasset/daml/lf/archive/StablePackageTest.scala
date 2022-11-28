// Copyright (c) 2022 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.lf
package archive

import java.io.File
import com.daml.bazeltools.BazelRunfiles
import com.daml.lf.language.StablePackage
import org.scalatest._
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class StablePackageTest
    extends AnyWordSpec
    with Matchers
    with Inside
    with BazelRunfiles
    with Inspectors
    with TryValues {

  private def resource(path: String): File = {
    val f = new File(path).getAbsoluteFile
    require(f.exists, s"File does not exist: $f")
    f
  }

  "DA.StablePackages" should {

    // We rely on the fact a dar generated with targer 1.dev contains all the stable packages
    lazy val darFile = resource(rlocation("daml-lf/archive/DarReaderTest-dev.dar"))
    lazy val depPkgs = UniversalArchiveDecoder.assertReadFile(darFile).dependencies.toMap

    //  Fix as new packages are added to the std lib
    //  As of SDK 2.5, this should be 25: 23 stable packages + `daml-prim` + `daml-stdlib`
    assert(depPkgs.size == StablePackage.values.size + 2)

    "contains all the stable packages generated by the compiler" in {
      val pkgIdsInDar = depPkgs.keySet
      StablePackage.values.foreach(pkg => pkgIdsInDar should contain(pkg.packageId))
    }

    "lists the stable packages with their proper version" in {
      StablePackage.values.foreach(pkg =>
        pkg.languageVersion shouldBe depPkgs(pkg.packageId).languageVersion
      )
    }

  }

}
