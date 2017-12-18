ActivityManagerPerfTests

Performance tests for various ActivityManager components, e.g. Services, Broadcasts

Command to run tests (not working yet, atest seems buggy)
* atest .../frameworks/base/tests/ActivityManagerPerfTests
* m ActivityManagerPerfTests ActivityManagerPerfTestsTestApp && \
  adb install $OUT/data/app/ActivityManagerPerfTests/ActivityManagerPerfTests.apk && \
  adb install $OUT/data/app/ActivityManagerPerfTestsTestApp/ActivityManagerPerfTestsTestApp.apk && \
  adb shell am instrument -w \
  com.android.frameworks.perftests.amtests/android.support.test.runner.AndroidJUnitRunner

Overview
* The numbers we are trying to measure are end-to-end numbers
  * For example, the time it takes from sending an Intent to start a Service
    to the time the Service runs its callbacks
* System.nanoTime() is monotonic and consistent between processes, so we use that for measuring time
* To make sure the test app is running, we start an Activity
* If the test app is involved, it will measure the time and send it back to the instrumentation test
  * The time is sent back through a Binder interface in the Intent
  * Each sent time is tagged with an id since there can be multiple events that send back a time
    * For example, one is sent when the Activity is started, and another could be sent when a
      Broadcast is received

Structure
* tests
  * Instrumentation test which runs the various performance tests and reports the results

* test-app
  * Target package which contains the Services, BroadcastReceivers, etc. to test against
  * Sends the time it measures back to the test package

* utils
  * Utilities that both the instrumentation test and test app can use
