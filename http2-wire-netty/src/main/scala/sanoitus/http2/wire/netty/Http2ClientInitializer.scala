package sanoitus.http2.wire.netty

import io.netty.buffer.UnpooledByteBufAllocator
import io.netty.channel.ChannelInitializer
import io.netty.channel.socket.SocketChannel
import io.netty.channel.ChannelInboundHandler
import io.netty.handler.codec.http2.Http2FrameLogger
import io.netty.handler.logging.LogLevel
import io.netty.handler.ssl.SslContext

class Http2ClientInitializer(sslCtx: SslContext, responseHandler: ChannelInboundHandler)
    extends ChannelInitializer[SocketChannel] {

  val logger = new Http2FrameLogger(LogLevel.DEBUG, classOf[Http2ClientInitializer])

  override def initChannel(ch: SocketChannel): Unit = {
    val pipeline = ch.pipeline()
    pipeline.addLast("ssl", sslCtx.newHandler(UnpooledByteBufAllocator.DEFAULT))
    pipeline.addLast("http/2-client", responseHandler)
  }
}
