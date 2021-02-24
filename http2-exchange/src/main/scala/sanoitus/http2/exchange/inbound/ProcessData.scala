package sanoitus.http2
package exchange
package inbound

import scala.concurrent.stm._
import sanoitus.http2.utils.Continue
import Error._

object ProcessData extends InboundPipeline[Data, Continue[Unit]] {

  private def checkAgainst(stream: Http2ExchangeStream, incomingLength: Int, endStream: Boolean, expected: Int)(
    implicit tx: InTxn
  ): Boolean = {
    val currentTotal = stream.inbound.dataTotal() + incomingLength
    (currentTotal < expected && !endStream) || (currentTotal == expected)
  }

  override def apply(ctx: ProcessingContext, data: Data)(implicit tx: InTxn): Either[Err, Continue[Unit]] = {
    val connection = ctx.connection
    val state = connection.getStateOf(data.stream)
    connection.streams().get(data.stream) match {
      case Some(stream) => {
        val headers = stream.inbound.getHeaders()

        lazy val lengthOk =
          headers
            .get("content-length")
            .map(expected => checkAgainst(stream, data.data.length, data.endStream, expected.toInt))
            .getOrElse(true)

        if (state == Closed || state == HalfClosedRemote) {
          connectionError(PROTOCOL_ERROR, s"DATA frame on ${state} stream")
        } else if (lengthOk) {
          ok(stream.inbound.writeData(data.data, data.endStream))
        } else {
          connectionError(PROTOCOL_ERROR, "Content length failure")
        }
      }
      case None => connectionError(PROTOCOL_ERROR, s"DATA frame on ${state} stream")
    }
  }
}
