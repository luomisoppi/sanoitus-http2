package sanoitus.http2.utils

sealed trait SchedulingResult[A] {
  val value: Option[A]
  val resumed: List[Resumed]
  def resumeMap(f: List[Resumed] => List[Resumed]): SchedulingResult[A]
}

case class Suspend[A](override val resumed: List[Resumed] = List()) extends SchedulingResult[A] {
  override val value = None
  override def resumeMap(f: List[Resumed] => List[Resumed]): Suspend[A] = Suspend(f(resumed))
}

case class Continue[A](_value: A, override val resumed: List[Resumed] = List()) extends SchedulingResult[A] {
  override val value = Some(_value)

  def map[B](f: A => B): Continue[B] = Continue(f(_value), resumed)
  def flatMap[B](f: A => Continue[B]): Continue[B] = f(_value).resumeMap(r => (r ++ resumed).toSet.toList)
  def suspend[B]: Suspend[B] = Suspend[B](resumed)
  def resumeMap(f: List[Resumed] => List[Resumed]): Continue[A] = Continue(_value, f(resumed))
}
