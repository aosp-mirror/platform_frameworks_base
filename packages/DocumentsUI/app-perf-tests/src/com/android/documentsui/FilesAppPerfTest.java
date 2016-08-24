/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.documentsui;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.support.test.uiautomator.UiDevice;
import android.test.InstrumentationTestCase;
import android.test.suitebuilder.annotation.LargeTest;
import android.util.Log;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;

@LargeTest
public class FilesAppPerfTest extends InstrumentationTestCase {

    // Keys used to report metrics to APCT.
    private static final String KEY_FILES_COLD_START_PERFORMANCE_MEDIAN =
            "files-cold-start-performance-median";
    private static final String KEY_FILES_WARM_START_PERFORMANCE_MEDIAN =
            "files-warm-start-performance-median";

    private static final String TARGET_PACKAGE = "com.android.documentsui";

    private static final int NUM_MEASUREMENTS = 10;

    private LauncherActivity mActivity;
    private UiDevice mDevice;

    @Override
    public void setUp() {
        mDevice = UiDevice.getInstance(getInstrumentation());
    }

    public void testFilesColdStartPerformance() throws Exception {
        runFilesStartPerformanceTest(true);
    }

    public void testFilesWarmStartPerformance() throws Exception {
        runFilesStartPerformanceTest(false);
    }

    public void runFilesStartPerformanceTest(boolean cold) throws Exception {
        long[] measurements = new long[NUM_MEASUREMENTS];
        for (int i = 0; i < NUM_MEASUREMENTS; i++) {
            if (cold) {
                // Kill all providers, as well as DocumentsUI to measure a cold start.
                killProviders();
                mDevice.executeShellCommand("am force-stop " + TARGET_PACKAGE);
            }
            mDevice.waitForIdle();

            LauncherActivity.testCaseLatch = new CountDownLatch(1);
            mActivity = launchActivity(getInstrumentation().getTargetContext().getPackageName(),
                    LauncherActivity.class, null);
            LauncherActivity.testCaseLatch.await();
            measurements[i] = LauncherActivity.measurement;
        }

        reportMetrics(cold ? KEY_FILES_COLD_START_PERFORMANCE_MEDIAN
                : KEY_FILES_WARM_START_PERFORMANCE_MEDIAN, measurements);
    }

    private void reportMetrics(String key, long[] measurements) {
        final Bundle status = new Bundle();
        Arrays.sort(measurements);
        final long median = measurements[NUM_MEASUREMENTS / 2 - 1];
        status.putDouble(key, median);

        getInstrumentation().sendStatus(Activity.RESULT_OK, status);
    }

    private void killProviders() throws Exception {
        final Context context = getInstrumentation().getContext();
        final PackageManager pm = context.getPackageManager();
        final ActivityManager am = (ActivityManager) context.getSystemService(
                Context.ACTIVITY_SERVICE);
        final Intent intent = new Intent(DocumentsContract.PROVIDER_INTERFACE);
        final List<ResolveInfo> providers = pm.queryIntentContentProviders(intent, 0);
        for (ResolveInfo info : providers) {
            final String packageName = info.providerInfo.packageName;
            am.killBackgroundProcesses(packageName);
        }
    }
}
