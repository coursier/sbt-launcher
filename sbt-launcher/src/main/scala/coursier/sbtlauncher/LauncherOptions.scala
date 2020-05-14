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
  pluginVersion: Option[String] = LauncherOptions.defaultCoursierPluginVersion,
  launcherPluginVersion: Option[String] = None,
  launcherScriptedPluginVersion: Option[String] = None,
  mainClass: Option[String] = None,
  mainComponents: List[String] = Nil,
  classpathExtra: List[String] = Nil,
  extra: List[String] = Nil,
  addCoursier: Boolean = LauncherOptions.defaultAddCoursier,
  coursierPlugin: Option[String] = LauncherOptions.defaultCoursierPlugin,
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
    Option(System.getenv("COURSIER_SBT_LAUNCHER_ADD_PLUGIN"))
      .orElse(sys.props.get("coursier.sbt-launcher.add-plugin"))
      .map(_.trim)
      .filter(_.nonEmpty)
      .forall(s => s.toLowerCase(Locale.ROOT) == "true" || s == "1")
  def defaultCoursierPlugin: Option[String] =
    Option(System.getenv("COURSIER_SBT_LAUNCHER_PLUGIN"))
      .orElse(sys.props.get("coursier.sbt-launcher.plugin"))
      .map(_.trim)
      .filter(_.nonEmpty)
  def defaultCoursierPluginVersion: Option[String] =
    Option(System.getenv("COURSIER_SBT_LAUNCHER_PLUGIN_VERSION"))
      .orElse(Option(System.getProperty("coursier.sbt-launcher.plugin-version")))
      .map(_.trim)
      .filter(_.nonEmpty)
}
