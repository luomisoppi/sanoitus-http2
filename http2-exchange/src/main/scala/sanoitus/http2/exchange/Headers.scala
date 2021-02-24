package sanoitus.http2.exchange

case class RequestHeaders(scheme: String, authority: String, method: String, path: String, values: Map[String, String])

case class ResponseHeaders(status: Int, values: Map[String, String])
