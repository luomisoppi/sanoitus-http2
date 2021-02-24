package sanoitus
package http2.exchange

import scala.concurrent.stm._
import sanoitus.http2.utils.Continue

case class OutboundControl(override val connection: Connection) extends Outbound {

  override val id = 0
  override val controlFrames: Ref[List[Http2ControlFrame]] = Ref(List())

  def consume(max: Int)(implicit tx: InTxn): Http2StreamData =
    Http2StreamData(0, controlFrames.swap(List()), None)

  def dataBytesAvailable(implicit tx: InTxn): Option[Int] = None

  def settings(settings: ConnectionSettings)(implicit tx: InTxn): Continue[Unit] = {
    val values: Map[Setting, Int] = Map(
      HEADER_TABLE_SIZE -> settings.headerTableSize(),
      ENABLE_PUSH -> (if (settings.enablePush()) 1 else 0),
      MAX_CONCURRENT_STREAMS -> settings.maxConcurrentStreams(),
      INITIAL_WINDOW_SIZE -> settings.initialWindowSize(),
      MAX_FRAME_SIZE -> settings.maxFrameSize(),
      MAX_HEADER_LIST_SIZE -> settings.maxHeaderListSize()
    )
    writeControlFrame(Settings(Some(values)))
  }

  def ackSettings(implicit tx: InTxn): Continue[Unit] =
    writeControlFrame(Settings(None))

  def pong(data: Array[Byte])(implicit tx: InTxn): Continue[Unit] =
    writeControlFrame(Ping(true, data))

  def goAway(code: Error.Code)(implicit tx: InTxn): Continue[Unit] =
    writeControlFrame(GoAway(connection.highestStreamId().max(0), code, None))

  def windowUpdate(amount: Int)(implicit tx: InTxn): Continue[Unit] = {
    connection.inboundWindow.transform(_ + amount)
    writeControlFrame(WindowUpdate(0, amount))
  }
}
