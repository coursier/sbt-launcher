package coursier.sbtlauncher

import caseapp.ExtraName
import coursier.Dependency

final case class MainOptions(
  @ExtraName("org")
    organization: Option[String] = None,
  name: Option[String] = None,
  version: Option[String] = None,
  scalaVersion: Option[String] = None,
  sbtVersion: Option[String] = None,
  mainClass: Option[String] = None,
  mainComponents: List[String] = Nil,
  classpathExtra: List[String] = Nil,
  extra: List[String] = Nil,
  addCoursier: Boolean = true
) {
  def sbtConfig: SbtConfig =
    SbtConfig(
      organization.getOrElse(""),
      name.getOrElse(""),
      version.getOrElse(""),
      sbtVersion.getOrElse(""),
      scalaVersion.getOrElse(""),
      mainClass.getOrElse(""),
      Nil
    )
  def extraDependencies(scalaVersion: String): Either[Seq[String], Seq[Dependency]] = {

    val (extraParseErrors, extraModuleVersions) =
      coursier.util.Parse.moduleVersions(extra, scalaVersion)

    if (extraParseErrors.nonEmpty)
      Left(extraParseErrors)
    else
      Right(
        extraModuleVersions.map {
          case (mod, ver) =>
            Dependency(mod, ver)
        }
      )
  }
}
