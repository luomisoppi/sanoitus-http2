package sanoitus
package http2
package wire
package netty

import java.nio.charset.Charset
import java.nio.file.Files
import java.nio.file.Paths
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64

import io.netty.bootstrap.Bootstrap
import io.netty.bootstrap.ServerBootstrap
import io.netty.buffer.ByteBuf
import io.netty.channel.ChannelFuture
import io.netty.channel.ChannelFutureListener
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandler
import io.netty.channel.ChannelOption
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.nio.NioServerSocketChannel
import io.netty.channel.socket.nio.NioSocketChannel
import io.netty.handler.codec.http2.Http2SecurityUtil
import io.netty.handler.logging.LoggingHandler
import io.netty.handler.logging.LogLevel
import io.netty.handler.ssl.ApplicationProtocolConfig
import io.netty.handler.ssl.ApplicationProtocolConfig.Protocol
import io.netty.handler.ssl.ApplicationProtocolConfig.SelectedListenerFailureBehavior
import io.netty.handler.ssl.ApplicationProtocolConfig.SelectorFailureBehavior
import io.netty.handler.ssl.ApplicationProtocolNames
import io.netty.handler.ssl.SslContextBuilder
import io.netty.handler.ssl.SslContext
import io.netty.handler.ssl.SupportedCipherSuiteFilter

import scala.concurrent.stm._
import scala.jdk.CollectionConverters._

import sanoitus._
import sanoitus.http2.wire._
import sanoitus.http2.utils._

class NettyHttp2WireInterpreter(clientSslContext: SslContext, group: NioEventLoopGroup)
    extends Interpreter
    with Http2WireLanguage
    with ChannelInboundHandler { self =>

  override type Connection = ChannelWrapper

  def close(): Unit =
    group.shutdownGracefully()

  val clientConnections: TMap[String, ChannelHandlerContext] = TMap()

  val connections: TMap[String, ChannelWrapper] = TMap()

  val serverConnectionPairing: ExecutionPairing[Option[Resource[Connection]]] =
    new ExecutionPairing[Option[Resource[Connection]]] {}

  override def apply[A](op: Op[A]): Program[A] =
    op match {
      case GetConnection() =>
        for {
          conn <- effect[Option[Resource[Connection]]] { serverConnectionPairing.addExec(_) }
          _ <- conn match {
            case Some(c) => mapResources(_ + c)
            case None    => unit(())
          }
        } yield conn

      case Connect(host, port) => {
        effect[Either[ConnectionNotSuccessful, Resource[Connection]]] { exec =>

          val initializer = new Http2ClientInitializer(clientSslContext, self)
          val b = new Bootstrap()
          b.group(group)
          b.channel(classOf[NioSocketChannel])
          b.remoteAddress(host, port)
          b.handler(initializer)
          val channelFuture = b.connect()

          val listener = new ChannelFutureListener() {
            override def operationComplete(future: ChannelFuture): Unit =
              if (future.isSuccess()) {
                val channel = future.channel()
                val channelId = channel.id().asLongText()
                val (ctx, wrapper) = atomic { implicit tx =>
                  val ctx = clientConnections.get(channelId).get
                  clientConnections -= channelId
                  val wrapper = new ChannelWrapper(false, ctx)
                  connections += ((channelId, wrapper))
                  (ctx, wrapper)
                }

                val buf =
                  ctx.alloc().buffer(NettyHttp2WireInterpreter.preface.length, NettyHttp2WireInterpreter.preface.length)

                buf.setBytes(0, NettyHttp2WireInterpreter.preface)
                buf.readerIndex(0)
                buf.writerIndex(NettyHttp2WireInterpreter.preface.length)
                channel.writeAndFlush(buf)

                val resource = new Resource[ChannelWrapper] {
                  override val value = wrapper
                  override val index = None
                  override def close() = effect[Unit] { _ =>
                    Some(wrapper.close())
                  }
                }

                exec.mapResources(_ + resource).proceed(Right(resource))
              } else {
                exec.proceed(Left(ConnectionFailed))
              }
          }
          channelFuture.addListener(listener)
          None
        }
      }

      case WriteFrame(connection, frame, frames) => {
        effect[Boolean] { exec =>
          connection.write(exec, frame :: frames)
          None
        }
      }

      case ReadFrame(connection) => {
        effect[Option[Frame]] { connection.framePairing.addExec }
      }
    }

  override def channelActive(ctx: ChannelHandlerContext): Unit = {}

  def channelInactive(ctx: ChannelHandlerContext): Unit = {}

  def channelRead(ctx: ChannelHandlerContext, msg: Any): Unit = {
    val channel = ctx.channel()
    val channelId = channel.id().asLongText()
    val wrapper = atomic { implicit tx =>
      connections.get(channelId).get
    }
    val input = msg.asInstanceOf[ByteBuf]

    wrapper.moreData(input)

    input.release()
  }

  override def channelReadComplete(ctx: ChannelHandlerContext): Unit = {}

  override def channelRegistered(ctx: ChannelHandlerContext): Unit = {}

  override def channelUnregistered(ctx: ChannelHandlerContext): Unit = {}

  override def channelWritabilityChanged(ctx: ChannelHandlerContext): Unit = {}

  override def exceptionCaught(ctx: ChannelHandlerContext, t: Throwable): Unit = {}

  override def handlerAdded(ctx: ChannelHandlerContext): Unit = {
    val channel = ctx.channel()
    val channelId = channel.id().asLongText()
    val server = ctx.name() == "http/2-server"

    if (!server) atomic { implicit tx =>
      clientConnections += ((channelId, ctx))
    }
    else {
      val res = atomic { implicit tx =>
        val wrapper = new ChannelWrapper(true, ctx)
        connections += ((channelId, wrapper))
        new Resource[ChannelWrapper] {
          override val value = wrapper
          override val index = None
          override def close() = effect[Unit] { _ =>
            Some(wrapper.close())
          }
        }
      }
      serverConnectionPairing.addValue(Some(res)).foreach(_.mapResources(_ + res).proceed(Some(res)))
    }
  }

  override def handlerRemoved(ctx: ChannelHandlerContext): Unit = {
    val channel = ctx.channel()
    val channelId = channel.id().asLongText()
    val conn = atomic { implicit tx => connections.remove(channelId) }
    conn.foreach(_.noMoreIncoming())
  }

  override def userEventTriggered(ctx: ChannelHandlerContext, event: Any): Unit = {}
}

object NettyHttp2WireInterpreter {

  val preface = "PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n".getBytes(Charset.forName("UTF-8"))

  def createClientSslContext(trusted: X509Certificate*): SslContext = {
    val sslContextBuilder = SslContextBuilder
      .forClient()
      .applicationProtocolConfig(
        new ApplicationProtocolConfig(Protocol.ALPN,
                                      SelectorFailureBehavior.NO_ADVERTISE,
                                      SelectedListenerFailureBehavior.ACCEPT,
                                      ApplicationProtocolNames.HTTP_2)
      )
    trusted.foreach(sslContextBuilder.trustManager(_))
    sslContextBuilder.build()
  }

  def apply(trusted: X509Certificate*): NettyHttp2WireInterpreter =
    new NettyHttp2WireInterpreter(createClientSslContext(trusted: _*), new NioEventLoopGroup())

  def apply(certFile: String): NettyHttp2WireInterpreter = {
    val certStream = getClass.getResourceAsStream(certFile)
    val trusted = CertificateFactory.getInstance("X.509").generateCertificate(certStream).asInstanceOf[X509Certificate]
    NettyHttp2WireInterpreter(trusted)
  }

  def apply(port: Integer): NettyHttp2WireInterpreter = {
    val (privateKey, keyCert) = CertificateCreator.create()
    NettyHttp2WireInterpreter(port, privateKey, keyCert)
  }

  def apply(port: Integer, certFile: String, keyFile: String): NettyHttp2WireInterpreter = {
    val pem = Files
      .readAllLines(Paths.get(getClass.getResource(keyFile).toURI()))
      .asScala
      .toList
      .filter(!_.startsWith("-"))
      .filter(!_.isBlank())
      .mkString

    val keyBytes = Base64.getDecoder.decode(pem)
    val spec = new PKCS8EncodedKeySpec(keyBytes)
    val kf = KeyFactory.getInstance("RSA")
    val privateKey = kf.generatePrivate(spec)

    val certStream = getClass.getResourceAsStream(certFile)
    val x509 = CertificateFactory.getInstance("X.509").generateCertificate(certStream).asInstanceOf[X509Certificate]
    NettyHttp2WireInterpreter(port, privateKey, x509)
  }

  def apply(port: Integer,
            privateKey: PrivateKey,
            keyCert: X509Certificate,
            keyCertChain: X509Certificate*): NettyHttp2WireInterpreter = {
    val defaultCiphers = Http2SecurityUtil.CIPHERS.asScala.toList

    val serverSslContext = SslContextBuilder
      .forServer(privateKey, keyCert :: keyCertChain.toList: _*)
      .ciphers(defaultCiphers.asJava, SupportedCipherSuiteFilter.INSTANCE)
      .applicationProtocolConfig(
        new ApplicationProtocolConfig(Protocol.ALPN,
                                      SelectorFailureBehavior.NO_ADVERTISE,
                                      SelectedListenerFailureBehavior.ACCEPT,
                                      ApplicationProtocolNames.HTTP_2)
      )
      .build()

    val group = new NioEventLoopGroup()
    val interpreter = new NettyHttp2WireInterpreter(serverSslContext, group)

    val initializer = new NettyHttp2ServerInitializer(serverSslContext, interpreter)
    val bootstrap = new ServerBootstrap()
    bootstrap.option(ChannelOption.SO_BACKLOG.asInstanceOf[ChannelOption[Any]], 1024)
    bootstrap
      .group(group)
      .channel(classOf[NioServerSocketChannel])
      .handler(new LoggingHandler(LogLevel.INFO))
      .childHandler(initializer)

    bootstrap.bind(port).sync().channel()
    interpreter
  }
}
