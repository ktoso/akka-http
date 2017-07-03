/*
 * Copyright (C) 2017 Lightbend Inc. <http://www.lightbend.com/>
 */

package akka.http.javadsl.server.directives

import akka.annotation.ApiMayChange
import akka.http.caching.javadsl.Cache
import akka.http.caching.scaladsl
import akka.http.javadsl.model.Uri
import akka.http.javadsl.model.headers.CacheDirectives.MAX_AGE
import akka.http.javadsl.model.headers.CacheDirectives.NO_CACHE
import akka.http.javadsl.model.headers.CacheControl
import akka.http.javadsl.server.{ RequestContext, RouteResult, RoutingJavaMapping }

import scala.concurrent.duration.Duration

@ApiMayChange
class CachingDirectives {

  import RoutingJavaMapping.Implicits._
  import akka.http.scaladsl.server.directives.{ CachingDirectives ⇒ D }

  /**
   * Wraps its inner Route with caching support using the given [[akka.http.caching.scaladsl.Cache]] implementation and
   * keyer function.
   *
   * Use [[akka.japi.JavaPartialFunction]] to build the `keyer`.
   */
  def cache[K](cache: Cache[K, RouteResult], keyer: PartialFunction[RequestContext, K]) = RouteAdapter {
    D.cache(cache, keyer)
  }

  /**
   * A simple keyer function that will cache responses to *all* GET requests, with the URI as key.
   * WARNING - consider whether you need special handling for e.g. authorised requests.
   */
  val simpleKeyer: PartialFunction[RequestContext, Uri] = RouteAdapter {
    ???
  }

  /**
   * Passes only requests to the inner route that explicitly forbid caching with a `Cache-Control` header with either
   * a `no-cache` or `max-age=0` setting.
   */
  def cachingProhibited() = RouteAdapter {
    D.cachingProhibited
  }

  /**
   * Wraps its inner Route with caching support using the given [[Cache]] implementation and
   * keyer function. Note that routes producing streaming responses cannot be wrapped with this directive.
   */
  def alwaysCache[K](cache: Cache[K, RouteResult], keyer: PartialFunction[RequestContext, K]) = RouteAdapter {
    D.alwaysCache(cache, keyer)
  }

  def routeCache[K](): Cache[K, RouteResult] = RouteAdapter {
    D.routeCache(500, 16, Duration.Inf, Duration.Inf)
  }
  def routeCache[K](maxCapacity: Int): Cache[K, RouteResult] = RouteAdapter {
    D.routeCache(maxCapacity, 16, Duration.Inf, Duration.Inf)
  }
  def routeCache[K](maxCapacity: Int, initialCapacity: Int): Cache[K, RouteResult] = RouteAdapter {
    D.routeCache(maxCapacity, initialCapacity, Duration.Inf, Duration.Inf)
  }
  def routeCache[K](maxCapacity: Int, initialCapacity: Int, timeToLive: Duration): Cache[K, RouteResult] = RouteAdapter {
    D.routeCache(maxCapacity, initialCapacity, timeToLive, Duration.Inf)
  }
  def routeCache[K](maxCapacity: Int, initialCapacity: Int, timeToLive: Duration, timeToIdle: Duration): Cache[K, RouteResult] = RouteAdapter {
    D.routeCache(maxCapacity, initialCapacity, timeToLive, timeToIdle)
  }
}

object CachingDirectives extends CachingDirectives
