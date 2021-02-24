package sanoitus.http2.exchange
package inbound

import scala.concurrent.stm._
import sanoitus.http2.utils.Continue

trait FrameProcessor[A <: Http2Frame] {
  val commonChecks = CheckFrameSize && CheckConsistencyOfHeaders
  def pipeline(connection: Connection): InboundPipeline[A, Continue[Unit]]

  def apply(ctx: ProcessingContext, frame: A)(implicit tx: InTxn): Either[Error, Continue[Unit]] =
    (commonChecks >> pipeline(ctx.connection))(ctx, frame) match {
      case Right(r)                         => Right(r)
      case Left(err) if err.connectionLevel => Left(ConnectionError(err.code, ctx.frame, err.message, err.cause))
      case Left(err)                        => Left(StreamError(err.code, ctx.frame, err.message, err.cause))
    }
}
