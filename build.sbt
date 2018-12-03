
inThisBuild(List(
  organization := "io.get-coursier",
  homepage := Some(url("https://github.com/coursier/coursier")),
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

lazy val `sbt-launcher` = project
  .in(file("."))
  .enablePlugins(PackPlugin)
  .settings(
    scalaVersion := "2.12.7",
    scalacOptions ++= Seq("-feature", "-deprecation"),
    libraryDependencies ++= Seq(
      "io.get-coursier" %% "coursier-cache" % "1.1.0-M7",
      "com.github.alexarchambault" %% "case-app" % "2.0.0-M5",
      "org.scala-sbt" % "launcher-interface" % "1.0.4",
      "com.typesafe" % "config" % "1.3.3",
      "com.lihaoyi" %% "utest" % "0.6.6" % "test"
    ),
    test.in(Test) := test.in(Test).dependsOn(publishLocal).value,
    testFrameworks += new TestFramework("utest.runner.Framework"),
    resourceGenerators.in(Compile) += Def.task {
      import sys.process._

      val dir = classDirectory.in(Compile).value / "coursier" / "sbtlauncher"
      val ver = version.value

      val f = dir / "sbtlauncher.properties"
      dir.mkdirs()

      val p = new java.util.Properties

      p.setProperty("version", ver)
      p.setProperty("commit-hash", Seq("git", "rev-parse", "HEAD").!!.trim)
      p.setProperty("sbt-coursier-default-version", coursierVersion) // FIXME Should be sbtCoursierVersion

      val w = new java.io.FileOutputStream(f)
      p.store(w, "coursier sbt-launcher properties")
      w.close()

      state.value.log.info(s"Wrote $f")

      Seq(f)
    }
  )
