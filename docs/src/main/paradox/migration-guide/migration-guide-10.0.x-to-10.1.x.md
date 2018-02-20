# Migration Guide from Akka HTTP 10.0 to 10.1.x

## General Notes
Akka HTTP is binary backwards compatible within for all version within the 10.0.x range. However, there are a set of APIs
that are marked with the special annotation `@ApiMayChange` which are exempt from this rule, in order to allow them to be
evolved more freely until stabilising them, by removing this annotation.
See @extref:[The @DoNotInherit and @ApiMayChange markers](akka-docs:common/binary-compatibility-rules.html#The_@DoNotInherit_and_@ApiMayChange_markers) for further information.

This migration guide aims to help developers who use these bleeding-edge APIs to migrate between their evolving versions
within patch releases.

## Akka HTTP 10.0.0 -> 10.1.0

### ServerBinding.unbind() return value changed to Future[Done]

The return value of `unbind()` is now @scala[`Future[Done]`]@java[`CompletionStage[Done]`] instead of the previous 
@scala[`Future[Unit]`]@java[`CompletionStage[Done]`] to better express the meaning of this value and be consistent with 
the rest of Akka for such return values. 

In case you were passing around this value to other methods or keeping in a field with explicit type, change the `Unit` 
to `Done`. The remaining semantics of this future remain the same. 
