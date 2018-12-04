package coursier.sbtlauncher

import java.io.{File, OutputStreamWriter}
import java.math.BigInteger
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.security.MessageDigest

import coursier.Cache.Logger
import coursier.core.{Artifact, Classifier, Extension, Type}
import coursier.maven.MavenAttributes
import coursier.paths.CachePath
import coursier.{Cache, CachePolicy, Dependency, Fetch, FileError, Module, Resolution, TermDisplay}
import coursier.util.Task

import scala.concurrent.ExecutionContext

final case class ResolutionCache(
  resolutionCacheDirOpt: Option[Path],
  repositories: Seq[coursier.core.Repository],
  cacheDir: File = Cache.default,
  cachePolicies: Seq[CachePolicy] = CachePolicy.default,
  log: String => Unit = _ => ()
) {

  import ResolutionCache._

  private def fetch(logger: Option[Logger]) = {
    def helper(policy: CachePolicy) =
      Cache.fetch[Task](cachePolicy = policy, logger = logger)

    val f = cachePolicies.map(helper)

    Fetch.from(repositories, f.head, f.tail: _*)
  }

  private def tasks(res: Resolution, logger: Option[Logger], classifiersOpt: Option[Seq[Classifier]] = None) = {
    val a = res.dependencyArtifacts(classifiersOpt)

    val keepArtifactTypes = classifiersOpt.fold(Set(Type.jar, Type.bundle))(c => c.map(c => MavenAttributes.classifierExtensionDefaultTypes.getOrElse((c, Extension.jar), ???)).toSet)

    a.collect {
      case (_, attr, artifact) if keepArtifactTypes(attr.`type`) =>
        def file(policy: CachePolicy) =
          Cache.file[Task](
            artifact,
            cachePolicy = policy,
            logger = logger
          )

        (file(cachePolicies.head) /: cachePolicies.tail)(_ orElse file(_))
          .run
          .map(artifact.->)
      }
  }

  private def actualArtifacts(
    dependencies: Seq[Dependency],
    forceVersion: Map[Module, String],
    classifiersOpt: Option[Seq[Classifier]]
  ): Either[ResolutionError, Seq[(Artifact, File)]] =
    if (dependencies.isEmpty)
      Right(Nil)
    else {

      val name = s"${dependencies.head.module}:${dependencies.head.version}"

      val initialRes = Resolution(
        dependencies.toSet,
        forceVersions = forceVersion
      )

      val logger =
        Some(new TermDisplay(
          new OutputStreamWriter(System.err)
        ))

      log(s"Resolving $name")

      logger.foreach(_.init {
        System.err.println(s"Resolving $name")
      })

      val res = initialRes.process.run(fetch(logger)).unsafeRun()(ExecutionContext.global)

      logger.foreach { l =>
        if (l.stopDidPrintSomething())
          System.err.println(s"Resolved $name")
      }

      log(s"Resolved $name")

      if (res.errors.nonEmpty)
        Left(ResolutionError.MetadataErrors(res.errors))
      else {

        if (res.conflicts.nonEmpty)
          Left(ResolutionError.Conflicts(res.conflicts))
        else {

          if (!res.isDone)
            Left(ResolutionError.DidNotConverge)
          else {

            val artifactLogger =
              Some(new TermDisplay(
                new OutputStreamWriter(System.err)
              ))

            log(s"Fetching $name artifacts")

            artifactLogger.foreach(_.init {
              System.err.println(s"Fetching $name artifacts")
            })

            val results = Task.gather.gather(tasks(res, artifactLogger, classifiersOpt)).unsafeRun()(ExecutionContext.global)

            artifactLogger.foreach { l =>
              if (l.stopDidPrintSomething())
                System.err.println(s"Fetched $name artifacts")
            }

            log(s"Fetched $name artifacts")

            val errors = results.collect { case (a, Left(err)) if !a.optional || !err.notFound => (a, err) }
            val files = results.collect { case (a, Right(f)) => (a, f) }

            if (errors.nonEmpty)
              Left(ResolutionError.DownloadErrors(errors))
            else
              Right(files)
          }
        }
      }
    }


  private lazy val repositoriesRepr: String = {
    // hope the toString representation won't change much
    repositories.map(_.toString).mkString("\n")
  }

  private lazy val repositoriesHash: String = {
    // hope the toString representation won't change much
    checksum(repositoriesRepr)
  }


  private def artifactsFromCache(keyRepr: String): Option[Seq[Artifact]] =
    resolutionCacheDirOpt.flatMap { resolutionCacheDir =>

      val repoDir = resolutionCacheDir.resolve(repositoriesHash)

      val hash = checksum(keyRepr)

      val keyDir = repoDir.resolve(hash)

      val result = keyDir.resolve("files")
      if (Files.exists(result)) {
        val content = new String(Files.readAllBytes(result), StandardCharsets.UTF_8)
        val checks = content
          .split('\n')
          .filter(_.nonEmpty)
          .map { l =>
            val idx = l.indexOf('#')
            val (url, changing) =
              if (idx < 0)
                (l, false)
              else {
                val elems = l.drop(idx + 1).split(';')
                (l.take(idx), elems.contains("changing"))
              }
            Artifact(url, Map(), Map(), changing, optional = false, None)
          }
        Some(checks)
      } else
        None
    }

  private def fromCache(keyRepr: String): Option[Seq[File]] =
    artifactsFromCache(keyRepr).flatMap { artifacts =>

      log(s"Found ${artifacts.length} artifacts via cache for\n$keyRepr\n")

      val fileOpts = artifacts.map { a =>
        // TODO Support authentication (user argument of CachePath.localFile)
        // TODO Check changing
        val f = CachePath.localFile(a.url, Cache.default, null, false)
        if (f.isFile)
          Some(f)
        else
          None
      }

      if (fileOpts.exists(_.isEmpty))
        None
      else {
        val files = fileOpts.collect { case Some(f) => f }
        log(s"Found ${files.length} files via cache for\n$keyRepr\n")
        Some(files)
      }
    }

  private def writeToCache(
    keyRepr: String,
    artifacts: Seq[Artifact]
  ): Unit =
    if (artifacts.forall(a => !a.optional && a.authentication.isEmpty))
      for (resolutionCacheDir <- resolutionCacheDirOpt) {
        val repoDir = resolutionCacheDir.resolve(repositoriesHash)
        // like in coursier.Cache, we should own a "structure lock" when creating directories
        Files.createDirectories(repoDir)

        val repoDescr = repoDir.resolve("repositories")
        if (!Files.exists(repoDescr))
          // possible concurrency issue here if two processes or threads try that at the same time
          Files.write(repoDescr, repositoriesRepr.getBytes(StandardCharsets.UTF_8))

        val hash = checksum(keyRepr)

        val keyDir = repoDir.resolve(hash)
        // like in coursier.Cache, we should own a "structure lock" when creating directories
        Files.createDirectories(keyDir)

        val keyDescr = keyDir.resolve("inputs")
        if (!Files.exists(keyDescr))
          // possible concurrency issue here if two processes or threads try that at the same time
          Files.write(keyDescr, keyRepr.getBytes(StandardCharsets.UTF_8))

        val result = keyDir.resolve("files")

        val content = artifacts
          .map { a =>
            var extra = List.empty[String]

            if (a.changing)
              extra = "changing" :: extra

            if (extra.isEmpty)
              a.url
            else
              s"${a.url}#${extra.mkString(";")}"
          }
          .mkString("\n")

        Files.write(result, content.getBytes(StandardCharsets.UTF_8))
      }


  def artifacts(
    dependencies: Seq[Dependency],
    forceVersion: Map[Module, String],
    classifiersOpt: Option[Seq[Classifier]] = None
  ): Either[ResolutionError, Seq[File]] = {

    val dependenciesRepr =
      // like for repositoriesRepr, toString might not be too suited for that…
      dependencies
        .map(_.toString)
        .sorted

    val forceVersionsRepr =
      forceVersion
        .toVector
        .map {
          case (m, v) =>
            s"$m:$v"
        }
        .sorted

    val classifiersRepr =
      classifiersOpt.fold("No classifiers") { l =>
        "Classifiers\n" + l.map("  " + _).mkString("\n")
      }

    val repr =
      "Dependencies\n" + dependenciesRepr.map("  " + _).mkString("\n") + "\n\n" +
      "Force versions\n" + forceVersionsRepr.map("  " + _).mkString("\n") + "\n\n" +
      classifiersRepr

    fromCache(repr) match {
      case Some(files) =>
        Right(files)
      case None =>
        actualArtifacts(dependencies, forceVersion, classifiersOpt)
          .right
          .map { l =>
            // optional set to false, as the artifacts we're handed here were all found
            writeToCache(repr, l.map(_._1.copy(optional = false)))
            l.map(_._2)
          }
    }
  }

}

object ResolutionCache {

  private def checksum(s: String): String = {

    val md = MessageDigest.getInstance("SHA-1")
    md.update(s.getBytes(StandardCharsets.UTF_8))

    val digest = md.digest()
    val sum = new BigInteger(1, digest)
    String.format("%040x", sum)
  }

  sealed abstract class ResolutionError extends Product with Serializable {
    def describe: String
  }

  object ResolutionError {
    final case class MetadataErrors(errors: Seq[((Module, String), Seq[String])]) extends ResolutionError {
      def describe: String =
        // kind of meh
        s"Errors:\n${errors.map("  " + _).mkString("\n")}"
    }
    final case class Conflicts(conflicts: Set[Dependency]) extends ResolutionError {
      def describe: String =
        // kind of meh
        s"Conflicts:\n${conflicts.map("  " + _).mkString("\n")}"
    }
    case object DidNotConverge extends ResolutionError {
      def describe: String =
        "Did not converge"
    }
    final case class DownloadErrors(errors: Seq[(Artifact, FileError)]) extends ResolutionError {
      def describe: String =
        // kind of meh
        s"Error downloading artifacts:\n${errors.map("  " + _).mkString("\n")}"
    }
  }

}
