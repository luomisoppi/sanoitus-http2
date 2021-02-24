package sanoitus.http2.exchange
package inbound

import scala.concurrent.stm._
import sanoitus.http2.utils.Continue

trait InboundPipeline[A, B] { self =>
  def apply(ctx: ProcessingContext, frame: A)(implicit tx: InTxn): Either[Err, B]

  def >>[C](another: InboundPipeline[B, C]) =
    new InboundPipeline[A, C] {
      override def apply(ctx: ProcessingContext, frame: A)(implicit tx: InTxn): Either[Err, C] =
        self(ctx, frame) match {
          case Right(b) => another(ctx, b)
          case Left(e)  => Left(e)
        }
    }

  def shortCircuit(f: A => Boolean, value: B): InboundPipeline[A, B] =
    new InboundPipeline[A, B] {
      override def apply(ctx: ProcessingContext, frame: A)(implicit tx: InTxn): Either[Err, B] =
        if (f(frame)) Right(value) else self(ctx, frame)
    }

  val ok = Right(Continue(()))
  def ok(cont: Continue[Unit]) = Right(cont)
}
