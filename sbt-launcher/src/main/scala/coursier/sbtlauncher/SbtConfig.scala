package coursier.sbtlauncher

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

import com.typesafe.config.{Config, ConfigFactory}
import coursier.Dependency
import coursier.parse.DependencyParser

import scala.collection.JavaConverters._

final case class SbtConfig(
  organization: String,
  moduleName: String,
  version: String,
  scalaVersion: String,
  mainClass: String,
  dependencies: Seq[String],
  plugins: Seq[String]
) {
  def orElse(otherOpt: Option[SbtConfig]): SbtConfig =
    otherOpt.fold(this)(orElse)
  def orElse(other: SbtConfig): SbtConfig =
    SbtConfig(
      if (organization.isEmpty) other.organization else organization,
      if (moduleName.isEmpty) other.moduleName else moduleName,
      if (version.isEmpty) other.version else version,
      if (scalaVersion.isEmpty) other.scalaVersion else scalaVersion,
      if (mainClass.isEmpty) other.mainClass else mainClass,
      dependencies ++ other.dependencies, // odd one out
      plugins ++ other.plugins
    )
  lazy val sbtBinaryVersion: String =
    version.split('.').take(2) match {
      case Array("0", v) => s"0.$v"
      case Array(major, _) => s"$major.0"
      case _ =>
        sys.error(s"Malformed sbt version '$version'")
    }

  def fillMissing: SbtConfig = {

    val scalaVersion0 =
      if (scalaVersion.isEmpty) {
        if (version.startsWith("0.13."))
          "2.10.7"
        else if (version.startsWith("1.")) {
          // might be better to adjust that depending on which version the sbt version depends on…
          val v = scala.util.Properties.versionNumberString
          assert(v.startsWith("2.12."))
          v
        } else
          throw new Exception(s"Don't know what Scala version should be used for sbt version '$version'")
      } else
        scalaVersion

    val org =
      if (organization.isEmpty)
        SbtConfig.defaultOrganization
      else
        organization

    val name =
      if (moduleName.isEmpty)
        SbtConfig.defaultModuleName
      else
        moduleName

    val mainClass0 =
      if (mainClass.isEmpty)
        SbtConfig.defaultMainClass
      else
        mainClass

    copy(
      organization = org,
      moduleName = name,
      scalaVersion = scalaVersion0,
      mainClass = mainClass0
    )
  }


  def parsedDependencies(): Seq[Dependency] = {

    val scalaBinaryVersion = scalaVersion.split('.').take(2).mkString(".")
    val sbtBinaryVersion = version.split('.').take(2).mkString(".")

    val pluginDependencies = DependencyParser.moduleVersions(plugins, scalaVersion).either match {
      case Left(errors) =>
        ???
      case Right(pluginsModuleVersions) =>
        pluginsModuleVersions.map {
          case (mod, ver) =>
            Dependency(
              mod.withAttributes(
                mod.attributes ++ Seq(
                  "scalaVersion" -> scalaBinaryVersion,
                  "sbtVersion" -> sbtBinaryVersion
                )
              ),
              ver
            )
        }
    }

    val dependencies0 = DependencyParser.moduleVersions(dependencies, scalaVersion).either match {
      case Left(errors) =>
        ???
      case Right(depsModuleVersions) =>
        depsModuleVersions.map {
          case (mod, ver) =>
            Dependency(mod, ver)
        }
    }

    pluginDependencies ++ dependencies0
  }
}

object SbtConfig {

  def defaultOrganization = "org.scala-sbt"
  def defaultModuleName = "sbt"
  def defaultMainClass = "sbt.xMain"

  def fromConfig(config: Config): SbtConfig = {

    val version =
      if (config.hasPath("sbt.version"))
        config.getString("sbt.version")
      else
        ""

    val scalaVersion =
      if (config.hasPath("scala.version"))
        config.getString("scala.version")
      else
        ""

    val org =
      if (config.hasPath("sbt.organization"))
        config.getString("sbt.organization")
      else
        ""

    val name =
      if (config.hasPath("sbt.module-name"))
        config.getString("sbt.module-name")
      else
        ""

    val mainClass =
      if (config.hasPath("sbt.main-class"))
        config.getString("sbt.main-class")
      else
        ""

    val pluginDependencies =
      if (config.hasPath("plugins"))
        config.getStringList("plugins").asScala
     else
        Nil

    val dependencies =
      if (config.hasPath("dependencies"))
        config.getStringList("dependencies").asScala
      else
        Nil

    SbtConfig(
      org,
      name,
      version,
      scalaVersion,
      mainClass,
      dependencies,
      pluginDependencies
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
