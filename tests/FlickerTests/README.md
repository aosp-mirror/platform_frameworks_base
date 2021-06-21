# Flicker Test Library

## Motivation
This set of tests use the flickerlib from `platform_testing/libraries/flicker` to execute a set of common UI transitions to detect discontinuous or unpredictable behavior.

The tests are organized in packages according to the transitions they test (e.g., `rotation`, `splitscreen`).

## Adding a Test

By default tests should inherit from `RotationTestBase` or `NonRotationTestBase` and must override the variable `transitionToRun` (Kotlin) or the function `getTransitionToRun()` (Java).
Only tests that are not supported by these classes should inherit directly from the `FlickerTestBase` class.

### Rotation animations and transitions

Tests that rotate the device should inherit from `RotationTestBase`.
Tests that inherit from the class automatically receive start and end rotation values.  
Moreover, these tests inherit the following checks:
* all regions on the screen are covered
* status bar is always visible
* status bar rotates
* nav bar is always visible
* nav bar is rotates

The default tests can be disabled by overriding the respective methods and including an `@Ignore` annotation.

### Non-Rotation animations and transitions

`NonRotationTestBase` was created to make it easier to write tests that do not involve rotation (e.g., `Pip`, `split screen` or `IME`).
Tests that inherit from the class are automatically executed twice: once in portrait and once in landscape mode and the assertions are checked independently.
Moreover, these tests inherit the following checks:
* all regions on the screen are covered
* status bar is always visible
* nav bar is always visible

The default tests can be disabled by overriding the respective methods and including an `@Ignore` annotation.

### Exceptional cases

Tests that rotate the device should inherit from `RotationTestBase`.
This class allows the test to be freely configured and does not provide any assertions.  


### Example

Start by defining common or error prone transitions using `TransitionRunner`.
```kotlin
@LargeTest
@RunWith(Parameterized::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class MyTest(
    beginRotationName: String,
    beginRotation: Int
) : NonRotationTestBase(beginRotationName, beginRotation) {
    init {
        mTestApp = MyAppHelper(InstrumentationRegistry.getInstrumentation())
    }

    override val transitionToRun: TransitionRunner
        get() = TransitionRunner.newBuilder()
            .withTag("myTest")
            .recordAllRuns()
            .runBefore { device.pressHome() }
            .runBefore { device.waitForIdle() }
            .run { testApp.open() }
            .runAfter{ testApp.exit() }
            .repeat(2)
            .includeJankyRuns()
            .build()

    @Test
    fun myWMTest() {
        checkResults {
            WmTraceSubject.assertThat(it)
                    .showsAppWindow(MyTestApp)
                    .forAllEntries()
        }
    }

    @Test
    fun mySFTest() {
        checkResults {
            LayersTraceSubject.assertThat(it)
                    .showsLayer(MyTestApp)
                    .forAllEntries()
        }
    }
}
```
