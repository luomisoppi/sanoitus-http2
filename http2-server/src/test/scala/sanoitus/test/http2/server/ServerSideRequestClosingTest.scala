package sanoitus
package test.http2.server

import org.scalatest.funsuite.AnyFunSuite
import sanoitus.http2.client.Http2ClientLanguage
import sanoitus.http2.exchange.ResponseHeaders
import sanoitus.http2.server.Http2ServerLanguage
import sanoitus.parallel.ParallelLanguage

trait ServerSideRequestClosingTest extends AnyFunSuite {

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

  lazy val prepare: Program[Option[(Resource[client.Connection#Request], Resource[server.Request])]] = {
    for {
      clientConnection <- Connect("localhost", port).map(_.toOption).optT
      clientRequest <- StartRequest(clientConnection.value)("GET", "/", Map(), false, None)
      serverConnection <- GetConnection()
      serverRequest <- GetRequest(serverConnection.value)
    } yield (clientRequest, serverRequest)
  }

  test("Server-side request handling failure before request body has been read breaks client-side response wait") {
    val program = for {
      init <- prepare.optT
      (clientRequest, serverRequest) = init
      responsePromise <- Fork(GetResponse(clientRequest.value))
      _ <- Fork(sanoitus.fail[Unit](new Exception()), serverRequest)
      value <- Await(responsePromise.map(_.isDefined))
    } yield value

    val result = es.executeUnsafe(program.value.map(_.get))
    assert(!result)
  }

  test("Server-side request handling failure after request body has been read breaks client-side response wait") {
    val program = for {
      init <- prepare.optT
      (clientRequest, serverRequest) = init
      _ <- WriteRequestBody(clientRequest.value, new Array[Byte](0), true)
      _ <- ReadRequestBody(serverRequest.value)
      responsePromise <- Fork(GetResponse(clientRequest.value))
      _ <- Fork(sanoitus.fail[Unit](new Exception()), serverRequest)
      value <- Await(responsePromise.map(_.isDefined))
    } yield value

    val result = es.executeUnsafe(program.value.map(_.get))
    assert(!result)
  }

  test("Server-side request handling failure breaks client-side reading of response body") {
    val program = for {
      init <- prepare.optT
      (clientRequest, serverRequest) = init
      serverResponse <- StartResponse(serverRequest.value, ResponseHeaders(200, Map()))
      clientResponse <- GetResponse(clientRequest.value)
      clientDataPromise <- Fork(ReadResponseBody(clientResponse.value))
      _ <- Fork(sanoitus.fail[Unit](new Exception()), serverRequest, serverResponse)
      responseBody <- Await(clientDataPromise)
    } yield responseBody

    val result = es.executeUnsafe(program.value.map(_.get))
    assert(result.isEmpty)
  }
}
