// Copyright (c) 2025 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.canton.platform.apiserver.services

import com.digitalasset.base.error.{BaseError, DamlErrorWithDefiniteAnswer, RpcError}
import com.digitalasset.canton.ledger.error.LedgerApiErrors
import com.digitalasset.canton.ledger.error.groups.{
  CommandExecutionErrors,
  ConsistencyErrors,
  RequestValidationErrors,
}
import com.digitalasset.canton.logging.{ErrorLoggingContext, NoLogging}
import com.digitalasset.canton.protocol.LfContractId
import com.digitalasset.canton.time.NonNegativeFiniteDuration
import com.digitalasset.canton.topology.SynchronizerId
import com.digitalasset.daml.lf.data.{Ref, Time}
import com.digitalasset.daml.lf.engine.Error as LfError
import com.digitalasset.daml.lf.engine.Error.{Interpretation, Package, Preprocessing, Validation}
import com.digitalasset.daml.lf.interpretation.Error as LfInterpretationError

sealed abstract class ErrorCause extends Product with Serializable

object ErrorCause {
  final case class DamlLf(error: LfError) extends ErrorCause
  final case class LedgerTime(retries: Int) extends ErrorCause
  sealed abstract class DisclosedContractsSynchronizerIdMismatch extends ErrorCause
  final case class DisclosedContractsSynchronizerIdsMismatch(
      mismatchingDisclosedContractSynchronizerIds: Map[LfContractId, SynchronizerId]
  ) extends DisclosedContractsSynchronizerIdMismatch
  final case class PrescribedSynchronizerIdMismatch(
      disclosedContractIds: Set[LfContractId],
      synchronizerIdOfDisclosedContracts: SynchronizerId,
      commandsSynchronizerId: SynchronizerId,
  ) extends DisclosedContractsSynchronizerIdMismatch

  final case class InterpretationTimeExceeded(
      ledgerEffectiveTime: Time.Timestamp, // the Ledger Effective Time of the submitted command
      tolerance: NonNegativeFiniteDuration,
      transactionTrace: Option[String],
  ) extends ErrorCause

  final case class RoutingFailed(err: BaseError) extends ErrorCause
}

object RejectionGenerators {

  def commandExecutorError(cause: ErrorCause)(implicit
      errorLoggingContext: ErrorLoggingContext
  ): RpcError = {

    def processPackageError(err: LfError.Package.Error): RpcError = err match {
      case e: Package.Internal => LedgerApiErrors.InternalError.PackageInternal(e)
      case Package.Validation(validationError) =>
        CommandExecutionErrors.Package.PackageValidationFailed
          .Reject(validationError.pretty)
      case Package.MissingPackage(packageRef, context) =>
        RequestValidationErrors.NotFound.Package
          .InterpretationReject(packageRef, context)
      case Package.AllowedLanguageVersion(packageId, languageVersion, allowedLanguageVersions) =>
        CommandExecutionErrors.Package.AllowedLanguageVersions.Error(
          packageId,
          languageVersion,
          allowedLanguageVersions,
        )
      case e: Package.DarSelfConsistency =>
        LedgerApiErrors.InternalError.PackageSelfConsistency(e)
    }

    def processPreprocessingError(err: LfError.Preprocessing.Error): RpcError = err match {
      case e: Preprocessing.Internal => LedgerApiErrors.InternalError.Preprocessing(e)
      case Preprocessing.UnresolvedPackageName(pkgName, context) =>
        RequestValidationErrors.NotFound.Package
          .InterpretationReject(Ref.PackageRef.Name(pkgName), context)
      case e => CommandExecutionErrors.Preprocessing.PreprocessingFailed.Reject(e)
    }

    def processValidationError(err: LfError.Validation.Error): RpcError = err match {
      // we shouldn't see such errors during submission
      case e: Validation.ReplayMismatch => LedgerApiErrors.InternalError.Validation(e)
    }

    def processDamlException(
        err: com.digitalasset.daml.lf.interpretation.Error,
        renderedMessage: String,
        transactionTrace: Option[String],
    ): RpcError =
      // detailMessage is only suitable for server side debugging but not for the user, so don't pass except on internal errors

      err match {
        case LfInterpretationError.ContractNotFound(cid) =>
          ConsistencyErrors.ContractNotFound
            .Reject(renderedMessage, cid)
        case LfInterpretationError.ContractKeyNotFound(key) =>
          CommandExecutionErrors.Interpreter.LookupErrors.ContractKeyNotFound
            .Reject(renderedMessage, key)
        case _: LfInterpretationError.FailedAuthorization =>
          CommandExecutionErrors.Interpreter.AuthorizationError
            .Reject(renderedMessage)
        case LfInterpretationError.UnresolvedPackageName(packageName) =>
          CommandExecutionErrors.Interpreter.LookupErrors.UnresolvedPackageName
            .Reject(renderedMessage, packageName)
        case e: LfInterpretationError.ContractNotActive =>
          CommandExecutionErrors.Interpreter.ContractNotActive
            .Reject(renderedMessage, e)
        case e: LfInterpretationError.DisclosedContractKeyHashingError =>
          CommandExecutionErrors.Interpreter.DisclosedContractKeyHashingError
            .Reject(renderedMessage, e)
        case LfInterpretationError.DuplicateContractKey(key) =>
          ConsistencyErrors.DuplicateContractKey
            .RejectWithContractKeyArg(renderedMessage, key)
        case LfInterpretationError.InconsistentContractKey(key) =>
          ConsistencyErrors.InconsistentContractKey
            .RejectWithContractKeyArg(renderedMessage, key)
        case e: LfInterpretationError.UnhandledException =>
          CommandExecutionErrors.Interpreter.UnhandledException.Reject(
            renderedMessage + transactionTrace.fold("")("\n" + _) + ".",
            e,
          )
        case e: LfInterpretationError.UserError =>
          CommandExecutionErrors.Interpreter.InterpretationUserError
            .Reject(renderedMessage, e)
        case _: LfInterpretationError.TemplatePreconditionViolated =>
          CommandExecutionErrors.Interpreter.TemplatePreconditionViolated
            .Reject(renderedMessage)
        case e: LfInterpretationError.CreateEmptyContractKeyMaintainers =>
          CommandExecutionErrors.Interpreter.CreateEmptyContractKeyMaintainers
            .Reject(renderedMessage, e)
        case e: LfInterpretationError.FetchEmptyContractKeyMaintainers =>
          CommandExecutionErrors.Interpreter.FetchEmptyContractKeyMaintainers
            .Reject(renderedMessage, e)
        case e: LfInterpretationError.WronglyTypedContract =>
          CommandExecutionErrors.Interpreter.WronglyTypedContract
            .Reject(renderedMessage, e)
        case e: LfInterpretationError.ContractDoesNotImplementInterface =>
          CommandExecutionErrors.Interpreter.ContractDoesNotImplementInterface
            .Reject(renderedMessage, e)
        case e: LfInterpretationError.ContractDoesNotImplementRequiringInterface =>
          CommandExecutionErrors.Interpreter.ContractDoesNotImplementRequiringInterface
            .Reject(renderedMessage, e)
        case LfInterpretationError.NonComparableValues =>
          CommandExecutionErrors.Interpreter.NonComparableValues
            .Reject(renderedMessage)
        case _: LfInterpretationError.ContractIdInContractKey =>
          CommandExecutionErrors.Interpreter.ContractIdInContractKey
            .Reject(renderedMessage)
        case e: LfInterpretationError.ContractIdComparability =>
          CommandExecutionErrors.Interpreter.ContractIdComparability
            .Reject(renderedMessage, e)
        case e: LfInterpretationError.ValueNesting =>
          CommandExecutionErrors.Interpreter.ValueNesting
            .Reject(renderedMessage, e)
        case e: LfInterpretationError.FailureStatus =>
          CommandExecutionErrors.Interpreter.FailureStatus
            .Reject(renderedMessage, e, transactionTrace)
        case LfInterpretationError.Upgrade(error: LfInterpretationError.Upgrade.ValidationFailed) =>
          CommandExecutionErrors.Interpreter.UpgradeError.ValidationFailed
            .Reject(renderedMessage, error)
        case LfInterpretationError.Upgrade(
              error: LfInterpretationError.Upgrade.DowngradeDropDefinedField
            ) =>
          CommandExecutionErrors.Interpreter.UpgradeError.DowngradeDropDefinedField
            .Reject(renderedMessage, error)
        case LfInterpretationError.Upgrade(
              error: LfInterpretationError.Upgrade.DowngradeFailed
            ) =>
          CommandExecutionErrors.Interpreter.UpgradeError.DowngradeFailed
            .Reject(renderedMessage, error)
        case LfInterpretationError.Crypto(
              error: LfInterpretationError.Crypto.MalformedByteEncoding
            ) =>
          CommandExecutionErrors.Interpreter.CryptoError.MalformedByteEncoding
            .Reject(renderedMessage, error)
        case LfInterpretationError.Crypto(
              error: LfInterpretationError.Crypto.MalformedKey
            ) =>
          CommandExecutionErrors.Interpreter.CryptoError.MalformedKey
            .Reject(renderedMessage, error)
        case LfInterpretationError.Crypto(
              error: LfInterpretationError.Crypto.MalformedSignature
            ) =>
          CommandExecutionErrors.Interpreter.CryptoError.MalformedSignature
            .Reject(renderedMessage, error)
        case LfInterpretationError.Crypto(
              error: LfInterpretationError.Crypto.MalformedContractId
            ) =>
          CommandExecutionErrors.Interpreter.CryptoError.MalformedContractId
            .Reject(renderedMessage, error)
        case LfInterpretationError.Dev(_, err) =>
          CommandExecutionErrors.Interpreter.InterpretationDevError
            .Reject(renderedMessage, err)
      }

    def processInterpretationError(
        err: LfError.Interpretation.Error,
        detailMessage: Option[String],
    ): RpcError =
      err match {
        case Interpretation.Internal(location, message, _) =>
          LedgerApiErrors.InternalError.Interpretation(location, message, detailMessage)
        case m @ Interpretation.DamlException(error) =>
          processDamlException(error, m.message, detailMessage)
      }

    def processLfError(error: LfError) = {
      val transformed = error match {
        case LfError.Package(packageError) => processPackageError(packageError)
        case LfError.Preprocessing(processingError) => processPreprocessingError(processingError)
        case LfError.Interpretation(interpretationError, detailMessage) =>
          processInterpretationError(interpretationError, detailMessage)
        case LfError.Validation(validationError) => processValidationError(validationError)
        case e
            if e.message.contains(
              "requires authorizers"
            ) => // Keeping this around as a string match as daml is not yet generating LfError.InterpreterErrors.Validation
          CommandExecutionErrors.Interpreter.AuthorizationError.Reject(e.message)
      }
      transformed
    }

    cause match {
      case ErrorCause.DamlLf(error) => processLfError(error)
      case ErrorCause.LedgerTime(retries) =>
        CommandExecutionErrors.FailedToDetermineLedgerTime
          .Reject(s"Could not find a suitable ledger time after $retries retries")
      case ErrorCause.InterpretationTimeExceeded(let, tolerance, transactionTrace) =>
        CommandExecutionErrors.TimeExceeded.Reject(
          s"Time exceeds limit of Ledger Effective Time ($let) + tolerance ($tolerance). Interpretation aborted" + transactionTrace
            .fold("")("\n" + _) + "."
        )
      case ErrorCause.DisclosedContractsSynchronizerIdsMismatch(
            mismatchingDisclosedContractSynchronizerIds
          ) =>
        CommandExecutionErrors.DisclosedContractsSynchronizerIdMismatch.Reject(
          mismatchingDisclosedContractSynchronizerIds.view.mapValues(_.toProtoPrimitive).toMap
        )
      case ErrorCause.PrescribedSynchronizerIdMismatch(
            disclosedContractsWithSynchronizerId,
            synchronizerIdOfDisclosedContracts,
            commandsSynchronizerId,
          ) =>
        CommandExecutionErrors.PrescribedSynchronizerIdMismatch.Reject(
          disclosedContractsWithSynchronizerId,
          synchronizerIdOfDisclosedContracts.toProtoPrimitive,
          commandsSynchronizerId.toProtoPrimitive,
        )
      case ErrorCause.RoutingFailed(baseError) =>
        // TODO(#25385) Streamline ErrorCause usage
        // TODO(#25385) This is logged again on this creation
        new DamlErrorWithDefiniteAnswer(baseError.cause, baseError.throwableO)(
          baseError.code,
          NoLogging,
        )
    }
  }
}
