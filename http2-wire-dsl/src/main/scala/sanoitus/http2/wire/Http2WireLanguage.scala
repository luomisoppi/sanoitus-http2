package sanoitus.http2
package wire

import sanoitus._

trait Http2WireLanguage extends Language { self: Interpreter =>

  sealed trait Op[+A] extends Operation[A]

  type Connection

  case class Connect(host: String, port: Int) extends Op[Either[ConnectionNotSuccessful, Resource[Connection]]]

  case class ReadFrame(connection: Connection) extends Op[Option[Frame]]

  case class WriteFrame(connection: Connection, frame: Frame, frames: List[Frame] = List()) extends Op[Boolean]

  case class GetConnection() extends Op[Option[Resource[Connection]]]
}
