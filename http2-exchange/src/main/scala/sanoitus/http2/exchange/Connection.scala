package sanoitus
package http2.exchange

import scala.concurrent.stm._

import sanoitus.http2.exchange.padding.PaddingStrategy
import sanoitus.http2.exchange.priority.Prioritization
import sanoitus.http2.hpack.Header
import sanoitus.http2.utils._

trait Connection {

  type ExchangeStream <: Http2ExchangeStream

  var latestDecoded: Either[Throwable, List[Header]] = Right(List())
  var latestHeadersFrame: sanoitus.http2.exchange.Headers = null

  val name: String

  val highestStreamId: Ref[Int]

  val localSettings: ConnectionSettings
  val remoteSettings = ConnectionSettings()

  val inboundFlowControl = InboundFlowControl(this)
  val outboundFlowControl = OutboundFlowControl(this)

  val prioritization: Prioritization
  val paddingStrategy: PaddingStrategy

  val inboundWindow: Ref[Int] = Ref(localSettings.initialWindowSize.single.get)
  val outboundWindow: Ref[Int] = Ref(remoteSettings.initialWindowSize.single.get)

  val control = OutboundControl(this)
  val streams: Ref[Map[Int, ExchangeStream]] = Ref(Map())

  val previousFrame: Ref[Http2Frame] = Ref(null)

  val inboundBufferSize: Int

  atomic { implicit tx =>
    control.settings(localSettings)
    val updateSize = inboundBufferSize - localSettings.initialWindowSize()
    if (updateSize > 0) {
      control.windowUpdate(updateSize)
    }
  }

  def getStateOf(id: Int)(implicit tx: InTxn): StreamState =
    streams().get(id) match {
      case Some(s) => s.getState()
      case None    => if (id <= highestStreamId()) Closed else Idle
    }

  def processHeaders(stream: Int, headers: Map[String, String])(implicit tx: InTxn): Either[Err, Continue[Unit]]

  def consumeStream(outbound: Outbound): Program[Http2StreamData] = transactionalEffect[Http2StreamData] {
    implicit tx =>
      val allowance = outboundFlowControl.allowanceOf(outbound)
      val maxFrameSize = remoteSettings.maxFrameSize()
      val totalToBeSent = allowance.min(maxFrameSize).min(outbound.dataBytesAvailable.getOrElse(0))
      val paddingSize = paddingStrategy.getPaddingAmountFor(totalToBeSent).getOrElse(0)
      val data = outbound.consume(totalToBeSent - paddingSize)
      outboundFlowControl.consumed(outbound)
      data
  }
}
