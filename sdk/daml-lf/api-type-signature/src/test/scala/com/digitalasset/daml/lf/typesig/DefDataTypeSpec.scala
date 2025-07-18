// Copyright (c) 2025 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.daml.lf.typesig

import com.daml.nonempty.NonEmpty
import scalaz.Equal

object DefDataTypeSpec {
  import org.scalacheck.{Arbitrary, Gen}, Arbitrary.arbitrary
  import com.digitalasset.daml.lf.data.Ref
  import com.digitalasset.daml.lf.value.test.ValueGenerators

  implicit def `TemplateChoices arb`[Ty: Arbitrary]: Arbitrary[TemplateChoices[Ty]] =
    Arbitrary(
      Gen.oneOf(
        mappedGen(TemplateChoices.Resolved[Ty] _),
        mappedGen((TemplateChoices.Unresolved[Ty] _).tupled),
      )
    )

  private[this] implicit def `TemplateChoice arb`[Ty: Arbitrary]: Arbitrary[TemplateChoice[Ty]] =
    Arbitrary(mappedGen((TemplateChoice[Ty] _).tupled))

  // equal is inductively natural; not bothering to write the non-natural case -SC
  implicit val `TemplateChoices eq`: Equal[TemplateChoices[Int]] = Equal.equalA

  private[this] implicit def `nonempty map arb`[K: Arbitrary, V: Arbitrary]
      : Arbitrary[NonEmpty[Map[K, V]]] =
    Arbitrary(
      arbitrary[((K, V), Map[K, V])] map { case (kv, m) => NonEmpty(Map, kv) ++ m }
    )

  private[this] implicit def `nonempty set arb`[A: Arbitrary]: Arbitrary[NonEmpty[Set[A]]] =
    Arbitrary(arbitrary[(A, Set[A])] map { case (hd, tl) => NonEmpty(Set, hd) ++ tl })

  private[this] implicit def `ChoiceName arb`: Arbitrary[Ref.ChoiceName] = Arbitrary(
    ValueGenerators.nameGen
  )
  private[this] implicit def `TypeConId arb`: Arbitrary[Ref.TypeConId] = Arbitrary(
    ValueGenerators.idGen
  )

  // helper to avoid restating the A type
  private def mappedGen[A: Arbitrary, B](f: A => B): Gen[B] =
    arbitrary[A] map f
}
