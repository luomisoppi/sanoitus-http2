package sanoitus
package test.http2.client

import org.scalatest.funsuite.AnyFunSuite

import sanoitus.http2.client.Http2ClientLanguage
import sanoitus.http2.exchange.ResponseHeaders
import sanoitus.http2.server.Http2ServerLanguage
import sanoitus.parallel.ParallelLanguage

trait ClientSideConnectionClosingTest extends AnyFunSuite {

  val es: ExecutionService
  val port: Int

  val parallel: ParallelLanguage
  import parallel._

  val server: Http2ServerLanguage
  import server._

  val client: Http2ClientLanguage
  import client._

  import sanoitus.util.OptionT._
  import sanoitus.util._

  test("Client stops waiting for response headers when connection is closed by the server") {
    val program =
      for {
        clientConnection <- Connect("localhost", port).map(_.toOption).optT
        serverConnection <- GetConnection()
        clientRequest <- StartRequest(clientConnection.value)("GET", "/", Map(), true, None)
        responsePromise <- Fork(GetResponse(clientRequest.value))
        _ <- close(serverConnection)
        response <- Await(responsePromise)
      } yield response

    val result = es.executeUnsafe(program.value)
    assert(!result.isDefined)
  }

  test("Client stops waiting for response body when connection is closed by the server") {
    val program =
      for {
        clientConnection <- Connect("localhost", port).map(_.toOption).optT
        serverConnection <- GetConnection()
        clientRequest <- StartRequest(clientConnection.value)("GET", "/", Map(), false, None)
        serverRequest <- GetRequest(serverConnection.value)
        _ <- StartResponse(serverRequest.value, ResponseHeaders(200, Map()))
        clientResponse <- GetResponse(clientRequest.value)
        responseBody <- Fork(ReadResponseBody(clientResponse.value))
        _ <- close(serverConnection)
        result <- Await(responseBody)
      } yield result

    val result = es.executeUnsafe(program.value.map(_.get))
    assert(result.length == 0)
  }
}
