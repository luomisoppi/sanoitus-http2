package sanoitus
package http2.exchange

import scala.concurrent.stm._
import sanoitus.http2.utils._

case class OutboundFlowControl(connection: Connection) { self =>

  val closed: Ref[Boolean] = Ref(false)
  val ready: Ref[Set[Outbound]] = Ref(Set())
  val processor: Ref[Option[Suspended[Set[Outbound]]]] = Ref(None)

  def close(implicit tx: InTxn): Continue[Unit] = {
    closed() = true
    Continue((), this.processor.swap(None).toList.map(Resumed(_, Right(Set[Outbound]()))))
  }

  def getReady(): Program[Set[Outbound]] = schedulingEffect[Set[Outbound]] { sus => implicit tx =>
    if (closed()) {
      Continue(Set())
    } else if (ready().isEmpty) {
      processor() = Some(sus)
      Suspend()
    } else {
      Continue(ready())
    }
  }

  def allowanceOf(outbound: Outbound)(implicit tx: InTxn): Int =
    outbound match {
      case e: OutboundExchange => e.window().min(connection.outboundWindow())
      case _                   => 0
    }

  private def isReady(outbound: Outbound)(implicit tx: InTxn): Boolean = {
    val (hasDataAndWindow, hasNonConsumedHeaders) = outbound match {
      case outbound: OutboundExchange =>
        (outbound.window() > 0 &&
           connection.outboundWindow() > 0 &&
           outbound.dataBytesAvailable.isDefined,
         !outbound.headersConsumed() && outbound.headers().isDefined)
      case _ => (false, false)
    }

    outbound.controlFrames().length > 0 || hasDataAndWindow || hasNonConsumedHeaders
  }

  def consumed(outbound: Outbound)(implicit tx: InTxn): Unit =
    if (!isReady(outbound)) {
      ready.transform(_ - outbound)
    }

  def produced(outbound: Outbound)(implicit tx: InTxn): Continue[Unit] = {
    if (isReady(outbound)) {
      ready.transform(_ + outbound)
    }

    if (!ready().isEmpty) {
      val proc = processor.swap(None)
      val resumed = proc.map(Resumed(_, Right(ready())))
      Continue((), resumed.toList)
    } else {
      Continue(())
    }
  }
}
