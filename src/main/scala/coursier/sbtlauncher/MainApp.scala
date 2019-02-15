package coursier.sbtlauncher

import java.io.File
import java.nio.file.Paths

import caseapp._
import coursier.sbtlauncher.implem.{AppConfiguration, ApplicationID, Launcher}
import coursier.{Dependency, Module, moduleNameString, organizationString}

import scala.annotation.tailrec

object MainApp extends CaseApp[MainOptions] {

  private val debug: Boolean =
    sys.props.contains("coursier.sbt-launcher.debug") ||
      sys.env.contains("COURSIER_SBT_LAUNCHER_DEBUG")

  private def log(msg: String): Unit =
    if (debug)
      System.err.println(msg)

  private def defaultBase(sbtBinaryVersion: String): String =
    s"${sys.props("user.home")}/.csbt/$sbtBinaryVersion"

  private case class RunParams(
    scalaVersion: String,
    sbtVersion: String,
    sbtBinaryVersion: String,
    sbtCoursierVersion: String,
    addCoursier: Boolean,
    userExtraDeps: Seq[Dependency]
  ) {

    def isSbt0x: Boolean =
      sbtVersion.startsWith("0.")

    private def coursierDepOpt: Option[Dependency] =
      if (addCoursier && sbtVersion.nonEmpty)
        Some(
          Dependency(
            Module(
              org"io.get-coursier", name"sbt-coursier",
              attributes = Map(
                "scalaVersion" -> scalaVersion.split('.').take(2).mkString("."),
                "sbtVersion" -> sbtBinaryVersion
              )
            ),
            sbtCoursierVersion
          )
        )
      else
        None

    def extraDeps: Seq[Dependency] =
      coursierDepOpt.toSeq ++ userExtraDeps
  }

  @tailrec
  private def doRun(
    appId: xsbti.ApplicationID,
    args: Array[String],
    params: RunParams
  ): Unit = {

    val projectDir = new File(s"${sys.props("user.dir")}")
    // Putting stuff under "project/target" rather than just "target" so that this doesn't get wiped out
    // when running the clean command from sbt.
    val targetDir = new File(projectDir, "project/target")

    log("Creating launcher")
    val launcher = new Launcher(
      params.scalaVersion,
      Some(new File(targetDir, "coursier-resolution-cache")),
      new File(targetDir, "scala-jars"),
      // FIXME Add org & moduleName in this path
      new File(targetDir, s"sbt-components/components_scala${params.scalaVersion}${if (params.sbtVersion.isEmpty) "" else "_sbt" + params.sbtVersion}"),
      new File(targetDir, "ivy2"),
      log
    )

    log("Registering scala components")
    launcher.registerScalaComponents()
    if (params.isSbt0x) {
      log("Registering sbt interface components")
      launcher.registerSbtInterfaceComponents(params.sbtVersion)
    }

    log("Getting app provider")
    val appProvider = launcher.app(appId, params.extraDeps: _*)

    log("Creating main")
    val appMain = appProvider.newMain()
    val appConfig = AppConfiguration(args, projectDir, appProvider)

    log(s"Running sbt ${params.sbtVersion}")
    val thread = Thread.currentThread()
    val previousLoader = thread.getContextClassLoader
    val result =
      try {
        thread.setContextClassLoader(appProvider.loader())
        appMain.run(appConfig)
      } finally {
        thread.setContextClassLoader(previousLoader)
      }
    log("Done")

    result match {
      case _: xsbti.Continue =>
      case e: xsbti.Exit =>
        sys.exit(e.code())
      case r: xsbti.Reboot =>
        // xsbti.Reboot also has a baseDirectory method, not sure how it is used in the sbt launcher implementation
        doRun(
          r.app(),
          r.arguments(),
          params.copy(scalaVersion = r.scalaVersion())
        )
    }
  }

  def run(options: MainOptions, remainingArgs: RemainingArgs): Unit = {

    val config =
      SbtConfig.fromProject(Paths.get(sys.props("user.dir")))
        .fold(options.sbtConfig)(options.sbtConfig orElse _)

    sys.props("sbt.global.base") = sys.props.getOrElse(
      "csbt.global.base",
      defaultBase(config.sbtBinaryVersion)
    )

    val extraDeps = options.extraDependencies(config.scalaVersion) match {
      case Left(errors) =>
        errors.foreach(System.err.println)
        sys.exit(1)
      case Right(deps) => deps
    }

    val sbtCoursierVersion =
      if (config.sbtVersion.startsWith("0.13."))
        "1.1.0-M7" // last sbt 0.13 compatible version
      else
        Properties.sbtCoursierDefaultVersion

    val appId = ApplicationID(
      config.organization,
      config.moduleName,
      config.version,
      config.mainClass,
      options.mainComponents.toArray,
      crossVersioned = false,
      xsbti.CrossValue.Disabled,
      options.classpathExtra.map(new File(_)).toArray
    )

    doRun(
      appId,
      remainingArgs.all.toArray,
      RunParams(
        config.scalaVersion,
        config.sbtVersion,
        config.sbtBinaryVersion,
        sbtCoursierVersion,
        options.addCoursier,
        config.dependencies ++ extraDeps
      )
    )
  }

}
