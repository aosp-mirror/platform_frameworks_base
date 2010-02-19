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

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.net.Uri;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.view.IWindowManager;
import android.widget.ListView;

import com.google.android.collect.Lists;

import junit.framework.Test;
import junit.framework.TestCase;
import junit.framework.TestSuite;

import java.util.List;

public class TestBrowserActivityTest extends InstrumentationTestCase {

    private TestBrowserActivity mTestBrowserActivity;
    private StubTestBrowserController mTestBrowserController;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        StubTestBrowserActivity.setTopTestSuite(null);
        mTestBrowserController = new StubTestBrowserController();
        ServiceLocator.setTestBrowserController(mTestBrowserController);
    }

    @Override
    protected void tearDown() throws Exception {
        if (mTestBrowserActivity != null) {
            mTestBrowserActivity.finish();
        }
        mTestBrowserActivity = null;
        super.tearDown();
    }

    public void testEmptyListContent() throws Exception {
        StubTestBrowserActivity.setTopTestSuite(new TestSuite());

        mTestBrowserActivity = createActivity();

        ListView listView = getListView();
        // There is always an item on the list for running all tests.
        assertEquals("Unexpected number of items on list view.", 1, listView.getCount());

        assertEquals("Stubbed Test Browser", mTestBrowserActivity.getTitle().toString());
    }

    public void testOneListContent() throws Exception {
        List<String> testCaseNames = Lists.newArrayList("AllTests");
        StubTestBrowserActivity.setTopTestSuite(createTestSuite(testCaseNames));

        mTestBrowserActivity = createActivity();

        ListView listView = getListView();
        assertListViewContents(testCaseNames, listView);
    }

    public void testListWithTestCases() throws Exception {
        List<String> testCaseNames = Lists.newArrayList("AllTests", "Apples", "Bananas", "Oranges");
        StubTestBrowserActivity.setTopTestSuite(createTestSuite(testCaseNames));

        mTestBrowserActivity = createActivity();

        ListView listView = getListView();
        assertListViewContents(testCaseNames, listView);
    }

    public void testListWithTestSuite() throws Exception {
        List<String> testCaseNames = Lists.newArrayList(OneTestTestCase.class.getSimpleName());
        StubTestBrowserActivity.setTopTestSuite(new OneTestInTestSuite());

        mTestBrowserActivity = createActivity();

        ListView listView = getListView();
        assertListViewContents(testCaseNames, listView);
    }

    public void testSelectATestCase() throws Exception {
        List<String> testCaseNames = Lists.newArrayList("AllTests");
        TestSuite testSuite = createTestSuite(testCaseNames);
        StubTestBrowserActivity.setTopTestSuite(testSuite);

        mTestBrowserController.setTestCase(OneTestTestCase.class);
        mTestBrowserActivity = createActivity();

        Instrumentation.ActivityMonitor activityMonitor = getInstrumentation().addMonitor(
                TestBrowserControllerImpl.TEST_RUNNER_ACTIVITY_CLASS_NAME, null, false);
        try {
            assertEquals(0, activityMonitor.getHits());

            ListView listView = getListView();
            int invokedTestCaseIndex = 0;
            listView.performItemClick(listView, invokedTestCaseIndex, 0);

            Activity activity = activityMonitor.waitForActivityWithTimeout(2000);
            assertNotNull(activity);
            try {
                assertEquals(1, activityMonitor.getHits());
                assertEquals(invokedTestCaseIndex, mTestBrowserController.getLastPosition());
            } finally {
                activity.finish();
            }
        } finally {
            getInstrumentation().removeMonitor(activityMonitor);
        }
    }

    public void testCreateFromIntentWithOneTest() throws Exception {
        List<String> testCaseNames = Lists.newArrayList("testOne");

        mTestBrowserActivity = launchTestBrowserActivity(new TestSuite(OneTestTestCase.class));

        ListView listView = getListView();
        assertListViewContents(testCaseNames, listView);
    }

    public void testUpdateListOnStart() throws Exception {
        StubTestBrowserActivity.setTopTestSuite(new TestSuite());

        mTestBrowserActivity = createActivity();

        ListView listView = getListView();
        assertEquals("Unexpected number of items on list view.", 1, listView.getCount());

        List<String> testCaseNames = Lists.newArrayList("AllTests");
        StubTestBrowserActivity.setTopTestSuite(createTestSuite(testCaseNames));

        getInstrumentation().runOnMainSync(new Runnable() {
            public void run() {
                ((StubTestBrowserActivity) mTestBrowserActivity).onStart();
            }
        });

        listView = getListView();
        assertListViewContents(testCaseNames, listView);
    }

    public void testTitleHasTestSuiteName() throws Exception {
        final String testSuiteName = "com.android.TestSuite";
        StubTestBrowserActivity.setTopTestSuite(new TestSuite(testSuiteName));

        mTestBrowserActivity = createActivity();

        assertEquals("TestSuite", mTestBrowserActivity.getTitle().toString());
    }
    
    private TestSuite createTestSuite(List<String> testCaseNames) {
        return createTestSuite(testCaseNames.toArray(new String[testCaseNames.size()]));
    }

    private TestSuite createTestSuite(String... testCaseNames) {
        TestSuite testSuite = new TestSuite();
        for (String testCaseName : testCaseNames) {
            testSuite.addTest(new FakeTestCase(testCaseName));
        }

        return testSuite;
    }

    public static class FakeTestCase extends TestCase {
        public FakeTestCase(String name) {
            super(name);
        }
    }

    public static class OneTestTestCase extends TestCase {
        public void testOne() throws Exception {
        }
    }

    public static class OneTestInTestSuite extends TestSuite {
        public static Test suite() {
            TestSuite suite = new TestSuite(OneTestInTestSuite.class.getName());
            suite.addTestSuite(OneTestTestCase.class);
            return suite;
        }
    }

    private void assertListViewContents(List<String> expectedTestCaseNames, ListView listView) {
        assertEquals("Run All", listView.getItemAtPosition(0).toString());
        assertEquals("Unexpected number of items on list view.",
                expectedTestCaseNames.size() + 1, listView.getCount());
        for (int i = 0; i < expectedTestCaseNames.size(); i++) {
            String expectedTestCaseName = expectedTestCaseNames.get(i);
            String actualTestCaseName = listView.getItemAtPosition(i + 1).toString();
            assertEquals("Unexpected test case name. Index: " + i,
                    expectedTestCaseName, actualTestCaseName);
        }
    }

    private ListView getListView() {
        return mTestBrowserActivity.getListView();
    }

    private TestBrowserActivity createActivity() throws RemoteException {
        return launchActivity(getAndroidPackageName(), StubTestBrowserActivity.class, null);
    }

    private Intent createIntent(TestSuite testSuite) {
        Intent intent = new Intent(Intent.ACTION_RUN);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        String className = StubTestBrowserActivity.class.getName();
        String packageName = getAndroidPackageName();
        intent.setClassName(packageName, className);
        intent.setData(Uri.parse(testSuite.getName()));
        return intent;
    }

    private String getAndroidPackageName() {
        String packageName = getInstrumentation().getTargetContext().getPackageName();
        return packageName;
    }

    private TestBrowserActivity launchTestBrowserActivity(TestSuite testSuite)
            throws RemoteException {
        getInstrumentation().setInTouchMode(false);

        TestBrowserActivity activity =
                (TestBrowserActivity) getInstrumentation().startActivitySync(
                        createIntent(testSuite));
        getInstrumentation().waitForIdleSync();
        return activity;
    }

    private static class StubTestBrowserController extends TestBrowserControllerImpl {
        private int mPosition;
        private Class<? extends TestCase> mTestCaseClass;

        public Intent getIntentForTestAt(int position) {
            mPosition = position;

            Intent intent = new Intent();
            intent.setAction(Intent.ACTION_RUN);

            String className = TestBrowserControllerImpl.TEST_RUNNER_ACTIVITY_CLASS_NAME;
            String testName = mTestCaseClass.getClass().getName();

            String packageName = className.substring(0, className.lastIndexOf("."));
            intent.setClassName(packageName, className);
            intent.setData(Uri.parse(testName));

            return intent;
        }

        public void setTestCase(Class<? extends TestCase> testCaseClass) {
            mTestCaseClass = testCaseClass;
        }

        public int getLastPosition() {
            return mPosition;
        }
    }
}
