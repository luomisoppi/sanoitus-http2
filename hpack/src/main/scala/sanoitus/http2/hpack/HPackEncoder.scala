package sanoitus.http2.hpack

trait HPackEncoder {
  def encode(headers: List[Header]): Array[Byte]
}
