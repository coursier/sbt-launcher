package coursier.sbtlauncher

import java.io.File
import java.net.{URL, URLClassLoader}
import java.nio.file.{Files, StandardCopyOption}
import java.util.concurrent.ConcurrentHashMap

import coursier.Cache.Logger
import coursier._
import coursier.core.{Classifier, Extension, Type}
import coursier.ivy.IvyRepository
import coursier.maven.MavenAttributes
import coursier.util.Task

import scala.annotation.tailrec
import scala.language.reflectiveCalls

class Launcher(
  scalaVersion: String,
  resolutionCacheDirOpt: Option[File],
  scalaJarCache: File,
  componentsCache: File,
  val ivyHome: File,
  log: String => Unit
) extends xsbti.Launcher {

  import Launcher._

  private def noVersionScalaJar(scalaOrg: Organization, scalaVersion: String, jar: File): File = {

    val dir = new File(scalaJarCache, s"${scalaOrg.value}/$scalaVersion")
    dir.mkdirs()

    val origName = jar.getName

    val destNameOpt =
      if (origName == s"scala-library-$scalaVersion.jar")
        Some("scala-library.jar")
      else if (origName == s"scala-compiler-$scalaVersion.jar")
        Some("scala-compiler.jar")
      else if (origName == s"scala-reflect-$scalaVersion.jar")
        Some("scala-reflect.jar")
      else
        None

    destNameOpt match {
      case None =>
        jar
      case Some(destName) =>
        val dest = new File(dir, destName)
        if (!dest.exists())
          // FIXME Two processes doing that at the same time would clash
          Files.copy(
            jar.toPath,
            dest.toPath,
            StandardCopyOption.COPY_ATTRIBUTES
          )
        dest
    }
  }

  private val componentProvider = new ComponentProvider(componentsCache)

  private lazy val baseLoader = {

    @tailrec
    def rootLoader(cl: ClassLoader): ClassLoader =
      if (cl == null)
        sys.error("Cannot find base loader")
      else {
        val isLauncherLoader =
          try {
            cl
              .asInstanceOf[AnyRef { def getIsolationTargets: Array[String] }]
              .getIsolationTargets
              .contains("launcher")
          } catch {
            case _: Throwable => false
          }

        if (isLauncherLoader)
          cl
        else
          rootLoader(cl.getParent)
      }

    rootLoader(Thread.currentThread().getContextClassLoader)
  }

  val repositoryIdPrefix = "coursier-launcher-"

  val repositories = Seq(
    // mmh, ID "local" seems to be required for publishLocal to be fine if we're launching sbt
    "local" -> Cache.ivy2Local,
    s"${repositoryIdPrefix}central" -> MavenRepository("https://repo1.maven.org/maven2", sbtAttrStub = true),
    s"${repositoryIdPrefix}typesafe-ivy-releases" -> IvyRepository.parse(
      "https://repo.typesafe.com/typesafe/ivy-releases/[organization]/[module]/[revision]/[type]s/[artifact](-[classifier]).[ext]"
    ).left.map(sys.error).merge,
    s"${repositoryIdPrefix}sbt-plugin-releases" -> IvyRepository.parse(
      "https://repo.scala-sbt.org/scalasbt/sbt-plugin-releases/[organization]/[module](/scala_[scalaVersion])(/sbt_[sbtVersion])/[revision]/[type]s/[artifact](-[classifier]).[ext]"
    ).left.map(sys.error).merge
  )

  assert(!repositories.groupBy(_._1).exists(_._2.lengthCompare(1) > 0))

  val cachePolicies = CachePolicy.default

  private val resolutionCache = ResolutionCache(
    resolutionCacheDirOpt.map(_.toPath),
    repositories.map(_._2),
    cachePolicies = cachePolicies,
    log = log
  )

  def fetch(logger: Option[Logger]) = {
    def helper(policy: CachePolicy) =
      Cache.fetch[Task](cachePolicy = policy, logger = logger)

    val f = cachePolicies.map(helper)

    Fetch.from(repositories.map(_._2), f.head, f.tail: _*)
  }

  def tasks(res: Resolution, logger: Option[Logger], classifiersOpt: Option[Seq[Classifier]] = None) = {
    val a = res.dependencyArtifacts(classifiersOpt)

    val keepArtifactTypes = classifiersOpt.fold(Set(Type.jar, Type.bundle))(c => c.map(c => MavenAttributes.classifierExtensionDefaultTypes.getOrElse((c, Extension.jar), ???)).toSet)

    a.collect {
      case (_, attr, artifact) if keepArtifactTypes(attr.`type`) =>
        def file(policy: CachePolicy) =
          Cache.file[Task](
            artifact,
            cachePolicy = policy,
            logger = logger
          )

        (file(cachePolicies.head) /: cachePolicies.tail)(_ orElse file(_))
          .run
          .map(artifact.->)
      }
  }


  def isOverrideRepositories = false // ???

  def bootDirectory: File = ???

  def getScala(version: String): xsbti.ScalaProvider =
    getScala(version, "")

  def getScala(version: String, reason: String): xsbti.ScalaProvider =
    getScala(version, reason, defaultScalaOrg.value)

  def getScala(version: String, reason: String, scalaOrg: String): xsbti.ScalaProvider = {

    val scalaOrg0 = Organization(scalaOrg)
    val key = (version, scalaOrg0)

    if (!scalaProviderCache.contains(key)) {
      val prov = getScala0(version, reason, scalaOrg0)
      scalaProviderCache.putIfAbsent(key, prov)
    }

    scalaProviderCache.get(key)
  }

  private val scalaProviderCache = new ConcurrentHashMap[(String, Organization), xsbti.ScalaProvider]

  private def getScalaProvider(files: Seq[File], version: String): xsbti.ScalaProvider = {

    // The way these JARs are found is kind of flaky - there are ways to get the right JARs with 100% certainty
    // via coursier.core.Resolution.dependencyArtifacts.
    val (libraryJars, otherJars) = files.partition(_.getName.startsWith("scala-library"))
    val libraryJar = libraryJars match {
      case Seq(j) => j
      case _ => throw new NoSuchElementException("scala-library JAR")
    }
    val compilerJar = otherJars.find(_.getName.startsWith("scala-compiler")).getOrElse {
      throw new NoSuchElementException("scala-compiler JAR")
    }

    val libraryLoader = new URLClassLoader(Array(libraryJar.toURI.toURL), baseLoader)
    val loader = new URLClassLoader(otherJars.map(_.toURI.toURL).toArray, libraryLoader)

    ScalaProvider(
      this,
      version,
      libraryLoader,
      loader,
      files.toArray,
      libraryJar,
      compilerJar,
      id => app(id, id.version())
    )
  }

  private def getScala0(version: String, reason: String, scalaOrg: Organization): xsbti.ScalaProvider = {

    val files = getScalaFiles(version, reason, scalaOrg).map(noVersionScalaJar(scalaOrg, version, _))

    getScalaProvider(files, version)
  }

  private def getScalaFiles(version: String, reason: String, scalaOrg: Organization): Seq[File] =
    resolutionCache.artifacts(
      Seq(
        Dependency(Module(scalaOrg, name"scala-library"), version),
        Dependency(Module(scalaOrg, name"scala-compiler"), version)
      ),
      Map(
        Module(scalaOrg, name"scala-library") -> version,
        Module(scalaOrg, name"scala-compiler") -> version,
        Module(scalaOrg, name"scala-reflect") -> version
      )
    ) match {
      case Left(e) =>
        Console.err.println(e.describe)
        sys.exit(1)
      case Right(files) => files
    }

  def topLoader: ClassLoader = baseLoader

  def appRepositories: Array[xsbti.Repository] =
    repositories.map {
      case (id, m: MavenRepository) =>
        Repository.Maven(id, new URL(m.root))
      case (id, i: IvyRepository) =>

        assert(i.metadataPatternOpt.forall(_ == i.pattern))

        val (base, pat) = i.pattern.string.span(c => c != '[' && c != '$' && c != '(')

        assert(base.nonEmpty, i.pattern.string)

        Repository.Ivy(
          id,
          new URL(base),
          pat,
          pat,
          mavenCompatible = false,
          skipConsistencyCheck = true, // ???
          descriptorOptional = true // ???
        )
      case (_, _) =>
        ???
    }.toArray

  def ivyRepositories: Array[xsbti.Repository] =
    appRepositories // ???

  def globalLock = DummyGlobalLock

  // See https://github.com/sbt/ivy/blob/2cf13e211b2cb31f0d3b317289dca70eca3362f6/src/java/org/apache/ivy/util/ChecksumHelper.java
  val checksums: Array[String] = Array("sha1", "md5")

  def app(id: xsbti.ApplicationID, version: String): xsbti.AppProvider =
    app(ApplicationID(id).copy(version = version))

  def app(id: xsbti.ApplicationID, extra: Dependency*): xsbti.AppProvider = {

    val scalaOrg = defaultScalaOrg
    val (scalaFiles, files) = appFiles(scalaOrg, scalaVersion, id, extra: _*)
    val scalaFiles0 = scalaFiles.map(noVersionScalaJar(scalaOrg, scalaVersion, _))

    val scalaProvider = getScalaProvider(scalaFiles0, scalaVersion)

    val loader = new URLClassLoader(files.filterNot(scalaFiles.toSet).map(_.toURI.toURL).toArray, scalaProvider.loader())
    val mainClass0 = loader.loadClass(id.mainClass).asSubclass(classOf[xsbti.AppMain])

    AppProvider(
      scalaProvider,
      id,
      loader,
      mainClass0,
      () => mainClass0.newInstance(),
      files.toArray,
      componentProvider
    )
  }

  private def appFiles(
    scalaOrg: Organization,
    scalaVersion: String,
    id: xsbti.ApplicationID,
    extra: Dependency*
  ): (Seq[File], Seq[File]) = {

    val id0 = ApplicationID(id).disableCrossVersion(scalaVersion)

    val files = resolutionCache
      .artifacts(
        Seq(
          Dependency(Module(Organization(id0.groupID), ModuleName(id0.name)), id0.version),
          Dependency(Module(scalaOrg, name"scala-library"), scalaVersion),
          Dependency(Module(scalaOrg, name"scala-compiler"), scalaVersion)
        ) ++ extra,
        Map(
          Module(scalaOrg, name"scala-library") -> scalaVersion,
          Module(scalaOrg, name"scala-compiler") -> scalaVersion,
          Module(scalaOrg, name"scala-reflect") -> scalaVersion
        )
      )
      .left.map { e =>
        System.err.println(e.describe)
        sys.exit(1)
      }
      .merge

    val scalaFiles = resolutionCache
      .artifacts(
        Seq(
          Dependency(Module(scalaOrg, name"scala-library"), scalaVersion),
          Dependency(Module(scalaOrg, name"scala-compiler"), scalaVersion)
        ),
        Map(
          Module(scalaOrg, name"scala-library") -> scalaVersion,
          Module(scalaOrg, name"scala-compiler") -> scalaVersion,
          Module(scalaOrg, name"scala-reflect") -> scalaVersion
        )
      )
      .left.map { e =>
        System.err.println(e.describe)
        sys.exit(1)
      }
      .merge

    (scalaFiles, files)
  }

  def registerScalaComponents(scalaVersion: String = scalaVersion): Unit = {

    lazy val prov = getScala(scalaVersion)
    lazy val jars = prov.jars()

    lazy val libraryJar = jars.find(_.getName.startsWith("scala-library")).getOrElse {
      throw new NoSuchElementException("scala-library JAR")
    }
    lazy val compilerJar = jars.find(_.getName.startsWith("scala-compiler")).getOrElse {
      throw new NoSuchElementException("scala-compiler JAR")
    }
    lazy val reflectJar = jars.find(_.getName.startsWith("scala-reflect")).getOrElse {
      throw new NoSuchElementException("scala-reflect JAR")
    }

    if (componentProvider.component("library").isEmpty)
      componentProvider.defineComponentNoCopy("library", Array(libraryJar))
    if (componentProvider.component("compiler").isEmpty)
      componentProvider.defineComponentNoCopy("compiler", Array(compilerJar))
    if (componentProvider.component("reflect").isEmpty)
      componentProvider.defineComponentNoCopy("reflect", Array(reflectJar))
  }

  def registerSbtInterfaceComponents(sbtVersion: String): Unit = {

    lazy val compilerInterfaceSourceJar = sbtCompilerInterfaceSrcComponentFile(sbtVersion)

    if (componentProvider.component("xsbti").isEmpty) {
      val (interfaceJar, _) = sbtInterfaceComponentFiles(sbtVersion)
      componentProvider.defineComponentNoCopy("xsbti", Array(interfaceJar))
    }
    if (componentProvider.component("compiler-interface").isEmpty)
      componentProvider.defineComponentNoCopy("compiler-interface", Array(compilerInterfaceSourceJar))
    if (componentProvider.component("compiler-interface-src").isEmpty)
      componentProvider.defineComponentNoCopy("compiler-interface-src", Array(compilerInterfaceSourceJar))
  }

  private def sbtInterfaceComponentFiles(sbtVersion: String): (File, File) = {

    val sbtVersion0 = sbtVersion match {
      case "1.2.1" => "1.2.0"
      case other => other
    }

    lazy val interfaceJar = {

      val files = resolutionCache
        .artifacts(
          Seq(Dependency(mod"org.scala-sbt:interface", sbtVersion0, transitive = false)),
          Map()
        )
        .left.map { e =>
          System.err.println(e.describe)
          sys.exit(1)
        }
        .merge

      files match {
        case Nil =>
          throw new NoSuchElementException(s"interface JAR for sbt $sbtVersion0")
        case Seq(jar) =>
          jar
        case _ =>
          sys.error(s"Too many interface JAR for sbt $sbtVersion0: ${files.mkString(", ")}")
      }
    }

    lazy val compilerInterfaceSourcesJar = {

      val files = resolutionCache
        .artifacts(
          Seq(Dependency(mod"org.scala-sbt:interface", sbtVersion0, transitive = false)),
          Map(),
          Some(Seq(Classifier.sources))
        )
        .left.map { e =>
          System.err.println(e.describe)
          sys.exit(1)
        }
        .merge

      files match {
        case Nil =>
          throw new NoSuchElementException(s"compiler-interface source JAR for sbt $sbtVersion0")
        case Seq(jar) =>
          jar
        case _ =>
          sys.error(s"Too many compiler-interface source JAR for sbt $sbtVersion0: ${files.mkString(", ")}")
      }
    }

    (interfaceJar, compilerInterfaceSourcesJar)
  }

  private def sbtCompilerInterfaceSrcComponentFile(sbtVersion: String): File = {

    def files0(classifiers: Option[Seq[Classifier]]) =
      resolutionCache
        .artifacts(
          Seq(Dependency(mod"org.scala-sbt:compiler-interface", sbtVersion, transitive = false)),
          Map(),
          classifiers
        )
        .left.map { e =>
        System.err.println(e.describe)
        sys.exit(1)
      }
        .merge

    val files = files0(None) ++ files0(Some(Seq(Classifier.sources)))

    files
      .find(f =>
        f.getName.endsWith("-src.jar") ||
          f.getName.endsWith("-sources.jar")
      )
      .getOrElse {
        sys.error("compiler-interface-src not found")
      }
  }
}

object Launcher {

  val defaultScalaOrg = org"org.scala-lang"

}