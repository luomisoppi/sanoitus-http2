package sanoitus.http2

sealed trait ConnectionNotSuccessful
package object wire {
  case object NameResolutionFailed extends ConnectionNotSuccessful
  case object ConnectionFailed extends ConnectionNotSuccessful
}
