/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package android.os;

import static android.content.pm.PackageManager.PERMISSION_DENIED;
import static android.content.pm.PackageManager.PERMISSION_GRANTED;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.perftests.utils.BenchmarkState;
import android.perftests.utils.PerfStatusReporter;
import android.support.test.InstrumentationRegistry;
import android.support.test.filters.LargeTest;
import android.support.test.runner.AndroidJUnit4;

import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
@LargeTest
public class PackageManagerPerfTest {
    private static final String PERMISSION_NAME_EXISTS =
            "com.android.perftests.core.TestPermission";
    private static final String PERMISSION_NAME_DOESNT_EXIST =
            "com.android.perftests.core.TestBadPermission";
    private static final ComponentName TEST_ACTIVITY =
            new ComponentName("com.android.perftests.core", "android.perftests.utils.StubActivity");

    @Rule
    public PerfStatusReporter mPerfStatusReporter = new PerfStatusReporter();

    @Test
    public void testCheckPermissionExists() {
        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        final PackageManager pm = InstrumentationRegistry.getTargetContext().getPackageManager();
        final String packageName = TEST_ACTIVITY.getPackageName();

        while (state.keepRunning()) {
            int ret = pm.checkPermission(PERMISSION_NAME_EXISTS, packageName);
        }
    }

    @Test
    public void testCheckPermissionDoesntExist() {
        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        final PackageManager pm = InstrumentationRegistry.getTargetContext().getPackageManager();
        final String packageName = TEST_ACTIVITY.getPackageName();

        while (state.keepRunning()) {
            int ret = pm.checkPermission(PERMISSION_NAME_DOESNT_EXIST, packageName);
        }
    }

    @Test
    public void testQueryIntentActivities() {
        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        final PackageManager pm = InstrumentationRegistry.getTargetContext().getPackageManager();
        final Intent intent = new Intent("com.android.perftests.core.PERFTEST");

        while (state.keepRunning()) {
            pm.queryIntentActivities(intent, 0);
        }
    }

    @Test
    public void testGetPackageInfo() throws Exception {
        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        final PackageManager pm = InstrumentationRegistry.getTargetContext().getPackageManager();
        final String packageName = TEST_ACTIVITY.getPackageName();

        while (state.keepRunning()) {
            pm.getPackageInfo(packageName, 0);
        }
    }

    @Test
    public void testGetApplicationInfo() throws Exception {
        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        final PackageManager pm = InstrumentationRegistry.getTargetContext().getPackageManager();
        final String packageName = TEST_ACTIVITY.getPackageName();
        
        while (state.keepRunning()) {
            pm.getApplicationInfo(packageName, 0);
        }
    }

    @Test
    public void testGetActivityInfo() throws Exception {
        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        final PackageManager pm = InstrumentationRegistry.getTargetContext().getPackageManager();
        
        while (state.keepRunning()) {
            pm.getActivityInfo(TEST_ACTIVITY, 0);
        }
    }
}
