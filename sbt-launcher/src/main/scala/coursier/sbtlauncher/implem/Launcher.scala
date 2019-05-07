package coursier.sbtlauncher.implem

import java.io.File
import java.net.{URL, URLClassLoader}
import java.nio.file.{Files, StandardCopyOption}
import java.util.concurrent.ConcurrentHashMap

import coursier._
import coursier.core.Classifier
import coursier.ivy.IvyRepository
import coursier.sbtlauncher.{Repository, ResolutionCache}

import scala.language.reflectiveCalls

class Launcher(
  scalaVersion: String,
  resolutionCacheDirOpt: Option[File],
  scalaJarCache: File,
  componentsCache: File,
  val ivyHome: File,
  log: String => Unit,
  useDistinctSbtTestInterfaceLoader: Boolean
) extends xsbti.Launcher {

  import Launcher._

  private val componentProvider = new ComponentProvider(componentsCache)

  private val resolutionCache = ResolutionCache(
    resolutionCacheDirOpt.map(_.toPath),
    repositories.map(_._2),
    log = log
  )

  private val scalaProviderCache = new ConcurrentHashMap[(String, Organization), xsbti.ScalaProvider]


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

    val intermediateTopLoader =
      sbtTestInterfaceFilesOpt.fold(topLoader) { sbtTestInterfaceFiles =>
        new URLClassLoader(sbtTestInterfaceFiles.map(_.toURI.toURL).toArray, topLoader)
      }
    val libraryLoader = new URLClassLoader(Array(libraryJar.toURI.toURL), intermediateTopLoader)
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

    val files = getScalaFiles(version, scalaOrg)
      .map(noVersionScalaJar(scalaJarCache, scalaOrg, version, _))

    getScalaProvider(files, version)
  }

  private def getScalaFiles(version: String, scalaOrg: Organization): Seq[File] =
    resolutionCache.artifactsOrExit(
      Seq(
        Dependency(Module(scalaOrg, name"scala-library"), version),
        Dependency(Module(scalaOrg, name"scala-compiler"), version)
      ),
      forceScala = scalaOrg -> version,
      name = s"scala-compiler $version" + {
        if (scalaOrg.value == "org.scala-lang") ""
        else s" for organization ${scalaOrg.value}"
      }
    )

  lazy val topLoader: ClassLoader =
    classOf[xsbti.Launcher].getClassLoader

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

  def globalLock = InMemoryGlobalLock

  // See https://github.com/sbt/ivy/blob/2cf13e211b2cb31f0d3b317289dca70eca3362f6/src/java/org/apache/ivy/util/ChecksumHelper.java
  val checksums: Array[String] = Array("sha1", "md5")

  def app(id: xsbti.ApplicationID, version: String): xsbti.AppProvider =
    app(ApplicationID(id).copy(version = version))

  private def sbtTestInterfaceFilesOpt =
    if (useDistinctSbtTestInterfaceLoader)
      Some(libFiles(("org.scala-sbt", "test-interface", "1.0")))
    else
      None

  def app(id: xsbti.ApplicationID, extra: Dependency*): xsbti.AppProvider = {

    val scalaOrg = defaultScalaOrg
    val (scalaFiles, files) = appFiles(scalaOrg, scalaVersion, id, extra: _*)
    val scalaFiles0 = scalaFiles.map(noVersionScalaJar(scalaJarCache, scalaOrg, scalaVersion, _))

    val scalaProvider = getScalaProvider(scalaFiles0, scalaVersion)

    val loader = new URLClassLoader(files.filterNot(scalaFiles.toSet).filterNot(sbtTestInterfaceFilesOpt.toSeq.flatten.toSet).map(_.toURI.toURL).toArray, scalaProvider.loader())
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

  private def libFiles(
    id: (String, String, String)
  ): Seq[File] = {
    val (org, name, ver) = id
    resolutionCache.artifactsOrExit(
      Seq(
        Dependency(Module(Organization(org), ModuleName(name)), ver)
      ),
      name = s"$org:$name:$ver"
    )
  }

  private def appFiles(
    scalaOrg: Organization,
    scalaVersion: String,
    id: xsbti.ApplicationID,
    extra: Dependency*
  ): (Seq[File], Seq[File]) = {

    val id0 = ApplicationID(id).disableCrossVersion(scalaVersion)

    val files = resolutionCache.artifactsOrExit(
      extra ++ Seq(
        Dependency(Module(Organization(id0.groupID), ModuleName(id0.name)), id0.version),
        Dependency(Module(scalaOrg, name"scala-library"), scalaVersion),
        Dependency(Module(scalaOrg, name"scala-compiler"), scalaVersion)
      ),
      forceScala = scalaOrg -> scalaVersion,
      name = {
        if (id0.groupID == "org.scala-sbt" && id0.name == "sbt")
          s"sbt ${id0.version}"
        else
          s"${id0.groupID}:${id0.name}:${id0.version}"
      }
    )

    val scalaFiles = getScalaFiles(scalaVersion, scalaOrg)

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
      val interfaceJar = sbtInterfaceComponentFile(sbtVersion)
      componentProvider.defineComponentNoCopy("xsbti", Array(interfaceJar))
    }
    if (componentProvider.component("compiler-interface").isEmpty)
      componentProvider.defineComponentNoCopy("compiler-interface", Array(compilerInterfaceSourceJar))
    if (componentProvider.component("compiler-interface-src").isEmpty)
      componentProvider.defineComponentNoCopy("compiler-interface-src", Array(compilerInterfaceSourceJar))
  }

  private def sbtInterfaceComponentFile(sbtVersion: String): File = {

    val selectedSbtVersion = sbtVersion match {
      case "1.2.1" => "1.2.0" // not sure why this is required…
      case other => other
    }

    val dep = Dependency(mod"org.scala-sbt:interface", selectedSbtVersion)

    resolutionCache.artifactOrExit(
      dep,
      name = s"sbt interface $selectedSbtVersion"
    )
  }

  private def sbtCompilerInterfaceSrcComponentFile(sbtVersion: String): File = {

    val deps = Seq(Dependency(mod"org.scala-sbt:compiler-interface", sbtVersion, transitive = false))

    val files =
      resolutionCache.artifactsOrExit(deps, name = s"compiler interface $sbtVersion") ++
        resolutionCache.artifactsOrExit(deps, classifiers = Seq(Classifier.sources), name = s"compiler interface $sbtVersion sources")

    files
      .find(f =>
        f.getName.endsWith("-src.jar") ||
          f.getName.endsWith("-sources.jar")
      )
      .getOrElse {
        System.err.println("compiler-interface-src not found")
        sys.exit(1)
      }
  }
}

object Launcher {

  private def defaultScalaOrg = org"org.scala-lang"
  private def repositoryIdPrefix = "coursier-launcher-"

  private val repositories = Seq(
    // FIXME Use defaults from coursier.Resolve.defaultRepositories here?
    // mmh, ID "local" seems to be required for publishLocal to be fine if we're launching sbt
    "local" -> LocalRepositories.ivy2Local,
    s"${repositoryIdPrefix}central" -> MavenRepository("https://repo1.maven.org/maven2", sbtAttrStub = true),
    s"${repositoryIdPrefix}typesafe-ivy-releases" -> IvyRepository.parse(
      "https://repo.typesafe.com/typesafe/ivy-releases/[organization]/[module]/[revision]/[type]s/[artifact](-[classifier]).[ext]"
    ).left.map(sys.error).merge,
    s"${repositoryIdPrefix}sbt-plugin-releases" -> IvyRepository.parse(
      "https://repo.scala-sbt.org/scalasbt/sbt-plugin-releases/[organization]/[module](/scala_[scalaVersion])(/sbt_[sbtVersion])/[revision]/[type]s/[artifact](-[classifier]).[ext]"
    ).left.map(sys.error).merge
  )

  assert(!repositories.groupBy(_._1).exists(_._2.lengthCompare(1) > 0))

  private def noVersionScalaJar(
    scalaJarCache: File,
    scalaOrg: Organization,
    scalaVersion: String,
    jar: File
  ): File = {

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
        val dest = new File(scalaJarCache, s"${scalaOrg.value}/$scalaVersion/$destName")
        if (!dest.exists()) {
          // FIXME Two processes doing that at the same time would clash
          Files.createDirectories(dest.toPath.getParent)
          Files.copy(
            jar.toPath,
            dest.toPath,
            StandardCopyOption.COPY_ATTRIBUTES
          )
        }
        dest
    }
  }

}
