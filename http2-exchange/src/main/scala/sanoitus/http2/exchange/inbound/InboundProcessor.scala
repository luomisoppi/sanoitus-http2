package sanoitus
package http2
package exchange
package inbound

import scala.concurrent.stm._

import sanoitus.http2.hpack.HPackProvider
import sanoitus.http2.utils._
import sanoitus.http2.wire.Frame
import sanoitus.stream.StreamLanguage

object InboundProcessor {
  def process(connection: Connection,
              frameProcessors: FrameProcessors): Either[Error, (Frame, Http2Frame)] => Program[Option[Http2Frame]] =
    incoming =>
      schedulingEffect[Option[Http2Frame]] { _ => implicit tx =>
        {
          import frameProcessors._
          val result = incoming match {
            case Right((w, f)) => {
              val ctx = ProcessingContext(connection, w)
              f match {
                case frame: Headers      => frame.process(ctx)
                case frame: Settings     => frame.process(ctx)
                case frame: WindowUpdate => frame.process(ctx)
                case frame: Priority     => frame.process(ctx)
                case frame: Ping         => frame.process(ctx)
                case frame: RstStream    => frame.process(ctx)
                case frame: Data         => frame.process(ctx)
                case frame: Continuation => frame.process(ctx)
                case frame: GoAway       => frame.process(ctx)
                case frame: Unknown      => frame.process(ctx)
              }
            }
            case Left(e) => Left(e)
          }

          val processed = result match {
            case Right(result) => result
            case Left(StreamError(code, frame, message @ _, cause @ _)) =>
              connection.streams().get(frame.stream) match {
                case Some(stream) => stream.rst(code)
                case None => {
                  val c = connection
                  connection.outboundFlowControl.produced(new Outbound() {
                    override val id = frame.stream
                    override val connection = c
                    override def dataBytesAvailable(implicit tx: InTxn) = None
                    override def consume(max: Int)(implicit tx: InTxn) =
                      Http2StreamData(id, List(RstStream(id, code)), None)
                  })
                }
              }
            case Left(ConnectionError(code, frame @ _, message @ _, cause @ _)) => {
              connection.control.goAway(code)
            }
          }
          processed.map(_ => incoming.toOption.map(_._2))
        }
      }

  def apply(streamLanguage: StreamLanguage,
            readWire: Program[Option[Frame]],
            closeWire: Program[Unit],
            hpackProvider: HPackProvider,
            connection: Connection,
            frameProcessors: FrameProcessors): streamLanguage.Stream[Unit] = {
    import streamLanguage._

    val hpack = new InboundHPackProcessor(hpackProvider.createDecoder(), connection)

    Stream
      .from(readWire)
      .repeat
      .takeWhileDefined
      .map(wire => Http2Frame.parse(wire).map((wire, _)))
      .effect({
        case Right((_, frame)) => hpack(frame)
        case err @ Left(_)     => unit(err)
      })
      .through(process(connection, frameProcessors))
      .through({
        case Some(_: GoAway) => closeWire
        case _               => unit(())
      })
  }
}
