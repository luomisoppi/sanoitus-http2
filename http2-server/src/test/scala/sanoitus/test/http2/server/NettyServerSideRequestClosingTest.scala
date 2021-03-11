package sanoitus.test.http2.server

import sanoitus._
import sanoitus.http2.client.Http2ClientInterpreter
import sanoitus.http2.hpack.jetty.JettyHPackProvider
import sanoitus.http2.server.Http2ServerInterpreter
import sanoitus.http2.utils.CertificateCreator
import sanoitus.http2.wire.netty.NettyHttp2WireInterpreter
import sanoitus.parallel.core.ParallelInterpreter
import sanoitus.stream.core.StreamInterpreter

class NettyServerSideRequestClosingTest extends ServerSideRequestClosingTest {

  override val port = 10465

  override val parallel = ParallelInterpreter

  val (privateKey, keyCert) = CertificateCreator.create()

  val serverWire = NettyHttp2WireInterpreter(port, privateKey, keyCert)

  override lazy val server =
    new Http2ServerInterpreter(serverWire, StreamInterpreter, ParallelInterpreter, JettyHPackProvider)

  val clientWire = NettyHttp2WireInterpreter(keyCert)
  override lazy val client =
    new Http2ClientInterpreter(clientWire, StreamInterpreter, ParallelInterpreter, JettyHPackProvider)

  override lazy val es = BasicExecutionService(50, 1000, new AnomalySink {
    override def error[A](exec: Execution[A], err: Throwable) = ()
  })
}
