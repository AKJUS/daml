// Copyright (c) 2019 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.daml.lf.data

import org.scalacheck.Gen
import org.scalacheck.Prop.forAll
import org.scalatest.{Matchers, WordSpec}

import scala.Ordering.Implicits._
import scala.collection.JavaConverters._
import scala.util.Random

@SuppressWarnings(Array("org.wartremover.warts.Any"))
class UTF8Spec extends WordSpec with Matchers {

  private def codepointToString(cp: Int): String =
    Character.toChars(cp).mkString

  private val lowCodepoints =
    Gen.chooseNum(Character.MIN_CODE_POINT, Character.MIN_HIGH_SURROGATE - 1).map(codepointToString)
  private val highCodepoints =
    Gen.chooseNum(Character.MAX_LOW_SURROGATE + 1, Character.MAX_CODE_POINT).map(codepointToString)
  private val asciiCodepoints =
    Gen.asciiChar.map(c => codepointToString(c.toInt))
  private val twoWordsCodepoints =
    Gen.chooseNum(Character.MAX_VALUE + 1, Character.MAX_CODE_POINT).map(codepointToString)

  private val codepoints =
    Gen.frequency(
      5 -> asciiCodepoints,
      5 -> twoWordsCodepoints,
      1 -> lowCodepoints,
      1 -> highCodepoints
    )

  private val strings =
    Gen.listOf(codepoints).map(_.mkString)

  // All the valid Unicode codepoints in increasing order converted in string
  private val validCodepoints =
    ((Character.MIN_CODE_POINT to Character.MIN_HIGH_SURROGATE) ++
      (Character.MAX_LOW_SURROGATE + 1 until Character.MAX_CODE_POINT))
      .map(codepointToString)

  "Unicode.explode" should {

    "explode properly counter example" in {
      "a¶‱😂".toList shouldNot be(List("a", "¶", "‱", "😂"))
      UTF8.explode("a¶‱😂") shouldBe ImmArray("a", "¶", "‱", "😂")
    }

    "explode in a same way a naive implementation" in {
      def naiveExplode(s: String) =
        ImmArray(s.codePoints().iterator().asScala.map(codepointToString(_)).toIterable)

      forAll(strings) { s =>
        naiveExplode(s) == UTF8.explode(s)
      }

    }
  }

  "Unicode.Ordering" should {

    "do not have basic UTF16 ordering issue" in {
      val s1 = "｡"
      val s2 = "😂"
      val List(cp1) = s1.codePoints().iterator().asScala.toList
      val List(cp2) = s2.codePoints().iterator().asScala.toList

      Ordering.String.lt(s1, s2) shouldNot be(Ordering.Int.lt(cp1, cp2))
      UTF8.ordering.lt(s1, s2) shouldBe Ordering.Int.lt(cp1, cp2)
    }

    "be reflexive" in {
      forAll(strings) { x =>
        UTF8.ordering.compare(x, x) == 0
      }
    }

    "consistent when flipping its arguments" in {
      forAll(strings, strings) { (x, y) =>
        UTF8.ordering.compare(x, y).signum == -UTF8.ordering.compare(y, x).signum
      }
    }

    "be transitive" in {
      import UTF8.ordering.lteq

      forAll(strings, strings, strings) { (x_, y_, z_) =>
        val List(x, y, z) = List(x_, y_, z_).sorted(UTF8.ordering)
        lteq(x, y) && lteq(y, z) && lteq(x, z)
      }
    }

    "respect Unicode ordering on individual codepoints" in {

      val shuffledCodepoints = new Random(0).shuffle(validCodepoints)

      // Sort according UTF16
      val negativeCase = shuffledCodepoints.sorted

      // Sort according our ad hoc ordering
      val positiveCase = shuffledCodepoints.sorted(UTF8.ordering)

      negativeCase shouldNot be(validCodepoints)
      positiveCase shouldBe validCodepoints

    }

    "respect Unicode ordering on complex string" in {

      // a naive inefficient Unicode ordering
      val naiveOrdering =
        Ordering.by((s: String) => s.codePoints().toArray.toIterable)

      forAll(Gen.listOfN(20, strings)) { list =>
        list.sorted(naiveOrdering) == list.sorted(UTF8.ordering)
      }

    }

    "be strict on individual codepoints" in {
      (validCodepoints zip validCodepoints.tail).foreach {
        case (x, y) => UTF8.ordering.compare(x, y) should be < 0
      }
    }

  }

}
