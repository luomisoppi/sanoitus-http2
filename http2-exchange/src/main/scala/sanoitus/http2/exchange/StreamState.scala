package sanoitus.http2.exchange

sealed trait StreamState
case object Idle extends StreamState
case object Open extends StreamState
case object HalfClosedLocal extends StreamState
case object HalfClosedRemote extends StreamState
case object Closed extends StreamState
