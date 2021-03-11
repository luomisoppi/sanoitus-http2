package sanoitus
package test.http2.server

import org.scalatest.funsuite.AnyFunSuite
import sanoitus.http2.client.Http2ClientLanguage
import sanoitus.http2.server.Http2ServerLanguage
import sanoitus.parallel.ParallelLanguage

trait ServerSideConnectionClosingTest extends AnyFunSuite {

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

  test("Server stops waiting for new requests when connection is closed by the client") {
    val program =
      for {
        clientConnection <- Connect("localhost", port).map(_.toOption).optT
        serverConnection <- GetConnection()
        requestPromise <- Fork(GetRequest(serverConnection.value))
        _ <- close(clientConnection)
        request <- Await(requestPromise)
      } yield request

    val result = es.executeUnsafe(program.value)
    assert(!result.isDefined)
  }

  test("Server streams stops waiting for new requests when connection is closed by the client") {
    val program =
      for {
        clientConnection <- Connect("localhost", port).map(_.toOption).optT
        _ <- StartRequest(clientConnection.value)("GET", "/", Map(), false, None)
        serverConnection <- GetConnection()
        serverRequest <- GetRequest(serverConnection.value)
        bodyPromise <- Fork(ReadRequestBody(serverRequest.value))
        _ <- close(clientConnection)
        body <- Await(bodyPromise)
      } yield body

    val result = es.executeUnsafe(program.value)
    assert(result.isDefined)
  }
}
