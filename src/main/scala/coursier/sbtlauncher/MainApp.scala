package coursier.sbtlauncher

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files

import caseapp._
import com.typesafe.config.ConfigFactory
import coursier.{Dependency, Module, moduleNameString, organizationString}

import scala.annotation.tailrec

object MainApp extends CaseApp[MainOptions] {

  private val debug = sys.props.contains("coursier.sbt-launcher.debug") || sys.env.contains("COURSIER_SBT_LAUNCHER_DEBUG")

  private def log(msg: String): Unit =
    if (debug)
      Console.err.println(msg)

  private def defaultBase(sbtBinaryVersion: String): String =
    s"${sys.props("user.home")}/.csbt/$sbtBinaryVersion"

  def run(options: MainOptions, remainingArgs: RemainingArgs): Unit = {

    val sbtPropFile = new File(sys.props("user.dir") + "/sbt.properties")
    val buildPropFile = new File(sys.props("user.dir") + "/project/build.properties")

    val propFileOpt = Some(sbtPropFile).filter(_.exists())
      .orElse(Some(buildPropFile).filter(_.exists()))

    val (org0, name0, ver0, scalaVer0, extraDeps0, mainClass0, sbtVersion0) =
      propFileOpt match {
        case Some(propFile) =>
          log(s"Parsing $propFile")

          // can't get ConfigFactory.parseFile to work fine here
          val conf = ConfigFactory.parseString(new String(Files.readAllBytes(propFile.toPath), StandardCharsets.UTF_8))
            .withFallback(ConfigFactory.defaultReference(Thread.currentThread().getContextClassLoader))
            .resolve()
          val sbtConfig = SbtConfig.fromConfig(conf)

          (sbtConfig.organization, sbtConfig.moduleName, sbtConfig.version, sbtConfig.scalaVersion, sbtConfig.dependencies, sbtConfig.mainClass, sbtConfig.version)
        case None =>
          require(options.scalaVersion.nonEmpty, "No scala version specified")
          (
            options.organization,
            options.name,
            options.version,
            options.scalaVersion,
            Nil,
            options.mainClass,
            options.sbtVersion
          )
      }

    val sbtBinaryVersion = sbtVersion0.split('.').take(2) match {
      case Array("0", v) => s"0.$v"
      case Array(major, _) => s"$major.0"
    }

    sys.props("sbt.global.base") = sys.props.getOrElse(
      "csbt.global.base",
      defaultBase(sbtBinaryVersion)
    )

    val (extraParseErrors, extraModuleVersions) =
      coursier.util.Parse.moduleVersions(options.extra, options.scalaVersion)

    if (extraParseErrors.nonEmpty) {
      ???
    }

    val extraDeps = extraModuleVersions.map {
      case (mod, ver) =>
        Dependency(mod, ver)
    }

    val sbtCoursierVersion =
      if (sbtVersion0.startsWith("0.13."))
        "1.1.0-M7" // last sbt 0.13 compatible version
      else
        Properties.sbtCoursierDefaultVersion

    @tailrec
    def run(
      appId: xsbti.ApplicationID,
      args: Array[String],
      scalaVersion: String
    ): Unit = {

      val coursierDeps =
        if (options.addCoursier && sbtVersion0.nonEmpty)
          Seq(
            Dependency(
              Module(
                org"io.get-coursier",
                name"sbt-coursier",
                attributes = Map(
                  "scalaVersion" -> scalaVersion.split('.').take(2).mkString("."),
                  "sbtVersion" -> sbtBinaryVersion
                )
              ),
              sbtCoursierVersion
            )
          )
        else
          Nil

      log("Creating launcher")

      // Putting stuff under "project/target" rather than just "target" so that this doesn't get wiped out
      // when running the clean command from sbt.
      val launcher = new Launcher(
        scalaVersion,
        new File(s"${sys.props("user.dir")}/project/target/scala-jars"),
        // FIXME Add org & moduleName in this path
        new File(s"${sys.props("user.dir")}/project/target/sbt-components/components_scala$scalaVersion${if (sbtVersion0.isEmpty) "" else "_sbt" + sbtVersion0}"),
        new File(s"${sys.props("user.dir")}/project/target/ivy2")
      )

      log("Registering scala components")

      launcher.registerScalaComponents()

      if (sbtVersion0.nonEmpty) {
        log("Registering sbt interface components")
        launcher.registerSbtInterfaceComponents(sbtVersion0)
      }

      log("Getting app provider")

      val appProvider = launcher.app(appId, extraDeps0 ++ extraDeps ++ coursierDeps: _*)

      log("Creating main")

      val appMain = appProvider.newMain()

      val appConfig = AppConfiguration(
        args,
        new File(sys.props("user.dir")),
        appProvider
      )

      Console.err.println(s"Running sbt $sbtVersion0")

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
          run(r.app(), r.arguments(), r.scalaVersion())
      }
    }

    val appId = ApplicationID(
      org0,
      name0,
      ver0,
      mainClass0,
      options.mainComponents.toArray,
      crossVersioned = false,
      xsbti.CrossValue.Disabled,
      options.classpathExtra.map(new File(_)).toArray
    )

    run(appId, remainingArgs.all.toArray, scalaVer0)
  }

}
