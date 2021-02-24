package sanoitus.http2.exchange
package inbound

import scala.concurrent.stm._
import sanoitus.http2.utils.Continue

object ProcessSettings extends InboundPipeline[Settings, Continue[Unit]] {
  override def apply(ctx: ProcessingContext, frame: Settings)(implicit tx: InTxn): Either[Err, Continue[Unit]] = {
    val connection = ctx.connection
    frame.settings match {
      case Some(settings) => {

        val res = settings.foldLeft(connection.control.ackSettings) { (acc, entry) =>
          val (setting, value) = entry

          setting match {
            case INITIAL_WINDOW_SIZE => {
              val diff = value - connection.remoteSettings.initialWindowSize()
              connection.remoteSettings.initialWindowSize() = value
              connection.streams().values.foreach { _.outbound.window.transform(_ + diff) }
              if (diff < 0) {
                connection.streams().values.foreach { stream =>
                  connection.outboundFlowControl.consumed(stream.outbound)
                }
                acc
              } else {
                connection.streams().values.foldLeft(acc) { (acc, a) =>
                  acc.flatMap(_ => connection.outboundFlowControl.produced(a.outbound))
                }
              }
            }

            case MAX_CONCURRENT_STREAMS => {
              connection.remoteSettings.maxConcurrentStreams() = value
              acc
            }

            case MAX_FRAME_SIZE => {
              connection.remoteSettings.maxFrameSize() = value
              acc
            }

            case MAX_HEADER_LIST_SIZE => {
              connection.remoteSettings.maxHeaderListSize() = value
              acc
            }

            case ENABLE_PUSH => {
              connection.remoteSettings.enablePush() = value == 1
              acc
            }

            case HEADER_TABLE_SIZE => {
              connection.remoteSettings.headerTableSize() = value
              acc
            }
          }
        }
        ok(res)
      }
      case None => {
        ok(Continue(()))
      }
    }
  }
}
