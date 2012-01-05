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

import com.android.internal.util.Predicate;
import com.android.internal.util.Predicates;

import android.app.Activity;
import android.app.Instrumentation;
import android.os.Bundle;
import android.os.Debug;
import android.os.Looper;
import android.os.Parcelable;
import android.os.PerformanceCollector;
import android.os.PerformanceCollector.PerformanceResultsWriter;
import android.test.suitebuilder.TestMethod;
import android.test.suitebuilder.TestPredicates;
import android.test.suitebuilder.TestSuiteBuilder;
import android.test.suitebuilder.annotation.HasAnnotation;
import android.test.suitebuilder.annotation.LargeTest;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import junit.framework.AssertionFailedError;
import junit.framework.Test;
import junit.framework.TestCase;
import junit.framework.TestListener;
import junit.framework.TestResult;
import junit.framework.TestSuite;
import junit.runner.BaseTestRunner;
import junit.textui.ResultPrinter;

/**
 * An {@link Instrumentation} that runs various types of {@link junit.framework.TestCase}s against
 * an Android package (application).
 *
 * <div class="special reference">
 * <h3>Developer Guides</h3>
 * <p>For more information about application testing, read the
 * <a href="{@docRoot}guide/topics/testing/index.html">Testing</a> developer guide.</p>
 * </div>
 *
 * <h3>Typical Usage</h3>
 * <ol>
 * <li>Write {@link junit.framework.TestCase}s that perform unit, functional, or performance tests
 * against the classes in your package.  Typically these are subclassed from:
 *   <ul><li>{@link android.test.ActivityInstrumentationTestCase2}</li>
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
 * <b>Filter test run to tests with given annotation:</b> adb shell am instrument -w
 * -e annotation com.android.foo.MyAnnotation
 * com.android.foo/android.test.InstrumentationTestRunner
 * <p/>
 * If used with other options, the resulting test run will contain the union of the two options.
 * e.g. "-e size large -e annotation com.android.foo.MyAnnotation" will run only tests with both
 * the {@link LargeTest} and "com.android.foo.MyAnnotation" annotations.
 * <p/>
 * <b>Filter test run to tests <i>without</i> given annotation:</b> adb shell am instrument -w
 * -e notAnnotation com.android.foo.MyAnnotation
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
 * <b>Running all tests in a java package:</b> adb shell am instrument -w
 * -e package com.android.foo.subpkg
 *  com.android.foo/android.test.InstrumentationTestRunner
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
 * will be saved in a /data/<app>/coverage.ec file, unless overridden by coverageFile flag (see
 * below)
 * <p/>
 * <b> To specify EMMA code coverage results file path:</b>
 * -e coverageFile /sdcard/myFile.ec
 * <br/>
 * in addition to the other arguments.
 */

/* (not JavaDoc)
 * Although not necessary in most case, another way to use this class is to extend it and have the
 * derived class return the desired test suite from the {@link #getTestSuite()} method. The test
 * suite returned from this method will be used if no target class is defined in the meta-data or
 * command line argument parameters. If a derived class is used it needs to be added as an
 * instrumentation to the AndroidManifest.xml and the command to run it would look like:
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
    public static final String ARGUMENT_DELAY_MSEC = "delay_msec";

    private static final String SMALL_SUITE = "small";
    private static final String MEDIUM_SUITE = "medium";
    private static final String LARGE_SUITE = "large";

    private static final String ARGUMENT_LOG_ONLY = "log";
    /** @hide */
    static final String ARGUMENT_ANNOTATION = "annotation";
    /** @hide */
    static final String ARGUMENT_NOT_ANNOTATION = "notAnnotation";

    /**
     * This constant defines the maximum allowed runtime (in ms) for a test included in the "small"
     * suite. It is used to make an educated guess at what suite an unlabeled test belongs.
     */
    private static final float SMALL_SUITE_MAX_RUNTIME = 100;

    /**
     * This constant defines the maximum allowed runtime (in ms) for a test included in the
     * "medium" suite. It is used to make an educated guess at what suite an unlabeled test belongs.
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
     * reports the number of total iterations of the current test.
     */
    private static final String REPORT_KEY_NUM_ITERATIONS = "numiterations";
    /**
     * If included in the status or final bundle sent to an IInstrumentationWatcher, this key
     * reports the guessed suite assignment for the current test.
     */
    private static final String REPORT_KEY_SUITE_ASSIGNMENT = "suiteassignment";
    /**
     * If included in the status or final bundle sent to an IInstrumentationWatcher, this key
     * identifies the path to the generated code coverage file.
     */
    private static final String REPORT_KEY_COVERAGE_PATH = "coverageFilePath";

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

    // Default file name for code coverage
    private static final String DEFAULT_COVERAGE_FILE_NAME = "coverage.ec";

    private static final String LOG_TAG = "InstrumentationTestRunner";

    private final Bundle mResults = new Bundle();
    private Bundle mArguments;
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
        mArguments = arguments;

        // Apk paths used to search for test classes when using TestSuiteBuilders.
        String[] apkPaths =
                {getTargetContext().getPackageCodePath(), getContext().getPackageCodePath()};
        ClassPathPackageInfoSource.setApkPaths(apkPaths);

        Predicate<TestMethod> testSizePredicate = null;
        Predicate<TestMethod> testAnnotationPredicate = null;
        Predicate<TestMethod> testNotAnnotationPredicate = null;
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
            testAnnotationPredicate = getAnnotationPredicate(
                    arguments.getString(ARGUMENT_ANNOTATION));
            testNotAnnotationPredicate = getNotAnnotationPredicate(
                    arguments.getString(ARGUMENT_NOT_ANNOTATION));

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
        if (testAnnotationPredicate != null) {
            testSuiteBuilder.addRequirements(testAnnotationPredicate);
        }
        if (testNotAnnotationPredicate != null) {
            testSuiteBuilder.addRequirements(testNotAnnotationPredicate);
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

        testSuiteBuilder.addRequirements(getBuilderRequirements());

        mTestRunner = getAndroidTestRunner();
        mTestRunner.setContext(getTargetContext());
        mTestRunner.setInstrumentation(this);
        mTestRunner.setSkipExecution(logOnly);
        mTestRunner.setTest(testSuiteBuilder.build());
        mTestCount = mTestRunner.getTestCases().size();
        if (mSuiteAssignmentMode) {
            mTestRunner.addTestListener(new SuiteAssignmentPrinter());
        } else {
            WatcherResultPrinter resultPrinter = new WatcherResultPrinter(mTestCount);
            mTestRunner.addTestListener(new TestPrinter("TestRunner", false));
            mTestRunner.addTestListener(resultPrinter);
            mTestRunner.setPerformanceResultsWriter(resultPrinter);
        }
        start();
    }

    /**
     * Get the Bundle object that contains the arguments
     *
     * @return the Bundle object
     * @hide
     */
    public Bundle getBundle(){
        return mArguments;
    }

    /**
     * Add a {@link TestListener}
     * @hide
     */
    protected void addTestListener(TestListener listener){
        if(mTestRunner!=null && listener!=null){
            mTestRunner.addTestListener(listener);
        }
    }

    List<Predicate<TestMethod>> getBuilderRequirements() {
        return new ArrayList<Predicate<TestMethod>>();
    }

    /**
     * Parses and loads the specified set of test classes
     *
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
     *
     * @param testClassName - full package name of test class and optionally method to add.
     *        Expected format: com.android.TestClass#testMethod
     * @param testSuiteBuilder - builder to add tests to
     */
    private void parseTestClass(String testClassName, TestSuiteBuilder testSuiteBuilder) {
        int methodSeparatorIndex = testClassName.indexOf('#');
        String testMethodName = null;

        if (methodSeparatorIndex > 0) {
            testMethodName = testClassName.substring(methodSeparatorIndex + 1);
            testClassName = testClassName.substring(0, methodSeparatorIndex);
        }
        testSuiteBuilder.addTestClassByName(testClassName, testMethodName, getTargetContext());
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

   /**
    * Returns the test predicate object, corresponding to the annotation class value provided via
    * the {@link ARGUMENT_ANNOTATION} argument.
    *
    * @return the predicate or <code>null</code>
    */
    private Predicate<TestMethod> getAnnotationPredicate(String annotationClassName) {
        Class<? extends Annotation> annotationClass = getAnnotationClass(annotationClassName);
        if (annotationClass != null) {
            return new HasAnnotation(annotationClass);
        }
        return null;
    }

    /**
     * Returns the negative test predicate object, corresponding to the annotation class value
     * provided via the {@link ARGUMENT_NOT_ANNOTATION} argument.
     *
     * @return the predicate or <code>null</code>
     */
     private Predicate<TestMethod> getNotAnnotationPredicate(String annotationClassName) {
         Class<? extends Annotation> annotationClass = getAnnotationClass(annotationClassName);
         if (annotationClass != null) {
             return Predicates.not(new HasAnnotation(annotationClass));
         }
         return null;
     }

    /**
     * Helper method to return the annotation class with specified name
     *
     * @param annotationClassName the fully qualified name of the class
     * @return the annotation class or <code>null</code>
     */
    private Class<? extends Annotation> getAnnotationClass(String annotationClassName) {
        if (annotationClassName == null) {
            return null;
        }
        try {
           Class<?> annotationClass = Class.forName(annotationClassName);
           if (annotationClass.isAnnotation()) {
               return (Class<? extends Annotation>)annotationClass;
           } else {
               Log.e(LOG_TAG, String.format("Provided annotation value %s is not an Annotation",
                       annotationClassName));
           }
        } catch (ClassNotFoundException e) {
            Log.e(LOG_TAG, String.format("Could not find class for specified annotation %s",
                    annotationClassName));
        }
        return null;
    }

    /**
     * Initialize the current thread as a looper.
     * <p/>
     * Exposed for unit testing.
     */
    void prepareLooper() {
        Looper.prepare();
    }

    @Override
    public void onStart() {
        prepareLooper();

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
            } catch (Throwable t) {
                // catch all exceptions so a more verbose error message can be outputted
                writer.println(String.format("Test run aborted due to unexpected exception: %s",
                                t.getMessage()));
                t.printStackTrace(writer);
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
        String coverageFilePath = getCoverageFilePath();
        java.io.File coverageFile = new java.io.File(coverageFilePath);
        try {
            Class<?> emmaRTClass = Class.forName("com.vladium.emma.rt.RT");
            Method dumpCoverageMethod = emmaRTClass.getMethod("dumpCoverageData",
                    coverageFile.getClass(), boolean.class, boolean.class);

            dumpCoverageMethod.invoke(null, coverageFile, false, false);
            // output path to generated coverage file so it can be parsed by a test harness if
            // needed
            mResults.putString(REPORT_KEY_COVERAGE_PATH, coverageFilePath);
            // also output a more user friendly msg
            final String currentStream = mResults.getString(
                    Instrumentation.REPORT_KEY_STREAMRESULT);
            mResults.putString(Instrumentation.REPORT_KEY_STREAMRESULT,
                String.format("%s\nGenerated code coverage data to %s", currentStream,
                coverageFilePath));
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
            return getTargetContext().getFilesDir().getAbsolutePath() + File.separator +
                   DEFAULT_COVERAGE_FILE_NAME;
        } else {
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
    private class SuiteAssignmentPrinter implements TestListener {

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
    private class WatcherResultPrinter implements TestListener, PerformanceResultsWriter {
        private final Bundle mResultTemplate;
        Bundle mTestResult;
        int mTestNum = 0;
        int mTestResultCode = 0;
        String mTestClass = null;
        PerformanceCollector mPerfCollector = new PerformanceCollector();
        boolean mIsTimedTest = false;
        boolean mIncludeDetailedStats = false;

        public WatcherResultPrinter(int numTests) {
            mResultTemplate = new Bundle();
            mResultTemplate.putString(Instrumentation.REPORT_KEY_IDENTIFIER, REPORT_VALUE_ID);
            mResultTemplate.putInt(REPORT_KEY_NUM_TOTAL, numTests);
        }

        /**
         * send a status for the start of a each test, so long tests can be seen
         * as "running"
         */
        public void startTest(Test test) {
            String testClass = test.getClass().getName();
            String testName = ((TestCase)test).getName();
            mTestResult = new Bundle(mResultTemplate);
            mTestResult.putString(REPORT_KEY_NAME_CLASS, testClass);
            mTestResult.putString(REPORT_KEY_NAME_TEST, testName);
            mTestResult.putInt(REPORT_KEY_NUM_CURRENT, ++mTestNum);
            // pretty printing
            if (testClass != null && !testClass.equals(mTestClass)) {
                mTestResult.putString(Instrumentation.REPORT_KEY_STREAMRESULT,
                        String.format("\n%s:", testClass));
                mTestClass = testClass;
            } else {
                mTestResult.putString(Instrumentation.REPORT_KEY_STREAMRESULT, "");
            }

            Method testMethod = null;
            try {
                testMethod = test.getClass().getMethod(testName);
                // Report total number of iterations, if test is repetitive
                if (testMethod.isAnnotationPresent(RepetitiveTest.class)) {
                    int numIterations = testMethod.getAnnotation(
                        RepetitiveTest.class).numIterations();
                    mTestResult.putInt(REPORT_KEY_NUM_ITERATIONS, numIterations);
                }
            } catch (NoSuchMethodException e) {
                // ignore- the test with given name does not exist. Will be handled during test
                // execution
            }

            // The delay_msec parameter is normally used to provide buffers of idle time
            // for power measurement purposes. To make sure there is a delay before and after
            // every test in a suite, we delay *after* every test (see endTest below) and also
            // delay *before* the first test. So, delay test1 delay test2 delay.

            try {
                if (mTestNum == 1) Thread.sleep(mDelayMsec);
            } catch (InterruptedException e) {
                throw new IllegalStateException(e);
            }

            sendStatus(REPORT_VALUE_RESULT_START, mTestResult);
            mTestResultCode = 0;

            mIsTimedTest = false;
            mIncludeDetailedStats = false;
            try {
                // Look for TimedTest annotation on both test class and test method
                if (testMethod != null && testMethod.isAnnotationPresent(TimedTest.class)) {
                    mIsTimedTest = true;
                    mIncludeDetailedStats = testMethod.getAnnotation(
                            TimedTest.class).includeDetailedStats();
                } else if (test.getClass().isAnnotationPresent(TimedTest.class)) {
                    mIsTimedTest = true;
                    mIncludeDetailedStats = test.getClass().getAnnotation(
                            TimedTest.class).includeDetailedStats();
                }
            } catch (SecurityException e) {
                // ignore - the test with given name cannot be accessed. Will be handled during
                // test execution
            }

            if (mIsTimedTest && mIncludeDetailedStats) {
                mPerfCollector.beginSnapshot("");
            } else if (mIsTimedTest) {
                mPerfCollector.startTiming("");
            }
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
                    ((TestCase)test).getName(), BaseTestRunner.getFilteredTrace(t)));
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
                    ((TestCase)test).getName(), BaseTestRunner.getFilteredTrace(t)));
        }

        /**
         * @see junit.framework.TestListener#endTest(Test)
         */
        public void endTest(Test test) {
            if (mIsTimedTest && mIncludeDetailedStats) {
                mTestResult.putAll(mPerfCollector.endSnapshot());
            } else if (mIsTimedTest) {
                writeStopTiming(mPerfCollector.stopTiming(""));
            }

            if (mTestResultCode == 0) {
                mTestResult.putString(Instrumentation.REPORT_KEY_STREAMRESULT, ".");
            }
            sendStatus(mTestResultCode, mTestResult);

            try { // Sleep after every test, if specified
                Thread.sleep(mDelayMsec);
            } catch (InterruptedException e) {
                throw new IllegalStateException(e);
            }
        }

        public void writeBeginSnapshot(String label) {
            // Do nothing
        }

        public void writeEndSnapshot(Bundle results) {
            // Copy all snapshot data fields into mResults, which is outputted
            // via Instrumentation.finish
            mResults.putAll(results);
        }

        public void writeStartTiming(String label) {
            // Do nothing
        }

        public void writeStopTiming(Bundle results) {
            // Copy results into mTestResult by flattening list of iterations,
            // which is outputted via WatcherResultPrinter.endTest
            int i = 0;
            for (Parcelable p :
                    results.getParcelableArrayList(PerformanceCollector.METRIC_KEY_ITERATIONS)) {
                Bundle iteration = (Bundle)p;
                String index = "iteration" + i + ".";
                mTestResult.putString(index + PerformanceCollector.METRIC_KEY_LABEL,
                        iteration.getString(PerformanceCollector.METRIC_KEY_LABEL));
                mTestResult.putLong(index + PerformanceCollector.METRIC_KEY_CPU_TIME,
                        iteration.getLong(PerformanceCollector.METRIC_KEY_CPU_TIME));
                mTestResult.putLong(index + PerformanceCollector.METRIC_KEY_EXECUTION_TIME,
                        iteration.getLong(PerformanceCollector.METRIC_KEY_EXECUTION_TIME));
                i++;
            }
        }

        public void writeMeasurement(String label, long value) {
            mTestResult.putLong(label, value);
        }

        public void writeMeasurement(String label, float value) {
            mTestResult.putFloat(label, value);
        }

        public void writeMeasurement(String label, String value) {
            mTestResult.putString(label, value);
        }

        // TODO report the end of the cycle
    }
}
