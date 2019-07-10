package coursier.sbtlauncher

import java.io.File

import scala.collection.mutable.ListBuffer

object MainApp {
  def main(args: Array[String]): Unit = {

    val launcherArgs = new ListBuffer[String]
    val sbtArgs = new ListBuffer[String]
    val residualArgs = new ListBuffer[String]

    var sbtOptFile = Option.empty[File]

    // missing:
    // -jvm-debug
    // -batch
    // …

    def processArgs(it: Iterator[String]): Unit =
      while (it.hasNext)
        it.next() match {
          case arg if arg.startsWith("-C") =>
            launcherArgs += arg.stripPrefix("-C")
          case "-d" =>
            sbtArgs += "--debug"
          case "-w" =>
            sbtArgs += "--warn"
          case "-q" =>
            sbtArgs += "--error"
          case "-trace" =>
            val traceLevel = it.next().toInt // TODO handle errors gracefully
            sbtArgs += s"""set traceLevel in ThisBuild := "$traceLevel""""
          case "-debug-inc" =>
            sys.props("xsbt.inc.debug") = "true"
          case "-no-colors" =>
            sys.props("sbt.log.noformat") = "true"
          case "-sbt-dir" =>
            sys.props("sbt.global.base") = it.next()
          case "-sbt-boot" =>
            sys.props("sbt.boot.directory") = it.next()
          case "-ivy" =>
            sys.props("sbt.ivy.home") = it.next()
          case "-no-share" =>
            sys.props("sbt.global.base") = "project/.sbtboot"
            sys.props("sbt.boot.directory") = "project/.boot"
            sys.props("sbt.ivy.home") = "project/.ivy"
          case "-offline" =>
            sbtArgs += "set offline in Global := true"
          case "-prompt" =>
            val prompt = it.next()
            sbtArgs += s"""set shellPrompt in ThisBuild := (s => { val e = Project.extract(s) ; $prompt }) """
          case "-script" =>
            // will this really get picked with our launcher?
            sys.props("sbt.main.class") = "sbt.ScriptMain"
            residualArgs.prepend(it.next())
          case "-sbt-version" =>
            launcherArgs += s"--version=${it.next()}"
          case "-sbt-opts" =>
            sbtOptFile = Some(new File(it.next()))
          case arg if arg.startsWith("-B") =>
            sbtArgs += arg.stripPrefix("-B")
          case "--" =>
            residualArgs ++= it
          case other =>
            residualArgs += other
        }

    processArgs(args.iterator)

    LauncherApp.main((launcherArgs ++ Seq("--") ++ sbtArgs ++ residualArgs).toArray)
  }
}
