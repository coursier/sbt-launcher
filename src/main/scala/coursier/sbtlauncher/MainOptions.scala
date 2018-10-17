package coursier.sbtlauncher

import caseapp.ExtraName

final case class MainOptions(
  @ExtraName("org")
    organization: String = "",
  name: String = "",
  version: String = "",
  scalaVersion: String = "",
  sbtVersion: String = "",
  mainClass: String = "",
  mainComponents: List[String] = Nil,
  classpathExtra: List[String] = Nil,
  extra: List[String] = Nil,
  addCoursier: Boolean = true
)
