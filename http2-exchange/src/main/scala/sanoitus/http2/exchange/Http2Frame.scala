package sanoitus.http2.exchange

import scala.collection.mutable.ArrayBuffer

import sanoitus.http2.utils._
import sanoitus.http2.wire.Frame

sealed trait Http2Frame {
  val stream: Int
  def toWire(): Frame
}

sealed trait Http2ControlFrame extends Http2Frame

object Http2Frame {
  val Data = 0x0
  val Headers = 0x1
  val Priority = 0x2
  val RstStream = 0x3
  val Settings = 0x4
  val PushPromise = 0x5
  val Ping = 0x6
  val GoAway = 0x7
  val WindowUpdate = 0x8
  val Continuation = 0x9

  def parse(frame: Frame): Either[Error, Http2Frame] =
    frame.frameType match {
      case Data         => sanoitus.http2.exchange.Data(frame)
      case Headers      => sanoitus.http2.exchange.Headers(frame)
      case Priority     => sanoitus.http2.exchange.Priority(frame)
      case RstStream    => sanoitus.http2.exchange.RstStream(frame)
      case Settings     => sanoitus.http2.exchange.Settings.fromFrame(frame)
      case Ping         => sanoitus.http2.exchange.Ping(frame)
      case GoAway       => sanoitus.http2.exchange.GoAway(frame)
      case WindowUpdate => sanoitus.http2.exchange.WindowUpdate(frame)
      case Continuation => sanoitus.http2.exchange.Continuation(frame)
      case _            => sanoitus.http2.exchange.Unknown(frame)
    }
}

case class Data(override val stream: Int, data: Array[Byte], endStream: Boolean, padding: Option[Int])
    extends Http2Frame {

  override def toWire() = {
    val length = padding match {
      case Some(padding) => padding + 1 + data.length
      case None          => data.length
    }

    val flags = Map(Data.END_STREAM -> endStream, Data.PADDED -> padding.isDefined)

    val content =
      padding match {
        case Some(padding) => {
          val buf = new ArrayBuffer[Byte](length)
          buf += padding.byteValue
          buf ++= data
          buf ++= new Array[Byte](padding)
          buf.toArray
        }
        case None => data
      }

    Frame(length, Http2Frame.Data, flags, 0x0, stream, content)
  }

  override def toString() = {
    val displayed =
      if (data.length > 50) {
        data.take(20).map("%02X".format(_)).mkString + "..." + data.takeRight(20).map("%02X".format(_)).mkString
      } else {
        data.map("%02X".format(_)).mkString
      }
    s"Data(stream=$stream, endStream=$endStream, padding=$padding, content length=${data.length}, data=${displayed})"
  }
}

object Data {
  val END_STREAM = 0x1
  val PADDED = 0x8

  def apply(frame: Frame): Either[Error, Data] = {
    var content = frame.content
    val padLength = if (frame.hasFlag(PADDED)) Some(content.uint(0, 1)) else None
    padLength.foreach(_ => content = content.tail)
    val data = content.dropRight(padLength.getOrElse(0))

    if (frame.stream == 0) {
      Left(ConnectionError(Error.PROTOCOL_ERROR, frame, "DATA frame on stream 0"))
    } else if (padLength.getOrElse(-1) >= frame.length) {
      Left(ConnectionError(Error.PROTOCOL_ERROR, frame, "DATA frame padding larger than frame"))
    } else {
      Right(new Data(frame.stream, data, frame.hasFlag(END_STREAM), padLength))
    }
  }
}

case class PriorityBlock(exclusive: Boolean, dependency: Int, weight: Int) {
  override def toString() = s"exclusive=$exclusive, dependency=$dependency, weight=$weight, "
}

case class Headers(override val stream: Int,
                   priorities: Option[PriorityBlock],
                   blockFragment: Array[Byte],
                   endHeaders: Boolean,
                   endStream: Boolean,
                   padding: Option[Int])
    extends Http2Frame { self =>

  override def toWire(): Frame = {

    val length = padding.map(_ + 1).getOrElse(0) +
      priorities.map(_ => 5).getOrElse(0) +
      blockFragment.length

    val flags = Map(
      Headers.END_STREAM -> endStream,
      Headers.END_HEADERS -> endHeaders,
      Headers.PADDED -> padding.isDefined,
      Headers.PRIORITY -> priorities.isDefined
    )

    val buf = new ArrayBuffer[Byte](length)

    padding.foreach(buf += _.byteValue)
    priorities.foreach { p =>
      buf ++= (p.dependency | ((if (p.exclusive) 1 else 0) << 31)).bytes(4)
      buf += p.weight.byteValue
    }
    buf ++= blockFragment
    padding.foreach(buf ++= new Array[Byte](_))

    val content = buf.toArray
    Frame(length, Http2Frame.Headers, flags, 0x0, stream, content)
  }

  override def toString(): String =
    s"Headers(stream=$stream, ${priorities.map(_.toString).getOrElse("")}endHeaders=$endHeaders, endStream=$endStream, padding=$padding, frag=${blockFragment.map("%02X".format(_)).mkString})"
}

object Headers {
  val END_STREAM = 0x1
  val END_HEADERS = 0x4
  val PADDED = 0x8
  val PRIORITY = 0x20

  def apply(frame: Frame): Either[Error, Headers] = {
    var content = frame.content

    val padLength = if (frame.hasFlag(PADDED)) Some(content.uint(0, 1)) else None
    padLength.foreach(_ => content = content.tail)

    val priority =
      if (frame.hasFlag(PRIORITY)) {
        val exclusive = (content.uint(0, 1) >>> 7) == 1
        val dependency = content.uint(0, 4) & 0x7fffffff
        val weight = content.uint(4, 1)
        Some(PriorityBlock(exclusive, dependency, weight))
      } else None
    priority.foreach(_ => content = content.drop(5))

    val fragment = content.dropRight(padLength.getOrElse(0))

    if (frame.stream == 0) {
      Left(ConnectionError(Error.PROTOCOL_ERROR, frame, "HEADERS frame on stream 0"))
    } else if (padLength.getOrElse(-1) >= frame.length) {
      Left(ConnectionError(Error.PROTOCOL_ERROR, frame, "HEADERS frame padding larger than frame"))
    } else {
      Right(
        new Headers(frame.stream, priority, fragment, frame.hasFlag(END_HEADERS), frame.hasFlag(END_STREAM), padLength)
      )
    }
  }
}

case class Priority(override val stream: Int, priority: PriorityBlock) extends Http2ControlFrame {

  override def toWire(): Frame = {
    val length = 5
    val content = new Array[Byte](length)
    content.writeUint(0, (priority.dependency | ((if (priority.exclusive) 1 else 0) << 31)), 4)
    content.writeUint(4, priority.weight, 1)
    Frame(length, Http2Frame.Priority, Map[Int, Boolean](), 0x0, stream, content)
  }

  override def toString() =
    s"Priority(stream=$stream, $priority)"
}

object Priority {

  def apply(frame: Frame): Either[Error, Priority] =
    if (frame.stream == 0) {
      Left(ConnectionError(Error.PROTOCOL_ERROR, frame, "PRIORITY frame on stream 0"))
    } else if (frame.length != 5) {
      Left(StreamError(Error.FRAME_SIZE_ERROR, frame, "PRIORITY frame size != 5"))
    } else {
      val content = frame.content
      val exclusive = (content.uint(0, 1) >>> 7) == 1
      val dependency = content.uint(0, 4) & 0x7fffffff
      val weight = content.uint(4, 1)

      Right(new Priority(frame.stream, PriorityBlock(exclusive, dependency, weight)))
    }
}

case class RstStream(override val stream: Int, errorCode: Error.Code) extends Http2ControlFrame {

  override def toWire(): Frame = {
    val length = 4
    val content = new Array[Byte](length)
    content.writeUint(0, errorCode, 4)
    Frame(length, Http2Frame.RstStream, Map[Int, Boolean](), 0x0, stream, content)
  }

  override def toString() =
    s"RstStream(stream=$stream, errorCode=$errorCode)"
}

object RstStream {
  def apply(frame: Frame): Either[Error, RstStream] =
    if (frame.stream == 0) {
      Left(ConnectionError(Error.PROTOCOL_ERROR, frame, "RST_STREAM frame on stream 0"))
    } else if (frame.length != 4) {
      Left(StreamError(Error.FRAME_SIZE_ERROR, frame, "RST_STREAM frame size != 4"))
    } else {
      Right(new RstStream(frame.stream, frame.content.uint(0, 4)))
    }
}

sealed abstract class Setting(val identifier: Int, val name: String) {
  def check(value: Int, frame: Frame): Option[Error]
}
case object HEADER_TABLE_SIZE extends Setting(0x1, "HEADER_TABLE_SIZE") {
  override def check(value: Int, frame: Frame): Option[Error] = None
}
case object ENABLE_PUSH extends Setting(0x2, "ENABLE_PUSH") {
  override def check(value: Int, frame: Frame): Option[Error] =
    if (value == 0 || value == 1) None
    else Some(ConnectionError(Error.PROTOCOL_ERROR, frame, s"Invalid ENABLE_PUSH value: $value"))
}
case object MAX_CONCURRENT_STREAMS extends Setting(0x3, "MAX_CONCURRENT_STREAMS") {
  override def check(value: Int, frame: Frame): Option[Error] = None
}
case object INITIAL_WINDOW_SIZE extends Setting(0x4, "INITIAL_WINDOW_SIZE") {
  override def check(value: Int, frame: Frame): Option[Error] =
    if (value < 0) Some(ConnectionError(Error.FLOW_CONTROL_ERROR, frame, s"Invalid INITIAL_WINDOW_SIZE value: $value"))
    else None
}
case object MAX_FRAME_SIZE extends Setting(0x5, "MAX_FRAME_SIZE") {
  override def check(value: Int, frame: Frame): Option[Error] =
    if (value < 16384 || value > 16777215)
      Some(ConnectionError(Error.PROTOCOL_ERROR, frame, s"Invalid MAX_FRAME_SIZE value: $value"))
    else None
}
case object MAX_HEADER_LIST_SIZE extends Setting(0x6, "MAX_HEADER_LIST_SIZE") {
  override def check(value: Int, frame: Frame): Option[Error] = None
}

case class Settings(settings: Option[Map[Setting, Int]]) extends Http2ControlFrame {
  override val stream = 0

  override def toWire(): Frame = {
    val length = settings.map(_.keySet.size * 6).getOrElse(0)
    val flags = Map(Settings.ACK -> settings.isEmpty)
    val content = new Array[Byte](length)
    settings.foreach(_.zipWithIndex.foreach { kvi =>
      val ((key, value), index) = kvi
      content.writeUint(index * 6, key.identifier, 2)
      content.writeUint(index * 6 + 2, value, 4)
    })
    Frame(length, Http2Frame.Settings, flags, 0x0, stream, content)
  }

  override def toString() =
    settings match {
      case None                         => "Settings(ack=true)"
      case Some(data) if data.size == 0 => "Settings(ack=false)"
      case Some(data) => {
        "Settings(ack=false, " +
          data
            .map({
              case (key, value) =>
                val name = key.name
                s"$name: $value"
            })
            .mkString(", ") +
          ")"
      }
    }
}

object Settings {

  val settings = Set(HEADER_TABLE_SIZE,
                     ENABLE_PUSH,
                     MAX_CONCURRENT_STREAMS,
                     INITIAL_WINDOW_SIZE,
                     MAX_FRAME_SIZE,
                     MAX_HEADER_LIST_SIZE)

  val ACK = 0x1

  def fromFrame(frame: Frame): Either[Error, Settings] =
    if (frame.stream != 0) {
      Left(ConnectionError(Error.PROTOCOL_ERROR, frame, "SETTINGS frame on stream != 0"))
    } else if (frame.length % 6 != 0) {
      Left(ConnectionError(Error.FRAME_SIZE_ERROR, frame, "SETTINGS frame length not multiple of 6 octets"))
    } else if (frame.hasFlag(ACK)) {
      if (frame.length != 0) {
        Left(ConnectionError(Error.FRAME_SIZE_ERROR, frame, "SETTINGS acknowledgment with frame of non-zero size"))
      } else {
        Right(new Settings(None))
      }
    } else {
      val count = frame.length / 6
      val content = frame.content

      val values = (0 until count).foldLeft(Right(Map()): Either[Error, Map[Setting, Int]]) { (acc, num) =>
        acc match {
          case Left(_) => acc
          case Right(map) => {
            val index = num * 6
            val identifier = content.uint(index, 2)
            val value = content.uint(index + 2, 4)

            settings.find(_.identifier == identifier) match {
              case Some(setting) =>
                setting.check(value, frame) match {
                  case None      => Right(map + ((setting, value)))
                  case Some(err) => Left(err)
                }
              case None => acc
            }
          }
        }
      }

      values.map(v => new Settings(Some(v)))
    }

}

case class Ping(ack: Boolean, data: Array[Byte]) extends Http2ControlFrame {
  override val stream = 0

  override def toWire(): Frame = {
    val length = 8
    val flags = Map(Ping.ACK -> ack)
    val content = data
    Frame(length, Http2Frame.Ping, flags, 0x0, stream, content)
  }

  override def toString() =
    s"Ping(ack=$ack, data=${data.map("%02X".format(_)).mkString})"
}

object Ping {
  val ACK = 0x1

  def apply(frame: Frame): Either[Error, Ping] =
    if (frame.stream != 0) {
      Left(ConnectionError(Error.PROTOCOL_ERROR, frame, "PING frame on stream != 0"))
    } else if (frame.length != 8) {
      Left(ConnectionError(Error.FRAME_SIZE_ERROR, frame, "PING frame length != 8"))
    } else {
      Right(new Ping(frame.hasFlag(ACK), frame.content))
    }
}

case class GoAway(lastStreamId: Int, errorCode: Int, debugData: Option[Array[Byte]]) extends Http2ControlFrame {
  override val stream = 0

  override def toWire(): Frame = {
    val length = 8 + debugData.map(_.length).getOrElse(0)

    val buf = new ArrayBuffer[Byte](length)
    buf ++= lastStreamId.bytes(4)
    buf ++= errorCode.bytes(4)
    debugData.foreach(buf ++= _)
    val content = buf.toArray

    Frame(length, Http2Frame.GoAway, Map[Int, Boolean](), 0x0, stream, content)
  }

  override def toString() =
    debugData match {
      case None => s"GoAway(lastStreamId=$lastStreamId, errorCode=$errorCode)"
      case Some(data) =>
        s"GoAway(lastStreamId=$lastStreamId, errorCode=$errorCode, debugData=${data.map("%02X".format(_)).mkString})"
    }
}

object GoAway {
  def apply(frame: Frame): Either[Error, GoAway] =
    if (frame.stream != 0) {
      Left(ConnectionError(Error.PROTOCOL_ERROR, frame, "GOAWAY frame with non-zero stream id"))
    } else {
      Right(
        new GoAway(frame.content.uint(0, 4),
                   frame.content.uint(4, 4),
                   if (frame.length == 8) None else Some(frame.content.drop(8)))
      )
    }
}

case class WindowUpdate(override val stream: Int, _increment: Int) extends Http2ControlFrame {
  val increment = (_increment << 1) >> 1

  override def toWire(): Frame = {
    val length = 4
    val content = new Array[Byte](length)
    content.writeUint(0, increment, 4)
    Frame(length, Http2Frame.WindowUpdate, Map[Int, Boolean](), 0x0, stream, content)
  }

  override def toString() =
    s"WindowUpdate(stream=$stream, increment=$increment)"
}

object WindowUpdate {
  def apply(frame: Frame): Either[Error, WindowUpdate] =
    if (frame.length != 4) {
      Left(ConnectionError(Error.FRAME_SIZE_ERROR, frame, "WINDOW_UPDATE frame with length != 4"))
    } else {

      val update = new WindowUpdate(frame.stream, (frame.content.uint(0, 4) << 1) >> 1)
      if (update.increment == 0) {
        if (update.stream == 0) {
          Left(ConnectionError(Error.PROTOCOL_ERROR, frame, "WINDOW_UPDATE of zero size"))
        } else {
          Left(StreamError(Error.PROTOCOL_ERROR, frame, "WINDOW_UPDATE of zero size"))
        }
      } else {
        Right(update)
      }
    }
}

case class Continuation(override val stream: Int, blockFragment: Array[Byte], endHeaders: Boolean) extends Http2Frame {
  self =>

  override def toWire(): Frame = {

    val length = blockFragment.length

    val flags = Map(
      Continuation.END_HEADERS -> endHeaders
    )

    Frame(length, Http2Frame.Continuation, flags, 0x0, stream, blockFragment)
  }

  override def toString(): String =
    s"Continuation(stream=$stream, endHeaders=$endHeaders, frag=${blockFragment.map("%02X".format(_)).mkString})"
}

object Continuation {
  val END_HEADERS = 0x4

  def apply(frame: Frame): Either[Error, Continuation] =
    if (frame.stream == 0) {
      Left(ConnectionError(Error.PROTOCOL_ERROR, frame, "CONTINUATION frame on stream 0"))
    } else {
      Right(new Continuation(frame.stream, frame.content, frame.hasFlag(END_HEADERS)))
    }
}

case class Unknown(override val stream: Int, frame: Frame) extends Http2Frame {

  override def toWire() = frame

  override def toString(): String =
    s"Unknown(type=${frame.frameType})"
}

object Unknown {
  def apply(frame: Frame): Either[Error, Unknown] =
    Right(new Unknown(frame.stream, frame))
}
