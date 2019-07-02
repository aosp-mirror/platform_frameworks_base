# Flicker Test Library

## Motivation
Detect *flicker* &mdash; any discontinuous, or unpredictable behavior seen during UI transitions that is not due to performance. This is often the result of a logic error in the code and difficult to identify because the issue is transient and at times difficult to reproduce. This library helps create integration tests between `SurfaceFlinger`, `WindowManager` and `SystemUI` to identify flicker.

## Adding a Test
The library builds and runs UI transitions, captures Winscope traces and exposes common assertions that can be tested against each trace.

### Building Transitions
Start by defining common or error prone transitions using `TransitionRunner`.
```java
// Example: Build a transition that cold launches an app from launcher
TransitionRunner transition = TransitionRunner.newBuilder()
                // Specify a tag to identify the transition (optional)
                .withTag("OpenAppCold_" + testApp.getLauncherName())

                // Specify preconditions to setup the device
                // Wake up device and go to home screen
                .runBeforeAll(AutomationUtils::wakeUpAndGoToHomeScreen)

                // Setup transition under test
                // Press the home button and close the app to test a cold start
                .runBefore(device::pressHome)
                .runBefore(testApp::exit)

                // Run the transition under test
                // Open the app and wait for UI to be idle
                // This is the part of the transition that will be tested.
                .run(testApp::open)
                .run(device::waitForIdle)

                // Perform any tear downs
                // Close the app
                .runAfterAll(testApp::exit)

                // Number of times to repeat the transition to catch any flaky issues
                .repeat(5);
```


Run the transition to get a list of `TransitionResult` for each time the transition is repeated.
```java
    List<TransitionResult> results = transition.run();
```
`TransitionResult` contains paths to test artifacts such as Winscope traces and screen recordings.


### Checking Assertions
Each `TransitionResult` can be tested using an extension of the Google Truth library, `LayersTraceSubject` and `WmTraceSubject`. They try to balance test principles set out by Google Truth (not supporting nested assertions, keeping assertions simple) with providing support for common assertion use cases.

Each trace can be represented as a ordered collection of trace entries, with an associated timestamp. Each trace entry has common assertion checks. The trace subjects expose methods to filter the range of entries and test for changing assertions.

```java
    TransitionResult result = results.get(0);
    Rect displayBounds = getDisplayBounds();

    // check all trace entries
    assertThat(result).coversRegion(displayBounds).forAllEntries();

    // check a range of entries
    assertThat(result).coversRegion(displayBounds).forRange(startTime, endTime);

    // check first entry
    assertThat(result).coversRegion(displayBounds).inTheBeginning();

    // check last entry
    assertThat(result).coversRegion(displayBounds).atTheEnd();

    // check a change in assertions, e.g. wallpaper window is visible,
    // then wallpaper window becomes and stays invisible
    assertThat(result)
                .showsBelowAppWindow("wallpaper")
                .then()
                .hidesBelowAppWindow("wallpaper")
                .forAllEntries();
```

All assertions return `Result` which contains a `success` flag, `assertionName` string identifier, and `reason` string to provide actionable details to the user. The `reason` string is build along the way with all the details as to why the assertions failed and any hints which might help the user determine the root cause. Failed assertion message will also contain a path to the trace that was tested. Example of a failed test:

```
    java.lang.AssertionError: Not true that <com.android.server.wm.flicker.LayersTrace@65da4cc>
    Layers Trace can be found in: /layers_trace_emptyregion.pb
    Timestamp: 2308008331271
    Assertion: coversRegion
    Reason:   Region to test: Rect(0, 0 - 1440, 2880)
    first empty point: 0, 99
    visible regions:
    StatusBar#0Rect(0, 0 - 1440, 98)
    NavigationBar#0Rect(0, 2712 - 1440, 2880)
    ScreenDecorOverlay#0Rect(0, 0 - 1440, 91)
    ...
        at com.google.common.truth.FailureStrategy.fail(FailureStrategy.java:24)
        ...
```

---

## Running Tests

The tests can be run as any other Android JUnit tests. `platform_testing/tests/flicker` uses the library to test common UI transitions. Run `atest FlickerTest` to execute these tests.

---

## Other Topics
### Monitors
Monitors capture test artifacts for each transition run. They are started before each iteration of the test transition (after the `runBefore` calls) and stopped after the transition is completed. Each iteration will produce a new test artifact. The following monitors are available:

#### LayersTraceMonitor
Captures Layers trace. This monitor is started by default. Build a transition with `skipLayersTrace()` to disable this monitor.
#### WindowManagerTraceMonitor
Captures Window Manager trace. This monitor is started by default. Build a transition with `skipWindowManagerTrace()` to disable this monitor.
#### WindowAnimationFrameStatsMonitor
Captures WindowAnimationFrameStats for the transition. This monitor is started by default and is used to eliminate *janky* runs. If an iteration has skipped frames, as determined by WindowAnimationFrameStats, the results for the iteration is skipped. If the list of results is empty after all iterations are completed, then the test should fail. Build a transition with `includeJankyRuns()` to disable this monitor.
#### ScreenRecorder
Captures screen to a video file. This monitor is disabled by default. Build a transition with `recordEachRun()` to capture each transition or build with `recordAllRuns()` to capture every transition including setup and teardown.

---

### Extending Assertions

To add a new assertion, add a function to one of the trace entry classes, `LayersTrace.Entry` or `WindowManagerTrace.Entry`.

```java
    // Example adds an assertion to the check if layer is hidden by parent.
    Result isHiddenByParent(String layerName) {
        // Result should contain a details if assertion fails for any reason
        // such as if layer is not found or layer is not hidden by parent
        // or layer has no parent.
        // ...
    }
```
Then add a function to the trace subject `LayersTraceSubject` or `WmTraceSubject` which will add the assertion for testing. When the assertion is evaluated, the trace will first be filtered then the assertion will be applied to the remaining entries.

```java
    public LayersTraceSubject isHiddenByParent(String layerName) {
        mChecker.add(entry -> entry.isHiddenByParent(layerName),
                "isHiddenByParent(" + layerName + ")");
        return this;
    }
```

To use the new assertion:
```java
    // Check if "Chrome" layer is hidden by parent in the first trace entry.
    assertThat(result).isHiddenByParent("Chrome").inTheBeginning();
```