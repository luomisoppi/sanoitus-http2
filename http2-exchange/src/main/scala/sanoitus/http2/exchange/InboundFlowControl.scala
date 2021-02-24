package sanoitus.http2
package exchange

import scala.concurrent.stm._
import sanoitus.http2.utils._

case class InboundFlowControl(connection: Connection) {

  val buffered: Ref[Int] = Ref(0)

  def consumed(stream: Http2ExchangeStream, amount: Int)(implicit tx: InTxn): Continue[Unit] = {

    val streamWindow = stream.inbound.window()
    val streamBuffered = stream.inbound.data().length
    val streamMax = connection.localSettings.initialWindowSize()
    val streamCouldBe = streamMax - streamBuffered

    val streamUpdate =
      if (streamWindow.toFloat / streamCouldBe < 0.25 && !stream.inbound.isComplete())
        stream.outbound.windowUpdate(streamCouldBe - streamWindow)
      else
        Continue(())

    buffered.transform(_ - amount)

    val connectionWindow = connection.inboundWindow()
    val connectionBuffered = buffered()
    val connectionMax = connection.inboundBufferSize
    val connectionCouldBe = connectionMax - connectionBuffered

    val connectionUpdate =
      if (connectionWindow.toFloat / connectionCouldBe < 0.25)
        connection.control.windowUpdate(connectionCouldBe - connectionWindow)
      else
        Continue(())

    for {
      _ <- streamUpdate
      _ <- connectionUpdate
    } yield ()
  }
}
