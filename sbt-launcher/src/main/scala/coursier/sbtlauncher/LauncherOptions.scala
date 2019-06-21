package coursier.sbtlauncher

import java.util.Locale

import caseapp.ExtraName
import coursier.Dependency
import coursier.parse.DependencyParser

final case class LauncherOptions(
  @ExtraName("org")
    organization: Option[String] = None,
  name: Option[String] = None,
  scalaVersion: Option[String] = None,
  version: Option[String] = None,
  pluginVersion: Option[String] = None,
  launcherPluginVersion: Option[String] = None,
  launcherScriptedPluginVersion: Option[String] = None,
  mainClass: Option[String] = None,
  mainComponents: List[String] = Nil,
  classpathExtra: List[String] = Nil,
  extra: List[String] = Nil,
  addCoursier: Boolean = LauncherOptions.defaultAddCoursier,
  coursierPlugin: Option[String] = None,
  shortCircuitSbtMain: Option[Boolean] = None,
  useDistinctSbtTestInterfaceLoader: Option[Boolean] = None
) {
  def sbtConfig: SbtConfig =
    SbtConfig(
      organization.getOrElse(""),
      name.getOrElse(""),
      version.getOrElse(""),
      scalaVersion.getOrElse(""),
      mainClass.getOrElse(""),
      // accept those as args?
      Nil,
      Nil
    )
  def extraDependencies(scalaVersion: String): Either[Seq[String], Seq[Dependency]] =
    DependencyParser.moduleVersions(extra, scalaVersion).either.right.map { l =>
      l.map {
        case (mod, ver) =>
          Dependency(mod, ver)
      }
    }
}

object LauncherOptions {
  def defaultAddCoursier: Boolean =
    sys.env.get("COURSIER_SBT_LAUNCHER_ADD_PLUGIN")
      .orElse(sys.props.get("coursier.sbt-launcher.add-plugin"))
      .forall(s => s.toLowerCase(Locale.ROOT) == "true" || s == "1")
}
