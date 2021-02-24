package sanoitus
package http2
package client

import scala.concurrent.stm._

import sanoitus.http2.exchange.Connection
import sanoitus.http2.exchange.Http2ExchangeStream
import sanoitus.http2.exchange.OutboundExchange
import sanoitus.http2.exchange.RequestHeaders

case class ClientOutboundExchange(override val stream: Http2ExchangeStream,
                                  override val connection: Connection,
                                  _window: Int,
                                  val requestHeaders: RequestHeaders,
                                  end: Boolean,
                                  _writer: Suspended[Boolean])
    extends OutboundExchange {

  override val id = stream.id
  override val window: Ref[Int] = Ref(_window)
  override val headers: Ref[Option[List[(String, String)]]] = Ref(
    Some(
      (":authority", requestHeaders.authority.toString()) ::
        (":scheme", requestHeaders.scheme.toString()) ::
        (":method", requestHeaders.method.toString()) ::
        (":path", requestHeaders.path.toString()) ::
        requestHeaders.values.toList
    )
  )
  override val data: Ref[Option[Array[Byte]]] = Ref(None)
  override val isComplete: Ref[Boolean] = Ref(end)
  override val writer: Ref[Option[Suspended[Boolean]]] = Ref(Some(_writer))
}
