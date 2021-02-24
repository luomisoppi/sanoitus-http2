package sanoitus.http2.hpack

trait Header {
  val name: String
  val value: String

  override def toString = s"Header(name=$name, value=$value)"
}
