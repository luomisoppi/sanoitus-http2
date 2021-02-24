package sanoitus
package http2
package client

import sanoitus.http2.exchange.ConnectionSettings
import sanoitus.http2.exchange.inbound.InboundProcessor
import sanoitus.http2.exchange.outbound.OutboundProcessor
import sanoitus.http2.hpack.HPackProvider
import sanoitus.http2.wire.Http2WireLanguage
import sanoitus.parallel.ParallelLanguage
import sanoitus.stream.StreamLanguage
import sanoitus.util.EitherT._

class Http2ClientInterpreter(wire: Http2WireLanguage,
                             stream: StreamLanguage,
                             parallel: ParallelLanguage,
                             hpack: HPackProvider)
    extends Interpreter
    with Http2ClientLanguage { self =>

  import wire._
  import parallel._
  import stream._

  override type Connection = ClientConnection
  override type Response = ClientHttp2ExchangeStream with Connection#Dependable

  override def apply[A](op: Op[A]): Program[A] =
    op match {
      case Connect(host,
                   port,
                   headerTableSize,
                   enablePush,
                   maxConcurrentStreams,
                   initialWindowSize,
                   maxFrameSize,
                   maxHeaderListSize,
                   inboundBufferSize) => {
        for {
          wireConn <- wire.Connect(host, port).eitherT
          settings = ConnectionSettings(headerTableSize,
                                        enablePush,
                                        maxConcurrentStreams,
                                        initialWindowSize,
                                        maxFrameSize,
                                        maxHeaderListSize)
          conn = ClientConnection(host, port, wire, stream, hpack, wireConn, settings, inboundBufferSize)
          inboundLogic = InboundProcessor(stream, ReadFrame(wireConn.value), hpack, conn, ClientFrameProcessors)
          outboundLogic = OutboundProcessor(
            stream,
            frames => WriteFrame(wireConn.value, frames.head, frames.drop(1)),
            hpack,
            conn,
            wireConn
          )
          _ <- Fork(Process(inboundLogic), wireConn)
          _ <- Fork(Process(outboundLogic), wireConn)
          res <- resource(conn: Connection)(_ => println("Closing client exchange connection"))
        } yield res
      }
      case s: StartRequest => {
        for {
          req <- s.conn.createRequest(s.method, s.path, s.headers, s.priority, s.end)
          res <- req match {
            case Some(req) => resource(req)(_ => ()).map(Some(_))
            case None      => unit(None)
          }
        } yield res
      }

      case WriteRequestBody(request, data, end) =>
        request.outbound.writeData(data, end)

      case GetResponse(request) =>
        for {
          response <- request.inbound.waitForResponse
          res <- response match {
            case true  => resource(request)(_ => ()).map(Some(_))
            case false => unit(None)
          }
        } yield res

      case GetResponseHeaders(response) =>
        unit(response.inbound.inboundHeaders.single().get)

      case ReadResponseBody(response) =>
        response.inbound.readData
    }

  override def close(): Unit = ()
}
