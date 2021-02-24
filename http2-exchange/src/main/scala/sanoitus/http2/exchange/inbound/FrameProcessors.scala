package sanoitus.http2.exchange
package inbound

import scala.concurrent.stm._
import sanoitus.http2.utils.Continue

trait FrameProcessors {

  val convertUppercaseHeaders: Boolean
  val acceptPushPromise: Boolean

  implicit class FrameOps[A <: Http2Frame](frame: A) {
    def process(ctx: ProcessingContext)(implicit processor: FrameProcessor[A], tx: InTxn) = processor(ctx, frame)
  }

  implicit object HeadersProcessor extends FrameProcessor[Headers] {
    override def pipeline(connection: Connection) =
      (CheckPriority && CheckStreamConcurrencyLimit) >>
        (ParseHeaders[Headers](convertUppercaseHeaders) >> ProcessHeaders)
          .shortCircuit(!_.endHeaders, Continue(()))
  }

  implicit object SettingsProcessor extends FrameProcessor[Settings] {
    override def pipeline(connection: Connection) = ProcessSettings
  }

  implicit object WindowUpdateProcessor extends FrameProcessor[WindowUpdate] {
    override def pipeline(connection: Connection) = ProcessWindowUpdate
  }

  implicit object PriorityProcessor extends FrameProcessor[Priority] {
    override def pipeline(connection: Connection) = CheckPriority >> ProcessPriority
  }

  implicit object PingProcessor extends FrameProcessor[Ping] {
    override def pipeline(connection: Connection) = ProcessPing
  }

  implicit object RstStreamProcessor extends FrameProcessor[RstStream] {
    override def pipeline(connection: Connection) = ProcessRstStream
  }

  implicit object DataProcessor extends FrameProcessor[Data] {
    override def pipeline(connection: Connection) = ProcessData
  }

  implicit object ContinuationProcessor extends FrameProcessor[Continuation] {
    override def pipeline(connection: Connection) =
      (ParseHeaders[Continuation](convertUppercaseHeaders) >> ProcessHeaders).shortCircuit(!_.endHeaders, Continue(()))
  }

  implicit object GoAwayProcessor extends FrameProcessor[GoAway] {
    override def pipeline(connection: Connection) = ProcessGoAway
  }

  implicit object UnknownProcessor extends FrameProcessor[Unknown] {
    override def pipeline(connection: Connection) = ProcessUnknown(acceptPushPromise)
  }
}
