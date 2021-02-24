package sanoitus.http2.exchange
package inbound

import scala.concurrent.stm._

trait InboundCheck[A] { self =>
  def apply(ctx: ProcessingContext, frame: A)(implicit tx: InTxn): Option[Err]

  def &&[B <: A](another: InboundCheck[B]) =
    new InboundCheck[B] {
      override def apply(ctx: ProcessingContext, frame: B)(implicit tx: InTxn): Option[Err] =
        self(ctx, frame) match {
          case None        => another(ctx, frame)
          case e @ Some(_) => e
        }
    }

  def >>[A2 <: A, B](pipe: InboundPipeline[A2, B]) =
    new InboundPipeline[A2, B] {
      override def apply(ctx: ProcessingContext, frame: A2)(implicit tx: InTxn): Either[Err, B] =
        self(ctx, frame) match {
          case None      => pipe(ctx, frame)
          case Some(err) => Left(err)
        }
    }

  def connectionError(code: Error.Code, message: String, cause: Option[Throwable] = None) =
    Some(Err(true, code, message, cause))

  def ok = None
}
