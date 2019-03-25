package coursier.sbtlauncher

import java.io.File
import java.math.BigInteger
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.security.MessageDigest

import coursier.cache.FileCache
import coursier.cache.loggers.{FileTypeRefreshDisplay, RefreshLogger}
import coursier.core.{Artifact, Classifier, Organization}
import coursier.params.ResolutionParams
import coursier.{Dependency, Fetch, Module, moduleNameString}

final case class ResolutionCache(
  resolutionCacheDirOpt: Option[Path],
  repositories: Seq[coursier.core.Repository],
  log: String => Unit = _ => ()
) {

  import ResolutionCache._

  private val cache = FileCache()
    .withLogger(
      RefreshLogger.create(FileTypeRefreshDisplay.create())
    )

  private def actualArtifacts(
    dependencies: Seq[Dependency],
    forceVersion: Map[Module, String],
    classifiersOpt: Option[Seq[Classifier]],
    name: String
  ): Either[Exception, Seq[(Artifact, File)]] =
    if (dependencies.isEmpty)
      Right(Nil)
    else {

      val name0 =
        if (name.isEmpty) {
          val extra =
            if (dependencies.lengthCompare(1) > 0)
              s" and ${dependencies.length - 1} other dependencies"
            else
              ""
          s"${dependencies.head.module}:${dependencies.head.version}" + extra
        } else
          name

      val classifiers = classifiersOpt.getOrElse(Nil).toSet
      Fetch()
        .addDependencies(dependencies: _*)
        .withRepositories(repositories)
        .withClassifiers(classifiers)
        .withResolutionParams(
          ResolutionParams()
            .withForceVersion(forceVersion)
        )
        .withCache(cache)
        .eitherResult()
        .map(_._2)
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
        Some(cache.localFile(a.url))
          .filter(_.isFile)
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
    forceVersion: Map[Module, String] = Map(),
    classifiers: Seq[Classifier] = null,
    forceScala: (Organization, String) = null,
    name: String = ""
  ): Either[Exception, Seq[File]] = {

    val dependenciesRepr =
      // like for repositoriesRepr, toString might not be too suited for that…
      dependencies
        .map(_.toString)
        .sorted

    val forceVersion0 = forceVersion ++ {
      Option(forceScala) match {
        case None => Seq()
        case Some((org, sv)) =>
          Seq(name"scala-library", name"scala-compiler", name"scala-reflect", name"scalap")
            .map(n => Module(org, n) -> sv)
      }
    }

    val forceVersionsRepr =
      forceVersion0
        .toVector
        .map {
          case (m, v) =>
            s"$m:$v"
        }
        .sorted

    val classifiersRepr =
      Option(classifiers).fold("No classifiers") { l =>
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
        actualArtifacts(
          dependencies,
          forceVersion0,
          Option(classifiers),
          name
        )
          .right
          .map { l =>
            // optional set to false, as the artifacts we're handed here were all found
            if (l.nonEmpty)
              writeToCache(repr, l.map(_._1.copy(optional = false)))
            l.map(_._2)
          }
    }
  }

  def artifactsOrExit(
    dependencies: Seq[Dependency],
    forceVersion: Map[Module, String] = Map(),
    classifiers: Seq[Classifier] = null,
    forceScala: (Organization, String) = null,
    name: String = ""
  ): Seq[File] =
    artifacts(
      dependencies,
      forceVersion,
      classifiers,
      forceScala,
      name
    ) match {
      case Left(err) =>
        System.err.println(err.getMessage)
        sys.exit(1)
      case Right(files) =>
        files
    }

  def artifactOrExit(
    dependency: Dependency,
    forceVersion: Map[Module, String] = Map(),
    classifiers: Seq[Classifier] = null,
    forceScala: (Organization, String) = null,
    name: String = ""
  ): File =
    artifacts(
      Seq(dependency.copy(transitive = false)),
      forceVersion,
      classifiers,
      forceScala,
      name
    ) match {
      case Left(err) =>
        System.err.println(err.getMessage)
        sys.exit(1)
      case Right(Nil) =>
        System.err.println(s"No JAR found for ${dependency.module}:${dependency.version}${Option(classifiers).fold("")(_.map(_.value).mkString(" (classifiers ", ", ", ")"))}")
        sys.exit(1)
      case Right(Seq(file)) =>
        file
      case Right(_) =>
        System.err.println(s"Too many JARs found for ${dependency.module}:${dependency.version}")
        sys.exit(1)
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

}
