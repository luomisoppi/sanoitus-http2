package sanoitus
package http2
package client

import scala.concurrent.stm._

import sanoitus.http2.exchange.Connection
import sanoitus.http2.exchange.Http2ExchangeStream
import sanoitus.http2.exchange.RequestHeaders
import sanoitus.http2.utils._

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

  override def remoteRst()(implicit tx: InTxn): Continue[Unit] = {
    val headerWaiters = inbound.headerWaiters.swap(List()).map(Resumed(_, Right(false)))
    val dataWaiters = inbound.reader.swap(None).toList.map(Resumed(_, Right(new Array[Byte](0))))
    super
      .remoteRst()
      .resumeMap(_ ++ headerWaiters ++ dataWaiters)
  }
}
