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
import android.net.Uri;
import com.google.android.collect.Lists;
import junit.framework.Test;
import junit.framework.TestCase;
import junit.framework.TestSuite;

import java.util.List;

/**
 * @hide - This is part of a framework that is under development and should not be used for
 * active development.
 */
public class TestBrowserControllerImpl implements TestBrowserController {

    static final String TEST_RUNNER_ACTIVITY_CLASS_NAME =
           "com.android.testharness.TestRunnerActivity";

    private TestSuite mTestSuite;
    private TestBrowserView mTestBrowserView;
    private static final int RUN_ALL_INDEX = 0;
    private String mTargetBrowserActivityClassName;
    private String mTargetPackageName;

    public void setTargetPackageName(String targetPackageName) {
        mTargetPackageName = targetPackageName;
    }

    public Intent getIntentForTestAt(int position) {
        Intent intent = new Intent();
        intent.setAction(Intent.ACTION_RUN);
        // We must add the following two flags to make sure that we create a new activity when
        // we browse nested test suites.
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.addFlags(Intent.FLAG_ACTIVITY_MULTIPLE_TASK);

        String packageName = getDefaultPackageNameForTestRunner();
        String className = "";
        String testName = "";
        if (shouldAllTestsBeRun(position)) {
            testName = mTestSuite.getName();
            className = TEST_RUNNER_ACTIVITY_CLASS_NAME;
        } else {
            Test test = TestCaseUtil.getTestAtIndex(mTestSuite, position - 1);
            if (TestSuite.class.isAssignableFrom(test.getClass())) {
                TestSuite testSuite = (TestSuite) test;
                testName = testSuite.getName();
                className = mTargetBrowserActivityClassName;
                packageName = mTargetPackageName;
            } else if (TestCase.class.isAssignableFrom(test.getClass())) {
                TestCase testCase = (TestCase) test;
                testName = testCase.getClass().getName();
                className = TEST_RUNNER_ACTIVITY_CLASS_NAME;
                String testMethodName = testCase.getName();
                intent.putExtra(BUNDLE_EXTRA_TEST_METHOD_NAME, testMethodName);
            }
        }

        intent.setClassName(packageName, className);
        intent.setData(Uri.parse(testName));

        return intent;
    }

    private String getDefaultPackageNameForTestRunner() {
        return TEST_RUNNER_ACTIVITY_CLASS_NAME.substring(0,
                TEST_RUNNER_ACTIVITY_CLASS_NAME.lastIndexOf("."));
    }

    private boolean shouldAllTestsBeRun(int position) {
        return position == RUN_ALL_INDEX;
    }

    public void setTestSuite(TestSuite testSuite) {
        mTestSuite = testSuite;

        List<String> testCaseNames = Lists.newArrayList();
        testCaseNames.add("Run All");
        testCaseNames.addAll(TestCaseUtil.getTestCaseNames(testSuite, false));

        mTestBrowserView.setTestNames(testCaseNames);
    }

    public void registerView(TestBrowserView testBrowserView) {
        mTestBrowserView = testBrowserView;
    }


    public void setTargetBrowserActivityClassName(String targetBrowserActivityClassName) {
        mTargetBrowserActivityClassName = targetBrowserActivityClassName;
    }
}
