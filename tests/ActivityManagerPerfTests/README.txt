ActivityManagerPerfTests

Performance tests for various ActivityManager components, e.g. Services, Broadcasts
* These are only for tests that don't require a target package to test against
* Self-contained perf tests should go in frameworks/base/apct-tests/perftests

Command to run tests
* atest -v ActivityManagerPerfTests

Overview
* The numbers we are trying to measure are end-to-end numbers
  * For example, the time it takes from sending an Intent to start a Service
    to the time the Service runs its callbacks
* System.nanoTime() is monotonic and consistent between processes, so we use that for measuring time
* If the test app is involved, it will measure the time and send it back to the instrumentation test
  * The time is sent back through a Binder interface in the Intent with the help of Utils.sendTime()
  * Each sent time is tagged with an id since there can be multiple events that send back a time
* Each test will run multiple times to account for variation in test runs

Structure
* tests
  * Instrumentation test which runs the various performance tests and reports the results
* test-app
  * Target package which contains the Services, BroadcastReceivers, etc. to test against
  * Sends the time it measures back to the test package
* utils
  * Utilities that both the instrumentation test and test app can use

Adding tests
* Example
  * Look at tests/src/com/android/frameworks/perftests/am/BroadcastPerfTest and
    test-app/src/com/android/frameworks/perftests/amteststestapp/TestBroadcastReceiver
    for simple examples using this framework
* Steps
  * Add any components you will test against in the target package under
    test-app/src/com/android/frameworks/perftests/amteststestapp/
  * Add the test class under tests/src/com/android/frameworks/perftests/am/tests/
    * The class should extend BasePerfTest
    * Each test should call runPerfFunction() returning the elapsed time for a single iteration
    * The test has access to a Context through mContext
  * If you are measuring the time elapsed of something that either starts or ends in the target
    package
    * The target package can report the time it measures through an ITimeReceiverCallback passed
      through an Intent through Utils.sendTime(intent, "tag")
      (or however a Binder needs to be passed to the target package)
    * The instrumentation test can collect that time by calling getReceivedTimeNs("tag") and
      calculate the elapsed time
    * Each timestamp sent to the instrumentation test is tagged with a tag since multiple timestamps
      can be reported in an iteration
  * If the target package should be running before your test logic starts, add startTargetPackage();
    at the beginning of the iteration

* Reporting
  * Look at internal documentation for how to add new tests to dashboards and receive notification
    on regressions
