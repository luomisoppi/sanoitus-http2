package sanoitus.http2
package exchange
package inbound

import scala.concurrent.stm._
import sanoitus.http2.utils.Continue

object ProcessPriority extends InboundPipeline[Priority, Continue[Unit]] {
  override def apply(ctx: ProcessingContext, prio: Priority)(implicit tx: InTxn): Either[Err, Continue[Unit]] =
    ok
}
