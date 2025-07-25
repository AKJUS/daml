// Copyright (c) 2025 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.canton.participant.admin

import cats.data.{EitherT, OptionT}
import cats.syntax.bifunctor.*
import cats.syntax.foldable.*
import cats.syntax.functor.*
import cats.syntax.functorFilter.*
import cats.syntax.parallel.*
import com.digitalasset.base.error.RpcError
import com.digitalasset.canton.concurrent.FutureSupervisor
import com.digitalasset.canton.config.CantonRequireTypes.String255
import com.digitalasset.canton.config.{PackageMetadataViewConfig, ProcessingTimeout}
import com.digitalasset.canton.ledger.error.PackageServiceErrors
import com.digitalasset.canton.ledger.participant.state.PackageDescription
import com.digitalasset.canton.lifecycle.{FlagCloseable, FutureUnlessShutdown, LifeCycle}
import com.digitalasset.canton.logging.pretty.{Pretty, PrettyPrinting}
import com.digitalasset.canton.logging.{ErrorLoggingContext, NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.participant.admin.CantonPackageServiceError.PackageRemovalErrorCode
import com.digitalasset.canton.participant.admin.CantonPackageServiceError.PackageRemovalErrorCode.{
  CannotRemoveOnlyDarForPackage,
  MainPackageInUse,
  PackageRemovalError,
  PackageVetted,
}
import com.digitalasset.canton.participant.admin.PackageService.*
import com.digitalasset.canton.participant.admin.data.UploadDarData
import com.digitalasset.canton.participant.metrics.ParticipantMetrics
import com.digitalasset.canton.participant.store.DamlPackageStore.readPackageId
import com.digitalasset.canton.participant.store.memory.{
  MutablePackageMetadataViewImpl,
  PackageMetadataView,
}
import com.digitalasset.canton.participant.topology.PackageOps
import com.digitalasset.canton.platform.packages.DeduplicatingPackageLoader
import com.digitalasset.canton.time.Clock
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.{EitherTUtil, MonadUtil}
import com.digitalasset.canton.{LedgerSubmissionId, LfPackageId, ProtoDeserializationError}
import com.digitalasset.daml.lf.archive
import com.digitalasset.daml.lf.archive.{DamlLf, DarParser, Error as LfArchiveError}
import com.digitalasset.daml.lf.data.Ref.PackageId
import com.digitalasset.daml.lf.engine.Engine
import com.digitalasset.daml.lf.language.Ast.Package
import com.google.protobuf.ByteString
import org.apache.pekko.actor.ActorSystem
import slick.jdbc.GetResult

import java.util.UUID
import java.util.zip.ZipInputStream
import scala.concurrent.ExecutionContext

trait DarService {
  def upload(
      dars: Seq[UploadDarData],
      submissionIdO: Option[LedgerSubmissionId],
      vetAllPackages: Boolean,
      synchronizeVetting: PackageVettingSynchronization,
  )(implicit
      traceContext: TraceContext
  ): EitherT[FutureUnlessShutdown, RpcError, Seq[DarMainPackageId]]

  def validateDar(
      payload: ByteString,
      filename: String,
  )(implicit
      traceContext: TraceContext
  ): EitherT[FutureUnlessShutdown, RpcError, DarMainPackageId]

  def getDar(mainPackageId: DarMainPackageId)(implicit
      traceContext: TraceContext
  ): OptionT[FutureUnlessShutdown, PackageService.Dar]

  def getDarContents(mainPackageId: DarMainPackageId)(implicit
      traceContext: TraceContext
  ): OptionT[FutureUnlessShutdown, Seq[PackageDescription]]

  def listDars(limit: Option[Int])(implicit
      traceContext: TraceContext
  ): FutureUnlessShutdown[Seq[PackageService.DarDescription]]
}

class PackageService(
    val packageDependencyResolver: PackageDependencyResolver,
    protected val loggerFactory: NamedLoggerFactory,
    metrics: ParticipantMetrics,
    val packageMetadataView: PackageMetadataView,
    packageOps: PackageOps,
    packageUploader: PackageUploader,
    protected val timeouts: ProcessingTimeout,
)(implicit ec: ExecutionContext)
    extends DarService
    with NamedLogging
    with FlagCloseable {

  private val packageLoader = new DeduplicatingPackageLoader()
  private val packagesDarsStore = packageDependencyResolver.damlPackageStore

  def getLfArchive(packageId: PackageId)(implicit
      traceContext: TraceContext
  ): FutureUnlessShutdown[Option[DamlLf.Archive]] =
    packagesDarsStore.getPackage(packageId)

  def listPackages(limit: Option[Int] = None)(implicit
      traceContext: TraceContext
  ): FutureUnlessShutdown[Seq[PackageDescription]] =
    packagesDarsStore.listPackages(limit)

  def getPackageDescription(packageId: PackageId)(implicit
      traceContext: TraceContext
  ): OptionT[FutureUnlessShutdown, PackageDescription] =
    packagesDarsStore.getPackageDescription(packageId)

  def getPackage(packageId: PackageId)(implicit
      traceContext: TraceContext
  ): FutureUnlessShutdown[Option[Package]] =
    FutureUnlessShutdown.recoverFromAbortException(
      packageLoader.loadPackage(
        packageId,
        packageId => getLfArchive(packageId).failOnShutdownToAbortException("getLfArchive"),
        metrics.ledgerApiServer.execution.getLfPackage,
      )
    )

  def removePackage(
      packageId: PackageId,
      force: Boolean,
  )(implicit
      traceContext: TraceContext
  ): EitherT[FutureUnlessShutdown, RpcError, Unit] =
    if (force) {
      logger.info(s"Forced removal of package $packageId")
      EitherT.right(packagesDarsStore.removePackage(packageId))
    } else {
      val checkUnused =
        packageOps.checkPackageUnused(packageId)

      val checkNotVetted =
        packageOps
          .hasVettedPackageEntry(packageId)
          .flatMap[RpcError, Unit] {
            case true => EitherT.leftT(new PackageVetted(packageId))
            case false => EitherT.rightT(())
          }

      for {
        _ <- neededForAdminWorkflow(packageId)
        _ <- checkUnused
        _ <- checkNotVetted
        _ = logger.debug(s"Removing package $packageId")
        _ <- EitherT.right(packagesDarsStore.removePackage(packageId))
      } yield ()
    }

  def removeDar(mainPackageId: DarMainPackageId)(implicit
      tc: TraceContext
  ): EitherT[FutureUnlessShutdown, RpcError, Unit] =
    ifDarExists(mainPackageId)(removeDarLf(_, _))(ifNotExistsOperationFailed =
      "DAR archive removal"
    )

  def vetDar(
      mainPackageId: DarMainPackageId,
      synchronizeVetting: PackageVettingSynchronization,
  )(implicit
      traceContext: TraceContext
  ): EitherT[FutureUnlessShutdown, RpcError, Unit] =
    ifDarExists(mainPackageId) { (_, darLf) =>
      packageOps
        .vetPackages(darLf.all.map(readPackageId), synchronizeVetting)
        .leftWiden[RpcError]
    }(ifNotExistsOperationFailed = "DAR archive vetting")

  def unvetDar(mainPackageId: DarMainPackageId)(implicit
      traceContext: TraceContext
  ): EitherT[FutureUnlessShutdown, RpcError, Unit] =
    ifDarExists(mainPackageId) { (descriptor, lfArchive) =>
      val packages = lfArchive.all.map(readPackageId)
      val mainPkg = readPackageId(lfArchive.main)
      revokeVettingForDar(mainPkg, packages, descriptor)
    }(ifNotExistsOperationFailed = "DAR archive unvetting")

  private def ifDarExists(mainPackageId: DarMainPackageId)(
      action: (
          DarDescription,
          archive.Dar[DamlLf.Archive],
      ) => EitherT[FutureUnlessShutdown, RpcError, Unit]
  )(ifNotExistsOperationFailed: => String)(implicit
      tc: TraceContext
  ): EitherT[FutureUnlessShutdown, RpcError, Unit] =
    for {
      dar <- packagesDarsStore
        .getDar(mainPackageId)
        .toRight(
          CantonPackageServiceError.Fetching.DarNotFound
            .Reject(
              operation = ifNotExistsOperationFailed,
              mainPackageId = mainPackageId.unwrap,
            )
        )
      darLfE <- EitherT.fromEither[FutureUnlessShutdown](
        PackageService.darToLf(dar).leftMap { err =>
          CantonPackageServiceError.InternalError.Error(err)
        }
      )
      (descriptor, lfArchive) =
        darLfE
      _ <- action(descriptor, lfArchive)
    } yield ()

  private def neededForAdminWorkflow(packageId: PackageId)(implicit
      elc: ErrorLoggingContext
  ): EitherT[FutureUnlessShutdown, PackageRemovalError, Unit] =
    EitherTUtil.condUnitET(
      !AdminWorkflowServices.AdminWorkflowPackages.keySet.contains(packageId),
      new PackageRemovalErrorCode.CannotRemoveAdminWorkflowPackage(packageId),
    )

  private def removeDarLf(
      darDescriptor: DarDescription,
      dar: archive.Dar[DamlLf.Archive],
  )(implicit
      tc: TraceContext
  ): EitherT[FutureUnlessShutdown, RpcError, Unit] = {
    // Can remove the DAR if:
    // 1. The main package of the dar is unused
    // 2. For all dependencies of the DAR, either:
    //     - They are unused
    //     - Or they are contained in another vetted DAR
    // 3. The main package of the dar is either:
    //     - Already un-vetted
    //     - Or can be automatically un-vetted, by revoking a vetting transaction corresponding to all packages in the DAR

    val packages = {
      import scalaz.syntax.traverse.*
      dar.map(readPackageId)
    }

    val mainPkg = readPackageId(dar.main)
    for {
      _notAdminWf <- neededForAdminWorkflow(mainPkg)

      _mainUnused <- packageOps
        .checkPackageUnused(mainPkg)
        .leftMap(err =>
          new MainPackageInUse(err.pkg, darDescriptor, err.contract, err.synchronizerId)
        )

      packageUsed <- EitherT
        .liftF(packages.dependencies.parTraverse(p => packageOps.checkPackageUnused(p).value))

      usedPackages = packageUsed.mapFilter {
        case Left(packageInUse: PackageRemovalErrorCode.PackageInUse) => Some(packageInUse.pkg)
        case Right(()) => None
      }

      _unit <- packagesDarsStore
        .anyPackagePreventsDarRemoval(usedPackages, darDescriptor)
        .toLeft(())
        .leftMap(p => new CannotRemoveOnlyDarForPackage(p, darDescriptor))

      packagesThatCanBeRemoved_ <- EitherT
        .liftF(
          packagesDarsStore
            .determinePackagesExclusivelyInDar(packages.all, darDescriptor)
        )

      packagesThatCanBeRemoved = packagesThatCanBeRemoved_.toList

      _unit <- revokeVettingForDar(
        mainPkg,
        packagesThatCanBeRemoved,
        darDescriptor,
      )

      // TODO(#26078): update documentation to reflect main package dependency removal changes
      _unit <- EitherT.liftF(
        packagesThatCanBeRemoved.parTraverse(packagesDarsStore.removePackage(_))
      )

      _removed <- {
        logger.info(s"Removing dar ${darDescriptor.mainPackageId}")
        EitherT
          .liftF[FutureUnlessShutdown, RpcError, Unit](
            packagesDarsStore.removeDar(darDescriptor.mainPackageId)
          )
      }
    } yield ()
  }

  private def revokeVettingForDar(
      mainPkg: PackageId,
      packages: List[PackageId],
      darDescriptor: DarDescription,
  )(implicit
      tc: TraceContext
  ): EitherT[FutureUnlessShutdown, RpcError, Unit] =
    packageOps
      .hasVettedPackageEntry(mainPkg)
      .flatMap { isVetted =>
        if (!isVetted)
          EitherT.pure[FutureUnlessShutdown, RpcError](
            logger.info(
              s"Package with id $mainPkg is already unvetted. Doing nothing for the unvet operation"
            )
          )
        else
          packageOps.revokeVettingForPackages(mainPkg, packages, darDescriptor).leftWiden
      }

  /** Performs the upload DAR flow:
    *   1. Decodes the provided DAR payload
    *   1. Validates the resulting Daml packages
    *   1. Persists the DAR and decoded archives in the DARs and package stores
    *   1. Dispatches the package upload event for inclusion in the ledger sync event stream
    *   1. Updates the
    *      [[com.digitalasset.canton.participant.store.memory.MutablePackageMetadataView]] which is
    *      used for subsequent DAR upload validations and incoming Ledger API queries
    *   1. Issues a package vetting topology transaction for all uploaded packages (if
    *      `vetAllPackages` is enabled) and waits for for its completion (if `synchronizeVetting` is
    *      enabled).
    *
    * @param darBytes
    *   The DAR payload to store.
    * @param description
    *   A description of the DAR.
    * @param submissionIdO
    *   upstream submissionId for ledger api server to recognize previous package upload requests
    * @param vetAllPackages
    *   if true, then the packages will be vetted automatically
    * @param synchronizeVetting
    *   a value of PackageVettingSynchronization, that checks that the packages have been vetted on
    *   all connected synchronizers. The Future returned by the check will complete once all
    *   synchronizers have observed the vetting for the new packages. The caller may also pass be a
    *   no-op implementation that immediately returns, depending no the caller's needs for
    *   synchronization.
    */
  final def upload(
      darBytes: ByteString,
      description: Option[String],
      submissionIdO: Option[LedgerSubmissionId],
      vetAllPackages: Boolean,
      synchronizeVetting: PackageVettingSynchronization,
      expectedMainPackageId: Option[LfPackageId],
  )(implicit
      traceContext: TraceContext
  ): EitherT[FutureUnlessShutdown, RpcError, DarMainPackageId] =
    upload(
      Seq(UploadDarData(darBytes, description, expectedMainPackageId)),
      submissionIdO,
      vetAllPackages,
      synchronizeVetting,
    ).subflatMap {
      case Seq(mainPackageId) => Right(mainPackageId)
      case Seq() =>
        Left(
          PackageServiceErrors.InternalError.Generic(
            "Uploading the DAR unexpectedly did not return a DAR package ID"
          )
        )
      case many =>
        Left(
          PackageServiceErrors.InternalError.Generic(
            s"Uploading the DAR unexpectedly returned multiple DAR package IDs: $many"
          )
        )
    }

  /** Performs the upload DAR flow:
    *   1. Decodes the provided DAR payloads
    *   1. Validates the resulting Daml packages
    *   1. Persists the DARs and decoded archives in the DARs and package stores
    *   1. Dispatches the package upload event for inclusion in the ledger sync event stream
    *   1. Updates the
    *      [[com.digitalasset.canton.participant.store.memory.MutablePackageMetadataView]] which is
    *      used for subsequent DAR upload validations and incoming Ledger API queries
    *   1. Issues a package vetting topology transaction for all uploaded packages (if
    *      `vetAllPackages` is enabled) and waits for for its completion (if `synchronizeVetting` is
    *      enabled).
    *
    * @param dars
    *   The DARs (bytes, description, expected main package) to upload.
    * @param submissionIdO
    *   upstream submissionId for ledger api server to recognize previous package upload requests
    * @param vetAllPackages
    *   if true, then the packages will be vetted automatically
    * @param synchronizeVetting
    *   a value of PackageVettingSynchronization, that checks that the packages have been vetted on
    *   all connected synchronizers. The Future returned by the check will complete once all
    *   synchronizers have observed the vetting to be effective for the new packages. The caller may
    *   also pass be a no-op implementation that immediately returns, depending no the caller's
    *   needs for synchronization.
    */
  def upload(
      dars: Seq[UploadDarData],
      submissionIdO: Option[LedgerSubmissionId],
      vetAllPackages: Boolean,
      synchronizeVetting: PackageVettingSynchronization,
  )(implicit
      traceContext: TraceContext
  ): EitherT[FutureUnlessShutdown, RpcError, Seq[DarMainPackageId]] = {
    val submissionId =
      submissionIdO.getOrElse(LedgerSubmissionId.assertFromString(UUID.randomUUID().toString))
    for {
      uploadResult <- MonadUtil.sequentialTraverse(dars) {
        case UploadDarData(darBytes, description, expectedMainPackageId) =>
          packageUploader.upload(
            darBytes,
            description,
            submissionId,
            expectedMainPackageId,
          )
      }
      (mainPkgs, allPackages) = uploadResult.foldMap { case (mainPkg, dependencies) =>
        (List(DarMainPackageId.tryCreate(mainPkg)), mainPkg +: dependencies)
      }
      _ <- EitherTUtil.ifThenET(vetAllPackages)(
        vetPackages(allPackages, synchronizeVetting)
      )

    } yield mainPkgs // try is okay as we get this package-id from the uploader
  }

  /** Returns all dars that reference a certain package id */
  def getPackageReferences(packageId: LfPackageId)(implicit
      traceContext: TraceContext
  ): FutureUnlessShutdown[Seq[DarDescription]] = packagesDarsStore.getPackageReferences(packageId)

  def validateDar(
      payload: ByteString,
      darName: String,
  )(implicit
      traceContext: TraceContext
  ): EitherT[FutureUnlessShutdown, RpcError, DarMainPackageId] =
    packageUploader.validateDar(payload, darName)

  override def getDar(mainPackageId: DarMainPackageId)(implicit
      traceContext: TraceContext
  ): OptionT[FutureUnlessShutdown, PackageService.Dar] =
    packagesDarsStore.getDar(mainPackageId)

  override def listDars(limit: Option[Int])(implicit
      traceContext: TraceContext
  ): FutureUnlessShutdown[Seq[PackageService.DarDescription]] = packagesDarsStore.listDars(limit)

  override def getDarContents(mainPackageId: DarMainPackageId)(implicit
      traceContext: TraceContext
  ): OptionT[FutureUnlessShutdown, Seq[PackageDescription]] =
    packagesDarsStore
      .getPackageDescriptionsOfDar(mainPackageId)

  def vetPackages(
      packages: Seq[PackageId],
      synchronizeVetting: PackageVettingSynchronization,
  )(implicit
      traceContext: TraceContext
  ): EitherT[FutureUnlessShutdown, RpcError, Unit] =
    packageOps
      .vetPackages(packages, synchronizeVetting)
      .leftMap[RpcError] { err =>
        implicit val code = err.code
        CantonPackageServiceError.IdentityManagerParentError(err)
      }

  override def onClosed(): Unit = LifeCycle.close(packageUploader, packageMetadataView)(logger)

}

object PackageService {
  def createAndInitialize(
      clock: Clock,
      engine: Engine,
      packageDependencyResolver: PackageDependencyResolver,
      enableUpgradeValidation: Boolean,
      enableStrictDarValidation: Boolean,
      futureSupervisor: FutureSupervisor,
      loggerFactory: NamedLoggerFactory,
      metrics: ParticipantMetrics,
      exitOnFatalFailures: Boolean,
      packageMetadataViewConfig: PackageMetadataViewConfig,
      packageOps: PackageOps,
      timeouts: ProcessingTimeout,
  )(implicit
      ec: ExecutionContext,
      actorSystem: ActorSystem,
      traceContext: TraceContext,
  ): FutureUnlessShutdown[PackageService] = {
    val mutablePackageMetadataView = new MutablePackageMetadataViewImpl(
      clock,
      packageDependencyResolver.damlPackageStore,
      loggerFactory,
      packageMetadataViewConfig,
      timeouts,
    )

    val packageUploader = PackageUploader(
      clock,
      engine,
      enableUpgradeValidation,
      enableStrictDarValidation,
      futureSupervisor,
      packageDependencyResolver,
      mutablePackageMetadataView,
      exitOnFatalFailures,
      timeouts,
      loggerFactory,
    )

    val packageService = new PackageService(
      packageDependencyResolver,
      loggerFactory,
      metrics,
      mutablePackageMetadataView,
      packageOps,
      packageUploader,
      timeouts,
    )

    // Initialize the packageMetadataView and return only the PackageService. It also takes care of teardown of the packageMetadataView and packageUploader
    mutablePackageMetadataView.refreshState.map(_ => packageService)
  }

  // Opaque type for the main package id of a DAR
  final case class DarMainPackageId private (value: String255) extends AnyVal {
    def unwrap: String = value.unwrap
    def toProtoPrimitive: String = value.toProtoPrimitive
    def str: String = value.str
  }

  object DarMainPackageId {
    def create(str: String): Either[String, DarMainPackageId] =
      for {
        _ <- LfPackageId.fromString(str)
        lengthLimited <- String255.create(str)
      } yield DarMainPackageId(lengthLimited)

    def tryCreate(str: String): DarMainPackageId =
      create(str)
        .leftMap(err =>
          throw new IllegalArgumentException(
            s"Cannot convert '$str' to ${classOf[DarMainPackageId].getSimpleName}: $err"
          )
        )
        .merge

    def fromProtoPrimitive(str: String): Either[ProtoDeserializationError, DarMainPackageId] =
      create(str).leftMap(err => ProtoDeserializationError.StringConversionError(err))
  }

  final case class DarDescription(
      mainPackageId: DarMainPackageId,
      description: String255,
      name: String255,
      version: String255,
  ) extends PrettyPrinting {

    override protected def pretty: Pretty[DarDescription] = prettyOfClass(
      param("dar-id", _.mainPackageId.unwrap.readableHash),
      param("name", _.name.str.unquoted),
      param("version", _.version.str.unquoted),
      param("description", _.description.str.unquoted),
    )

  }

  object DarDescription {
    implicit val getResult: GetResult[DarDescription] =
      GetResult(r =>
        DarDescription(
          mainPackageId = DarMainPackageId.tryCreate(r.<<),
          description = String255.tryCreate(r.<<),
          name = String255.tryCreate(r.<<),
          version = String255.tryCreate(r.<<),
        )
      )
  }

  final case class Dar(descriptor: DarDescription, bytes: Array[Byte]) {
    override def equals(other: Any): Boolean = other match {
      case that: Dar =>
        // Array equality only returns true when both are the same instance.
        // So by using sameElements to compare the bytes, we ensure that we compare the data, not the instance.
        (bytes sameElements that.bytes) && descriptor == that.descriptor
      case _ => false
    }
  }

  object Dar {
    implicit def getResult(implicit getResultByteArray: GetResult[Array[Byte]]): GetResult[Dar] =
      GetResult(r => Dar(r.<<, r.<<))
  }

  private def darToLf(
      dar: Dar
  ): Either[String, (DarDescription, archive.Dar[DamlLf.Archive])] = {
    val bytes = dar.bytes
    val payload = ByteString.copyFrom(bytes)
    val stream = new ZipInputStream(payload.newInput())
    DarParser
      .readArchive(dar.descriptor.description.str, stream)
      .fold(
        _ => Left(s"Cannot parse stored dar $dar"),
        x => Right(dar.descriptor -> x),
      )
  }

  def catchUpstreamErrors[E](
      attempt: Either[LfArchiveError, E]
  )(implicit
      executionContext: ExecutionContext,
      errorLoggingContext: ErrorLoggingContext,
  ): EitherT[FutureUnlessShutdown, RpcError, E] =
    EitherT.fromEither(attempt match {
      case Right(value) => Right(value)
      case Left(LfArchiveError.InvalidDar(entries, cause)) =>
        Left(PackageServiceErrors.Reading.InvalidDar.Error(entries.entries.keys.toSeq, cause))
      case Left(LfArchiveError.InvalidZipEntry(name, entries)) =>
        Left(
          PackageServiceErrors.Reading.InvalidZipEntry.Error(name, entries.entries.keys.toSeq)
        )
      case Left(LfArchiveError.InvalidLegacyDar(entries)) =>
        Left(PackageServiceErrors.Reading.InvalidLegacyDar.Error(entries.entries.keys.toSeq))
      case Left(LfArchiveError.ZipBomb) =>
        Left(PackageServiceErrors.Reading.ZipBomb.Error(LfArchiveError.ZipBomb.getMessage))
      case Left(e: LfArchiveError) =>
        Left(PackageServiceErrors.Reading.ParseError.Error(e.msg))
      case Left(e) =>
        Left(PackageServiceErrors.InternalError.Unhandled(e))
    })

}
