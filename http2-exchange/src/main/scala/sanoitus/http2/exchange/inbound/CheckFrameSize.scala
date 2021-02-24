package sanoitus.http2.exchange
package inbound

import scala.concurrent.stm._
import Error._

object CheckFrameSize extends InboundCheck[Http2Frame] {
  override def apply(ctx: ProcessingContext, frame: Http2Frame)(implicit tx: InTxn): Option[Err] = {
    val frameSize = ctx.frame.length
    val maxSize = ctx.connection.localSettings.maxFrameSize()
    if (frameSize > maxSize) {
      connectionError(FRAME_SIZE_ERROR, s"Frame size too large ($frameSize), maximum allowed $maxSize")
    } else {
      ok
    }
  }
}
