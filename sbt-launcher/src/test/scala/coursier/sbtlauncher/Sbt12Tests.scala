package coursier.sbtlauncher

import java.nio.file.{Files, Paths}

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

      "2.8" - {
        "reboot" - {
          val dir = Paths.get("tests/sbt-lm-coursier-proj")
          run(
            dir,
            "1.2.8",
            sbtCommands = Seq("stage", "reboot", "check")
          )
        }

        "ivy.home property" - {
          val dir = Paths.get("tests/ivy-local-check")
          val ivyHome = dir.resolve("ivy-home")

          TestHelpers.deleteRecursively(ivyHome)

          run(
            dir,
            "1.2.8",
            sbtCommands = Seq("check", "a/publishLocal"),
            ivyHomeOpt = Some(ivyHome)
          )

          val expected = ivyHome.resolve("local/foo/a_2.12/0.1.0-SNAPSHOT/ivys/ivy.xml")
          assert(Files.isRegularFile(expected))
        }

        "sbt-lm-coursier" - {
          val dir = Paths.get("tests/sbt-lm-coursier-proj")
          run(
            dir,
            "1.2.8",
            sbtCommands = Seq("stage", "check")
          )
        }

        "sbt-lm-coursier via variable" - {
          // In this project, the project/**.sbt files can't be matched to find the plugin or version used, but it adds
          // sbt-lm-coursier nevertheless. Things should still work fine for it with the right Java property.
          val dir = Paths.get("tests/sbt-lm-coursier-proj-variable")
          run(
            dir,
            "1.2.8",
            sbtCommands = Seq("stage", "check"),
            extraOpts = Seq("-Dcoursier.sbt-launcher.add-plugin=false")
          )
        }
      }
    }

  }

}
