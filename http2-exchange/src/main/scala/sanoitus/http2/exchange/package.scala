package sanoitus.http2

package object exchange {

  def connectionError(code: Error.Code, message: String, cause: Option[Throwable] = None) =
    Left(Err(true, code, message, cause))

  def streamError(code: Error.Code, message: String, cause: Option[Throwable] = None) =
    Left(Err(false, code, message, cause))

  implicit def toFlags(map: Map[Int, Boolean]): Int =
    map.foldLeft(0) { (acc, a) =>
      acc | (if (a._2) a._1 else 0)
    }

  def pseudo(name: String): PseudoParser[String] = BasicPseudoParser(name)
  def pseudo[A](value: Either[Err, A]): PseudoParser[A] = UnitPseudoParser(value)

}
