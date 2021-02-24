package sanoitus.http2.wire

case class Frame(length: Int, frameType: Int, flags: Int, reserved: Int, stream: Int, content: Array[Byte]) {
  def hasFlag(flag: Int): Boolean =
    (flags & flag) > 0
}
