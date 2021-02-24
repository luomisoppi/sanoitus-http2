package sanoitus
package http2.exchange

import scala.concurrent.stm._
import sanoitus.http2.utils._

trait OutboundExchange extends Outbound {

  val stream: Http2ExchangeStream
  val window: Ref[Int]
  val headersConsumed = Ref(false)
  val headers: Ref[Option[List[(String, String)]]]
  val data: Ref[Option[Array[Byte]]] = Ref(None)
  val isComplete: Ref[Boolean]
  val writer: Ref[Option[Suspended[Boolean]]]

  override def dataBytesAvailable(implicit tx: InTxn) =
    data().map(_.length)

  override def consume(max: Int)(implicit tx: InTxn): Http2StreamData =
    if (!headersConsumed()) {
      val h = headers() match {
        case None => None
        case Some(headers) => {
          headersConsumed() = true
          Some(headers)
        }
      }
      Http2StreamData(
        stream.id,
        controlFrames.swap(List()),
        h.map(h =>
          Http2ExchangeData(
            Left(h),
            connection.remoteSettings.maxFrameSize(),
            writer.swap(None),
            isComplete() && data().isEmpty
          )
        )
      )
    } else {
      data() match {
        case Some(bytes) => {
          val (head, tail, wasAll, w) = if (bytes.length > max) {
            (bytes.take(max), Some(bytes.drop(max)), false, None)
          } else {
            (bytes, None, true, writer.swap(None))
          }

          window.transform(_ - head.length)
          connection.outboundWindow.transform(_ - head.length)

          data() = tail
          Http2StreamData(
            stream.id,
            controlFrames.swap(List()),
            Some(Http2ExchangeData(Right(head), connection.remoteSettings.maxFrameSize(), w, isComplete() && wasAll))
          )
        }

        case None => Http2StreamData(stream.id, controlFrames.swap(List()), None)
      }
    }

  def cancel(implicit tx: InTxn): Continue[Unit] = {
    isComplete() = true
    headersConsumed() = true
    connection.streams.transform(_ - stream.id)
    data() = None
    connection.outboundFlowControl.ready.transform(_ - this)
    Continue((), writer.swap(None).toList.map(Resumed(_, Right(false))))
  }

  def writeData(bytes: Array[Byte], end: Boolean): Program[Boolean] = schedulingEffect[Boolean] { sus => implicit tx =>
    {
      if (isComplete()) {
        Continue(false)
      } else if (writer().isDefined) {
        throw new IllegalStateException()
      } else {
        writer() = Some(sus)
        data() = Some(bytes)
        if (end) {
          isComplete() = true
          if (stream.getState() == Closed) {
            connection.streams.transform(_ - stream.id)
          }
        }
        connection.outboundFlowControl.produced(this).suspend[Boolean]
      }
    }
  }

  def windowUpdate(amount: Int)(implicit tx: InTxn): Continue[Unit] = {
    window.transform(_ + amount)
    writeControlFrame(WindowUpdate(stream.id, amount))
  }
}
