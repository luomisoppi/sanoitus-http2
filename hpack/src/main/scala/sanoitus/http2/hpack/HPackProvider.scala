package sanoitus.http2.hpack

trait HPackProvider {
  def createEncoder(): HPackEncoder
  def createDecoder(): HPackDecoder
}
