package sanoitus.http2.exchange

case class ParseResult[A](value: A, remains: Map[String, String])

sealed trait PseudoParser[A] {
  def map[B](f: A => B): PseudoParser[B] = FlatMapPseudoParser(this, (a: A) => UnitPseudoParser(Right(f(a))))
  def flatMap[B](f: A => PseudoParser[B]): PseudoParser[B] = FlatMapPseudoParser(this, f)
  def run(headers: Map[String, String]): Either[Err, ParseResult[A]]
  def apply(headers: Map[String, String]): Either[Err, A] = run(headers).map { _.value }
}

private case class UnitPseudoParser[A](value: Either[Err, A]) extends PseudoParser[A] {
  def run(headers: Map[String, String]): Either[Err, ParseResult[A]] = value.map(ParseResult(_, headers))
}

case class BasicPseudoParser(name: String) extends PseudoParser[String] {
  def run(headers: Map[String, String]): Either[Err, ParseResult[String]] =
    headers
      .get(name)
      .map(ParseResult(_, headers - name))
      .toRight(Err(true, Error.PROTOCOL_ERROR, s"$name missing"))
}

private case class FlatMapPseudoParser[A, B](from: PseudoParser[A], f: A => PseudoParser[B]) extends PseudoParser[B] {
  def run(headers: Map[String, String]): Either[Err, ParseResult[B]] =
    for {
      init <- from.run(headers)
      result <- f(init.value).run(init.remains)
    } yield result
}

case object Remains extends PseudoParser[Map[String, String]] {
  def run(headers: Map[String, String]): Either[Err, ParseResult[Map[String, String]]] =
    UnitPseudoParser(Right(headers)).run(headers)
}
