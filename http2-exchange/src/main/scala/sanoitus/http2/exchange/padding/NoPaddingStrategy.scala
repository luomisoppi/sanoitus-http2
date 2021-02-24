package sanoitus.http2.exchange.padding

import scala.concurrent.stm._

class NoPaddingStrategy extends PaddingStrategy {
  override def getPaddingAmountFor(totalToBeSent: Int)(implicit tx: InTxn): Option[Int] = None
}
