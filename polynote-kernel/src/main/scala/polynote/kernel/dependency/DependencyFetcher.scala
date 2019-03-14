package polynote.kernel.dependency

import java.io._
import java.util.concurrent.{ExecutorService, Executors}
import java.net.URL
import java.nio.file.{Files, Paths}

import cats.Parallel
import cats.data.{Validated, ValidatedNel}
import cats.effect.{ContextShift, IO}
import cats.implicits._
import coursier.ivy.IvyRepository
import coursier.util.{Monad, Schedulable}
import coursier.core._
import coursier.{Attributes, Cache, Dependency, Fetch, FileError, MavenRepository, Module, ModuleName, Organization, ProjectCache, Repository, Resolution}
import polynote.config.{DependencyConfigs, RepositoryConfig, ivy, maven}
import polynote.kernel.util.Publish
import polynote.kernel.{KernelStatusUpdate, TaskInfo, TaskStatus, UpdatedTasks}
import polynote.messages.{TinyList, TinyMap, TinyString}

import scala.collection.mutable
import scala.concurrent.ExecutionContext
import scala.util.Try

trait DependencyFetcher[F[_]] {

  def fetchDependencyList(
    repositories: List[RepositoryConfig],
    dependencies: List[DependencyConfigs],
    exclusions: List[String],
    taskInfo: TaskInfo,
    statusUpdates: Publish[F, KernelStatusUpdate]
  ): F[List[(String, F[File])]]

}

// Fetches only Scala dependencies
class CoursierFetcher extends DependencyFetcher[IO] {
  import CoursierFetcher._, instances._

  private implicit val executionContext: ExecutionContext = ExecutionContext.fromExecutorService(Executors.newCachedThreadPool())
  private implicit val contextShift: ContextShift[IO] =  IO.contextShift(executionContext)

  private val excludedOrgs = Set(Organization("org.scala-lang"), Organization("org.apache.spark"))
  val artifactTypes = coursier.core.Resolution.defaultTypes - Type.testJar

  private val resolutionCacheFile = "resolution-cache"
  private lazy val resolutionCachePath = Cache.default.toPath.resolve(resolutionCacheFile)

  private def resolution(dependencies: List[DependencyConfigs], exclusions: List[String]): Resolution = {
    // exclusions are applied to all direct and transitive dependencies.
    val coursierExclude = exclusions.map { exclusionStr =>
      exclusionStr.split(":") match {
        case Array(org, name) => (Organization(org), ModuleName(name))
        case Array(org) => (Organization(org), Exclusions.allNames)
      }
    }.toSet

    Resolution(
      dependencies.flatMap(_.get(TinyString("scala"))).flatten.map {
        moduleStr =>
          val (org, name, typ, config, classifier, ver) = moduleStr.split(':') match {
            case Array(org, name, ver) => (Organization(org), ModuleName(name), Type.empty, Configuration.default, Classifier.empty, ver)
            case Array(org, name, classifier, ver) => (Organization(org), ModuleName(name), Type.empty, Configuration.default, Classifier(classifier), ver)
            case Array(org, name, typ, classifier, ver) => (Organization(org), ModuleName(name), Type(typ), Configuration.default, Classifier(classifier), ver)
            case Array(org, name, typ, config, classifier, ver) => (Organization(org), ModuleName(name), Type(typ), Configuration(config), Classifier(classifier), ver)
          }
          Dependency(Module(org, name), ver, config, Attributes(typ, classifier), coursierExclude, transitive = classifier.value != "all")

      }.toSet,
      filter = Some(dep => !dep.optional && !excludedOrgs(dep.module.organization))
    )
  }

  private def repos(repositories: List[RepositoryConfig]): Either[Throwable, List[Repository]] = repositories.map {
    case repo @ ivy(base, _, _, changing) =>
      val baseUri = base.stripSuffix("/") + "/"
      val artifactPattern = s"$baseUri${repo.artifactPattern}"
      val metadataPattern = s"$baseUri${repo.metadataPattern}"
      Validated.fromEither(IvyRepository.parse(artifactPattern, Some(metadataPattern), changing = changing)).toValidatedNel
    case maven(base, changing) => Validated.validNel(MavenRepository(base, changing = changing))
  }.sequence[ValidatedNel[String, ?], Repository].leftMap {
    errs => new RuntimeException(s"Errors parsing repositories:\n- ${errs.toList.mkString("\n- ")}")
  }.toEither.map {
    repos => (Cache.ivy2Local :: Cache.ivy2Cache :: repos) :+ MavenRepository("https://repo1.maven.org/maven2")
  }

  private def cacheFilesList(resolved: Resolution, downloaded: List[(String, IO[File])], statusUpdates: Publish[IO, KernelStatusUpdate]): List[(String, IO[File])] = {
    val logger = new Cache.Logger {
      private val size = new mutable.HashMap[String, Long]()

      private def update(url: String, progress: Double) = statusUpdates.publish1(
        UpdatedTasks(TaskInfo(url, s"Download ${url.split('/').last}", url, if (progress < 1.0) TaskStatus.Running else TaskStatus.Complete, (progress * 255).toByte) :: Nil)
      ).unsafeRunAsyncAndForget()

      override def downloadLength(url: String, totalLength: Long, alreadyDownloaded: Long, watching: Boolean): Unit = if (totalLength > 0) {
        size.synchronized {
          size.put(url, totalLength)
        }
        update(url, alreadyDownloaded.toDouble / totalLength)

      }

      override def downloadProgress(url: String, downloaded: Long): Unit = {
        size.get(url).foreach {
          totalLength => update(url, downloaded.toDouble / totalLength)
        }
      }

      override def downloadedArtifact(url: String, success: Boolean): Unit = size.get(url).foreach { _ =>
        val progress = if (success) 1.0 else Double.NaN
        update(url, progress)
      }
    }

    val allArtifacts = resolved.dependencyArtifacts().filter(da => artifactTypes(da._2.`type`))

    val filteredArtifacts = allArtifacts.distinct.collect {
      case (dependency, attributes, artifact) =>
        s"${dependency.module}:${dependency.version}" -> artifact
    }.distinct.map {
      case (mv, artifact) => mv -> Cache.file[IO](artifact, logger = Some(logger)).leftMap(FileErrorException).run.flatMap(IO.fromEither)
    }.toList

    val downloadedFiles = downloaded.map {
      case (url, ioF) =>
        // TODO: any way to get real progress?
        val ioWithUpdated = statusUpdates.publish1(UpdatedTasks(TaskInfo(url, s"Downloading $url", url, TaskStatus.Running) :: Nil)).bracket { _ =>
          ioF
        } { _ =>
          statusUpdates.publish1(UpdatedTasks(TaskInfo(url, s"Downloading $url", url, TaskStatus.Complete) :: Nil))
        }
        "downloaded" -> ioWithUpdated
    }

    filteredArtifacts ++ downloadedFiles
  }

  private def resolveDependencies(
    resolution: Resolution,
    fetch: Fetch.Metadata[IO],
    taskInfo: TaskInfo,
    statusUpdates: Publish[IO, KernelStatusUpdate],
    maxIterations: Int = 100
  ): IO[Resolution] = {
    // check whether we care about a missing resolution
    def shouldErrorIfMissing(mv: (Module, String)): Boolean = {
      resolution.rootDependencies.exists(dep => dep.module == mv._1 && dep.version == mv._2)
    }

    // reimplements ResolutionProcess.run, so we can update the iteration progress
    def run(resolutionProcess: ResolutionProcess, iteration: Int): IO[Resolution] =
      statusUpdates.publish1(UpdatedTasks(taskInfo.copy(progress = (iteration.toDouble / maxIterations * 255).toByte) :: Nil)) *> {
        if (iteration > maxIterations) {
          IO.pure(resolutionProcess.current)
        } else {
          resolutionProcess match {
            case Done(res) if res.errorCache.keys.exists(shouldErrorIfMissing) =>
              res.errorCache.map {
                case (mv, err) if shouldErrorIfMissing(mv) =>
                  val depStr = s"${mv._1}:${mv._2}"
                  statusUpdates.publish1(
                    UpdatedTasks(TaskInfo(
                      id = s"${taskInfo.id}_$depStr",
                      label = s"Error fetching dependency $depStr",
                      detail = err.mkString("\n\n"),
                      status = TaskStatus.Error
                    ) :: taskInfo.copy(status = TaskStatus.Complete) :: Nil)
                  )
                case _ => IO.unit
              }.toList.sequence *> IO.raiseError(new Exception("Dependency Resolution Error"))
            case Done(res) =>
              IO.pure(res)
            case missing0 @ Missing(missing, _, _) =>
              CoursierFetcher.fetchAll(missing, fetch).flatMap {
                result => run(missing0.next(result), iteration + 1)
              }
            case cont @ Continue(_, _) =>
              run(cont.nextNoCont, iteration + 1)
          }
        }
    }

    run(resolution.process, 0)
  }

  def splitDependencies(deps: List[DependencyConfigs]): (List[URL], List[DependencyConfigs]) = {
    val (urlList, dependencies) = deps.flatMap { dep =>
      dep.toList.collect {
        case (k, v) =>
          val (dependencies, urls) = v.map(s => Either.fromTry(Try(new URL(s))).leftMap(_ => s)).separate
          (urls, TinyMap(k -> TinyList(dependencies)))
      }
    }.unzip
    (urlList.flatten, dependencies)
  }

  def fetchUrls(urls: List[URL], statusUpdates: Publish[IO, KernelStatusUpdate], chunkSize: Int = 8192)(implicit ctx: ExecutionContext): List[(String, IO[File])] = {

    // cheating! we could probably provide real progress if we moved to the filesystem API
    def fakeProgress(s: fs2.Stream[IO, Unit], url: String): fs2.Stream[IO, Int] = {
      val max: Double = 1.5 * 1024 * 1024 / chunkSize // looks ok, really big jars might wrap around
      s.scan(0)((n, _) => n + 1).evalTap { i =>
        statusUpdates.publish1(UpdatedTasks(TaskInfo(url.toString, s"Downloading $url", url.toString, TaskStatus.Running, (i / max).toByte) :: Nil))
      }
    }

    urls.map { url =>
      url.toString -> IO(url.openStream).flatMap { is =>

        val file = Cache.default.toPath.resolve(Paths.get(url.getProtocol, url.getAuthority, url.getPath)).toFile

        if (file.exists()) {
          IO.pure(file)
        } else {
          for {
            _ <- IO(Files.createDirectories(file.toPath.getParent))
            os = new FileOutputStream(file)
            // is it overkill to use fs2 steams for this...?
            fs2IS = fs2.io.readInputStream[IO](IO(is), chunkSize, ctx)
            fs2OS = fs2.io.writeOutputStream[IO](IO(os), ctx)
            copyingStream = fs2IS.through(fs2OS)
            _ <- fakeProgress(copyingStream, url.toString).compile.drain
          } yield file
        }
      }
    }
  }

  def fetchDependencyList(
    repositories: List[RepositoryConfig],
    dependencies: List[DependencyConfigs],
    exclusions: List[String],
    taskInfo: TaskInfo,
    statusUpdates: Publish[IO, KernelStatusUpdate]
  ): IO[List[(String, IO[File])]] = for {
    repos <- IO.fromEither(repos(repositories))
    (urls, deps) = splitDependencies(dependencies)
    downloadFiles = fetchUrls(urls, statusUpdates)
    res   = resolution(deps, exclusions)
    resolved <- resolveDependencies(res, Fetch.from(repos, Cache.fetch[IO]()), taskInfo, statusUpdates)
  } yield cacheFilesList(resolved, downloadFiles, statusUpdates)

}

object CoursierFetcher {

  object instances extends LowPriorityInstances {
    implicit def deriveSchedulable[M[_], F[_]](implicit
      shift: ContextShift[M],
      M: cats.effect.Effect[M],
      parM: cats.Parallel[M, F]
    ): coursier.util.Schedulable[M] = new Schedulable[M] {
      def schedule[A](pool: ExecutorService)(f: => A): M[A] =
        shift.evalOn(ExecutionContext.fromExecutorService(pool))(M.delay(f))

      def gather[A](elems: Seq[M[A]]): M[Seq[A]] = Parallel.parSequence[List, M, F, A](elems.toList).map(_.toSeq)

      def point[A](a: A): M[A] = M.point(a)

      def bind[A, B](elem: M[A])(f: A => M[B]): M[B] = M.flatMap(elem)(f)

      def delay[A](a: => A): M[A] = M.delay(a)

      def handle[A](a: M[A])(f: PartialFunction[Throwable, A]): M[A] = M.handleErrorWith(a) {
        err => if (f.isDefinedAt(err)) M.pure(f(err)) else M.raiseError(err)
      }
    }

  }

  private[dependency] trait LowPriorityInstances {
    implicit def deriveMonad[F[_]](implicit F: cats.Monad[F]): coursier.util.Monad[F] = new Monad[F] {
      def point[A](a: A): F[A] = F.point(a)
      def bind[A, B](elem: F[A])(f: A => F[B]): F[B] = F.flatMap(elem)(f)
    }
  }

  final case class FileErrorException(err: FileError) extends Throwable(err.message)

  private def fetchAll[F[_]](
    modVers: Seq[(Module, String)],
    fetch: Fetch.Metadata[F]
  )(implicit F: Monad[F]): F[Vector[((Module, String), Either[Seq[String], (Artifact.Source, Project)])]] = {

    def uniqueModules(modVers: Seq[(Module, String)]): Stream[Seq[(Module, String)]] = {

      val res = modVers.groupBy(_._1).toSeq.map(_._2).map {
        case Seq(v) => (v, Nil)
        case Seq() => sys.error("Cannot happen")
        case v =>
          // there might be version intervals in there, but that shouldn't matter...
          val res = v.maxBy { case (_, v0) => Version(v0) }
          (res, v.filter(_ != res))
      }

      val other = res.flatMap(_._2)

      if (other.isEmpty)
        Stream(modVers)
      else {
        val missing0 = res.map(_._1)
        missing0 #:: uniqueModules(other)
      }
    }

    uniqueModules(modVers)
      .toVector
      .foldLeft(F.point(Vector.empty[((Module, String), Either[Seq[String], (Artifact.Source, Project)])])) {
        (acc, l) =>
          F.bind(acc) { v =>
            F.map(fetch(l)) { e =>
              v ++ e.map {
                case (mv, Right((src, proj))) =>
                  (mv, Right(src, proj.copy(dependencies = proj.dependencies.filter(_._2.version != ""))))
                case ee => ee
              }
            }
          }
      }
  }
}