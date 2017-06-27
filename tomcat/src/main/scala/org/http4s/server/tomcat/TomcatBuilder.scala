package org.http4s
package server
package tomcat

import java.net.InetSocketAddress
import java.util
import java.util.concurrent.ExecutorService
import javax.servlet.http.HttpServlet
import javax.servlet.{DispatcherType, Filter}

import cats.effect._
import org.apache.catalina.{Context, Lifecycle, LifecycleEvent, LifecycleListener}
import org.apache.catalina.startup.Tomcat
import org.apache.tomcat.util.descriptor.web.{FilterDef, FilterMap}
import org.http4s.server.SSLKeyStoreSupport.StoreInfo
import org.http4s.servlet.{Http4sServlet, ServletContainer, ServletIo}
import org.http4s.util.threads.DefaultPool

import scala.collection.JavaConverters._
import scala.concurrent.duration._

sealed class TomcatBuilder[F[_]: Effect] private (
  socketAddress: InetSocketAddress,
  private val serviceExecutor: ExecutorService,
  private val idleTimeout: Duration,
  private val asyncTimeout: Duration,
  private val servletIo: ServletIo[F],
  sslBits: Option[KeyStoreBits],
  mounts: Vector[Mount[F]]
)
  extends ServletContainer[F]
  with ServerBuilder[F]
  with IdleTimeoutSupport[F]
  with SSLKeyStoreSupport[F] {

  private val F = Effect[F]
  type Self = TomcatBuilder[F]

  private def copy(
    socketAddress: InetSocketAddress = socketAddress,
    serviceExecutor: ExecutorService = serviceExecutor,
    idleTimeout: Duration = idleTimeout,
    asyncTimeout: Duration = asyncTimeout,
    servletIo: ServletIo[F] = servletIo,
    sslBits: Option[KeyStoreBits] = sslBits,
    mounts: Vector[Mount[F]] = mounts
  ): TomcatBuilder[F] =
    new TomcatBuilder(socketAddress, serviceExecutor, idleTimeout, asyncTimeout, servletIo, sslBits, mounts)

  override def withSSL(keyStore: StoreInfo, keyManagerPassword: String, protocol: String, trustStore: Option[StoreInfo], clientAuth: Boolean): Self = {
    copy(sslBits = Some(KeyStoreBits(keyStore, keyManagerPassword, protocol, trustStore, clientAuth)))
  }

  override def bindSocketAddress(socketAddress: InetSocketAddress): Self =
    copy(socketAddress = socketAddress)

  override def withServiceExecutor(serviceExecutor: ExecutorService): Self =
    copy(serviceExecutor = serviceExecutor)

  override def mountServlet(servlet: HttpServlet, urlMapping: String, name: Option[String] = None): Self =
    copy(mounts = mounts :+ Mount[F] { (ctx, index, _) =>
      val servletName = name.getOrElse(s"servlet-$index")
      val wrapper = Tomcat.addServlet(ctx, servletName, servlet)
      wrapper.addMapping(urlMapping)
      wrapper.setAsyncSupported(true)
    })

  override def mountFilter(filter: Filter,
                           urlMapping: String,
                           name: Option[String],
                           dispatches: util.EnumSet[DispatcherType]): Self =
    copy(mounts = mounts :+ Mount[F] { (ctx, index, _) =>
      val filterName = name.getOrElse(s"filter-$index")

      val filterDef = new FilterDef
      filterDef.setFilterName(filterName)
      filterDef.setFilter(filter)
      filterDef.setAsyncSupported(true.toString)
      ctx.addFilterDef(filterDef)

      val filterMap = new FilterMap
      filterMap.setFilterName(filterName)
      filterMap.addURLPattern(urlMapping)
      dispatches.asScala.foreach { dispatcher =>
        filterMap.setDispatcher(dispatcher.name)
      }
      ctx.addFilterMap(filterMap)
    })

  override def mountService(service: HttpService[F], prefix: String): Self =
    copy(mounts = mounts :+ Mount[F] { (ctx, index, builder) =>
      val servlet = new Http4sServlet(
        service = service,
        asyncTimeout = builder.asyncTimeout,
        servletIo = builder.servletIo,
        threadPool = builder.serviceExecutor
      )
      val wrapper = Tomcat.addServlet(ctx, s"servlet-$index", servlet)
      wrapper.addMapping(ServletContainer.prefixMapping(prefix))
      wrapper.setAsyncSupported(true)
    })

  /*
   * Tomcat maintains connections on a fixed interval determined by the global
   * attribute worker.maintain with a default interval of 60 seconds. In the worst case the connection
   * may not timeout for an additional 59.999 seconds from the specified Duration
   */
  override def withIdleTimeout(idleTimeout: Duration): Self =
    copy(idleTimeout = idleTimeout)

  override def withAsyncTimeout(asyncTimeout: Duration): Self =
    copy(asyncTimeout = asyncTimeout)

  override def withServletIo(servletIo: ServletIo[F]): Self =
    copy(servletIo = servletIo)

  override def start: F[Server[F]] = F.delay {
    val tomcat = new Tomcat

    val context = tomcat.addContext("", getClass.getResource("/").getPath)

    val conn = tomcat.getConnector()

    sslBits.foreach { sslBits =>
      conn.setSecure(true)
      conn.setScheme("https")
      conn.setAttribute("keystoreFile", sslBits.keyStore.path)
      conn.setAttribute("keystorePass", sslBits.keyStore.password)
      conn.setAttribute("keyPass", sslBits.keyManagerPassword)

      conn.setAttribute("clientAuth", sslBits.clientAuth)
      conn.setAttribute("sslProtocol", sslBits.protocol)

      sslBits.trustStore.foreach { ts =>
        conn.setAttribute("truststoreFile", ts.path)
        conn.setAttribute("truststorePass", ts.password)
      }

      conn.setPort(socketAddress.getPort)

      conn.setAttribute("SSLEnabled", true)
    }

    conn.setAttribute("address", socketAddress.getHostString)
    conn.setPort(socketAddress.getPort)
    conn.setAttribute("connection_pool_timeout",
      if (idleTimeout.isFinite) idleTimeout.toSeconds.toInt else 0)

    val rootContext = tomcat.getHost.findChild("").asInstanceOf[Context]
    for ((mount, i) <- mounts.zipWithIndex)
      mount.f(rootContext, i, this)

    tomcat.start()

    new Server[F] {
      override def shutdown: F[Unit] =
        F.delay {
          tomcat.stop()
          tomcat.destroy()
        }

      override def onShutdown(f: => Unit): this.type = {
        tomcat.getServer.addLifecycleListener(new LifecycleListener {
          override def lifecycleEvent(event: LifecycleEvent): Unit = {
            if (Lifecycle.AFTER_STOP_EVENT.equals(event.getLifecycle))
              f
          }
        })
        this
      }

      lazy val address: InetSocketAddress = {
        val host = socketAddress.getHostString
        val port = tomcat.getConnector.getLocalPort
        new InetSocketAddress(host, port)
      }      
    }
  }
}

object TomcatBuilder {

  def apply[F[_]: Effect]: TomcatBuilder[F] =
    new TomcatBuilder[F](
      socketAddress = ServerBuilder.DefaultSocketAddress,
      // TODO fs2 port
      // This is garbage how do we shut this down I just want it to compile argh
      serviceExecutor = DefaultPool,
      idleTimeout = IdleTimeoutSupport.DefaultIdleTimeout,
      asyncTimeout = AsyncTimeoutSupport.DefaultAsyncTimeout,
      servletIo = ServletContainer.DefaultServletIo[F],
      sslBits = None,
      mounts = Vector.empty
    )
}

private final case class Mount[F[_]](f: (Context, Int, TomcatBuilder[F]) => Unit)

