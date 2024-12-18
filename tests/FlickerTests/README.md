# Flicker Test Library

## Motivation
This set of tests use the flickerlib from `platform_testing/libraries/flicker` to execute a set of common UI transitions to detect discontinuous or unpredictable behavior.

The tests are organized in packages according to the transitions they test (e.g., `rotation`, `splitscreen`).

## Adding a Test

By default, tests should inherit from `TestBase` and override the variable `transition` (Kotlin) or the function `getTransition()` (Java).

Inheriting from this class ensures the common assertions will be executed, namely:

* all regions on the screen are covered
* status bar is always visible
* status bar is at the correct position at the start and end of the transition
* nav bar is always visible
* nav bar is at the correct position at the start and end of the transition

The default tests can be disabled by overriding the respective methods and including an `@Ignore` annotation.

For more examples of how a test looks like check `ChangeAppRotationTest` within the `Rotation` subdirectory.

