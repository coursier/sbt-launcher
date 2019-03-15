package coursier.sbtlauncher

import java.util.Locale

import caseapp.core.RemainingArgs

object MainApp {
  def main(args: Array[String]): Unit = {
    val fromEnv = sys.env.get("COURSIER_SBT_LAUNCHER_PARSE_ARGS")
      .map(s => s == "1" || s.toLowerCase(Locale.ROOT) == "true")
    def fromProps = sys.props.get("coursier.sbt-launcher.parse-args")
      .map(s => s == "1" || s.toLowerCase(Locale.ROOT) == "true")
    val parseArgs = fromEnv.orElse(fromProps).getOrElse(false)
    if (parseArgs)
      LauncherApp.main(args)
    else
      LauncherApp.run(
        LauncherOptions(),
        RemainingArgs(Nil, args)
      )
  }
}
