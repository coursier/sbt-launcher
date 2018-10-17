
lazy val `sbt-launcher` = project
  .in(file("."))
  .enablePlugins(PackPlugin)
  .settings(
    organization := "io.get-coursier",
    scalaVersion := "2.12.7",
    scalacOptions ++= Seq("-feature", "-deprecation"),
    libraryDependencies ++= Seq(
      "io.get-coursier" %% "coursier-cache" % "1.1.0-M7",
      "com.github.alexarchambault" %% "case-app" % "2.0.0-M2",
      "org.scala-sbt" % "launcher-interface" % "1.0.0",
      "com.typesafe" % "config" % "1.3.2"
    ),
    Publish.settings
  )
