// Copyright (c) 2025 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.daml.lf
package script

import com.digitalasset.daml.lf.data.{ImmArray, Numeric, Ref}
import com.digitalasset.daml.lf.language.Ast.{FailureCategory, PackageMetadata}
import com.digitalasset.daml.lf.script.api.{v1 => proto}
import com.digitalasset.daml.lf.speedy.{SError, SValue, TraceLog, Warning, WarningLog}
import com.digitalasset.daml.lf.transaction.{
  GlobalKey,
  GlobalKeyWithMaintainers,
  IncompleteTransaction,
  Node,
  NodeId,
}
import com.digitalasset.daml.lf.ledger._
import com.digitalasset.daml.lf.value.{Value => V}

import scala.jdk.CollectionConverters._

@SuppressWarnings(Array("org.wartremover.warts.NonUnitStatements"))
final class Conversions(
    homePackageId: Ref.PackageId,
    ledger: IdeLedger,
    incomplete: Option[IncompleteTransaction],
    traceLog: TraceLog,
    warningLog: WarningLog,
    commitLocation: Option[Ref.Location],
    stackTrace: ImmArray[Ref.Location],
    devMode: Boolean,
) {

  private val empty: proto.Empty = proto.Empty.newBuilder.build

  private val packageIdSelf: proto.PackageIdentifier =
    proto.PackageIdentifier.newBuilder.setSelf(empty).build

  // The ledger data will not contain information from the partial transaction at this point.
  // We need the mapping for converting error message so we manually add it here.
  private val ptxCoidToNodeId = incomplete
    .map(_.transaction.nodes)
    .getOrElse(Map.empty)
    .collect { case (nodeId, node: Node.Create) =>
      node.coid -> ledger.ptxEventId(nodeId)
    }

  private val coidToEventId = ledger.ledgerData.coidToNodeId ++ ptxCoidToNodeId

  private val nodes =
    ledger.ledgerData.nodeInfos.map(Function.tupled(convertNode))

  private val steps = ledger.scriptSteps.map { case (idx, step) =>
    convertScriptStep(idx.toInt, step)
  }

  def convertScriptResult(svalue: SValue): proto.ScriptResult = {
    val builder = proto.ScriptResult.newBuilder
      .addAllNodes(nodes.asJava)
      .addAllScriptSteps(steps.asJava)
      .setReturnValue(convertSValue(svalue))
      .setFinalTime(ledger.currentTime.micros)
      .addAllActiveContracts(
        ledger.ledgerData.activeContracts.view
          .map[String](coid => coidToEventId(coid).toLedgerString)
          .asJava
      )
    traceLog.iterator.foreach { entry =>
      builder.addTraceLog(convertSTraceMessage(entry))
    }
    warningLog.iterator.foreach { entry =>
      builder.addWarnings(convertSWarningMessage(entry))
    }
    builder.build
  }

  def convertScriptError(err: Error): proto.ScriptError = {
    val builder = proto.ScriptError.newBuilder
      .addAllNodes(nodes.asJava)
      .addAllScriptSteps(steps.asJava)
      .setLedgerTime(ledger.currentTime.micros)
      .addAllActiveContracts(
        ledger.ledgerData.activeContracts.view
          .map[String](coid => coidToEventId(coid).toLedgerString)
          .asJava
      )

    traceLog.iterator.foreach { entry =>
      builder.addTraceLog(convertSTraceMessage(entry))
    }

    def setCrash(reason: String) = builder.setCrash(reason)

    commitLocation.foreach { loc =>
      builder.setCommitLoc(convertLocation(loc))
    }

    builder.addAllStackTrace(stackTrace.map(convertLocation).toSeq.asJava)

    incomplete.foreach(ptx =>
      builder.setPartialTransaction(
        convertPartialTransaction(ptx)
      )
    )

    err match {
      case Error.Internal(reason) => setCrash(reason)

      case Error.RunnerException(serror) =>
        serror match {
          case SError.SErrorCrash(_, reason) => setCrash(reason)

          case SError.SErrorDamlException(interpretationError) =>
            import interpretation.Error._
            interpretationError match {
              case UnhandledException(_, value) =>
                builder.setUnhandledException(convertValue(value))
              case UserError(msg) =>
                builder.setUserError(msg)
              case ContractNotFound(cid) =>
                // NOTE https://github.com/digital-asset/daml/issues/9974
                // We crash here because:
                //  1. You cannot construct a cid yourself in scripts
                //  2. Contract id fetch failures because a contract was
                //     archived or what not are turned into more specific
                //     errors so we never produce ContractNotFound
                builder.setCrash(s"contract ${cid.coid} not found")
              case UnresolvedPackageName(packageName) =>
                builder.setUnresolvedPackageName(
                  proto.ScriptError.UnresolvedPackageName.newBuilder
                    .setPackageName(packageName)
                )
              case TemplatePreconditionViolated(tid, optLoc, arg) =>
                val uepvBuilder = proto.ScriptError.TemplatePreconditionViolated.newBuilder
                optLoc.map(convertLocation).foreach(uepvBuilder.setLocation)
                builder.setTemplatePrecondViolated(
                  uepvBuilder
                    .setTemplateId(convertIdentifier(tid))
                    .setArg(convertValue(arg))
                    .build
                )
              case ContractNotActive(coid, tid, consumedBy) =>
                builder.setUpdateLocalContractNotActive(
                  proto.ScriptError.ContractNotActive.newBuilder
                    .setContractRef(mkContractRef(coid, tid))
                    .setConsumedBy(proto.NodeId.newBuilder.setId(consumedBy.toString).build)
                    .build
                )
              case DisclosedContractKeyHashingError(contractId, globalKey, hash) =>
                builder.setDisclosedContractKeyHashingError(
                  proto.ScriptError.DisclosedContractKeyHashingError.newBuilder
                    .setContractRef(mkContractRef(contractId, globalKey.templateId))
                    .setKey(convertValue(globalKey.key))
                    .setComputedHash(globalKey.hash.toHexString)
                    .setDeclaredHash(hash.toHexString)
                    .build
                )
              case ContractKeyNotFound(gk) =>
                builder.setScriptContractKeyNotFound(
                  proto.ScriptError.ContractKeyNotFound.newBuilder
                    .setKey(convertGlobalKey(gk))
                    .build
                )
              case DuplicateContractKey(key) =>
                builder.setScriptCommitError(
                  proto.CommitError.newBuilder
                    .setUniqueContractKeyViolation(convertGlobalKey(key))
                    .build
                )
              case InconsistentContractKey(key) =>
                builder.setScriptCommitError(
                  proto.CommitError.newBuilder
                    .setInconsistentContractKey(convertGlobalKey(key))
                    .build
                )
              case CreateEmptyContractKeyMaintainers(tid, arg, key) =>
                builder.setCreateEmptyContractKeyMaintainers(
                  proto.ScriptError.CreateEmptyContractKeyMaintainers.newBuilder
                    .setArg(convertValue(arg))
                    .setTemplateId(convertIdentifier(tid))
                    .setKey(convertValue(key))
                )
              case FetchEmptyContractKeyMaintainers(tid, key, _) =>
                builder.setFetchEmptyContractKeyMaintainers(
                  proto.ScriptError.FetchEmptyContractKeyMaintainers.newBuilder
                    .setTemplateId(convertIdentifier(tid))
                    .setKey(convertValue(key))
                )
              case WronglyTypedContract(coid, expected, actual) =>
                builder.setWronglyTypedContract(
                  proto.ScriptError.WronglyTypedContract.newBuilder
                    .setContractRef(mkContractRef(coid, actual))
                    .setExpected(convertIdentifier(expected))
                )
              case ContractDoesNotImplementInterface(interfaceId, coid, templateId) =>
                builder.setContractDoesNotImplementInterface(
                  proto.ScriptError.ContractDoesNotImplementInterface.newBuilder
                    .setContractRef(mkContractRef(coid, templateId))
                    .setInterfaceId(convertIdentifier(interfaceId))
                    .build
                )
              case ContractDoesNotImplementRequiringInterface(
                    requiredIfaceId,
                    requiringIfaceId,
                    coid,
                    templateId,
                  ) =>
                builder.setContractDoesNotImplementRequiringInterface(
                  proto.ScriptError.ContractDoesNotImplementRequiringInterface.newBuilder
                    .setContractRef(mkContractRef(coid, templateId))
                    .setRequiredInterfaceId(convertIdentifier(requiredIfaceId))
                    .setRequiringInterfaceId(convertIdentifier(requiringIfaceId))
                    .build
                )
              case FailedAuthorization(nid, fa) =>
                builder.setScriptCommitError(
                  proto.CommitError.newBuilder
                    .setFailedAuthorizations(convertFailedAuthorization(nid, fa))
                    .build
                )
              case ContractIdInContractKey(key) =>
                builder.setContractIdInContractKey(
                  proto.ScriptError.ContractIdInContractKey.newBuilder.setKey(convertValue(key))
                )
              case ContractIdComparability(_) =>
                // We crash here because you cannot construct a cid yourself in scripts
                builder.setCrash(s"Contract Id comparability Error")
              case NonComparableValues =>
                builder.setComparableValueError(proto.Empty.newBuilder)
              case ValueNesting(_) =>
                builder.setValueExceedsMaxNesting(proto.Empty.newBuilder)
              case FailureStatus(errorId, categoryId, message, metadata) =>
                builder.setCrash(s"Failure status: $errorId")
                builder.setFailureStatusError(
                  proto.ScriptError.FailureStatusError.newBuilder
                    .setErrorId(errorId)
                    .setCategoryName(
                      FailureCategory.all
                        .find(cat => cat.cantonCategoryId == categoryId)
                        .fold(s"UnknownCategoryID $categoryId")(_.toString)
                    )
                    .setMessage(message)
                    .addAllMetadata(
                      metadata.toList.map { case (k, v) =>
                        proto.ScriptError.FailureStatusError.MetadataEntry.newBuilder
                          .setKey(k)
                          .setValue(v)
                          .build
                      }.asJava
                    )
                    .build
                )
              case Dev(_, devError) if devMode =>
                devError match {
                  case Dev.Conformance(_, _, _) =>
                    builder.setCrash("conformance fails")
                  case Dev.Limit(limitError) =>
                    limitError match {
                      // TODO https://github.com/digital-asset/daml/issues/11691
                      //   Handle the other cases properly.
                      case _ =>
                        builder.setCrash(s"A limit was overpassed when building the transaction")
                    }
                  case Dev.ChoiceGuardFailed(coid, templateId, choiceName, byInterface) =>
                    val cgfBuilder =
                      proto.ScriptError.ChoiceGuardFailed.newBuilder
                        .setContractRef(mkContractRef(coid, templateId))
                        .setChoiceId(choiceName)
                    byInterface.foreach(ifaceId =>
                      cgfBuilder.setByInterface(convertIdentifier(ifaceId))
                    )
                    builder.setChoiceGuardFailed(cgfBuilder.build)
                  case Dev.WronglyTypedContractSoft(coid, expected, accepted, actual) =>
                    builder.setWronglyTypedContractSoft(
                      proto.ScriptError.WronglyTypedContractSoft.newBuilder
                        .setContractRef(mkContractRef(coid, actual))
                        .setExpected(convertIdentifier(expected))
                        .addAllAccepted(accepted.map(convertIdentifier(_)).asJava)
                    )
                }
              case _: Upgrade =>
                builder.setUpgradeError(
                  proto.ScriptError.UpgradeError.newBuilder.setMessage(
                    speedy.Pretty.prettyDamlException(interpretationError).render(80)
                  )
                )
              case _: Crypto =>
                builder.setCryptoError(
                  proto.ScriptError.CryptoError.newBuilder.setMessage(
                    speedy.Pretty.prettyDamlException(interpretationError).render(80)
                  )
                )
              case err @ Dev(_, _) =>
                builder.setCrash(s"Unexpected Dev error: " + err.toString)
            }
        }

      case Error.ContractNotEffective(coid, tid, effectiveAt) =>
        builder.setScriptContractNotEffective(
          proto.ScriptError.ContractNotEffective.newBuilder
            .setEffectiveAt(effectiveAt.micros)
            .setContractRef(mkContractRef(coid, tid))
            .build
        )

      case Error.ContractNotActive(coid, tid, optConsumedBy) =>
        val errorBuilder = proto.ScriptError.ContractNotActive.newBuilder
          .setContractRef(mkContractRef(coid, tid))
        optConsumedBy.foreach(consumedBy => errorBuilder.setConsumedBy(convertEventId(consumedBy)))
        builder.setScriptContractNotActive(
          errorBuilder.build
        )

      case Error.ContractNotVisible(coid, tid, actAs, readAs, observers) =>
        builder.setScriptContractNotVisible(
          proto.ScriptError.ContractNotVisible.newBuilder
            .setContractRef(mkContractRef(coid, tid))
            .addAllActAs(actAs.map(convertParty(_)).asJava)
            .addAllReadAs(readAs.map(convertParty(_)).asJava)
            .addAllObservers(observers.map(convertParty).asJava)
            .build
        )

      case Error.ContractKeyNotVisible(coid, gk, actAs, readAs, stakeholders) =>
        builder.setScriptContractKeyNotVisible(
          proto.ScriptError.ContractKeyNotVisible.newBuilder
            .setContractRef(mkContractRef(coid, gk.templateId))
            .setKey(convertValue(gk.key))
            .addAllActAs(actAs.map(convertParty(_)).asJava)
            .addAllReadAs(readAs.map(convertParty(_)).asJava)
            .addAllStakeholders(stakeholders.map(convertParty).asJava)
            .build
        )

      case Error.CommitError(commitError) =>
        builder.setScriptCommitError(
          convertCommitError(commitError)
        )
      case Error.MustFailSucceeded(tx @ _) =>
        builder.setScriptMustfailSucceeded(empty)

      case Error.InvalidPartyName(party, _) =>
        builder.setScriptInvalidPartyName(party)

      case Error.PartyAlreadyExists(party) =>
        builder.setScriptPartyAlreadyExists(party)
      case Error.PartiesNotAllocated(parties) =>
        builder.setScriptPartiesNotAllocated(
          proto.ScriptError.PartiesNotAllocated.newBuilder
            .addAllParties(parties.map(convertParty).asJava)
            .build
        )
      case Error.Timeout(timeout) =>
        builder.setEvaluationTimeout(timeout.toSeconds)
      case Error.CanceledByRequest() =>
        builder.setCancelledByRequest(empty)
      case Error.LookupError(err, oPackageMeta, packageId) =>
        val nstBuilder =
          proto.ScriptError.LookupError.newBuilder
            .setPackageId(packageId)
        err match {
          case language.LookupError.NotFound(notFound, context) =>
            nstBuilder.setNotFound(
              proto.ScriptError.LookupError.NotFound.newBuilder
                .setNotFound(notFound.pretty)
                .setContext(context.pretty)
            )
        }
        oPackageMeta.foreach(packageMeta =>
          nstBuilder.setPackageMetadata(mkPackageMetadata(packageMeta))
        )
        builder.setLookupError(nstBuilder.build)

      case Error.DisclosureDecoding(message) =>
        builder.setCrash("disclosure decode failed: " + message)
    }
    builder.build
  }

  def convertCommitError(commitError: IdeLedger.CommitError): proto.CommitError = {
    val builder = proto.CommitError.newBuilder
    commitError match {
      case IdeLedger.CommitError.UniqueKeyViolation(gk) =>
        builder.setUniqueContractKeyViolation(convertGlobalKey(gk.gk))
    }
    builder.build
  }

  def convertGlobalKey(globalKey: GlobalKey): proto.GlobalKey = {
    val builder = proto.GlobalKey.newBuilder
    builder
      .setPackage(convertPackageId(globalKey.templateId.packageId))
      .setName(globalKey.qualifiedName.toString)
      .setKey(convertValue(globalKey.key))
      .build
  }

  def convertSValue(svalue: SValue): proto.Value = {
    def unserializable(what: String): proto.Value =
      proto.Value.newBuilder.setUnserializable(what).build
    try {
      convertValue(svalue.toUnnormalizedValue)
    } catch {
      case _: SError.SErrorCrash => {
        // We cannot rely on serializability information since we do not have that available in the IDE.
        // We also cannot simply pattern match on SValue since the unserializable values can be nested, e.g.,
        // a function ina record.
        // We could recurse on SValue to produce slightly better error messages if we
        // encounter an unserializable type but that doesn’t seem worth the effort, especially
        // given that the error would still be on speedy expressions.
        unserializable("Unserializable script result")
      }
    }
  }

  def convertSTraceMessage(msgAndLoc: (String, Option[Ref.Location])): proto.TraceMessage = {
    val builder = proto.TraceMessage.newBuilder
    msgAndLoc._2.map(loc => builder.setLocation(convertLocation(loc)))
    builder.setMessage(msgAndLoc._1).build
  }

  private[this] def convertSWarningMessage(warning: Warning): proto.WarningMessage = {
    val builder = proto.WarningMessage.newBuilder
    warning.commitLocation.map(loc => builder.setCommitLocation(convertLocation(loc)))
    builder.setMessage(warning.message).build
  }

  def convertFailedAuthorization(
      nodeId: NodeId,
      fa: FailedAuthorization,
  ): proto.FailedAuthorizations = {
    val builder = proto.FailedAuthorizations.newBuilder
    builder.addFailedAuthorizations {
      val faBuilder = proto.FailedAuthorization.newBuilder
      faBuilder.setNodeId(convertTxNodeId(nodeId))
      fa match {
        case FailedAuthorization.CreateMissingAuthorization(
              templateId,
              optLocation,
              authParties,
              reqParties,
            ) =>
          val cmaBuilder =
            proto.FailedAuthorization.CreateMissingAuthorization.newBuilder
              .setTemplateId(convertIdentifier(templateId))
              .addAllAuthorizingParties(authParties.map(convertParty).asJava)
              .addAllRequiredAuthorizers(reqParties.map(convertParty).asJava)
          optLocation.map(loc => cmaBuilder.setLocation(convertLocation(loc)))
          faBuilder.setCreateMissingAuthorization(cmaBuilder.build)

        case FailedAuthorization.MaintainersNotSubsetOfSignatories(
              templateId,
              optLocation,
              signatories,
              maintainers,
            ) =>
          val maintNotSignBuilder =
            proto.FailedAuthorization.MaintainersNotSubsetOfSignatories.newBuilder
              .setTemplateId(convertIdentifier(templateId))
              .addAllSignatories(signatories.map(convertParty).asJava)
              .addAllMaintainers(maintainers.map(convertParty).asJava)
          optLocation.map(loc => maintNotSignBuilder.setLocation(convertLocation(loc)))
          faBuilder.setMaintainersNotSubsetOfSignatories(maintNotSignBuilder.build)

        case fma: FailedAuthorization.FetchMissingAuthorization =>
          val fmaBuilder =
            proto.FailedAuthorization.FetchMissingAuthorization.newBuilder
              .setTemplateId(convertIdentifier(fma.templateId))
              .addAllAuthorizingParties(fma.authorizingParties.map(convertParty).asJava)
              .addAllStakeholders(fma.stakeholders.map(convertParty).asJava)
          fma.optLocation.map(loc => fmaBuilder.setLocation(convertLocation(loc)))
          faBuilder.setFetchMissingAuthorization(fmaBuilder.build)

        case FailedAuthorization.ExerciseMissingAuthorization(
              templateId,
              choiceId,
              optLocation,
              authParties,
              reqParties,
            ) =>
          val emaBuilder =
            proto.FailedAuthorization.ExerciseMissingAuthorization.newBuilder
              .setTemplateId(convertIdentifier(templateId))
              .setChoiceId(choiceId)
              .addAllAuthorizingParties(authParties.map(convertParty).asJava)
              .addAllRequiredAuthorizers(reqParties.map(convertParty).asJava)
          optLocation.map(loc => emaBuilder.setLocation(convertLocation(loc)))
          faBuilder.setExerciseMissingAuthorization(emaBuilder.build)
        case FailedAuthorization.NoSignatories(templateId, optLocation) =>
          val nsBuilder =
            proto.FailedAuthorization.NoSignatories.newBuilder
              .setTemplateId(convertIdentifier(templateId))
          optLocation.map(loc => nsBuilder.setLocation(convertLocation(loc)))
          faBuilder.setNoSignatories(nsBuilder.build)

        case FailedAuthorization.NoControllers(templateId, choiceId, optLocation) =>
          val ncBuilder =
            proto.FailedAuthorization.NoControllers.newBuilder
              .setTemplateId(convertIdentifier(templateId))
              .setChoiceId(choiceId)
          optLocation.map(loc => ncBuilder.setLocation(convertLocation(loc)))
          faBuilder.setNoControllers(ncBuilder.build)

        case _: FailedAuthorization.NoAuthorizers =>
          sys.error(
            "Unexpected FailedAuthorization.NoAuthorizers: choice authority not supported by scripts."
          )
        case FailedAuthorization.LookupByKeyMissingAuthorization(
              templateId,
              optLocation,
              maintainers,
              authorizers,
            ) =>
          val lbkmaBuilder =
            proto.FailedAuthorization.LookupByKeyMissingAuthorization.newBuilder
              .setTemplateId(convertIdentifier(templateId))
              .addAllMaintainers(maintainers.map(convertParty).asJava)
              .addAllAuthorizingParties(authorizers.map(convertParty).asJava)
          optLocation.foreach(loc => lbkmaBuilder.setLocation(convertLocation(loc)))
          faBuilder.setLookupByKeyMissingAuthorization(lbkmaBuilder)
      }
      faBuilder.build
    }

    builder.build
  }

  def mkContractRef(coid: V.ContractId, templateId: Ref.Identifier): proto.ContractRef =
    proto.ContractRef.newBuilder
      .setContractId(coidToEventId(coid).toLedgerString)
      .setTemplateId(convertIdentifier(templateId))
      .build

  def mkPackageMetadata(packageMetadata: PackageMetadata): proto.PackageMetadata =
    proto.PackageMetadata.newBuilder
      .setPackageName(packageMetadata.name.toString)
      .setPackageVersion(packageMetadata.version.toString)
      .build

  def convertScriptStep(
      stepId: Int,
      step: IdeLedger.ScriptStep,
  ): proto.ScriptStep = {
    val builder = proto.ScriptStep.newBuilder
    builder.setStepId(stepId)
    step match {
      case IdeLedger.Commit(txId, rtx, optLocation) =>
        val commitBuilder = proto.ScriptStep.Commit.newBuilder
        optLocation.map { loc =>
          commitBuilder.setLocation(convertLocation(loc))
        }
        builder.setCommit(
          commitBuilder
            .setTxId(txId.index)
            .setTx(convertTransaction(rtx))
            .build
        )
      case IdeLedger.PassTime(dt) =>
        builder.setPassTime(dt)
      case IdeLedger.AssertMustFail(actAs, readAs, optLocation, time, txId) =>
        val assertBuilder = proto.ScriptStep.AssertMustFail.newBuilder
        optLocation.map { loc =>
          assertBuilder.setLocation(convertLocation(loc))
        }
        builder
          .setAssertMustFail(
            assertBuilder
              .addAllActAs(actAs.map(convertParty(_)).asJava)
              .addAllReadAs(readAs.map(convertParty(_)).asJava)
              .setTime(time.micros)
              .setTxId(txId.index)
              .build
          )
      case IdeLedger.SubmissionFailed(actAs, readAs, optLocation, time, txId) =>
        val submissionFailedBuilder = proto.ScriptStep.SubmissionFailed.newBuilder
        optLocation.map { loc =>
          submissionFailedBuilder.setLocation(convertLocation(loc))
        }
        builder
          .setSubmissionFailed(
            submissionFailedBuilder
              .addAllActAs(actAs.map(convertParty(_)).asJava)
              .addAllReadAs(readAs.map(convertParty(_)).asJava)
              .setTime(time.micros)
              .setTxId(txId.index)
              .build
          )
    }
    builder.build
  }

  def convertTransaction(
      rtx: IdeLedger.RichTransaction
  ): proto.Transaction = {
    proto.Transaction.newBuilder
      .addAllActAs(rtx.actAs.map(convertParty(_)).asJava)
      .addAllReadAs(rtx.readAs.map(convertParty(_)).asJava)
      .setEffectiveAt(rtx.effectiveAt.micros)
      .addAllRoots(rtx.transaction.roots.map(convertNodeId(rtx.transactionOffset, _)).toSeq.asJava)
      .addAllNodes(rtx.transaction.nodes.keys.map(convertNodeId(rtx.transactionOffset, _)).asJava)
      .setFailedAuthorizations(
        proto.FailedAuthorizations.newBuilder.build
      )
      .build
  }

  def convertPartialTransaction(incomplete: IncompleteTransaction): proto.PartialTransaction = {
    val tx = incomplete.transaction

    val builder = proto.PartialTransaction.newBuilder
      .addAllNodes(tx.nodes.map(convertIncompleteTransactionNode(incomplete.locationInfo)).asJava)
      .addAllRoots(tx.roots.toList.map(convertTxNodeId).asJava)

    builder.build
  }

  def convertEventId(nodeId: EventId): proto.NodeId =
    proto.NodeId.newBuilder.setId(nodeId.toLedgerString).build

  def convertNodeId(txOffset: Long, nodeId: NodeId): proto.NodeId =
    proto.NodeId.newBuilder.setId(EventId(txOffset, nodeId).toLedgerString).build

  def convertTxNodeId(nodeId: NodeId): proto.NodeId =
    proto.NodeId.newBuilder.setId(nodeId.index.toString).build

  def convertNode(eventId: EventId, nodeInfo: IdeLedger.LedgerNodeInfo): proto.Node = {
    val builder = proto.Node.newBuilder
    builder
      .setNodeId(convertEventId(eventId))
      .setEffectiveAt(nodeInfo.effectiveAt.micros)
      .addAllReferencedBy(nodeInfo.referencedBy.map(convertEventId).asJava)
      .addAllDisclosures(nodeInfo.disclosures.toList.map {
        case (party, IdeLedger.Disclosure(txId, explicit)) =>
          proto.Disclosure.newBuilder
            .setParty(convertParty(party))
            .setSinceTxId(txId.index)
            .setExplicit(explicit)
            .build
      }.asJava)

    nodeInfo.consumedBy
      .map(eventId => builder.setConsumedBy(convertEventId(eventId)))

    nodeInfo.node match {
      case rollback: Node.Rollback =>
        val rollbackBuilder = proto.Node.Rollback.newBuilder
          .addAllChildren(
            rollback.children.map(convertNodeId(eventId.transactionOffset, _)).toSeq.asJava
          )
        builder.setRollback(rollbackBuilder.build)
      case create: Node.Create =>
        val createBuilder =
          proto.Node.Create.newBuilder
            .setContractId(coidToEventId(create.coid).toLedgerString)
            .setThinContractInstance(
              proto.ThinContractInstance.newBuilder
                .setTemplateId(convertIdentifier(create.templateId))
                .setValue(convertValue(create.arg))
                .build
            )
            .addAllSignatories(create.signatories.map(convertParty).asJava)
            .addAllStakeholders(create.stakeholders.map(convertParty).asJava)

        nodeInfo.optLocation.map(loc => builder.setLocation(convertLocation(loc)))
        builder.setCreate(createBuilder.build)
      case fetch: Node.Fetch =>
        val fetchBuilder =
          proto.Node.Fetch.newBuilder
            .setContractId(coidToEventId(fetch.coid).toLedgerString)
            .setTemplateId(convertIdentifier(fetch.templateId))
            .addAllActingParties(fetch.actingParties.map(convertParty).asJava)
        if (fetch.byKey) {
          fetch.keyOpt.foreach { key =>
            fetchBuilder.setFetchByKey(convertKeyWithMaintainers(key))
          }
        }
        builder.setFetch(fetchBuilder.build)
      case ex: Node.Exercise =>
        nodeInfo.optLocation.map(loc => builder.setLocation(convertLocation(loc)))
        val exerciseBuilder =
          proto.Node.Exercise.newBuilder
            .setTargetContractId(coidToEventId(ex.targetCoid).toLedgerString)
            .setTemplateId(convertIdentifier(ex.templateId))
            .setChoiceId(ex.choiceId)
            .setConsuming(ex.consuming)
            .addAllActingParties(ex.actingParties.map(convertParty).asJava)
            .setChosenValue(convertValue(ex.chosenValue))
            .addAllSignatories(ex.signatories.map(convertParty).asJava)
            .addAllStakeholders(ex.stakeholders.map(convertParty).asJava)
            .addAllChildren(
              ex.children
                .map(convertNodeId(eventId.transactionOffset, _))
                .toSeq
                .asJava
            )
        ex.exerciseResult.foreach { result =>
          exerciseBuilder.setExerciseResult(convertValue(result))
        }
        if (ex.byKey) {
          ex.keyOpt.foreach { key =>
            exerciseBuilder.setExerciseByKey(convertKeyWithMaintainers(key))
          }
        }
        builder.setExercise(exerciseBuilder.build)

      case lbk: Node.LookupByKey =>
        nodeInfo.optLocation.foreach(loc => builder.setLocation(convertLocation(loc)))
        val lbkBuilder = proto.Node.LookupByKey.newBuilder
          .setTemplateId(convertIdentifier(lbk.templateId))
          .setKeyWithMaintainers(convertKeyWithMaintainers(lbk.key))
        lbk.result.foreach(cid => lbkBuilder.setContractId(coidToEventId(cid).toLedgerString))
        builder.setLookupByKey(lbkBuilder)

    }
    builder.build
  }

  def convertKeyWithMaintainers(
      key: GlobalKeyWithMaintainers
  ): proto.KeyWithMaintainers = {
    proto.KeyWithMaintainers
      .newBuilder()
      .setKey(convertValue(key.value))
      .addAllMaintainers(key.maintainers.map(convertParty).asJava)
      .build()
  }

  def convertIncompleteTransactionNode(
      locationInfo: Map[NodeId, Ref.Location]
  )(nodeWithId: (NodeId, Node)): proto.Node = {
    val (nodeId, node) = nodeWithId
    val optLocation = locationInfo.get(nodeId)
    val builder = proto.Node.newBuilder
    builder
      .setNodeId(proto.NodeId.newBuilder.setId(nodeId.index.toString).build)
    // FIXME(JM): consumedBy, parent, ...
    node match {
      case rollback: Node.Rollback =>
        val rollbackBuilder =
          proto.Node.Rollback.newBuilder
            .addAllChildren(
              rollback.children
                .map(nid => proto.NodeId.newBuilder.setId(nid.index.toString).build)
                .toSeq
                .asJava
            )
        builder.setRollback(rollbackBuilder.build)
      case create: Node.Create =>
        val createBuilder =
          proto.Node.Create.newBuilder
            .setThinContractInstance(
              proto.ThinContractInstance.newBuilder
                .setTemplateId(convertIdentifier(create.templateId))
                .setValue(convertValue(create.arg))
                .build
            )
            .addAllSignatories(create.signatories.map(convertParty).asJava)
            .addAllStakeholders(create.stakeholders.map(convertParty).asJava)
        create.keyOpt.foreach(key =>
          createBuilder.setKeyWithMaintainers(convertKeyWithMaintainers(key))
        )
        optLocation.map(loc => builder.setLocation(convertLocation(loc)))
        builder.setCreate(createBuilder.build)
      case fetch: Node.Fetch =>
        builder.setFetch(
          proto.Node.Fetch.newBuilder
            .setContractId(coidToEventId(fetch.coid).toLedgerString)
            .setTemplateId(convertIdentifier(fetch.templateId))
            .addAllActingParties(fetch.actingParties.map(convertParty).asJava)
            .build
        )
      case ex: Node.Exercise =>
        optLocation.map(loc => builder.setLocation(convertLocation(loc)))
        builder.setExercise(
          proto.Node.Exercise.newBuilder
            .setTargetContractId(coidToEventId(ex.targetCoid).toLedgerString)
            .setTemplateId(convertIdentifier(ex.templateId))
            .setChoiceId(ex.choiceId)
            .setConsuming(ex.consuming)
            .addAllActingParties(ex.actingParties.map(convertParty).asJava)
            .setChosenValue(convertValue(ex.chosenValue))
            .addAllSignatories(ex.signatories.map(convertParty).asJava)
            .addAllStakeholders(ex.stakeholders.map(convertParty).asJava)
            .addAllChildren(
              ex.children
                .map(nid => proto.NodeId.newBuilder.setId(nid.index.toString).build)
                .toSeq
                .asJava
            )
            .build
        )

      case lookup: Node.LookupByKey =>
        optLocation.map(loc => builder.setLocation(convertLocation(loc)))
        builder.setLookupByKey({
          val builder = proto.Node.LookupByKey.newBuilder
            .setKeyWithMaintainers(convertKeyWithMaintainers(lookup.key))
          lookup.result.foreach(cid => builder.setContractId(coidToEventId(cid).toLedgerString))
          builder.build
        })
    }
    builder.build
  }

  def convertPackageId(pkg: Ref.PackageId): proto.PackageIdentifier =
    if (pkg == homePackageId)
      // Reconstitute the self package reference.
      packageIdSelf
    else
      proto.PackageIdentifier.newBuilder.setPackageId(pkg).build

  def convertIdentifier(identifier: Ref.Identifier): proto.Identifier =
    proto.Identifier.newBuilder
      .setPackage(convertPackageId(identifier.packageId))
      .setName(identifier.qualifiedName.toString)
      .build

  def convertLocation(loc: Ref.Location): proto.Location = {
    val (sline, scol) = loc.start
    val (eline, ecol) = loc.end
    proto.Location.newBuilder
      .setPackage(convertPackageId(loc.packageId))
      .setModule(loc.module.toString)
      .setDefinition(loc.definition)
      .setStartLine(sline)
      .setStartCol(scol)
      .setEndLine(eline)
      .setEndCol(ecol)
      .build

  }

  def convertValue(value: V): proto.Value = {
    val builder = proto.Value.newBuilder
    value match {
      case V.ValueRecord(tycon, fields) =>
        val rbuilder = proto.Record.newBuilder
        tycon.map(x => rbuilder.setRecordId(convertIdentifier(x)))
        builder.setRecord(
          rbuilder
            .addAllFields(
              fields.toSeq.map { case (optName, fieldValue) =>
                val builder = proto.Field.newBuilder
                optName.foreach(builder.setLabel)
                builder
                  .setValue(convertValue(fieldValue))
                  .build
              }.asJava
            )
            .build
        )
      case V.ValueVariant(tycon, variant, value) =>
        val vbuilder = proto.Variant.newBuilder
        tycon.foreach(x => vbuilder.setVariantId(convertIdentifier(x)))
        builder.setVariant(
          vbuilder
            .setConstructor(variant)
            .setValue(convertValue(value))
            .build
        )
      case V.ValueEnum(tycon, constructor) =>
        val eBuilder = proto.Enum.newBuilder.setConstructor(constructor)
        tycon.foreach(x => eBuilder.setEnumId(convertIdentifier(x)))
        builder.setEnum(eBuilder.build)
      case V.ValueContractId(coid) =>
        builder.setContractId(coidToEventId(coid).toLedgerString)
      case V.ValueList(values) =>
        builder.setList(
          proto.List.newBuilder
            .addAllElements(
              values
                .map(convertValue)
                .toImmArray
                .toSeq
                .asJava
            )
            .build
        )
      case V.ValueInt64(v) => builder.setInt64(v)
      case V.ValueNumeric(d) => builder.setNumeric(Numeric.toString(d))
      case V.ValueText(t) => builder.setText(t)
      case V.ValueTimestamp(ts) => builder.setTimestamp(ts.micros)
      case V.ValueDate(d) => builder.setDate(d.days)
      case V.ValueParty(p) => builder.setParty(p)
      case V.ValueBool(b) => builder.setBool(b)
      case V.ValueUnit => builder.setUnit(empty)
      case V.ValueOptional(mbV) =>
        val optionalBuilder = proto.Optional.newBuilder
        mbV match {
          case None => ()
          case Some(v) => optionalBuilder.setValue(convertValue(v))
        }
        builder.setOptional(optionalBuilder)
      case V.ValueTextMap(entries) =>
        val mapBuilder = proto.TextMap.newBuilder
        entries.toImmArray.foreach { case (k, v) =>
          mapBuilder.addEntries(
            proto.TextMap.Entry.newBuilder().setKey(k).setValue(convertValue(v))
          )
          ()
        }
        builder.setTextMap(mapBuilder)
      case V.ValueGenMap(entries) =>
        val mapBuilder = proto.Map.newBuilder
        entries.foreach { case (k, v) =>
          mapBuilder.addEntries(
            proto.Map.Entry.newBuilder().setKey(convertValue(k)).setValue(convertValue(v))
          )
          ()
        }
        builder.setMap(mapBuilder)
    }
    builder.build
  }

  def convertParty(p: Ref.Party): proto.Party =
    proto.Party.newBuilder.setParty(p).build

}
