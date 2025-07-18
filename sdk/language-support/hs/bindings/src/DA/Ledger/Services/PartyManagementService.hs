-- Copyright (c) 2025 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
-- SPDX-License-Identifier: Apache-2.0

{-# LANGUAGE DuplicateRecordFields #-}

module DA.Ledger.Services.PartyManagementService (
    getParticipantId, ParticipantId(..),
    listKnownParties, PartyDetails(..),
    allocateParty, AllocatePartyRequest(..),
    ) where

import DA.Ledger.Convert
import DA.Ledger.GrpcWrapUtils
import DA.Ledger.LedgerService
import DA.Ledger.Types
import Data.Functor
import Data.Text.Lazy (Text)
import qualified Data.Text.Lazy as LT
import Network.GRPC.HighLevel.Generated
import qualified Data.Aeson as A
import Data.Aeson ((.:))
import qualified Com.Daml.Ledger.Api.V2.Admin.PartyManagementService as LL

getParticipantId :: LedgerService ParticipantId
getParticipantId =
    makeLedgerService $ \timeout config mdm -> do
    withGRPCClient config $ \client -> do
        service <- LL.partyManagementServiceClient client
        let LL.PartyManagementService{partyManagementServiceGetParticipantId=rpc} = service
        let request = LL.GetParticipantIdRequest{}
        rpc (ClientNormalRequest request timeout mdm)
            >>= unwrap
            <&> \(LL.GetParticipantIdResponse text) -> ParticipantId text

data PartyDetails = PartyDetails
    { party :: Party
    , isLocal :: Bool
    } deriving (Eq,Ord,Show)

instance A.FromJSON PartyDetails where
  parseJSON =
    A.withObject "PartyDetails" $ \v ->
      PartyDetails
        <$> v .: "identifier"
        <*> v .: "isLocal"

listKnownParties :: LedgerService [PartyDetails]
listKnownParties =
    makeLedgerService $ \timeout config mdm -> do
    withGRPCClient config $ \client -> do
        service <- LL.partyManagementServiceClient client
        let LL.PartyManagementService{partyManagementServiceListKnownParties=rpc} = service
        let loop token = do
            let request = 
                  LL.ListKnownPartiesRequest
                    { listKnownPartiesRequestIdentityProviderId = ""
                    , listKnownPartiesRequestPageToken = token
                    -- 0 means the server chooses the page size
                    , listKnownPartiesRequestPageSize = 0 
                    }
            result <- rpc (ClientNormalRequest request timeout mdm)
            LL.ListKnownPartiesResponse parties nextPageToken <- unwrap result
            if LT.null nextPageToken
                then pure parties
                else (parties <>) <$> loop nextPageToken
        parties <- loop ""
        either (fail . show) return $ raiseList raisePartyDetails parties

raisePartyDetails :: LL.PartyDetails -> Perhaps PartyDetails
raisePartyDetails = \case
    LL.PartyDetails{..} -> do
        party <- raiseParty partyDetailsParty
        let isLocal = partyDetailsIsLocal
        return $ PartyDetails {..}

data AllocatePartyRequest = AllocatePartyRequest
    { partyIdHint :: Text }

allocateParty :: AllocatePartyRequest -> LedgerService PartyDetails
allocateParty request =
    makeLedgerService $ \timeout config mdm -> do
    withGRPCClient config $ \client -> do
        service <- LL.partyManagementServiceClient client
        let LL.PartyManagementService{partyManagementServiceAllocateParty=rpc} = service
        rpc (ClientNormalRequest (lowerRequest request) timeout mdm)
            >>= unwrap
            >>= \case
            LL.AllocatePartyResponse Nothing ->
                fail "AllocatePartyResponse, party_details field is missing"
            LL.AllocatePartyResponse (Just x) ->
                either (fail . show) return $ raisePartyDetails x
    where
        lowerRequest :: AllocatePartyRequest -> LL.AllocatePartyRequest
        lowerRequest AllocatePartyRequest{..} = do
            let allocatePartyRequestPartyIdHint = partyIdHint
            let allocatePartyRequestLocalMetadata = Nothing
            let allocatePartyRequestIdentityProviderId = ""
            let allocatePartyRequestSynchronizerId = ""
            let allocatePartyRequestUserId = ""
            LL.AllocatePartyRequest {..}
