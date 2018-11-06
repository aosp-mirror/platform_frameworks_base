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
 * limitations under the License.
 */

package com.android.server.usage;

import static android.app.usage.UsageEvents.Event.NOTIFICATION_SEEN;
import static android.app.usage.UsageEvents.Event.SLICE_PINNED;
import static android.app.usage.UsageEvents.Event.SLICE_PINNED_PRIV;
import static android.app.usage.UsageEvents.Event.SYSTEM_INTERACTION;
import static android.app.usage.UsageEvents.Event.USER_INTERACTION;
import static android.app.usage.UsageStatsManager.REASON_MAIN_DEFAULT;
import static android.app.usage.UsageStatsManager.REASON_MAIN_FORCED;
import static android.app.usage.UsageStatsManager.REASON_MAIN_PREDICTED;
import static android.app.usage.UsageStatsManager.REASON_MAIN_TIMEOUT;
import static android.app.usage.UsageStatsManager.REASON_MAIN_USAGE;
import static android.app.usage.UsageStatsManager.STANDBY_BUCKET_ACTIVE;
import static android.app.usage.UsageStatsManager.STANDBY_BUCKET_EXEMPTED;
import static android.app.usage.UsageStatsManager.STANDBY_BUCKET_FREQUENT;
import static android.app.usage.UsageStatsManager.STANDBY_BUCKET_NEVER;
import static android.app.usage.UsageStatsManager.STANDBY_BUCKET_RARE;
import static android.app.usage.UsageStatsManager.STANDBY_BUCKET_WORKING_SET;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Matchers.anyInt;
import static org.mockito.Matchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManagerInternal;
import android.appwidget.AppWidgetManager;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.Looper;
import android.os.RemoteException;
import android.platform.test.annotations.Presubmit;
import android.support.test.filters.SmallTest;
import android.support.test.InstrumentationRegistry;
import android.support.test.runner.AndroidJUnit4;
import android.util.ArraySet;
import android.view.Display;

import com.android.server.SystemService;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Unit test for AppStandbyController.
 */
@RunWith(AndroidJUnit4.class)
@Presubmit
@SmallTest
public class AppStandbyControllerTests {

    private static final String PACKAGE_1 = "com.example.foo";
    private static final int UID_1 = 10000;
    private static final String PACKAGE_EXEMPTED_1 = "com.android.exempted";
    private static final int UID_EXEMPTED_1 = 10001;
    private static final int USER_ID = 0;
    private static final int USER_ID2 = 10;

    private static final String ADMIN_PKG = "com.android.admin";
    private static final String ADMIN_PKG2 = "com.android.admin2";
    private static final String ADMIN_PKG3 = "com.android.admin3";

    private static final long MINUTE_MS = 60 * 1000;
    private static final long HOUR_MS = 60 * MINUTE_MS;
    private static final long DAY_MS = 24 * HOUR_MS;

    private static final long WORKING_SET_THRESHOLD = 12 * HOUR_MS;
    private static final long FREQUENT_THRESHOLD = 24 * HOUR_MS;
    private static final long RARE_THRESHOLD = 48 * HOUR_MS;
    // Short STABLE_CHARGING_THRESHOLD for testing purposes
    private static final long STABLE_CHARGING_THRESHOLD = 2000;

    private MyInjector mInjector;
    private AppStandbyController mController;

    static class MyContextWrapper extends ContextWrapper {
        PackageManager mockPm = mock(PackageManager.class);

        public MyContextWrapper(Context base) {
            super(base);
        }

        public PackageManager getPackageManager() {
            return mockPm;
        }
    }

    static class MyInjector extends AppStandbyController.Injector {
        long mElapsedRealtime;
        boolean mIsAppIdleEnabled = true;
        boolean mIsCharging;
        List<String> mPowerSaveWhitelistExceptIdle = new ArrayList<>();
        boolean mDisplayOn;
        DisplayManager.DisplayListener mDisplayListener;
        String mBoundWidgetPackage = PACKAGE_EXEMPTED_1;

        MyInjector(Context context, Looper looper) {
            super(context, looper);
        }

        @Override
        void onBootPhase(int phase) {
        }

        @Override
        int getBootPhase() {
            return SystemService.PHASE_BOOT_COMPLETED;
        }

        @Override
        long elapsedRealtime() {
            return mElapsedRealtime;
        }

        @Override
        long currentTimeMillis() {
            return mElapsedRealtime;
        }

        @Override
        boolean isAppIdleEnabled() {
            return mIsAppIdleEnabled;
        }

        @Override
        boolean isCharging() {
            return mIsCharging;
        }

        @Override
        boolean isPowerSaveWhitelistExceptIdleApp(String packageName) throws RemoteException {
            return mPowerSaveWhitelistExceptIdle.contains(packageName);
        }

        @Override
        File getDataSystemDirectory() {
            return new File(getContext().getFilesDir(), Long.toString(Math.randomLongInternal()));
        }

        @Override
        void noteEvent(int event, String packageName, int uid) throws RemoteException {
        }

        @Override
        boolean isPackageEphemeral(int userId, String packageName) {
            // TODO: update when testing ephemeral apps scenario
            return false;
        }

        @Override
        int[] getRunningUserIds() {
            return new int[] {USER_ID};
        }

        @Override
        boolean isDefaultDisplayOn() {
            return mDisplayOn;
        }

        @Override
        void registerDisplayListener(DisplayManager.DisplayListener listener, Handler handler) {
            mDisplayListener = listener;
        }

        @Override
        String getActiveNetworkScorer() {
            return null;
        }

        @Override
        public boolean isBoundWidgetPackage(AppWidgetManager appWidgetManager, String packageName,
                int userId) {
            return packageName != null && packageName.equals(mBoundWidgetPackage);
        }

        @Override
        String getAppIdleSettings() {
            return "screen_thresholds=0/0/0/" + HOUR_MS + ",elapsed_thresholds=0/"
                    + WORKING_SET_THRESHOLD + "/"
                    + FREQUENT_THRESHOLD + "/"
                    + RARE_THRESHOLD + ","
                    + "stable_charging_threshold=" + STABLE_CHARGING_THRESHOLD;
        }

        @Override
        public boolean isDeviceIdleMode() {
            return false;
        }

        // Internal methods

        void setDisplayOn(boolean on) {
            mDisplayOn = on;
            if (mDisplayListener != null) {
                mDisplayListener.onDisplayChanged(Display.DEFAULT_DISPLAY);
            }
        }
    }

    private void setupPm(PackageManager mockPm) throws PackageManager.NameNotFoundException {
        List<PackageInfo> packages = new ArrayList<>();
        PackageInfo pi = new PackageInfo();
        pi.applicationInfo = new ApplicationInfo();
        pi.applicationInfo.uid = UID_1;
        pi.packageName = PACKAGE_1;
        packages.add(pi);

        PackageInfo pie = new PackageInfo();
        pie.applicationInfo = new ApplicationInfo();
        pie.applicationInfo.uid = UID_EXEMPTED_1;
        pie.packageName = PACKAGE_EXEMPTED_1;
        packages.add(pie);

        doReturn(packages).when(mockPm).getInstalledPackagesAsUser(anyInt(), anyInt());
        try {
            doReturn(UID_1).when(mockPm).getPackageUidAsUser(eq(PACKAGE_1), anyInt(), anyInt());
            doReturn(UID_EXEMPTED_1).when(mockPm).getPackageUidAsUser(eq(PACKAGE_EXEMPTED_1),
                    anyInt(), anyInt());
            doReturn(pi.applicationInfo).when(mockPm).getApplicationInfo(eq(pi.packageName),
                    anyInt());
            doReturn(pie.applicationInfo).when(mockPm).getApplicationInfo(eq(pie.packageName),
                    anyInt());
        } catch (PackageManager.NameNotFoundException nnfe) {}
    }

    private void setChargingState(AppStandbyController controller, boolean charging) {
        mInjector.mIsCharging = charging;
        if (controller != null) {
            controller.setChargingState(charging);
        }
    }

    private void setAppIdleEnabled(AppStandbyController controller, boolean enabled) {
        mInjector.mIsAppIdleEnabled = enabled;
        if (controller != null) {
            controller.setAppIdleEnabled(enabled);
        }
    }

    private AppStandbyController setupController() throws Exception {
        mInjector.mElapsedRealtime = 0;
        setupPm(mInjector.getContext().getPackageManager());
        AppStandbyController controller = new AppStandbyController(mInjector);
        controller.initializeDefaultsForSystemApps(USER_ID);
        controller.onBootPhase(SystemService.PHASE_SYSTEM_SERVICES_READY);
        controller.onBootPhase(SystemService.PHASE_BOOT_COMPLETED);
        mInjector.setDisplayOn(false);
        mInjector.setDisplayOn(true);
        setChargingState(controller, false);
        controller.checkIdleStates(USER_ID);
        assertEquals(STANDBY_BUCKET_EXEMPTED,
                controller.getAppStandbyBucket(PACKAGE_EXEMPTED_1, USER_ID,
                        mInjector.mElapsedRealtime, false));
        assertNotEquals(STANDBY_BUCKET_EXEMPTED,
                controller.getAppStandbyBucket(PACKAGE_1, USER_ID,
                        mInjector.mElapsedRealtime, false));

        return controller;
    }

    private long getCurrentTime() {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime());
    }

    @Before
    public void setUp() throws Exception {
        MyContextWrapper myContext = new MyContextWrapper(InstrumentationRegistry.getContext());
        mInjector = new MyInjector(myContext, Looper.getMainLooper());
        mController = setupController();
        setChargingState(mController, false);
    }

    private class TestParoleListener extends UsageStatsManagerInternal.AppIdleStateChangeListener {
        private boolean mOnParole = false;
        private CountDownLatch mLatch;
        private long mLastParoleChangeTime;

        public boolean getParoleState() {
            synchronized (this) {
                return mOnParole;
            }
        }

        public void rearmLatch() {
            synchronized (this) {
                mLatch = new CountDownLatch(1);
            }
        }

        public void awaitOnLatch(long time) throws Exception {
            mLatch.await(time, TimeUnit.MILLISECONDS);
        }

        public long getLastParoleChangeTime() {
            synchronized (this) {
                return mLastParoleChangeTime;
            }
        }

        @Override
        public void onAppIdleStateChanged(String packageName, int userId, boolean idle,
                int bucket, int reason) {
        }

        @Override
        public void onParoleStateChanged(boolean isParoleOn) {
            synchronized (this) {
                // Only record information if it is being looked for
                if (mLatch != null && mLatch.getCount() > 0) {
                    mOnParole = isParoleOn;
                    mLastParoleChangeTime = getCurrentTime();
                    mLatch.countDown();
                }
            }
        }
    }

    @Test
    public void testCharging() throws Exception {
        long startTime;
        TestParoleListener paroleListener = new TestParoleListener();
        long marginOfError = 200;

        // Charging
        paroleListener.rearmLatch();
        mController.addListener(paroleListener);
        startTime = getCurrentTime();
        setChargingState(mController, true);
        paroleListener.awaitOnLatch(STABLE_CHARGING_THRESHOLD * 3 / 2);
        assertTrue(paroleListener.mOnParole);
        // Parole will only be granted after device has been charging for a sufficient amount of
        // time.
        assertEquals(STABLE_CHARGING_THRESHOLD,
                paroleListener.getLastParoleChangeTime() - startTime,
                marginOfError);

        // Discharging
        paroleListener.rearmLatch();
        startTime = getCurrentTime();
        setChargingState(mController, false);
        mController.checkIdleStates(USER_ID);
        paroleListener.awaitOnLatch(STABLE_CHARGING_THRESHOLD * 3 / 2);
        assertFalse(paroleListener.getParoleState());
        // Parole should be revoked immediately
        assertEquals(0,
                paroleListener.getLastParoleChangeTime() - startTime,
                marginOfError);

        // Brief Charging
        paroleListener.rearmLatch();
        setChargingState(mController, true);
        setChargingState(mController, false);
        // Device stopped charging before the stable charging threshold.
        // Parole should not be granted at the end of the threshold
        paroleListener.awaitOnLatch(STABLE_CHARGING_THRESHOLD * 3 / 2);
        assertFalse(paroleListener.getParoleState());

        // Charging Again
        paroleListener.rearmLatch();
        startTime = getCurrentTime();
        setChargingState(mController, true);
        paroleListener.awaitOnLatch(STABLE_CHARGING_THRESHOLD * 3 / 2);
        assertTrue(paroleListener.getParoleState());
        assertTrue(paroleListener.mOnParole);
        assertEquals(STABLE_CHARGING_THRESHOLD,
                paroleListener.getLastParoleChangeTime() - startTime,
                marginOfError);
    }

    @Test
    public void testEnabledState() throws Exception {
        TestParoleListener paroleListener = new TestParoleListener();
        mController.addListener(paroleListener);
        long lastUpdateTime;

        // Test that listeners are notified if enabled changes when the device is not in parole.
        setChargingState(mController, false);

        // Start off not enabled. Device is effectively on permanent parole.
        setAppIdleEnabled(mController, false);

        // Enable controller
        paroleListener.rearmLatch();
        setAppIdleEnabled(mController, true);
        paroleListener.awaitOnLatch(STABLE_CHARGING_THRESHOLD * 3 / 2);
        assertFalse(paroleListener.mOnParole);
        lastUpdateTime = paroleListener.getLastParoleChangeTime();

        paroleListener.rearmLatch();
        setAppIdleEnabled(mController, true);
        paroleListener.awaitOnLatch(STABLE_CHARGING_THRESHOLD * 3 / 2);
        assertFalse(paroleListener.mOnParole);
        // Make sure AppStandbyController doesn't notify listeners when there's no change.
        assertEquals(lastUpdateTime, paroleListener.getLastParoleChangeTime());

        // Disable controller
        paroleListener.rearmLatch();
        setAppIdleEnabled(mController, false);
        paroleListener.awaitOnLatch(STABLE_CHARGING_THRESHOLD * 3 / 2);
        assertTrue(paroleListener.mOnParole);
        lastUpdateTime = paroleListener.getLastParoleChangeTime();

        paroleListener.rearmLatch();
        setAppIdleEnabled(mController, false);
        paroleListener.awaitOnLatch(STABLE_CHARGING_THRESHOLD * 3 / 2);
        assertTrue(paroleListener.mOnParole);
        // Make sure AppStandbyController doesn't notify listeners when there's no change.
        assertEquals(lastUpdateTime, paroleListener.getLastParoleChangeTime());


        // Test that listeners aren't notified if enabled status changes when the device is already
        // in parole.

        // A device is in parole whenever it's charging.
        setChargingState(mController, true);

        // Start off not enabled.
        paroleListener.rearmLatch();
        setAppIdleEnabled(mController, false);
        paroleListener.awaitOnLatch(STABLE_CHARGING_THRESHOLD * 3 / 2);
        assertTrue(paroleListener.mOnParole);
        lastUpdateTime = paroleListener.getLastParoleChangeTime();

        // Test that toggling doesn't notify the listener.
        paroleListener.rearmLatch();
        setAppIdleEnabled(mController, true);
        paroleListener.awaitOnLatch(STABLE_CHARGING_THRESHOLD * 3 / 2);
        assertTrue(paroleListener.mOnParole);
        assertEquals(lastUpdateTime, paroleListener.getLastParoleChangeTime());

        paroleListener.rearmLatch();
        setAppIdleEnabled(mController, false);
        paroleListener.awaitOnLatch(STABLE_CHARGING_THRESHOLD * 3 / 2);
        assertTrue(paroleListener.mOnParole);
        assertEquals(lastUpdateTime, paroleListener.getLastParoleChangeTime());
    }

    private void assertTimeout(AppStandbyController controller, long elapsedTime, int bucket) {
        mInjector.mElapsedRealtime = elapsedTime;
        controller.checkIdleStates(USER_ID);
        assertEquals(bucket,
                controller.getAppStandbyBucket(PACKAGE_1, USER_ID, mInjector.mElapsedRealtime,
                        false));
    }

    private void reportEvent(AppStandbyController controller, int eventType,
            long elapsedTime) {
        // Back to ACTIVE on event
        mInjector.mElapsedRealtime = elapsedTime;
        UsageEvents.Event ev = new UsageEvents.Event();
        ev.mPackage = PACKAGE_1;
        ev.mEventType = eventType;
        controller.reportEvent(ev, elapsedTime, USER_ID);
    }

    private int getStandbyBucket(AppStandbyController controller) {
        return controller.getAppStandbyBucket(PACKAGE_1, USER_ID, mInjector.mElapsedRealtime,
                true);
    }

    private void assertBucket(int bucket) {
        assertEquals(bucket, getStandbyBucket(mController));
    }

    @Test
    public void testBuckets() throws Exception {
        assertTimeout(mController, 0, STANDBY_BUCKET_NEVER);

        reportEvent(mController, USER_INTERACTION, 0);

        // ACTIVE bucket
        assertTimeout(mController, WORKING_SET_THRESHOLD - 1, STANDBY_BUCKET_ACTIVE);

        // WORKING_SET bucket
        assertTimeout(mController, WORKING_SET_THRESHOLD + 1, STANDBY_BUCKET_WORKING_SET);

        // WORKING_SET bucket
        assertTimeout(mController, FREQUENT_THRESHOLD - 1, STANDBY_BUCKET_WORKING_SET);

        // FREQUENT bucket
        assertTimeout(mController, FREQUENT_THRESHOLD + 1, STANDBY_BUCKET_FREQUENT);

        // RARE bucket
        assertTimeout(mController, RARE_THRESHOLD + 1, STANDBY_BUCKET_RARE);

        reportEvent(mController, USER_INTERACTION, RARE_THRESHOLD + 1);

        assertTimeout(mController, RARE_THRESHOLD + 1, STANDBY_BUCKET_ACTIVE);

        // RARE bucket
        assertTimeout(mController, RARE_THRESHOLD * 2 + 2, STANDBY_BUCKET_RARE);
    }

    @Test
    public void testScreenTimeAndBuckets() throws Exception {
        mInjector.setDisplayOn(false);

        assertTimeout(mController, 0, STANDBY_BUCKET_NEVER);

        reportEvent(mController, USER_INTERACTION, 0);

        // ACTIVE bucket
        assertTimeout(mController, WORKING_SET_THRESHOLD - 1, STANDBY_BUCKET_ACTIVE);

        // WORKING_SET bucket
        assertTimeout(mController, WORKING_SET_THRESHOLD + 1, STANDBY_BUCKET_WORKING_SET);

        // RARE bucket, should fail because the screen wasn't ON.
        mInjector.mElapsedRealtime = RARE_THRESHOLD + 1;
        mController.checkIdleStates(USER_ID);
        assertNotEquals(STANDBY_BUCKET_RARE, getStandbyBucket(mController));

        mInjector.setDisplayOn(true);
        assertTimeout(mController, RARE_THRESHOLD * 2 + 2, STANDBY_BUCKET_RARE);
    }

    @Test
    public void testForcedIdle() throws Exception {
        mController.forceIdleState(PACKAGE_1, USER_ID, true);
        assertEquals(STANDBY_BUCKET_RARE, getStandbyBucket(mController));
        assertTrue(mController.isAppIdleFiltered(PACKAGE_1, UID_1, USER_ID, 0));

        mController.forceIdleState(PACKAGE_1, USER_ID, false);
        assertEquals(STANDBY_BUCKET_ACTIVE, mController.getAppStandbyBucket(PACKAGE_1, USER_ID, 0,
                true));
        assertFalse(mController.isAppIdleFiltered(PACKAGE_1, UID_1, USER_ID, 0));
    }

    @Test
    public void testNotificationEvent() throws Exception {
        reportEvent(mController, USER_INTERACTION, 0);
        assertEquals(STANDBY_BUCKET_ACTIVE, getStandbyBucket(mController));
        mInjector.mElapsedRealtime = 1;
        reportEvent(mController, NOTIFICATION_SEEN, mInjector.mElapsedRealtime);
        assertEquals(STANDBY_BUCKET_ACTIVE, getStandbyBucket(mController));

        mController.forceIdleState(PACKAGE_1, USER_ID, true);
        reportEvent(mController, NOTIFICATION_SEEN, mInjector.mElapsedRealtime);
        assertEquals(STANDBY_BUCKET_WORKING_SET, getStandbyBucket(mController));
    }

    @Test
    public void testSlicePinnedEvent() throws Exception {
        reportEvent(mController, USER_INTERACTION, 0);
        assertEquals(STANDBY_BUCKET_ACTIVE, getStandbyBucket(mController));
        mInjector.mElapsedRealtime = 1;
        reportEvent(mController, SLICE_PINNED, mInjector.mElapsedRealtime);
        assertEquals(STANDBY_BUCKET_ACTIVE, getStandbyBucket(mController));

        mController.forceIdleState(PACKAGE_1, USER_ID, true);
        reportEvent(mController, SLICE_PINNED, mInjector.mElapsedRealtime);
        assertEquals(STANDBY_BUCKET_WORKING_SET, getStandbyBucket(mController));
    }

    @Test
    public void testSlicePinnedPrivEvent() throws Exception {
        mController.forceIdleState(PACKAGE_1, USER_ID, true);
        reportEvent(mController, SLICE_PINNED_PRIV, mInjector.mElapsedRealtime);
        assertEquals(STANDBY_BUCKET_ACTIVE, getStandbyBucket(mController));
    }

    @Test
    public void testPredictionTimedout() throws Exception {
        // Set it to timeout or usage, so that prediction can override it
        mController.setAppStandbyBucket(PACKAGE_1, USER_ID, STANDBY_BUCKET_RARE,
                REASON_MAIN_TIMEOUT, HOUR_MS);
        assertEquals(STANDBY_BUCKET_RARE, getStandbyBucket(mController));

        mController.setAppStandbyBucket(PACKAGE_1, USER_ID, STANDBY_BUCKET_ACTIVE,
                REASON_MAIN_PREDICTED, HOUR_MS);
        assertEquals(STANDBY_BUCKET_ACTIVE, getStandbyBucket(mController));

        // Fast forward 12 hours
        mInjector.mElapsedRealtime += WORKING_SET_THRESHOLD;
        mController.checkIdleStates(USER_ID);
        // Should still be in predicted bucket, since prediction timeout is 1 day since prediction
        assertEquals(STANDBY_BUCKET_ACTIVE, getStandbyBucket(mController));
        // Fast forward two more hours
        mInjector.mElapsedRealtime += 2 * HOUR_MS;
        mController.checkIdleStates(USER_ID);
        // Should have now applied prediction timeout
        assertEquals(STANDBY_BUCKET_WORKING_SET, getStandbyBucket(mController));

        // Fast forward RARE bucket
        mInjector.mElapsedRealtime += RARE_THRESHOLD;
        mController.checkIdleStates(USER_ID);
        // Should continue to apply prediction timeout
        assertEquals(STANDBY_BUCKET_RARE, getStandbyBucket(mController));
    }

    @Test
    public void testOverrides() throws Exception {
        // Can force to NEVER
        mController.setAppStandbyBucket(PACKAGE_1, USER_ID, STANDBY_BUCKET_NEVER,
                REASON_MAIN_FORCED, 1 * HOUR_MS);
        assertEquals(STANDBY_BUCKET_NEVER, getStandbyBucket(mController));

        // Prediction can't override FORCED reason
        mController.setAppStandbyBucket(PACKAGE_1, USER_ID, STANDBY_BUCKET_FREQUENT,
                REASON_MAIN_FORCED, 1 * HOUR_MS);
        mController.setAppStandbyBucket(PACKAGE_1, USER_ID, STANDBY_BUCKET_WORKING_SET,
                REASON_MAIN_PREDICTED, 1 * HOUR_MS);
        assertEquals(STANDBY_BUCKET_FREQUENT, getStandbyBucket(mController));

        // Prediction can't override NEVER
        mController.setAppStandbyBucket(PACKAGE_1, USER_ID, STANDBY_BUCKET_NEVER,
                REASON_MAIN_DEFAULT, 2 * HOUR_MS);
        mController.setAppStandbyBucket(PACKAGE_1, USER_ID, STANDBY_BUCKET_ACTIVE,
                REASON_MAIN_PREDICTED, 2 * HOUR_MS);
        assertEquals(STANDBY_BUCKET_NEVER, getStandbyBucket(mController));

        // Prediction can't set to NEVER
        mController.setAppStandbyBucket(PACKAGE_1, USER_ID, STANDBY_BUCKET_ACTIVE,
                REASON_MAIN_USAGE, 2 * HOUR_MS);
        mController.setAppStandbyBucket(PACKAGE_1, USER_ID, STANDBY_BUCKET_NEVER,
                REASON_MAIN_PREDICTED, 2 * HOUR_MS);
        assertEquals(STANDBY_BUCKET_ACTIVE, getStandbyBucket(mController));
    }

    @Test
    public void testTimeout() throws Exception {
        reportEvent(mController, USER_INTERACTION, 0);
        assertBucket(STANDBY_BUCKET_ACTIVE);

        mInjector.mElapsedRealtime = 2000;
        mController.setAppStandbyBucket(PACKAGE_1, USER_ID, STANDBY_BUCKET_FREQUENT,
                REASON_MAIN_PREDICTED, mInjector.mElapsedRealtime);
        assertBucket(STANDBY_BUCKET_ACTIVE);

        // bucketing works after timeout
        mInjector.mElapsedRealtime = mController.mPredictionTimeoutMillis - 100;
        mController.checkIdleStates(USER_ID);
        // Use recent prediction
        assertBucket(STANDBY_BUCKET_FREQUENT);

        // Way past prediction timeout, use system thresholds
        mInjector.mElapsedRealtime = RARE_THRESHOLD * 4;
        mController.checkIdleStates(USER_ID);
        assertBucket(STANDBY_BUCKET_RARE);
    }

    @Test
    public void testCascadingTimeouts() throws Exception {
        reportEvent(mController, USER_INTERACTION, 0);
        assertBucket(STANDBY_BUCKET_ACTIVE);

        reportEvent(mController, NOTIFICATION_SEEN, 1000);
        assertBucket(STANDBY_BUCKET_ACTIVE);

        mController.setAppStandbyBucket(PACKAGE_1, USER_ID, STANDBY_BUCKET_WORKING_SET,
                REASON_MAIN_PREDICTED, 1000);
        assertBucket(STANDBY_BUCKET_ACTIVE);

        mController.setAppStandbyBucket(PACKAGE_1, USER_ID, STANDBY_BUCKET_FREQUENT,
                REASON_MAIN_PREDICTED, 2000 + mController.mStrongUsageTimeoutMillis);
        assertBucket(STANDBY_BUCKET_WORKING_SET);

        mController.setAppStandbyBucket(PACKAGE_1, USER_ID, STANDBY_BUCKET_FREQUENT,
                REASON_MAIN_PREDICTED, 2000 + mController.mNotificationSeenTimeoutMillis);
        assertBucket(STANDBY_BUCKET_FREQUENT);
    }

    @Test
    public void testOverlappingTimeouts() throws Exception {
        reportEvent(mController, USER_INTERACTION, 0);
        assertBucket(STANDBY_BUCKET_ACTIVE);

        reportEvent(mController, NOTIFICATION_SEEN, 1000);
        assertBucket(STANDBY_BUCKET_ACTIVE);

        // Overlapping USER_INTERACTION before previous one times out
        reportEvent(mController, USER_INTERACTION, mController.mStrongUsageTimeoutMillis - 1000);
        assertBucket(STANDBY_BUCKET_ACTIVE);

        // Still in ACTIVE after first USER_INTERACTION times out
        mInjector.mElapsedRealtime = mController.mStrongUsageTimeoutMillis + 1000;
        mController.setAppStandbyBucket(PACKAGE_1, USER_ID, STANDBY_BUCKET_FREQUENT,
                REASON_MAIN_PREDICTED, mInjector.mElapsedRealtime);
        assertBucket(STANDBY_BUCKET_ACTIVE);

        // Both timed out, so NOTIFICATION_SEEN timeout should be effective
        mInjector.mElapsedRealtime = mController.mStrongUsageTimeoutMillis * 2 + 2000;
        mController.setAppStandbyBucket(PACKAGE_1, USER_ID, STANDBY_BUCKET_FREQUENT,
                REASON_MAIN_PREDICTED, mInjector.mElapsedRealtime);
        assertBucket(STANDBY_BUCKET_WORKING_SET);

        mInjector.mElapsedRealtime = mController.mNotificationSeenTimeoutMillis + 2000;
        mController.setAppStandbyBucket(PACKAGE_1, USER_ID, STANDBY_BUCKET_RARE,
                REASON_MAIN_PREDICTED, mInjector.mElapsedRealtime);
        assertBucket(STANDBY_BUCKET_RARE);
    }

    @Test
    public void testSystemInteractionTimeout() throws Exception {
        setChargingState(mController, false);

        reportEvent(mController, USER_INTERACTION, 0);
        // Fast forward to RARE
        mInjector.mElapsedRealtime = RARE_THRESHOLD + 100;
        mController.checkIdleStates(USER_ID);
        assertBucket(STANDBY_BUCKET_RARE);

        // Trigger a SYSTEM_INTERACTION and verify bucket
        reportEvent(mController, SYSTEM_INTERACTION, mInjector.mElapsedRealtime);
        assertBucket(STANDBY_BUCKET_ACTIVE);

        // Verify it's still in ACTIVE close to end of timeout
        mInjector.mElapsedRealtime += mController.mSystemInteractionTimeoutMillis - 100;
        mController.checkIdleStates(USER_ID);
        assertBucket(STANDBY_BUCKET_ACTIVE);

        // Verify bucket moves to RARE after timeout
        mInjector.mElapsedRealtime += 200;
        mController.checkIdleStates(USER_ID);
        assertBucket(STANDBY_BUCKET_RARE);
    }

    @Test
    public void testPredictionNotOverridden() throws Exception {
        reportEvent(mController, USER_INTERACTION, 0);
        assertBucket(STANDBY_BUCKET_ACTIVE);

        mInjector.mElapsedRealtime = WORKING_SET_THRESHOLD - 1000;
        reportEvent(mController, NOTIFICATION_SEEN, mInjector.mElapsedRealtime);
        assertBucket(STANDBY_BUCKET_ACTIVE);

        // Falls back to WORKING_SET
        mInjector.mElapsedRealtime += 5000;
        mController.checkIdleStates(USER_ID);
        assertBucket(STANDBY_BUCKET_WORKING_SET);

        // Predict to ACTIVE
        mInjector.mElapsedRealtime += 1000;
        mController.setAppStandbyBucket(PACKAGE_1, USER_ID, STANDBY_BUCKET_ACTIVE,
                REASON_MAIN_PREDICTED, mInjector.mElapsedRealtime);
        assertBucket(STANDBY_BUCKET_ACTIVE);

        // CheckIdleStates should not change the prediction
        mInjector.mElapsedRealtime += 1000;
        mController.checkIdleStates(USER_ID);
        assertBucket(STANDBY_BUCKET_ACTIVE);
    }

    @Test
    public void testPredictionStrikesBack() throws Exception {
        reportEvent(mController, USER_INTERACTION, 0);
        assertBucket(STANDBY_BUCKET_ACTIVE);

        // Predict to FREQUENT
        mInjector.mElapsedRealtime = RARE_THRESHOLD;
        mController.setAppStandbyBucket(PACKAGE_1, USER_ID, STANDBY_BUCKET_FREQUENT,
                REASON_MAIN_PREDICTED, mInjector.mElapsedRealtime);
        assertBucket(STANDBY_BUCKET_FREQUENT);

        // Add a short timeout event
        mInjector.mElapsedRealtime += 1000;
        reportEvent(mController, SYSTEM_INTERACTION, mInjector.mElapsedRealtime);
        assertBucket(STANDBY_BUCKET_ACTIVE);
        mInjector.mElapsedRealtime += 1000;
        mController.checkIdleStates(USER_ID);
        assertBucket(STANDBY_BUCKET_ACTIVE);

        // Verify it reverted to predicted
        mInjector.mElapsedRealtime += WORKING_SET_THRESHOLD / 2;
        mController.checkIdleStates(USER_ID);
        assertBucket(STANDBY_BUCKET_FREQUENT);
    }

    @Test
    public void testAddActiveDeviceAdmin() {
        assertActiveAdmins(USER_ID, (String[]) null);
        assertActiveAdmins(USER_ID2, (String[]) null);

        mController.addActiveDeviceAdmin(ADMIN_PKG, USER_ID);
        assertActiveAdmins(USER_ID, ADMIN_PKG);
        assertActiveAdmins(USER_ID2, (String[]) null);

        mController.addActiveDeviceAdmin(ADMIN_PKG, USER_ID);
        assertActiveAdmins(USER_ID, ADMIN_PKG);
        assertActiveAdmins(USER_ID2, (String[]) null);

        mController.addActiveDeviceAdmin(ADMIN_PKG2, USER_ID2);
        assertActiveAdmins(USER_ID, ADMIN_PKG);
        assertActiveAdmins(USER_ID2, ADMIN_PKG2);
    }

    @Test
    public void testSetActiveAdminApps() {
        assertActiveAdmins(USER_ID, (String[]) null);
        assertActiveAdmins(USER_ID2, (String[]) null);

        setActiveAdmins(USER_ID, ADMIN_PKG, ADMIN_PKG2);
        assertActiveAdmins(USER_ID, ADMIN_PKG, ADMIN_PKG2);
        assertActiveAdmins(USER_ID2, (String[]) null);

        mController.addActiveDeviceAdmin(ADMIN_PKG2, USER_ID2);
        setActiveAdmins(USER_ID2, ADMIN_PKG);
        assertActiveAdmins(USER_ID, ADMIN_PKG, ADMIN_PKG2);
        assertActiveAdmins(USER_ID2, ADMIN_PKG);

        mController.setActiveAdminApps(null, USER_ID);
        assertActiveAdmins(USER_ID, (String[]) null);
    }

    @Test
    public void isActiveDeviceAdmin() {
        assertActiveAdmins(USER_ID, (String[]) null);
        assertActiveAdmins(USER_ID2, (String[]) null);

        mController.addActiveDeviceAdmin(ADMIN_PKG, USER_ID);
        assertIsActiveAdmin(ADMIN_PKG, USER_ID);
        assertIsNotActiveAdmin(ADMIN_PKG, USER_ID2);

        mController.addActiveDeviceAdmin(ADMIN_PKG2, USER_ID2);
        mController.addActiveDeviceAdmin(ADMIN_PKG, USER_ID2);
        assertIsActiveAdmin(ADMIN_PKG, USER_ID);
        assertIsNotActiveAdmin(ADMIN_PKG2, USER_ID);
        assertIsActiveAdmin(ADMIN_PKG, USER_ID2);
        assertIsActiveAdmin(ADMIN_PKG2, USER_ID2);

        setActiveAdmins(USER_ID2, ADMIN_PKG2);
        assertIsActiveAdmin(ADMIN_PKG2, USER_ID2);
        assertIsNotActiveAdmin(ADMIN_PKG, USER_ID2);
        assertIsActiveAdmin(ADMIN_PKG, USER_ID);
        assertIsNotActiveAdmin(ADMIN_PKG2, USER_ID);
    }

    private String getAdminAppsStr(int userId) {
        return getAdminAppsStr(userId, mController.getActiveAdminAppsForTest(userId));
    }

    private String getAdminAppsStr(int userId, Set<String> adminApps) {
        return "admin apps for u" + userId + ": "
                + (adminApps == null ? "null" : Arrays.toString(adminApps.toArray()));
    }

    private void assertIsActiveAdmin(String adminApp, int userId) {
        assertTrue(adminApp + " should be an active admin; " + getAdminAppsStr(userId),
                mController.isActiveDeviceAdmin(adminApp, userId));
    }

    private void assertIsNotActiveAdmin(String adminApp, int userId) {
        assertFalse(adminApp + " shouldn't be an active admin; " + getAdminAppsStr(userId),
                mController.isActiveDeviceAdmin(adminApp, userId));
    }

    private void assertActiveAdmins(int userId, String... admins) {
        final Set<String> actualAdminApps = mController.getActiveAdminAppsForTest(userId);
        if (admins == null) {
            if (actualAdminApps != null && !actualAdminApps.isEmpty()) {
                fail("Admin apps should be null; " + getAdminAppsStr(userId, actualAdminApps));
            }
            return;
        }
        assertEquals("No. of admin apps not equal; " + getAdminAppsStr(userId, actualAdminApps)
                + "; expected=" + Arrays.toString(admins), admins.length, actualAdminApps.size());
        final Set<String> adminAppsCopy = new ArraySet<>(actualAdminApps);
        for (String admin : admins) {
            adminAppsCopy.remove(admin);
        }
        assertTrue("Unexpected admin apps; " + getAdminAppsStr(userId, actualAdminApps)
                + "; expected=" + Arrays.toString(admins), adminAppsCopy.isEmpty());
    }

    private void setActiveAdmins(int userId, String... admins) {
        mController.setActiveAdminApps(new ArraySet<>(Arrays.asList(admins)), userId);
    }
}
