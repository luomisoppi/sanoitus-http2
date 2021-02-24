package sanoitus.http2.exchange

import scala.concurrent.stm._
import sanoitus.http2.utils.Continue

trait Http2ExchangeStream {

  type In <: InboundExchange
  type Out <: OutboundExchange

  val id: Int
  val inbound: In
  val outbound: Out

  def getState()(implicit tx: InTxn) =
    (inbound.isComplete(), outbound.isComplete()) match {
      case (true, true)   => Closed
      case (true, false)  => HalfClosedRemote
      case (false, true)  => HalfClosedLocal
      case (false, false) => Open
    }

  def remoteRst()(implicit tx: InTxn): Continue[Unit] = {
    val inboundC =
      if (!inbound.isComplete()) {
        val removed = inbound.data.swap(new Array(0))
        inbound.writeData(new Array(0), true)
        inbound.flowControl.consumed(this, removed.length)
      } else {
        Continue(())
      }

    for {
      _ <- inboundC
      _ <- outbound.cancel
    } yield ()
  }

  def rst(code: Error.Code)(implicit tx: InTxn): Continue[Unit] = {
    outbound.isComplete() = true
    outbound.headersConsumed() = true
    outbound.data() = None
    outbound.connection.streams.transform(_ - id)
    outbound.writeControlFrame(RstStream(id, code))
  }

  def closeRequest() = ()
  def closeResponse() = ()
}
