addSbtPlugin("org.xerial.sbt" % "sbt-pack" % "0.12")
addSbtPlugin("com.geirsson" % "sbt-ci-release" % "1.4.31")

// This one isn't really needed as we're using sbt 1.3.x, but its version is
// used in build.sbt as the default sbt-(lm-)coursier version the launcher
// injects. And having it here allows to get scala-steward updates for it.
addSbtPlugin("io.get-coursier" % "sbt-lm-coursier" % "2.0.0-RC3-6")
