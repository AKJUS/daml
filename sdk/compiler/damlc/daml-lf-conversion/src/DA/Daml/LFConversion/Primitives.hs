-- Copyright (c) 2025 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
-- SPDX-License-Identifier: Apache-2.0

{-# OPTIONS_GHC -Wno-incomplete-patterns #-}
{-# OPTIONS_GHC -Wno-overlapping-patterns #-} -- Because the pattern match checker is garbage


-- | The Daml-LF primitives, matched with their type, and using 'primitive' on the libraries side.
module DA.Daml.LFConversion.Primitives(convertPrim) where

import           DA.Daml.LFConversion.ConvertM
import           DA.Daml.LF.Ast
import           DA.Daml.LF.Ast.Numeric (numeric)
import           DA.Daml.UtilLF
import           DA.Pretty (renderPretty)
import qualified Data.Text as T
import qualified Data.List as L

convertPrim :: Version -> String -> Type -> ConvertM Expr
-- Update
convertPrim _ "UPure" (a1 :-> TUpdate a2) | a1 == a2 =
    pure $ ETmLam (varV1, a1) $ EUpdate $ UPure a1 $ EVar varV1
convertPrim _ "UBind" (t1@(TUpdate a1) :-> t2@(a2 :-> TUpdate b1) :-> TUpdate b2) | a1 == a2, b1 == b2 =
    pure $ ETmLam (varV1, t1) $ ETmLam (varV2, t2) $ EUpdate $ UBind (Binding (varV3, a1) (EVar varV1)) (EVar varV2 `ETmApp` EVar varV3)
convertPrim _ "UAbort" (TText :-> t@(TUpdate a)) =
    pure $ ETmLam (varV1, TText) $ EUpdate (UEmbedExpr a (EBuiltinFun BEError `ETyApp` t `ETmApp` EVar varV1))
convertPrim _ "UGetTime" (TUpdate TTimestamp) =
    pure $ EUpdate UGetTime
convertPrim _ "ULedgerTimeLT" (TTimestamp :-> TUpdate TBool) =
    pure $ ETmLam (varV1, TTimestamp) $ EUpdate (ULedgerTimeLT (EVar varV1))

-- Comparison
convertPrim _ "BEEqual" (a1 :-> a2 :-> TBool) | a1 == a2 =
    pure $ EBuiltinFun BEEqual `ETyApp` a1
convertPrim _ "BELess" (a1 :-> a2 :-> TBool) | a1 == a2 =
    pure $ EBuiltinFun BELess `ETyApp` a1
convertPrim _ "BELessEq" (a1 :-> a2 :-> TBool) | a1 == a2 =
    pure $ EBuiltinFun BELessEq `ETyApp` a1
convertPrim _ "BEGreater" (a1 :-> a2 :-> TBool) | a1 == a2 =
    pure $ EBuiltinFun BEGreater `ETyApp` a1
convertPrim _ "BEGreaterEq" (a1 :-> a2 :-> TBool) | a1 == a2 =
    pure $ EBuiltinFun BEGreaterEq `ETyApp` a1
convertPrim _ "BEEqualList" ((a1 :-> a2 :-> TBool) :-> TList a3 :-> TList a4 :-> TBool) | a1 == a2, a2 == a3, a3 == a4 =
    pure $ EBuiltinFun BEEqualList `ETyApp` a1
convertPrim _ "BESecp256k1Bool" (TText :-> TText :-> TText :-> TBool) =
    pure $ EBuiltinFun BESecp256k1Bool

-- Integer arithmetic
convertPrim _ "BEAddInt64" (TInt64 :-> TInt64 :-> TInt64) =
    pure $ EBuiltinFun BEAddInt64
convertPrim _ "BESubInt64" (TInt64 :-> TInt64 :-> TInt64) =
    pure $ EBuiltinFun BESubInt64
convertPrim _ "BEMulInt64" (TInt64 :-> TInt64 :-> TInt64) =
    pure $ EBuiltinFun BEMulInt64
convertPrim _ "BEDivInt64" (TInt64 :-> TInt64 :-> TInt64) =
    pure $ EBuiltinFun BEDivInt64
convertPrim _ "BEModInt64" (TInt64 :-> TInt64 :-> TInt64) =
    pure $ EBuiltinFun BEModInt64
convertPrim _ "BEExpInt64" (TInt64 :-> TInt64 :-> TInt64) =
    pure $ EBuiltinFun BEExpInt64

-- Time arithmetic
convertPrim _ "BETimestampToUnixMicroseconds" (TTimestamp :-> TInt64) =
    pure $ EBuiltinFun BETimestampToUnixMicroseconds
convertPrim _ "BEUnixMicrosecondsToTimestamp" (TInt64 :-> TTimestamp) =
    pure $ EBuiltinFun BEUnixMicrosecondsToTimestamp
convertPrim _ "BEDateToUnixDays" (TDate :-> TInt64) =
    pure $ EBuiltinFun BEDateToUnixDays
convertPrim _ "BEUnixDaysToDate" (TInt64 :-> TDate) =
    pure $ EBuiltinFun BEUnixDaysToDate

-- List operations
convertPrim _ "BEFoldl" ((b1 :-> a1 :-> b2) :-> b3 :-> TList a2 :-> b4) | a1 == a2, b1 == b2, b2 == b3, b3 == b4 =
    pure $ EBuiltinFun BEFoldl `ETyApp` a1 `ETyApp` b1
convertPrim _ "BEFoldr" ((a1 :-> b1 :-> b2) :-> b3 :-> TList a2 :-> b4) | a1 == a2, b1 == b2, b2 == b3, b3 == b4 =
    pure $ EBuiltinFun BEFoldr `ETyApp` a1 `ETyApp` b1

-- Error
convertPrim _ "BEError" (TText :-> t2) =
    pure $ ETyApp (EBuiltinFun BEError) t2

-- Text operations
convertPrim _ "BEToText" (TBuiltin x :-> TText) =
    pure $ EBuiltinFun $ BEToText x
convertPrim _ "BEExplodeText" (TText :-> TList TText) =
    pure $ EBuiltinFun BEExplodeText
convertPrim _ "BEImplodeText" (TList TText :-> TText) =
    pure $ EBuiltinFun BEImplodeText
convertPrim _ "BEAppendText" (TText :-> TText :-> TText) =
    pure $ EBuiltinFun BEAppendText
convertPrim _ "BETrace" (TText :-> a1 :-> a2) | a1 == a2 =
    pure $ EBuiltinFun BETrace `ETyApp` a1
convertPrim _ "BESha256Text" (TText :-> TText) =
    pure $ EBuiltinFun BESha256Text
convertPrim _ "BEKecCak256Text" (TText :-> TText) =
    pure $ EBuiltinFun BEKecCak256Text
convertPrim _ "BEEncodeHex" (TText :-> TText) =
    pure $ EBuiltinFun BEEncodeHex
convertPrim _ "BEDecodeHex" (TText :-> TText) =
    pure $ EBuiltinFun BEDecodeHex
convertPrim _ "BETextToParty" (TText :-> TOptional TParty) =
    pure $ EBuiltinFun BETextToParty
convertPrim _ "BETextToInt64" (TText :-> TOptional TInt64) =
    pure $ EBuiltinFun BETextToInt64
convertPrim _ "BETextToCodePoints" (TText :-> TList TInt64) =
    pure $ EBuiltinFun BETextToCodePoints
convertPrim _ "BETextToContractId" (TText :-> TContractId a) =
    pure $ EBuiltinFun BETextToContractId `ETyApp` a
convertPrim _ "BECodePointsToText" (TList TInt64 :-> TText) =
    pure $ EBuiltinFun BECodePointsToText

-- Map operations

convertPrim _ "BETextMapEmpty" (TTextMap a) =
    pure $ EBuiltinFun BETextMapEmpty `ETyApp` a
convertPrim _ "BETextMapInsert"  (TText :-> a1 :-> TTextMap a2 :-> TTextMap a3) | a1 == a2, a2 == a3 =
    pure $ EBuiltinFun BETextMapInsert `ETyApp` a1
convertPrim _ "BETextMapLookup" (TText :-> TTextMap a1 :-> TOptional a2) | a1 == a2 =
    pure $ EBuiltinFun BETextMapLookup `ETyApp` a1
convertPrim _ "BETextMapDelete" (TText :-> TTextMap a1 :-> TTextMap a2) | a1 == a2 =
    pure $ EBuiltinFun BETextMapDelete `ETyApp` a1
convertPrim _ "BETextMapToList" (TTextMap a1 :-> TList (TTextMapEntry a2)) | a1 == a2  =
    pure $ EBuiltinFun BETextMapToList `ETyApp` a1
convertPrim _ "BETextMapSize" (TTextMap a :-> TInt64) =
    pure $ EBuiltinFun BETextMapSize `ETyApp` a


convertPrim _ "BEGenMapEmpty" (TGenMap a b) =
    pure $ EBuiltinFun BEGenMapEmpty `ETyApp` a `ETyApp` b
convertPrim _ "BEGenMapInsert"  (a :-> b :-> TGenMap a1 b1 :-> TGenMap a2 b2) | a == a1, a == a2, b == b1, b == b2 =
    pure $ EBuiltinFun BEGenMapInsert `ETyApp` a `ETyApp` b
convertPrim _ "BEGenMapLookup" (a1 :-> TGenMap a b :-> TOptional b1) | a == a1, b == b1 =
    pure $ EBuiltinFun BEGenMapLookup `ETyApp` a `ETyApp` b
convertPrim _ "BEGenMapDelete" (a2 :-> TGenMap a b :-> TGenMap a1 b1) | a == a1, a == a2, b == b1 =
    pure $ EBuiltinFun BEGenMapDelete `ETyApp` a `ETyApp` b
convertPrim _ "BEGenMapKeys" (TGenMap a b :-> TList a1) | a == a1 =
    pure $ EBuiltinFun BEGenMapKeys `ETyApp` a `ETyApp` b
convertPrim _ "BEGenMapValues" (TGenMap a b :-> TList b1) | b == b1 =
    pure $ EBuiltinFun BEGenMapValues `ETyApp` a `ETyApp` b
convertPrim _ "BEGenMapSize" (TGenMap a b :-> TInt64) =
    pure $ EBuiltinFun BEGenMapSize `ETyApp` a `ETyApp` b

convertPrim _ "BECoerceContractId" (TContractId a :-> TContractId b) =
    pure $ EBuiltinFun BECoerceContractId `ETyApp` a `ETyApp` b

-- Numeric primitives. These are polymorphic in the scale.
convertPrim _ "BEAddNumeric" (TNumeric n1 :-> TNumeric n2 :-> TNumeric n3) | n1 == n2, n1 == n3 =
    pure $ ETyApp (EBuiltinFun BEAddNumeric) n1
convertPrim _ "BESubNumeric" (TNumeric n1 :-> TNumeric n2 :-> TNumeric n3) | n1 == n2, n1 == n3 =
    pure $ ETyApp (EBuiltinFun BESubNumeric) n1
convertPrim _ "BEMulNumeric" (TNumeric n0 :-> TNumeric n1 :-> TNumeric n2 :-> TNumeric n3) | n0 == n3 =
    pure $ EBuiltinFun BEMulNumeric `ETyApp` n1 `ETyApp` n2 `ETyApp` n3
convertPrim _ "BEDivNumeric" (TNumeric n0 :-> TNumeric n1 :-> TNumeric n2 :-> TNumeric n3) | n0 == n3 =
    pure $ EBuiltinFun BEDivNumeric `ETyApp` n1 `ETyApp` n2 `ETyApp` n3
convertPrim _ "BERoundNumeric" (TInt64 :-> TNumeric n1 :-> TNumeric n2) | n1 == n2 =
    pure $ ETyApp (EBuiltinFun BERoundNumeric) n1
convertPrim _ "BECastNumeric" (TNumeric n0 :-> TNumeric n1 :-> TNumeric n2) | n0 == n2 =
    pure $ EBuiltinFun BECastNumeric `ETyApp` n1 `ETyApp` n2
convertPrim _ "BEShiftNumeric" (TNumeric n0 :-> TNumeric n1 :-> TNumeric n2) | n0 == n2 =
    pure $ EBuiltinFun BEShiftNumeric `ETyApp` n1 `ETyApp` n2
convertPrim _ "BEInt64ToNumeric" (TNumeric n0 :-> TInt64 :-> TNumeric n) | n0 == n =
    pure $ ETyApp (EBuiltinFun BEInt64ToNumeric) n
convertPrim _ "BENumericToInt64" (TNumeric n :-> TInt64) =
    pure $ ETyApp (EBuiltinFun BENumericToInt64) n
convertPrim _ "BENumericToText" (TNumeric n :-> TText) =
    pure $ ETyApp (EBuiltinFun BENumericToText) n
convertPrim _ "BETextToNumeric" (TNumeric n0 :-> TText :-> TOptional (TNumeric n)) | n0 == n =
    pure $ ETyApp (EBuiltinFun BETextToNumeric) n
convertPrim _ "BENumericOne" (TNumeric (TNat n0))  =
    pure $ EBuiltinFun $ BENumeric $ numeric n (10 ^ n)
  where n = fromTypeLevelNat n0

convertPrim version "BEScaleBigNumeric" ty@(TBigNumeric :-> TInt64) =
    pure $
      whenRuntimeSupports version featureBigNumeric ty $
        EBuiltinFun BEScaleBigNumeric
convertPrim version "BEPrecisionBigNumeric" ty@(TBigNumeric :-> TInt64) =
    pure $
      whenRuntimeSupports version featureBigNumeric ty $
        EBuiltinFun BEPrecisionBigNumeric
convertPrim version "BEAddBigNumeric" ty@(TBigNumeric :-> TBigNumeric :-> TBigNumeric) =
    pure $
      whenRuntimeSupports version featureBigNumeric ty $
        EBuiltinFun BEAddBigNumeric
convertPrim version "BESubBigNumeric" ty@(TBigNumeric :-> TBigNumeric :-> TBigNumeric) =
    pure $
      whenRuntimeSupports version featureBigNumeric ty $
        EBuiltinFun BESubBigNumeric
convertPrim version "BEMulBigNumeric" ty@(TBigNumeric :-> TBigNumeric :-> TBigNumeric) =
    pure $
      whenRuntimeSupports version featureBigNumeric ty $
        EBuiltinFun BEMulBigNumeric
convertPrim version "BEDivBigNumeric" ty@(TInt64 :-> TRoundingMode :-> TBigNumeric :-> TBigNumeric :-> TBigNumeric) =
    pure $
      whenRuntimeSupports version featureBigNumeric ty $
        EBuiltinFun BEDivBigNumeric
convertPrim version "BEShiftRightBigNumeric" ty@(TInt64 :-> TBigNumeric :-> TBigNumeric) =
    pure $
      whenRuntimeSupports version featureBigNumeric ty $
        EBuiltinFun BEShiftRightBigNumeric
convertPrim version "BENumericToBigNumeric" ty@(TNumeric n :-> TBigNumeric) =
    pure $
      whenRuntimeSupports version featureBigNumeric ty $
        EBuiltinFun BENumericToBigNumeric `ETyApp` n
convertPrim version "BEBigNumericToNumeric" ty@(TNumeric n0 :-> TBigNumeric :-> TNumeric n) | n0 == n =
    pure $
      whenRuntimeSupports version featureBigNumeric ty $
        EBuiltinFun BEBigNumericToNumeric `ETyApp` n

-- Conversion from ContractId to Text

convertPrim _ "BEContractIdToText" (TContractId t :-> TOptional TText) =
    pure $ ETyApp (EBuiltinFun BEContractIdToText) t

-- Template Desugaring.

convertPrim _ "UCreate" (TCon template :-> TUpdate (TContractId (TCon template')))
    | template == template' =
    pure $
    ETmLam (mkVar "this", TCon template) $
    EUpdate $ UCreate template (EVar (mkVar "this"))

convertPrim _ "UCreateInterface" (TCon interface :-> TUpdate (TContractId (TCon interface')))
    | interface == interface' =
    pure $
    ETmLam (mkVar "this", TCon interface) $
    EUpdate $ UCreateInterface interface (EVar (mkVar "this"))

convertPrim _ "UFetch" (TContractId (TCon template) :-> TUpdate (TCon template'))
    | template == template' =
    pure $
    ETmLam (mkVar "this", TContractId (TCon template)) $
    EUpdate $ UFetch template (EVar (mkVar "this"))

convertPrim _ "UFetchInterface" (TContractId (TCon iface) :-> TUpdate (TCon iface'))
    | iface == iface' =
    pure $
    ETmLam (mkVar "this", TContractId (TCon iface)) $
    EUpdate $ UFetchInterface iface (EVar (mkVar "this"))

convertPrim _ "UExercise"
    (TContractId (TCon template) :-> TCon choice :-> TUpdate _returnTy) =
    pure $
    ETmLam (mkVar "this", TContractId (TCon template)) $
    ETmLam (mkVar "arg", TCon choice) $
    EUpdate $ UExercise template choiceName (EVar (mkVar "this")) (EVar (mkVar "arg"))
  where
    choiceName = ChoiceName (T.intercalate "." $ unTypeConName $ qualObject choice)

convertPrim _ "UExerciseInterface"
    (   TContractId (TCon iface)
    :-> TCon choice
    :->  TUpdate _returnTy) =
    pure $
    ETmLam (mkVar "this", TContractId (TCon iface)) $
    ETmLam (mkVar "arg", TCon choice) $
    EUpdate $ UExerciseInterface
        { exeInterface  = iface
        , exeChoice     = choiceName
        , exeContractId = EVar (mkVar "this")
        , exeArg        = EVar (mkVar "arg")
        , exeGuard      = Nothing
        }
  where
    choiceName = ChoiceName (T.intercalate "." $ unTypeConName $ qualObject choice)

convertPrim version "UExerciseInterfaceGuarded" _
    | not (version `supports` featureExtendedInterfaces) =
        conversionError $ OnlySupportedOnDev "Guards on choice exercises are"

convertPrim _ "UExerciseInterfaceGuarded"
    (   TContractId (TCon iface)
    :-> TCon choice
    :-> (TCon iface2 :-> TBuiltin BTBool)
    :->  TUpdate _returnTy)
    | iface == iface2 =
    pure $
    ETmLam (mkVar "this", TContractId (TCon iface)) $
    ETmLam (mkVar "arg", TCon choice) $
    ETmLam (mkVar "pred", TCon iface :-> TBuiltin BTBool) $
    EUpdate $ UExerciseInterface
        { exeInterface  = iface
        , exeChoice     = choiceName
        , exeContractId = EVar (mkVar "this")
        , exeArg        = EVar (mkVar "arg")
        , exeGuard      = Just (EVar (mkVar "pred"))
        }
  where
    choiceName = ChoiceName (T.intercalate "." $ unTypeConName $ qualObject choice)

convertPrim _ "UExerciseByKey"
    (tProxy@(TApp _ (TCon template)) :-> key :-> TCon choice :-> TUpdate _returnTy) =
    pure $
    ETmLam (mkVar "_", tProxy) $
    ETmLam (mkVar "key", key) $
    ETmLam (mkVar "arg", TCon choice) $
    EUpdate $ UExerciseByKey template choiceName (EVar (mkVar "key")) (EVar (mkVar "arg"))
  where
    choiceName = ChoiceName (T.intercalate "." $ unTypeConName $ qualObject choice)

convertPrim _ "ULookupByKey" (_ :-> TUpdate (TOptional (TContractId (TCon template)))) =
    pure $ EUpdate $  ULookupByKey template

convertPrim _ "UFetchByKey"
    (_ :-> TUpdate (TTuple2 (TContractId (TCon template)) ty2))
    | ty2 == TCon template =
    pure $ EUpdate $ UFetchByKey template

convertPrim _ "ETemplateTypeRep"
    (tProxy@(TApp _ tCon@(TCon _)) :-> TTypeRep) =
    pure $
    ETmLam (mkVar "_", tProxy) $
    ETypeRep tCon

convertPrim _ "EFromAnyTemplate"
    (TAny :-> TOptional (TCon template)) =
    pure $
    ETmLam (mkVar "any", TAny) $
    EFromAny (TCon template) (EVar $ mkVar "any")

convertPrim _ "EFromAnyChoice"
    (tProxy :-> TAny :-> TOptional choice) =
    pure $
    ETmLam (mkVar "_", tProxy) $
    ETmLam (mkVar "any", TAny) $
    EFromAny choice (EVar $ mkVar "any")

convertPrim _ "EFromAnyContractKey"
    (tProxy@(TApp _ (TCon _)) :-> TAny :-> TOptional key) =
    pure $
    ETmLam (mkVar "_", tProxy) $
    ETmLam (mkVar "any", TAny) $
    EFromAny key (EVar $ mkVar "any")

convertPrim _ "EFromAnyView"
    (tProxy :-> TAny :-> TOptional view) =
    pure $
    ETmLam (mkVar "_", tProxy) $
    ETmLam (mkVar "any", TAny) $
    EFromAny view (EVar $ mkVar "any")

convertPrim _ "EToAnyTemplate"
    (TCon template :-> TAny) =
    pure $
    ETmLam (mkVar "template", TCon template) $
    EToAny (TCon template) (EVar $ mkVar "template")

convertPrim _ "EToAnyChoice"
    (tProxy :-> choice :-> TAny) =
    pure $
    ETmLam (mkVar "_", tProxy) $
    ETmLam (mkVar "choice", choice) $
    EToAny choice (EVar $ mkVar "choice")

convertPrim _ "EToAnyContractKey"
    (tProxy@(TApp _ (TCon _)) :-> key :-> TAny) =
    pure $
    ETmLam (mkVar "_", tProxy) $
    ETmLam (mkVar "key", key) $
    EToAny key (EVar $ mkVar "key")

convertPrim _ "EToAnyView"
    (tProxy :-> view :-> TAny) =
    pure $
    ETmLam (mkVar "_", tProxy) $
    ETmLam (mkVar "view", view) $
    EToAny view (EVar $ mkVar "view")

convertPrim _ "EInterfaceTemplateTypeRep" (TCon interface :-> TTypeRep) =
    pure $
    ETmLam (mkVar "this", TCon interface) $
    EInterfaceTemplateTypeRep interface (EVar (mkVar "this"))

convertPrim _ "ESignatoryInterface" (TCon interface :-> TList TParty) =
    pure $
    ETmLam (mkVar "this", TCon interface) $
    ESignatoryInterface interface (EVar (mkVar "this"))

convertPrim _ "EObserverInterface" (TCon interface :-> TList TParty) =
    pure $
    ETmLam (mkVar "this", TCon interface) $
    EObserverInterface interface (EVar (mkVar "this"))

-- Exceptions
convertPrim _ "BEAnyExceptionMessage" (TBuiltin BTAnyException :-> TText) =
    pure $ EBuiltinFun BEAnyExceptionMessage

convertPrim _ "EThrow" (ty1 :-> ty2) =
    pure $ ETmLam (mkVar "x", ty1) (EThrow ty2 ty1 (EVar (mkVar "x")))
convertPrim _ "EToAnyException" (ty :-> TBuiltin BTAnyException) =
    pure $ ETmLam (mkVar "x", ty) (EToAnyException ty (EVar (mkVar "x")))
convertPrim _ "EFromAnyException" (TBuiltin BTAnyException :-> TOptional ty) =
    pure $ ETmLam (mkVar "x", TBuiltin BTAnyException) (EFromAnyException ty (EVar (mkVar "x")))

convertPrim _ "UTryCatch" ((TUnit :-> TUpdate t1) :-> (TBuiltin BTAnyException :-> TOptional (TUpdate t2)) :-> TUpdate t3)
    | t1 == t2, t2 == t3
        = pure
        $ ETmLam (mkVar "t", TUnit :-> TUpdate t1)
        $ ETmLam (mkVar "c", TBuiltin BTAnyException :-> TOptional (TUpdate t2))
        $ EUpdate
        $ UTryCatch t3
            (EVar (mkVar "t") `ETmApp` EUnit)
            (mkVar "x")
            (EVar (mkVar "c") `ETmApp` EVar (mkVar "x"))

convertPrim _ "EToInterface" (TCon tpid :-> TCon iface) =
    pure $
      ETmLam (mkVar "t", TCon tpid) $
        EToInterface iface tpid (EVar $ mkVar "t")

convertPrim _ "EFromInterface" (TCon iface :-> TOptional (TCon tpid)) =
    pure $
      ETmLam (mkVar "i", TCon iface) $
        EFromInterface iface tpid (EVar $ mkVar "i")

convertPrim _ "EUnsafeFromInterface" (TContractId (TCon iface) :-> TCon iface1 :-> TCon tpid)
    | iface == iface1
        = pure
        $ ETmLam (mkVar "cid", TContractId (TCon iface))
        $ ETmLam (mkVar "i", TCon iface)
        $ EUnsafeFromInterface iface tpid (EVar $ mkVar "cid") (EVar $ mkVar "i")

convertPrim _ "EToRequiredInterface" (TCon subIface :-> TCon superIface) =
    pure $
      ETmLam (mkVar "i", TCon subIface) $
        EToRequiredInterface superIface subIface (EVar $ mkVar "i")

convertPrim _ "EToRequiredInterface" ty@(TCon _ :-> retTy) =
    pure $ runtimeError ty $ "Tried to convert to a required interface '" <> T.pack (renderPretty retTy) <> "', but that type is not an interface."

convertPrim _ "EFromRequiredInterface" (TCon superIface :-> TOptional (TCon subIface)) =
    pure $
      ETmLam (mkVar "i", TCon superIface) $
        EFromRequiredInterface superIface subIface (EVar $ mkVar "i")

convertPrim _ "EFromRequiredInterface" ty@(fromTy :-> TOptional (TCon _)) =
    pure $ runtimeError ty $ "Tried to convert from a required interface '" <> T.pack (renderPretty fromTy) <> "', but that type is not an interface."

convertPrim _ "EUnsafeFromRequiredInterface" (TContractId (TCon superIface) :-> TCon superIface1 :-> TCon subIface)
    | superIface == superIface1
        = pure
        $ ETmLam (mkVar "cid", TContractId (TCon superIface))
        $ ETmLam (mkVar "i", TCon superIface)
        $ EUnsafeFromRequiredInterface superIface subIface (EVar $ mkVar "cid") (EVar $ mkVar "i")

convertPrim _ "EUnsafeFromRequiredInterface" ty@(TContractId fromTy :-> fromTy1 :-> TCon _)
    | fromTy == fromTy1
        = pure $ runtimeError ty $ "Tried to unsafely convert from a required interface '" <> T.pack (renderPretty fromTy) <> "', but that type is not an interface."

convertPrim _ "ETypeRepTyConName" (TTypeRep :-> TOptional TText) = pure $ EBuiltinFun BETypeRepTyConName

convertPrim _ "EViewInterface" (TCon iface :-> _) =
    pure $
      ETmLam (mkVar "i", TCon iface) $
        EViewInterface iface (EVar $ mkVar "i")

convertPrim version "EChoiceController" _
    | not (version `supports` featureChoiceFuncs) =
        conversionError $ OnlySupportedOnDev "'choiceController' is"

convertPrim _ "EChoiceController"
    (TCon template :-> TCon choice :-> TList TParty) =
    pure $
    ETmLam (mkVar "template", TCon template) $
    ETmLam (mkVar "choice", TCon choice) $
    EChoiceController template choiceName (EVar (mkVar "template")) (EVar (mkVar "choice"))
  where
    choiceName = ChoiceName (T.intercalate "." $ unTypeConName $ qualObject choice)

convertPrim version "EChoiceObserver" _
    | not (version `supports` featureChoiceFuncs) =
        conversionError $ OnlySupportedOnDev "'choiceObserver' is"

convertPrim _ "EChoiceObserver"
    (TCon template :-> TCon choice :-> TList TParty) =
    pure $
    ETmLam (mkVar "template", TCon template) $
    ETmLam (mkVar "choice", TCon choice) $
    EChoiceObserver template choiceName (EVar (mkVar "template")) (EVar (mkVar "choice"))
  where
    choiceName = ChoiceName (T.intercalate "." $ unTypeConName $ qualObject choice)

convertPrim _ "EFailWithStatus"
    (TText :-> TFailureCategory :-> TText :-> TTextMap TText :-> retTy) =
    pure $ EBuiltinFun BEFailWithStatus `ETyApp` retTy

convertPrim (isDevVersion->True) (L.stripPrefix "$" -> Just builtin) typ =
    pure $
      EExperimental (T.pack builtin) typ

-- Unknown primitive.
convertPrim _ x ty = conversionError $ UnknownPrimitive x ty

-- | Some builtins are only supported in specific versions of Daml-LF.
whenRuntimeSupports :: Version -> Feature -> Type -> Expr -> Expr
whenRuntimeSupports version feature t e
    | version `supports` feature = e
    | otherwise = runtimeError t (featureErrorMessage feature)

runtimeError :: Type -> T.Text -> Expr
runtimeError t msg = ETmApp (ETyApp (EBuiltinFun BEError) t) (EBuiltinFun (BEText msg))

featureErrorMessage :: Feature -> T.Text
featureErrorMessage (Feature name versionReq _) =
    mconcat
        [ name
        , " only supported when compiling to Daml-LF versions "
        , T.pack (renderFeatureVersionReq versionReq)
        ]
