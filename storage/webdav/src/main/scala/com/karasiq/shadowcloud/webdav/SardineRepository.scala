package com.karasiq.shadowcloud.webdav

import java.{net, util}
import java.io.IOException
import java.net._

import scala.collection.JavaConverters._
import scala.collection.concurrent.TrieMap
import scala.concurrent.Future
import scala.util.{Failure, Success, Try}

import akka.NotUsed
import akka.dispatch.MessageDispatcher
import akka.stream.{ActorAttributes, Supervision}
import akka.stream.scaladsl.{Flow, Keep, Sink, Source, StreamConverters}
import com.github.sardine.{DavResource, Sardine}
import com.github.sardine.impl.SardineImpl
import org.apache.http.config.Registry
import org.apache.http.conn.HttpClientConnectionManager
import org.apache.http.conn.socket.ConnectionSocketFactory
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager

import com.karasiq.common.configs.ConfigImplicits._
import com.karasiq.shadowcloud.model.Path
import com.karasiq.shadowcloud.storage.StorageIOResult
import com.karasiq.shadowcloud.storage.props.StorageProps
import com.karasiq.shadowcloud.storage.repository.PathTreeRepository
import com.karasiq.shadowcloud.storage.utils.StorageUtils
import com.karasiq.shadowcloud.streams.utils.AkkaStreamUtils

object SardineRepository {
  def apply(props: StorageProps, sardine: Sardine)(implicit dispatcher: MessageDispatcher): SardineRepository = {
    new SardineRepository(props, sardine)
  }

   def getResourceURL(baseUrl: String, path: Path): String = {
    val urlWithSlash = if (baseUrl.endsWith("/")) baseUrl else baseUrl + "/"
    val encodedPath = path.nodes.map(URLEncoder.encode(_, "UTF-8"))
    urlWithSlash + encodedPath.mkString("/")
  }

  def createSardine(props: StorageProps) = {
    def createProxySelector(config: Config) = {
      val proxies = {
        val proxies = config.withDefault(Nil, _.getStrings("proxies")).map { ps ⇒
          val uri = new URI(if (ps.contains("://")) ps else "http://" + ps)
          val proxyType = uri.getScheme match {
            case "socks" | "socks4" | "socks5" ⇒ net.Proxy.Type.SOCKS
            case _ ⇒ net.Proxy.Type.HTTP
          }
          new net.Proxy(proxyType, InetSocketAddress.createUnresolved(uri.getHost, uri.getPort))
        }
        if (proxies.isEmpty) List(net.Proxy.NO_PROXY) else proxies
      }

      new ProxySelector {
        def select(uri: URI): util.List[Proxy] = proxies.asJava
        def connectFailed(uri: URI, sa: SocketAddress, ioe: IOException): Unit = ()
      }
    }

    val sardineConfig = props.rootConfig.getConfigIfExists("sardine")
    val maxConnections = sardineConfig.withDefault(8, _.getInt("max-connections"))
    val proxySelector = createProxySelector(sardineConfig)

    val sardine = new SardineImpl(props.credentials.login, props.credentials.password, proxySelector) {
      override def createDefaultConnectionManager(schemeRegistry: Registry[ConnectionSocketFactory]): HttpClientConnectionManager = {
        val manager = new PoolingHttpClientConnectionManager(schemeRegistry)
        manager.setDefaultMaxPerRoute(maxConnections)
        manager.setMaxTotal(maxConnections * 2)
        manager
      }
    }
    sardine.enablePreemptiveAuthentication(props.address.uri.get.getHost)
    sardine.disableCompression()
    sardine
  }
}

class SardineRepository(props: StorageProps, sardine: Sardine)(implicit dispatcher: MessageDispatcher) extends PathTreeRepository {
  private[this] val rootUrl = props.address.uri.map(_.toString).getOrElse(throw new IllegalArgumentException("No WebDav URL"))
  private[this] val baseUrl = SardineRepository.getResourceURL(rootUrl, props.address.path)
  private[this] val cachedDirectories = TrieMap.empty[Path, DavResource]

  def read(path: Path) = {
    Source.single(path)
      .viaMat(AkkaStreamUtils.flatMapConcatMat { path ⇒
        val resourceUrl = SardineRepository.getResourceURL(baseUrl, path)
        StreamConverters.fromInputStream(() ⇒ sardine.get(resourceUrl))
      })(Keep.right)
      .mapMaterializedValue(_.map(rs ⇒ StorageUtils.foldIOResults(rs.map(StorageUtils.wrapAkkaIOResult(path, _)): _*)))
      .alsoToMat(StorageUtils.countPassedBytes(path).toMat(Sink.head)(Keep.right))(Keep.right)
      .withAttributes(ActorAttributes.dispatcher(dispatcher.id))
      .log("webdav-read")
      .named("webdavRead")
  }

  def write(path: Path) = {
    val resourceUrl = SardineRepository.getResourceURL(baseUrl, path)
    val sink = AkkaStreamUtils
      .writeInputStream { inputStream ⇒
        val result = Future {
          makeDirectories(sardine, path.parent)
          sardine.put(resourceUrl, inputStream)
          val result = sardine.list(resourceUrl, 0).asScala.head
          StorageIOResult.Success(path, result.getContentLength)
        }
        result.failed.foreach(_ ⇒ inputStream.close())
        Source.fromFuture(StorageUtils.wrapFuture(path, result))
      }
      .log("webdav-write")
      .toMat(Sink.head)(Keep.right)
      .withAttributes(ActorAttributes.dispatcher(dispatcher.id))
      .named("webdavWriteSink")

    Flow[Data]
      .toMat(sink)(Keep.right)
      .named("webdavWrite")
  }

  def delete = {
    Flow[Path]
      .map { path ⇒
        val resourceUrl = SardineRepository.getResourceURL(baseUrl, path)
        val size = sardine.list(resourceUrl, 0).asScala.head.getContentLength
        sardine.delete(resourceUrl)
        StorageIOResult.Success(path, size)
      }
      .via(StorageUtils.foldStream())
      // .log("webdav-delete")
      .toMat(Sink.head)(Keep.right)
      .withAttributes(ActorAttributes.supervisionStrategy(Supervision.resumingDecider) and ActorAttributes.dispatcher(dispatcher.id))
      .named("webdavDelete")
  }

  def subKeys(fromPath: Path) = {
    def listDirectory: Flow[Path, Vector[DavResource], NotUsed] = {
      Flow[Path]
        .map { path ⇒
          val resourceUrl = SardineRepository.getResourceURL(baseUrl, path)
          val resources = if (sardine.exists(resourceUrl)) {
            sardine.list(resourceUrl, 1).asScala.toVector
          } else {
            Vector.empty
          }
          resources
        }
        .withAttributes(ActorAttributes.dispatcher(dispatcher.id))
        .named("webdavList")
    }

    def traverseDirectory: Flow[Path, Path, NotUsed] = {
      Flow[Path]
        .log("webdav-traverse")
        .flatMapConcat { path ⇒
          Source.single(path).via(listDirectory).flatMapConcat { resources ⇒
            val (folders, files) = resources.partition(_.isDirectory)
            Source(files.map(_.getPath: Path))
              .concat(Source(folders.map(_.getPath: Path).filterNot(Path.equalsIgnoreCase(path, _))).via(traverseDirectory))
          }
        }
        .named("webdavTraverse")
    }

    Source.single(fromPath)
      .via(traverseDirectory)
      .map(_.toRelative(fromPath))
      .alsoToMat(StorageUtils.countPassedElements(fromPath).toMat(Sink.head)(Keep.right))(Keep.right)
      .withAttributes(ActorAttributes.dispatcher(dispatcher.id))
      .named("webdavKeys")
  }

  protected def makeDirectories(sardine: Sardine, path: Path): Unit = {
    def getCached(path: Path) = {
      cachedDirectories.getOrElseUpdate(path, sardine.list(SardineRepository.getResourceURL(rootUrl, path), 0).asScala.head)
    }

    Try(getCached(props.address.path / path)) match {
      case Success(directory) ⇒
        require(directory.isDirectory, "Not a directory")

      case Failure(_) ⇒
        def exists(path: Path): Boolean = {
          val resourceUrl = SardineRepository.getResourceURL(rootUrl, path)
          sardine.exists(resourceUrl)
        }

        def createDirectory(path: Path) = {
          val resourceUrl = SardineRepository.getResourceURL(rootUrl, path)
          sardine.createDirectory(resourceUrl)
        }

        def createDirectoryRec(path: Path): Unit = {
          if (path.isRoot) {
            // Ignore
          } else if (exists(path.parent)) {
            createDirectory(path)
          } else {
            createDirectoryRec(path.parent)
            createDirectory(path)
          }
        }

        createDirectoryRec(props.address.path / path)
    }
  }
}
