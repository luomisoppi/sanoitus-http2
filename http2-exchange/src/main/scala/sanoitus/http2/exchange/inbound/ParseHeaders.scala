package sanoitus.http2.exchange
package inbound

import scala.concurrent.stm._
import sanoitus.http2.hpack.Header
import Error._

case class ParseHeaders[I <: Http2Frame](convertUppercaseHeaders: Boolean)
    extends InboundPipeline[I, Map[String, String]] {

  private def hasUpperCase(s: String): Boolean = !(s.toLowerCase() == s)

  def validateStructure[A](data: List[Header]): Option[String] = {
    val keys = data.map(_.name)
    val headerNamesWithUpperCaseLetters = keys.filter(hasUpperCase).toSet.mkString(",")
    val pseudoHeadersAfterHeaders = keys.dropWhile(_.startsWith(":")).filter(_.startsWith(":")).toSet.mkString(",")
    val duplicatePseudoHeaders =
      keys.filter(_.startsWith(":")).groupBy(identity).filter(_._2.length > 1).keySet.mkString(",")

    if (!headerNamesWithUpperCaseLetters.isBlank && !convertUppercaseHeaders) {
      Some(s"Header names contain uppercase letters: $headerNamesWithUpperCaseLetters")
    } else if (!pseudoHeadersAfterHeaders.isBlank) {
      Some(s"Pseudo-headers after headers: $pseudoHeadersAfterHeaders")
    } else if (!duplicatePseudoHeaders.isBlank) {
      Some(s"Duplicate values for pseudo-headers: $pseudoHeadersAfterHeaders")
    } else {
      None
    }
  }

  def validateValues[A](data: Map[String, String]): Option[String] = {
    val (isTeValid, teValue) =
      data.get("te").map(value => (value == "trailers", value)).getOrElse((true, ""))

    val hasConnectionSpecificHeader = data.contains("connection")

    if (!isTeValid) {
      Some(s"TE header value '$teValue' != 'trailers'")
    } else if (hasConnectionSpecificHeader) {
      Some("connection-specific header field not allowed")
    } else {
      None
    }
  }

  import sanoitus.util.MonoidOps
  import sanoitus.util.Monoid

  implicit val headerMonoid = new Monoid[String] {
    override def op(x: String, y: String): String = x + "," + y
    override val zero = ""
  }

  override def apply(ctx: ProcessingContext, frame: I)(implicit tx: InTxn) =
    for {
      data <- ctx.connection.latestDecoded.left.flatMap(error =>
        connectionError(COMPRESSION_ERROR, "Header decompression error", Some(error))
      )
      _ <- validateStructure(data).toLeft(()).left.flatMap(error => connectionError(PROTOCOL_ERROR, error))
      map = data.foldLeft(Map[String, String]()) { (acc, a) =>
        acc |+| Map((if (convertUppercaseHeaders) a.name.toLowerCase() else a.name) -> a.value)
      }
      _ <- validateValues(map).toLeft(()).left.flatMap(error => connectionError(PROTOCOL_ERROR, error))
    } yield map
}
