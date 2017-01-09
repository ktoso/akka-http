/*
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.impl.engine.http2

import akka.util.ByteString

/**
 * Provides a simple way to parse string data like this: "040c 2f73 616d 706c 652f 7061 7468"
 * into its ByteString representation while interpreting reading the hex values.
 */
trait HexStringInterpolator {

  implicit final class HexByteStringHelper(val sc: StringContext) {
    def hex(args: Any*): ByteString = {
      val strings = sc.parts.iterator
      val expressions = args.iterator

      val buf = new StringBuffer(strings.next)
      while (strings.hasNext) {
        buf append expressions.next
        buf append strings.next
      }

      ByteString(buf.toString.replaceAll("\\s", "").trim.grouped(2).map(Integer.parseInt(_, 16).toByte).toArray)
    }
  }

}

object HexStringInterpolator extends HexStringInterpolator
