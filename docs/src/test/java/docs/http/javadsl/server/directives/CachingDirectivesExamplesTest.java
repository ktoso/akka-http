/*
 * Copyright (C) 2016-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package docs.http.javadsl.server.directives;

import java.util.concurrent.atomic.AtomicInteger;
import akka.japi.JavaPartialFunction;
import akka.http.javadsl.model.Uri;
import akka.http.javadsl.model.HttpRequest;
import akka.http.javadsl.model.HttpMethods;
import akka.http.javadsl.model.StatusCodes;
import akka.http.javadsl.model.headers.*;
import akka.http.javadsl.model.headers.CacheDirectives.*;
import akka.http.javadsl.server.Route;
import akka.http.javadsl.server.RequestContext;
import static akka.http.javadsl.server.directives.AllCachingDirectives.*;
import akka.http.javadsl.testkit.JUnitRouteTest;
import org.junit.Test;
import static org.junit.Assert.assertEquals;

import akka.http.caching.javadsl.Cache;
import akka.http.caching.javadsl.CachingSettings;
import akka.http.javadsl.server.RouteResult;

public class CachingDirectivesExamplesTest extends JUnitRouteTest {

  @Test
  public void testCache() {
    //#cache
    final CachingSettings cachingSettings = CachingSettings.create(system());
    final JavaPartialFunction<RequestContext, Uri> simpleKeyer = new JavaPartialFunction<RequestContext, Uri>() {
      public Uri apply(RequestContext in, boolean isCheck) {
        final HttpRequest request = in.getRequest();
        final boolean isGet = request.method() == HttpMethods.GET;
        final boolean isAuthorized = request.getHeader(Authorization.class).isPresent();

        if (isGet && !isAuthorized)
          return request.getUri();
        else
          throw noMatch();
      }
    };

    final AtomicInteger count = new AtomicInteger(0);
    final Route route = cache(routeCache(cachingSettings), simpleKeyer, () ->
      extractUri(uri ->
        complete(String.format("Request for %s @ count %d", uri, count.incrementAndGet()))
      )
    );

    // tests:
    testRoute(route)
      .run(HttpRequest.GET("/"))
      .assertEntity("Request for http://example.com/ @ count 1");

    // now cached
    testRoute(route)
      .run(HttpRequest.GET("/"))
      .assertEntity("Request for http://example.com/ @ count 1");

    // caching prevented
    final CacheControl noCache = CacheControl.create(CacheDirectives.NO_CACHE);
    testRoute(route).run(HttpRequest.GET("/").addHeader(noCache))
      .assertEntity("Request for http://example.com/ @ count 2");
    //#cache
  }

  @Test
  public void testAlwaysCache() {
    //#always-cache
    final CachingSettings cachingSettings = CachingSettings.create(system());
    // Example keyer for non-authenticated GET requests
    final JavaPartialFunction<RequestContext, Uri> simpleKeyer = new JavaPartialFunction<RequestContext, Uri>() {
      public Uri apply(RequestContext in, boolean isCheck) {
        final HttpRequest request = in.getRequest();
        final boolean isGet = request.method() == HttpMethods.GET;
        final boolean isAuthorized = request.getHeader(Authorization.class).isPresent();

        if (isGet && !isAuthorized)
          return request.getUri();
        else
          throw noMatch();
      }
    };

    final AtomicInteger count = new AtomicInteger(0);
    final Route route = alwaysCache(routeCache(cachingSettings), simpleKeyer, () ->
      extractUri(uri ->
        complete(String.format("Request for %s @ count %d", uri, count.incrementAndGet()))
      )
    );

    // tests:
    testRoute(route)
      .run(HttpRequest.GET("/"))
      .assertEntity("Request for http://example.com/ @ count 1");

    // now cached
    testRoute(route)
      .run(HttpRequest.GET("/"))
      .assertEntity("Request for http://example.com/ @ count 1");

    final CacheControl noCache = CacheControl.create(CacheDirectives.NO_CACHE);
    testRoute(route)
      .run(HttpRequest.GET("/").addHeader(noCache))
      .assertEntity("Request for http://example.com/ @ count 1");
    //#always-cache
  }

  @Test
  public void testCachingProhibited() {
    //#caching-prohibited
    final Route route = cachingProhibited(() ->
      complete("abc")
    );

    // tests:
    testRoute(route)
      .run(HttpRequest.GET("/"))
      .assertStatusCode(StatusCodes.NOT_FOUND);

    final CacheControl noCache = CacheControl.create(CacheDirectives.NO_CACHE);
    testRoute(route)
      .run(HttpRequest.GET("/").addHeader(noCache))
      .assertEntity("abc");
    //#caching-prohibited
  }
}
