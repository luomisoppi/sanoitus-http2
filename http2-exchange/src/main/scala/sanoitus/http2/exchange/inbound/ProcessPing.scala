package sanoitus.http2
package exchange
package inbound

import scala.concurrent.stm._
import sanoitus.http2.utils.Continue

object ProcessPing extends InboundPipeline[Ping, Continue[Unit]] {
  override def apply(ctx: ProcessingContext, ping: Ping)(implicit tx: InTxn): Either[Err, Continue[Unit]] =
    if (ping.ack) {
      ok
    } else {
      ok(ctx.connection.control.pong(ping.data))
    }
}
