package sanoitus.http2.exchange
package priority

trait Prioritization {
  def pickStream(streams: Set[Outbound]): Outbound
}
