package sanoitus.http2.client

import sanoitus.http2.exchange.inbound.FrameProcessors

object ClientFrameProcessors extends FrameProcessors {
  override val convertUppercaseHeaders = true
  override val acceptPushPromise = true
}
