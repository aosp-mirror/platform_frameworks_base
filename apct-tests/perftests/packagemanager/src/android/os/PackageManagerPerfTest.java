/*
 * Copyright (C) 2020 The Android Open Source Project
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

package android.os;

import static libcore.junit.util.compat.CoreCompatChangeRule.DisableCompatChanges;
import static libcore.junit.util.compat.CoreCompatChangeRule.EnableCompatChanges;

import android.compat.testing.PlatformCompatChangeRule;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.perftests.utils.BenchmarkState;
import android.perftests.utils.PerfStatusReporter;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.LargeTest;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
@LargeTest
public class PackageManagerPerfTest {
    private static final String PERMISSION_NAME_EXISTS =
            "com.android.perftests.packagemanager.TestPermission";
    private static final String PERMISSION_NAME_DOESNT_EXIST =
            "com.android.perftests.packagemanager.TestBadPermission";
    private static final ComponentName TEST_ACTIVITY =
            new ComponentName("com.android.perftests.packagemanager",
                    "android.perftests.utils.PerfTestActivity");

    @Rule
    public final PerfStatusReporter mPerfStatusReporter = new PerfStatusReporter();

    @Rule
    public final PlatformCompatChangeRule mPlatformCompatChangeRule =
            new PlatformCompatChangeRule();

    public PackageManagerPerfTest() throws PackageManager.NameNotFoundException {
        final Context context = InstrumentationRegistry.getInstrumentation().getContext();
    }

    @Before
    public void setup() {
        PackageManager.disableApplicationInfoCache();
        PackageManager.disablePackageInfoCache();
    }

    @Test
    @DisableCompatChanges(PackageManager.FILTER_APPLICATION_QUERY)
    public void testCheckPermissionExists() {
        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        final PackageManager pm =
                InstrumentationRegistry.getInstrumentation().getTargetContext().getPackageManager();
        final String packageName = TEST_ACTIVITY.getPackageName();

        while (state.keepRunning()) {
            int ret = pm.checkPermission(PERMISSION_NAME_EXISTS, packageName);
        }
    }

    @Test
    @EnableCompatChanges(PackageManager.FILTER_APPLICATION_QUERY)
    public void testCheckPermissionExistsWithFiltering() {
        testCheckPermissionExists();
    }

    @Test
    @DisableCompatChanges(PackageManager.FILTER_APPLICATION_QUERY)
    public void testCheckPermissionDoesntExist() {
        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        final PackageManager pm =
                InstrumentationRegistry.getInstrumentation().getTargetContext().getPackageManager();
        final String packageName = TEST_ACTIVITY.getPackageName();

        while (state.keepRunning()) {
            int ret = pm.checkPermission(PERMISSION_NAME_DOESNT_EXIST, packageName);
        }
    }

    @Test
    @EnableCompatChanges(PackageManager.FILTER_APPLICATION_QUERY)
    public void testCheckPermissionDoesntExistWithFiltering() {
        testCheckPermissionDoesntExist();
    }

    @Test
    @DisableCompatChanges(PackageManager.FILTER_APPLICATION_QUERY)
    public void testQueryIntentActivities() {
        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        final PackageManager pm =
                InstrumentationRegistry.getInstrumentation().getTargetContext().getPackageManager();
        final Intent intent = new Intent("com.android.perftests.core.PERFTEST");

        while (state.keepRunning()) {
            pm.queryIntentActivities(intent, 0);
        }
    }

    @Test
    @EnableCompatChanges(PackageManager.FILTER_APPLICATION_QUERY)
    public void testQueryIntentActivitiesWithFiltering() {
        testQueryIntentActivities();
    }

    @Test
    @DisableCompatChanges(PackageManager.FILTER_APPLICATION_QUERY)
    public void testGetPackageInfo() throws Exception {
        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        final PackageManager pm =
                InstrumentationRegistry.getInstrumentation().getTargetContext().getPackageManager();

        while (state.keepRunning()) {
            pm.getPackageInfo(TEST_ACTIVITY.getPackageName(), 0);
        }
    }

    @Test
    @EnableCompatChanges(PackageManager.FILTER_APPLICATION_QUERY)
    public void testGetPackageInfoWithFiltering() throws Exception {
        testGetPackageInfo();
    }

    @Test
    @DisableCompatChanges(PackageManager.FILTER_APPLICATION_QUERY)
    public void testGetApplicationInfo() throws Exception {
        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        final PackageManager pm =
                InstrumentationRegistry.getInstrumentation().getTargetContext().getPackageManager();

        while (state.keepRunning()) {
            pm.getApplicationInfo(TEST_ACTIVITY.getPackageName(), 0);
        }
    }

    @Test
    @EnableCompatChanges(PackageManager.FILTER_APPLICATION_QUERY)
    public void testGetApplicationInfoWithFiltering() throws Exception {
        testGetApplicationInfo();
    }

    @Test
    @DisableCompatChanges(PackageManager.FILTER_APPLICATION_QUERY)
    public void testGetActivityInfo() throws Exception {
        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        final PackageManager pm =
                InstrumentationRegistry.getInstrumentation().getTargetContext().getPackageManager();

        while (state.keepRunning()) {
            pm.getActivityInfo(TEST_ACTIVITY, 0);
        }
    }

    @Test
    @EnableCompatChanges(PackageManager.FILTER_APPLICATION_QUERY)
    public void testGetActivityInfoWithFiltering() throws Exception {
        testGetActivityInfo();
    }

    @Test
    @DisableCompatChanges(PackageManager.FILTER_APPLICATION_QUERY)
    public void testGetInstalledPackages() throws Exception {
        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        final PackageManager pm =
                InstrumentationRegistry.getInstrumentation().getTargetContext().getPackageManager();

        while (state.keepRunning()) {
            pm.getInstalledPackages(0);
        }
    }

    @Test
    @EnableCompatChanges(PackageManager.FILTER_APPLICATION_QUERY)
    public void testGetInstalledPackagesWithFiltering() throws Exception {
        testGetInstalledPackages();
    }
}
