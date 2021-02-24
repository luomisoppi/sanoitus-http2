package sanoitus.http2.exchange
package inbound

import sanoitus.http2.wire.Frame

case class ProcessingContext(connection: Connection, frame: Frame)
