package sanoitus
package http2
package server

import scala.concurrent.stm._
import sanoitus.http2.utils._
import sanoitus.http2.exchange._
import sanoitus.http2.exchange.padding.NoPaddingStrategy
import sanoitus.http2.exchange.priority.RandomPrioritization
import Error._
import sanoitus.http2.exchange.ConnectionSettings
import sanoitus.http2.exchange.Connection

class ServerConnection(override val localSettings: ConnectionSettings, bufferSize: Int) extends Connection {

  override lazy val name = "Server"

  override val highestStreamId = Ref(-1)

  override val prioritization = RandomPrioritization
  override val paddingStrategy = new NoPaddingStrategy()

  override val inboundBufferSize = bufferSize

  override type ExchangeStream = ServerHttp2ExchangeStream

  val requests: Ref[SchedulingBuffer[ExchangeStream]] = Ref(FifoSchedulingBuffer[ExchangeStream]())

  def getRequest(): Program[Option[ExchangeStream]] =
    schedulingEffect {
      atomic { implicit tx => requests().get() }
    }

  val parse =
    for {
      scheme <- pseudo(":scheme")
      authority <- pseudo(":authority")
      method <- pseudo(":method")
      path <- pseudo(":path")
      remains <- Remains
    } yield RequestHeaders(scheme, authority, method, path, remains)

  override def processHeaders(unused: Int /*read from latestHeadersFrame*/,
                              headers: Map[String, String])(implicit tx: InTxn): Either[Err, Continue[Unit]] = {
    val state = getStateOf(latestHeadersFrame.stream)
    val frame = latestHeadersFrame

    if (state == Closed || state == HalfClosedRemote) {
      connectionError(PROTOCOL_ERROR, s"Stream in state $state receiving headers")
    } else if (frame.stream % 2 != 1) {
      connectionError(PROTOCOL_ERROR, "Client initializing stream with even id")
    } else if (state == Open && !frame.endStream) {
      connectionError(PROTOCOL_ERROR, "Trailing headers not ending the stream")
    } else if (state == Open && frame.endStream) {
      streams().get(frame.stream) match {
        case Some(stream) => Right(stream.inbound.writeData(new Array(0), true))
        case None         => Right(Continue(()))
      }
    } else {
      parse(headers).map { headers =>
        highestStreamId() = frame.stream

        val stream = ServerHttp2ExchangeStream(frame.stream,
                                               this,
                                               headers,
                                               localSettings.initialWindowSize(),
                                               remoteSettings.initialWindowSize(),
                                               frame.endStream)
        streams.transform(_ + ((stream.id, stream)))
        requests().add(stream)
      }
    }
  }
}
