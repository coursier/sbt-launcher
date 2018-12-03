package coursier.sbtlauncher

import caseapp.ExtraName

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
)
