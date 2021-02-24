package sanoitus
package test.http2.server

import org.scalacheck.Arbitrary.arbitrary
import org.scalacheck.Gen
import org.scalactic.anyvals.PosInt
import org.scalacheck.Prop
import org.scalacheck.Prop._

import org.scalatest.BeforeAndAfterAll
import org.scalatest.propspec.AnyPropSpec

import org.scalatestplus.scalacheck.Checkers

import scala.util.Random

import sanoitus.http2.client.Http2ClientLanguage
import sanoitus.http2.exchange.RequestHeaders
import sanoitus.http2.exchange.ResponseHeaders
import sanoitus.http2.server.Http2ServerLanguage
import sanoitus.parallel.core.ParallelInterpreter
import sanoitus.stream.core.StreamInterpreter
import sanoitus.util._
import sanoitus.util.Monad._

trait EchoTest extends AnyPropSpec with Checkers with BeforeAndAfterAll {

  val numWorkers = 16

  implicit override val generatorDrivenConfig =
    PropertyCheckConfiguration(
      minSuccessful = 1000,
      workers = PosInt.from(numWorkers).get,
      minSize = 1,
      sizeRange = 100,
      maxDiscardedFactor = 0.00000001
    )

  val server: Http2ServerLanguage
  val client: Http2ClientLanguage
  val serverPort: Int
  val es: ExecutionService

  object Server {
    import ParallelInterpreter._
    import StreamInterpreter._
    import server._

    def createResponse(req: RequestHeaders): ResponseHeaders = {
      val statics = Map[String, String]("request-scheme" -> req.scheme,
                                        "request-authority" -> req.authority,
                                        "request-method" -> req.method,
                                        "request-path" -> req.path)
      val headers = req.values.map(kv => ("request-" + kv._1, kv._2))
      val statusCode = req.values.get("status-code-request").map(_.toInt).getOrElse(200)
      ResponseHeaders(statusCode, (statics ++ headers))
    }

    def requestHandler(req: Request): Program[Unit] =
      for {
        headers <- GetRequestHeaders(req)
        response <- StartResponse(req, createResponse(headers), false).map(_.get)
        _ <- Process(
          Stream
            .from(ReadRequestBody(req))
            .repeat
            .takeWhileNotZeroLength
            .through(WriteResponseBody(response.value, _, false))
        )
        _ <- WriteResponseBody(response.value, new Array[Byte](0), true)
      } yield ()

    def connectionHandler(conn: Connection) =
      Stream
        .from(GetRequest(conn))
        .repeat
        .takeWhileDefined
        .through(req => Fork(requestHandler(req.value), req))

    val main =
      Stream
        .from(GetConnection())
        .repeat
        .takeWhileDefined
        .through(conn => Fork(Process(connectionHandler(conn.value)), conn))
  }

  object Client {
    import client._
    import ParallelInterpreter._
    import StreamInterpreter._

    val createConnection = for {
      connection <- Connect("localhost", serverPort)
      _ <- mapResources(_ => Set())
    } yield connection.toOption.get

    def clientProgram(conn: Connection,
                      method: String,
                      path: String,
                      headers: Map[String, String],
                      body: List[Array[Byte]]): Program[(ResponseHeaders, List[Array[Byte]])] =
      for {
        req <- StartRequest(conn)(method, path, headers, false, None).map(_.get)
        _ <- Fork(
          Process(
            Stream
              .from(body)
              .through(WriteRequestBody(req.value, _))
              ++ Stream.from(WriteRequestBody(req.value, new Array[Byte](0), true))
          ),
          req
        )
        resp <- GetResponse(req.value).map { _.get }
        headers <- GetResponseHeaders(resp.value)
        bytes <- ReadAll(Stream.from(ReadResponseBody(resp.value)).repeat.takeUntil(_.size == 0))
      } yield (headers, bytes)
  }

  val validCharacters = "/abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-._~!$&'()*+,;=:@%"

  val pathChar: Gen[String] = for {
    char <- Gen.oneOf(validCharacters).map(_.toString)
    hex <- Gen.choose(0, 255).map(_.toHexString).map(hex => if (hex.length == 1) "0" + hex else hex)
  } yield char match {
    case "%" => "%" + hex
    case _   => char
  }

  val path: Gen[String] = for {
    size <- Gen.choose(0, 100)
    chars <- Gen.listOfN(size, pathChar)
  } yield size match {
    case 0 => "/"
    case _ => "/" + chars.mkString
  }

  val header: Gen[(String, String)] =
    for {
      first <- Gen.alphaNumChar.map(_.toLower)
      rest <- Gen.alphaNumStr.map(x => x.substring(0, x.length.min(50))).map(_.toLowerCase())
      value <- Gen.alphaNumStr
      name = s"$first$rest"
    } yield if (name == "te") ("tee", value) else (name, value)

  val headers: Gen[Map[String, String]] =
    for {
      size <- Gen.choose(0, 50)
      headers <- Gen.listOfN(size, header)
    } yield headers.toMap

  val chunks: Gen[Array[Byte]] =
    for {
      size <- Gen.choose(0, 10240)
      data <- Gen.listOfN(size, arbitrary[Byte])
    } yield data.toArray

  val body: Gen[List[Array[Byte]]] =
    for {
      numChunks <- Gen.choose(0, 50)
      chunks <- Gen.listOfN(numChunks, chunks)
    } yield chunks

  val inputs: Gen[(String, String, Map[String, String], List[Array[Byte]], Int)] =
    for {
      method <- Gen.oneOf(List("GET", "PUT", "POST", "DELETE"))
      path <- path
      statusCode <- Gen.choose(1, 1000)
      headers <- headers.map(_ ++ Map("status-code-request" -> statusCode.toString))
      body <- body
    } yield (method, path, headers, body, statusCode)

  def check(responseHeaders: ResponseHeaders,
            method: String,
            path: String,
            requestHeaders: Map[String, String]): Prop = {
    val expected =
      requestHeaders.map(x => ("request-" + x._1, x._2)) ++ Map("request-method" -> method,
                                                                "request-path" -> path,
                                                                "request-scheme" -> "https",
                                                                "request-authority" -> s"localhost:$serverPort")
    responseHeaders.values ?= expected
  }

  lazy val roundtrip = {
    es.executeAsync(StreamInterpreter.Process(Server.main)) { x => println(s"Server exits $x") }
    val connections = es.executeUnsafe(List.fill((numWorkers / 2).max(1))(Client.createConnection).sequence)
    try {
      forAllNoShrink(inputs) { input =>
        val (method, path, requestHeaders, requestBody, statusCode) = input

        val result =
          es.execute(
            Client.clientProgram(
              connections(Random.nextInt(connections.size)).value,
              method,
              path,
              requestHeaders,
              requestBody
            )
          )

        result.value match {
          case Left(_) => {
            println(result.meta)
            Prop.falsified
          }
          case Right((responseHeaders, responseBody)) => {
            val expected = requestBody.flatten
            val actual = responseBody.flatten

            check(responseHeaders, method, path, requestHeaders) &&
            (actual.length ?= expected.length) :| responseHeaders.values.toString &&
            (actual.sameElements(expected) ?= true) &&
            (responseHeaders.status.toInt ?= statusCode)
          }
        }
      }
    } finally {
      es.executeUnsafe(connections.map(close).sequence)
    }
  }

  property("HTTP roundtrip") {
    check(roundtrip)
  }
}
