package sanoitus.http2.exchange
package inbound

import scala.concurrent.stm._
import Error._

object CheckStreamConcurrencyLimit extends InboundCheck[Headers] {

  override def apply(ctx: ProcessingContext, h: Headers)(implicit tx: InTxn): Option[Err] = {
    val connection = ctx.connection
    if (connection.streams().get(h.stream).isEmpty /* trailers ok */
        && connection.streams().size >= connection.localSettings.maxConcurrentStreams()) {
      connectionError(REFUSED_STREAM,
                      s"Concurrent stream limit (${connection.localSettings.maxConcurrentStreams()} reached")
    } else {
      ok
    }
  }
}
