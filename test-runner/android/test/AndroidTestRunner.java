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

import android.app.Instrumentation;
import android.content.Context;
import android.os.PerformanceCollector.PerformanceResultsWriter;

import com.google.android.collect.Lists;
import junit.framework.Test;
import junit.framework.TestCase;
import junit.framework.TestListener;
import junit.framework.TestResult;
import junit.framework.TestSuite;
import junit.runner.BaseTestRunner;

import java.lang.reflect.InvocationTargetException;
import java.util.List;

public class AndroidTestRunner extends BaseTestRunner {

    private TestResult mTestResult;
    private String mTestClassName;
    private List<TestCase> mTestCases;
    private Context mContext;
    private boolean mSkipExecution = false;

    private List<TestListener> mTestListeners = Lists.newArrayList();
    private Instrumentation mInstrumentation;
    private PerformanceResultsWriter mPerfWriter;

    @SuppressWarnings("unchecked")
    public void setTestClassName(String testClassName, String testMethodName) {
        Class testClass = loadTestClass(testClassName);

        if (shouldRunSingleTestMethod(testMethodName, testClass)) {
            TestCase testCase = buildSingleTestMethod(testClass, testMethodName);
            mTestCases = Lists.newArrayList(testCase);
            mTestClassName = testClass.getSimpleName();
        } else {
            setTest(getTest(testClass), testClass);
        }
    }

    public void setTest(Test test) {
        setTest(test, test.getClass());
    }

    private void setTest(Test test, Class<? extends Test> testClass) {
        mTestCases = (List<TestCase>) TestCaseUtil.getTests(test, true);
        if (TestSuite.class.isAssignableFrom(testClass)) {
            mTestClassName = TestCaseUtil.getTestName(test);
        } else {
            mTestClassName = testClass.getSimpleName();
        }
    }

    public void clearTestListeners() {
        mTestListeners.clear();
    }

    public void addTestListener(TestListener testListener) {
        if (testListener != null) {
            mTestListeners.add(testListener);
        }
    }

    @SuppressWarnings("unchecked")
    private Class<? extends Test> loadTestClass(String testClassName) {
        try {
            return (Class<? extends Test>) mContext.getClassLoader().loadClass(testClassName);
        } catch (ClassNotFoundException e) {
            runFailed("Could not find test class. Class: " + testClassName);
        }
        return null;
    }

    private TestCase buildSingleTestMethod(Class testClass, String testMethodName) {
        try {
            TestCase testCase = (TestCase) testClass.newInstance();
            testCase.setName(testMethodName);
            return testCase;
        } catch (IllegalAccessException e) {
            runFailed("Could not access test class. Class: " + testClass.getName());
        } catch (InstantiationException e) {
            runFailed("Could not instantiate test class. Class: " + testClass.getName());
        }

        return null;
    }

    private boolean shouldRunSingleTestMethod(String testMethodName,
            Class<? extends Test> testClass) {
        return testMethodName != null && TestCase.class.isAssignableFrom(testClass);
    }

    private Test getTest(Class clazz) {
        if (TestSuiteProvider.class.isAssignableFrom(clazz)) {
            try {
                TestSuiteProvider testSuiteProvider =
                        (TestSuiteProvider) clazz.getConstructor().newInstance();
                return testSuiteProvider.getTestSuite();
            } catch (InstantiationException e) {
                runFailed("Could not instantiate test suite provider. Class: " + clazz.getName());
            } catch (IllegalAccessException e) {
                runFailed("Illegal access of test suite provider. Class: " + clazz.getName());
            } catch (InvocationTargetException e) {
                runFailed("Invocation exception test suite provider. Class: " + clazz.getName());
            } catch (NoSuchMethodException e) {
                runFailed("No such method on test suite provider. Class: " + clazz.getName());
            }
        }
        return getTest(clazz.getName());
    }

    protected TestResult createTestResult() {
        if (mSkipExecution) {
            return new NoExecTestResult();
        }
        return new TestResult();
    }

    void setSkipExecution(boolean skip) {
        mSkipExecution = skip;
    }

    public List<TestCase> getTestCases() {
        return mTestCases;
    }

    public String getTestClassName() {
        return mTestClassName;
    }

    public TestResult getTestResult() {
        return mTestResult;
    }

    public void runTest() {
        runTest(createTestResult());
    }

    public void runTest(TestResult testResult) {
        mTestResult = testResult;

        for (TestListener testListener : mTestListeners) {
            mTestResult.addListener(testListener);
        }

        Context testContext = mInstrumentation == null ? mContext : mInstrumentation.getContext();
        for (TestCase testCase : mTestCases) {
            setContextIfAndroidTestCase(testCase, mContext, testContext);
            setInstrumentationIfInstrumentationTestCase(testCase, mInstrumentation);
            setPerformanceWriterIfPerformanceCollectorTestCase(testCase, mPerfWriter);
            testCase.run(mTestResult);
        }
    }

    private void setContextIfAndroidTestCase(Test test, Context context, Context testContext) {
        if (AndroidTestCase.class.isAssignableFrom(test.getClass())) {
            ((AndroidTestCase) test).setContext(context);
            ((AndroidTestCase) test).setTestContext(testContext);
        }
    }

    public void setContext(Context context) {
        mContext = context;
    }

    private void setInstrumentationIfInstrumentationTestCase(
            Test test, Instrumentation instrumentation) {
        if (InstrumentationTestCase.class.isAssignableFrom(test.getClass())) {
            ((InstrumentationTestCase) test).injectInstrumentation(instrumentation);
        }
    }

    private void setPerformanceWriterIfPerformanceCollectorTestCase(
            Test test, PerformanceResultsWriter writer) {
        if (PerformanceCollectorTestCase.class.isAssignableFrom(test.getClass())) {
            ((PerformanceCollectorTestCase) test).setPerformanceResultsWriter(writer);
        }
    }

    public void setInstrumentation(Instrumentation instrumentation) {
        mInstrumentation = instrumentation;
    }

    /**
     * @deprecated Incorrect spelling,
     * use {@link #setInstrumentation(android.app.Instrumentation)} instead.
     */
    @Deprecated
    public void setInstrumentaiton(Instrumentation instrumentation) {
        setInstrumentation(instrumentation);
    }

    /**
     * {@hide} Pending approval for public API.
     */
    public void setPerformanceResultsWriter(PerformanceResultsWriter writer) {
        mPerfWriter = writer;
    }

    @Override
    protected Class loadSuiteClass(String suiteClassName) throws ClassNotFoundException {
        return mContext.getClassLoader().loadClass(suiteClassName);
    }

    public void testStarted(String testName) {
    }

    public void testEnded(String testName) {
    }

    public void testFailed(int status, Test test, Throwable t) {
    }

    protected void runFailed(String message) {
        throw new RuntimeException(message);
    }
}
