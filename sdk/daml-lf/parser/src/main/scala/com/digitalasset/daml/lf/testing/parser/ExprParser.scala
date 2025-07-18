// Copyright (c) 2025 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.daml.lf.testing.parser

import com.digitalasset.daml.lf.data.Ref.{Location, Name}
import com.digitalasset.daml.lf.data.ImmArray
import com.digitalasset.daml.lf.language.Ast._
import com.digitalasset.daml.lf.testing.parser.Parsers._
import com.digitalasset.daml.lf.testing.parser.Token._

@SuppressWarnings(Array("org.wartremover.warts.AnyVal"))
private[parser] class ExprParser[P](parserParameters: ParserParameters[P]) {

  import ExprParser._

  private[parser] val typeParser: TypeParser[P] = new TypeParser(parserParameters)
  import typeParser._

  lazy val expr0: Parser[Expr] =
    // expressions starting with fullIdentifier should come before literals
    eRecCon |
      eRecProj |
      eRecUpd |
      eVariantOrEnumCon |
      fullIdentifier ^^ EVal |
      literal ^^ EBuiltinLit |
      primCon ^^ EBuiltinCon |
      update ^^ EUpdate |
      eList |
      eOption |
      eStructCon |
      eStructUpd |
      eStructProj |
      eAbs |
      eTyAbs |
      eLet |
      eToAny |
      eFromAny |
      eToAnyException |
      eFromAnyException |
      eToTextTypeConId |
      eThrow |
      eCallInterface |
      eToInterface |
      eFromInterface |
      eUnsafeFromInterface |
      eToRequiredInterface |
      eFromRequiredInterface |
      eUnsafeFromRequiredInterface |
      eInterfaceTemplateTypeRep |
      eSignatoryInterface |
      eObserverInterface |
      eViewInterface |
      eChoiceController |
      eChoiceObserver |
      (id ^? builtinFunctions) ^^ EBuiltinFun |
      experimental |
      caseOf |
      id ^^ EVar |
      (`(` ~> expr <~ `)`)

  lazy val exprs: Parser[List[Expr]] = rep(expr0)

  private[this] val roundingModes = {
    import java.math.RoundingMode._
    Map(
      "ROUNDING_UP" -> UP,
      "ROUNDING_DOWN" -> DOWN,
      "ROUNDING_CEILING" -> CEILING,
      "ROUNDING_FLOOR" -> FLOOR,
      "ROUNDING_HALF_UP" -> HALF_UP,
      "ROUNDING_HALF_DOWN" -> HALF_DOWN,
      "ROUNDING_HALF_EVEN" -> HALF_EVEN,
      "ROUNDING_UNNECESSARY" -> UNNECESSARY,
    )
  }

  private[this] val failureCategories =
    Map(
      "INVALID_INDEPENDENT_OF_SYSTEM_STATE" -> FCInvalidIndependentOfSystemState,
      "INVALID_GIVEN_CURRENT_SYSTEM_STATE_OTHER" -> FCInvalidGivenCurrentSystemStateOther,
    )

  private lazy val literal: Parsers.Parser[BuiltinLit] =
    acceptMatch[BuiltinLit]("Number", { case Number(l) => BLInt64(l) }) |
      acceptMatch("Numeric", { case Numeric(d) => BLNumeric(d) }) |
      acceptMatch("Text", { case Text(s) => BLText(s) }) |
      acceptMatch("Timestamp", { case Timestamp(l) => BLTimestamp(l) }) |
      acceptMatch("Date", { case Date(l) => BLDate(l) }) |
      (id ^? roundingModes) ^^ BLRoundingMode |
      (id ^? failureCategories) ^^ BLFailureCategory

  private lazy val primCon =
    Id("True") ^^^ BCTrue |
      Id("False") ^^^ BCFalse |
      (`(` ~ `)` ^^^ BCUnit)

  private lazy val eAppAgr: Parser[EAppAgr] =
    argTyp ^^ EAppTypArg |
      expr0 ^^ EAppExprArg

  lazy val expr: Parser[Expr] = {
    expr0 ~ rep(eAppAgr) ^^ { case e0 ~ args =>
      (args foldLeft e0) {
        case (acc, EAppExprArg(e)) => EApp(acc, e)
        case (acc, EAppTypArg(t)) => ETyApp(acc, t)
      }
    } |
      eLoc
  }

  private lazy val fieldInit: Parser[(Name, Expr)] = id ~ (`=` ~> expr) ^^ { case fName ~ value =>
    fName -> value
  }

  private lazy val fieldInits: Parser[ImmArray[(Name, Expr)]] =
    repsep(fieldInit, `,`) ^^ ImmArray.apply

  private lazy val typeArgs = rep(argTyp) ^^ (_.to(ImmArray))

  private lazy val typeConApp = fullIdentifier ~ typeArgs ^^ { case tName ~ types =>
    TypeConApp(tName, types)
  }

  private lazy val eList = eNil | eCons

  private lazy val eNil = `nil` ~>! argTyp ^^ ENil

  private lazy val eCons = `cons` ~>! argTyp ~ (`[` ~> repsep(expr, `,`) <~ `]`) ~ expr0 ^^ {
    case t ~ front ~ tail => ECons(t, front.to(ImmArray), tail)
  }

  private lazy val eOption = eNone | eSome

  private lazy val eNone = `none` ~>! argTyp ^^ ENone

  private lazy val eSome = `some` ~>! argTyp ~ expr0 ^^ { case t ~ e =>
    ESome(t, e)
  }

  private lazy val eRecCon: Parser[Expr] =
    typeConApp ~ (`{` ~> fieldInits <~ `}`) ^^ { case tConApp ~ fields =>
      ERecCon(tConApp, fields)
    }

  private lazy val eRecProj: Parser[Expr] =
    typeConApp ~ (`{` ~> id <~ `}`) ~! expr0 ^^ { case tConApp ~ fName ~ record =>
      ERecProj(tConApp, fName, record)
    }

  private lazy val eRecUpd: Parser[Expr] =
    typeConApp ~ (`{` ~> expr ~ (`with` ~>! fieldInit <~ `}`)) ^^ {
      case tConApp ~ (record ~ ((fName, fValue))) =>
        ERecUpd(tConApp, fName, record, fValue)
    }

  private lazy val eVariantOrEnumCon: Parser[Expr] = Parser { in =>
    val parser = fullIdentifier ~ (`:` ~> id) ~ rep(argTyp) ~ opt(expr0) ^^ {
      case tName ~ vName ~ argsTyp ~ Some(arg) =>
        EVariantCon(TypeConApp(tName, argsTyp.to(ImmArray)), vName, arg)
      case _ ~ _ ~ argsTyp ~ None if argsTyp.nonEmpty =>
        throw ParsingError(
          "enum type does not take type parameters",
          in.pos,
        )
      case tName ~ vName ~ _ ~ None =>
        EEnumCon(tName, vName)
    }

    parser(in)
  }

  private lazy val eStructCon: Parser[Expr] =
    `<` ~> fieldInits <~ `>` ^^ EStructCon

  private lazy val eStructProj: Parser[Expr] =
    (`(` ~> expr <~ `)` ~ `.`) ~! id ^^ { case struct ~ fName =>
      EStructProj(fName, struct)
    }

  private lazy val eStructUpd: Parser[Expr] =
    `<` ~> expr ~ (`with` ~>! fieldInit) <~ `>` ^^ { case struct ~ ((fName, value)) =>
      EStructUpd(fName, struct, value)
    }

  private[parser] lazy val varBinder: Parser[(Name, Type)] =
    `(` ~> id ~ (`:` ~> typ <~ `)`) ^^ { case name ~ t => name -> t }

  private lazy val eAbs: Parser[Expr] =
    `\\` ~>! rep1(varBinder) ~ (`->` ~> expr) ^^ { case binders ~ body =>
      (binders foldRight body)((binder: (ExprVarName, Type), body: Expr) => EAbs(binder, body))
    }

  private lazy val eTyAbs: Parser[Expr] =
    `/\\` ~>! rep1(typeBinder) ~ (`.` ~> expr) ^^ { case binders ~ body =>
      (binders foldRight body)(ETyAbs)
    }

  private lazy val bindings: Parser[ImmArray[Binding]] =
    rep1sep(binding(`<-`), `;`) <~! `in` ^^ (_.to(ImmArray))

  private def binding(sep: Token): Parser[Binding] =
    Id("_") ~> (`:` ~> typ) ~ (sep ~> expr) ^^ { case t ~ value =>
      Binding(None, t, value)
    } |
      id ~ (`:` ~> typ) ~ (sep ~> expr) ^^ { case vName ~ t ~ value =>
        Binding(Some(vName), t, value)
      }

  private lazy val eLet: Parser[Expr] =
    `let` ~>! binding(`=`) ~ (`in` ~> expr) ^^ { case b ~ body =>
      ELet(b, body)
    }

  private lazy val eToAny: Parser[Expr] =
    `to_any` ~>! argTyp ~ expr0 ^^ { case ty ~ e =>
      EToAny(ty, e)
    }

  private lazy val eFromAny: Parser[Expr] =
    `from_any` ~>! argTyp ~ expr0 ^^ { case ty ~ e =>
      EFromAny(ty, e)
    }

  private lazy val eToAnyException: Parser[EToAnyException] =
    `to_any_exception` ~>! argTyp ~ expr0 ^^ { case ty ~ e =>
      EToAnyException(ty, e)
    }

  private lazy val eFromAnyException: Parser[EFromAnyException] =
    `from_any_exception` ~>! argTyp ~ expr0 ^^ { case ty ~ e =>
      EFromAnyException(ty, e)
    }

  private lazy val eThrow: Parser[EThrow] =
    `throw` ~>! argTyp ~ argTyp ~ expr0 ^^ { case retType ~ excepType ~ exception =>
      EThrow(retType, excepType, exception)
    }

  private lazy val eToTextTypeConId: Parser[Expr] =
    `type_rep` ~>! argTyp ^^ ETypeRep

  private lazy val eToInterface: Parser[Expr] =
    `to_interface` ~! `@` ~> fullIdentifier ~ `@` ~ fullIdentifier ~ expr0 ^^ {
      case ifaceId ~ _ ~ tmplId ~ e =>
        EToInterface(ifaceId, tmplId, e)
    }

  private lazy val eFromInterface: Parser[Expr] =
    `from_interface` ~! `@` ~> fullIdentifier ~ `@` ~ fullIdentifier ~ expr0 ^^ {
      case ifaceId ~ _ ~ tmplId ~ e =>
        EFromInterface(ifaceId, tmplId, e)
    }

  private lazy val eUnsafeFromInterface: Parser[Expr] =
    `unsafe_from_interface` ~! `@` ~> fullIdentifier ~ `@` ~ fullIdentifier ~ expr0 ~ expr0 ^^ {
      case ifaceId ~ _ ~ tmplId ~ cid ~ expr =>
        EUnsafeFromInterface(ifaceId, tmplId, cid, expr)
    }

  private lazy val eToRequiredInterface: Parser[Expr] =
    `to_required_interface` ~! `@` ~> fullIdentifier ~ `@` ~ fullIdentifier ~ expr0 ^^ {
      case ifaceId1 ~ _ ~ ifaceId2 ~ e =>
        EToRequiredInterface(ifaceId1, ifaceId2, e)
    }

  private lazy val eFromRequiredInterface: Parser[Expr] =
    `from_required_interface` ~! `@` ~> fullIdentifier ~ `@` ~ fullIdentifier ~ expr0 ^^ {
      case ifaceId1 ~ _ ~ ifaceId2 ~ e =>
        EFromRequiredInterface(ifaceId1, ifaceId2, e)
    }

  private lazy val eUnsafeFromRequiredInterface: Parser[Expr] =
    `unsafe_from_required_interface` ~! `@` ~> fullIdentifier ~ `@` ~ fullIdentifier ~ expr0 ~ expr0 ^^ {
      case ifaceId1 ~ _ ~ ifaceId2 ~ cid ~ expr =>
        EUnsafeFromRequiredInterface(ifaceId1, ifaceId2, cid, expr)
    }

  private lazy val eInterfaceTemplateTypeRep: Parser[Expr] =
    `interface_template_type_rep` ~! `@` ~> fullIdentifier ~ expr0 ^^ { case ifaceId ~ e =>
      EInterfaceTemplateTypeRep(ifaceId, e)
    }

  private lazy val eSignatoryInterface: Parser[Expr] =
    `signatory_interface` ~! `@` ~> fullIdentifier ~ expr0 ^^ { case ifaceId ~ e =>
      ESignatoryInterface(ifaceId, e)
    }

  private lazy val eObserverInterface: Parser[Expr] =
    `observer_interface` ~! `@` ~> fullIdentifier ~ expr0 ^^ { case ifaceId ~ e =>
      EObserverInterface(ifaceId, e)
    }

  private lazy val eViewInterface: Parser[Expr] =
    `view_interface` ~! `@` ~> fullIdentifier ~ expr0 ^^ { case ifaceId ~ e =>
      EViewInterface(ifaceId, e)
    }

  private lazy val eChoiceController: Parser[Expr] =
    `choice_controller` ~! `@` ~> fullIdentifier ~ id ~ expr0 ~ expr0 ^^ {
      case typeId ~ choiceName ~ contract ~ choiceArg =>
        EChoiceController(typeId, choiceName, contract, choiceArg)
    }

  private lazy val eChoiceObserver: Parser[Expr] =
    `choice_observer` ~! `@` ~> fullIdentifier ~ id ~ expr0 ~ expr0 ^^ {
      case typeId ~ choiceName ~ contract ~ choiceArg =>
        EChoiceObserver(typeId, choiceName, contract, choiceArg)
    }

  private lazy val pattern: Parser[CasePat] =
    Id("_") ^^^ CPDefault |
      primCon ^^ CPBuiltinCon |
      (`nil` ^^^ CPNil) |
      (`cons` ~>! id ~ id ^^ { case x1 ~ x2 => CPCons(x1, x2) }) |
      (`none` ^^^ CPNone) |
      (`some` ~>! id ^^ CPSome) |
      (fullIdentifier <~ `:`) ~ id ~ opt(id) ^^ {
        case tyCon ~ vName ~ Some(x) =>
          CPVariant(tyCon, vName, x)
        case tyCon ~ vName ~ None =>
          CPEnum(tyCon, vName)
      }

  private lazy val alternative: Parser[CaseAlt] =
    pattern ~! (`->` ~>! expr) ^^ { case p ~ e =>
      CaseAlt(p, e)
    }

  private lazy val caseOf: Parser[Expr] =
    `case` ~>! expr ~ (`of` ~> repsep(alternative, `|`)) ^^ { case scrut ~ alts =>
      ECase(scrut, alts.to(ImmArray))
    }

  private val builtinFunctions = Map(
    "TRACE" -> BTrace,
    "ADD_NUMERIC" -> BAddNumeric,
    "SUB_NUMERIC" -> BSubNumeric,
    "MUL_NUMERIC" -> BMulNumeric,
    "DIV_NUMERIC" -> BDivNumeric,
    "ROUND_NUMERIC" -> BRoundNumeric,
    "CAST_NUMERIC" -> BCastNumeric,
    "SHIFT_NUMERIC" -> BShiftNumeric,
    "ADD_INT64" -> BAddInt64,
    "SUB_INT64" -> BSubInt64,
    "MUL_INT64" -> BMulInt64,
    "DIV_INT64" -> BDivInt64,
    "MOD_INT64" -> BModInt64,
    "EXP_INT64" -> BExpInt64,
    "INT64_TO_NUMERIC" -> BInt64ToNumeric,
    "NUMERIC_TO_INT64" -> BNumericToInt64,
    "DATE_TO_UNIX_DAYS" -> BDateToUnixDays,
    "UNIX_DAYS_TO_DATE" -> BUnixDaysToDate,
    "TIMESTAMP_TO_UNIX_MICROSECONDS" -> BTimestampToUnixMicroseconds,
    "UNIX_MICROSECONDS_TO_TIMESTAMP" -> BUnixMicrosecondsToTimestamp,
    "FOLDL" -> BFoldl,
    "FOLDR" -> BFoldr,
    "TEXTMAP_EMPTY" -> BTextMapEmpty,
    "TEXTMAP_INSERT" -> BTextMapInsert,
    "TEXTMAP_LOOKUP" -> BTextMapLookup,
    "TEXTMAP_DELETE" -> BTextMapDelete,
    "TEXTMAP_TO_LIST" -> BTextMapToList,
    "TEXTMAP_SIZE" -> BTextMapSize,
    "GENMAP_EMPTY" -> BGenMapEmpty,
    "GENMAP_INSERT" -> BGenMapInsert,
    "GENMAP_LOOKUP" -> BGenMapLookup,
    "GENMAP_DELETE" -> BGenMapDelete,
    "GENMAP_KEYS" -> BGenMapKeys,
    "GENMAP_VALUES" -> BGenMapValues,
    "GENMAP_SIZE" -> BGenMapSize,
    "EXPLODE_TEXT" -> BExplodeText,
    "IMPLODE_TEXT" -> BImplodeText,
    "APPEND_TEXT" -> BAppendText,
    "SHA256_TEXT" -> BSHA256Text,
    "KECCAK256_TEXT" -> BKECCAK256Text,
    "TEXT_TO_HEX" -> BEncodeHex,
    "HEX_TO_TEXT" -> BDecodeHex,
    "INT64_TO_TEXT" -> BInt64ToText,
    "NUMERIC_TO_TEXT" -> BNumericToText,
    "TIMESTAMP_TO_TEXT" -> BTimestampToText,
    "PARTY_TO_TEXT" -> BPartyToText,
    "DATE_TO_TEXT" -> BDateToText,
    "CONTRACT_ID_TO_TEXT" -> BContractIdToText,
    "PARTY_TO_QUOTED_TEXT" -> BPartyToQuotedText,
    "CODE_POINTS_TO_TEXT" -> BCodePointsToText,
    "TEXT_TO_PARTY" -> BTextToParty,
    "TEXT_TO_INT64" -> BTextToInt64,
    "TEXT_TO_NUMERIC" -> BTextToNumeric,
    "TEXT_TO_CODE_POINTS" -> BTextToCodePoints,
    "TEXT_TO_CONTRACT_ID" -> BTextToContractId,
    "ERROR" -> BError,
    "LESS_NUMERIC" -> BLessNumeric,
    "LESS_EQ_NUMERIC" -> BLessEqNumeric,
    "GREATER_NUMERIC" -> BGreaterNumeric,
    "GREATER_EQ_NUMERIC" -> BGreaterEqNumeric,
    "EQUAL_NUMERIC" -> BEqualNumeric,
    "EQUAL_LIST" -> BEqualList,
    "EQUAL_CONTRACT_ID" -> BEqualContractId,
    "EQUAL" -> BEqual,
    "LESS" -> BLess,
    "LESS_EQ" -> BLessEq,
    "GREATER" -> BGreater,
    "GREATER_EQ" -> BGreaterEq,
    "SECP256K1_BOOL" -> BSECP256K1Bool,
    "COERCE_CONTRACT_ID" -> BCoerceContractId,
    "ANY_EXCEPTION_MESSAGE" -> BAnyExceptionMessage,
    "SCALE_BIGNUMERIC" -> BScaleBigNumeric,
    "PRECISION_BIGNUMERIC" -> BPrecisionBigNumeric,
    "ADD_BIGNUMERIC" -> BAddBigNumeric,
    "SUB_BIGNUMERIC" -> BSubBigNumeric,
    "MUL_BIGNUMERIC" -> BMulBigNumeric,
    "DIV_BIGNUMERIC" -> BDivBigNumeric,
    "SHIFT_RIGHT_BIGNUMERIC" -> BShiftRightBigNumeric,
    "BIGNUMERIC_TO_NUMERIC" -> BBigNumericToNumeric,
    "NUMERIC_TO_BIGNUMERIC" -> BNumericToBigNumeric,
    "BIGNUMERIC_TO_TEXT" -> BBigNumericToText,
    "TYPE_REP_TYCON_NAME" -> BTypeRepTyConName,
    "FAIL_WITH_STATUS" -> BFailWithStatus,
  )

  private lazy val eCallInterface: Parser[ECallInterface] =
    `call_method` ~! `@` ~> fullIdentifier ~ id ~ expr0 ^^ { case ifaceId ~ name ~ body =>
      ECallInterface(interfaceId = ifaceId, methodName = name, value = body)
    }

  private lazy val experimental: Parser[EExperimental] =
    Id("experimental") ~>! id ~ typeParser.typ ^^ { case id ~ typ => EExperimental(id, typ) }

  /* Updates */

  private lazy val updatePure =
    Id("upure") ~>! argTyp ~ expr0 ^^ { case t ~ e =>
      UpdatePure(t, e)
    }

  private lazy val updateBlock =
    Id("ubind") ~>! bindings ~ expr ^^ { case bs ~ body =>
      UpdateBlock(bs, body)
    }

  private lazy val updateCreate =
    Id("create") ~! `@` ~> fullIdentifier ~ expr0 ^^ { case t ~ e =>
      UpdateCreate(t, e)
    }

  private lazy val updateCreateInterface =
    Id("create_by_interface") ~! `@` ~> fullIdentifier ~ expr0 ^^ { case iface ~ e =>
      UpdateCreateInterface(iface, e)
    }

  private lazy val updateFetch =
    Id("fetch_template") ~! `@` ~> fullIdentifier ~ expr0 ^^ { case t ~ e =>
      UpdateFetchTemplate(t, e)
    }

  private lazy val updateFetchInterface =
    Id("fetch_interface") ~! `@` ~> fullIdentifier ~ expr0 ^^ { case iface ~ e =>
      UpdateFetchInterface(iface, e)
    }

  private lazy val updateExercise =
    Id("exercise") ~! `@` ~> fullIdentifier ~ id ~ expr0 ~ expr0 ^^ { case t ~ choice ~ cid ~ arg =>
      UpdateExercise(t, choice, cid, arg)
    }

  private lazy val updateExerciseInterface =
    Id("exercise_interface") ~! `@` ~> fullIdentifier ~ id ~ expr0 ~ expr0 ^^ {
      case iface ~ choice ~ cid ~ arg =>
        UpdateExerciseInterface(iface, choice, cid, arg, None)
    }

  private lazy val updateExerciseInterfaceWithGuard =
    Id(
      "exercise_interface_with_guard"
    ) ~! `@` ~> fullIdentifier ~ id ~ expr0 ~ expr0 ~ expr0 ^^ {
      case iface ~ choice ~ cid ~ arg ~ guard =>
        UpdateExerciseInterface(iface, choice, cid, arg, Some(guard))
    }

  private lazy val updateExerciseByKey =
    Id("exercise_by_key") ~! `@` ~> fullIdentifier ~ id ~ expr0 ~ expr0 ^^ {
      case t ~ choice ~ key ~ arg => UpdateExerciseByKey(t, choice, key, arg)
    }

  private lazy val updateFetchByKey =
    Id("fetch_by_key") ~! `@` ~> fullIdentifier ^^ UpdateFetchByKey

  private lazy val updateLookupByKey =
    Id("lookup_by_key") ~! `@` ~> fullIdentifier ^^ UpdateLookupByKey

  private lazy val updateGetTime =
    Id("uget_time") ^^^ UpdateGetTime

  private lazy val updateLedgerTimeLT =
    Id("ledger_time_lt") ~> expr0 ^^ { case time =>
      UpdateLedgerTimeLT(time)
    }

  private lazy val updateEmbedExpr =
    Id("uembed_expr") ~> argTyp ~ expr0 ^^ { case t ~ e =>
      UpdateEmbedExpr(t, e)
    }

  private lazy val updateCatch =
    Id("try") ~>! argTyp ~ expr0 ~ `catch` ~ id ~ `->` ~ expr ^^ {
      case typ ~ body ~ _ ~ binder ~ _ ~ handler => UpdateTryCatch(typ, body, binder, handler)
    }

  private lazy val update: Parser[Update] =
    updatePure |
      updateBlock |
      updateCreate |
      updateCreateInterface |
      updateFetch |
      updateFetchInterface |
      updateExercise |
      updateExerciseInterface |
      updateExerciseInterfaceWithGuard |
      updateExerciseByKey |
      updateFetchByKey |
      updateLookupByKey |
      updateGetTime |
      updateLedgerTimeLT |
      updateEmbedExpr |
      updateCatch

  private lazy val int: Parser[Int] =
    acceptMatch("Int", { case Number(l) => l.toInt })

  private lazy val eLoc: Parser[Expr] =
    `loc` ~>! (`(` ~> dottedName) ~ (`,` ~> id) ~ (`,` ~> int) ~ (`,` ~> int) ~ (`,` ~> int) ~ (`,` ~> int) ~ (`)` ~> expr0) ^^ {
      case m ~ d ~ ls ~ cs ~ le ~ ce ~ e =>
        val location =
          Location(
            parserParameters.defaultPackageId,
            m,
            d.toString,
            (ls, cs),
            (le, ce),
          )
        ELocation(location, e)
    }
}

object ExprParser {

  private sealed trait EAppAgr extends Product with Serializable
  private final case class EAppExprArg(e: Expr) extends EAppAgr
  private final case class EAppTypArg(e: Type) extends EAppAgr

}
