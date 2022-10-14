
inThisBuild(List(
  organization := "io.get-coursier",
  homepage := Some(url("https://github.com/coursier/sbt-launcher")),
  licenses := Seq("Apache 2.0" -> url("http://opensource.org/licenses/Apache-2.0")),
  developers := List(
    Developer(
      "alexarchambault",
      "Alexandre Archambault",
      "",
      url("https://github.com/alexarchambault")
    )
  )
))

def scala212 = "2.12.12"
def scala210 = "2.10.7"

lazy val `sbt-launcher-plugin` = project
  .settings(
    sbtPlugin := true,
    scalaVersion := scala212,
    crossScalaVersions := Seq(scala212, scala210),
    (pluginCrossBuild / sbtVersion) := {
      scalaBinaryVersion.value match {
        case "2.10" => "0.13.8"
        case "2.12" => "1.0.1"
        case _ => (pluginCrossBuild / sbtVersion).value
      }
    }
  )

lazy val `sbt-launcher-scripted-plugin` = project
  .settings(
    sbtPlugin := true,
    scalaVersion := scala212,
    crossScalaVersions := Seq(scala212),
    (pluginCrossBuild / sbtVersion) := {
      scalaBinaryVersion.value match {
        case "2.12" => "1.2.0"
        case _ => (pluginCrossBuild / sbtVersion).value
      }
    }
  )

lazy val `sbt-launcher` = project
  .enablePlugins(PackPlugin)
  .settings(
    scalaVersion := scala212,
    crossScalaVersions := Seq(scala212),
    scalacOptions ++= Seq("-feature", "-deprecation"),
    libraryDependencies ++= Seq(
      "io.get-coursier" %% "coursier" % "2.0.16",
      "com.github.alexarchambault" %% "case-app" % "2.0.4",
      "org.scala-sbt" % "launcher-interface" % "1.2.0",
      "com.typesafe" % "config" % "1.4.1",
      "com.lihaoyi" %% "utest" % "0.7.7" % "test"
    ),
    (Compile / mainClass) := Some("coursier.sbtlauncher.MainApp"),
    (Test / test) := (Test / test).dependsOn(publishLocal).value,
    testFrameworks += new TestFramework("utest.runner.Framework"),
    (Compile / resourceGenerators) += Def.task {
      import sys.process._

      val dir = (Compile / classDirectory).value / "coursier" / "sbtlauncher"
      val ver = version.value

      val f = dir / "sbtlauncher.properties"
      dir.mkdirs()

      val p = new java.util.Properties

      p.setProperty("version", ver)
      p.setProperty("commit-hash", Seq("git", "rev-parse", "HEAD").!!.trim)
      p.setProperty("sbt-coursier-default-version", sbtCoursierVersion)

      val w = new java.io.FileOutputStream(f)
      p.store(w, "coursier sbt-launcher properties")
      w.close()

      state.value.log.info(s"Wrote $f")

      Seq(f)
    }
  )

lazy val `coursier-sbt-launcher` = project
  .in(file("."))
  .aggregate(
    `sbt-launcher`,
    `sbt-launcher-plugin`,
    `sbt-launcher-scripted-plugin`
  )
