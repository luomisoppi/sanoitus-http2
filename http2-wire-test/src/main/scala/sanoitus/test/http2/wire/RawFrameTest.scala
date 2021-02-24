package sanoitus.test.http2.wire

import java.util.concurrent.atomic.AtomicInteger

import org.scalacheck.Gen
import org.scalacheck.Prop
import org.scalacheck.Prop._

import org.scalatestplus.scalacheck.Checkers
import org.scalatest.propspec.AnyPropSpec

import scala.concurrent.stm._
import scala.math.pow
import scala.util.Random

import sanoitus._
import sanoitus.http2.wire._
import sanoitus.parallel.ParallelLanguage
import sanoitus.stream.StreamLanguage

trait RawFrameTest extends AnyPropSpec with Checkers {

  implicit override val generatorDrivenConfig =
    PropertyCheckConfiguration(minSuccessful = 1000,
                               workers = 16,
                               minSize = 0,
                               sizeRange = 0xffffff,
                               maxDiscardedFactor = 0.00000001)

  val es: ExecutionService
  val parallel: ParallelLanguage
  import parallel._

  val port: Int

  val streaming: StreamLanguage
  import streaming._

  def createServerInterpreter(port: Int): Http2WireLanguage
  def createClientInterpreter(): Http2WireLanguage

  val firstFrameStream = new AtomicInteger(0)

  def patch(frame: Frame): Frame =
    Frame(frame.length, frame.frameType, frame.flags, frame.reserved, firstFrameStream.getAndIncrement, frame.content)

  def maxFrames(maxFrameSize: Int): Int =
    pow(2, 26).intValue / (maxFrameSize + 9)

  def kestrel[A](x: A)(f: A => Unit): A = { f(x); x }
  val data = kestrel(Array.fill[Byte](pow(2, 24).intValue - 1)(0))(Random.nextBytes)

  val frame: Gen[Frame] = Gen.sized { size =>
    for {
      len <- Gen.choose(0, size)
      typ <- Gen.choose[Int](0, pow(2, 8).toInt - 1)
      fla <- Gen.choose[Int](0, pow(2, 8).toInt - 1)
      res <- Gen.choose[Int](0, pow(2, 1).toInt - 1)
      str <- Gen.choose[Int](0, pow(2, 31).toInt - 1)
      idx <- Gen.choose(0, data.length - len)
    } yield Frame(len, typ, fla, res, str, data.slice(idx, idx + len))
  }

  val frames: Gen[List[Frame]] = Gen.sized { size =>
    for {
      amount <- Gen.choose(1, 100.min(maxFrames(size)))
      frames <- Gen.listOfN(amount, frame)
    } yield patch(frames.head) :: frames.tail
  }

  def sendAndReceive(lang: Http2WireLanguage, frames: List[Frame])(conn: lang.Connection): Program[List[Frame]] = {

    import lang._
    val read =
      for {
        first <- ReadFrame(conn)
        count <- effect[Int] { _ =>
          atomic { implicit tx =>
            Some(resultMap.get(first.get.stream).get.length)
          }
        }
        rest <- ReadAll(Stream.from(ReadFrame(conn)).repeat.take(count - 1).map(_.get))
      } yield first.get :: rest

    for {
      sendPromise <- Fork(Process(Stream.fromOps(frames.map(WriteFrame(conn, _)))))
      readPromise <- Fork(read)
      _ <- Await(sendPromise)
      incoming <- Await(readPromise)
    } yield incoming
  }

  lazy val server = createServerInterpreter(port)
  lazy val client = createClientInterpreter()

  val count = new AtomicInteger(0)

  val resultMap: TMap[Integer, List[Frame]] = TMap.empty

  val roundtrip =
    forAllNoShrink(frames, frames) { (serverSends, clientSends) =>

      atomic { implicit tx =>
        resultMap += ((serverSends(0).stream, serverSends))
        resultMap += ((clientSends(0).stream, clientSends))
      }

      val clientProg =
        for {
          conn <- client.Connect("localhost", port).map { _.toOption.get }
          recv <- sendAndReceive(client, clientSends)(conn.value)
          _ <- close(conn)
        } yield recv

      val serverProg =
        for {
          conn <- server.GetConnection().map { _.get }
          recv <- sendAndReceive(server, serverSends)(conn.value)
          _ <- close(conn)
        } yield recv

      val main = for {
        a <- Fork(clientProg)
        b <- Fork(serverProg)
        clientReceived <- Await(a)
        serverReceived <- Await(b)
      } yield (clientReceived, serverReceived)

      val (clientReceived, serverReceived) = {
        val res = es.execute(main)

        res.value match {
          case Right((a, b)) => {
            (a, b)
          }
          case Left(err) => {
            println(res.meta)
            throw err
          }
        }
      }

      def frameCompare(a: Frame, b: Frame): Prop =
        (a.length ?= b.length) :| "length" &&
          (a.frameType ?= b.frameType) :| "type" &&
          (a.flags ?= b.flags) :| "flags" &&
          (a.reserved ?= b.reserved) :| "reserved" &&
          (a.stream ?= b.stream) :| "stream" &&
          (a.content.sameElements(b.content) :| "content")

      val (clientShouldHaveReceived, serverShouldHaveReceived) = atomic { implicit tx =>
        val c = resultMap.get(clientReceived(0).stream).get
        val s = resultMap.get(serverReceived(0).stream).get
        resultMap -= clientReceived(0).stream
        resultMap -= serverReceived(0).stream
        (c, s)
      }

      if (clientReceived.size != clientShouldHaveReceived.size) {
        println(clientReceived)
        println(clientShouldHaveReceived)
      }

      if (serverReceived.size != serverShouldHaveReceived.size) {
        println(serverReceived)
        println(serverShouldHaveReceived)
      }

      (clientReceived.size ?= clientShouldHaveReceived.size) :| "sizes (client)" &&
      (serverReceived.size ?= serverShouldHaveReceived.size) :| "sizes (server)" &&
      all(clientReceived.zip(clientShouldHaveReceived).map(pair => frameCompare(pair._1, pair._2)): _*) &&
      all(serverReceived.zip(serverShouldHaveReceived).map(pair => frameCompare(pair._1, pair._2)): _*)
    }

  property("Raw frame roundtrip") {
    check(roundtrip)
  }
}
