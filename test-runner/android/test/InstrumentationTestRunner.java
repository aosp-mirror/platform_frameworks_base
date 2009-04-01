/*
 * Copyright (C) 2007 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.test;

import static android.test.suitebuilder.TestPredicates.REJECT_PERFORMANCE;
import android.app.Activity;
import android.app.Instrumentation;
import android.os.Bundle;
import android.os.Debug;
import android.os.Looper;
import android.test.suitebuilder.TestMethod;
import android.test.suitebuilder.TestPredicates;
import android.test.suitebuilder.TestSuiteBuilder;
import android.util.Log;

import com.android.internal.util.Predicate;

import junit.framework.AssertionFailedError;
import junit.framework.Test;
import junit.framework.TestCase;
import junit.framework.TestListener;
import junit.framework.TestResult;
import junit.framework.TestSuite;
import junit.runner.BaseTestRunner;
import junit.textui.ResultPrinter;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;


/**
 * An {@link Instrumentation} that runs various types of {@link junit.framework.TestCase}s against
 * an Android package (application). Typical usage:
 * <ol>
 * <li>Write {@link junit.framework.TestCase}s that perform unit, functional, or performance tests
 * against the classes in your package.  Typically these are subclassed from:
 *   <ul><li>{@link android.test.ActivityInstrumentationTestCase}</li>
 *   <li>{@link android.test.ActivityUnitTestCase}</li>
 *   <li>{@link android.test.AndroidTestCase}</li>
 *   <li>{@link android.test.ApplicationTestCase}</li>
 *   <li>{@link android.test.InstrumentationTestCase}</li>
 *   <li>{@link android.test.ProviderTestCase}</li>
 *   <li>{@link android.test.ServiceTestCase}</li>
 *   <li>{@link android.test.SingleLaunchActivityTestCase}</li></ul>
 *   <li>In an appropriate AndroidManifest.xml, define the this instrumentation with
 * the appropriate android:targetPackage set.
 * <li>Run the instrumentation using "adb shell am instrument -w",
 * with no optional arguments, to run all tests (except performance tests).
 * <li>Run the instrumentation using "adb shell am instrument -w",
 * with the argument '-e func true' to run all functional tests. These are tests that derive from
 * {@link android.test.InstrumentationTestCase}.
 * <li>Run the instrumentation using "adb shell am instrument -w",
 * with the argument '-e unit true' to run all unit tests. These are tests that <i>do not</i>derive
 * from {@link android.test.InstrumentationTestCase} (and are not performance tests).
 * <li>Run the instrumentation using "adb shell am instrument -w",
 * with the argument '-e class' set to run an individual {@link junit.framework.TestCase}.
 * </ol>
 * <p/>
 * <b>Running all tests:</b> adb shell am instrument -w
 * com.android.foo/android.test.InstrumentationTestRunner
 * <p/>
 * <b>Running all small tests:</b> adb shell am instrument -w
 * -e size small
 * com.android.foo/android.test.InstrumentationTestRunner
 * <p/>
 * <b>Running all medium tests:</b> adb shell am instrument -w
 * -e size medium
 * com.android.foo/android.test.InstrumentationTestRunner
 * <p/>
 * <b>Running all large tests:</b> adb shell am instrument -w
 * -e size large
 * com.android.foo/android.test.InstrumentationTestRunner
 * <p/>
 * <b>Running a single testcase:</b> adb shell am instrument -w
 * -e class com.android.foo.FooTest
 * com.android.foo/android.test.InstrumentationTestRunner
 * <p/>
 * <b>Running a single test:</b> adb shell am instrument -w
 * -e class com.android.foo.FooTest#testFoo
 * com.android.foo/android.test.InstrumentationTestRunner
 * <p/>
 * <b>Running multiple tests:</b> adb shell am instrument -w
 * -e class com.android.foo.FooTest,com.android.foo.TooTest
 * com.android.foo/android.test.InstrumentationTestRunner
 * <p/>
 * <b>Including performance tests:</b> adb shell am instrument -w
 * -e perf true
 * com.android.foo/android.test.InstrumentationTestRunner
 * <p/>
 * <b>To debug your tests, set a break point in your code and pass:</b>
 * -e debug true
 * <p/>
 * <b>To run in 'log only' mode</b>
 * -e log true
 * This option will load and iterate through all test classes and methods, but will bypass actual 
 * test execution. Useful for quickly obtaining info on the tests to be executed by an 
 * instrumentation command.
 * <p/>
 * <b>To generate EMMA code coverage:</b>
 * -e coverage true
 * Note: this requires an emma instrumented build. By default, the code coverage results file 
 * will be saved as /sdcard/coverage.ec, unless overridden by coverageFile flag (see below)
 * <p/>
 * <b> To specify EMMA code coverage results file path:</b>
 * -e coverageFile /sdcard/myFile.ec
 * <br/>
 * in addition to the other arguments.
 */

/* (not JavaDoc)
 * Although not necessary in most case, another way to use this class is to extend it and have the
 * derived class return
 * the desired test suite from the {@link #getTestSuite()} method. The test suite returned from this
 * method will be used if no target class is defined in the meta-data or command line argument
 * parameters. If a derived class is used it needs to be added as an instrumentation to the
 * AndroidManifest.xml and the command to run it would look like:
 * <p/>
 * adb shell am instrument -w com.android.foo/<i>com.android.FooInstrumentationTestRunner</i>
 * <p/>
 * Where <i>com.android.FooInstrumentationTestRunner</i> is the derived class.
 *
 * This model is used by many existing app tests, but can probably be deprecated.
 */
public class InstrumentationTestRunner extends Instrumentation implements TestSuiteProvider {

    /** @hide */
    public static final String ARGUMENT_TEST_CLASS = "class";
    /** @hide */
    public static final String ARGUMENT_TEST_PACKAGE = "package";
    /** @hide */
    public static final String ARGUMENT_TEST_SIZE_PREDICATE = "size";
    /** @hide */
    public static final String ARGUMENT_INCLUDE_PERF = "perf";
    /** @hide */
    public static final String ARGUMENT_DELAY_MSEC = "delay_msec";

    private static final String SMALL_SUITE = "small";
    private static final String MEDIUM_SUITE = "medium";  
    private static final String LARGE_SUITE = "large";
    
    private static final String ARGUMENT_LOG_ONLY = "log";

   
    /**
     * This constant defines the maximum allowed runtime (in ms) for a test included in the "small" suite. 
     * It is used to make an educated guess at what suite an unlabeled test belongs.
     */
    private static final float SMALL_SUITE_MAX_RUNTIME = 100;
    
    /**
     * This constant defines the maximum allowed runtime (in ms) for a test included in the "medium" suite. 
     * It is used to make an educated guess at what suite an unlabeled test belongs.
     */
    private static final float MEDIUM_SUITE_MAX_RUNTIME = 1000;
    
    /**
     * The following keys are used in the status bundle to provide structured reports to 
     * an IInstrumentationWatcher. 
     */

    /**
     * This value, if stored with key {@link android.app.Instrumentation#REPORT_KEY_IDENTIFIER}, 
     * identifies InstrumentationTestRunner as the source of the report.  This is sent with all
     * status messages.
     */
    public static final String REPORT_VALUE_ID = "InstrumentationTestRunner";
    /**
     * If included in the status or final bundle sent to an IInstrumentationWatcher, this key 
     * identifies the total number of tests that are being run.  This is sent with all status
     * messages.
     */
    public static final String REPORT_KEY_NUM_TOTAL = "numtests";
    /**
     * If included in the status or final bundle sent to an IInstrumentationWatcher, this key 
     * identifies the sequence number of the current test.  This is sent with any status message
     * describing a specific test being started or completed.
     */
    public static final String REPORT_KEY_NUM_CURRENT = "current";
    /**
     * If included in the status or final bundle sent to an IInstrumentationWatcher, this key 
     * identifies the name of the current test class.  This is sent with any status message
     * describing a specific test being started or completed.
     */
    public static final String REPORT_KEY_NAME_CLASS = "class";
    /**
     * If included in the status or final bundle sent to an IInstrumentationWatcher, this key 
     * identifies the name of the current test.  This is sent with any status message
     * describing a specific test being started or completed.
     */
    public static final String REPORT_KEY_NAME_TEST = "test";
    /**
     * If included in the status or final bundle sent to an IInstrumentationWatcher, this key 
     * reports the run time in seconds of the current test.
     */
    private static final String REPORT_KEY_RUN_TIME = "runtime";
    /**
     * If included in the status or final bundle sent to an IInstrumentationWatcher, this key 
     * reports the guessed suite assignment for the current test.
     */
    private static final String REPORT_KEY_SUITE_ASSIGNMENT = "suiteassignment";
    /**
     * The test is starting.
     */
    public static final int REPORT_VALUE_RESULT_START = 1;
    /**
     * The test completed successfully.
     */
    public static final int REPORT_VALUE_RESULT_OK = 0;
    /**
     * The test completed with an error.
     */
    public static final int REPORT_VALUE_RESULT_ERROR = -1;
    /**
     * The test completed with a failure.
     */
    public static final int REPORT_VALUE_RESULT_FAILURE = -2;
    /**
     * If included in the status bundle sent to an IInstrumentationWatcher, this key 
     * identifies a stack trace describing an error or failure.  This is sent with any status 
     * message describing a specific test being completed.
     */
    public static final String REPORT_KEY_STACK = "stack";

    private static final String DEFAULT_COVERAGE_FILE_PATH = "/sdcard/coverage.ec";
    
    private static final String LOG_TAG = "InstrumentationTestRunner";

    private final Bundle mResults = new Bundle();
    private AndroidTestRunner mTestRunner;
    private boolean mDebug;
    private boolean mJustCount;
    private boolean mSuiteAssignmentMode;
    private int mTestCount;
    private String mPackageOfTests;
    private boolean mCoverage;
    private String mCoverageFilePath;
    private int mDelayMsec;

    @Override
    public void onCreate(Bundle arguments) {
        super.onCreate(arguments);

        // Apk paths used to search for test classes when using TestSuiteBuilders.
        String[] apkPaths =
                {getTargetContext().getPackageCodePath(), getContext().getPackageCodePath()};
        ClassPathPackageInfoSource.setApkPaths(apkPaths);

        Predicate<TestMethod> testSizePredicate = null;
        boolean includePerformance = false;
        String testClassesArg = null;
        boolean logOnly = false;

        if (arguments != null) {
            // Test class name passed as an argument should override any meta-data declaration.
            testClassesArg = arguments.getString(ARGUMENT_TEST_CLASS);
            mDebug = getBooleanArgument(arguments, "debug");
            mJustCount = getBooleanArgument(arguments, "count");
            mSuiteAssignmentMode = getBooleanArgument(arguments, "suiteAssignment");
            mPackageOfTests = arguments.getString(ARGUMENT_TEST_PACKAGE);
            testSizePredicate = getSizePredicateFromArg(
                    arguments.getString(ARGUMENT_TEST_SIZE_PREDICATE));
            includePerformance = getBooleanArgument(arguments, ARGUMENT_INCLUDE_PERF);
            logOnly = getBooleanArgument(arguments, ARGUMENT_LOG_ONLY);
            mCoverage = getBooleanArgument(arguments, "coverage");
            mCoverageFilePath = arguments.getString("coverageFile");

            try {
                Object delay = arguments.get(ARGUMENT_DELAY_MSEC);  // Accept either string or int
                if (delay != null) mDelayMsec = Integer.parseInt(delay.toString());
            } catch (NumberFormatException e) {
                Log.e(LOG_TAG, "Invalid delay_msec parameter", e);
            }
        }

        TestSuiteBuilder testSuiteBuilder = new TestSuiteBuilder(getClass().getName(),
                getTargetContext().getClassLoader());

        if (testSizePredicate != null) {
            testSuiteBuilder.addRequirements(testSizePredicate);
        }
        if (!includePerformance) {
            testSuiteBuilder.addRequirements(REJECT_PERFORMANCE);
        }

        if (testClassesArg == null) {
            if (mPackageOfTests != null) {
                testSuiteBuilder.includePackages(mPackageOfTests);
            } else {
                TestSuite testSuite = getTestSuite();
                if (testSuite != null) {
                    testSuiteBuilder.addTestSuite(testSuite);
                } else {
                    // no package or class bundle arguments were supplied, and no test suite 
                    // provided so add all tests in application
                    testSuiteBuilder.includePackages("");
                }
            }
        } else {
            parseTestClasses(testClassesArg, testSuiteBuilder);
        }

        mTestRunner = getAndroidTestRunner();
        mTestRunner.setContext(getTargetContext());
        mTestRunner.setInstrumentaiton(this);
        mTestRunner.setSkipExecution(logOnly);
        mTestRunner.setTest(testSuiteBuilder.build());
        mTestCount = mTestRunner.getTestCases().size();
        if (mSuiteAssignmentMode) {
            mTestRunner.addTestListener(new SuiteAssignmentPrinter());
        } else {
            mTestRunner.addTestListener(new TestPrinter("TestRunner", false));
            mTestRunner.addTestListener(new WatcherResultPrinter(mTestCount));
        }
        start();
    }

    /**
     * Parses and loads the specified set of test classes 
     * @param testClassArg - comma-separated list of test classes and methods
     * @param testSuiteBuilder - builder to add tests to
     */
    private void parseTestClasses(String testClassArg, TestSuiteBuilder testSuiteBuilder) {
        String[] testClasses = testClassArg.split(",");
        for (String testClass : testClasses) {
            parseTestClass(testClass, testSuiteBuilder);
        }
    }

    /**
     * Parse and load the given test class and, optionally, method
     * @param testClassName - full package name of test class and optionally method to add. Expected
     *   format: com.android.TestClass#testMethod
     * @param testSuiteBuilder - builder to add tests to
     */
    private void parseTestClass(String testClassName, TestSuiteBuilder testSuiteBuilder) {
        int methodSeparatorIndex = testClassName.indexOf('#');
        String testMethodName = null;

        if (methodSeparatorIndex > 0) {
            testMethodName = testClassName.substring(methodSeparatorIndex + 1);
            testClassName = testClassName.substring(0, methodSeparatorIndex);
        }
        testSuiteBuilder.addTestClassByName(testClassName, testMethodName, 
                getTargetContext());
    }

    protected AndroidTestRunner getAndroidTestRunner() {
        return new AndroidTestRunner();
    }

    private boolean getBooleanArgument(Bundle arguments, String tag) {
        String tagString = arguments.getString(tag);
        return tagString != null && Boolean.parseBoolean(tagString);
    }
    
    /*
     * Returns the size predicate object, corresponding to the "size" argument value.
     */
    private Predicate<TestMethod> getSizePredicateFromArg(String sizeArg) {
     
        if (SMALL_SUITE.equals(sizeArg)) {
            return TestPredicates.SELECT_SMALL;
        } else if (MEDIUM_SUITE.equals(sizeArg)) {
            return TestPredicates.SELECT_MEDIUM;
        } else if (LARGE_SUITE.equals(sizeArg)) {
            return TestPredicates.SELECT_LARGE;
        } else {
            return null;
        }
    }
  
    @Override
    public void onStart() {
        Looper.prepare();
        
        if (mJustCount) {
            mResults.putString(Instrumentation.REPORT_KEY_IDENTIFIER, REPORT_VALUE_ID);
            mResults.putInt(REPORT_KEY_NUM_TOTAL, mTestCount);
            finish(Activity.RESULT_OK, mResults);
        } else {
            if (mDebug) {
                Debug.waitForDebugger();
            }
    
            ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
            PrintStream writer = new PrintStream(byteArrayOutputStream);
            try {
                StringResultPrinter resultPrinter = new StringResultPrinter(writer);
    
                mTestRunner.addTestListener(resultPrinter);
                
                long startTime = System.currentTimeMillis();
                mTestRunner.runTest();
                long runTime = System.currentTimeMillis() - startTime;
    
                resultPrinter.print(mTestRunner.getTestResult(), runTime);
            } finally {
                mResults.putString(Instrumentation.REPORT_KEY_STREAMRESULT, 
                        String.format("\nTest results for %s=%s", 
                        mTestRunner.getTestClassName(), 
                        byteArrayOutputStream.toString()));

                if (mCoverage) {
                    generateCoverageReport();
                }
                writer.close();
                
                finish(Activity.RESULT_OK, mResults);
            }
        }
    }

    public TestSuite getTestSuite() {
        return getAllTests();
    }

    /**
     * Override this to define all of the tests to run in your package.
     */
    public TestSuite getAllTests() {
        return null;
    }

    /**
     * Override this to provide access to the class loader of your package.
     */
    public ClassLoader getLoader() {
        return null;
    }
    
    private void generateCoverageReport() {
        // use reflection to call emma dump coverage method, to avoid
        // always statically compiling against emma jar
        java.io.File coverageFile = new java.io.File(getCoverageFilePath());
        try {
            Class emmaRTClass = Class.forName("com.vladium.emma.rt.RT");
            Method dumpCoverageMethod = emmaRTClass.getMethod("dumpCoverageData", 
                    coverageFile.getClass(), boolean.class, boolean.class);
            
            dumpCoverageMethod.invoke(null, coverageFile, false, false);

        } catch (ClassNotFoundException e) {
            reportEmmaError("Is emma jar on classpath?", e);
        } catch (SecurityException e) {
            reportEmmaError(e);
        } catch (NoSuchMethodException e) {
            reportEmmaError(e);
        } catch (IllegalArgumentException e) {
            reportEmmaError(e);
        } catch (IllegalAccessException e) {
            reportEmmaError(e);
        } catch (InvocationTargetException e) {
            reportEmmaError(e);
        }
    }

    private String getCoverageFilePath() {
        if (mCoverageFilePath == null) {
            return DEFAULT_COVERAGE_FILE_PATH;
        }
        else {
            return mCoverageFilePath;
        }
    }

    private void reportEmmaError(Exception e) {
        reportEmmaError("", e); 
    }

    private void reportEmmaError(String hint, Exception e) {
        String msg = "Failed to generate emma coverage. " + hint;
        Log.e(LOG_TAG, msg, e);
        mResults.putString(Instrumentation.REPORT_KEY_STREAMRESULT, "\nError: " + msg);
    }

    // TODO kill this, use status() and prettyprint model for better output
    private class StringResultPrinter extends ResultPrinter {

        public StringResultPrinter(PrintStream writer) {
            super(writer);
        }

        synchronized void print(TestResult result, long runTime) {
            printHeader(runTime);
            printFooter(result);
        }
    }
    
    /**
     * This class sends status reports back to the IInstrumentationWatcher about 
     * which suite each test belongs.
     */
    private class SuiteAssignmentPrinter implements TestListener
    {
        
        private Bundle mTestResult;
        private long mStartTime;
        private long mEndTime;
        private boolean mTimingValid;
        
        public SuiteAssignmentPrinter() {
        }
        
        /**
         * send a status for the start of a each test, so long tests can be seen as "running"
         */
        public void startTest(Test test) {
            mTimingValid = true;
            mStartTime = System.currentTimeMillis(); 
        }
        
        /**
         * @see junit.framework.TestListener#addError(Test, Throwable)
         */
        public void addError(Test test, Throwable t) {
            mTimingValid = false;
        }

        /**
         * @see junit.framework.TestListener#addFailure(Test, AssertionFailedError)
         */
        public void addFailure(Test test, AssertionFailedError t) {
            mTimingValid = false;
        }

        /**
         * @see junit.framework.TestListener#endTest(Test)
         */
        public void endTest(Test test) {
            float runTime;
            String assignmentSuite;
            mEndTime = System.currentTimeMillis();
            mTestResult = new Bundle();

            if (!mTimingValid || mStartTime < 0) {
                assignmentSuite = "NA";
                runTime = -1;
            } else {
                runTime = mEndTime - mStartTime;
                if (runTime < SMALL_SUITE_MAX_RUNTIME 
                        && !InstrumentationTestCase.class.isAssignableFrom(test.getClass())) {
                    assignmentSuite = SMALL_SUITE;
                } else if (runTime < MEDIUM_SUITE_MAX_RUNTIME) {
                    assignmentSuite = MEDIUM_SUITE;
                } else {
                    assignmentSuite = LARGE_SUITE;
                }
            }
            // Clear mStartTime so that we can verify that it gets set next time.
            mStartTime = -1;

            mTestResult.putString(Instrumentation.REPORT_KEY_STREAMRESULT, 
                    test.getClass().getName() + "#" + ((TestCase) test).getName() 
                    + "\nin " + assignmentSuite + " suite\nrunTime: "
                    + String.valueOf(runTime) + "\n");
            mTestResult.putFloat(REPORT_KEY_RUN_TIME, runTime);
            mTestResult.putString(REPORT_KEY_SUITE_ASSIGNMENT, assignmentSuite);

            sendStatus(0, mTestResult);
        }
    }
    
    /**
     * This class sends status reports back to the IInstrumentationWatcher
     */
    private class WatcherResultPrinter implements TestListener
    {
        private final Bundle mResultTemplate;
        Bundle mTestResult;
        int mTestNum = 0;
        int mTestResultCode = 0;
        String mTestClass = null;
        
        public WatcherResultPrinter(int numTests) {
            mResultTemplate = new Bundle();
            mResultTemplate.putString(Instrumentation.REPORT_KEY_IDENTIFIER, REPORT_VALUE_ID);
            mResultTemplate.putInt(REPORT_KEY_NUM_TOTAL, numTests);
        }
        
        /**
         * send a status for the start of a each test, so long tests can be seen as "running"
         */
        public void startTest(Test test) {
            String testClass = test.getClass().getName();
            mTestResult = new Bundle(mResultTemplate);
            mTestResult.putString(REPORT_KEY_NAME_CLASS, testClass);
            mTestResult.putString(REPORT_KEY_NAME_TEST, ((TestCase) test).getName());
            mTestResult.putInt(REPORT_KEY_NUM_CURRENT, ++mTestNum);
            // pretty printing
            if (testClass != null && !testClass.equals(mTestClass)) {
                mTestResult.putString(Instrumentation.REPORT_KEY_STREAMRESULT, 
                        String.format("\n%s:", testClass));
                mTestClass = testClass;
            } else {
                mTestResult.putString(Instrumentation.REPORT_KEY_STREAMRESULT, "");
            }

            // The delay_msec parameter is normally used to provide buffers of idle time
            // for power measurement purposes.  To make sure there is a delay before and after
            // every test in a suite, we delay *after* every test (see endTest below) and also
            // delay *before* the first test.  So, delay test1 delay test2 delay.

            try {
                if (mTestNum == 1) Thread.sleep(mDelayMsec);
            } catch (InterruptedException e) {
                throw new IllegalStateException(e);
            }

            sendStatus(REPORT_VALUE_RESULT_START, mTestResult);
            mTestResultCode = 0;
        }
        
        /**
         * @see junit.framework.TestListener#addError(Test, Throwable)
         */
        public void addError(Test test, Throwable t) {
            mTestResult.putString(REPORT_KEY_STACK, BaseTestRunner.getFilteredTrace(t));
            mTestResultCode = REPORT_VALUE_RESULT_ERROR;
            // pretty printing
            mTestResult.putString(Instrumentation.REPORT_KEY_STREAMRESULT, 
                    String.format("\nError in %s:\n%s", 
                            ((TestCase) test).getName(), BaseTestRunner.getFilteredTrace(t)));
        }

        /**
         * @see junit.framework.TestListener#addFailure(Test, AssertionFailedError)
         */
        public void addFailure(Test test, AssertionFailedError t) {
            mTestResult.putString(REPORT_KEY_STACK, BaseTestRunner.getFilteredTrace(t));
            mTestResultCode = REPORT_VALUE_RESULT_FAILURE;
            // pretty printing
            mTestResult.putString(Instrumentation.REPORT_KEY_STREAMRESULT, 
                    String.format("\nFailure in %s:\n%s", 
                            ((TestCase) test).getName(), BaseTestRunner.getFilteredTrace(t)));
        }

        /**
         * @see junit.framework.TestListener#endTest(Test)
         */
        public void endTest(Test test) {
            if (mTestResultCode == 0) {
                mTestResult.putString(Instrumentation.REPORT_KEY_STREAMRESULT, ".");
            }
            sendStatus(mTestResultCode, mTestResult);

            try {  // Sleep after every test, if specified
                Thread.sleep(mDelayMsec);
            } catch (InterruptedException e) {
                throw new IllegalStateException(e);
            }
        }

        // TODO report the end of the cycle
        // TODO report runtime for each test
    }
}
