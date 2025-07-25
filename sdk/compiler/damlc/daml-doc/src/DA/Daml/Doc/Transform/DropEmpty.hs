-- Copyright (c) 2025 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
-- SPDX-License-Identifier: Apache-2.0

module DA.Daml.Doc.Transform.DropEmpty
    ( dropEmptyDocs
    ) where

import DA.Daml.Doc.Types
import Data.Maybe

-- | Drop modules that are devoid of any documentation.
dropEmptyDocs :: ModuleDoc -> Maybe ModuleDoc
dropEmptyDocs m
  | isModuleEmpty m = Nothing
  | otherwise = Just m

isModuleEmpty :: ModuleDoc -> Bool
isModuleEmpty ModuleDoc{..} =
    all isTemplateEmpty md_templates
    && all isADTEmpty md_adts
    && all isFunctionEmpty md_functions
    && all isClassEmpty md_classes
    -- If the module description is the only documentation item, the
    -- docs aren't very useful.
    && (isNothing md_descr
        || null md_adts && null md_templates
        && null md_functions && null md_classes)

isTemplateEmpty :: TemplateDoc -> Bool
isTemplateEmpty TemplateDoc{..} =
    null td_descr
    && all isFieldEmpty td_payload
    && all isChoiceEmpty td_choices

isChoiceEmpty :: ChoiceDoc -> Bool
isChoiceEmpty ChoiceDoc{..} =
    null cd_descr
    && all isFieldEmpty cd_fields

isClassEmpty :: ClassDoc -> Bool
isClassEmpty ClassDoc{..} =
    null cl_descr
    && all isClassMethodEmpty cl_methods

isClassMethodEmpty :: ClassMethodDoc -> Bool
isClassMethodEmpty ClassMethodDoc{..} =
    null cm_descr

isADTEmpty :: ADTDoc -> Bool
isADTEmpty = \case
    ADTDoc{..} ->
        null ad_descr
        && all isADTConstrEmpty ad_constrs
    TypeSynDoc{..} ->
        null ad_descr

isADTConstrEmpty :: ADTConstr -> Bool
isADTConstrEmpty = \case
    PrefixC{..} ->
        null ac_descr
    RecordC{..} ->
        null ac_descr
        && all isFieldEmpty ac_fields

isFieldEmpty :: FieldDoc -> Bool
isFieldEmpty FieldDoc{..} =
    isNothing fd_descr

isFunctionEmpty :: FunctionDoc -> Bool
isFunctionEmpty FunctionDoc{..} =
    null fct_descr

