package sanoitus.test.http2.wire.netty

import sanoitus._
import sanoitus.http2.utils.CertificateCreator
import sanoitus.http2.wire.netty.NettyHttp2WireInterpreter
import sanoitus.parallel.core.ParallelInterpreter
import sanoitus.stream.core.StreamInterpreter
import sanoitus.test.http2.wire.RawFrameTest

class NettyRawFrameTest extends RawFrameTest {

  override val port = 8765

  val (privateKey, keyCert) = CertificateCreator.create()

  override def createClientInterpreter() =
    NettyHttp2WireInterpreter(keyCert)

  override def createServerInterpreter(port: Int) =
    NettyHttp2WireInterpreter(port, privateKey, keyCert)

  override val es = BasicExecutionService(50, 1000)
  override val parallel = ParallelInterpreter
  override val streaming = StreamInterpreter
}
