package sanoitus
package http2
package server

import sanoitus.http2.exchange.RequestHeaders
import sanoitus.http2.exchange.ResponseHeaders

trait Http2ServerLanguage extends Language { self: Interpreter =>

  sealed trait Op[+A] extends Operation[A]

  type Connection
  type Request
  type Response

  /** Get a new incoming connection.
   *
   *  The executing program is suspended until a new connection is available or the interpreter is shutting down.
   *
   *  The arguments (except inboundBufferSize) are the HTTP/2 connection settings, defaulting to values specified by
   *  HTTP/2 specification (RFC 7540).
   *
   *  The inboundBufferSize value controls the connection-level flow control window. It specifies the amount of memory
   *  reserved for inbound data waiting to be processed.
   *
   *  The operation returns None if the interpreter is shutting down and thus, no more new connections will be
   *  available.
   */
  case class GetConnection(headerTableSize: Int = 4096,
                           enablePush: Boolean = true,
                           maxConcurrentStreams: Int = Integer.MAX_VALUE,
                           initialWindowSize: Int = 65535,
                           maxFrameSize: Int = 16384,
                           maxHeaderListSize: Int = Integer.MAX_VALUE,
                           inboundBufferSize: Int = 100 * 1024)
      extends Op[Option[Resource[Connection]]]

  /** Get a new incoming request from a connection.
   *
   *  The executing program is suspended until a new request is available or the connection is closed.
   *
   *  The operation returns None if the connection is closed.
   */
  case class GetRequest(connection: Connection) extends Op[Option[Resource[Request]]]

  /** Get headers of a request. */
  case class GetRequestHeaders(request: Request) extends Op[RequestHeaders]

  /** Read body of a request.
   *
   * The executing program is suspended until new data is available.
   *
   * Returns all available, previously unread data from the body of a request.
   *
   * When no more data is available, an empty array is returned.
   */
  case class ReadRequestBody(request: Request) extends Op[Array[Byte]]

  /** Starts a response to a request.
   *
   *  The executing program is suspended until the response headers are sent to the network.
   *
   *  Returns None if the response could not be sent (stream was cancelled or connection was closed).
   */
  case class StartResponse(request: Request, headers: ResponseHeaders, end: Boolean = false)
      extends Op[Option[Resource[Response]]]

  /** Writes body of a response.
   *
   *  The executing program is suspended until the data is sent to the network.
   *
   *  Sending empty array with end=false does nothing.
   *
   *  Returns true if data was sent successfully, false if it could not be sent (stream was cancelled or connection
   *  was closed).
   */
  case class WriteResponseBody(response: Response, data: Array[Byte], end: Boolean = false) extends Op[Boolean]
}
