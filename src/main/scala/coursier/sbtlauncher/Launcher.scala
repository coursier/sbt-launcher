package coursier.sbtlauncher

import java.io.{File, OutputStreamWriter}
import java.net.{URL, URLClassLoader}
import java.nio.file.{Files, StandardCopyOption}
import java.util.concurrent.ConcurrentHashMap

import coursier.Cache.Logger
import coursier._
import coursier.ivy.IvyRepository
import coursier.maven.MavenSource
import coursier.util.Task

import scala.annotation.tailrec
import scala.concurrent.ExecutionContext
import scala.language.reflectiveCalls

class Launcher(
  scalaVersion: String,
  scalaJarCache: File,
  componentsCache: File,
  val ivyHome: File
) extends xsbti.Launcher {

  private def noVersionScalaJar(scalaOrg: String, scalaVersion: String, jar: File): File = {

    val dir = new File(scalaJarCache, s"$scalaOrg/$scalaVersion")
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

  def fetch(logger: Option[Logger]) = {
    def helper(policy: CachePolicy) =
      Cache.fetch[Task](cachePolicy = policy, logger = logger)

    val f = cachePolicies.map(helper)

    Fetch.from(repositories.map(_._2), f.head, f.tail: _*)
  }

  val keepArtifactTypes = Set("jar", "bundle")

  def tasks(res: Resolution, logger: Option[Logger], classifiersOpt: Option[Seq[String]] = None) = {
    val a = classifiersOpt
      .fold(res.dependencyArtifacts.map(_._2))(res.dependencyClassifiersArtifacts(_).map(_._2))

    val keepArtifactTypes = classifiersOpt.fold(Set("jar", "bundle"))(c => c.map(c => MavenSource.classifierExtensionDefaultTypes.getOrElse((c, "jar"), ???)).toSet)

    a.collect {
        case artifact if keepArtifactTypes(artifact.`type`) =>
          def file(policy: CachePolicy) = Cache.file[Task](
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
    getScala(version, reason, "org.scala-lang")

  def getScala(version: String, reason: String, scalaOrg: String): xsbti.ScalaProvider = {

    val key = (version, scalaOrg)

    if (!scalaProviderCache.contains(key)) {
      val prov = getScala0(version, reason, scalaOrg)
      scalaProviderCache.putIfAbsent(key, prov)
    }

    scalaProviderCache.get(key)
  }

  private val scalaProviderCache = new ConcurrentHashMap[(String, String), xsbti.ScalaProvider]

  private def getScala0(version: String, reason: String, scalaOrg: String): xsbti.ScalaProvider = {

    val files = getScalaFiles(version, reason, scalaOrg).map(noVersionScalaJar(scalaOrg, version, _))

    val libraryJar = files.find(_.getName.startsWith("scala-library")).getOrElse {
      throw new NoSuchElementException("scala-library JAR")
    }
    val compilerJar = files.find(_.getName.startsWith("scala-compiler")).getOrElse {
      throw new NoSuchElementException("scala-compiler JAR")
    }

    val scalaLoader = new URLClassLoader(files.map(_.toURI.toURL).toArray, baseLoader)

    ScalaProvider(
      this,
      version,
      scalaLoader,
      files.toArray,
      libraryJar,
      compilerJar,
      id => app(id, id.version())
    )
  }

  private def getScalaFiles(version: String, reason: String, scalaOrg: String): Seq[File] = {

    val initialRes = Resolution(
      Set(
        Dependency(Module(scalaOrg, "scala-library"), version),
        Dependency(Module(scalaOrg, "scala-compiler"), version)
      ),
      forceVersions = Map(
        Module(scalaOrg, "scala-library") -> version,
        Module(scalaOrg, "scala-compiler") -> version,
        Module(scalaOrg, "scala-reflect") -> version
      )
    )

    val logger =
      Some(new TermDisplay(
        new OutputStreamWriter(System.err)
      ))

    logger.foreach(_.init {
      System.err.println(s"Resolving Scala $version (organization $scalaOrg)")
    })

    val res = initialRes.process.run(fetch(logger)).unsafeRun()(ExecutionContext.global)

    logger.foreach { l =>
      if (l.stopDidPrintSomething())
        System.err.println(s"Resolved Scala $version (organization $scalaOrg)")
    }

    if (res.errors.nonEmpty) {
      Console.err.println(s"Errors:\n${res.errors.map("  " + _).mkString("\n")}")
      sys.exit(1)
    }

    if (res.conflicts.nonEmpty) {
      Console.err.println(s"Conflicts:\n${res.conflicts.map("  " + _).mkString("\n")}")
      sys.exit(1)
    }

    if (!res.isDone) {
      Console.err.println("Did not converge")
      sys.exit(1)
    }

    val artifactLogger =
      Some(new TermDisplay(
        new OutputStreamWriter(System.err)
      ))

    artifactLogger.foreach(_.init {
      System.err.println(s"Fetching Scala $version artifacts (organization $scalaOrg)")
    })

    val results = Task.gather.gather(tasks(res, artifactLogger)).unsafeRun()(ExecutionContext.global)

    artifactLogger.foreach { l =>
      if (l.stopDidPrintSomething())
        System.err.println(s"Fetched Scala $version artifacts (organization $scalaOrg)")
    }

    val errors = results.collect { case (a, Left(err)) => (a, err) }
    val files = results.collect { case (_, Right(f)) => f }

    if (errors.nonEmpty) {
      Console.err.println(s"Error downloading artifacts:\n${errors.map("  " + _).mkString("\n")}")
      sys.exit(1)
    }

    files
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

    val scalaOrg = "org.scala-lang"
    val (scalaFiles, files) = appFiles(scalaOrg, scalaVersion, id, extra: _*)
    val scalaFiles0 = scalaFiles.map(noVersionScalaJar(scalaOrg, scalaVersion, _))

    val scalaLoader = new URLClassLoader(scalaFiles0.map(_.toURI.toURL).toArray, baseLoader)

    val libraryJar = scalaFiles0.find(_.getName.startsWith("scala-library")).getOrElse {
      throw new NoSuchElementException("scala-library JAR")
    }
    val compilerJar = scalaFiles0.find(_.getName.startsWith("scala-compiler")).getOrElse {
      throw new NoSuchElementException("scala-compiler JAR")
    }

    val scalaProvider = ScalaProvider(
      this,
      scalaVersion,
      scalaLoader,
      scalaFiles0.toArray,
      libraryJar,
      compilerJar,
      id => app(id, id.version())
    )

    val loader = new URLClassLoader(files.filterNot(scalaFiles.toSet).map(_.toURI.toURL).toArray, scalaLoader)
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
    scalaOrg: String,
    scalaVersion: String,
    id: xsbti.ApplicationID,
    extra: Dependency*
  ): (Seq[File], Seq[File]) = {

    val id0 = ApplicationID(id).disableCrossVersion(scalaVersion)

    val initialRes = Resolution(
      Set(
        Dependency(Module(scalaOrg, "scala-library"), scalaVersion),
        Dependency(Module(scalaOrg, "scala-compiler"), scalaVersion),
        Dependency(Module(id0.groupID, id0.name), id0.version)
      ) ++ extra,
      forceVersions = Map(
        Module(scalaOrg, "scala-library") -> scalaVersion,
        Module(scalaOrg, "scala-compiler") -> scalaVersion,
        Module(scalaOrg, "scala-reflect") -> scalaVersion
      )
    )

    val logger =
      Some(new TermDisplay(
        new OutputStreamWriter(System.err)
      ))

    val extraMsg =
      if (extra.isEmpty)
        ""
      else
        s" (plus ${extra.length} dependencies)"

    logger.foreach(_.init {
      System.err.println(s"Resolving ${id0.groupID}:${id0.name}:${id0.version}$extraMsg")
    })

    val res = initialRes.process.run(fetch(logger)).unsafeRun()(ExecutionContext.global)

    logger.foreach { l =>
      if (l.stopDidPrintSomething())
        System.err.println(s"Resolved ${id0.groupID}:${id0.name}:${id0.version}$extraMsg")
    }

    if (res.errors.nonEmpty) {
      Console.err.println(s"Errors:\n${res.errors.map("  " + _).mkString("\n")}")
      sys.exit(1)
    }

    if (res.conflicts.nonEmpty) {
      Console.err.println(s"Conflicts:\n${res.conflicts.map("  " + _).mkString("\n")}")
      sys.exit(1)
    }

    if (!res.isDone) {
      Console.err.println("Did not converge")
      sys.exit(1)
    }

    val artifactLogger =
      Some(new TermDisplay(
        new OutputStreamWriter(System.err)
      ))

    artifactLogger.foreach(_.init {
      System.err.println(s"Fetching ${id0.groupID}:${id0.name}:${id0.version} artifacts")
    })

    val results = Task.gather.gather(tasks(res, artifactLogger)).unsafeRun()(ExecutionContext.global)

    artifactLogger.foreach { l =>
      if (l.stopDidPrintSomething())
        System.err.println(s"Fetched ${id0.groupID}:${id0.name}:${id0.version} artifacts")
    }

    val errors = results.collect { case (a, Left(err)) => (a, err) }
    val files = results.collect { case (_, Right(f)) => f }

    if (errors.nonEmpty) {
      Console.err.println(s"Error downloading artifacts:\n${errors.map("  " + _).mkString("\n")}")
      sys.exit(1)
    }

    val scalaSubRes = res.subset(
      Set(
        Dependency(Module(scalaOrg, "scala-library"), scalaVersion),
        Dependency(Module(scalaOrg, "scala-compiler"), scalaVersion)
      )
    )

    val scalaArtifactLogger =
      Some(new TermDisplay(
        new OutputStreamWriter(System.err)
      ))

    scalaArtifactLogger.foreach(_.init {
      System.err.println(s"Fetching ${id0.groupID}:${id0.name}:${id0.version} Scala artifacts")
    })

    val scalaResults = Task.gather.gather(tasks(scalaSubRes, scalaArtifactLogger)).unsafeRun()(ExecutionContext.global)

    scalaArtifactLogger.foreach { l =>
      if (l.stopDidPrintSomething())
        System.err.println(s"Fetched ${id0.groupID}:${id0.name}:${id0.version} Scala artifacts")
    }

    val scalaErrors = scalaResults.collect { case (a, Left(err)) => (a, err) }
    val scalaFiles = scalaResults.collect { case (_, Right(f)) => f }

    if (scalaErrors.nonEmpty) {
      Console.err.println(s"Error downloading artifacts:\n${scalaErrors.map("  " + _).mkString("\n")}")
      sys.exit(1)
    }

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

    lazy val (interfaceJar, _) = sbtInterfaceComponentFiles(sbtVersion)
    lazy val compilerInterfaceSourceJar = sbtCompilerInterfaceSrcComponentFile(sbtVersion)

    // if (componentProvider.component("xsbti").isEmpty)
      // componentProvider.defineComponentNoCopy("xsbti", Array(interfaceJar))
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

    lazy val res = {

      val initialRes = Resolution(
        Set(
          Dependency(Module("org.scala-sbt", "interface"), sbtVersion0, transitive = false)
        )
      )

      val logger =
        Some(new TermDisplay(
          new OutputStreamWriter(System.err)
        ))

      logger.foreach(_.init {
        System.err.println(s"Resolving org.scala-sbt:interface:$sbtVersion0")
      })

      val res = initialRes.process.run(fetch(logger)).unsafeRun()(ExecutionContext.global)

      logger.foreach { l =>
        if (l.stopDidPrintSomething())
          System.err.println(s"Resolved org.scala-sbt:interface:$sbtVersion0")
      }

      if (res.errors.nonEmpty) {
        Console.err.println(s"Errors:\n${res.errors.map("  " + _).mkString("\n")}")
        sys.exit(1)
      }

      if (res.conflicts.nonEmpty) {
        Console.err.println(s"Conflicts:\n${res.conflicts.map("  " + _).mkString("\n")}")
        sys.exit(1)
      }

      if (!res.isDone) {
        Console.err.println("Did not converge")
        sys.exit(1)
      }

      res
    }

    lazy val interfaceJar = {

      val artifactLogger =
        Some(new TermDisplay(
          new OutputStreamWriter(System.err)
        ))

      artifactLogger.foreach(_.init {
        System.err.println(s"Fetching org.scala-sbt:interface:$sbtVersion0 artifacts")
      })

      val results = Task.gather.gather(tasks(res, artifactLogger)).unsafeRun()(ExecutionContext.global)

      artifactLogger.foreach { l =>
        if (l.stopDidPrintSomething())
          System.err.println(s"Fetched org.scala-sbt:interface:$sbtVersion0 artifacts")
      }

      val errors = results.collect { case (a, Left(err)) => (a, err) }
      val files = results.collect { case (_, Right(f)) => f }

      if (errors.nonEmpty) {
        Console.err.println(s"Error downloading artifacts:\n${errors.map("  " + _).mkString("\n")}")
        sys.exit(1)
      }

      files match {
        case Nil =>
          throw new NoSuchElementException(s"interface JAR for sbt $sbtVersion0")
        case List(jar) =>
          jar
        case _ =>
          sys.error(s"Too many interface JAR for sbt $sbtVersion0: ${files.mkString(", ")}")
      }
    }

    lazy val compilerInterfaceSourcesJar = {

      val artifactLogger =
        Some(new TermDisplay(
          new OutputStreamWriter(System.err)
        ))

      artifactLogger.foreach(_.init {
        System.err.println(s"Fetching org.scala-sbt:interface:$sbtVersion0 source artifacts")
      })

      val results = Task.gather.gather(tasks(res, artifactLogger, Some(Seq("sources")))).unsafeRun()(ExecutionContext.global)

      artifactLogger.foreach { l =>
        if (l.stopDidPrintSomething())
          System.err.println(s"Fetched org.scala-sbt:interface:$sbtVersion0 source artifacts")
      }

      val errors = results.collect { case (a, Left(err)) => (a, err) }
      val files = results.collect { case (_, Right(f)) => f }

      if (errors.nonEmpty) {
        Console.err.println(s"Error downloading artifacts:\n${errors.map("  " + _).mkString("\n")}")
        sys.exit(1)
      }

      files match {
        case Nil =>
          throw new NoSuchElementException(s"compiler-interface source JAR for sbt $sbtVersion0")
        case List(jar) =>
          jar
        case _ =>
          sys.error(s"Too many compiler-interface source JAR for sbt $sbtVersion0: ${files.mkString(", ")}")
      }
    }

    (interfaceJar, compilerInterfaceSourcesJar)
  }

  private def sbtCompilerInterfaceSrcComponentFile(sbtVersion: String): File = {

    val res = {

      val initialRes = Resolution(
        Set(
          Dependency(Module("org.scala-sbt", "compiler-interface"), sbtVersion, transitive = false)
        )
      )

      val logger =
        Some(new TermDisplay(
          new OutputStreamWriter(System.err)
        ))

      logger.foreach(_.init {
        System.err.println(s"Resolving org.scala-sbt:compiler-interface:$sbtVersion")
      })

      val res = initialRes.process.run(fetch(logger)).unsafeRun()(ExecutionContext.global)

      logger.foreach { l =>
        if (l.stopDidPrintSomething())
          System.err.println(s"Resolved org.scala-sbt:compiler-interface:$sbtVersion")
      }

      if (res.errors.nonEmpty) {
        Console.err.println(s"Errors:\n${res.errors.map("  " + _).mkString("\n")}")
        sys.exit(1)
      }

      if (res.conflicts.nonEmpty) {
        Console.err.println(s"Conflicts:\n${res.conflicts.map("  " + _).mkString("\n")}")
        sys.exit(1)
      }

      if (!res.isDone) {
        Console.err.println("Did not converge")
        sys.exit(1)
      }

      res
    }

    val files = {

      val artifactLogger =
        Some(new TermDisplay(
          new OutputStreamWriter(System.err)
        ))

      artifactLogger.foreach(_.init {
        System.err.println(s"Fetching org.scala-sbt:compiler-interface:$sbtVersion source artifacts")
      })

      val results = Task.gather.gather(
        tasks(res, artifactLogger, None) ++
          tasks(res, artifactLogger, Some(Seq("sources")))
      ).unsafeRun()(ExecutionContext.global)

      artifactLogger.foreach { l =>
        if (l.stopDidPrintSomething())
          System.err.println(s"Fetched org.scala-sbt:compiler-interface:$sbtVersion source artifacts")
      }

      val errors = results.collect { case (a, Left(err)) => (a, err) }

      if (errors.nonEmpty) {
        Console.err.println(s"Error downloading artifacts:\n${errors.map("  " + _).mkString("\n")}")
        sys.exit(1)
      }

      results.collect { case (_, Right(f)) => f }
    }

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
