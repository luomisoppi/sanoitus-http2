package sanoitus.http2
package exchange
package inbound

import scala.concurrent.stm._
import sanoitus.http2.utils.Continue
import Error._

object ProcessWindowUpdate extends InboundPipeline[WindowUpdate, Continue[Unit]] {
  override def apply(ctx: ProcessingContext, update: WindowUpdate)(implicit tx: InTxn): Either[Err, Continue[Unit]] = {
    val connection = ctx.connection
    connection.streams().get(update.stream) match {
      case Some(stream) => {
        if (stream.outbound.window() + update.increment < stream.outbound.window()) {
          streamError(FLOW_CONTROL_ERROR, s"Stream ${stream.id} flow control window set above 2^32-1")
        } else {
          stream.outbound.window.transform(_ + update.increment)
          ok(connection.outboundFlowControl.produced(stream.outbound))
        }
      }
      case None if update.stream == 0 => {
        val connectionWindow = connection.outboundWindow()
        if (connectionWindow + update.increment < connectionWindow) {
          connectionError(FLOW_CONTROL_ERROR, s"Connection flow control window set above 2^32-1")
        } else {
          connection.outboundWindow.transform(_ + update.increment)
          ok(connection.streams().values.foldLeft(Continue(())) { (acc, a) =>
            acc.flatMap(_ => connection.outboundFlowControl.produced(a.outbound))
          })
        }
      }
      case None if connection.getStateOf(update.stream) == Closed => ok(Continue(()))
      case None                                                   => connectionError(PROTOCOL_ERROR, "WINDOW_UPDATE frame on idle stream")

    }
  }
}
