
lazy val `sbt-launcher` = project
  .in(file("."))
  .enablePlugins(PackPlugin)
  .settings(
    organization := "io.get-coursier",
    scalacOptions ++= Seq("-feature", "-deprecation"),
    libraryDependencies ++= Seq(
      "io.get-coursier" %% "coursier-cache" % "1.0.0",
      "com.github.alexarchambault" %% "case-app" % "1.2.0",
      "org.scala-sbt" % "launcher-interface" % "1.0.0",
      "com.typesafe" % "config" % "1.3.2"
    ),
    Publish.settings
  )
