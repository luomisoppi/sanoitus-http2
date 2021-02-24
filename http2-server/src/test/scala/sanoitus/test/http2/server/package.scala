package sanoitus.test.http2

import org.scalacheck.Gen

import scala.jdk.CollectionConverters._

package object server {

  type StreamId = Option[Int]

  def adj[A, B](m: Map[A, B], k: A)(f: B => B): Map[A, B] = m.updated(k, f(m(k)))

  implicit class MapOps[A, B](map: Map[A, B]) {
    def adjust(k: A)(f: B => B): Map[A, B] = adj(map, k)(f)
  }

  def seq[A](gens: List[Gen[A]]): Gen[List[A]] =
    Gen.sequence(gens).map(_.asScala.toList)
}
