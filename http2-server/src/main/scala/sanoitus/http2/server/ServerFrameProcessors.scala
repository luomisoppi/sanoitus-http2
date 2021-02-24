package sanoitus.http2.server

import sanoitus.http2.exchange.inbound.FrameProcessors

object ServerFrameProcessors extends FrameProcessors {
  override val convertUppercaseHeaders = false
  override val acceptPushPromise = false
}
