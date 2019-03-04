package coursier.sbtlauncher

import java.nio.file.Paths

import utest._

object ScriptedTests extends TestSuite {

  import TestHelpers._

  val tests = Tests {

    'check - {
      val dir = Paths.get("tests/scripted-check")
      run(
        dir,
        "1.2.8",
        sbtCommands = Seq("check")
      )
    }

    'dynver - {
      "sbt 1.1.6" - {
        // scripted downloads the standard sbt launcher with sbt < 1.2.0
        // just checking that things still work here
        val dir = Paths.get("tests/sbt-dynver-sbt-1.1.6")
        run(
          dir,
          "1.1.6",
          sbtCommands = Seq("scripted")
        )
      }

      "sbt 1.2.8" - {
        val dir = Paths.get("tests/sbt-dynver-sbt-1.2.8")
        run(
          dir,
          "1.2.8",
          sbtCommands = Seq("scripted")
        )
      }
    }

  }

}
