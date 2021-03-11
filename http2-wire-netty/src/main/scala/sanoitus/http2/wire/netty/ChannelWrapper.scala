package sanoitus
package http2.wire
package netty

import io.netty.buffer.ByteBuf
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelFuture
import io.netty.channel.ChannelFutureListener

import scala.math._

import sanoitus.http2.utils._

class ChannelWrapper(server: Boolean, ctx: ChannelHandlerContext) {

  private lazy val alloc = ctx.alloc()

  private lazy val preface = NettyHttp2WireInterpreter.preface

  private lazy val headerBuf: ByteBuf = alloc.buffer(9, 9)

  private var header: Array[Byte] = new Array[Byte](0)

  private var nextLength: Option[Int] = if (server) Some(preface.length) else None

  private lazy val nextData: ByteBuf = alloc.buffer(pow(2, 14).intValue - 1, pow(2, 24).intValue - 1)

  lazy val framePairing = new ExecutionPairing[Option[Frame]]() {}

  def moreData(input: ByteBuf): Unit =
    while (input.readableBytes() > 0) {
      val byteCount = input.readableBytes()

      if (nextLength.isEmpty) {
        if (headerBuf.writableBytes() > byteCount) {
          headerBuf.writeBytes(input, byteCount)
        } else {
          headerBuf.writeBytes(input, headerBuf.writableBytes())
          header = new Array[Byte](9)
          headerBuf.readBytes(header)
          val length = header.uint(0, 3)
          nextLength = Some(length)
          headerBuf.resetReaderIndex()
          headerBuf.resetWriterIndex()

          nextData.resetReaderIndex()
          nextData.resetWriterIndex()
          if (nextData.capacity() < length) {
            nextData.capacity(length)
          }
        }
      }

      val readable = input.readableBytes()

      if (nextLength.isDefined) {
        val length = nextLength.get

        if (readable < length - nextData.writerIndex()) {
          nextData.writeBytes(input, readable)
        } else {
          nextData.writeBytes(input, length - nextData.writerIndex())
          val array = new Array[Byte](length)
          nextData.readBytes(array)
          if (header.length != 0) {
            val frame = Frame(
              header.uint(0, 3),
              header.uint(3, 1),
              header.uint(4, 1),
              header(5).&(0x80) >>> 7,
              header.uint(5, 4) & 0x7fffffff,
              array
            )
            framePairing.addValue(Some(frame)).foreach(_.proceed(Some(frame)))
          } else {
            if (!preface.sameElements(array)) {
              close()
              return
            }
          }
          nextLength = None
        }
      }
    }

  def write(suspended: Suspended[Boolean], frames: List[Frame]): Unit =
    if (framePairing.closed.single().isDefined) {
      suspended.proceed(false)
    } else {
      val totalLength = frames.map(_.length + 9).sum
      val buffer = alloc.buffer(totalLength, totalLength)
      frames.foreach { frame =>
        buffer.writeBytes(frame.length.bytes(3))
        buffer.writeByte(frame.frameType)
        buffer.writeByte(frame.flags)
        buffer.writeBytes(((frame.reserved << 31) | frame.stream).bytes(4))
        buffer.writeBytes(frame.content)
      }
      ctx
        .writeAndFlush(buffer)
        .addListener(new ChannelFutureListener() {
          override def operationComplete(future: ChannelFuture): Unit =
            suspended.proceed(future.isSuccess())
        })
    }

  def noMoreIncoming(): Unit =
    framePairing.close(None).foreach(_.proceed(None))

  def close(): Unit =
    ctx
      .close()
      .addListener(new ChannelFutureListener() {
        override def operationComplete(future: ChannelFuture): Unit = {
          noMoreIncoming()

          if (headerBuf.refCnt() > 0)
            headerBuf.release()

          if (nextData.refCnt() > 0)
            nextData.release()

          if (!future.isSuccess()) {
            println("Failed to close connection")
            future.cause().printStackTrace()
          }
        }
      })

  override def toString = s"ChannelWrapper(${ctx.channel().id().asShortText()})"
}
