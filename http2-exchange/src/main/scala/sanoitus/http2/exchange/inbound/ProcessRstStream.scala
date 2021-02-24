package sanoitus.http2
package exchange
package inbound

import scala.concurrent.stm._
import sanoitus.http2.utils.Continue
import Error._

object ProcessRstStream extends InboundPipeline[RstStream, Continue[Unit]] {
  override def apply(ctx: ProcessingContext, rst: RstStream)(implicit tx: InTxn): Either[Err, Continue[Unit]] = {
    val connection = ctx.connection
    val stream = connection.streams().get(rst.stream)
    val state = connection.getStateOf(rst.stream)

    if (rst.stream == 0) {
      connectionError(PROTOCOL_ERROR, "RST_STREAM frame on stream 0")
    } else {
      stream match {
        case Some(stream) => ok(stream.remoteRst())
        case None         => connectionError(PROTOCOL_ERROR, s"RST_STREAM frame on $state stream ${rst.stream}")
      }
    }
  }
}
