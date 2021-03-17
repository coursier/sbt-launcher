
enablePlugins(ScriptedPlugin)

lazy val check = taskKey[Unit]("")

check := {
  import scala.collection.JavaConverters._
  val f = sbtLauncher.value
  val f0 = new java.util.zip.ZipFile(f)
  val path = "coursier/bootstrap/launcher/Launcher.class"
  val entOpt = Option(f0.getEntry(path))
  if (entOpt.isEmpty) {
    println("Found entries")
    for (e <- f0.entries().asScala)
      println(e.getName)
    println()
    throw new Exception(s"$path not found in sbt launcher $f")
  }
}
