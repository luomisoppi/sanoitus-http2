package sanoitus.http2.exchange
package inbound

import scala.concurrent.stm._
import Error._

case object CheckPriority extends InboundCheck[Http2Frame] {

  def check(stream: Int, priority: PriorityBlock) =
    if (priority.dependency == stream) {
      connectionError(PROTOCOL_ERROR, "PRIORITY depends on itself")
    } else {
      ok
    }

  override def apply(ctx: ProcessingContext, frame: Http2Frame)(implicit tx: InTxn): Option[Err] =
    frame match {
      case h: Headers  => h.priorities.map(check(h.stream, _)).getOrElse(ok)
      case p: Priority => check(p.stream, p.priority)
      case _           => ok
    }

}
