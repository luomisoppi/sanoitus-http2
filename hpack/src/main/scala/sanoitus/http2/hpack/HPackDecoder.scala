package sanoitus.http2.hpack

trait HPackDecoder {
  def decode(data: Array[Byte]): List[Header]
  def setMaxTableSize(size: Int): Unit
}
