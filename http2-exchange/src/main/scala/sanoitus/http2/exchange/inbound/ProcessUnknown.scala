package sanoitus.http2
package exchange
package inbound

import scala.concurrent.stm._
import sanoitus.http2.utils.Continue
import Error._

case class ProcessUnknown(acceptPushPromise: Boolean) extends InboundPipeline[Unknown, Continue[Unit]] {
  override def apply(ctx: ProcessingContext, frame: Unknown)(implicit tx: InTxn): Either[Err, Continue[Unit]] =
    if (frame.frame.frameType == Http2Frame.PushPromise && !acceptPushPromise) {
      connectionError(PROTOCOL_ERROR, "PUSH_PROMISE frame from client")
    } else {
      ok(Continue(()))
    }
}
