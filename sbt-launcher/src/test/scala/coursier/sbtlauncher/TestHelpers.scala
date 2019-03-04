package coursier.sbtlauncher

import java.lang.ProcessBuilder.Redirect
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}

import utest._

import scala.collection.JavaConverters._

object TestHelpers {

  val launcher = {
    val p = Paths.get("target/test-csbt")
    val b = new ProcessBuilder("/bin/bash", "-c", "./scripts/generate-csbt.sh -r ivy2Local -f")
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
    p
  }

  private def deleteRecursively(p: Path): Unit = {
    if (Files.isDirectory(p))
      Files.list(p)
        .iterator()
        .asScala
        .foreach(deleteRecursively)
    Files.deleteIfExists(p)
  }

  def run(
    dir: Path,
    sbtVersion: String,
    sbtCommands: Seq[String] = Seq("update", "updateClassifiers", "test:compile", "test"),
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

    val cmd = Seq(
      launcher.toAbsolutePath.toString,
      "-J-Dsbt.global.base=" + sbtDir.toAbsolutePath,
      "-J-Dsbt.ivy.home=" + ivyHome.toAbsolutePath
    ) ++ extraArgs ++ sbtCommands
    Console.err.println("Running")
    Console.err.println(s"  ${cmd.mkString(" ")}")
    Console.err.println(s"in directory $dir")
    val b = new ProcessBuilder(cmd: _*)
      .directory(dir.toFile)
      .redirectOutput(Redirect.INHERIT)
      .redirectError(Redirect.INHERIT)
      .redirectInput(Redirect.PIPE)
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
