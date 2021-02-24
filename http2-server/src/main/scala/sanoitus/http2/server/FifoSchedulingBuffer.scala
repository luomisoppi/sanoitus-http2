package sanoitus
package http2.server

import scala.concurrent.stm._
import scala.collection.immutable.Queue

import sanoitus.http2.utils._

case class FifoSchedulingBuffer[A]() extends SchedulingBuffer[A] {

  val queue: Ref[Either[Queue[Suspended[Option[A]]], Queue[A]]] = Ref(Right(Queue()))

  override def add(stream: A)(implicit tx: InTxn): Continue[Unit] = queue() match {
    case Left(suspendedQueue) if !suspendedQueue.isEmpty => {
      val (s, rest) = suspendedQueue.dequeue
      queue() = if (rest.isEmpty) Right(Queue()) else Left(rest)
      Continue((), List(Resumed(s, Right(Some(stream)))))
    }
    case Left(empty @ _) => {
      queue() = Right(Queue(stream))
      Continue(())
    }
    case Right(q) => {
      queue() = Right(q.enqueue(stream))
      Continue(())
    }
  }

  override def get(): SchedulingEffect[Option[A]] = { sus => implicit tx =>
    queue() match {
      case Right(reqs) if !reqs.isEmpty => {
        val (req, rest) = reqs.dequeue
        queue() = Right(rest)
        Continue(Some(req))
      }
      case Right(empty @ _) => {
        queue() = Left(Queue(sus))
        Suspend()
      }
      case Left(suspended) => {
        queue() = Left(suspended.enqueue(sus))
        Suspend()
      }
    }
  }
}
