package coursier.sbtlauncher

import java.nio.file.Paths

import utest._

object Sbt1203Tests extends TestSuite {

  import TestHelpers._

  val tests = Tests {

    "sbt 1" - {
      "2.0" - {
        "case-app" - {
          runCaseAppTest("1.2.0")
        }
      }
      "2.1" - {
        "case-app" - {
          runCaseAppTest("1.2.1")
        }
      }
      "2.2" - {
        "case-app" - {
          runCaseAppTest("1.2.2")
        }
      }
      "2.3" - {
        "case-app" - {
          runCaseAppTest("1.2.3")
        }
        "sourcecode" - {
          val dir = Paths.get("tests/sourcecode-sbt-1.2.3")
          run(
            dir,
            "1.2.3",
            sbtCommands = Seq("+update", "+updateClassifiers", "+test:compile", "test")
          )
        }
      }
    }

    "reload" - {
      * - {
        val dir = Paths.get("tests/sourcecode-sbt-1.2.3")
        run(
          dir,
          "1.2.3",
          sbtCommands = Seq("test:compile", "reload", "test")
        )
      }
    }

  }

}
