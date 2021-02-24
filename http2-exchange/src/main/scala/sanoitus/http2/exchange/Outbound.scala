package sanoitus.http2.exchange

import scala.concurrent.stm._
import sanoitus.http2.utils._

trait Outbound {

  val id: Int
  val controlFrames: Ref[List[Http2ControlFrame]] = Ref(List())
  val connection: Connection

  def consume(max: Int)(implicit tx: InTxn): Http2StreamData

  def dataBytesAvailable(implicit tx: InTxn): Option[Int]

  def writeControlFrame(frame: Http2ControlFrame)(implicit tx: InTxn): Continue[Unit] = {
    controlFrames.transform(_ :+ frame)
    connection.outboundFlowControl.produced(this)
  }
}
