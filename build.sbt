
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
      "com.github.alexarchambault" %% "case-app" % "2.0.0-M2",
      "org.scala-sbt" % "launcher-interface" % "1.0.0",
      "com.typesafe" % "config" % "1.3.2"
    )
  )
