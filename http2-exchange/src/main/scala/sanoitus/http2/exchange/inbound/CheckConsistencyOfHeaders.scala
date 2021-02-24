package sanoitus.http2.exchange
package inbound

import scala.concurrent.stm._
import Error._

case object CheckConsistencyOfHeaders extends InboundCheck[Http2Frame] {

  override def apply(ctx: ProcessingContext, frame: Http2Frame)(implicit tx: InTxn): Option[Err] = {
    def fails(error: String) = connectionError(PROTOCOL_ERROR, error)

    val previousFrame = ctx.connection.previousFrame
    try {
      (previousFrame(), frame) match {
        case (h: Headers, _: Continuation) => if (h.endHeaders) fails("HEADERS(endHeaders=true), CONTINUATION") else ok
        case (h: Headers, _)               => if (h.endHeaders) ok else fails("HEADERS(endHeaders=false), !CONTINUATION")
        case (c: Continuation, _: Continuation) =>
          if (c.endHeaders) fails("CONTINUATION(endHeaders=true), CONTINUATION") else ok
        case (_, _: Continuation) => fails("!HEADERS, CONTINUATION")
        case (c: Continuation, _) => if (c.endHeaders) ok else fails("CONTINUATION(endHeaders=false), !CONTINUATION")
        case _                    => ok
      }
    } finally {
      previousFrame() = frame
    }
  }
}
