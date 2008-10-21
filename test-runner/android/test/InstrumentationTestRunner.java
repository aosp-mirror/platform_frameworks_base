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

import junit.framework.AssertionFailedError;
import junit.framework.Test;
import junit.framework.TestCase;
import junit.framework.TestListener;
import junit.framework.TestResult;
import junit.framework.TestSuite;
import junit.runner.BaseTestRunner;
import junit.textui.ResultPrinter;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.os.Bundle;
import android.os.Debug;
import android.os.Looper;
import android.test.suitebuilder.InstrumentationTestSuiteBuilder;
import android.test.suitebuilder.TestSuiteBuilder;
import android.test.suitebuilder.UnitTestSuiteBuilder;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;

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
 * <b>Running unit tests:</b> adb shell am instrument -w -e unit true
 * com.android.foo/android.test.InstrumentationTestRunner
 * <p/>
 * <b>Running instrumentation tests:</b> adb shell am instrument -w -e func true
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
 * <b>To debug your tests, set a break point in your code and pass:</b>
 * -e debug true
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
    public static final String ARGUMENT_UNIT_CLASS = "unit";
    /** @hide */
    public static final String ARGUMENT_FUNC_CLASS = "func";
    /** @hide */
    public static final String ARGUMENT_TEST_PACKAGE = "package";
    
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

    private final Bundle mResults = new Bundle();
    private AndroidTestRunner mTestRunner;
    private boolean mDebug;
    private boolean mJustCount;
    private int mTestCount;
    private boolean mBundleOutput;
    private boolean mDatabaseOutput;
    private String mPackageOfTests;

    @Override
    public void onCreate(Bundle arguments) {
        super.onCreate(arguments);

        // Apk paths used to search for test classes when using TestSuiteBuilders.
        String[] apkPaths =
                {getTargetContext().getPackageCodePath(), getContext().getPackageCodePath()};
        ClassPathPackageInfoSource.setApkPaths(apkPaths);

        boolean useUnitTestSuite = false;
        boolean useFunctionalTestSuite = false;

        String testClassName = null;
        Test test = null;

        if (arguments != null) {
            // Test class name passed as an argument should override any meta-data declaration.
            testClassName = arguments.getString(ARGUMENT_TEST_CLASS);
            mDebug = getBooleanArgument(arguments, "debug");
            mJustCount = getBooleanArgument(arguments, "count");
            mBundleOutput = getBooleanArgument(arguments, "bundle");
            mDatabaseOutput = getBooleanArgument(arguments, "database");
            useUnitTestSuite = getBooleanArgument(arguments, ARGUMENT_UNIT_CLASS);
            useFunctionalTestSuite = getBooleanArgument(arguments, ARGUMENT_FUNC_CLASS);
            mPackageOfTests = arguments.getString(ARGUMENT_TEST_PACKAGE);
        }

        if (testClassName == null) {
            if (mPackageOfTests != null) {
                test = createPackageTestSuite(
                        getTargetContext(), useUnitTestSuite,
                        useFunctionalTestSuite, mPackageOfTests);
            } else {
                test = getTestSuite();
            }

            if (test == null) {
                test = createDefaultTestSuite(getTargetContext(), useUnitTestSuite,
                        useFunctionalTestSuite);
            }
            mTestCount = test.countTestCases();
        }

        mTestRunner = getAndroidTestRunner();
        mTestRunner.setContext(getTargetContext());
        mTestRunner.setInstrumentaiton(this);
        mTestRunner.addTestListener(new TestPrinter("TestRunner", false));
        if (mDatabaseOutput) {
            mTestRunner.addTestListener(new TestRecorder());
        }

        if (testClassName != null) {
            int methodSeparatorIndex = testClassName.indexOf('#');
            String testMethodName = null;

            if (methodSeparatorIndex > 0) {
                testMethodName = testClassName.substring(methodSeparatorIndex + 1);
                testClassName = testClassName.substring(0, methodSeparatorIndex);
            }
            mTestRunner.setTestClassName(testClassName, testMethodName);
            mTestCount = mTestRunner.getTestCases().size();
        } else {
            mTestRunner.setTest(test);
        }
        // add this now that we know the count
        if (!mBundleOutput) {
            mTestRunner.addTestListener(new WatcherResultPrinter(mTestCount));
        }

        start();
    }

    private TestSuite createDefaultTestSuite(Context context, boolean useUnitTestSuite,
            boolean useFunctionalTestSuite) {
        return createPackageTestSuite(context, useUnitTestSuite, useFunctionalTestSuite, context.getPackageName());
    }

    private TestSuite createPackageTestSuite(Context context, boolean useUnitTestSuite,
            boolean useFunctionalTestSuite, String packageName) {
        TestSuiteBuilder testSuiteBuilder = null;

        if (useUnitTestSuite) {
            testSuiteBuilder = new UnitTestSuiteBuilder(getClass().getName(),
                    context.getClassLoader());
        } else if (useFunctionalTestSuite) {
            testSuiteBuilder = new InstrumentationTestSuiteBuilder(getClass().getName(),
                    context.getClassLoader());
        } else {
            testSuiteBuilder = new TestSuiteBuilder(getClass().getName(),
                    context.getClassLoader());
        }

        return testSuiteBuilder.includePackages(packageName).build();
    }

    protected AndroidTestRunner getAndroidTestRunner() {
        return new AndroidTestRunner();
    }

    private boolean getBooleanArgument(Bundle arguments, String tag) {
        String tagString = arguments.getString(tag);
        return tagString != null && Boolean.parseBoolean(tagString);
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
    
                if (mBundleOutput) {
                    mTestRunner.addTestListener(new BundleTestListener(mResults));
                } else {
                    mTestRunner.addTestListener(resultPrinter);
                }
    
                long startTime = System.currentTimeMillis();
                mTestRunner.runTest();
                long runTime = System.currentTimeMillis() - startTime;
    
                resultPrinter.print(mTestRunner.getTestResult(), runTime);
            } finally {
                if (!mBundleOutput) {
                    mResults.putString(Instrumentation.REPORT_KEY_STREAMRESULT, 
                            String.format("\nTest results for %s=%s", 
                                    mTestRunner.getTestClassName(), 
                                    byteArrayOutputStream.toString()));
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

    // TODO kill this, use status() and prettyprint model for better output
    private class StringResultPrinter extends ResultPrinter {

        public StringResultPrinter(PrintStream writer) {
            super(writer);
        }

        synchronized void print(TestResult result, long runTime) {
            printHeader(runTime);
            if (mBundleOutput) {
              printErrors(result);
              printFailures(result);
            }
            printFooter(result);
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
        }

        // TODO report the end of the cycle
        // TODO report runtime for each test
    }
    

}
