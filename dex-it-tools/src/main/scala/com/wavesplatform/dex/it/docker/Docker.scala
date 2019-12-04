package com.wavesplatform.dex.it.docker

import java.io.{ByteArrayInputStream, ByteArrayOutputStream, FileOutputStream}
import java.net.{InetAddress, InetSocketAddress}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Collections._
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

import cats.instances.map.catsKernelStdMonoidForMap
import cats.instances.string._
import cats.syntax.monoid._
import com.google.common.primitives.Ints._
import com.spotify.docker.client.exceptions.ContainerNotFoundException
import com.spotify.docker.client.messages.EndpointConfig.EndpointIpamConfig
import com.spotify.docker.client.messages._
import com.spotify.docker.client.shaded.com.google.common.collect.ImmutableList
import com.spotify.docker.client.{DefaultDockerClient, DockerClient}
import com.wavesplatform.utils.ScorexLogging
import monix.eval.Coeval
import mouse.any._
import org.apache.commons.compress.archivers.tar.{TarArchiveEntry, TarArchiveInputStream, TarArchiveOutputStream}
import org.apache.commons.io.IOUtils

import scala.collection.JavaConverters._
import scala.util.Random
import scala.util.control.NonFatal

class Docker(suiteName: String = "") extends AutoCloseable with ScorexLogging {

  import Docker._

  // connection pool and timeout changed because of CorrectStatusAfterPlaceTestSuite (otherwise part of requests cannot be sent)
  private val client          = DefaultDockerClient.fromEnv().connectionPoolSize(200).connectTimeoutMillis(3000).readTimeoutMillis(3000).build()
  private val knownContainers = ConcurrentHashMap.newKeySet[DockerContainer]()
  private val isStopped       = new AtomicBoolean(false)

  // a random network in 10.x.x.x range
  private val networkSeed = Random.nextInt(0x100000) << 4 | 0x0A000000

  // 10.x.x.x/28 network will accommodate up to 13 nodes
  private val networkPrefix = s"${InetAddress.getByAddress(toByteArray(networkSeed)).getHostAddress}/28"

  // A location for logs from containers on local machine
  private val logDir: Coeval[Path] = Coeval.evalOnce {
    val r = Option(System.getProperty("waves.it.logging.dir"))
      .map(Paths.get(_))
      .getOrElse(Paths.get(System.getProperty("user.dir"), "dex-it", "target", "logs", RunId, suiteName.replaceAll("""(\w)\w*\.""", "$1.")))

    Files.createDirectories(r)
    r
  }

  /**
    * @return The address inside the network
    */
  def getInternalSocketAddress(container: DockerContainer, internalPort: Int): InetSocketAddress =
    repeatUntilSuccess(10, {
      val ns = client.inspectContainer(container.id).networkSettings()
      new InetSocketAddress(ns.networks().get(network().name()).ipAddress(), internalPort)
    })

  /**
    * @return The address outside the network, from host machine
    */
  def getExternalSocketAddress(container: DockerContainer, internalPort: Int): InetSocketAddress =
    repeatUntilSuccess(
      10, {
        val ns = client.inspectContainer(container.id).networkSettings()
        val binding = Option(ns.ports().get(s"$internalPort/tcp"))
          .map(_.get(0))
          .getOrElse(throw new IllegalStateException(s"There is no mapping '$internalPort/tcp' for '${container.name}'"))

        new InetSocketAddress("127.0.0.1", binding.hostPort().toInt)
      }
    )

  def ipForNode(nodeId: Int): String = InetAddress.getByAddress(toByteArray(nodeId & 0xF | networkSeed)).getHostAddress

  val network: Coeval[Network] = Coeval.evalOnce {

    val id          = Random.nextInt(Int.MaxValue)
    val networkName = s"waves-$id"

    def getNetwork: Option[Network] =
      try {
        val networks = client.listNetworks(DockerClient.ListNetworksParam.byNetworkName(networkName))
        if (networks.isEmpty) None else Some(networks get 0)
      } catch {
        case NonFatal(_) => getNetwork
      }

    def attempt(rest: Int): Network = {
      try {
        getNetwork match {
          case Some(network) =>
            network unsafeTap { _ =>
              val ipam =
                network
                  .ipam()
                  .config()
                  .asScala
                  .map(n => s"subnet=${n.subnet()}, ip range=${n.ipRange()}")
                  .mkString(", ")

              log.info(s"Network '${network.name()}' (id: '${network.id()}') is created for '$suiteName', ipam: $ipam")
            }

          case None =>
            log.debug(s"Creating network '$networkName' for '$suiteName'")
            // Specify the network manually because of race conditions: https://github.com/moby/moby/issues/20648
            val networkCreation =
              client.createNetwork(
                NetworkConfig
                  .builder()
                  .name(networkName)
                  .ipam(
                    Ipam
                      .builder()
                      .driver("default")
                      .config(singletonList(IpamConfig.create(networkPrefix, networkPrefix, ipForNode(0xE))))
                      .build()
                  )
                  .checkDuplicate(true)
                  .build()
              )

            Option(networkCreation.warnings).foreach(log.warn(_))
            attempt(rest - 1)
        }
      } catch {
        case NonFatal(e) =>
          log.warn(s"Can not create a network for $suiteName", e)
          if (rest == 0) throw e else attempt(rest - 1)
      }
    }

    attempt(5)
  }

  def writeFile(container: DockerContainer, to: Path, content: String, logContent: Boolean = false): Unit = {

    if (logContent) log.trace(s"${prefix(container)} Write to '$to':\n$content")

    val os    = new ByteArrayOutputStream()
    val s     = new TarArchiveOutputStream(os)
    val bytes = content.getBytes(StandardCharsets.UTF_8)
    val entry = new TarArchiveEntry(s"${to.getFileName}")

    entry.setSize(bytes.size)
    s.putArchiveEntry(entry)
    s.write(bytes)
    s.closeArchiveEntry()

    val is = new ByteArrayInputStream(os.toByteArray)
    s.close()

    try client.copyToContainer(is, container.id, s"${to.getParent.toString}")
    finally is.close()
  }

  def loadFile(container: DockerContainer, containerPath: Path, toLocal: Path): Unit =
    try {
      val is = client.archiveContainer(container.id, containerPath.toString)
      val s  = new TarArchiveInputStream(is)
      Iterator.continually(s.getNextEntry).takeWhile(_ != null).take(1).foreach { _ =>
        val out = new FileOutputStream(toLocal.toString)
        try IOUtils.copy(s, out)
        finally out.close()
      }
    } catch {
      case e: ContainerNotFoundException => log.warn(s"File '$containerPath' not found: ${e.getMessage}")
    }

  // TODO remove
  def start(container: Coeval[DockerContainer]): Unit = start { container() }

  def start(container: DockerContainer): Unit = {
    log.debug(s"${prefix(container)} Starting ...")
    try {
      client.startContainer(container.id)
      waitProcessStarted(container)
    } catch {
      case NonFatal(e) =>
        log.error(s"${prefix(container)} Can't start", e)
        throw e
    }
  }

  private def waitProcessStarted(container: DockerContainer): Unit = repeat(20, hasLogs(container))

  private def hasLogs(container: DockerContainer): Boolean = {
    log.trace(s"${prefix(container)} Trying to check logs")
    val buffer = new ByteArrayOutputStream(1024)
    try {
      client
        .logs(
          container.id,
          DockerClient.LogsParam.stdout(),
          DockerClient.LogsParam.stderr()
        )
        .attach(buffer, buffer)

      val r = buffer.size() > 0
      log.trace(s"${prefix(container)} ${if (r) "has" else "has no"} logs")
      r
    } finally {
      buffer.close()
    }
  }

  def stop(container: Coeval[DockerContainer]): Unit = stop { container() }

  def stop(container: DockerContainer): Unit = {
    val containerInfo = client.inspectContainer(container.id)
    log.debug(s"""${prefix(container)} Information:
                 |Exit code: ${containerInfo.state().exitCode()}
                 |Error: ${containerInfo.state().error()}
                 |Status: ${containerInfo.state().status()}
                 |OOM killed: ${containerInfo.state().oomKilled()}""".stripMargin)

    log.debug(s"${prefix(container)} Stopping ...")
    try client.stopContainer(container.id, 10)
    catch {
      case NonFatal(e) =>
        log.error(s"${prefix(container)} Can't stop", e)
        throw e
    }

    saveLog(container)
  }

  def disconnectFromNetwork(container: Coeval[DockerContainer]): Unit = disconnectFromNetwork { container() }

  def disconnectFromNetwork(container: DockerContainer): Unit = {
    log.debug(s"${prefix(container)} Disconnecting from network '${network().name()}' ...")
    client.disconnectFromNetwork(container.id, network().id())
    log.info(s"${prefix(container)} Disconnected from network '${network().name()}'")
  }

  def connectToNetwork(container: Coeval[DockerContainer], netAlias: Option[String]): Unit = connectToNetwork(container(), netAlias)

  def connectToNetwork(container: DockerContainer, netAlias: Option[String] = None): Unit = {
    log.debug(s"${prefix(container)} Connecting to network '${network().name()}' ...")
    try client.connectToNetwork(
      network().id(),
      NetworkConnection
        .builder()
        .containerId(container.id)
        .endpointConfig(endpointConfigFor(container.number, netAlias))
        .build()
    )
    catch {
      case NonFatal(e) =>
        log.error(s"${prefix(container)} Can't connect to the network '${network().name()}'", e)
        throw e
    }
  }

  def printDebugMessage(container: DockerContainer, text: String): Unit =
    try {
      if (client.inspectContainer(container.id).state().running()) {
        val escaped = text.replace('\'', '\"')
        val id      = client.execCreate(container.id, Array("/bin/sh", "-c", s"/bin/echo '$escaped' >> /proc/1/fd/1")).id()
        val exec    = client.execStart(id)
        try exec.readFully()
        catch {
          case NonFatal(e) => /* ignore */
        } finally exec.close()
      }
    } catch {
      case _: ContainerNotFoundException =>
    }

  def printDebugMessage(text: String): Unit = knownContainers.asScala.foreach(printDebugMessage(_, text))

  def addKnownContainer(container: DockerContainer): Unit = knownContainers.add(container)

  def create(number: Int, name: String, imageName: String, env: Map[String, String], netAlias: Option[String] = None): String = {

    val ip            = ipForNode(number)
    val containerName = s"${network().name()}-$name"

    def info(id: String = "not yet created") = s"'$containerName': id='$id' name='$name', number='$number', image='$imageName', ip=$ip, env: $env"

    try {
      val containersWithSameName = client.listContainers(DockerClient.ListContainersParam.filter("name", containerName))

      if (!containersWithSameName.isEmpty) {
        dumpContainers(containersWithSameName, "Containers with the same name")
        throw new IllegalStateException(s"There is containers with the same name!")
      }

      val hostConfig =
        HostConfig
          .builder()
          .publishAllPorts(true)
          .build()

      // TODO
      val fixedEnv = env |+| Map("WAVES_OPTS" -> s" -Dwaves.network.declared-address=$ip:6883")

      val containerConfig =
        ContainerConfig
          .builder()
          .image(imageName)
          .networkingConfig(ContainerConfig.NetworkingConfig.create(Map(network().name() -> endpointConfigFor(number, netAlias)).asJava))
          .hostConfig(hostConfig)
          .env(fixedEnv.map { case (k, v) => s"$k=$v" }.toList.asJava)
          .build()

      log.debug(s"Creating container ${info()} ...")
      val containerCreation = client.createContainer(containerConfig, containerName)

      Option(containerCreation.warnings.asScala).toSeq.flatten.foreach(e => log.warn(s"""Error "$e", ${info(containerCreation.id())}"""))
      containerCreation.id()

    } catch {
      case NonFatal(e) =>
        log.error(s"Can't create a container ${info()}", e)
        dumpContainers(client.listContainers())
        throw e
    }
  }

  override def close(): Unit = if (isStopped.compareAndSet(false, true)) {
    log.info("Stopping containers")

    knownContainers.asScala.foreach { container =>
      stop(container)
      log.debug(s"${prefix(container)} Removing")
      try client.removeContainer(container.id)
      catch {
        case NonFatal(e) => log.warn(s"${prefix(container)} Can't remove", e)
      }
    }

    try {
      log.debug(s"Removing the '${network().id()}' network")
      client.removeNetwork(network().id())
    } catch {
      case NonFatal(e) =>
        // https://github.com/moby/moby/issues/17217
        log.warn(s"Can't remove the '${network().id()}' network", e)
    }

    client.close()
  }

  private def saveLog(container: DockerContainer): Unit = {
    val logFile = logDir().resolve(s"container-${container.name}.log").toFile
    log.info(s"${prefix(container)} Writing log to '${logFile.getAbsolutePath}'")

    val fileStream = new FileOutputStream(logFile, false)
    try {
      client
        .logs(
          container.id,
          DockerClient.LogsParam.follow(),
          DockerClient.LogsParam.stdout(),
          DockerClient.LogsParam.stderr()
        )
        .attach(fileStream, fileStream)
    } finally {
      fileStream.close()
    }

    val containerSystemLogPath = Paths.get(container.basePath, "system.log")
    val localSystemLogPath     = logDir().resolve(s"container-${container.name}.system.log")
    log.info(s"${prefix(container)} Loading system log from '$containerSystemLogPath' to '$localSystemLogPath'")
    loadFile(
      container,
      containerPath = containerSystemLogPath,
      toLocal = localSystemLogPath
    )
  }

  private def endpointConfigFor(number: Int, netAlias: Option[String]): EndpointConfig = {

    val ip        = ipForNode(number)
    val aliasList = new ImmutableList.Builder[String].addAll(netAlias.toList.asJava).build()

    EndpointConfig
      .builder()
      .ipAddress(ip)
      .ipamConfig(EndpointIpamConfig.builder().ipv4Address(ip).build())
      .aliases(aliasList)
      .build()
  }

  private def dumpContainers(containers: java.util.List[Container], label: String = "Containers"): Unit = {
    val x =
      if (containers.isEmpty) "No"
      else
        "\n" + containers.asScala
          .map { x =>
            s"Container(${x.id()}, status: ${x.status()}, names: ${x.names().asScala.mkString(", ")})"
          }
          .mkString("\n")

    log.debug(s"$label: $x")
  }

  private def prefix(container: DockerContainer): String = s"[name='${container.name}', id=${container.id}]"

  private def repeat(maxAttempts: Int, until: => Boolean): Boolean = repeat[Boolean](maxAttempts, until, _ == true)

  private def repeatUntilSuccess[T](maxAttempts: Int, get: => T): T =
    repeat[Option[T]](
      maxAttempts,
      get = try Some(get)
      catch {
        case NonFatal(_) => None
      },
      until = _.isDefined
    ).get

  @scala.annotation.tailrec
  private def repeat[T](maxAttempts: Int, get: => T, until: T => Boolean): T =
    if (maxAttempts == 0) throw new RuntimeException("All attempts are out")
    else {
      val x = get
      if (until(x)) x
      else repeat(maxAttempts - 1, get, until)
    }

  dumpContainers(client.listContainers())
  sys.addShutdownHook {
    log.debug("Shutdown hook")
    close()
  }
}

object Docker {

  val wavesNodesDomain = "waves.nodes"

  private val RunId = Option(System.getenv("RUN_ID")).getOrElse(DateTimeFormatter.ofPattern("MM-dd--HH_mm_ss").format(LocalDateTime.now))

  def apply(owner: Class[_]): Docker = new Docker(suiteName = owner.getSimpleName)
}