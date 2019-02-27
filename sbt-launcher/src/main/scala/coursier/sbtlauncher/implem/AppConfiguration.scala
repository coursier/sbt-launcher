package coursier.sbtlauncher.implem

import java.io.File

final case class AppConfiguration(
  arguments: Array[String],
  baseDirectory: File,
  provider: xsbti.AppProvider
) extends xsbti.AppConfiguration
