package sanoitus
package http2.exchange

case class Http2ExchangeData(data: Either[List[(String, String)], Array[Byte]],
                             maxFrameSize: Int,
                             writer: Option[Suspended[Boolean]],
                             end: Boolean)

case class Http2StreamData(id: Int, controlFrames: List[Http2ControlFrame], exchangeData: Option[Http2ExchangeData]) {
  lazy val length = exchangeData.flatMap(_.data.toOption).map(_.length).getOrElse(0)
}
