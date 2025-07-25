-- Copyright (c) 2022 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
-- SPDX-License-Identifier: Apache-2.0

create schema debug;

-- -------------------
--  HELPER FUNCTIONS
-- -------------------

-- convert bigint to the time format used in canton logs
create or replace function debug.canton_timestamp(bigint) returns varchar as
$$
select to_char(to_timestamp($1/1000000.0) at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"');
$$
  language sql
  immutable
  returns null on null input;

-- convert the integer representation to the name of the topology mapping
create or replace function debug.topology_mapping(integer) returns char as
$$
select
  case
    when $1 = 1 then 'NamespaceDelegation'
    -- 2 was IdentifierDelegation
    when $1 = 3 then 'DecentralizedNamespaceDefinition'
    when $1 = 4 then 'OwnerToKeyMapping'
    when $1 = 5 then 'SynchronizerTrustCertificate'
    when $1 = 6 then 'ParticipantSynchronizerPermission'
    when $1 = 7 then 'PartyHostingLimits'
    when $1 = 8 then 'VettedPackages'
    when $1 = 9 then 'PartyToParticipant'
    -- 10 was AuthorityOf
    when $1 = 11 then 'SynchronizerParameters'
    when $1 = 12 then 'MediatorSynchronizerState'
    when $1 = 13 then 'SequencerSynchronizerState'
    -- 14 was OffboardParticipant
    when $1 = 15 then 'PurgeTopologyTransaction'
    -- 16 was TrafficControlState
    when $1 = 17 then 'DynamicSequencingParametersState'
    when $1 = 18 then 'PartyToKeyMapping'
    else $1::text
  end;
$$
  language sql
  immutable
  returns null on null input;

-- convert the integer representation to the TopologyChangeOp name.
create or replace function debug.topology_change_op(integer) returns varchar as
$$
select
  case
    when $1 = 1 then 'Remove'
    when $1 = 2 then 'Replace'
    else $1::text
  end;
$$
  language sql
  immutable
  returns null on null input;

-- convert the integer representation to the name of the key purpose
create or replace function debug.key_purpose(integer) returns varchar as
$$
select
  case
    when $1 = 0 then 'Signing'
    when $1 = 1 then 'Encryption'
    else $1::text
  end;
$$
  language sql
  immutable
  returns null on null input;

-- convert the integer representation to the name of the signing key usage
create or replace function debug.key_usage(integer) returns varchar as
$$
select
case
  when $1 = 0 then 'Namespace'
  -- 1 was IdentityDelegation
  when $1 = 2 then 'SequencerAuthentication'
  when $1 = 3 then 'Protocol'
  when $1 = 4 then 'ProofOfOwnership'
  else $1::text
end;
$$
  language sql
  immutable
  returns null on null input;

-- convert the integer representation to the name of the signing key usage
create or replace function debug.key_usages(integer[]) returns varchar[] as
$$
select array_agg(debug.key_usage(m)) from unnest($1) as m;
$$
  language sql
  stable
  returns null on null input;

-- resolve an interned string to the textual representation
create or replace function debug.resolve_common_static_string(integer) returns varchar as
$$
select string from common_static_strings where id = $1;
$$
  language sql
  stable
  returns null on null input;

-- resolve an interned sequencer member id to the textual representation
create or replace function debug.resolve_sequencer_member(integer) returns varchar as
$$
select member from sequencer_members where id = $1;
$$
  language sql
  stable
  returns null on null input;

-- resolve multiple interned sequencer member ids to the textual representation
create or replace function debug.resolve_sequencer_members(integer[]) returns varchar[] as
$$
select array_agg(debug.resolve_sequencer_member(m)) from unnest($1) as m;
$$
  language sql
  stable
  returns null on null input;

-- -------------------
-- VIEWS
-- -------------------

-- Each regular canton table also has a view representation in the debug schema.
-- This way, when debugging, one doesn't have to think, whether there is a more convenient
-- debug view or just the regular table.
-- There are views also for tables that don't yet have columns that warrant a conversion (eg canton timestamp),
-- but future changes to tables should be consciously made to the debug views as well.

create or replace view debug.par_daml_packages as
  select
    package_id,
    data,
    name,
    version,
    uploaded_at,
    package_size
  from par_daml_packages;

create or replace view debug.par_dars as
  select
    main_package_id,
    data,
    description,
    name,
    version
  from par_dars;

create or replace view debug.par_dar_packages as
select main_package_id , package_id from par_dar_packages;

create or replace view debug.common_crypto_private_keys as
  select
    key_id,
    wrapper_key_id,
    debug.key_purpose(purpose) as purpose,
    data,
    name
  from common_crypto_private_keys;

create or replace view debug.common_kms_metadata_store as
  select
    fingerprint,
    kms_key_id,
    debug.key_purpose(purpose) as purpose,
    debug.key_usages(key_usage) as key_usage
  from common_kms_metadata_store;

create or replace view debug.common_crypto_public_keys as
  select
    key_id,
    debug.key_purpose(purpose) as purpose,
    data,
    name
  from common_crypto_public_keys;

create or replace view debug.par_contracts as
  select
    lower(encode(contract_id, 'hex')) as contract_id,
    instance,
    package_id,
    template_id
  from par_contracts;

create or replace view debug.common_node_id as
  select
    identifier,
    namespace
  from common_node_id;

create or replace view debug.common_party_metadata as
  select
    party_id,
    participant_id,
    submission_id,
    notified,
    debug.canton_timestamp(effective_at) as effective_at
  from common_party_metadata;

create or replace view debug.common_topology_dispatching as
  select
    store_id,
    debug.canton_timestamp(watermark_ts) as watermark_ts
  from common_topology_dispatching;

create or replace view debug.par_active_contracts as
  select
    debug.resolve_common_static_string(synchronizer_idx) as synchronizer_idx,
    lower(encode(contract_id, 'hex')) as contract_id,
    change, operation,
    debug.canton_timestamp(ts) as ts,
    repair_counter,
    debug.resolve_common_static_string(remote_synchronizer_idx) as remote_synchronizer_idx,
    reassignment_counter
  from par_active_contracts;

create or replace view debug.par_fresh_submitted_transaction as
  select
    debug.resolve_common_static_string(physical_synchronizer_idx) as physical_synchronizer_idx,
    root_hash_hex,
    debug.canton_timestamp(request_id) as request_id,
    debug.canton_timestamp(max_sequencing_time) as max_sequencing_time
  from par_fresh_submitted_transaction;

create or replace view debug.par_fresh_submitted_transaction_pruning as
  select
    debug.resolve_common_static_string(physical_synchronizer_idx) as physical_synchronizer_idx,
    phase,
    debug.canton_timestamp(ts) as ts,
    debug.canton_timestamp(succeeded) as succeeded
  from par_fresh_submitted_transaction_pruning;

create or replace view debug.med_response_aggregations as
  select
    debug.canton_timestamp(request_id) as request_id,
    mediator_confirmation_request,
    debug.canton_timestamp(finalization_time) as finalization_time,
    verdict,
    request_trace_context
  from med_response_aggregations;

create or replace view debug.common_sequenced_events as
  select
    debug.resolve_common_static_string(physical_synchronizer_idx) as physical_synchronizer_idx,
    sequenced_event,
    type,
    debug.canton_timestamp(ts) as ts,
    sequencer_counter,
    trace_context,
    ignore
  from common_sequenced_events;

create or replace view debug.par_synchronizer_connection_configs as
  select
    synchronizer_alias,
    physical_synchronizer_id,
    empty_if_null_physical_synchronizer_id,
    config,
    status,
    synchronizer_predecessor
  from par_synchronizer_connection_configs;

create or replace view debug.par_registered_synchronizers as
  select
    synchronizer_alias,
    physical_synchronizer_id
  from par_registered_synchronizers;

create or replace view debug.par_reassignments as
  select
    debug.resolve_common_static_string(target_synchronizer_idx) as target_synchronizer_idx,
    debug.resolve_common_static_string(source_synchronizer_idx) as source_synchronizer_idx,
    reassignment_id,
    unassignment_global_offset,
    assignment_global_offset,
    debug.canton_timestamp(unassignment_timestamp) as unassignment_timestamp,
    unassignment_data,
    contracts,
    debug.canton_timestamp(assignment_timestamp) as assignment_timestamp
  from par_reassignments;

create or replace view debug.par_journal_requests as
  select
    debug.resolve_common_static_string(physical_synchronizer_idx) as physical_synchronizer_idx,
    request_counter,
    request_state_index,
    debug.canton_timestamp(request_timestamp) as request_timestamp,
    debug.canton_timestamp(commit_time) as commit_time
  from par_journal_requests;

create or replace view debug.par_computed_acs_commitments as
  select
    debug.resolve_common_static_string(synchronizer_idx) as synchronizer_idx,
    counter_participant,
    debug.canton_timestamp(from_exclusive) as from_exclusive,
    debug.canton_timestamp(to_inclusive) as to_inclusive,
    commitment
  from par_computed_acs_commitments;


create or replace view debug.par_received_acs_commitments as
  select
    debug.resolve_common_static_string(synchronizer_idx) as synchronizer_idx,
    sender,
    debug.canton_timestamp(from_exclusive) as from_exclusive,
    debug.canton_timestamp(to_inclusive) as to_inclusive,
    signed_commitment
  from par_received_acs_commitments;

create or replace view debug.par_outstanding_acs_commitments as
  select
    debug.resolve_common_static_string(synchronizer_idx) as synchronizer_idx,
    counter_participant,
    debug.canton_timestamp(from_exclusive) as from_exclusive,
    debug.canton_timestamp(to_inclusive) as to_inclusive,
    matching_state,
    multi_hosted_cleared
  from par_outstanding_acs_commitments;

create or replace view debug.par_last_computed_acs_commitments as
  select
    debug.resolve_common_static_string(synchronizer_idx) as synchronizer_idx,
    debug.canton_timestamp(ts) as ts
  from par_last_computed_acs_commitments;

create or replace view debug.par_commitment_snapshot as
  select
    debug.resolve_common_static_string(synchronizer_idx) as synchronizer_idx,
    stakeholders_hash,
    stakeholders,
    commitment
  from par_commitment_snapshot;

create or replace view debug.par_commitment_snapshot_time as
  select
    debug.resolve_common_static_string(synchronizer_idx) as synchronizer_idx,
    debug.canton_timestamp(ts) as ts,
    tie_breaker
  from par_commitment_snapshot_time;

create or replace view debug.par_commitment_queue as
  select
    debug.resolve_common_static_string(synchronizer_idx) as synchronizer_idx,
    sender,
    counter_participant,
    debug.canton_timestamp(from_exclusive) as from_exclusive,
    debug.canton_timestamp(to_inclusive) as to_inclusive,
    commitment
  from par_commitment_queue;

create or replace view debug.par_static_synchronizer_parameters as
  select
    physical_synchronizer_id,
    params
  from par_static_synchronizer_parameters;

create or replace view debug.par_pruning_operation as
  select
    name,
    debug.canton_timestamp(started_up_to_inclusive) as started_up_to_inclusive,
    debug.canton_timestamp(completed_up_to_inclusive) as completed_up_to_inclusive
  from par_pruning_operation;

create or replace view debug.seq_block_height as
  select
    height,
    debug.canton_timestamp(latest_event_ts) as latest_event_ts,
    debug.canton_timestamp(latest_sequencer_event_ts) as latest_sequencer_event_ts
  from seq_block_height;

create or replace view debug.par_active_contract_pruning as
  select
    debug.resolve_common_static_string(synchronizer_idx) as synchronizer_idx,
    phase,
    debug.canton_timestamp(ts) as ts,
    debug.canton_timestamp(succeeded) as succeeded
  from par_active_contract_pruning;

create or replace view debug.par_commitment_pruning as
  select
    debug.resolve_common_static_string(synchronizer_idx) as synchronizer_idx,
    phase,
    debug.canton_timestamp(ts) as ts,
    debug.canton_timestamp(succeeded) as succeeded
  from par_commitment_pruning;

create or replace view debug.common_sequenced_event_store_pruning as
  select
    debug.resolve_common_static_string(physical_synchronizer_idx) as physical_synchronizer_idx,
    phase,
    debug.canton_timestamp(ts) as ts,
    debug.canton_timestamp(succeeded) as succeeded
  from common_sequenced_event_store_pruning;

create or replace view debug.mediator_synchronizer_configuration as
  select
    lock,
    physical_synchronizer_id,
    static_synchronizer_parameters,
    sequencer_connection,
    is_topology_initialized
  from mediator_synchronizer_configuration;

create or replace view debug.common_head_sequencer_counters as
  select
    debug.resolve_common_static_string(physical_synchronizer_idx) as physical_synchronizer_idx,
    prehead_counter,
    debug.canton_timestamp(ts) as ts
  from common_head_sequencer_counters;

create or replace view debug.sequencer_members as
  select
    member,
    id,
    debug.canton_timestamp(registered_ts) as registered_ts,
    debug.canton_timestamp(pruned_previous_event_timestamp) as pruned_previous_event_timestamp,
    enabled
  from sequencer_members;

create or replace view debug.sequencer_payloads as
  select
    id,
    instance_discriminator,
    content
  from sequencer_payloads;

create or replace view debug.sequencer_watermarks as
  select
    node_index,
    debug.canton_timestamp(watermark_ts) as watermark_ts,
    sequencer_online
  from sequencer_watermarks;

create or replace view debug.sequencer_acknowledgements as
  select
    debug.resolve_sequencer_member(member) as member,
    debug.canton_timestamp(ts) as ts
  from sequencer_acknowledgements;

create or replace view debug.sequencer_lower_bound as
  select
    single_row_lock,
    debug.canton_timestamp(ts) as ts,
    debug.canton_timestamp(latest_topology_client_timestamp) as latest_topology_client_timestamp
  from sequencer_lower_bound;

create or replace view debug.sequencer_events as
  select
    debug.canton_timestamp(ts) as ts,
    node_index,
    event_type,
    message_id,
    debug.resolve_sequencer_member(sender) as sender,
    debug.resolve_sequencer_members(recipients) as recipients,
    payload_id,
    debug.canton_timestamp(topology_timestamp) as topology_timestamp,
    trace_context,
    error,
    consumed_cost,
    extra_traffic_consumed,
    base_traffic_remainder
  from sequencer_events;

create or replace view debug.sequencer_event_recipients as
select
    debug.canton_timestamp(ts) as ts,
    debug.resolve_sequencer_member(recipient_id) as recipient_id,
    node_index,
    is_topology_event
from sequencer_event_recipients;

create or replace view debug.par_pruning_schedules as
  select
    lock,
    cron,
    max_duration,
    retention,
    prune_internally_only
  from par_pruning_schedules;

create or replace view debug.par_in_flight_submission as
  select
    change_id_hash,
    submission_id,
    submission_synchronizer_id,
    message_id,
    debug.canton_timestamp(sequencing_timeout) as sequencing_timeout,
    debug.canton_timestamp(sequencing_time) as sequencing_time,
    tracking_data,
    root_hash_hex,
    trace_context
from par_in_flight_submission;

create or replace view debug.par_settings as
  select
    client,
    max_infight_validation_requests,
    max_submission_rate,
    max_deduplication_duration,
    max_submission_burst_factor
  from par_settings;

create or replace view debug.par_command_deduplication as
  select
    change_id_hash,
    user_id,
    command_id,
    act_as,
    offset_definite_answer,
    debug.canton_timestamp(publication_time_definite_answer) as publication_time_definite_answer,
    submission_id_definite_answer,
    trace_context_definite_answer,
    offset_acceptance,
    debug.canton_timestamp(publication_time_acceptance) as publication_time_acceptance,
    submission_id_acceptance,
    trace_context_acceptance
  from par_command_deduplication;

create or replace view debug.par_command_deduplication_pruning as
  select
    client,
    pruning_offset,
    debug.canton_timestamp(publication_time) as publication_time
  from par_command_deduplication_pruning;

create or replace view debug.sequencer_synchronizer_configuration as
  select
    lock,
    physical_synchronizer_id,
    static_synchronizer_parameters
  from sequencer_synchronizer_configuration;

create or replace view debug.mediator_deduplication_store as
  select
    mediator_id,
    uuid,
    debug.canton_timestamp(request_time) as request_time,
    debug.canton_timestamp(expire_after) as expire_after
  from mediator_deduplication_store;

create or replace view debug.common_pruning_schedules as
  select
    node_type,
    cron,
    max_duration,
    retention
  from common_pruning_schedules;

create or replace view debug.seq_in_flight_aggregation as
  select
    aggregation_id,
    debug.canton_timestamp(max_sequencing_time) as max_sequencing_time,
    aggregation_rule
  from seq_in_flight_aggregation;

create or replace view debug.seq_in_flight_aggregated_sender as
  select
    aggregation_id,
    sender,
    debug.canton_timestamp(sequencing_timestamp) as sequencing_timestamp,
    signatures
  from seq_in_flight_aggregated_sender;

create or replace view debug.common_topology_transactions as
  select
    id,
    store_id,
    debug.canton_timestamp(sequenced) as sequenced,
    debug.topology_mapping(transaction_type) as transaction_type,
    namespace,
    identifier,
    mapping_key_hash,
    serial_counter,
    debug.canton_timestamp(valid_from) as valid_from,
    debug.canton_timestamp(valid_until) as valid_until,
    debug.topology_change_op(operation) as operation,
    instance,
    tx_hash,
    rejection_reason,
    is_proposal,
    representative_protocol_version,
    hash_of_signatures
  from common_topology_transactions;

create or replace view debug.seq_traffic_control_balance_updates as
  select
    member,
    debug.canton_timestamp(sequencing_timestamp) as sequencing_timestamp,
    balance,
    serial
  from seq_traffic_control_balance_updates;

create or replace view debug.seq_traffic_control_consumed_journal as
  select
    member,
    debug.canton_timestamp(sequencing_timestamp) as sequencing_timestamp,
    extra_traffic_consumed,
    base_traffic_remainder,
    last_consumed_cost
  from seq_traffic_control_consumed_journal;

create or replace view debug.seq_traffic_control_initial_timestamp as
  select
    debug.canton_timestamp(initial_timestamp) as initial_timestamp
  from seq_traffic_control_initial_timestamp;

create or replace view debug.ord_epochs as
  select
    epoch_number,
    start_block_number,
    epoch_length,
    debug.canton_timestamp(topology_ts) as topology_ts,
    in_progress
  from ord_epochs;

create or replace view debug.ord_availability_batch as
  select
    id,
    batch,
    epoch_number
  from ord_availability_batch;

create or replace view debug.ord_pbft_messages_in_progress as
select
    block_number,
    epoch_number,
    view_number,
    message,
    discriminator,
    from_sequencer_id
from ord_pbft_messages_in_progress;

create or replace view debug.ord_pbft_messages_completed as
  select
    block_number,
    epoch_number,
    message,
    discriminator,
    from_sequencer_id
  from ord_pbft_messages_completed;

create or replace view debug.ord_metadata_output_blocks as
  select
    epoch_number,
    block_number,
    debug.canton_timestamp(bft_ts) as bft_ts
  from ord_metadata_output_blocks;

create or replace view debug.ord_metadata_output_epochs as
  select
    epoch_number,
    could_alter_ordering_topology
  from ord_metadata_output_epochs;

create or replace view debug.ord_output_lower_bound as
  select
    single_row_lock,
    epoch_number,
    block_number
  from ord_output_lower_bound;

create or replace view debug.ord_leader_selection_state as
  select
    epoch_number,
    state
  from ord_leader_selection_state;

create or replace view debug.common_static_strings as
  select
    id,
    string,
    source
  from common_static_strings;

create or replace view debug.ord_p2p_endpoints as
  select
    address,
    port,
    transport_security,
    custom_server_trust_certificates,
    client_certificate_chain,
    client_private_key_file
  from ord_p2p_endpoints;

create or replace VIEW debug.acs_no_wait_counter_participants as
    select
        synchronizer_id,
        participant_id
        from acs_no_wait_counter_participants;

create or replace VIEW debug.acs_slow_participant_config as
    select
        synchronizer_id,
        threshold_distinguished,
        threshold_default
        from acs_slow_participant_config;

create or replace VIEW debug.acs_slow_counter_participants as
    select
        synchronizer_id,
        participant_id,
        is_distinguished,
        is_added_to_metrics
        from acs_slow_counter_participants;
