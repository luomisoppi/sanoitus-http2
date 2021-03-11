package sanoitus
package test.http2.client

import org.scalatest.funsuite.AnyFunSuite

import sanoitus.http2.client.Http2ClientLanguage
import sanoitus.http2.server.Http2ServerLanguage
import sanoitus.parallel.ParallelLanguage

trait ClientSideRequestClosingTest extends AnyFunSuite {

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

  test("Client-side request handling failure breaks server-side request body read") {
    val program = for {
      init <- prepare.optT
      (clientRequest, serverRequest) = init
      bodyPromise <- Fork(ReadRequestBody(serverRequest.value))
      _ <- Fork(sanoitus.fail[Unit](new Exception()), clientRequest)
      value <- Await(bodyPromise)
    } yield value

    val result = es.executeUnsafe(program.value.map(_.get))
    assert(result.isEmpty)
  }
}
