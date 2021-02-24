package sanoitus.http2.exchange.padding

import scala.concurrent.stm._

trait PaddingStrategy {
  def getPaddingAmountFor(totalToBeSent: Int)(implicit tx: InTxn): Option[Int]
}
