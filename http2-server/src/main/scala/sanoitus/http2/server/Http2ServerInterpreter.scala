package sanoitus
package http2
package server

import sanoitus.http2.exchange.ConnectionSettings
import sanoitus.http2.exchange.RequestHeaders
import sanoitus.http2.exchange.inbound.InboundProcessor
import sanoitus.http2.exchange.outbound.OutboundProcessor
import sanoitus.http2.hpack.HPackProvider
import sanoitus.http2.utils._
import sanoitus.http2.wire.Http2WireLanguage
import sanoitus.parallel.ParallelLanguage
import sanoitus.stream.StreamLanguage
import sanoitus.util.OptionT._

class Http2ServerInterpreter(wire: Http2WireLanguage,
                             stream: StreamLanguage,
                             parallel: ParallelLanguage,
                             hpackProvider: HPackProvider)
    extends Interpreter
    with Http2ServerLanguage { self =>

  override type Connection = ServerConnection
  override type Request = ServerHttp2ExchangeStream
  override type Response = ServerHttp2ExchangeStream

  import parallel._
  import stream._
  import wire._

  def apply[A](op: Op[A]): Program[A] =
    op match {
      case GetConnection(headerTableSize,
                         enablePush,
                         maxConcurrentStreams,
                         initialWindowSize,
                         maxFrameSize,
                         maxHeaderListSize,
                         inboundBufferSize) =>
        for {
          wireConn <- wire.GetConnection().optT
          settings = ConnectionSettings(headerTableSize,
                                        enablePush,
                                        maxConcurrentStreams,
                                        initialWindowSize,
                                        maxFrameSize,
                                        maxHeaderListSize)
          connection = new ServerConnection(settings, inboundBufferSize)

          wireConnectionCloser <- sharedCloser(parallel, wireConn)
          in <- createResource(())(_ => wireConnectionCloser)
          out <- createResource(())(_ => wireConnectionCloser)

          inboundLogic = InboundProcessor(stream,
                                          ReadFrame(wireConn.value),
                                          wireConnectionCloser,
                                          hpackProvider,
                                          connection,
                                          ServerFrameProcessors)
          outboundLogic = OutboundProcessor(
            stream,
            frames => WriteFrame(wireConn.value, frames.head, frames.drop(1)),
            hpackProvider,
            connection,
            wireConnectionCloser
          )
          _ <- Fork(Process(inboundLogic).flatMap(_ => connection.closer), in)
          _ <- Fork(Process(outboundLogic), out)
          res <- createResource(connection)(_.sendGoAway)
        } yield res

      case GetRequest(connection) =>
        for {
          request <- connection.getRequest()
          resourceOpt <- request match {
            case Some(r) => createResource(r)(_.closer).map(Some.apply)
            case None    => unit(None)
          }
        } yield resourceOpt

      case GetRequestHeaders(request) => {
        val h = request.inbound.requestHeaders
        unit(RequestHeaders(h.scheme, h.authority, h.method, h.path, h.values))
      }

      case ReadRequestBody(request) =>
        request.inbound.readData

      case StartResponse(request, headers, end) => {
        for {
          _ <- request.outbound.startResponse(headers, end)
          resource <- createResource(request) { _.closer }
        } yield Some(resource)
      }

      case WriteResponseBody(response, data, end) =>
        response.outbound.writeData(data, end)
    }

  def close(): Unit = ()
}
