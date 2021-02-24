package sanoitus
package http2.server

import scala.concurrent.stm._
import sanoitus.http2.utils._

trait SchedulingBuffer[A] {
  def add(value: A)(implicit tx: InTxn): Continue[Unit]
  def get(): SchedulingEffect[Option[A]]
}
