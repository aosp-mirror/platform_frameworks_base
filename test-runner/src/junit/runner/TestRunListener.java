package junit.runner;
/**
 * A listener interface for observing the
 * execution of a test run. Unlike TestListener,
 * this interface using only primitive objects,
 * making it suitable for remote test execution.
 * {@hide} - Not needed for 1.0 SDK
 */
 public interface TestRunListener {
     /* test status constants*/
     public static final int STATUS_ERROR= 1;
     public static final int STATUS_FAILURE= 2;

     public void testRunStarted(String testSuiteName, int testCount);
     public void testRunEnded(long elapsedTime);
     public void testRunStopped(long elapsedTime);
     public void testStarted(String testName);
     public void testEnded(String testName);
     public void testFailed(int status, String testName, String trace);
}
