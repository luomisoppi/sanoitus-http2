package sanoitus.http2.utils

import scala.concurrent.stm._
import sanoitus._

trait ExecutionPairing[A] {

  val pairing: Pairing[Suspended[A], A] = Pairing()
  val closed: Ref[Option[A]] = Ref(None)

  def close(a: A): List[Suspended[A]] =
    atomic { implicit tx =>
      if (!closed().isDefined) {
        closed() = Some(a)
        pairing.queue() match {
          case Some(Left(execs)) => {
            pairing.queue() = None
            execs.toList
          }
          case _ => List()
        }
      } else {
        List()
      }
    }

  def addValue(a: A): Option[Suspended[A]] = atomic { implicit tx =>
    if (closed().isDefined) None else pairing.addRight(a)
  }

  def addExec(exec: Suspended[A]): Option[A] =
    atomic { implicit tx =>
      (pairing.queue(), closed()) match {
        case (Some(Right(_)), _) => pairing.addLeft(exec)
        case (_, None)           => pairing.addLeft(exec)
        case (_, Some(a))        => Some(a)
      }
    }
}
