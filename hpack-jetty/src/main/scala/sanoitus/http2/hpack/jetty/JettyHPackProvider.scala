package sanoitus.http2.hpack
package jetty

import java.nio.ByteBuffer

import org.eclipse.jetty.http.HttpField
import org.eclipse.jetty.http.MetaData
import org.eclipse.jetty.http2.hpack.HpackDecoder
import org.eclipse.jetty.http2.hpack.HpackEncoder

import scala.jdk.CollectionConverters._

object JettyHPackProvider extends HPackProvider {

  case class header(override val name: String, override val value: String) extends Header

  override def createDecoder(): HPackDecoder = new HPackDecoder {

    val decoder = new HpackDecoder(4096, Integer.MAX_VALUE)

    override def setMaxTableSize(size: Int): Unit = decoder.setLocalMaxDynamicTableSize(size)

    override def decode(data: Array[Byte]): List[Header] = {
      val metadata = decoder.decode(ByteBuffer.wrap(data))
      val pseudoHeaders = metadata match {
        case metadata: MetaData.Request => {
          val uri = metadata.getURI()
          val scheme = header(":scheme", uri.getScheme())
          val authority = header(":authority", uri.getAuthority())
          val method = header(":method", metadata.getMethod())
          val path = header(":path", uri.getPathQuery())
          List(scheme, authority, method, path)
        }
        case metadata: MetaData.Response => List(header(":status", metadata.getStatus().toString()))
        case _                           => List()
      }

      pseudoHeaders ++
        metadata
          .iterator()
          .asScala
          .toList
          .map(field => header(field.getName(), field.getValue()))
    }
  }

  def createEncoder(): HPackEncoder = new HPackEncoder {

    val encoder = new HpackEncoder(4096)
    var buf = ByteBuffer.allocate(0)

    def sizeOf(header: Header): Int = header.name.length() + header.value.length()

    def requiredBufferSizeFor(headers: List[Header]): Int =
      (headers.map(sizeOf).sum * 2).toInt

    override def encode(headers: List[Header]): Array[Byte] = {
      val required = requiredBufferSizeFor(headers)
      if (buf.capacity() < required) {
        buf = ByteBuffer.allocate(required)
      }
      buf.position(0)
      headers.foreach(h => encoder.encode(buf, new HttpField(h.name, h.value)))
      val arr = new Array[Byte](buf.position())
      buf.position(0)
      buf.get(arr, 0, arr.length)
      arr
    }
  }
}
