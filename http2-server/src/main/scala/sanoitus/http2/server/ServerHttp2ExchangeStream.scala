package sanoitus.http2
package server

import sanoitus.http2.exchange.Connection
import sanoitus.http2.exchange.Http2ExchangeStream
import sanoitus.http2.exchange.RequestHeaders

case class ServerHttp2ExchangeStream(override val id: Int,
                                     connection: Connection,
                                     headers: RequestHeaders,
                                     localWindow: Int,
                                     remoteWindow: Int,
                                     end: Boolean)
    extends Http2ExchangeStream {

  override type In = ServerInboundExchange
  override type Out = ServerOutboundExchange

  override val inbound = ServerInboundExchange(connection, this, headers, localWindow, end)
  override val outbound = ServerOutboundExchange(this, connection, remoteWindow)
}
