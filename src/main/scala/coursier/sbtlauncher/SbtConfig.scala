package coursier.sbtlauncher

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

import com.typesafe.config.{Config, ConfigFactory}
import coursier.Dependency
import coursier.util.Parse

import scala.collection.JavaConverters._

final case class SbtConfig(
  organization: String,
  moduleName: String,
  version: String,
  sbtVersion: String,
  scalaVersion: String,
  mainClass: String,
  dependencies: Seq[Dependency]
) {
  def orElse(other: SbtConfig): SbtConfig =
    SbtConfig(
      if (organization.isEmpty) other.organization else organization,
      if (moduleName.isEmpty) other.moduleName else moduleName,
      if (version.isEmpty) other.version else version,
      if (sbtVersion.isEmpty) other.sbtVersion else sbtVersion,
      if (scalaVersion.isEmpty) other.scalaVersion else scalaVersion,
      if (mainClass.isEmpty) other.mainClass else mainClass,
      dependencies ++ other.dependencies // odd one out
    )
  lazy val sbtBinaryVersion: String =
    sbtVersion.split('.').take(2) match {
      case Array("0", v) => s"0.$v"
      case Array(major, _) => s"$major.0"
    }
}

object SbtConfig {

  def defaultOrganization = "org.scala-sbt"
  def defaultModuleName = "sbt"
  def defaultMainClass = "sbt.xMain"

  def fromConfig(config: Config): SbtConfig = {

    val version = config.getString("sbt.version")

    val scalaVersion =
      if (config.hasPath("scala.version"))
        config.getString("scala.version")
      else if (version.startsWith("0.13."))
        "2.10.7"
      else if (version.startsWith("1."))
        "2.12.7"
      else
        throw new Exception(s"Don't know what Scala version should be used for sbt version '$version'")

    val org =
      if (config.hasPath("sbt.organization"))
        config.getString("sbt.organization")
      else
        defaultOrganization

    val name =
      if (config.hasPath("sbt.module-name"))
        config.getString("sbt.module-name")
      else
        defaultModuleName

    val mainClass =
      if (config.hasPath("sbt.main-class"))
        config.getString("sbt.main-class")
      else
        defaultMainClass

    val scalaBinaryVersion = scalaVersion.split('.').take(2).mkString(".")
    val sbtBinaryVersion = version.split('.').take(2).mkString(".")

    val rawPlugins =
      if (config.hasPath("plugins"))
        config.getStringList("plugins").asScala
     else
        Nil

    val (pluginErrors, pluginsModuleVersions) = Parse.moduleVersions(rawPlugins, scalaVersion)

    if (pluginErrors.nonEmpty) {
      ???
    }

    val pluginDependencies =
      pluginsModuleVersions.map {
        case (mod, ver) =>
          Dependency(
            mod.copy(
              attributes = mod.attributes ++ Seq(
                "scalaVersion" -> scalaBinaryVersion,
                "sbtVersion" -> sbtBinaryVersion
              )
            ),
            ver
          )
      }

    val rawDeps =
      if (config.hasPath("dependencies"))
        config.getStringList("dependencies").asScala
      else
        Nil

    val (depsErrors, depsModuleVersions) = Parse.moduleVersions(rawDeps, scalaVersion)

    if (depsErrors.nonEmpty) {
      ???
    }

    val dependencies =
      depsModuleVersions.map {
        case (mod, ver) =>
          Dependency(mod, ver)
      }

    SbtConfig(
      org,
      name,
      version,
      version,
      scalaVersion,
      mainClass,
      pluginDependencies ++ dependencies
    )
  }

  // may throw via typesafe-config…
  def fromProject(dir: Path): Option[SbtConfig] = {

    val propFileOpt = Seq("sbt.properties", "project/build.properties")
      .map(dir.resolve)
      .find(Files.isRegularFile(_))

    propFileOpt.map { propFile =>
      val b = Files.readAllBytes(propFile)
      val s = new String(b, StandardCharsets.UTF_8)

      // can't get ConfigFactory.parseFile to work fine here
      val conf = ConfigFactory.parseString(s)
        .withFallback(ConfigFactory.defaultReference(Thread.currentThread().getContextClassLoader))
        .resolve()

      SbtConfig.fromConfig(conf)
    }
  }
}
