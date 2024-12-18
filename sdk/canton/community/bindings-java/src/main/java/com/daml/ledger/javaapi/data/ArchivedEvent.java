// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates.
// Proprietary code. All rights reserved.

package com.daml.ledger.javaapi.data;

import com.daml.ledger.api.v2.EventOuterClass;
import java.util.List;
import java.util.Objects;
import org.checkerframework.checker.nullness.qual.NonNull;

public final class ArchivedEvent implements Event {

  private final List<String> witnessParties;

  private final String eventId;

  private final Long offset;

  private final Integer nodeId;

  private final Identifier templateId;

  private final String packageName;

  private final String contractId;

  public ArchivedEvent(
      @NonNull List<@NonNull String> witnessParties,
      @NonNull String eventId,
      @NonNull Long offset,
      @NonNull Integer nodeId,
      @NonNull Identifier templateId,
      @NonNull String packageName,
      @NonNull String contractId) {
    this.witnessParties = witnessParties;
    this.eventId = eventId;
    this.offset = offset;
    this.nodeId = nodeId;
    this.templateId = templateId;
    this.packageName = packageName;
    this.contractId = contractId;
  }

  @NonNull
  @Override
  public List<@NonNull String> getWitnessParties() {
    return witnessParties;
  }

  @NonNull
  @Override
  public String getEventId() {
    return eventId;
  }

  @NonNull
  @Override
  public Long getOffset() {
    return offset;
  }

  @NonNull
  @Override
  public Integer getNodeId() {
    return nodeId;
  }

  @NonNull
  @Override
  public Identifier getTemplateId() {
    return templateId;
  }

  @NonNull
  @Override
  public String getPackageName() {
    return packageName;
  }

  @NonNull
  @Override
  public String getContractId() {
    return contractId;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    ArchivedEvent that = (ArchivedEvent) o;
    return Objects.equals(witnessParties, that.witnessParties)
        && Objects.equals(eventId, that.eventId)
        && Objects.equals(offset, that.offset)
        && Objects.equals(nodeId, that.nodeId)
        && Objects.equals(templateId, that.templateId)
        && Objects.equals(packageName, that.packageName)
        && Objects.equals(contractId, that.contractId);
  }

  @Override
  public int hashCode() {
    return Objects.hash(
        witnessParties, eventId, offset, nodeId, templateId, packageName, contractId);
  }

  @Override
  public String toString() {
    return "ArchivedEvent{"
        + "witnessParties="
        + witnessParties
        + ", eventId='"
        + eventId
        + '\''
        + ", offset="
        + offset
        + ", nodeId="
        + nodeId
        + ", packageName="
        + packageName
        + ", templateId="
        + templateId
        + ", contractId='"
        + contractId
        + '\''
        + '}';
  }

  public EventOuterClass.ArchivedEvent toProto() {
    return EventOuterClass.ArchivedEvent.newBuilder()
        .setContractId(getContractId())
        .setEventId(getEventId())
        .setOffset(getOffset())
        .setNodeId(getNodeId())
        .setTemplateId(getTemplateId().toProto())
        .setPackageName(getPackageName())
        .addAllWitnessParties(getWitnessParties())
        .build();
  }

  public static ArchivedEvent fromProto(EventOuterClass.ArchivedEvent archivedEvent) {
    return new ArchivedEvent(
        archivedEvent.getWitnessPartiesList(),
        archivedEvent.getEventId(),
        archivedEvent.getOffset(),
        archivedEvent.getNodeId(),
        Identifier.fromProto(archivedEvent.getTemplateId()),
        archivedEvent.getPackageName(),
        archivedEvent.getContractId());
  }
}
