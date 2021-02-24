package sanoitus.http2.exchange

import sanoitus.http2.wire.Frame

sealed trait Error {
  val code: Error.Code
  val frame: Frame
  val message: String
  val cause: Option[Throwable]
}

object Error {
  type Code = Int
  val NO_ERROR: Code = 0x0
  val PROTOCOL_ERROR: Code = 0x1
  val INTERNAL_ERROR: Code = 0x2
  val FLOW_CONTROL_ERROR: Code = 0x3
  val SETTINGS_TIMEOUT: Code = 0x4
  val STREAM_CLOSED: Code = 0x5
  val FRAME_SIZE_ERROR: Code = 0x6
  val REFUSED_STREAM: Code = 0x7
  val CANCEL: Code = 0x8
  val COMPRESSION_ERROR: Code = 0x9
  val CONNECT_ERROR: Code = 0xa
  val ENHANCE_YOUR_CALM: Code = 0xb
  val INADEQUATE_SECURITY: Code = 0xc
  val HTTP_1_1_REQUIRED: Code = 0xd
}

case class ConnectionError(code: Error.Code, frame: Frame, message: String, cause: Option[Throwable] = None)
    extends Error

case class StreamError(code: Error.Code, frame: Frame, message: String, cause: Option[Throwable] = None) extends Error

case class Err(connectionLevel: Boolean, code: Error.Code, message: String, cause: Option[Throwable] = None)
