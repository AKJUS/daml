// Copyright (c) 2025 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.daml.lf
package testing.archive

import com.digitalasset.daml.lf.archive.Decode
import com.digitalasset.daml.lf.archive.testing.Encode
import com.digitalasset.daml.lf.data.Ref._
import com.digitalasset.daml.lf.language.Ast._
import com.digitalasset.daml.lf.language.{Ast, LanguageVersion}
import com.digitalasset.daml.lf.testing.parser.Implicits.SyntaxHelper
import com.digitalasset.daml.lf.testing.parser.{AstRewriter, ParserParameters}
import com.digitalasset.daml.lf.validation.Validation
import org.scalatest.prop.TableDrivenPropertyChecks
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import scala.language.implicitConversions

class EncodeV2Spec extends EncodeSpec(LanguageVersion.v2_dev)

abstract class EncodeSpec(languageVersion: LanguageVersion)
    extends AnyWordSpec
    with Matchers
    with TableDrivenPropertyChecks {

  import EncodeSpec._

  private val stablePackages =
    com.digitalasset.daml.lf.stablepackages.StablePackages(languageVersion.major)

  private val tuple2TyCon: String = {
    import stablePackages.Tuple2
    s"'${Tuple2.packageId}':${Tuple2.qualifiedName}"
  }

  private val pkgId: PackageId = "self"

  private val defaultParserParameters: ParserParameters[this.type] =
    ParserParameters(pkgId, languageVersion)

  "Encode and Decode" should {
    "form a prism" in {

      implicit val defaultParserParameters2: ParserParameters[this.type] =
        defaultParserParameters

      val pkg: Ast.Package =
        p"""

         metadata ( 'foobar' : '0.0.1' )

         module Mod {

            record @serializable MyUnit = {};

            record @serializable Person = { person: Party, name: Text } ;

            interface (this: Planet) = {
              viewtype Mod:MyUnit;
            };

            interface (this: Human) = {
              viewtype Mod:MyUnit;
              requires Mod:Planet;
              method asParty: Party;
              method getName: Text;
              choice HumanSleep (self) (u:Unit) : ContractId Mod:Human
                , controllers Cons @Party [call_method @Mod:Human asParty this] (Nil @Party)
                , observers Nil @Party
                to upure @(ContractId Mod:Human) self;
              choice @nonConsuming HumanNap (self) (i : Int64): Int64
                , controllers Cons @Party [call_method @Mod:Human asParty this] (Nil @Party)
                , observers Nil @Party
                to upure @Int64 i;
            };

            template (this : Person) =  {
              precondition True;
              signatories Cons @Party [Mod:Person {person} this] (Nil @Party);
              observers Cons @Party [Mod:Person {person} this] (Nil @Party);
              choice Sleep (self) (u: Unit) : Unit,
                  controllers Cons @Party [Mod:Person {person} this] (Nil @Party),
                  observers Nil @Party
                to upure @Unit ();
              choice @nonConsuming Nap (self) (i : Int64): Int64,
                  controllers Cons @Party [Mod:Person {person} this] (Nil @Party),
                  observers Cons @Party [Mod:Person {person} this] (Nil @Party)
              to upure @Int64 i;
              implements Mod:Planet {
                view = Mod:MyUnit {};
              };
              implements Mod:Human {
                view = Mod:MyUnit {};
                method asParty = Mod:Person {person} this;
                method getName = Mod:Person {name} this;
              };
              key @Party (Mod:Person {person} this) (\ (p: Party) -> Cons @Party [p] (Nil @Party));
            };

           variant Tree (a : * ) = Leaf : Unit | Node : Mod:Tree.Node a ;
           record Tree.Node (a: *) = { value: a, left : Mod:Tree a, right : Mod:Tree a };
           enum Color = Red | Green | Blue;

           val unit: Unit = loc(Mod, unit, 92, 12, 92, 61) ();
           val aVar: forall (a:*). a -> a = /\ (a: *). \ (x: a) -> x;
           val aValue: forall (a:*). a -> a = Mod:aVar;
           val aBuiltin : Int64 -> Int64 -> Int64 = ADD_INT64;
           val myFalse: Bool = False;
           val myTrue: Bool = True;
           val aInt: Int64 = 14;
           val aNumeric10: Numeric 10 = 2.2000000000;
           val aDate: Date = 1879-03-14;
           val aTimestamp: Timestamp = 1970-01-01T00:00:00.000001Z;
           val aString: Text = "a string";
           val aStruct: forall (a:*) (b:*). a ->  b -> < x1: a, x2: b > = /\ (a:*) (b:*). \ (x1: a) (x2: b) ->
             <x1 = x1, x2 = x2>;
           val aStructProj: forall (a:*) (b:*). < x1: a, x2: b > -> a = /\ (a:*) (b:*). \ (struct: < x1: a, x2: b >) ->
             (struct).x1;
           val aStructUpd: forall (a:*) (b:*). < x1: a, x2: b > -> a -> < x1: a, x2: b >  =
             /\ (a:*) (b:*). \ (struct: < x1: a, x2: b >) (x: a) ->
               < struct with x1 = x >;
           val aApp: forall (a: *) (b: *) (c:*). (a -> b -> c) -> a -> b -> c =
             /\ (a:*) (b:*) (c: *). \ (f: a -> b -> c) (x: a) (y: b) -> f x y;
           val anEmptyList: forall (a: *). List a = /\ (a: *).
              Nil @a;
           val aNonEmptList: forall (a: *). a -> a -> a -> List a = /\ (a: *). \ (x1:a) (x2: a) (x3: a) ->
             Cons @a [x1, x2, x3] (Nil @a);
           val anEmptyOption: forall (a: *). Option a = /\ (a:*).
             None @a;
           val aNonEmptyOption: forall (a: *). a -> Option a = /\ (a:*). \ (x: a) ->
             Some @a x;
           val aLeaf: forall (a: *). Mod:Tree a = /\ (a:*).
             Mod:Tree:Leaf @a ();
           val aNode: forall (a: *). a -> Mod:Tree a = /\ (a:*). \ (x: a) ->
             Mod:Tree:Node @a (Mod:Tree.Node @a { value = x, left = Mod:Tree:Leaf @a (), right = Mod:Tree:Leaf @a ()});
           val red: Mod:Color =
             Mod:Color:Red;
           val aRecProj: forall (a: *). Mod:Tree.Node a -> a = /\ (a:*). \ (node: Mod:Tree.Node a) ->
             Mod:Tree.Node @a { value } node;
           val aRecUpdate: forall (a: *). Mod:Tree.Node a -> Mod:Tree.Node a = /\ (a:*). \ (node: Mod:Tree.Node a) ->
             Mod:Tree.Node @a { node with left = Mod:Tree:Leaf @a () };
           val aUnitMatch: forall (a: *). a -> Unit -> a = /\ (a: *). \(x :a ) (e:Unit) ->
             case e of () -> x;
           val aBoolMatch: forall (a: *). a -> a -> Bool -> a = /\ (a: *). \(x :a) (y: a) (e: Bool) ->
             case e of True -> x | False -> y;
           val aListMatch: forall (a: *). List a -> Option (<head: a, tail: List a>) = /\ (a: *). \ (e: List a) ->
             case e of Nil -> None @(<head: a, tail: List a>)
                     | Cons h t -> Some @(<head: a, tail: List a>) (<head = h, tail = t>);
           val aOptionMatch: forall (a: *). Text -> TextMap a -> a -> a = /\ (a:*). \ (key: Text) (map: TextMap a) (default: a) ->
             case (TEXTMAP_LOOKUP @a key map) of None -> default | Some y -> y;
           val aVariantMatch: forall (a:*). Mod:Tree a -> Option a = /\ (a: *). \(e: Mod:Tree a) ->
             case e of Mod:Tree:Leaf x -> None @a
                     | Mod:Tree:Node node -> Some @a (Mod:Tree.Node @a { value } node);
           val aEnumMatch: Mod:Color -> Text = \(e: Mod:Color) ->
             case e of Mod:Color:Red -> "Red" | Mod:Color:Green -> "Green" | Mod:Color:Blue -> "Blue";
           val aLet: Int64 = let i: Int64 = 42 in i;

           val aPureUpdate: forall (a: *). a -> Update a = /\ (a: *). \(x: a) ->
             upure @a x;
           val anUpdateBlock: forall (a: *). a -> Update a = /\ (a: *). \(x: a) ->
             ubind y: a <- (Mod:aPureUpdate @a x) in upure @a y;
           val aCreate: Mod:Person -> Update (ContractId Mod:Person) = \(person: Mod:Person) ->
             create @Mod:Person person;
           val identity: forall (a: *). a -> a = /\ (a: *). \(x: a) -> x;
           val anExercise: (ContractId Mod:Person) -> Update Unit = \(cId: ContractId Mod:Person) ->
             exercise @Mod:Person Sleep (Mod:identity @(ContractId Mod:Person) cId) ();
           val aFecthByKey: Party -> Update ($tuple2TyCon (ContractId Mod:Person) Mod:Person) = \(party: Party) ->
             fetch_by_key @Mod:Person party;
           val aLookUpByKey: Party -> Update (Option (ContractId Mod:Person)) = \(party: Party) ->
             lookup_by_key @Mod:Person party;
           val aGetTime: Update Timestamp =
             uget_time;
           val aLedgerTimeLT: Timestamp -> Update Bool = \(time: Timestamp) ->
             ledger_time_lt time;
           val anEmbedExpr: forall (a: *). Update a -> Update a = /\ (a: *). \ (x: Update a) ->
             uembed_expr @a x;
           val isZero: Int64 -> Bool = EQUAL @Int64 0;
           val isOne: BigNumeric -> Bool = EQUAL @BigNumeric (NUMERIC_TO_BIGNUMERIC @10 1.0000000000);
           val defaultRounding: RoundingMode = ROUNDING_UP;

           record @serializable MyException = { message: Text } ;
           exception MyException = {
             message \(e: Mod:MyException) -> Mod:MyException {message} e
           };

           val testException: Update Unit = ubind
              u1: Unit <-
                try @Unit
                  throw @(Update Unit) @Mod:MyException (Mod:MyException {message = "oops"})
                catch e -> Some @(Update Unit) (upure @Unit ())
            in upure @Unit ();

           val myAnyException: AnyException =
             to_any_exception @Mod:MyException (Mod:MyException {message = "oops"});

           val maybeException: Option Mod:MyException =
             from_any_exception @Mod:MyException Mod:myAnyException;

           val concrete_to_interface: Mod:Person -> Mod:Human =
             \ (p: Mod:Person) -> to_interface @Mod:Human @Mod:Person p;

           val concrete_from_interface: Mod:Human -> Option Mod:Person  =
             \ (h: Mod:Human) -> from_interface @Mod:Human @Mod:Person h;

           val concrete_unsafe_from_interface: ContractId Mod:Human -> Mod:Human -> Mod:Person  =
             \ (cid: ContractId Mod:Human) (h: Mod:Human) -> unsafe_from_interface @Mod:Human @Mod:Person cid h;

           val concrete_to_required_interface: Mod:Human -> Mod:Planet =
             \ (h: Mod:Human) -> to_required_interface @Mod:Planet @Mod:Human h;

           val concrete_from_required_interface: Mod:Planet -> Option Mod:Human  =
             \ (p: Mod:Planet) -> from_required_interface @Mod:Planet @Mod:Human p;

           val concrete_unsafe_from_required_interface: ContractId Mod:Planet -> Mod:Planet -> Mod:Human  =
             \ (cid: ContractId Mod:Planet) (p: Mod:Planet) -> unsafe_from_required_interface @Mod:Planet @Mod:Human cid p;

           val concrete_interface_template_type_rep: Mod:Planet -> TypeRep =
             \ (p: Mod:Planet) -> interface_template_type_rep @Mod:Planet p;

           val concrete_signatory_interface: Mod:Planet -> List Party =
             \ (p: Mod:Planet) -> signatory_interface @Mod:Planet p;

           val concrete_observer_interface: Mod:Planet -> List Party =
             \ (p: Mod:Planet) -> observer_interface @Mod:Planet p;

           val concrete_template_choice_controller: Mod:Person -> Int64 -> List Party =
             \ (p : Mod:Person) (i : Int64) -> choice_controller @Mod:Person Nap p i;
           val concrete_template_choice_observer: Mod:Person -> Int64 -> List Party =
             \ (p : Mod:Person) (i : Int64) -> choice_observer @Mod:Person Nap p i;
           val concrete_interface_choice_controller: Mod:Human -> Int64 -> List Party =
             \ (p : Mod:Human) (i : Int64) -> choice_controller @Mod:Human HumanNap p i;
           val concrete_interface_choice_observer: Mod:Human -> Int64 -> List Party =
             \ (p : Mod:Human) (i : Int64) -> choice_observer @Mod:Human HumanNap p i;

           interface (this: Root) = {
             viewtype Mod:MyUnit;
           };

           interface (this: Boxy) = {
             viewtype Mod:MyUnit;
             requires Mod:Root;
             method getParty: Party;
             choice @nonConsuming ReturnInt (self) (i: Int64): Int64
               , controllers Cons @Party [call_method @Mod:Boxy getParty this] (Nil @Party)
               , observers Nil @Party
               to upure @Int64 i;
           };
         }

         module Mod0 {
           record @serializable Parcel = { party: Party };

           template (this: Parcel) = {
             precondition True;
             signatories Cons @Party [Mod0:Parcel {party} this] (Nil @Party);
             observers Cons @Party [Mod0:Parcel {party} this] (Nil @Party);
           };
         }
      """

      validate(pkgId, pkg)
      val archive =
        Encode.encodeArchive(pkgId -> pkg, defaultParserParameters.languageVersion)
      val (hash, decodedPackage) = Decode.assertDecodeArchive(archive)

      val pkg1 = normalize(decodedPackage, hash, pkgId)
      pkg shouldBe pkg1
    }
  }

  private def validate(pkgId: PackageId, pkg: Package): Unit = {
    Validation
      .checkPackage(
        language.PackageInterface(stablePackages.packagesMap + (pkgId -> pkg)),
        pkgId,
        pkg,
      )
      .left
      .foreach(e => sys.error(e.toString))
  }

}

object EncodeSpec {

  private implicit def toPackageId(s: String): PackageId = PackageId.assertFromString(s)

  private def normalize(pkg: Package, hashCode: PackageId, selfPackageId: PackageId): Package = {

    val replacePkId: PartialFunction[PackageId, PackageId] = { case `hashCode` =>
      selfPackageId
    }
    lazy val dropEAbsRef: PartialFunction[Expr, Expr] = { case EAbs(binder, body) =>
      EAbs(normalizer.apply(binder), normalizer.apply(body))
    }
    lazy val normalizer = new AstRewriter(exprRule = dropEAbsRef, packageIdRule = replacePkId)

    normalizer.apply(pkg)
  }

}
