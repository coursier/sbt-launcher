package coursier.sbtlauncher

import utest._

object Sbt12Tests extends TestSuite {

  import TestHelpers._

  val tests = Tests {

    "sbt 1" - {
      "2.4" - {
        "case-app" - {
          runCaseAppTest("1.2.4")
        }
      }
      "2.5" - {
        "case-app" - {
          runCaseAppTest("1.2.5")
        }
      }
      "2.6" - {
        "case-app" - {
          runCaseAppTest("1.2.6")
        }
      }
      "2.7" - {
        "case-app" - {
          runCaseAppTest("1.2.7")
        }

        "case-app global plugins" - {
          runCaseAppTest(
            "1.2.7",
            globalPlugins = Seq(
              """"ch.epfl.scala" % "sbt-bloop" % "1.2.5"""",
              """"org.scalameta" % "sbt-metals" % "0.4.4""""
            )
          )
        }
      }
    }

  }

}
