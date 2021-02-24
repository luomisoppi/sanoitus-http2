package sanoitus.http2.exchange
package priority

object RandomPrioritization extends Prioritization {
  def pickStream(streams: Set[Outbound]): Outbound =
    streams.head
}
