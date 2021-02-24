package sanoitus.http2.exchange
package inbound

import scala.concurrent.stm._
import sanoitus.http2.utils.Continue

object ProcessHeaders extends InboundPipeline[Map[String, String], Continue[Unit]] {

  override def apply(ctx: ProcessingContext, headers: Map[String, String])(implicit tx: InTxn) =
    ctx.connection.processHeaders(ctx.frame.stream, headers)
}
