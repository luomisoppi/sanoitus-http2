package sanoitus
package http2.utils

trait Resumed {
  type A
  val sus: Suspended[A]
  val res: Either[Throwable, A]
  def go(): Unit = sus.continueWith(res)
}

object Resumed {
  def apply[A0](suspended: Suspended[A0], resumeWith: Either[Throwable, A0]): Resumed = 
    new Resumed {
      override type A = A0
      override val sus = suspended
      override val res = resumeWith
    }
}