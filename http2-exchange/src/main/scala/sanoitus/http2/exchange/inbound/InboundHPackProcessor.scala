package sanoitus
package http2.exchange
package inbound

import sanoitus.http2.hpack.Header
import sanoitus.http2.hpack.HPackDecoder

class InboundHPackProcessor(decoder: HPackDecoder, connection: Connection) {

  var fragments: List[Array[Byte]] = List()

  def apply(frame: Http2Frame): Program[Unit] = effect[Unit] { _ =>

    val endHeaders = frame match {
      case h: Headers => {
        fragments = List(h.blockFragment)
        connection.latestHeadersFrame = h
        h.endHeaders
      }
      case c: Continuation => {
        fragments = fragments :+ c.blockFragment
        c.endHeaders
      }
      case Settings(Some(settings)) => {
        settings.get(HEADER_TABLE_SIZE).foreach(value => decoder.setMaxTableSize(value))
        false
      }
      case _ => false
    }

    if (endHeaders) {
      connection.latestDecoded = decode()
    }

    Some(())
  }

  private def decode(): Either[Throwable, List[Header]] =
    if (fragments.isEmpty) {
      Right(List())
    } else {
      val data = fragments.tail.foldLeft(fragments.head) { _ ++ _ }
      try {
        Right(decoder.decode(data))
      } catch {
        case t: Throwable => Left(t)
      }
    }
}
