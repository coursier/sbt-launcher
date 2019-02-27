package coursier.sbtlauncher

import java.nio.file.Paths

import utest._

object SbtPre12Tests extends TestSuite {

  import TestHelpers._

  val tests = Tests {

    "sbt 0.13.17" - {
      "sourcecode" - {
        val dir = Paths.get("tests/sourcecode-sbt-0.13.17")
        run(dir, "0.13.17")
      }
    }

    "sbt 1" - {
      "0.0" - {
        "case-app" - {
          runCaseAppTest("1.0.0")
        }
      }
      "0.1" - {
        "case-app" - {
          runCaseAppTest("1.0.1")
        }
      }
      "0.2" - {
        "case-app" - {
          runCaseAppTest("1.0.2")
        }
      }
      "0.3" - {
        "case-app" - {
          runCaseAppTest("1.0.3")
        }
      }
      "0.4" - {
        "case-app" - {
          runCaseAppTest("1.0.4")
        }
      }
    }

  }

}
