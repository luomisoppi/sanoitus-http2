package sanoitus
package http2.exchange

import scala.concurrent.stm._
import sanoitus.http2.utils._

trait InboundExchange {
  val connection: Connection
  val stream: Http2ExchangeStream
  val data: Ref[Array[Byte]] = Ref(new Array(0))
  val reader: Ref[Option[Suspended[Array[Byte]]]] = Ref(None)
  val flowControl = connection.inboundFlowControl
  val isComplete: Ref[Boolean]
  val dataTotal = Ref(0)
  val window: Ref[Int]
  val closed: Ref[Boolean] = Ref(false)

  def getHeaders(): Map[String, String]

  def close(implicit tx: InTxn): Continue[Unit] = {
    closed() = true
    Continue((), reader.swap(None).toList.map(Resumed(_, Right(new Array[Byte](0)))))
  }

  val readData: Program[Array[Byte]] = schedulingEffect[Array[Byte]] { sus => implicit tx =>
    {
      if (closed()) {
        Continue(new Array[Byte](0))
      } else if (data().isEmpty) {
        if (isComplete()) {
          Continue(new Array[Byte](0))
        } else {
          reader() = Some(sus)
          Suspend()
        }
      } else {
        val bytes = data.swap(new Array(0))
        flowControl.consumed(stream, bytes.length).map(_ => bytes)
      }
    }
  }

  def writeData(bytes: Array[Byte], end: Boolean)(implicit tx: InTxn): Continue[Unit] = {
    if (end) {
      isComplete() = true
      if (stream.getState() == Closed) {
        connection.streams.transform(_ - stream.id)
      }
    }

    dataTotal.transform(_ + bytes.length)

    window.transform(_ - bytes.length)
    connection.inboundWindow.transform(_ - bytes.length)

    if (bytes.length > 0 || isComplete()) {
      reader.swap(None) match {
        case Some(suspended) => {
          flowControl.consumed(stream, bytes.length).resumeMap(Resumed(suspended, Right(bytes)) :: _)
        }
        case None => {
          data.transform(_ ++ bytes)
          flowControl.buffered.transform(_ + bytes.length)
          Continue(())
        }
      }
    } else {
      Continue(())
    }
  }
}
