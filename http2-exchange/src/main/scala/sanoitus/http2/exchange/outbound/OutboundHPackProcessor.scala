package sanoitus
package http2.exchange.outbound

import sanoitus.http2.hpack.Header
import sanoitus.http2.hpack.HPackEncoder

class OutboundHPackProcessor(encoder: HPackEncoder) {

  def encode(headers: List[(String, String)]): Program[Array[Byte]] = effect[Array[Byte]] { _ =>
    Some(
      encoder.encode(
        headers.map(kv =>
          new Header {
            override val name = kv._1
            override val value = kv._2
          }
        )
      )
    )
  }
}
