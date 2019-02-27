package coursier.sbtlauncher

import java.util.{Properties => JProperties}

object Properties {

  private lazy val props = {
    val p = new JProperties
    try {
      p.load(
        getClass
          .getClassLoader
          .getResourceAsStream("coursier/sbtlauncher/sbtlauncher.properties")
      )
    }
    catch  {
      case _: NullPointerException =>
    }
    p
  }

  lazy val version = props.getProperty("version")
  lazy val sbtCoursierDefaultVersion = props.getProperty("sbt-coursier-default-version")
  lazy val commitHash = props.getProperty("commit-hash")

}
