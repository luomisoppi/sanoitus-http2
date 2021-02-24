package sanoitus.http2.exchange
package priority

object FifoPrioritization extends Prioritization {
  def pickStream(streams: Set[Outbound]): Outbound =
    streams.minBy(_.id)
}
