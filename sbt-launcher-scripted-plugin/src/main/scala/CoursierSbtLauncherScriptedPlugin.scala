
import sbt._
import sbt.Keys.libraryDependencies

object CoursierSbtLauncherScriptedPlugin extends AutoPlugin {

  override def trigger = allRequirements

  override def requires = ScriptedPlugin

  import ScriptedPlugin.autoImport._

  override def projectSettings = Seq(
    libraryDependencies := libraryDependencies.value.filter { m =>
      m.organization != "org.scala-sbt" ||
        m.name != "sbt-launch" ||
        !m.configurations.contains(ScriptedLaunchConf.name)
    },
    sbtLauncher := {
      sys.props.get("coursier.sbt-launcher.jar") match {
        case None =>
          sys.error("coursier.sbt-launcher.jar not set")
        case Some(v) =>
          new File(v)
      }
    },
    scriptedLaunchOpts ++= Seq(
      "-Dsbt.version=" + scriptedSbt.value,
      // contains compiled compiler interfaces in particular, that we shared across tests
      "-Dcoursier.sbt-launcher.dirs.base=" + (Keys.target.value / "scripted-sbt-launcher")
    ),
    scriptedLaunchOpts ++= sys.props
      .filterKeys(_.startsWith("coursier.sbt-launcher."))
      .toSeq
      .map {
        case (k, v) => s"-D$k=$v"
      }
  )

}
