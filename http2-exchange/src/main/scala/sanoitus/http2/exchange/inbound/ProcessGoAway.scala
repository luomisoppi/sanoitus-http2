package sanoitus.http2
package exchange
package inbound

import scala.concurrent.stm._
import sanoitus.http2.utils.Continue

object ProcessGoAway extends InboundPipeline[GoAway, Continue[Unit]] {
  override def apply(ctx: ProcessingContext, goAway: GoAway)(implicit tx: InTxn): Either[Err, Continue[Unit]] = {
    val close = ctx.connection.streams().values.foldLeft(Continue(())) { (acc, a) => acc.flatMap(_ => a.remoteRst()) }
    ok(close)
  }
}
