/*
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.impl.util

import java.util.concurrent.atomic.{ AtomicBoolean, AtomicReference }

import akka.NotUsed
import akka.actor.ActorSystem
import akka.stream._
import akka.stream.impl.fusing.GraphStages.SimpleLinearGraphStage
import akka.stream.impl._
import akka.stream.impl.fusing.GraphInterpreter
import akka.stream.scaladsl._
import akka.stream.stage._
import akka.util.ByteString
import org.reactivestreams.{ Processor, Publisher, Subscriber, Subscription }

import scala.concurrent.{ ExecutionContext, Future, Promise }

/**
 * INTERNAL API
 */
private[http] object StreamUtils {

  /**
   * Creates a transformer that will call `f` for each incoming ByteString and output its result. After the complete
   * input has been read it will call `finish` once to determine the final ByteString to post to the output.
   * Empty ByteStrings are discarded.
   */
  def byteStringTransformer(f: ByteString ⇒ ByteString, finish: () ⇒ ByteString): GraphStage[FlowShape[ByteString, ByteString]] = new SimpleLinearGraphStage[ByteString] {
    override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new GraphStageLogic(shape) with InHandler with OutHandler {
      override def onPush(): Unit = {
        val data = f(grab(in))
        if (data.nonEmpty) push(out, data)
        else pull(in)
      }

      override def onPull(): Unit = pull(in)

      override def onUpstreamFinish(): Unit = {
        val data = finish()
        if (data.nonEmpty) emit(out, data)
        completeStage()
      }

      setHandlers(in, out, this)
    }
  }

  /** USE WITH CAUTION: Drains (runs with Sink.ignore) a Source by locating the current GraphInterpreter, otherwise throws. */
  def kill[T, M](s: Source[T, M]): Unit = {
    GraphInterpreter.currentInterpreterOrNull match {
      case null ⇒ throw new UnsupportedOperationException(s"Cannot drop Source of type ${s.getClass.getName}, not running in scope of a GraphInterpreter!")
      case intp ⇒ s.runWith(Sink.ignore)(intp.subFusingMaterializer)
    }
  }

  def failedPublisher[T](ex: Throwable): Publisher[T] =
    impl.ErrorPublisher(ex, "failed").asInstanceOf[Publisher[T]]

  def captureTermination[T, Mat](source: Source[T, Mat]): (Source[T, Mat], Future[Unit]) = {
    val promise = Promise[Unit]()
    val transformer = new SimpleLinearGraphStage[T] {
      override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new GraphStageLogic(shape) with InHandler with OutHandler {
        override def onPush(): Unit = push(out, grab(in))

        override def onPull(): Unit = pull(in)

        override def onUpstreamFailure(ex: Throwable): Unit = {
          promise.tryFailure(ex)
          failStage(ex)
        }

        override def postStop(): Unit = {
          promise.trySuccess(())
        }

        setHandlers(in, out, this)
      }
    }
    source.via(transformer) → promise.future
  }

  def sliceBytesTransformer(start: Long, length: Long): Flow[ByteString, ByteString, NotUsed] = {
    val transformer = new SimpleLinearGraphStage[ByteString] {
      override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new GraphStageLogic(shape) with InHandler with OutHandler {
        override def onPull() = pull(in)

        var toSkip = start
        var remaining = length

        override def onPush(): Unit = {
          val element = grab(in)
          if (toSkip >= element.length)
            pull(in)
          else {
            val data = element.drop(toSkip.toInt).take(math.min(remaining, Int.MaxValue).toInt)
            remaining -= data.size
            push(out, data)
            if (remaining <= 0) completeStage()
          }
          toSkip -= element.length
        }

        setHandlers(in, out, this)
      }
    }
    Flow[ByteString].via(transformer).named("sliceBytes")
  }

  def limitByteChunksStage(maxBytesPerChunk: Int): GraphStage[FlowShape[ByteString, ByteString]] =
    new SimpleLinearGraphStage[ByteString] {
      override def initialAttributes = Attributes.name("limitByteChunksStage")

      override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new GraphStageLogic(shape) {

        var remaining = ByteString.empty

        def splitAndPush(elem: ByteString): Unit = {
          val toPush = remaining.take(maxBytesPerChunk)
          val toKeep = remaining.drop(maxBytesPerChunk)
          push(out, toPush)
          remaining = toKeep
        }

        setHandlers(in, out, WaitingForData)

        case object WaitingForData extends InHandler with OutHandler {
          override def onPush(): Unit = {
            val elem = grab(in)
            if (elem.size <= maxBytesPerChunk) push(out, elem)
            else {
              splitAndPush(elem)
              setHandlers(in, out, DeliveringData)
            }
          }

          override def onPull(): Unit = pull(in)
        }

        case object DeliveringData extends InHandler() with OutHandler {
          var finishing = false

          override def onPush(): Unit = throw new IllegalStateException("Not expecting data")

          override def onPull(): Unit = {
            splitAndPush(remaining)
            if (remaining.isEmpty) {
              if (finishing) completeStage() else setHandlers(in, out, WaitingForData)
            }
          }

          override def onUpstreamFinish(): Unit = if (remaining.isEmpty) completeStage() else finishing = true
        }

        override def toString = "limitByteChunksStage"
      }
    }

  /**
   * Returns a source that can only be used once for testing purposes.
   */
  def oneTimeSource[T, Mat](other: Source[T, Mat], errorMsg: String = "One time source can only be instantiated once"): Source[T, Mat] = {
    val onlyOnceFlag = new AtomicBoolean(false)
    other.mapMaterializedValue { elem ⇒
      if (onlyOnceFlag.get() || !onlyOnceFlag.compareAndSet(false, true))
        throw new IllegalStateException(errorMsg)
      elem
    }
  }

  /**
   * Similar to Source.maybe but doesn't rely on materialization. Can only be used once.
   */
  trait OneTimeValve {
    def source[T]: Source[T, NotUsed]
    def open(): Unit
  }
  object OneTimeValve {
    def apply(): OneTimeValve = new OneTimeValve {
      val promise = Promise[Unit]()
      val _source = Source.fromFuture(promise.future).drop(1) // we are only interested in the completion event

      def source[T]: Source[T, NotUsed] = _source.asInstanceOf[Source[T, NotUsed]] // safe, because source won't generate any elements
      def open(): Unit = promise.success(())
    }
  }

  /**
   * INTERNAL API
   *
   * Returns a flow that is almost identity but doesn't propagate cancellation from downstream to upstream.
   *
   * Note: This might create a resource leak if the upstream never completes. External measures
   * need to be taken to ensure that the upstream will always complete.
   */
  def absorbCancellation[T]: Flow[T, T, NotUsed] = Flow.fromGraph(new AbsorbCancellationStage)
  final class AbsorbCancellationStage[T] extends SimpleLinearGraphStage[T] {
    def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new GraphStageLogic(shape) with InHandler with OutHandler with StageLogging {
      setHandlers(in, out, this)

      def onPush(): Unit = push(out, grab(in)) // using `passAlong` was considered but it seems to need some boilerplate to make it work
      def onPull(): Unit = pull(in)

      override def onDownstreamFinish(): Unit = {
        // don't pass cancellation to upstream, and ignore eventually outstanding future element
        setHandler(in, new InHandler { def onPush(): Unit = log.debug("Ignoring unexpected data received after cancellation was ignored.") })
      }
    }
  }

  /**
   * Similar idea than [[FlowOps.statefulMapConcat]] but for a simple map.
   */
  def statefulMap[T, U](functionConstructor: () ⇒ T ⇒ U): Flow[T, U, NotUsed] =
    Flow[T].statefulMapConcat { () ⇒
      val f = functionConstructor()
      i ⇒ f(i) :: Nil
    }
}

/**
 * INTERNAL API
 */
private[http] class EnhancedByteStringSource[Mat](val byteStringStream: Source[ByteString, Mat]) extends AnyVal {
  def join(implicit materializer: Materializer): Future[ByteString] =
    byteStringStream.runFold(ByteString.empty)(_ ++ _)
  def utf8String(implicit materializer: Materializer, ec: ExecutionContext): Future[String] =
    join.map(_.utf8String)
}
