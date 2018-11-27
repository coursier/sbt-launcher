package coursier.sbtlauncher

import java.lang.ProcessBuilder.Redirect
import java.nio.file.{Path, Paths}

import utest._

object ProjectTests extends TestSuite {

  val launcher = {
    val p = Paths.get("target/test-csbt")
    val b = new ProcessBuilder("/bin/bash", "-c", "./generate-csbt.sh -r ivy2Local -f")
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

  def run(
    dir: Path,
    sbtCommands: Seq[String] = Seq("update", "updateClassifiers", "test:compile", "test")
  ) = {
    val p = new ProcessBuilder(launcher.toAbsolutePath.toString +: sbtCommands: _*)
      .directory(dir.toFile)
      .redirectOutput(Redirect.INHERIT)
      .redirectError(Redirect.INHERIT)
      .redirectInput(Redirect.PIPE)
      .start()
    p.getOutputStream.close()
    val retCode = p.waitFor()
    assert(retCode == 0)
  }

  val tests = Tests {

    "sbt 0.13.17" - {
      "sourcecode" - {
        val dir = Paths.get("tests/sourcecode-sbt-0.13.17")
        run(dir)
      }
    }

    "sbt 1.2.3" - {
      "sourcecode" - {
        val dir = Paths.get("tests/sourcecode-sbt-1.2.3")
        run(
          dir,
          sbtCommands = Seq("+update", "+updateClassifiers", "+test:compile", "test")
        )
      }
    }

  }

}
