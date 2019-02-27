
import sbt._

object SbtLauncherPlugin extends AutoPlugin {

  override def trigger = allRequirements

  object autoImport {
    val `sbt-launcher-setup` = taskKey[Unit]("")
  }

  import autoImport._

  override def projectSettings = Seq(
    `sbt-launcher-setup` := {
      Keys.projectDescriptors.value
        .collect {
          case (k, v) if k.getOrganisation == "org.scala-sbt" && k.getName == "global-plugins" =>
            val scalaVer = Option(k.getExtraAttribute("scalaVersion"))
            val sbtVer = Option(k.getExtraAttribute("sbtVersion"))
            // this file is required by sbt-coursier versions prior to https://github.com/coursier/sbt-coursier/pull/35
            val f = new File(sys.props("sbt.global.base") + s"/plugins/target/resolution-cache/org.scala-sbt/global-plugins${scalaVer.fold("")("/scala_" + _)}${sbtVer.fold("")("/sbt_" + _)}/${k.getRevision}/resolved.xml.xml")
            f.getParentFile.mkdirs()
            println(s"Writing $f")
            v.toIvyFile(f)
        }
    },
    Keys.fullResolvers := Keys.fullResolvers.dependsOn(`sbt-launcher-setup`).value
  )

}