# CachingDirectives

To enable caching support add a library dependency onto:

@@dependency [sbt,Gradle,Maven] {
  group="com.typesafe.akka"
  artifact="akka-http-caching_$scala.binary.version$"
  version="$project.version$"
}

Directives are available by importing:

@@snip [CachingDirectivesExamplesTest.java](../../../../../../../test/java/docs/http/javadsl/server/directives/CachingDirectivesExamplesTest.java) { #caching-directives-import }

@@toc { depth=1 }

@@@ index

* [cache](cache.md)
* [alwaysCache](alwaysCache.md)
* [cachingProhibited](cachingProhibited.md)

@@@
