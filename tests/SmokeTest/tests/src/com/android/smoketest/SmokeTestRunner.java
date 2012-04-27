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

package com.android.smoketest;

import android.app.ActivityManager;
import android.app.ActivityManager.ProcessErrorStateInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.test.InstrumentationTestRunner;

import junit.framework.TestCase;
import junit.framework.TestSuite;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * A special test runner which does a test-start for each app in a separate testcase
 */
public class SmokeTestRunner extends InstrumentationTestRunner {

    private static final String SUITE_NAME = "Smoke Test Suite";

    /**
     * Returns a single testcase for each app to launch
     */
    @Override
    public TestSuite getAllTests() {
        final TestSuite suite = new TestSuite(SUITE_NAME);

        final PackageManager pm = getTargetContext().getPackageManager();
        final List<ResolveInfo> apps = ProcessErrorsTest.getLauncherActivities(pm);

        final TestCase setupTest = new ProcessErrorsTest() {
            @Override
            public void runTest() throws Exception {
                testSetUpConditions();
            }
        };
        setupTest.setName("testSetUpConditions");
        suite.addTest(setupTest);

        final TestCase postBootTest = new ProcessErrorsTest() {
            @Override
            public void runTest() throws Exception {
                testNoProcessErrorsAfterBoot();
            }
        };
        postBootTest.setName("testNoProcessErrorsAfterBoot");
        suite.addTest(postBootTest);

        for (final ResolveInfo app : apps) {
            final TestCase appTest = new ProcessErrorsTest() {
                @Override
                public void runTest() throws Exception {
                    final Set<ProcessError> errSet = new HashSet<ProcessError>();
                    final Collection<ProcessError> errProcs = runOneActivity(app);
                    if (errProcs != null) {
                        errSet.addAll(errProcs);
                    }

                    if (!errSet.isEmpty()) {
                        fail(String.format("Got %d errors:\n%s", errSet.size(),
                                reportWrappedListContents(errSet)));
                    }
                }
            };
            appTest.setName(app.activityInfo.name);
            suite.addTest(appTest);
        }

        final TestCase asyncErrorTest = new ProcessErrorsTest() {
            @Override
            public void runTest() throws Exception {
                testZZReportAsyncErrors();
            }
        };
        asyncErrorTest.setName("testAsynchronousErrors");
        suite.addTest(asyncErrorTest);

        return suite;
    }
}

