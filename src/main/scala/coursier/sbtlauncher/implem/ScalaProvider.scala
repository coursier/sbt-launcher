package coursier.sbtlauncher.implem

import java.io.File

final case class ScalaProvider(
  launcher: xsbti.Launcher,
  version: String,
  loaderLibraryOnly: ClassLoader,
  loader: ClassLoader,
  jars: Array[File],
  libraryJar: File,
  compilerJar: File,
  createApp: xsbti.ApplicationID => xsbti.AppProvider
) extends xsbti.ExtendedScalaProvider {
  def app(id: xsbti.ApplicationID): xsbti.AppProvider =
    createApp(id)
}
