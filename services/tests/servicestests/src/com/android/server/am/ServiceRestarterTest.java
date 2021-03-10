/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.server.am;

import static com.android.internal.app.procstats.ProcessStats.ADJ_MEM_FACTOR_LOW;
import static com.android.internal.app.procstats.ProcessStats.ADJ_MEM_FACTOR_MODERATE;
import static com.android.internal.app.procstats.ProcessStats.ADJ_MEM_FACTOR_NORMAL;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.ActivityManager;
import android.app.ActivityManager.OnUidImportanceListener;
import android.app.ActivityManager.RunningAppProcessInfo;
import android.app.Instrumentation;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.os.SystemClock;
import android.provider.DeviceConfig;
import android.provider.Settings;
import android.server.wm.settings.SettingsSession;
import android.test.suitebuilder.annotation.LargeTest;
import android.util.Log;

import androidx.test.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import com.android.compatibility.common.util.SystemUtil;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Build/Install/Run:
 *  atest FrameworksServicesTests:ServiceRestarterTest
 */
@RunWith(AndroidJUnit4.class)
public final class ServiceRestarterTest {
    private static final String TAG = "ServiceRestarterTest";

    private static final String TEST_PACKAGE1_NAME =
            "com.android.servicestests.apps.simpleservicetestapp1";
    private static final String TEST_PACKAGE2_NAME =
            "com.android.servicestests.apps.simpleservicetestapp2";
    private static final String TEST_PACKAGE3_NAME =
            "com.android.servicestests.apps.simpleservicetestapp3";
    private static final String TEST_SERVICE_NAME =
            "com.android.servicestests.apps.simpleservicetestapp.SimpleService";
    private static final String[] MEMORY_PRESSURE_MAP = {
            "NORMAL", "MODERATE", "LOW", "CRITICAL"};
    private static final String EXTRA_DELAY = "0,2000,4000,8000";
    private static final int SERVICE_RESTART_DURATION_FACTOR = 2;
    private static final long BOUND_SERVICE_CRASH_RESTART_DURATION = 1000;
    private static final long SERVICE_MIN_RESTART_TIME_BETWEEN = 1000;

    private static final long WAIT_MS = 5 * 1000;
    private static final long WAIT_LONG_MS = 30 * 1000;

    private static final int ACTION_START = 1;
    private static final int ACTION_KILL = 2;
    private static final int ACTION_WAIT = 4;
    private static final int ACTION_STOPPKG = 8;
    private static final int ACTION_ALL = ACTION_START | ACTION_KILL | ACTION_WAIT | ACTION_STOPPKG;

    private SettingsSession<String> mAMConstantsSettings;
    private DeviceConfigSession<String> mExtraDelaysDeviceConfig;
    private DeviceConfigSession<Boolean> mEnableExtraDelaysDeviceConfig;

    private Context mContext;
    private Instrumentation mInstrumentation;
    private int mTestPackage1Uid;
    private int mTestPackage2Uid;
    private int mTestPackage3Uid;

    @Before
    public void setUp() throws Exception {
        mInstrumentation = InstrumentationRegistry.getInstrumentation();
        mContext = InstrumentationRegistry.getContext();
        ApplicationInfo ai = mContext.getPackageManager().getApplicationInfo(TEST_PACKAGE1_NAME, 0);
        mTestPackage1Uid = ai.uid;
        ai = mContext.getPackageManager().getApplicationInfo(TEST_PACKAGE2_NAME, 0);
        mTestPackage2Uid = ai.uid;
        ai = mContext.getPackageManager().getApplicationInfo(TEST_PACKAGE3_NAME, 0);
        mTestPackage3Uid = ai.uid;
        final String activityManagerConstants = Settings.Global.ACTIVITY_MANAGER_CONSTANTS;
        mAMConstantsSettings = new SettingsSession<>(
                Settings.Global.getUriFor(activityManagerConstants),
                Settings.Global::getString, Settings.Global::putString);
        mAMConstantsSettings.set(
                ActivityManagerConstants.KEY_SERVICE_RESTART_DURATION_FACTOR + "="
                + SERVICE_RESTART_DURATION_FACTOR + ","
                + ActivityManagerConstants.KEY_BOUND_SERVICE_CRASH_RESTART_DURATION + "="
                + BOUND_SERVICE_CRASH_RESTART_DURATION + ","
                + ActivityManagerConstants.KEY_SERVICE_MIN_RESTART_TIME_BETWEEN + "="
                + SERVICE_MIN_RESTART_TIME_BETWEEN);
        mExtraDelaysDeviceConfig = new DeviceConfigSession<>(
                DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                ActivityManagerConstants.KEY_EXTRA_SERVICE_RESTART_DELAY_ON_MEM_PRESSURE,
                DeviceConfig::getString, null);
        mExtraDelaysDeviceConfig.set(EXTRA_DELAY);
        mEnableExtraDelaysDeviceConfig = new DeviceConfigSession<>(
                DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                ActivityManagerConstants.KEY_ENABLE_EXTRA_SERVICE_RESTART_DELAY_ON_MEM_PRESSURE,
                DeviceConfig::getBoolean, false);
        mEnableExtraDelaysDeviceConfig.set(true);
    }

    @After
    public void tearDown() throws Exception {
        if (mAMConstantsSettings != null) {
            mAMConstantsSettings.close();
        }
        if (mExtraDelaysDeviceConfig != null) {
            mExtraDelaysDeviceConfig.close();
        }
        if (mEnableExtraDelaysDeviceConfig != null) {
            mEnableExtraDelaysDeviceConfig.close();
        }
    }

    @LargeTest
    @Test
    public void testDisableServiceRestartBackoff() throws Exception {
        final ActivityManager am = mContext.getSystemService(ActivityManager.class);
        final MyUidImportanceListener uid1Listener1 = new MyUidImportanceListener(mTestPackage1Uid);
        final MyUidImportanceListener uid1Listener2 = new MyUidImportanceListener(mTestPackage1Uid);
        final MyUidImportanceListener uid2Listener1 = new MyUidImportanceListener(mTestPackage2Uid);
        final MyUidImportanceListener uid2Listener2 = new MyUidImportanceListener(mTestPackage2Uid);
        final MyUidImportanceListener uid3Listener1 = new MyUidImportanceListener(mTestPackage3Uid);
        final MyUidImportanceListener uid3Listener2 = new MyUidImportanceListener(mTestPackage3Uid);
        try {
            am.addOnUidImportanceListener(uid1Listener1, RunningAppProcessInfo.IMPORTANCE_SERVICE);
            am.addOnUidImportanceListener(uid1Listener2, RunningAppProcessInfo.IMPORTANCE_GONE);
            am.addOnUidImportanceListener(uid2Listener1, RunningAppProcessInfo.IMPORTANCE_SERVICE);
            am.addOnUidImportanceListener(uid2Listener2, RunningAppProcessInfo.IMPORTANCE_GONE);
            am.addOnUidImportanceListener(uid3Listener1, RunningAppProcessInfo.IMPORTANCE_SERVICE);
            am.addOnUidImportanceListener(uid3Listener2, RunningAppProcessInfo.IMPORTANCE_GONE);
            executeShellCmd("cmd deviceidle whitelist +" + TEST_PACKAGE1_NAME);
            executeShellCmd("cmd deviceidle whitelist +" + TEST_PACKAGE2_NAME);
            executeShellCmd("cmd deviceidle whitelist +" + TEST_PACKAGE3_NAME);

            // Issue the command to enable service backoff policy for app2.
            executeShellCmd("am service-restart-backoff enable " + TEST_PACKAGE2_NAME);
            // Test restarts in normal case
            final long[] ts1 = startKillAndRestart(am, ACTION_START | ACTION_KILL | ACTION_WAIT,
                    uid1Listener1, uid1Listener2, uid2Listener1, uid2Listener2,
                    uid3Listener1, uid3Listener2, Long.MAX_VALUE, false);
            assertTrue("app1 restart should be before app2", ts1[1] < ts1[2]);
            assertTrue("app2 restart should be before app3", ts1[2] < ts1[3]);

            // Issue the command to disable service backoff policy for app2.
            executeShellCmd("am service-restart-backoff disable " + TEST_PACKAGE2_NAME);
            // Test restarts again.
            final long[] ts2 = startKillAndRestart(am, ACTION_KILL | ACTION_WAIT | ACTION_STOPPKG,
                    uid1Listener1, uid1Listener2, uid2Listener1,
                    uid2Listener2, uid3Listener1, uid3Listener2, Long.MAX_VALUE, false);
            assertTrue("app2 restart should be before app1", ts2[2] < ts2[1]);
            assertTrue("app1 restart should be before app3", ts2[1] < ts2[3]);
            assertTrue("app2 should be restart in a very short moment", ts2[2] - ts2[0] < WAIT_MS);

            // Issue the command to enable service backoff policy for app2.
            executeShellCmd("am service-restart-backoff enable " + TEST_PACKAGE2_NAME);
            // Test restarts again.
            final long[] ts3 = startKillAndRestart(am, ACTION_ALL, uid1Listener1, uid1Listener2,
                    uid2Listener1, uid2Listener2, uid3Listener1, uid3Listener2, Long.MAX_VALUE,
                    false);
            assertTrue("app1 restart should be before app2", ts3[1] < ts3[2]);
            assertTrue("app2 restart should be before app3", ts3[2] < ts3[3]);

        } finally {
            executeShellCmd("cmd deviceidle whitelist -" + TEST_PACKAGE1_NAME);
            executeShellCmd("cmd deviceidle whitelist -" + TEST_PACKAGE2_NAME);
            executeShellCmd("cmd deviceidle whitelist -" + TEST_PACKAGE3_NAME);
            executeShellCmd("am service-restart-backoff enable " + TEST_PACKAGE2_NAME);
            am.removeOnUidImportanceListener(uid1Listener1);
            am.removeOnUidImportanceListener(uid1Listener2);
            am.removeOnUidImportanceListener(uid2Listener1);
            am.removeOnUidImportanceListener(uid2Listener2);
            am.removeOnUidImportanceListener(uid3Listener1);
            am.removeOnUidImportanceListener(uid3Listener2);
            am.forceStopPackage(TEST_PACKAGE1_NAME);
            am.forceStopPackage(TEST_PACKAGE2_NAME);
            am.forceStopPackage(TEST_PACKAGE3_NAME);
        }
    }

    @LargeTest
    @Test
    public void testExtraDelaysInServiceRestartOnLowMem() throws Exception {
        final ActivityManager am = mContext.getSystemService(ActivityManager.class);
        final MyUidImportanceListener uid1Listener1 = new MyUidImportanceListener(mTestPackage1Uid);
        final MyUidImportanceListener uid1Listener2 = new MyUidImportanceListener(mTestPackage1Uid);
        final MyUidImportanceListener uid2Listener1 = new MyUidImportanceListener(mTestPackage2Uid);
        final MyUidImportanceListener uid2Listener2 = new MyUidImportanceListener(mTestPackage2Uid);
        final MyUidImportanceListener uid3Listener1 = new MyUidImportanceListener(mTestPackage3Uid);
        final MyUidImportanceListener uid3Listener2 = new MyUidImportanceListener(mTestPackage3Uid);
        try {
            am.addOnUidImportanceListener(uid1Listener1, RunningAppProcessInfo.IMPORTANCE_SERVICE);
            am.addOnUidImportanceListener(uid1Listener2, RunningAppProcessInfo.IMPORTANCE_GONE);
            am.addOnUidImportanceListener(uid2Listener1, RunningAppProcessInfo.IMPORTANCE_SERVICE);
            am.addOnUidImportanceListener(uid2Listener2, RunningAppProcessInfo.IMPORTANCE_GONE);
            am.addOnUidImportanceListener(uid3Listener1, RunningAppProcessInfo.IMPORTANCE_SERVICE);
            am.addOnUidImportanceListener(uid3Listener2, RunningAppProcessInfo.IMPORTANCE_GONE);
            executeShellCmd("cmd deviceidle whitelist +" + TEST_PACKAGE1_NAME);
            executeShellCmd("cmd deviceidle whitelist +" + TEST_PACKAGE2_NAME);
            executeShellCmd("cmd deviceidle whitelist +" + TEST_PACKAGE3_NAME);

            // Force the memory pressure to normal.
            setMemoryPressure(ADJ_MEM_FACTOR_NORMAL);

            // Test restarts in normal condition.
            final long[] ts1 = startKillAndRestart(am, ACTION_ALL, uid1Listener1, uid1Listener2,
                    uid2Listener1, uid2Listener2, uid3Listener1, uid3Listener2, WAIT_LONG_MS, true);

            // Mimic a memory pressure event.
            setMemoryPressure(ADJ_MEM_FACTOR_MODERATE);

            final long[] ts2 = startKillAndRestart(am, ACTION_ALL, uid1Listener1, uid1Listener2,
                    uid2Listener1, uid2Listener2, uid3Listener1, uid3Listener2, WAIT_LONG_MS, true);

            assertTrue("Expect extra delays in service restarts",
                    (ts2[1] - ts2[0]) > (ts1[1] - ts1[0]));
            assertTrue("Expect extra delays in service restarts",
                    (ts2[2] - ts2[0]) > (ts1[2] - ts1[0]));
            assertTrue("Expect extra delays in service restarts",
                    (ts2[3] - ts2[0]) > (ts1[3] - ts1[0]));

            // Increase the memory pressure event.
            setMemoryPressure(ADJ_MEM_FACTOR_LOW);

            final long[] ts3 = startKillAndRestart(am, ACTION_ALL, uid1Listener1, uid1Listener2,
                    uid2Listener1, uid2Listener2, uid3Listener1, uid3Listener2, WAIT_LONG_MS, true);

            assertTrue("Expect extra delays in service restarts",
                    (ts3[1] - ts3[0]) > (ts2[1] - ts2[0]));
            assertTrue("Expect extra delays in service restarts",
                    (ts3[2] - ts3[0]) > (ts2[2] - ts2[0]));
            assertTrue("Expect extra delays in service restarts",
                    (ts3[3] - ts3[0]) > (ts2[3] - ts2[0]));

            // Start and kill them again, but don't wait for the restart.
            final long now4 = startKillAndRestart(am, ACTION_START | ACTION_KILL, uid1Listener1,
                    uid1Listener2, uid2Listener1, uid2Listener2, uid3Listener1, uid3Listener2,
                    WAIT_LONG_MS, true)[0];

            // Set the memory pressure to normal.
            setMemoryPressure(ADJ_MEM_FACTOR_NORMAL);

            // Now wait for the restarts.
            final long[] ts4 = startKillAndRestart(am, ACTION_WAIT | ACTION_STOPPKG, uid1Listener1,
                    uid1Listener2, uid2Listener1, uid2Listener2, uid3Listener1, uid3Listener2,
                    WAIT_LONG_MS, true);

            assertTrue("Expect less delays in service restarts",
                    (ts4[1] - now4) < (ts2[1] - ts2[0]));
            assertTrue("Expect less delays in service restarts",
                    (ts4[2] - now4) < (ts2[2] - ts2[0]));
            assertTrue("Expect less delays in service restarts",
                    (ts4[3] - now4) < (ts2[3] - ts2[0]));
        } finally {
            executeShellCmd("cmd deviceidle whitelist -" + TEST_PACKAGE1_NAME);
            executeShellCmd("cmd deviceidle whitelist -" + TEST_PACKAGE2_NAME);
            executeShellCmd("cmd deviceidle whitelist -" + TEST_PACKAGE3_NAME);
            resetMemoryPressure();
            am.removeOnUidImportanceListener(uid1Listener1);
            am.removeOnUidImportanceListener(uid1Listener2);
            am.removeOnUidImportanceListener(uid2Listener1);
            am.removeOnUidImportanceListener(uid2Listener2);
            am.removeOnUidImportanceListener(uid3Listener1);
            am.removeOnUidImportanceListener(uid3Listener2);
            am.forceStopPackage(TEST_PACKAGE1_NAME);
            am.forceStopPackage(TEST_PACKAGE2_NAME);
            am.forceStopPackage(TEST_PACKAGE3_NAME);
        }
    }

    private long[] startKillAndRestart(ActivityManager am, int action,
            MyUidImportanceListener uid1Listener1, MyUidImportanceListener uid1Listener2,
            MyUidImportanceListener uid2Listener1, MyUidImportanceListener uid2Listener2,
            MyUidImportanceListener uid3Listener1, MyUidImportanceListener uid3Listener2,
            long waitDuration, boolean checkStartSeq) throws Exception {
        final long[] res = new long[4];
        // Test restarts in normal condition.
        if ((action & ACTION_START) != 0) {
            startServiceAndWait(TEST_PACKAGE1_NAME, uid1Listener1, WAIT_MS);
            startServiceAndWait(TEST_PACKAGE2_NAME, uid2Listener1, WAIT_MS);
            startServiceAndWait(TEST_PACKAGE3_NAME, uid3Listener1, WAIT_MS);
        }

        if ((action & ACTION_KILL) != 0) {
            final long now = res[0] = SystemClock.uptimeMillis();
            killUidAndWait(am, mTestPackage1Uid, uid1Listener2, WAIT_MS);
            killUidAndWait(am, mTestPackage2Uid, uid2Listener2, WAIT_MS);
            killUidAndWait(am, mTestPackage3Uid, uid3Listener2, WAIT_MS);
        }

        if ((action & ACTION_WAIT) != 0) {
            assertTrue("Timed out to restart " + TEST_PACKAGE1_NAME, uid1Listener1.waitFor(
                    RunningAppProcessInfo.IMPORTANCE_SERVICE, waitDuration));
            assertTrue("Timed out to restart " + TEST_PACKAGE2_NAME, uid2Listener1.waitFor(
                    RunningAppProcessInfo.IMPORTANCE_SERVICE, waitDuration));
            assertTrue("Timed out to restart " + TEST_PACKAGE3_NAME, uid3Listener1.waitFor(
                    RunningAppProcessInfo.IMPORTANCE_SERVICE, waitDuration));
            final long uid1RestartTime = res[1] = uid1Listener1.mCurrentTimestamp;
            final long uid2RestartTime = res[2] = uid2Listener1.mCurrentTimestamp;
            final long uid3RestartTime = res[3] = uid3Listener1.mCurrentTimestamp;
            if (checkStartSeq) {
                assertTrue(TEST_PACKAGE1_NAME + " should restart before " + TEST_PACKAGE2_NAME,
                        uid1RestartTime <= uid2RestartTime);
                assertTrue(TEST_PACKAGE2_NAME + " should restart before " + TEST_PACKAGE3_NAME,
                        uid2RestartTime <= uid3RestartTime);
            }
        }

        if ((action & ACTION_STOPPKG) != 0) {
            // Force stop these packages to reset the backoff delays.
            am.forceStopPackage(TEST_PACKAGE1_NAME);
            am.forceStopPackage(TEST_PACKAGE2_NAME);
            am.forceStopPackage(TEST_PACKAGE3_NAME);
            assertTrue("Timed out to force-stop " + mTestPackage1Uid,
                    uid1Listener2.waitFor(RunningAppProcessInfo.IMPORTANCE_GONE, WAIT_MS));
            assertTrue("Timed out to force-stop " + mTestPackage2Uid,
                    uid2Listener2.waitFor(RunningAppProcessInfo.IMPORTANCE_GONE, WAIT_MS));
            assertTrue("Timed out to force-stop " + mTestPackage3Uid,
                    uid3Listener2.waitFor(RunningAppProcessInfo.IMPORTANCE_GONE, WAIT_MS));
        }
        return res;
    }

    private void startServiceAndWait(String pkgName, MyUidImportanceListener uidListener,
            long timeout) throws Exception {
        final Intent intent = new Intent();
        final ComponentName cn = ComponentName.unflattenFromString(
                pkgName + "/" + TEST_SERVICE_NAME);
        intent.setComponent(cn);
        Log.i(TAG, "Starting service " + cn);
        assertNotNull(mContext.startService(intent));
        assertTrue("Timed out to start service " + cn,
                uidListener.waitFor(RunningAppProcessInfo.IMPORTANCE_SERVICE, timeout));
    }

    private void killUidAndWait(ActivityManager am, int uid, MyUidImportanceListener uidListener,
            long timeout) throws Exception {
        am.killUid(uid, "test service restart");
        assertTrue("Timed out to kill " + uid,
                uidListener.waitFor(RunningAppProcessInfo.IMPORTANCE_GONE, timeout));
    }

    private String executeShellCmd(String cmd) throws Exception {
        final String result = SystemUtil.runShellCommand(mInstrumentation, cmd);
        Log.d(TAG, String.format("Output for '%s': %s", cmd, result));
        return result;
    }

    private void setMemoryPressure(int pressure) throws Exception {
        executeShellCmd("am memory-factor set " + MEMORY_PRESSURE_MAP[pressure]);
    }

    private void resetMemoryPressure() throws Exception {
        executeShellCmd("am memory-factor reset");
    }

    private static class MyUidImportanceListener implements OnUidImportanceListener {
        final CountDownLatch[] mLatchHolder = new CountDownLatch[1];
        private final int mExpectedUid;
        private int mExpectedImportance;
        private int mCurrentImportance = RunningAppProcessInfo.IMPORTANCE_GONE;
        long mCurrentTimestamp;

        MyUidImportanceListener(int uid) {
            mExpectedUid = uid;
        }

        @Override
        public void onUidImportance(int uid, int importance) {
            if (uid == mExpectedUid) {
                mCurrentTimestamp = SystemClock.uptimeMillis();
                synchronized (this) {
                    if (importance == mExpectedImportance && mLatchHolder[0] != null) {
                        mLatchHolder[0].countDown();
                    }
                    mCurrentImportance = importance;
                }
                Log.i(TAG, "uid " + uid + " importance: " + importance);
            }
        }

        boolean waitFor(int expectedImportance, long timeout) throws Exception {
            synchronized (this) {
                mExpectedImportance = expectedImportance;
                if (mCurrentImportance == expectedImportance) {
                    return true;
                }
                mLatchHolder[0] = new CountDownLatch(1);
            }
            return mLatchHolder[0].await(timeout, TimeUnit.MILLISECONDS);
        }
    }
}
