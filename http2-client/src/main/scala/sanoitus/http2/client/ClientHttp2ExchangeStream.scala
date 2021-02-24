package sanoitus
package http2
package client

import sanoitus.http2.exchange.Connection
import sanoitus.http2.exchange.Http2ExchangeStream
import sanoitus.http2.exchange.RequestHeaders

case class ClientHttp2ExchangeStream(override val id: Int,
                                     connection: Connection,
                                     requestHeaders: RequestHeaders,
                                     localWindow: Int,
                                     remoteWindow: Int,
                                     end: Boolean,
                                     writer: Suspended[Boolean])
    extends Http2ExchangeStream {

  override type In = ClientInboundExchange
  override type Out = ClientOutboundExchange

  override val inbound = ClientInboundExchange(connection, this, localWindow)
  override val outbound = ClientOutboundExchange(this, connection, remoteWindow, requestHeaders, end, writer)
}
