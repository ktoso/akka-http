/*
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.impl.engine.http2

import java.io.ByteArrayOutputStream

import akka.dispatch.ExecutionContexts
import akka.http.impl.engine.http2.Http2Protocol.ErrorCode
import akka.http.impl.engine.http2.parsing.ByteStringInputStream
import akka.http.impl.engine.http2.parsing.HttpRequestHeaderHpackDecompression.HeaderDecompressionFailedException
import akka.http.scaladsl.model.headers.RawHeader
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.http2.Http2StreamIdHeader
import akka.stream.impl.fusing.GraphStages.SimpleLinearGraphStage
import akka.stream.scaladsl.{ Sink, Source }
import akka.stream.{ Attributes, BidiShape, Inlet, Outlet }
import akka.stream.stage._
import akka.util.ByteString
import com.twitter.hpack.HeaderListener

import scala.collection.immutable

final class HPackCompression extends GraphStage[BidiShape[HttpResponse, Http2SubStream, Http2SubStream, HttpRequest]] { stage ⇒

  final val responseIn = Inlet[HttpResponse]("HPackCompression.responseIn")
  final val subStreamOut = Outlet[Http2SubStream]("HPackCompression.subStreamOut")

  final val subStreamIn = Inlet[Http2SubStream]("HPackCompression.subStreamIn")
  final val requestOut = Outlet[HttpRequest]("HPackCompression.requestOut")

  override def shape = BidiShape(responseIn, subStreamOut, subStreamIn, requestOut)

  override def createLogic(inheritedAttributes: Attributes) = new GraphStageLogic(shape) with StageLogging with HPackDecompressionSide with HPackCompressionSide {

    @inline override final def subStreamIn = stage.subStreamIn
    @inline override final def requestOut = stage.requestOut

    @inline override final def responseIn = stage.responseIn
    @inline override final def subStreamOut = stage.subStreamOut

    override def preStart(): Unit = pull(subStreamIn)

  }

}

sealed trait HPackDecompressionSide extends BufferedOutletSupport { this: GraphStageLogic with StageLogging ⇒
  import HPackCompression._

  protected def subStreamIn: Inlet[Http2SubStream]
  protected def requestOut: Outlet[HttpRequest]

  // protected def responseIn: Inlet[HttpResponse]
  protected def subStreamOut: Outlet[Http2SubStream] // send failure here

  val decompressionInHandler = new InHandler with HeaderListener {
    /** While we're pulling a SubStreams frames, we should not pass through completion */
    private var pullingSubStreamFrames = false
    /** If the outer upstream has completed while we were pulling substream frames, we should complete it after we emit the request. */
    private var completionPending = false

    private[this] def resetBeingBuiltRequest(http2SubStream: Http2SubStream): Unit = {
      val streamId = http2SubStream.streamId

      beingBuiltRequest =
        HttpRequest(headers = immutable.Seq(Http2StreamIdHeader(streamId)))
          .withProtocol(HttpProtocols.`HTTP/2.0`)

      val entity =
        if (http2SubStream.initialFrame.endStream) {
          HttpEntity.Strict(ContentTypes.NoContentType, ByteString.empty)
        } else {
          // FIXME fix the size and entity type according to info from headers
          val data = http2SubStream.frames.completeAfter(_.endStream).map(_.payload)
          HttpEntity.Default(ContentTypes.NoContentType, Long.MaxValue, data) // FIXME that 1 is a hack, since it must be positive, and we're awaiting a real content length...
        }

      beingBuiltRequest = beingBuiltRequest.copy(entity = entity)
    }

    private[this] var beingBuiltRequest: HttpRequest = _ // TODO replace with "RequestBuilder" that's more efficient

    val decoder = new com.twitter.hpack.Decoder(maxHeaderSize, maxHeaderTableSize)

    override def onPush(): Unit = {
      val httpSubStream: Http2SubStream = grab(subStreamIn)
      // no backpressure (limited by SETTINGS_MAX_CONCURRENT_STREAMS)
      pull(subStreamIn)

      resetBeingBuiltRequest(httpSubStream)
      try processFrame(httpSubStream.initialFrame) catch {
        case ex: Throwable ⇒
          // FIXME toying around with solutions
          // complete(decompressOut)

          //          val GO_AWAY = GoAwayFrame(1, ErrorCode.COMPRESSION_ERROR, ByteString("Header decompression failed."))
          //          log.warning("Pushing GO_AWAY because compression failed: {}", ex.getMessage)
          //          push(subStreamOut, new Http2SubStream(null, Source.empty) {
          //            override def special = Some(GO_AWAY)
          //          })

          fail(subStreamOut, new Http2Compliance.DecompressionFailedException)
        //            emit(decompressOut, beingBuiltRequest.addHeader(Http2ForceGoAway))
      }

      if (httpSubStream.initialFrame.endStream) {
        // we know frames should be empty here.
        // but for sanity lets kill that stream anyway I guess (at least for now)
        requireRemainingStreamEmpty(httpSubStream)
      }
    }

    // this is invoked synchronously from decoder.decode()
    override def addHeader(name: Array[Byte], value: Array[Byte], sensitive: Boolean): Unit = {
      // FIXME wasteful :-(
      val nameString = new String(name)
      val valueString = new String(value)

      // FIXME lookup here must be optimised
      if (name.head == ColonByte) {
        nameString match {
          case ":method" ⇒
            val method = HttpMethods.getForKey(valueString)
              .getOrElse(throw new IllegalArgumentException(s"Unknown HttpMethod! Was: '$valueString'."))

            // FIXME only copy if value has changed to avoid churning allocs
            beingBuiltRequest = beingBuiltRequest.copy(method = method)

          case ":path" ⇒
            // FIXME only copy if value has changed to avoid churning allocs
            beingBuiltRequest = beingBuiltRequest.copy(uri = beingBuiltRequest.uri.withPath(Uri.Path(valueString)))

          case ":authority" ⇒
            beingBuiltRequest = beingBuiltRequest.copy(uri = beingBuiltRequest.uri.withAuthority(Uri.Authority.parse(valueString)))

          case ":scheme" ⇒
            beingBuiltRequest = beingBuiltRequest.copy(uri = beingBuiltRequest.uri.withScheme(valueString))

          // TODO handle all special headers

          case unknown ⇒
            throw new Exception(s": prefixed header should be emitted well-typed! Was: '${new String(unknown)}'. This is a bug.")
        }
      } else {
        nameString match {
          case "content-type" ⇒

            val entity = beingBuiltRequest.entity
            ContentType.parse(valueString) match {
              case Right(ct) ⇒
                val len = entity.contentLengthOption.getOrElse(0L)
                // FIXME instead of putting in random 1, this should become a builder, that emits the right type of entity (with known size or not)
                val newEntity =
                  if (len == 0) HttpEntity.Strict(ct, ByteString.empty) // HttpEntity.empty(entity.contentType)
                  else HttpEntity.Default(ct, len, entity.dataBytes)

                beingBuiltRequest = beingBuiltRequest.copy(entity = newEntity) // FIXME not quite correct still
              case Left(errorInfos) ⇒ throw new ParsingException(errorInfos.head)
            }

          case "content-length" ⇒
            val entity = beingBuiltRequest.entity
            val len = java.lang.Long.parseLong(valueString)
            val newEntity =
              if (len == 0) HttpEntity.Strict(entity.contentType, ByteString.empty) // HttpEntity.empty(entity.contentType)
              else HttpEntity.Default(entity.contentType, len, entity.dataBytes)

            beingBuiltRequest = beingBuiltRequest.copy(entity = newEntity) // FIXME not quite correct still

          case _ ⇒
            // other headers we simply expose as RawHeader
            // FIXME handle all typed headers
            beingBuiltRequest = beingBuiltRequest.addHeader(RawHeader(nameString, new String(value)))
        }
      }
    }

    override def onUpstreamFinish(): Unit = {
      if (pullingSubStreamFrames) {
        // we're currently pulling Frames out of the SubStream, thus we should not shut-down just yet
        completionPending = true // TODO I think we don't need this, can just rely on isClosed(in)?
      } else {
        // we've emitted all there was to emit, and can complete this stage
        completeStage()
      }
    }

    // TODO needs cleanup?
    private def processFrame(frame: StreamFrameEvent): Unit = {
      frame match {
        case h: HeadersFrame ⇒
          require(h.endHeaders, s"${getClass.getSimpleName} requires a complete HeadersFrame, the stage before it should concat incoming continuation frames into it.")

          // TODO optimise
          try {
            val is = ByteStringInputStream(h.headerBlockFragment)
            decoder.decode(is, this) // this: HeaderListener (invoked synchronously)
            decoder.endHeaderBlock()

            emit(requestOut, beingBuiltRequest)
          } catch {
            case _: java.io.IOException ⇒
              // the twitter HPack impl throws a raw IOException upon failure to decompress, we must specialize it 
              // in order to properly render a GO_AWAY as a reaction to this:
              //                fail(decompressOut, new Http2Compliance.DecompressionFailedException) // FIXME now we do nothing hm... since we failed and user should not get that request
              // complete(decompressOut)

              throw HeaderDecompressionFailedException // TODO do we want to add more information here?
          }

        case _ ⇒
          throw new UnsupportedOperationException(s"Not implemented to handle $frame! TODO / FIXME for impl.")
      }
    }

    // TODO if we can inspect it and it's really empty we don't need to materialize, for safety otherwise we cancel that stream
    private def requireRemainingStreamEmpty(httpSubStream: Http2SubStream): Unit = {
      if (httpSubStream.frames == Source.empty) ()
      else {
        // FIXME less aggresive once done with PoC
        implicit val ec = ExecutionContexts.sameThreadExecutionContext // ok, we'll just block and blow up, good.
        httpSubStream.frames.runWith(Sink.foreach(t ⇒ interpreter.log.warning("Draining element: " + t)))(interpreter.materializer)
          .map(_ ⇒ throw new IllegalStateException("Expected no more frames, but source was NOT empty! " +
            s"Draining the remaining frames, from: ${httpSubStream.frames}"))
      }
    }
  }

  // buffer outgoing requests if necessary (total number limited by SETTINGS_MAX_CONCURRENT_STREAMS)
  val bufferedRequestOut = new BufferedOutlet(requestOut)

  setHandler(subStreamIn, decompressionInHandler)

}

sealed trait HPackCompressionSide { this: GraphStageLogic ⇒
  import HPackCompression._

  def responseIn: Inlet[HttpResponse]
  def subStreamOut: Outlet[Http2SubStream]

  val compressionHandler = new InHandler with OutHandler {
    val encoder = new com.twitter.hpack.Encoder(maxHeaderTableSize)
    val os = new ByteArrayOutputStream() // FIXME: use a reasonable default size

    override def onPush(): Unit = {
      val response = grab(responseIn)
      // TODO possibly specialize static table? https://http2.github.io/http2-spec/compression.html#static.table.definition
      val headerBlockFragment = encodeResponse(response)

      def failBecauseOfMissingHeader: Nothing =
        // header is missing, shutting down because we will most likely otherwise miss a response and leak a substream
        // TODO: optionally a less drastic measure would be only resetting all the active substreams
        throw new RuntimeException("Received response for HTTP/2 request without Http2StreamIdHeader. Failing connection.")

      val streamId = response.header[Http2StreamIdHeader].getOrElse(failBecauseOfMissingHeader).streamId

      val dataFrames =
        if (response.entity.isKnownEmpty) Source.empty
        else
          response.entity.dataBytes.map(bytes ⇒ DataFrame(streamId, endStream = false, bytes)) ++
            Source.single(DataFrame(streamId, endStream = true, ByteString.empty))

      val headers = HeadersFrame(streamId, endStream = dataFrames == Source.empty, endHeaders = true, headerBlockFragment)
      val http2SubStream = Http2SubStream(headers, dataFrames)
      push(subStreamOut, http2SubStream)
    }

    def encodeResponse(response: HttpResponse): ByteString = {
      encoder.encodeHeader(os, StatusKey, response.status.intValue.toString.getBytes, false) // TODO so wasteful
      response.headers
        .filter(_.renderInResponses)
        .foreach { h ⇒
          val nameBytes = h.lowercaseName.getBytes
          val valueBytes = h.value.getBytes
          encoder.encodeHeader(os, nameBytes, valueBytes, false)
        }

      val res = ByteString(os.toByteArray)
      os.reset()
      res
    }

    override def onPull(): Unit = {
      if (!hasBeenPulled(responseIn)) pull(responseIn) // FIXME
    }

    setHandlers(responseIn, subStreamOut, this)
  }

}

object HPackCompression {
  final val ColonByte = ':'.toByte
  final val StatusKey = ":status".getBytes

  final val maxHeaderSize = 4096
  final val maxHeaderTableSize = 4096

  implicit class CompleteAfterSource[T](val s: Source[T, _]) extends AnyVal {
    /**
     * Passes through elements until the test returns `true`.
     * The element that triggered this is then passed through, and *after* that completion is signalled.
     */
    def completeAfter(test: T ⇒ Boolean) =
      s.via(new SimpleLinearGraphStage[T] {
        override def createLogic(inheritedAttributes: Attributes) = new GraphStageLogic(shape) with InHandler with OutHandler {
          override def onPush(): Unit = {
            val el = grab(in)
            if (test(el)) emit(out, el, () ⇒ completeStage())
            else push(out, el)
          }
          override def onPull(): Unit = pull(in)
          setHandlers(in, out, this)
        }
      })
  }

}
