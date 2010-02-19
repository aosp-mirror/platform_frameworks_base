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

import android.content.Intent;

import junit.framework.TestCase;
import junit.framework.TestSuite;

import java.util.Arrays;
import java.util.List;

public class TestBrowserControllerImplTest extends TestCase {
    private TestBrowserControllerImpl mTestBrowserController;
    private TestBrowserViewStub mTestBrowserView;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        mTestBrowserController = new TestBrowserControllerImpl();
        mTestBrowserView = new TestBrowserViewStub();
        mTestBrowserController.registerView(mTestBrowserView);
    }

    public void testSetTestSuite() throws Exception {
        TestSuite testSuite = new TestSuite();
        testSuite.addTestSuite(DummyTestCase.class);

        mTestBrowserController.setTestSuite(testSuite);

        verifyTestNames(Arrays.asList("Run All", DummyTestCase.class.getSimpleName()),
                mTestBrowserView.getTestNames());
    }

    private static void verifyTestNames(List<String> expectedTestNames,
            List<String> actualTestNames) {
        assertEquals(expectedTestNames.size(), actualTestNames.size());

        // We use endsWith instead of equals because the return value of
        // class.getSimpleName(), when called on an inner class, varies
        // from one vm to another.
        // This allows the test to pass in multiple environments.
        for (int i = 0; i < expectedTestNames.size(); i++) {
            assertTrue(actualTestNames.get(i).endsWith(expectedTestNames.get(i)));
        }
    }

    public void testGetIntentForTestSuite() throws Exception {
        TestSuite testSuite = new TestSuite();
        testSuite.addTestSuite(DummyTestCase.class);

        String targetBrowserActvityClassName = "com.android.bogus.DummyActivity";
        String expectedTargetPackageName = "com.android.bogus";
        mTestBrowserController.setTargetBrowserActivityClassName(targetBrowserActvityClassName);
        mTestBrowserController.setTestSuite(testSuite);
        mTestBrowserController.setTargetPackageName(expectedTargetPackageName);
        Intent intent = mTestBrowserController.getIntentForTestAt(1);
        verifyIntent(intent, DummyTestCase.class, expectedTargetPackageName);
        assertEquals(targetBrowserActvityClassName, intent.getComponent().getClassName());
    }

    public void testGetIntentForTestCase() throws Exception {
        TestSuite testSuite = new TestSuite();
        testSuite.addTest(new DummyTestCase());

        mTestBrowserController.setTestSuite(testSuite);
        Intent intent = mTestBrowserController.getIntentForTestAt(1);
        verifyIntent(intent, DummyTestCase.class, "com.android.testharness");
        assertEquals(TestBrowserControllerImpl.TEST_RUNNER_ACTIVITY_CLASS_NAME,
                intent.getComponent().getClassName());
        assertEquals("testDummyTest",
                intent.getStringExtra(TestBrowserController.BUNDLE_EXTRA_TEST_METHOD_NAME));
    }

    public void testGetIntentForRunAll() throws Exception {
        TestSuite testSuite = new DummyTestSuite();
        testSuite.addTestSuite(DummyTestCase.class);

        mTestBrowserController.setTestSuite(testSuite);
        Intent intent = mTestBrowserController.getIntentForTestAt(0);
        verifyIntent(intent, DummyTestSuite.class, "com.android.testharness");
    }

    private static void verifyIntent(Intent intent, Class testClass, String expectedPackageName) {
        assertEquals(Intent.ACTION_RUN, intent.getAction());
        assertEquals(Intent.FLAG_ACTIVITY_NEW_TASK,
                intent.getFlags() & Intent.FLAG_ACTIVITY_NEW_TASK);
        assertEquals(Intent.FLAG_ACTIVITY_MULTIPLE_TASK,
                intent.getFlags() & Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
        assertEquals(testClass.getName(), intent.getData().toString());
        assertEquals(expectedPackageName, intent.getComponent().getPackageName());
    }

    private static class DummyTestSuite extends TestSuite {
        private DummyTestSuite() {
            super(DummyTestSuite.class.getName());
        }
    }

    private static class DummyTestCase extends TestCase {
        private DummyTestCase() {
            super("testDummyTest");
        }

        public void testDummyTest() throws Exception {
        }
    }

    private class TestBrowserViewStub implements TestBrowserView {
        private List<String> mTestNames;

        public void setTestNames(List<String> testNames) {
            mTestNames = testNames;
        }

        public List<String> getTestNames() {
            return mTestNames;
        }
    }
}
