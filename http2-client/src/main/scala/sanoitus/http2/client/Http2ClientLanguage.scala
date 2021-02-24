package sanoitus
package http2
package client

import sanoitus.http2.exchange.ResponseHeaders

trait Http2ClientLanguage extends Language { self: Interpreter =>

  sealed trait Op[+A] extends Operation[A]

  type Connection <: ClientLanguageConnection

  type Response

  /** Creates an HTTP/2 connection.
   *
   *  The executing program is suspended until the connection is established.
   *
   *  The arguments (except inboundBufferSize) are the HTTP/2 connection settings, defaulting to values specified by
   *  HTTP/2 specification (RFC 7540).
   *
   *  The inboundBufferSize value controls the connection-level flow control window. It specifies the amount of memory
   *  reserved for inbound data waiting to be processed.
   */
  case class Connect(host: String,
                     port: Int,
                     headerTableSize: Int = 4096,
                     enablePush: Boolean = true,
                     maxConcurrentStreams: Int = Integer.MAX_VALUE,
                     initialWindowSize: Int = 65535,
                     maxFrameSize: Int = 16384,
                     maxHeaderListSize: Int = Integer.MAX_VALUE,
                     inboundBufferSize: Int = 100 * 1024)
      extends Op[Either[ConnectionNotSuccessful, Resource[Connection]]]

  /** Starts a request.
   *
   *  The executing program is suspended until the request headers are sent to the network.
   *
   *  Returns None if the request could not be sent (connection was closed).
   */
  trait StartRequest extends Op[Option[Resource[Connection#Request]]] {
    val conn: Connection
    val method: String
    val path: String
    val headers: Map[String, String]
    val priority: conn.Priority
    val end: Boolean
  }

  // https://github.com/scala/bug/issues/7234 prevents using conn.defaultPriority as default value
  object StartRequest {
    def apply(_conn: Connection)(
      _method: String,
      _path: String,
      _headers: Map[String, String],
      _end: Boolean,
      _priority: Option[_conn.Priority]
    ): Operation[Option[Resource[Connection#Request]]] = new StartRequest {
      override val conn: _conn.type = _conn
      override val method = _method
      override val path = _path
      override val headers = _headers
      override val end = _end
      override val priority = _priority.getOrElse(conn.defaultPriority)
    }
  }

  /** Writes body of a request.
   *
   *  The executing program is suspended until the data is sent to the network.
   *
   *  Sending empty array with end=false does nothing.
   *
   *  Returns true if data was sent successfully, false if it could not be sent (stream was cancelled or connection
   *  was closed).
   */
  case class WriteRequestBody(request: Connection#Request, data: Array[Byte], end: Boolean = false) extends Op[Boolean]

  /** Get a response for a request.
   *
   *  The executing program is suspended until the response is available.
   *
   *  Returns None if there will not be a response (stream was cancelled or connection was closed).
   */
  case class GetResponse(request: Connection#Request) extends Op[Option[Resource[Response]]]

  /** Get headers of a response */
  case class GetResponseHeaders(response: Response) extends Op[ResponseHeaders]

  /** Read body of a response.
   *
   * The executing program is suspended until new data is available.
   *
   * Returns all available, previously unread data from the body of a response.
   *
   * When no more data is available, an empty array is returned.
   */
  case class ReadResponseBody(response: Response) extends Op[Array[Byte]]
}
