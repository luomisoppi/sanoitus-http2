package sanoitus
package http2
package client

import scala.annotation.nowarn
import scala.concurrent.stm._
import scala.util.control.Exception.allCatch

import sanoitus.http2.exchange._
import sanoitus.http2.exchange.padding.NoPaddingStrategy
import sanoitus.http2.exchange.priority.FifoPrioritization
import sanoitus.http2.hpack.HPackProvider
import sanoitus.http2.wire.Http2WireLanguage
import sanoitus.http2.utils._
import sanoitus.stream.StreamLanguage

import Error._

case class ClientConnection(host: String,
                            port: Int,
                            wire: Http2WireLanguage,
                            stream: StreamLanguage,
                            hpack: HPackProvider,
                            wireConnection: Resource[_],
                            override val localSettings: ConnectionSettings,
                            bufferSize: Int)
    extends Connection
    with ClientLanguageConnection {

  override type ExchangeStream = ClientHttp2ExchangeStream

  override lazy val name = "Client"
  override val highestStreamId = Ref(-1)

  override val prioritization = FifoPrioritization
  override val paddingStrategy = new NoPaddingStrategy()

  override val authority = s"$host:$port"
  override val scheme = "https"

  override lazy val inboundBufferSize = bufferSize

  override val defaultPriority = new Priority {}
  override val root = new Dependable {}
  override type Priorizable = ClientHttp2ExchangeStream with Dependable
  override type Grouping = Priorizable
  override type Request = Priorizable

  def createRequest(method: String,
                    path: String,
                    headers: Map[String, String],
                    @nowarn priority: Priority,
                    end: Boolean) =
    schedulingEffect[Option[ClientConnection#Request]] { sus => implicit tx =>
      {
        val id = highestStreamId.transformAndGet(_ + 2)

        lazy val stream: ClientHttp2ExchangeStream with Dependable =
          new ClientHttp2ExchangeStream(id,
                                        this,
                                        RequestHeaders(scheme, authority, method, path, headers),
                                        localSettings.initialWindowSize(),
                                        remoteSettings.initialWindowSize(),
                                        end,
                                        sus.map((success: Boolean) => if (success) Some(stream) else None))
            with Dependable

        streams.transform(_ + ((id, stream)))
        outboundFlowControl.produced(stream.outbound).suspend
      }
    }

  val parse =
    for {
      status <- pseudo(":status").flatMap { status =>
        pseudo(allCatch.either(status.toInt).left.map(_ => Err(true, PROTOCOL_ERROR, s":status not numeric: $status")))
      }
      remains <- Remains
    } yield ResponseHeaders(status, remains)

  override def processHeaders(stream: Int,
                              headers: Map[String, String])(implicit tx: InTxn): Either[Err, Continue[Unit]] =
    streams().get(stream) match {
      case None => connectionError(PROTOCOL_ERROR, "Response headers on " + getStateOf(stream) + " stream " + stream)
      case Some(stream) =>
        for {
          responseHeaders <- parse(headers)
        } yield stream.inbound.addResponseHeaders(responseHeaders)

    }
}
