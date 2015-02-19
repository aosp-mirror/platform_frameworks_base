/*
 * Copyright (C) 2012 The Android Open Source Project
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

package com.android.uiautomator.testrunner;

import android.app.Activity;
import android.app.IInstrumentationWatcher;
import android.app.Instrumentation;
import android.content.ComponentName;
import android.os.Bundle;
import android.os.Debug;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.SystemClock;
import android.test.RepetitiveTest;
import android.util.Log;

import com.android.uiautomator.core.ShellUiAutomatorBridge;
import com.android.uiautomator.core.Tracer;
import com.android.uiautomator.core.UiAutomationShellWrapper;
import com.android.uiautomator.core.UiDevice;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.Thread.UncaughtExceptionHandler;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import junit.framework.AssertionFailedError;
import junit.framework.Test;
import junit.framework.TestCase;
import junit.framework.TestListener;
import junit.framework.TestResult;
import junit.runner.BaseTestRunner;
import junit.textui.ResultPrinter;

/**
 * @hide
 */
public class UiAutomatorTestRunner {

    private static final String LOGTAG = UiAutomatorTestRunner.class.getSimpleName();
    private static final int EXIT_OK = 0;
    private static final int EXIT_EXCEPTION = -1;

    private static final String HANDLER_THREAD_NAME = "UiAutomatorHandlerThread";

    private boolean mDebug;
    private boolean mMonkey;
    private Bundle mParams = null;
    private UiDevice mUiDevice;
    private List<String> mTestClasses = null;
    private final FakeInstrumentationWatcher mWatcher = new FakeInstrumentationWatcher();
    private final IAutomationSupport mAutomationSupport = new IAutomationSupport() {
        @Override
        public void sendStatus(int resultCode, Bundle status) {
            mWatcher.instrumentationStatus(null, resultCode, status);
        }
    };
    private final List<TestListener> mTestListeners = new ArrayList<TestListener>();

    private HandlerThread mHandlerThread;

    public void run(List<String> testClasses, Bundle params, boolean debug, boolean monkey) {
        Thread.setDefaultUncaughtExceptionHandler(new UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread thread, Throwable ex) {
                Log.e(LOGTAG, "uncaught exception", ex);
                Bundle results = new Bundle();
                results.putString("shortMsg", ex.getClass().getName());
                results.putString("longMsg", ex.getMessage());
                mWatcher.instrumentationFinished(null, 0, results);
                // bailing on uncaught exception
                System.exit(EXIT_EXCEPTION);
            }
        });

        mTestClasses = testClasses;
        mParams = params;
        mDebug = debug;
        mMonkey = monkey;
        start();
        System.exit(EXIT_OK);
    }

    /**
     * Called after all test classes are in place, ready to test
     */
    protected void start() {
        TestCaseCollector collector = getTestCaseCollector(this.getClass().getClassLoader());
        try {
            collector.addTestClasses(mTestClasses);
        } catch (ClassNotFoundException e) {
            // will be caught by uncaught handler
            throw new RuntimeException(e.getMessage(), e);
        }
        if (mDebug) {
            Debug.waitForDebugger();
        }
        mHandlerThread = new HandlerThread(HANDLER_THREAD_NAME);
        mHandlerThread.setDaemon(true);
        mHandlerThread.start();
        UiAutomationShellWrapper automationWrapper = new UiAutomationShellWrapper();
        automationWrapper.connect();

        long startTime = SystemClock.uptimeMillis();
        TestResult testRunResult = new TestResult();
        ResultReporter resultPrinter;
        String outputFormat = mParams.getString("outputFormat");
        List<TestCase> testCases = collector.getTestCases();
        Bundle testRunOutput = new Bundle();
        if ("simple".equals(outputFormat)) {
            resultPrinter = new SimpleResultPrinter(System.out, true);
        } else {
            resultPrinter = new WatcherResultPrinter(testCases.size());
        }
        try {
            automationWrapper.setRunAsMonkey(mMonkey);
            mUiDevice = UiDevice.getInstance();
            mUiDevice.initialize(new ShellUiAutomatorBridge(automationWrapper.getUiAutomation()));

            String traceType = mParams.getString("traceOutputMode");
            if(traceType != null) {
                Tracer.Mode mode = Tracer.Mode.valueOf(Tracer.Mode.class, traceType);
                if (mode == Tracer.Mode.FILE || mode == Tracer.Mode.ALL) {
                    String filename = mParams.getString("traceLogFilename");
                    if (filename == null) {
                        throw new RuntimeException("Name of log file not specified. " +
                                "Please specify it using traceLogFilename parameter");
                    }
                    Tracer.getInstance().setOutputFilename(filename);
                }
                Tracer.getInstance().setOutputMode(mode);
            }

            // add test listeners
            testRunResult.addListener(resultPrinter);
            // add all custom listeners
            for (TestListener listener : mTestListeners) {
                testRunResult.addListener(listener);
            }

            // run tests for realz!
            for (TestCase testCase : testCases) {
                prepareTestCase(testCase);
                testCase.run(testRunResult);
            }
        } catch (Throwable t) {
            // catch all exceptions so a more verbose error message can be outputted
            resultPrinter.printUnexpectedError(t);
            testRunOutput.putString("shortMsg", t.getMessage());
        } finally {
            long runTime = SystemClock.uptimeMillis() - startTime;
            resultPrinter.print(testRunResult, runTime, testRunOutput);
            automationWrapper.disconnect();
            automationWrapper.setRunAsMonkey(false);
            mHandlerThread.quit();
        }
    }

    // copy & pasted from com.android.commands.am.Am
    private class FakeInstrumentationWatcher implements IInstrumentationWatcher {

        private final boolean mRawMode = true;

        @Override
        public IBinder asBinder() {
            throw new UnsupportedOperationException("I'm just a fake!");
        }

        @Override
        public void instrumentationStatus(ComponentName name, int resultCode, Bundle results) {
            synchronized (this) {
                // pretty printer mode?
                String pretty = null;
                if (!mRawMode && results != null) {
                    pretty = results.getString(Instrumentation.REPORT_KEY_STREAMRESULT);
                }
                if (pretty != null) {
                    System.out.print(pretty);
                } else {
                    if (results != null) {
                        for (String key : results.keySet()) {
                            System.out.println("INSTRUMENTATION_STATUS: " + key + "="
                                    + results.get(key));
                        }
                    }
                    System.out.println("INSTRUMENTATION_STATUS_CODE: " + resultCode);
                }
                notifyAll();
            }
        }

        @Override
        public void instrumentationFinished(ComponentName name, int resultCode, Bundle results) {
            synchronized (this) {
                // pretty printer mode?
                String pretty = null;
                if (!mRawMode && results != null) {
                    pretty = results.getString(Instrumentation.REPORT_KEY_STREAMRESULT);
                }
                if (pretty != null) {
                    System.out.println(pretty);
                } else {
                    if (results != null) {
                        for (String key : results.keySet()) {
                            System.out.println("INSTRUMENTATION_RESULT: " + key + "="
                                    + results.get(key));
                        }
                    }
                    System.out.println("INSTRUMENTATION_CODE: " + resultCode);
                }
                notifyAll();
            }
        }
    }

    private interface ResultReporter extends TestListener {
        public void print(TestResult result, long runTime, Bundle testOutput);
        public void printUnexpectedError(Throwable t);
    }

    // Copy & pasted from InstrumentationTestRunner.WatcherResultPrinter
    private class WatcherResultPrinter implements ResultReporter {

        private static final String REPORT_KEY_NUM_TOTAL = "numtests";
        private static final String REPORT_KEY_NAME_CLASS = "class";
        private static final String REPORT_KEY_NUM_CURRENT = "current";
        private static final String REPORT_KEY_NAME_TEST = "test";
        private static final String REPORT_KEY_NUM_ITERATIONS = "numiterations";
        private static final String REPORT_VALUE_ID = "UiAutomatorTestRunner";
        private static final String REPORT_KEY_STACK = "stack";

        private static final int REPORT_VALUE_RESULT_START = 1;
        private static final int REPORT_VALUE_RESULT_ERROR = -1;
        private static final int REPORT_VALUE_RESULT_FAILURE = -2;

        private final Bundle mResultTemplate;
        Bundle mTestResult;
        int mTestNum = 0;
        int mTestResultCode = 0;
        String mTestClass = null;

        private final SimpleResultPrinter mPrinter;
        private final ByteArrayOutputStream mStream;
        private final PrintStream mWriter;

        public WatcherResultPrinter(int numTests) {
            mResultTemplate = new Bundle();
            mResultTemplate.putString(Instrumentation.REPORT_KEY_IDENTIFIER, REPORT_VALUE_ID);
            mResultTemplate.putInt(REPORT_KEY_NUM_TOTAL, numTests);

            mStream = new ByteArrayOutputStream();
            mWriter = new PrintStream(mStream);
            mPrinter = new SimpleResultPrinter(mWriter, false);
        }

        /**
         * send a status for the start of a each test, so long tests can be seen
         * as "running"
         */
        @Override
        public void startTest(Test test) {
            String testClass = test.getClass().getName();
            String testName = ((TestCase) test).getName();
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
                    int numIterations = testMethod.getAnnotation(RepetitiveTest.class)
                            .numIterations();
                    mTestResult.putInt(REPORT_KEY_NUM_ITERATIONS, numIterations);
                }
            } catch (NoSuchMethodException e) {
                // ignore- the test with given name does not exist. Will be
                // handled during test
                // execution
            }

            mAutomationSupport.sendStatus(REPORT_VALUE_RESULT_START, mTestResult);
            mTestResultCode = 0;

            mPrinter.startTest(test);
        }

        @Override
        public void addError(Test test, Throwable t) {
            mTestResult.putString(REPORT_KEY_STACK, BaseTestRunner.getFilteredTrace(t));
            mTestResultCode = REPORT_VALUE_RESULT_ERROR;
            // pretty printing
            mTestResult.putString(Instrumentation.REPORT_KEY_STREAMRESULT,
                String.format("\nError in %s:\n%s",
                    ((TestCase)test).getName(), BaseTestRunner.getFilteredTrace(t)));

            mPrinter.addError(test, t);
        }

        @Override
        public void addFailure(Test test, AssertionFailedError t) {
            mTestResult.putString(REPORT_KEY_STACK, BaseTestRunner.getFilteredTrace(t));
            mTestResultCode = REPORT_VALUE_RESULT_FAILURE;
            // pretty printing
            mTestResult.putString(Instrumentation.REPORT_KEY_STREAMRESULT,
                String.format("\nFailure in %s:\n%s",
                    ((TestCase)test).getName(), BaseTestRunner.getFilteredTrace(t)));

            mPrinter.addFailure(test, t);
        }

        @Override
        public void endTest(Test test) {
            if (mTestResultCode == 0) {
                mTestResult.putString(Instrumentation.REPORT_KEY_STREAMRESULT, ".");
            }
            mAutomationSupport.sendStatus(mTestResultCode, mTestResult);

            mPrinter.endTest(test);
        }

        @Override
        public void print(TestResult result, long runTime, Bundle testOutput) {
            mPrinter.print(result, runTime, testOutput);
            testOutput.putString(Instrumentation.REPORT_KEY_STREAMRESULT,
                  String.format("\nTest results for %s=%s",
                  getClass().getSimpleName(),
                  mStream.toString()));
            mWriter.close();
            mAutomationSupport.sendStatus(Activity.RESULT_OK, testOutput);
        }

        @Override
        public void printUnexpectedError(Throwable t) {
            mWriter.println(String.format("Test run aborted due to unexpected exception: %s",
                    t.getMessage()));
            t.printStackTrace(mWriter);
        }
    }

    /**
     * Class that produces the same output as JUnit when running from command line. Can be
     * used when default UiAutomator output is too verbose.
     */
    private class SimpleResultPrinter extends ResultPrinter implements ResultReporter {
        private final boolean mFullOutput;
        public SimpleResultPrinter(PrintStream writer, boolean fullOutput) {
            super(writer);
            mFullOutput = fullOutput;
        }

        @Override
        public void print(TestResult result, long runTime, Bundle testOutput) {
            printHeader(runTime);
            if (mFullOutput) {
                printErrors(result);
                printFailures(result);
            }
            printFooter(result);
        }

        @Override
        public void printUnexpectedError(Throwable t) {
            if (mFullOutput) {
                getWriter().printf("Test run aborted due to unexpected exeption: %s",
                        t.getMessage());
                t.printStackTrace(getWriter());
            }
        }
    }

    protected TestCaseCollector getTestCaseCollector(ClassLoader classLoader) {
        return new TestCaseCollector(classLoader, getTestCaseFilter());
    }

    /**
     * Returns an object which determines if the class and its methods should be
     * accepted into the test suite.
     * @return
     */
    public UiAutomatorTestCaseFilter getTestCaseFilter() {
        return new UiAutomatorTestCaseFilter();
    }

    protected void addTestListener(TestListener listener) {
        if (!mTestListeners.contains(listener)) {
            mTestListeners.add(listener);
        }
    }

    protected void removeTestListener(TestListener listener) {
        mTestListeners.remove(listener);
    }

    /**
     * subclass may override this method to perform further preparation
     *
     * @param testCase
     */
    protected void prepareTestCase(TestCase testCase) {
        ((UiAutomatorTestCase)testCase).setAutomationSupport(mAutomationSupport);
        ((UiAutomatorTestCase)testCase).setUiDevice(mUiDevice);
        ((UiAutomatorTestCase)testCase).setParams(mParams);
    }
}
