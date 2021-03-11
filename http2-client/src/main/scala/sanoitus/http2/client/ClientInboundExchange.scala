package sanoitus
package http2
package client

import scala.concurrent.stm._

import sanoitus.http2.exchange.Connection
import sanoitus.http2.exchange.Http2ExchangeStream
import sanoitus.http2.exchange.InboundExchange
import sanoitus.http2.exchange.ResponseHeaders
import sanoitus.http2.utils._

case class ClientInboundExchange(override val connection: Connection,
                                 override val stream: Http2ExchangeStream,
                                 _window: Int)
    extends InboundExchange {

  val isComplete: Ref[Boolean] = Ref(false)
  val window: Ref[Int] = Ref(_window)

  val inboundHeaders: Ref[Option[ResponseHeaders]] = Ref(None)
  val headerWaiters: Ref[List[Suspended[Boolean]]] = Ref(List())

  override def close(implicit tx: InTxn): Continue[Unit] =
    for {
      _ <- super.close
      _ <- Continue((), headerWaiters.swap(List()).map(Resumed(_, Right(false))))
    } yield ()

  val waitForResponse = schedulingEffect[Boolean] { sus => implicit tx =>
    {
      if (closed()) {
        Continue(false)
      } else if (inboundHeaders().isDefined) {
        Continue(true)
      } else {
        headerWaiters.transform(sus :: _)
        Suspend()
      }
    }
  }

  def addResponseHeaders(responseHeaders: ResponseHeaders)(implicit tx: InTxn): Continue[Unit] = {
    inboundHeaders() = Some(responseHeaders)
    val release = headerWaiters.swap(List())
    Continue((), release.map(Resumed(_, Right(true))))
  }

  override def getHeaders(): Map[String, String] =
    inboundHeaders.single().map(_.values).getOrElse(Map())
}
