package sanoitus.http2.utils

import scala.collection.immutable.Queue
import scala.concurrent.stm._

trait Pairing[A, B] {

  val queue: Ref[Option[Either[Queue[A], Queue[B]]]] = Ref(None)

  def addLeft(a: A): Option[B] = atomic { implicit tx =>
    val (returned, newQueue) = queue() match {
      case None           => (None, Some(Left(Queue(a))))
      case Some(Left(as)) => (None, Some(Left(as.enqueue(a))))
      case Some(Right(bs)) => {
        val (b, rest) = bs.dequeue
        if (rest.size == 0) (Some(b), None)
        else (Some(b), Some(Right(rest)))
      }
    }
    queue() = newQueue
    returned
  }

  def addRight(b: B): Option[A] = atomic { implicit tx =>
    val (returned, newQueue) = queue() match {
      case None            => (None, Some(Right(Queue(b))))
      case Some(Right(bs)) => (None, Some(Right(bs.enqueue(b))))
      case Some(Left(as)) => {
        val (a, rest) = as.dequeue
        if (rest.size == 0) (Some(a), None)
        else (Some(a), Some(Left(rest)))
      }
    }
    queue() = newQueue
    returned
  }
}

object Pairing {
  def apply[A, B]() = new Pairing[A, B] {}
}
