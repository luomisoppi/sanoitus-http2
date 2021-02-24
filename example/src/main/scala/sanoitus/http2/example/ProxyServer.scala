package sanoitus
package http2.example

import sanoitus.http2.client.Http2ClientInterpreter
import sanoitus.http2.exchange.RequestHeaders
import sanoitus.http2.hpack.jetty.JettyHPackProvider
import sanoitus.http2.server.Http2ServerInterpreter
import sanoitus.http2.wire.netty.NettyHttp2WireInterpreter
import sanoitus.parallel.core.ParallelInterpreter
import sanoitus.stream.core.StreamInterpreter
import sanoitus.util.OptionT._

import ParallelInterpreter._
import StreamInterpreter._

object ProxyServer {
  def main(args: Array[String]): Unit = {

    val main =
      for {
        client <- Client.connect()
        _ <- Process(Server.proxyRequestsTo(client))
      } yield ()

    val es = BasicExecutionService()
    println(s"Proxy listening at https://localhost:${Server.port}")
    es.executeUnsafe(main)
  }
}

object Client {
  val wire = NettyHttp2WireInterpreter()
  val client = new Http2ClientInterpreter(wire, StreamInterpreter, ParallelInterpreter, JettyHPackProvider)
  import client._

  def connect() =
    Connect("en.wikipedia.org", 443).map(_.toOption.get.value)

  def makeRequest(connection: Connection, requestHeaders: RequestHeaders) =
    for {
      req <- StartRequest(connection)(requestHeaders.method,
                                      if (requestHeaders.path == "/") "/wiki/Proxy_server" else requestHeaders.path,
                                      requestHeaders.values,
                                      true,
                                      None).optT
      resp <- GetResponse(req.value)
      responseHeaders <- GetResponseHeaders(resp.value)
    } yield (resp.value, responseHeaders)

  def bodyOf(resp: Response): Stream[Array[Byte]] =
    Stream.from(ReadResponseBody(resp)).repeat.takeWhileNotZeroLength
}

object Server {
  val port = 8443

  val wire = NettyHttp2WireInterpreter(port, "/server.crt", "/server.key")
  val server = new Http2ServerInterpreter(wire, StreamInterpreter, ParallelInterpreter, JettyHPackProvider)
  import server._

  def requestHandler(req: Request, connection: Client.client.Connection) =
    for {
      headers <- GetRequestHeaders(req).optT
      responseData <- Client.makeRequest(connection, headers)
      (clientResponse, responseHeaders) = responseData
      response <- StartResponse(req, responseHeaders, false)
      stream = Client.bodyOf(clientResponse).through(data => WriteResponseBody(response.value, data))
      _ <- Process(stream)
      _ <- WriteResponseBody(response.value, new Array[Byte](0), true)
    } yield ()

  def connectionHandler(conn: Connection, clientConnection: Client.client.Connection) =
    Stream
      .from(GetRequest(conn))
      .repeat
      .takeWhileDefined
      .through(req => Fork(requestHandler(req.value, clientConnection), req))

  def proxyRequestsTo(clientConnection: Client.client.Connection) =
    Stream
      .from(GetConnection(enablePush = false, maxConcurrentStreams = 100))
      .repeat
      .takeWhileDefined
      .through(connection => Fork(Process(connectionHandler(connection.value, clientConnection)), connection))
}
