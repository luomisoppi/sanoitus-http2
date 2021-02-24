package sanoitus
package http2
package server

import scala.concurrent.stm._
import sanoitus.http2.utils._
import sanoitus.http2.exchange.Http2ExchangeStream
import sanoitus.http2.exchange.Connection
import sanoitus.http2.exchange.OutboundExchange
import sanoitus.http2.exchange.ResponseHeaders

case class ServerOutboundExchange(override val stream: Http2ExchangeStream,
                                  override val connection: Connection,
                                  _window: Int)
    extends OutboundExchange {

  override val id = stream.id
  override val window: Ref[Int] = Ref(_window)
  override val headers: Ref[Option[List[(String, String)]]] = Ref(None)
  override val isComplete: Ref[Boolean] = Ref(false)
  override val writer: Ref[Option[Suspended[Boolean]]] = Ref(None)

  def startResponse(h: ResponseHeaders, end: Boolean): Program[Boolean] = schedulingEffect[Boolean] {
    sus => implicit tx =>
      {
        if (isComplete()) {
          Continue(false)
        } else if (writer().isDefined) {
          throw new IllegalStateException()
        } else {
          writer() = Some(sus)
          headers() = Some((":status", h.status.toString()) :: h.values.toList)
          if (end) {
            isComplete() = true
          }
          connection.outboundFlowControl.produced(this).suspend[Boolean]
        }
      }
  }
}
