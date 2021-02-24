package sanoitus
package http2.example

import java.io.File
import java.nio.channels.AsynchronousFileChannel
import java.nio.file.Paths
import java.nio.file.Path
import java.nio.file.Files
import java.nio.file.StandardOpenOption.READ

import org.apache.tika.Tika

import sanoitus.http2.exchange.RequestHeaders
import sanoitus.http2.exchange.ResponseHeaders
import sanoitus.http2.hpack.jetty.JettyHPackProvider
import sanoitus.http2.server.Http2ServerInterpreter
import sanoitus.http2.wire.netty.NettyHttp2WireInterpreter
import sanoitus.parallel.core.ParallelInterpreter
import sanoitus.stream.core.StreamInterpreter

object FileServer {
  val serverPort = 8443

  def main(args: Array[String]): Unit = {
    val wire = NettyHttp2WireInterpreter(serverPort, "/server.crt", "/server.key")
    val exchange = new Http2ServerInterpreter(wire, StreamInterpreter, ParallelInterpreter, JettyHPackProvider)

    import exchange._
    import ParallelInterpreter._
    import StreamInterpreter._

    val chunkSize = 1024
    val tika = new Tika()

    def renderEntry(resource: String): File => String = file => {
      val name = file.getName()
      val label = if (file.isDirectory) name + "/" else name
      val link = s"$resource/$name"
      s"<a href='$link'>$label</a>"
    }

    def renderDirectory(dir: Path, resource: String): String = {
      val prefix = "<html><body><div style='height:100%; display:flex; flex-direction:column;'>"
      val content =
        dir.toFile().listFiles().toList.sortBy(file => (!file.isDirectory(), file.getName())).map(renderEntry(resource))
      val postfix = "</div></body></html>"
      prefix + content.mkString + postfix
    }

    def getContentOf(headers: RequestHeaders): Option[(Stream[Array[Byte]], String)] = {
      val path = Paths.get("./" + headers.path).toAbsolutePath().normalize()
      val resource = s"${headers.scheme}://${headers.authority}" + (if (headers.path == "/") "" else headers.path)

      if (Files.isRegularFile(path)) {
        Some((Stream.fromFile(AsynchronousFileChannel.open(path, READ), chunkSize), tika.detect(path)))
      } else if (Files.isDirectory(path)) {
        Some((Stream(renderDirectory(path, resource).getBytes()), "text/html"))
      } else {
        None
      }
    }

    def requestHandler(req: Request): Program[Unit] =
      for {
        headers <- GetRequestHeaders(req)
        _ <- getContentOf(headers) match {
          case None => StartResponse(req, ResponseHeaders(404, Map[String, String]()), true): Program[_]
          case Some((stream, contentType)) =>
            for {
              res <- StartResponse(req, ResponseHeaders(200, Map("content-type" -> contentType)), false)
              _ <- Process(stream.through(data => WriteResponseBody(res.get.value, data, false)))
              _ <- WriteResponseBody(res.get.value, new Array[Byte](0), true)
            } yield ()
        }
      } yield ()

    def connectionHandler(conn: Connection) =
      Stream
        .from(GetRequest(conn))
        .repeat
        .takeWhileDefined
        .through(req => Fork(requestHandler(req.value), req))

    val main =
      Stream
        .from(GetConnection(maxConcurrentStreams = 100))
        .repeat
        .takeWhileDefined
        .through(conn => Fork(Process(connectionHandler(conn.value)), conn))

    val es = BasicExecutionService()
    println(s"Server listening at https://localhost:$serverPort")
    es.executeUnsafe(Process(main))
  }
}
