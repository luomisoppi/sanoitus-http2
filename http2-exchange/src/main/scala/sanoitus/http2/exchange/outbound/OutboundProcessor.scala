package sanoitus
package http2
package exchange
package outbound

import sanoitus.http2.hpack.HPackProvider
import sanoitus.http2.wire.Frame
import sanoitus.stream.StreamLanguage

object OutboundProcessor {

  private def sendFrames(writeWire: List[Frame] => Program[Boolean], hpackOutgoing: OutboundHPackProcessor)(
    data: Http2StreamData
  ): Program[Unit] =
    for {
      exchangeFrames <- data.exchangeData match {
        case Some(Http2ExchangeData(Left(headers), maxFrameSize, _, end)) =>
          hpackOutgoing.encode(headers).map { encoded =>
            val chunks = encoded.grouped(maxFrameSize).toList
            if (chunks.size == 1) {
              List(Headers(data.id, None, chunks(0), true, end, None))
            } else {
              val head = chunks.head
              val middle = chunks.drop(1).dropRight(1).map(Continuation(data.id, _, false))
              val tail = chunks.last
              (Headers(data.id, None, head, false, end, None) :: middle) :+ Continuation(data.id, tail, true)
            }
          }
        case Some(Http2ExchangeData(Right(content), _, _, end)) => {
          unit(List(Data(data.id, content, end, None)))
        }
        case _ => {
          unit(List())
        }
      }
      frames = data.controlFrames
        .find(_.isInstanceOf[GoAway])
        .map(List(_))
        .getOrElse(data.controlFrames ++ exchangeFrames)
      wireFrames = frames.map(_.toWire())
      success <- if (wireFrames.isEmpty) /*stream cancelled after picking it*/ unit(true) else writeWire(wireFrames)
      _ <- effect[Unit](_ =>
        data.exchangeData match {
          case Some(data) => Some(data.writer.foreach(_.proceed(success)))
          case None       => Some(())
        }
      )
    } yield ()

  def closer(close: Program[Unit])(data: Http2StreamData): Program[Unit] =
    if (data.controlFrames.exists(_.isInstanceOf[GoAway])) {
      close
    } else {
      unit(())
    }

  def apply(streamLanguage: StreamLanguage,
            writeWire: List[Frame] => Program[Boolean],
            hpackProvider: HPackProvider,
            connection: Connection,
            closeWire: Program[Unit]): streamLanguage.Stream[Unit] = {
    import streamLanguage._

    val hpackOutgoing = new OutboundHPackProcessor(hpackProvider.createEncoder())

    Stream
      .from(connection.outboundFlowControl.getReady())
      .repeat
      .takeWhileNotEmpty
      .map(connection.prioritization.pickStream)
      .through(connection.consumeStream)
      .effect(sendFrames(writeWire, hpackOutgoing))
      .through(closer(closeWire))
  }
}
