package sanoitus.http2

import scala.concurrent.stm._
import sanoitus._

package object utils {

  type TransactionalEffect[A] = scala.concurrent.stm.InTxn => A

  type SchedulingEffect[A] = sanoitus.Suspended[A] => TransactionalEffect[SchedulingResult[A]]

  def transactionalEffect[A](r: TransactionalEffect[A]) =
    effect[A] { _ => Some(atomic { implicit tx => r(tx) }) }

  def schedulingEffect[A](r: SchedulingEffect[A]) =
    effect[A] { sus =>
      val res = atomic { implicit tx => r(sus)(tx) }
      res.resumed.foreach(_.go())
      res.value
    }

  implicit class ByteOps(byte: Byte) {
    def uint = byte.toInt & 0xff
  }

  implicit class ByteArrayOps(bytes: Array[Byte]) {
    def uint(start: Int, numBytes: Int): Int =
      (0 to numBytes - 1).foldLeft(0) { (acc, index) =>
        acc + (bytes(start + index).uint << ((numBytes - 1 - index) * 8))
      }

    def writeUint(start: Int, value: Int, numBytes: Int = 1): Unit = {
      val mask = 0xffffffff >>> (32 - numBytes * 8)
      val stripped = value & mask
      for (index <- (numBytes - 1) to 0 by -1) {
        val value = (stripped >>> (index * 8)).byteValue()
        bytes(start + numBytes - 1 - index) = value
      }
    }
  }

  implicit class IntOps(value: Int) {
    def bytes(size: Int): Array[Byte] = {
      val array = new Array[Byte](size)
      (0 to size - 1).foreach { i => array(i) = ((value >> ((size - 1 - i) * 8)) & 0xff).byteValue() }
      array
    }
  }
}
