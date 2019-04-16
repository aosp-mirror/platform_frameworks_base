/*
 * Copyright (C) 2017 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.server.job;

import static com.android.servicestests.apps.jobtestapp.TestJobService.ACTION_JOB_STARTED;
import static com.android.servicestests.apps.jobtestapp.TestJobService.ACTION_JOB_STOPPED;
import static com.android.servicestests.apps.jobtestapp.TestJobService.JOB_PARAMS_EXTRA_KEY;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.ActivityManager;
import android.app.AppOpsManager;
import android.app.IActivityManager;
import android.app.job.JobParameters;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.IDeviceIdleController;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.Log;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.LargeTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.servicestests.apps.jobtestapp.TestJobActivity;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Tests that background restrictions on jobs work as expected.
 * This test requires test-apps/JobTestApp to be installed on the device.
 * To run this test from root of checkout:
 * <pre>
 *  mmm -j32 frameworks/base/services/tests/servicestests/
 *  adb install -r $OUT/data/app/JobTestApp/JobTestApp.apk
 *  adb install -r $OUT/data/app/FrameworksServicesTests/FrameworksServicesTests.apk
 *  adb  shell am instrument -e class 'com.android.server.job.BackgroundRestrictionsTest' -w \
    com.android.frameworks.servicestests
 * </pre>
 */
@RunWith(AndroidJUnit4.class)
@LargeTest
public class BackgroundRestrictionsTest {
    private static final String TAG = BackgroundRestrictionsTest.class.getSimpleName();
    private static final String TEST_APP_PACKAGE = "com.android.servicestests.apps.jobtestapp";
    private static final String TEST_APP_ACTIVITY = TEST_APP_PACKAGE + ".TestJobActivity";
    private static final long POLL_INTERVAL = 2000;
    private static final long DEFAULT_WAIT_TIMEOUT = 5000;

    private Context mContext;
    private AppOpsManager mAppOpsManager;
    private IDeviceIdleController mDeviceIdleController;
    private IActivityManager mIActivityManager;
    private int mTestJobId;
    private int mTestPackageUid;
    /* accesses must be synchronized on itself */
    private final TestJobStatus mTestJobStatus = new TestJobStatus();
    private final BroadcastReceiver mJobStateChangeReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final JobParameters params = intent.getParcelableExtra(JOB_PARAMS_EXTRA_KEY);
            Log.d(TAG, "Received action " + intent.getAction());
            synchronized (mTestJobStatus) {
                switch (intent.getAction()) {
                    case ACTION_JOB_STARTED:
                        mTestJobStatus.running = true;
                        mTestJobStatus.jobId = params.getJobId();
                        mTestJobStatus.stopReason = JobParameters.REASON_CANCELED;
                        break;
                    case ACTION_JOB_STOPPED:
                        mTestJobStatus.running = false;
                        mTestJobStatus.jobId = params.getJobId();
                        mTestJobStatus.stopReason = params.getStopReason();
                        break;
                }
            }
        }
    };

    @Before
    public void setUp() throws Exception {
        mContext = InstrumentationRegistry.getTargetContext();
        mAppOpsManager = (AppOpsManager) mContext.getSystemService(Context.APP_OPS_SERVICE);
        mDeviceIdleController = IDeviceIdleController.Stub.asInterface(
                ServiceManager.getService(Context.DEVICE_IDLE_CONTROLLER));
        mIActivityManager = ActivityManager.getService();
        mTestPackageUid = mContext.getPackageManager().getPackageUid(TEST_APP_PACKAGE, 0);
        mTestJobId = (int) (SystemClock.uptimeMillis() / 1000);
        mTestJobStatus.reset();
        final IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(ACTION_JOB_STARTED);
        intentFilter.addAction(ACTION_JOB_STOPPED);
        mContext.registerReceiver(mJobStateChangeReceiver, intentFilter);
        setAppOpsModeAllowed(true);
        setPowerWhiteListed(false);
    }

    private void scheduleAndAssertJobStarted() throws Exception {
        final Intent scheduleJobIntent = new Intent(TestJobActivity.ACTION_START_JOB);
        scheduleJobIntent.putExtra(TestJobActivity.EXTRA_JOB_ID_KEY, mTestJobId);
        scheduleJobIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        scheduleJobIntent.setComponent(new ComponentName(TEST_APP_PACKAGE, TEST_APP_ACTIVITY));
        mContext.startActivity(scheduleJobIntent);
        Thread.sleep(TestJobActivity.JOB_MINIMUM_LATENCY);
        assertTrue("Job did not start after scheduling", awaitJobStart(DEFAULT_WAIT_TIMEOUT));
    }

    @Test
    public void testPowerWhiteList() throws Exception {
        scheduleAndAssertJobStarted();
        setAppOpsModeAllowed(false);
        mIActivityManager.makePackageIdle(TEST_APP_PACKAGE, UserHandle.USER_CURRENT);
        assertTrue("Job did not stop after making idle", awaitJobStop(DEFAULT_WAIT_TIMEOUT));
        setPowerWhiteListed(true);
        Thread.sleep(TestJobActivity.JOB_INITIAL_BACKOFF);
        assertTrue("Job did not start after adding to power whitelist",
                awaitJobStart(DEFAULT_WAIT_TIMEOUT));
        setPowerWhiteListed(false);
        assertTrue("Job did not stop after removing from power whitelist",
                awaitJobStop(DEFAULT_WAIT_TIMEOUT));
    }

    @Test
    public void testFeatureFlag() throws Exception {
        Settings.Global.putInt(mContext.getContentResolver(),
                Settings.Global.FORCED_APP_STANDBY_ENABLED, 0);
        scheduleAndAssertJobStarted();
        setAppOpsModeAllowed(false);
        mIActivityManager.makePackageIdle(TEST_APP_PACKAGE, UserHandle.USER_CURRENT);
        assertFalse("Job stopped even when feature flag was disabled",
                awaitJobStop(DEFAULT_WAIT_TIMEOUT));
    }

    @After
    public void tearDown() throws Exception {
        final Intent cancelJobsIntent = new Intent(TestJobActivity.ACTION_CANCEL_JOBS);
        cancelJobsIntent.setComponent(new ComponentName(TEST_APP_PACKAGE, TEST_APP_ACTIVITY));
        cancelJobsIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        mContext.startActivity(cancelJobsIntent);
        mContext.unregisterReceiver(mJobStateChangeReceiver);
        Thread.sleep(500); // To avoid race with register in the next setUp
        setAppOpsModeAllowed(true);
        setPowerWhiteListed(false);
        Settings.Global.putInt(mContext.getContentResolver(),
                Settings.Global.FORCED_APP_STANDBY_ENABLED, 1);
    }

    private void setPowerWhiteListed(boolean whitelist) throws RemoteException {
        if (whitelist) {
            mDeviceIdleController.addPowerSaveWhitelistApp(TEST_APP_PACKAGE);
        } else {
            mDeviceIdleController.removePowerSaveWhitelistApp(TEST_APP_PACKAGE);
        }
    }

    private void setAppOpsModeAllowed(boolean allow) {
        mAppOpsManager.setMode(AppOpsManager.OP_RUN_ANY_IN_BACKGROUND, mTestPackageUid,
                TEST_APP_PACKAGE, allow ? AppOpsManager.MODE_ALLOWED : AppOpsManager.MODE_IGNORED);
    }

    private boolean awaitJobStart(long timeout) throws InterruptedException {
        return waitUntilTrue(timeout, () -> {
            synchronized (mTestJobStatus) {
                return (mTestJobStatus.jobId == mTestJobId) && mTestJobStatus.running;
            }
        });
    }

    private boolean awaitJobStop(long timeout) throws InterruptedException {
        return waitUntilTrue(timeout, () -> {
            synchronized (mTestJobStatus) {
                return (mTestJobStatus.jobId == mTestJobId) && !mTestJobStatus.running &&
                        mTestJobStatus.stopReason == JobParameters.REASON_CONSTRAINTS_NOT_SATISFIED;
            }
        });
    }

    private boolean waitUntilTrue(long timeout, Condition condition) throws InterruptedException {
        final long deadLine = SystemClock.uptimeMillis() + timeout;
        do {
            Thread.sleep(POLL_INTERVAL);
        } while (!condition.isTrue() && SystemClock.uptimeMillis() < deadLine);
        return condition.isTrue();
    }

    private static final class TestJobStatus {
        int jobId;
        int stopReason;
        boolean running;
        private void reset() {
            running = false;
            stopReason = jobId = 0;
        }
    }

    private interface Condition {
        boolean isTrue();
    }
}
