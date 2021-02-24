package sanoitus.http2
package client

trait ClientLanguageConnection { self =>
  val scheme: String
  val authority: String

  trait Dependable {
    type P = Priority
  }

  type Priorizable <: Dependable

  type Grouping <: Priorizable

  type Request <: Priorizable

  trait Priority {
    type G = Grouping
  }

  val root: Dependable
  val defaultPriority: Priority
}
