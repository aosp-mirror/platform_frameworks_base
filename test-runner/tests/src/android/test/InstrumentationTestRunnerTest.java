/*
 * Copyright (C) 2008 The Android Open Source Project
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

import android.content.Context;
import android.os.Bundle;
import android.test.mock.MockContext;
import android.test.suitebuilder.ListTestCaseNames;
import android.test.suitebuilder.ListTestCaseNames.TestDescriptor;
import android.test.suitebuilder.annotation.SmallTest;

import junit.framework.Test;
import junit.framework.TestCase;
import junit.framework.TestSuite;

import java.util.List;

/**
 * Tests for {@link InstrumentationTestRunner}
 */
@SmallTest
public class InstrumentationTestRunnerTest extends TestCase {
    private StubInstrumentationTestRunner mInstrumentationTestRunner;
    private StubAndroidTestRunner mStubAndroidTestRunner;
    private String mTargetContextPackageName;

    protected void setUp() throws Exception {
        super.setUp();
        mStubAndroidTestRunner = new StubAndroidTestRunner();
        mTargetContextPackageName = "android.test.suitebuilder.examples";
        mInstrumentationTestRunner = new StubInstrumentationTestRunner(
                new StubContext("com.google.foo.tests"),
                new StubContext(mTargetContextPackageName), mStubAndroidTestRunner);
    }

    public void testOverrideTestToRunWithClassArgument() throws Exception {
        String expectedTestClassName = PlaceHolderTest.class.getName();
        mInstrumentationTestRunner.onCreate(createBundle(
                InstrumentationTestRunner.ARGUMENT_TEST_CLASS, expectedTestClassName));

        assertTestRunnerCalledWithExpectedParameters(expectedTestClassName, "testPlaceHolder");
    }

    public void testOverrideTestToRunWithClassAndMethodArgument() throws Exception {
        String expectedTestClassName = PlaceHolderTest.class.getName();
        String expectedTestMethodName = "testPlaceHolder";
        String classAndMethod = expectedTestClassName + "#" + expectedTestMethodName;
        mInstrumentationTestRunner.onCreate(createBundle(
                InstrumentationTestRunner.ARGUMENT_TEST_CLASS, classAndMethod));

        assertTestRunnerCalledWithExpectedParameters(expectedTestClassName,
                expectedTestMethodName);
    }

    public void testUseSelfAsTestSuiteProviderWhenNoMetaDataOrClassArgument() throws Exception {
        TestSuite testSuite = new TestSuite();
        testSuite.addTestSuite(PlaceHolderTest.class);
        mInstrumentationTestRunner.setAllTestsSuite(testSuite);
        mInstrumentationTestRunner.onCreate(null);
        assertTestRunnerCalledWithExpectedParameters(
                PlaceHolderTest.class.getName(), "testPlaceHolder");
    }
    
    public void testMultipleTestClass() throws Exception {
        String classArg = PlaceHolderTest.class.getName() + "," + 
            PlaceHolderTest2.class.getName();
        mInstrumentationTestRunner.onCreate(createBundle(
                InstrumentationTestRunner.ARGUMENT_TEST_CLASS, classArg));
        
        Test test = mStubAndroidTestRunner.getTest();

        assertContentsInOrder(ListTestCaseNames.getTestNames((TestSuite) test),
            new TestDescriptor(PlaceHolderTest.class.getName(), "testPlaceHolder"), 
            new TestDescriptor(PlaceHolderTest2.class.getName(), "testPlaceHolder2"));
        
    }

    public void testDelayParameter() throws Exception {
        int delayMsec = 1000;
        Bundle args = new Bundle();
        args.putInt(InstrumentationTestRunner.ARGUMENT_DELAY_MSEC, delayMsec);
        args.putString(InstrumentationTestRunner.ARGUMENT_TEST_CLASS,
                PlaceHolderTest.class.getName() + "," +
                PlaceHolderTest2.class.getName());
        mInstrumentationTestRunner.onCreate(args);
        Thread t = new Thread() { public void run() { mInstrumentationTestRunner.onStart(); } };

        // Should delay three times: before, between, and after the two tests.
        long beforeTest = System.currentTimeMillis();
        t.start();
        t.join();
        assertTrue(System.currentTimeMillis() > beforeTest + delayMsec * 3);
        assertTrue(mInstrumentationTestRunner.isStarted());
        assertTrue(mInstrumentationTestRunner.isFinished());
        assertTrue(mStubAndroidTestRunner.isRun());
    }

    private void assertContentsInOrder(List<TestDescriptor> actual, TestDescriptor... source) {
        TestDescriptor[] clonedSource = source.clone();
        assertEquals("Unexpected number of items.", clonedSource.length, actual.size());
        for (int i = 0; i < actual.size(); i++) {
            TestDescriptor actualItem = actual.get(i);
            TestDescriptor sourceItem = clonedSource[i];
            assertEquals("Unexpected item. Index: " + i, sourceItem, actualItem);
        }
    }

    private void assertTestRunnerCalledWithExpectedParameters(
            String expectedTestClassName, String expectedTestMethodName) {
        Test test = mStubAndroidTestRunner.getTest();
        assertContentsInOrder(ListTestCaseNames.getTestNames((TestSuite) test),
                new TestDescriptor(expectedTestClassName, expectedTestMethodName));  
        assertTrue(mInstrumentationTestRunner.isStarted());
        assertFalse(mInstrumentationTestRunner.isFinished());
    }

    private Bundle createBundle(String key, String value) {
        Bundle bundle = new Bundle();
        bundle.putString(key, value);
        return bundle;
    }

    private static class StubInstrumentationTestRunner extends InstrumentationTestRunner {
        private Context mContext;
        private Context mTargetContext;
        private boolean mStarted;
        private boolean mFinished;
        private AndroidTestRunner mAndroidTestRunner;
        private TestSuite mTestSuite;
        private TestSuite mDefaultTestSuite;
        private String mPackageNameForDefaultTests;

        public StubInstrumentationTestRunner(Context context, Context targetContext,
                AndroidTestRunner androidTestRunner) {
            this.mContext = context;
            this.mTargetContext = targetContext;
            this.mAndroidTestRunner = androidTestRunner;
        }

        public Context getContext() {
            return mContext;
        }

        public TestSuite getAllTests() {
            return mTestSuite;
        }

        public Context getTargetContext() {
            return mTargetContext;
        }

        protected AndroidTestRunner getAndroidTestRunner() {
            return mAndroidTestRunner;
        }

        public void start() {
            mStarted = true;
        }

        public void finish(int resultCode, Bundle results) {
            mFinished = true;
        }

        public boolean isStarted() {
            return mStarted;
        }

        public boolean isFinished() {
            return mFinished;
        }

        public void setAllTestsSuite(TestSuite testSuite) {
            mTestSuite = testSuite;
        }
        
        public void setDefaultTestsSuite(TestSuite testSuite) {
            mDefaultTestSuite = testSuite;
        }

        public String getPackageNameForDefaultTests() {
            return mPackageNameForDefaultTests;
        }
    }

    private static class StubContext extends MockContext {
        private String mPackageName;

        public StubContext(String packageName) {
            this.mPackageName = packageName;
        }

        @Override
        public String getPackageCodePath() {
            return mPackageName;
        }

        @Override
        public String getPackageName() {
            return mPackageName;
        }

        @Override
        public ClassLoader getClassLoader() {
            return getClass().getClassLoader();
        }
    }

    private static class StubAndroidTestRunner extends AndroidTestRunner {
        private Test mTest;
        private boolean mRun;

        public boolean isRun() {
            return mRun;
        }

        public void setTest(Test test) {
            super.setTest(test);
            mTest = test;
        }

        public Test getTest() {
            return mTest;
        }

        public void runTest() {
            super.runTest();
            mRun = true;
        }
    }

    /**
     * Empty test used for validation
     */
    public static class PlaceHolderTest extends TestCase {

        public PlaceHolderTest() {
            super("testPlaceHolder");
        }

        public void testPlaceHolder() throws Exception {

        }
    }
    
    /**
     * Empty test used for validation
     */
    public static class PlaceHolderTest2 extends TestCase {

        public PlaceHolderTest2() {
            super("testPlaceHolder2");
        }

        public void testPlaceHolder2() throws Exception {

        }
    }
}
