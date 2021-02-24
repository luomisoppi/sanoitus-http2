package sanoitus.http2.exchange

import scala.concurrent.stm._

trait ConnectionSettings {
  val headerTableSize: Ref[Int]
  val enablePush: Ref[Boolean]
  val maxConcurrentStreams: Ref[Int]
  val initialWindowSize: Ref[Int]
  val maxFrameSize: Ref[Int]
  val maxHeaderListSize: Ref[Int]
}

object ConnectionSettings {
  def apply(_headerTableSize: Int = 4096,
            _enablePush: Boolean = true,
            _maxConcurrentStreams: Int = Integer.MAX_VALUE,
            _initialWindowSize: Int = 65535,
            _maxFrameSize: Int = 16384,
            _maxHeaderListSize: Int = Integer.MAX_VALUE): ConnectionSettings =
    new ConnectionSettings {
      override val headerTableSize = Ref(_headerTableSize)
      override val enablePush = Ref(_enablePush)
      override val maxConcurrentStreams = Ref(_maxConcurrentStreams)
      override val initialWindowSize = Ref(_initialWindowSize)
      override val maxFrameSize = Ref(_maxFrameSize)
      override val maxHeaderListSize = Ref(_maxHeaderListSize)
    }
}
