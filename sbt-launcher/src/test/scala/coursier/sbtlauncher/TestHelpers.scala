package coursier.sbtlauncher

import java.lang.ProcessBuilder.Redirect
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}

import utest._

import scala.collection.JavaConverters._

object TestHelpers {

  lazy val isWindows = sys.props("os.name").toLowerCase(java.util.Locale.ROOT).contains("windows")

  lazy val launcher = {
    val p = Paths.get("target/test-csbt")
    val bashPath = if (isWindows) "bash" else "/bin/bash"
    val b = new ProcessBuilder(bashPath, "-c", "./scripts/generate-csbt.sh -r ivy2Local -f")
      .redirectOutput(Redirect.INHERIT)
      .redirectError(Redirect.INHERIT)
      .redirectInput(Redirect.PIPE)
    val env = b.environment()
    env.put("OUTPUT", p.toAbsolutePath.toString)
    env.put("VERSION", Properties.version)
    Console.err.println(s"Generating launcher $p")
    val proc = b.start()
    proc.getOutputStream.close()
    val retCode = proc.waitFor()
    assert(retCode == 0)
    Console.err.println(s"Generated launcher $p")
    if (isWindows) p.getParent.resolve(s"${p.getFileName}.bat") else p
  }

  private def deleteRecursively(p: Path): Unit = {
    if (Files.isDirectory(p)) {
      // Circumventing DirectoryNotEmptyException here, see https://stackoverflow.com/a/33014128/3714539
      var stream: java.util.stream.Stream[Path] = null
      try {
        stream = Files.list(p)
        stream
          .iterator()
          .asScala
          .foreach(deleteRecursively)
      } finally {
        if (stream != null)
          stream.close()
      }
    }
    Files.deleteIfExists(p)
  }

  def run(
    dir: Path,
    sbtVersion: String,
    sbtCommands: Seq[String] = Seq("update", "updateClassifiers", "test:compile", "test"),
    extraJavaOpts: Seq[String] = Nil,
    forceSbtVersion: Boolean = false,
    globalPlugins: Seq[String] = Nil
  ): Unit = {

    val propFile = dir.resolve("project/build.properties")

    val extraArgs =
      if (forceSbtVersion)
        Seq("--sbt-version", sbtVersion)
      else
        Nil

    if (!forceSbtVersion) {

      val actualSbtVersion = {
        val p = new java.util.Properties
        p.load(Files.newInputStream(propFile))
        Option(p.getProperty("sbt.version")).getOrElse {
          sys.error(s"No sbt version found in ${propFile.toAbsolutePath.normalize()}")
        }
      }

      assert(actualSbtVersion == sbtVersion)
    }

    val sbtDir = dir.resolve("sbt-global-base")
    val ivyHome = dir.resolve("ivy-home")
    deleteRecursively(sbtDir)
    deleteRecursively(ivyHome)

    Files.createDirectories(sbtDir)
    Files.createDirectories(ivyHome)

    if (globalPlugins.nonEmpty) {
      val pluginsSbt = sbtDir.resolve("plugins/plugins.sbt")
      Files.createDirectories(pluginsSbt.getParent)
      Files.write(
        pluginsSbt,
        globalPlugins
          .map(p => s"addSbtPlugin($p)\n")
          .mkString
          .getBytes(StandardCharsets.UTF_8)
      )
    }

    val (cmd, env) =
      if (isWindows) {
        val cmd = Seq(
          launcher.toAbsolutePath.toString
        ) ++ extraArgs ++ Seq("--") ++ sbtCommands

        val javaOpts = Seq(
          s"-Dsbt.global.base=${sbtDir.toAbsolutePath}",
          s"-Dsbt.ivy.home=${ivyHome.toAbsolutePath.toUri.getPath}",
          "-Dcoursier.sbt-launcher.parse-args=true"
        ) ++ extraJavaOpts

        (cmd, Seq("JAVA_OPTS" -> javaOpts.mkString(" ")))
      } else {
        val cmd = Seq(
          launcher.toAbsolutePath.toString,
          "-J-Dsbt.global.base=" + sbtDir.toAbsolutePath,
          "-J-Dsbt.ivy.home=" + ivyHome.toAbsolutePath,
          "-J-Dcoursier.sbt-launcher.parse-args=true"
        ) ++ extraJavaOpts.map("-J" + _) ++ extraArgs ++ Seq("--") ++ sbtCommands

        (cmd, Nil)
      }
    Console.err.println("Running")
    Console.err.println(s"  ${cmd.mkString(" ")}")
    Console.err.println(s"in directory $dir")
    val b = new ProcessBuilder(cmd: _*)
      .directory(dir.toFile)
      .redirectOutput(Redirect.INHERIT)
      .redirectError(Redirect.INHERIT)
      .redirectInput(Redirect.PIPE)
    val envMap = b.environment()
    for ((k, v) <- env)
      envMap.put(k, v)
    try {
      val p = b.start()
      p.getOutputStream.close()
      val retCode = p.waitFor()
      assert(retCode == 0)
    } finally {
      deleteRecursively(sbtDir)
      deleteRecursively(ivyHome)
    }
  }

  def runCaseAppTest(
    sbtVersion: String,
    globalPlugins: Seq[String] = Nil
  ): Unit =
    run(
      Paths.get(s"tests/case-app-sbt-$sbtVersion" + (if (globalPlugins.isEmpty) "" else "-global-plugins")),
      sbtVersion,
      sbtCommands = Seq("update", "updateClassifiers", "test:compile", "testsJVM/test"),
      forceSbtVersion = true,
      globalPlugins = globalPlugins
    )

}
