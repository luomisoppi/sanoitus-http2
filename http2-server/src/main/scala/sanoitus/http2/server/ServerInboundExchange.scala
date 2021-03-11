package sanoitus
package http2
package server

import scala.concurrent.stm._
import sanoitus.http2.exchange.Connection
import sanoitus.http2.exchange.Http2ExchangeStream
import sanoitus.http2.exchange.InboundExchange
import sanoitus.http2.exchange.RequestHeaders

case class ServerInboundExchange(override val connection: Connection,
                                 override val stream: Http2ExchangeStream,
                                 requestHeaders: RequestHeaders,
                                 _window: Int,
                                 end: Boolean)
    extends InboundExchange {

  override val isComplete: Ref[Boolean] = Ref(end)
  override val window: Ref[Int] = Ref(_window)

  override def getHeaders(): Map[String, String] = requestHeaders.values
}
