/*
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.impl.engine.http2

import akka.NotUsed
import akka.http.impl.engine.http2.Http2Protocol.ErrorCode
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.http2.Http2StreamIdHeader
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{ BidiFlow, Flow, Keep }
import akka.stream.testkit.scaladsl.{ TestSink, TestSource }
import akka.stream.testkit.{ TestPublisher, TestSubscriber }
import akka.testkit.AkkaSpec
import org.scalatest.concurrent.Eventually

import scala.concurrent.duration._

class DemuxDecompressionSpec extends AkkaSpec("akka.loglevel = DEBUG") with Eventually {
  implicit val mat = ActorMaterializer()

  val encodedGET = hex"82"

  "The Http/2 server implementation" should {
    "work" in {
      val (netOut, netIn) = runServer(server, net, user)

      val headerBlock = encodedGET
      netIn.sendNext(HeadersFrame(1, endStream = true, endHeaders = true, headerBlock))

      netOut.requestNext(SettingsFrame(List.empty))
      netOut.expectNoMsg(100.millis)
    }

    "GO_AWAY on invalid header block fragment (4.3 Decompression)" in {
      val (netOut, netIn) = runServer(server, net, user)

      // Literal Header Field with Incremental Indexing without Length and String segment
      // See: https://github.com/summerwind/h2spec/blob/master/4_3.go#L18  
      val headerBlock = hex"00 00 01 01 05 00 00 00 01 40"
      netIn.sendNext(HeadersFrame(1, endStream = true, endHeaders = true, headerBlock))

      netOut.requestNext(GoAwayFrame(1, errorCode = ErrorCode.COMPRESSION_ERROR))
      netOut.expectNoMsg(100.millis)
    }
  }

  val server: BidiFlow[HttpResponse, FrameEvent, FrameEvent, HttpRequest, NotUsed] =
    Http2Blueprint.httpLayer() atop
      Http2Blueprint.hpack() atop
      Http2Blueprint.demux()

  val net: Flow[FrameEvent, FrameEvent, (TestSubscriber.Probe[FrameEvent], TestPublisher.Probe[FrameEvent])] =
    Flow.fromSinkAndSourceMat(TestSink.probe[FrameEvent], TestSource.probe[FrameEvent])(Keep.both)

  val user: Flow[HttpRequest, HttpResponse, NotUsed] =
    Flow[HttpRequest].map { req ⇒
      println(s"req = ${req}")
      HttpResponse().addHeader(req.header[Http2StreamIdHeader].get)
    }

  private def runServer(server: BidiFlow[HttpResponse, FrameEvent, FrameEvent, HttpRequest, NotUsed], net: Flow[FrameEvent, FrameEvent, (TestSubscriber.Probe[FrameEvent], TestPublisher.Probe[FrameEvent])], user: Flow[HttpRequest, HttpResponse, NotUsed]) = {
    val (netOut, netIn) = server
      .joinMat(net)(Keep.right)
      .join(user).run()

    netIn.ensureSubscription()
    netOut.ensureSubscription()
    (netOut, netIn)
  }
}
