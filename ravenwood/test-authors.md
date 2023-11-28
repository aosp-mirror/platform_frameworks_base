# Ravenwood for Test Authors

The Ravenwood testing environment runs inside a single Java process on the host side, and provides a limited yet growing set of Android API functionality.

Ravenwood explicitly does not support “large” integration tests that expect a fully booted Android OS.  Instead, it’s more suited for “small” and “medium” tests where your code-under-test has been factored to remove dependencies on a fully booted device.

When writing tests under Ravenwood, all Android API symbols associated with your declared `sdk_version` are available to link against using, but unsupported APIs will throw an exception.  This design choice enables mocking of unsupported APIs, and supports sharing of test code to build “bivalent” test suites that run against either Ravenwood or a traditional device.

## Typical test structure

Below are the typical steps needed to add a straightforward “small” unit test:

* Define an `android_ravenwood_test` rule in your `Android.bp` file:

```
android_ravenwood_test {
    name: "MyTestsRavenwood",
    static_libs: [
        "androidx.annotation_annotation",
        "androidx.test.rules",
    ],
    srcs: [
        "src/com/example/MyCode.java",
        "tests/src/com/example/MyCodeTest.java",
    ],
    sdk_version: "test_current",
    auto_gen_config: true,
}
```

* Write your unit test just like you would for an Android device:

```
@RunWith(AndroidJUnit4.class)
public class MyCodeTest {
    @Test
    public void testSimple() {
        // ...
    }
}
```

* APIs available under Ravenwood are stateless by default.  If your test requires explicit states (such as defining the UID you’re running under, or requiring a main `Looper` thread), add a `RavenwoodRule` to declare that:

```
@RunWith(AndroidJUnit4.class)
public class MyCodeTest {
    @Rule
    public final RavenwoodRule mRavenwood = new RavenwoodRule.Builder()
            .setProcessApp()
            .setProvideMainThread(true)
            .build();
```

Once you’ve defined your test, you can use typical commands to execute it locally:

```
$ atest MyTestsRavenwood
```

> **Note:** There's a known bug where `atest` currently requires a connected device to run Ravenwood tests, but that device isn't used for testing.

You can also run your new tests automatically via `TEST_MAPPING` rules like this:

```
{
  "ravenwood-presubmit": [
    {
      "name": "MyTestsRavenwood",
      "host": true
    }
  ]
}
```

## Strategies for migration/bivalent tests

Ravenwood aims to support tests that are written in a “bivalent” way, where the same test code can run on both a real Android device and under a Ravenwood environment.

In situations where a test method depends on API functionality not yet available under Ravenwood, we provide an annotation to quietly “ignore” that test under Ravenwood, while continuing to validate that test on real devices.  Please note that your test must declare a `RavenwoodRule` for the annotation to take effect.

Test authors are encouraged to provide a `blockedBy` or `reason` argument to help future maintainers understand why a test is being ignored, and under what conditions it might be supported in the future.

```
@RunWith(AndroidJUnit4.class)
public class MyCodeTest {
    @Rule
    public final RavenwoodRule mRavenwood = new RavenwoodRule();

    @Test
    public void testSimple() {
        // Simple test that runs on both devices and Ravenwood
    }

    @Test
    @IgnoreUnderRavenwood(blockedBy = PackageManager.class)
    public void testComplex() {
        // Complex test that runs on devices, but is ignored under Ravenwood
    }
}
```

## Strategies for unsupported APIs

As you write tests against Ravenwood, you’ll likely discover API dependencies that aren’t supported yet.  Here’s a few strategies that can help you make progress:

* Your code-under-test may benefit from subtle dependency refactoring to reduce coupling.  (For example, providing a specific `File` argument instead of deriving it internally from a `Context`.)
* Although mocking code that your team doesn’t own is a generally discouraged testing practice, it can be a valuable pressure relief valve when a dependency isn’t yet supported.

## Strategies for debugging test development

When writing tests you may encounter odd or hard to debug behaviors.  One good place to start is at the beginning of the logs stored by atest:

```
$ atest MyTestsRavenwood
...
Test Logs have saved in /tmp/atest_result/20231128_094010_0e90t8v8/log
Run 'atest --history' to review test result history.
```

The most useful logs are in the `isolated-java-logs` text file, which can typically be tab-completed by copy-pasting the logs path mentioned in the atest output:

```
$ less /tmp/atest_result/20231128_133105_h9al__79/log/i*/i*/isolated-java-logs*
```

Here are some common known issues and recommended workarounds:

* Some code may unconditionally interact with unsupported APIs, such as via static initializers.  One strategy is to shift the logic into `@Before` methods and make it conditional by testing `RavenwoodRule.isUnderRavenwood()`.
* Some code may reference API symbols not yet present in the Ravenwood runtime, such as ART or ICU internals, or APIs from Mainline modules.  One strategy is to refactor to avoid these internal dependencies, but Ravenwood aims to better support them soon.
    * This may also manifest as very odd behavior, such as test not being executed at all, tracked by bug #312517322
    * This may also manifest as an obscure Mockito error claiming “Mockito can only mock non-private & non-final classes”
