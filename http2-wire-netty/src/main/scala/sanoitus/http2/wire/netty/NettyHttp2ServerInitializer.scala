package sanoitus
package http2
package wire
package netty

import java.net.InetSocketAddress

import io.netty.buffer.ByteBuf
import io.netty.buffer.UnpooledByteBufAllocator
import io.netty.channel.ChannelInitializer
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandler
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.channel.socket.SocketChannel
import io.netty.handler.codec.ByteToMessageDecoder
import io.netty.handler.codec.http.DefaultFullHttpResponse
import io.netty.handler.codec.http.HttpContentCompressor
import io.netty.handler.codec.http.HttpRequest
import io.netty.handler.codec.http.HttpRequestDecoder
import io.netty.handler.codec.http.HttpResponseEncoder
import io.netty.handler.codec.http.HttpResponseStatus
import io.netty.handler.codec.http.HttpVersion
import io.netty.handler.logging.LoggingHandler
import io.netty.handler.logging.LogLevel
import io.netty.handler.ssl.SslContext
import io.netty.handler.ssl.SslHandler

class NettyHttp2ServerInitializer(sslCtx: SslContext, handler: ChannelInboundHandler)
    extends ChannelInitializer[SocketChannel] {

  class HttpsRedirectHandler extends ChannelInboundHandlerAdapter {
    override def channelReadComplete(ctx: ChannelHandlerContext): Unit =
      ctx.flush()

    override def channelRead(ctx: ChannelHandlerContext, msg: Object): Unit =
      if (msg.isInstanceOf[HttpRequest]) {
        val req = msg.asInstanceOf[HttpRequest]
        val response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.MOVED_PERMANENTLY)
        val addr = ctx.channel().localAddress()
        val redirect = if (addr.isInstanceOf[InetSocketAddress]) {
          val iaddr = addr.asInstanceOf[InetSocketAddress]
          "https://" + iaddr.getHostName + ":" + iaddr.getPort + req.uri
        } else {
          "https://" + addr.toString + req.uri
        }

        response.headers().set("Location", redirect)
        ctx.write(response)
      }

    override def exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable): Unit =
      ctx.close()
  }

  class SslDetector(sslCtx: SslContext) extends ByteToMessageDecoder {
    def decode(ctx: ChannelHandlerContext, in: ByteBuf, out: java.util.List[Object]): Unit = {
      val pipeline = ctx.pipeline()
      if (SslHandler.isEncrypted(in)) {
        pipeline.addLast("ssl", sslCtx.newHandler(UnpooledByteBufAllocator.DEFAULT))
        pipeline.addLast("http/2-server", handler)
      } else {
        pipeline.addLast("decoder", new HttpRequestDecoder())
        pipeline.addLast("encoder", new HttpResponseEncoder())
        pipeline.addLast("deflater", new HttpContentCompressor())
        pipeline.addLast("handler", new HttpsRedirectHandler())
      }
      pipeline.remove(this)
    }
  }

  def initChannel(ch: SocketChannel): Unit = {
    ch.pipeline().addLast(new LoggingHandler(LogLevel.DEBUG))
    ch.pipeline().addLast(new SslDetector(sslCtx))
  }
}
