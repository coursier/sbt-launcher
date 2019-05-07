package coursier.sbtlauncher

import java.io.{File, FileFilter}
import java.lang.reflect.InvocationTargetException
import java.nio.file.Paths
import java.util.regex.Pattern

import caseapp._
import com.typesafe.config.ConfigFactory
import coursier.core.Version
import coursier.sbtlauncher.implem.{AppConfiguration, ApplicationID, Launcher}
import coursier.{Dependency, Module, moduleNameString, organizationString}

import scala.annotation.tailrec

object LauncherApp extends CaseApp[LauncherOptions] {

  private val debug: Boolean =
    sys.props.contains("coursier.sbt-launcher.debug") ||
      sys.env.contains("COURSIER_SBT_LAUNCHER_DEBUG")

  private def log(msg: String): Unit =
    if (debug)
      System.err.println(msg)

  private def defaultBase(sbtBinaryVersion: String): String =
    s"${sys.props("user.home")}/.sbt/$sbtBinaryVersion"

  private def sbtCoursierVersionFromPluginsSbt(dir: File, pluginName: String = "sbt-coursier"): Option[(String, File)] = {

    val pattern = (
      Pattern.quote("\"io.get-coursier\"") + "\\s+" +
        Pattern.quote("%") + "\\s+" +
        Pattern.quote("\"" + pluginName + "\"") + "\\s+" +
        Pattern.quote("%") + "\\s+" +
        Pattern.quote("\"") + "([^\"]+)" + Pattern.quote("\"")
    ).r

    def recurse(dir: File): Option[(String, File)] =
      if (dir.isDirectory) {
        val sbtFiles = dir.listFiles(
          new FileFilter {
            def accept(pathname: File) =
              pathname.getName.endsWith(".sbt") && pathname.isFile
          }
        )

        val it = sbtFiles.iterator
          .flatMap(f => scala.io.Source.fromFile(f).getLines().map(_.trim -> f))
          .filter(!_._1.startsWith("/"))
          .filter(_._1.contains("\"io.get-coursier\""))
          .flatMap(s => pattern.findFirstMatchIn(s._1).map(_.group(1) -> s._2))

        if (it.hasNext)
          Some(it.next())
        else
          recurse(new File(dir, "project"))
      } else
        None

    recurse(dir)
  }

  private case class RunParams(
    scalaVersion: String,
    sbtVersion: String,
    sbtBinaryVersion: String,
    addSbtLauncherPlugin: Boolean,
    sbtCoursierVersionOpt: Option[String],
    sbtLmCoursierVersionOpt: Option[String],
    userExtraDeps: Seq[Dependency],
    shortCircuitSbtMain: Boolean,
    useDistinctSbtTestInterfaceLoader: Boolean
  ) {

    def isSbt0x: Boolean =
      sbtVersion.startsWith("0.")

    private def sbtLauncherPluginDepOpt =
      if (addSbtLauncherPlugin)
        Some(
          Dependency(
            Module(
              org"io.get-coursier", name"sbt-launcher-plugin",
              attributes = Map(
                "scalaVersion" -> scalaVersion.split('.').take(2).mkString("."),
                "sbtVersion" -> sbtBinaryVersion
              )
            ),
            Properties.version
          )
        )
      else
        None

    private def sbtLauncherScriptedPluginDepOpt =
      if (sbtBinaryVersion.startsWith("0.") || sbtVersion.startsWith("1.0.") || sbtVersion.startsWith("1.1."))
        None
      else
        Some(
          Dependency(
            Module(
              org"io.get-coursier", name"sbt-launcher-scripted-plugin",
              attributes = Map(
                "scalaVersion" -> scalaVersion.split('.').take(2).mkString("."),
                "sbtVersion" -> sbtBinaryVersion
              )
            ),
            Properties.version
          )
        )

    private def coursierDepOpt: Option[Dependency] =
      sbtCoursierVersionOpt.flatMap { sbtCoursierVersion =>
        if (sbtVersion.nonEmpty)
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
      }

    private def lmCoursierDepOpt: Option[Dependency] =
      sbtLmCoursierVersionOpt.flatMap { sbtLmCoursierVersion =>
        if (sbtVersion.nonEmpty)
          Some(
            Dependency(
              Module(
                org"io.get-coursier", name"sbt-lm-coursier",
                attributes = Map(
                  "scalaVersion" -> scalaVersion.split('.').take(2).mkString("."),
                  "sbtVersion" -> sbtBinaryVersion
                )
              ),
              sbtLmCoursierVersion
            )
          )
        else
          None
      }

    def extraDeps: Seq[Dependency] =
      userExtraDeps ++
        sbtLauncherPluginDepOpt.toSeq ++
        sbtLauncherScriptedPluginDepOpt.toSeq ++
        coursierDepOpt.toSeq ++
        lmCoursierDepOpt.toSeq
  }

  val projectDir = new File(s"${sys.props("user.dir")}")

  val targetDir =
    sys.props.get("coursier.sbt-launcher.dirs.base") match {
      case None =>
        // Putting stuff under "project/target" rather than just "target" so that this doesn't get wiped out
        // when running the clean command from sbt.
        new File(projectDir, "project/target")
      case Some(p) =>
        new File(p)
    }

  val coursierResolutionCache =
    sys.props.get("coursier.sbt-launcher.dirs.resolution-cache") match {
      case None =>
        new File(targetDir, "coursier-resolution-cache")
      case Some(p) =>
        new File(p)
    }

  val scalaJarDir =
    sys.props.get("coursier.sbt-launcher.dirs.scala-jars") match {
      case None =>
        new File(targetDir, "scala-jars")
      case Some(p) =>
        new File(p)
    }

  val sbtComponents =
    sys.props.get("coursier.sbt-launcher.dirs.sbt-components") match {
      case None =>
        new File(targetDir, "sbt-components")
      case Some(p) =>
        new File(p)
    }

  val ivy2 =
    sys.props.get("coursier.sbt-launcher.dirs.ivy2") match {
      case None =>
        new File(targetDir, "ivy2")
      case Some(p) =>
        new File(p)
    }

  @tailrec
  private def doRun(
    appId: xsbti.ApplicationID,
    args: Array[String],
    params: RunParams
  ): Unit = {

    log("Creating launcher")
    val launcher = new Launcher(
      params.scalaVersion,
      Some(coursierResolutionCache),
      scalaJarDir,
      // FIXME Add org & moduleName in this path
      new File(sbtComponents, s"components_scala${params.scalaVersion}${if (params.sbtVersion.isEmpty) "" else "_sbt" + params.sbtVersion}"),
      ivy2,
      log,
      useDistinctSbtTestInterfaceLoader = params.useDistinctSbtTestInterfaceLoader
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
    val appMain =
      if (params.shortCircuitSbtMain) {
        val loader = appProvider.loader()
        val clazz = loader.loadClass("sbt.xMainImpl$")
        val instance = clazz.getField("MODULE$").get(null)
        val runMethod = clazz.getMethod("run", classOf[xsbti.AppConfiguration])
        val f = {
          appConfig: AppConfiguration =>
            try {
              runMethod.invoke(instance, appConfig).asInstanceOf[xsbti.MainResult]
            } catch {
              case e: InvocationTargetException =>
                // ??? xsbti.Main does this
                throw e.getCause
            }
        }
        Left(f)
      } else
        Right(appProvider.newMain())

    val appConfig = AppConfiguration(args, projectDir, appProvider)

    log(s"Running sbt ${params.sbtVersion}")
    val thread = Thread.currentThread()
    val previousLoader = thread.getContextClassLoader
    val result =
      try {
        thread.setContextClassLoader(appProvider.loader())
        Right(appMain.fold(_(appConfig), _.run(appConfig)))
      } catch {
        case r: xsbti.FullReload =>
          Left(r)
      } finally {
        thread.setContextClassLoader(previousLoader)
      }
    log("Done")

    result match {
      case Left(fullReload) =>
        // default sbt launcher also cleans launcher.bootDirectory, but it's unused here
        doRun(
          appId,
          fullReload.arguments(),
          params
        )
      case Right(_: xsbti.Continue) =>
      case Right(e: xsbti.Exit) =>
        sys.exit(e.code())
      case Right(r: xsbti.Reboot) =>
        // xsbti.Reboot also has a baseDirectory method, not sure how it is used in the sbt launcher implementation
        doRun(
          r.app(),
          r.arguments(),
          params.copy(scalaVersion = r.scalaVersion())
        )
    }
  }

  def run(options: LauncherOptions, remainingArgs: RemainingArgs): Unit = {

    if (!sys.props.contains("jna.nosys"))
      sys.props("jna.nosys") = "true"

    val config = options.sbtConfig.orElse {
      SbtConfig.fromProject(Paths.get(sys.props("user.dir")))
        .getOrElse(SbtConfig.fromConfig(ConfigFactory.systemProperties()))
    }

    if (!sys.props.contains("sbt.global.base"))
      sys.props("sbt.global.base") = defaultBase(config.sbtBinaryVersion)

    if (!sys.props.contains("coursier.sbt-launcher.version"))
      sys.props("coursier.sbt-launcher.version") = Properties.version

    if (!sys.props.contains("coursier.sbt-launcher.jar"))
      sys.props("coursier.sbt-launcher.jar") = sys.props("coursier.mainJar")

    val extraDeps = options.extraDependencies(config.scalaVersion) match {
      case Left(errors) =>
        errors.foreach(System.err.println)
        sys.exit(1)
      case Right(deps) => deps
    }

    val foundSbtCoursierVersionOpt =
      if (options.addCoursier) {

        val fromProject = sbtCoursierVersionFromPluginsSbt(new File(sys.props("user.dir") + "/project"))
        val global = sbtCoursierVersionFromPluginsSbt(new File(sys.props("sbt.global.base") + "/plugins"))

        (fromProject.toSeq ++ global.toSeq)
          .sortBy { case (v, _) => coursier.core.Version(v) }
          .lastOption
          .map(_._1)
      } else
        None

    val foundSbtLmCoursierVersionOpt =
      if (!config.sbtVersion.startsWith("0.") && options.addCoursier) {

        val fromProject = sbtCoursierVersionFromPluginsSbt(new File(sys.props("user.dir") + "/project"), "sbt-lm-coursier")
        val global = sbtCoursierVersionFromPluginsSbt(new File(sys.props("sbt.global.base") + "/plugins"), "sbt-lm-coursier")

        (fromProject.toSeq ++ global.toSeq)
          .sortBy { case (v, _) => coursier.core.Version(v) }
          .lastOption
          .map(_._1)
      } else
        None

    val (sbtCoursierVersionOpt, sbtLmCoursierVersionOpt) =
      if (options.addCoursier)
        (foundSbtCoursierVersionOpt, foundSbtLmCoursierVersionOpt) match {
          case (Some(v), _) =>
            (Some(v), None)
          case (None, Some(v)) =>
            (None, Some(v))
          case (None, None) =>
            if (config.sbtVersion.startsWith("0.13."))
              (Some("1.1.0-M7"), None) // last sbt 0.13 compatible version
            else
              // now adding sbt-lm-coursier by default, that adds a coursier-based DependencyResolution in particular
              options.coursierPlugin.getOrElse("sbt-lm-coursier") match {
                case "sbt-coursier" =>
                  (Some(Properties.sbtCoursierDefaultVersion), None)
                case "sbt-lm-coursier" =>
                  (None, Some(Properties.sbtCoursierDefaultVersion))
                case other =>
                  System.err.println(s"Unrecognized coursier plugin: $other")
                  sys.exit(1)
              }
        }
      else
        (None, None)

    // TODO Warn if both sbt-coursier and sbt-lm-coursier are found?

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

    lazy val isAtLeastSbt130M3 =
      config.organization == SbtConfig.defaultOrganization &&
        config.moduleName == SbtConfig.defaultModuleName &&
        config.mainClass == SbtConfig.defaultMainClass &&
        coursier.core.Version(config.version).compare(coursier.core.Version("1.3.0-M3")) >= 0

    val shortCircuitSbtMain = options.shortCircuitSbtMain
      .getOrElse(isAtLeastSbt130M3)
    val useDistinctSbtTestInterfaceLoader = options.useDistinctSbtTestInterfaceLoader
      .getOrElse(isAtLeastSbt130M3)

    doRun(
      appId,
      remainingArgs.all.toArray,
      RunParams(
        config.scalaVersion,
        config.sbtVersion,
        config.sbtBinaryVersion,
        addSbtLauncherPlugin = sbtCoursierVersionOpt.exists(v => Version(v).compare(Version("1.1.0-M12")) < 0),
        sbtCoursierVersionOpt,
        sbtLmCoursierVersionOpt,
        config.dependencies ++ extraDeps,
        shortCircuitSbtMain = shortCircuitSbtMain,
        useDistinctSbtTestInterfaceLoader = useDistinctSbtTestInterfaceLoader
      )
    )
  }

}
