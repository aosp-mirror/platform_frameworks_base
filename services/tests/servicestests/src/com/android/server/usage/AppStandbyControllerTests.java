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

import static android.app.usage.UsageEvents.Event.FOREGROUND_SERVICE_START;
import static android.app.usage.UsageEvents.Event.NOTIFICATION_SEEN;
import static android.app.usage.UsageEvents.Event.SLICE_PINNED;
import static android.app.usage.UsageEvents.Event.SLICE_PINNED_PRIV;
import static android.app.usage.UsageEvents.Event.SYSTEM_INTERACTION;
import static android.app.usage.UsageEvents.Event.USER_INTERACTION;
import static android.app.usage.UsageStatsManager.REASON_MAIN_DEFAULT;
import static android.app.usage.UsageStatsManager.REASON_MAIN_FORCED_BY_SYSTEM;
import static android.app.usage.UsageStatsManager.REASON_MAIN_FORCED_BY_USER;
import static android.app.usage.UsageStatsManager.REASON_MAIN_PREDICTED;
import static android.app.usage.UsageStatsManager.REASON_MAIN_TIMEOUT;
import static android.app.usage.UsageStatsManager.REASON_MAIN_USAGE;
import static android.app.usage.UsageStatsManager.REASON_SUB_FORCED_SYSTEM_FLAG_ABUSE;
import static android.app.usage.UsageStatsManager.REASON_SUB_FORCED_SYSTEM_FLAG_BACKGROUND_RESOURCE_USAGE;
import static android.app.usage.UsageStatsManager.REASON_SUB_FORCED_SYSTEM_FLAG_BUGGY;
import static android.app.usage.UsageStatsManager.REASON_SUB_FORCED_SYSTEM_FLAG_UNDEFINED;
import static android.app.usage.UsageStatsManager.REASON_SUB_USAGE_EXEMPTED_SYNC_SCHEDULED_NON_DOZE;
import static android.app.usage.UsageStatsManager.REASON_SUB_USAGE_MOVE_TO_FOREGROUND;
import static android.app.usage.UsageStatsManager.REASON_SUB_USAGE_SYNC_ADAPTER;
import static android.app.usage.UsageStatsManager.REASON_SUB_USAGE_SYSTEM_INTERACTION;
import static android.app.usage.UsageStatsManager.REASON_SUB_USAGE_USER_INTERACTION;
import static android.app.usage.UsageStatsManager.STANDBY_BUCKET_ACTIVE;
import static android.app.usage.UsageStatsManager.STANDBY_BUCKET_EXEMPTED;
import static android.app.usage.UsageStatsManager.STANDBY_BUCKET_FREQUENT;
import static android.app.usage.UsageStatsManager.STANDBY_BUCKET_NEVER;
import static android.app.usage.UsageStatsManager.STANDBY_BUCKET_RARE;
import static android.app.usage.UsageStatsManager.STANDBY_BUCKET_RESTRICTED;
import static android.app.usage.UsageStatsManager.STANDBY_BUCKET_WORKING_SET;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Matchers.anyInt;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

import android.annotation.NonNull;
import android.app.ActivityManager;
import android.app.usage.AppStandbyInfo;
import android.app.usage.UsageEvents;
import android.appwidget.AppWidgetManager;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.Looper;
import android.os.RemoteException;
import android.os.UserHandle;
import android.platform.test.annotations.Presubmit;
import android.util.ArraySet;
import android.view.Display;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.server.SystemService;
import com.android.server.usage.AppStandbyInternal.AppIdleStateChangeListener;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
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
    private static final String PACKAGE_SYSTEM_HEADFULL = "com.example.system.headfull";
    private static final int UID_SYSTEM_HEADFULL = 10002;
    private static final String PACKAGE_SYSTEM_HEADLESS = "com.example.system.headless";
    private static final int UID_SYSTEM_HEADLESS = 10003;
    private static final String PACKAGE_WELLBEING = "com.example.wellbeing";
    private static final int UID_WELLBEING = 10004;
    private static final int USER_ID = 0;
    private static final int USER_ID2 = 10;
    private static final UserHandle USER_HANDLE_USER2 = new UserHandle(USER_ID2);
    private static final int USER_ID3 = 11;

    private static final String PACKAGE_UNKNOWN = "com.example.unknown";

    private static final String ADMIN_PKG = "com.android.admin";
    private static final String ADMIN_PKG2 = "com.android.admin2";
    private static final String ADMIN_PKG3 = "com.android.admin3";

    private static final long MINUTE_MS = 60 * 1000;
    private static final long HOUR_MS = 60 * MINUTE_MS;
    private static final long DAY_MS = 24 * HOUR_MS;

    private static final long WORKING_SET_THRESHOLD = 12 * HOUR_MS;
    private static final long FREQUENT_THRESHOLD = 24 * HOUR_MS;
    private static final long RARE_THRESHOLD = 48 * HOUR_MS;
    private static final long RESTRICTED_THRESHOLD = 96 * HOUR_MS;

    /** Mock variable used in {@link MyInjector#isPackageInstalled(String, int, int)} */
    private static boolean isPackageInstalled = true;

    private MyInjector mInjector;
    private AppStandbyController mController;

    private CountDownLatch mStateChangedLatch = new CountDownLatch(1);
    private String mLatchPkgName = null;
    private AppIdleStateChangeListener mListener = new AppIdleStateChangeListener() {
        @Override
        public void onAppIdleStateChanged(String packageName, int userId,
                boolean idle, int bucket, int reason) {
            // Ignore events not related to mLatchPkgName, if set.
            if (mLatchPkgName != null && !mLatchPkgName.equals(packageName)) return;
            mStateChangedLatch.countDown();
        }
    };

    static class MyContextWrapper extends ContextWrapper {
        PackageManager mockPm = mock(PackageManager.class);

        public MyContextWrapper(Context base) {
            super(base);
        }

        public PackageManager getPackageManager() {
            return mockPm;
        }

        public Object getSystemService(@NonNull String name) {
            if (Context.ACTIVITY_SERVICE.equals(name)) {
                return mock(ActivityManager.class);
            }
            return super.getSystemService(name);
        }
    }

    static class MyInjector extends AppStandbyController.Injector {
        long mElapsedRealtime;
        boolean mIsAppIdleEnabled = true;
        boolean mIsCharging;
        boolean mIsRestrictedBucketEnabled = true;
        List<String> mNonIdleWhitelistApps = new ArrayList<>();
        boolean mDisplayOn;
        DisplayManager.DisplayListener mDisplayListener;
        String mBoundWidgetPackage = PACKAGE_EXEMPTED_1;
        int[] mRunningUsers = new int[] {USER_ID};
        List<UserHandle> mCrossProfileTargets = Collections.emptyList();
        boolean mDeviceIdleMode = false;

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
        boolean isNonIdleWhitelisted(String packageName) {
            return mNonIdleWhitelistApps.contains(packageName);
        }

        @Override
        boolean isWellbeingPackage(String packageName) {
            return PACKAGE_WELLBEING.equals(packageName);
        }

        @Override
        void updatePowerWhitelistCache() {
        }

        @Override
        boolean isRestrictedBucketEnabled() {
            return mIsRestrictedBucketEnabled;
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
        boolean isPackageInstalled(String packageName, int flags, int userId) {
            // Should always return true (default value) unless testing for an uninstalled app
            return isPackageInstalled;
        }

        @Override
        int[] getRunningUserIds() {
            return mRunningUsers;
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
                    + WORKING_SET_THRESHOLD + "/" + FREQUENT_THRESHOLD + "/" + RARE_THRESHOLD
                    + "/" + RESTRICTED_THRESHOLD;
        }

        @Override
        public boolean isDeviceIdleMode() {
            return mDeviceIdleMode;
        }

        @Override
        public List<UserHandle> getValidCrossProfileTargets(String pkg, int userId) {
            return mCrossProfileTargets;
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

        PackageInfo pis = new PackageInfo();
        pis.activities = new ActivityInfo[]{mock(ActivityInfo.class)};
        pis.applicationInfo = new ApplicationInfo();
        pis.applicationInfo.uid = UID_SYSTEM_HEADFULL;
        pis.applicationInfo.flags = ApplicationInfo.FLAG_SYSTEM;
        pis.packageName = PACKAGE_SYSTEM_HEADFULL;
        packages.add(pis);

        PackageInfo pish = new PackageInfo();
        pish.applicationInfo = new ApplicationInfo();
        pish.applicationInfo.uid = UID_SYSTEM_HEADLESS;
        pish.applicationInfo.flags = ApplicationInfo.FLAG_SYSTEM;
        pish.packageName = PACKAGE_SYSTEM_HEADLESS;
        packages.add(pish);

        PackageInfo piw = new PackageInfo();
        piw.applicationInfo = new ApplicationInfo();
        piw.applicationInfo.uid = UID_WELLBEING;
        piw.packageName = PACKAGE_WELLBEING;
        packages.add(piw);

        doReturn(packages).when(mockPm).getInstalledPackagesAsUser(anyInt(), anyInt());
        try {
            for (int i = 0; i < packages.size(); ++i) {
                PackageInfo pkg = packages.get(i);

                doReturn(pkg.applicationInfo.uid).when(mockPm)
                        .getPackageUidAsUser(eq(pkg.packageName), anyInt());
                doReturn(pkg.applicationInfo.uid).when(mockPm)
                        .getPackageUidAsUser(eq(pkg.packageName), anyInt(), anyInt());
                doReturn(pkg.applicationInfo).when(mockPm)
                        .getApplicationInfo(eq(pkg.packageName), anyInt());
            }
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
        assertNotEquals(STANDBY_BUCKET_EXEMPTED,
                controller.getAppStandbyBucket(PACKAGE_1, USER_ID,
                        mInjector.mElapsedRealtime, false));

        controller.addListener(mListener);
        mLatchPkgName = null;
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
    }

    @Test
    public void testBoundWidgetPackageExempt() throws Exception {
        assumeTrue(mInjector.getContext().getSystemService(AppWidgetManager.class) != null);
        assertEquals(STANDBY_BUCKET_ACTIVE,
                mController.getAppStandbyBucket(PACKAGE_EXEMPTED_1, USER_ID,
                        mInjector.mElapsedRealtime, false));
    }

    private static class TestParoleListener extends AppIdleStateChangeListener {
        private boolean mIsParoleOn = false;
        private CountDownLatch mLatch;
        private boolean mIsExpecting = false;
        private boolean mExpectedParoleState;

        boolean getParoleState() {
            synchronized (this) {
                return mIsParoleOn;
            }
        }

        void rearmLatch(boolean expectedParoleState) {
            synchronized (this) {
                mLatch = new CountDownLatch(1);
                mIsExpecting = true;
                mExpectedParoleState = expectedParoleState;
            }
        }

        void awaitOnLatch(long time) throws Exception {
            mLatch.await(time, TimeUnit.MILLISECONDS);
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
                    mIsParoleOn = isParoleOn;
                    if (mIsExpecting && isParoleOn == mExpectedParoleState) {
                        mLatch.countDown();
                    }
                }
            }
        }
    }

    @Test
    public void testIsAppIdle_Charging() throws Exception {
        TestParoleListener paroleListener = new TestParoleListener();
        mController.addListener(paroleListener);

        setChargingState(mController, false);
        mController.setAppStandbyBucket(PACKAGE_1, USER_ID, STANDBY_BUCKET_RARE,
                REASON_MAIN_FORCED_BY_SYSTEM);
        assertEquals(STANDBY_BUCKET_RARE, getStandbyBucket(mController, PACKAGE_1));
        assertTrue(mController.isAppIdleFiltered(PACKAGE_1, UID_1, USER_ID, 0));
        assertTrue(mController.isAppIdleFiltered(PACKAGE_1, UID_1, USER_ID, false));
        assertFalse(mController.isInParole());

        paroleListener.rearmLatch(true);
        setChargingState(mController, true);
        paroleListener.awaitOnLatch(2000);
        assertTrue(paroleListener.getParoleState());
        assertEquals(STANDBY_BUCKET_RARE, getStandbyBucket(mController, PACKAGE_1));
        assertFalse(mController.isAppIdleFiltered(PACKAGE_1, UID_1, USER_ID, 0));
        assertFalse(mController.isAppIdleFiltered(PACKAGE_1, UID_1, USER_ID, false));
        assertTrue(mController.isInParole());

        paroleListener.rearmLatch(false);
        setChargingState(mController, false);
        paroleListener.awaitOnLatch(2000);
        assertFalse(paroleListener.getParoleState());
        assertEquals(STANDBY_BUCKET_RARE, getStandbyBucket(mController, PACKAGE_1));
        assertTrue(mController.isAppIdleFiltered(PACKAGE_1, UID_1, USER_ID, 0));
        assertTrue(mController.isAppIdleFiltered(PACKAGE_1, UID_1, USER_ID, false));
        assertFalse(mController.isInParole());
    }

    @Test
    public void testIsAppIdle_Enabled() throws Exception {
        setChargingState(mController, false);
        TestParoleListener paroleListener = new TestParoleListener();
        mController.addListener(paroleListener);

        setAppIdleEnabled(mController, true);
        mController.setAppStandbyBucket(PACKAGE_1, USER_ID, STANDBY_BUCKET_RARE,
                REASON_MAIN_FORCED_BY_SYSTEM);
        assertEquals(STANDBY_BUCKET_RARE, getStandbyBucket(mController, PACKAGE_1));
        assertTrue(mController.isAppIdleFiltered(PACKAGE_1, UID_1, USER_ID, 0));
        assertTrue(mController.isAppIdleFiltered(PACKAGE_1, UID_1, USER_ID, false));

        paroleListener.rearmLatch(false);
        setAppIdleEnabled(mController, false);
        paroleListener.awaitOnLatch(2000);
        assertTrue(paroleListener.mIsParoleOn);
        assertFalse(mController.isAppIdleFiltered(PACKAGE_1, UID_1, USER_ID, 0));
        assertFalse(mController.isAppIdleFiltered(PACKAGE_1, UID_1, USER_ID, false));

        paroleListener.rearmLatch(true);
        setAppIdleEnabled(mController, true);
        paroleListener.awaitOnLatch(2000);
        assertFalse(paroleListener.getParoleState());
        assertEquals(STANDBY_BUCKET_RARE, getStandbyBucket(mController, PACKAGE_1));
        assertTrue(mController.isAppIdleFiltered(PACKAGE_1, UID_1, USER_ID, 0));
        assertTrue(mController.isAppIdleFiltered(PACKAGE_1, UID_1, USER_ID, false));
    }

    private void assertTimeout(AppStandbyController controller, long elapsedTime, int bucket) {
        assertTimeout(controller, elapsedTime, bucket, USER_ID);
    }

    private void assertTimeout(AppStandbyController controller, long elapsedTime, int bucket,
            int userId) {
        mInjector.mElapsedRealtime = elapsedTime;
        controller.checkIdleStates(userId);
        assertEquals(bucket,
                controller.getAppStandbyBucket(PACKAGE_1, userId, mInjector.mElapsedRealtime,
                        false));
    }

    private void reportEvent(AppStandbyController controller, int eventType, long elapsedTime,
            String packageName) {
        // Back to ACTIVE on event
        mInjector.mElapsedRealtime = elapsedTime;
        UsageEvents.Event ev = new UsageEvents.Event();
        ev.mPackage = packageName;
        ev.mEventType = eventType;
        controller.reportEvent(ev, USER_ID);
    }

    private int getStandbyBucket(AppStandbyController controller, String packageName) {
        return getStandbyBucket(USER_ID, controller, packageName);
    }

    private int getStandbyBucket(int userId, AppStandbyController controller, String packageName) {
        return controller.getAppStandbyBucket(packageName, userId, mInjector.mElapsedRealtime,
                true);
    }

    private int getStandbyBucketReason(String packageName) {
        return mController.getAppStandbyBucketReason(packageName, USER_ID,
                mInjector.mElapsedRealtime);
    }

    private void assertBucket(int bucket) {
        assertBucket(bucket, PACKAGE_1);
    }

    private void assertBucket(int bucket, String pkg) {
        assertEquals(bucket, getStandbyBucket(mController, pkg));
    }

    private void assertNotBucket(int bucket) {
        assertNotEquals(bucket, getStandbyBucket(mController, PACKAGE_1));
    }

    @Test
    public void testBuckets() throws Exception {
        assertTimeout(mController, 0, STANDBY_BUCKET_NEVER);

        reportEvent(mController, USER_INTERACTION, 0, PACKAGE_1);

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

        // RESTRICTED bucket
        assertTimeout(mController, RESTRICTED_THRESHOLD + 1, STANDBY_BUCKET_RESTRICTED);

        reportEvent(mController, USER_INTERACTION, RARE_THRESHOLD + 1, PACKAGE_1);

        assertTimeout(mController, RARE_THRESHOLD + 1, STANDBY_BUCKET_ACTIVE);

        // RESTRICTED bucket
        assertTimeout(mController, RESTRICTED_THRESHOLD * 2 + 2, STANDBY_BUCKET_RESTRICTED);
    }

    @Test
    public void testSetAppStandbyBucket() throws Exception {
        // For a known package, standby bucket should be set properly
        reportEvent(mController, USER_INTERACTION, 0, PACKAGE_1);
        mInjector.mElapsedRealtime = HOUR_MS;
        mController.setAppStandbyBucket(PACKAGE_1, USER_ID, STANDBY_BUCKET_ACTIVE,
                REASON_MAIN_TIMEOUT);
        assertEquals(STANDBY_BUCKET_ACTIVE, getStandbyBucket(mController, PACKAGE_1));

        // For an unknown package, standby bucket should not be set, hence NEVER is returned
        // Ensure the unknown package is not already in history by removing it
        mController.clearAppIdleForPackage(PACKAGE_UNKNOWN, USER_ID);
        isPackageInstalled = false; // Mock package is not installed
        mController.setAppStandbyBucket(PACKAGE_UNKNOWN, USER_ID, STANDBY_BUCKET_ACTIVE,
                REASON_MAIN_TIMEOUT);
        isPackageInstalled = true; // Reset mocked variable for other tests
        assertEquals(STANDBY_BUCKET_NEVER, getStandbyBucket(mController, PACKAGE_UNKNOWN));
    }

    @Test
    public void testAppStandbyBucketOnInstallAndUninstall() throws Exception {
        // On package install, standby bucket should be ACTIVE
        reportEvent(mController, USER_INTERACTION, 0, PACKAGE_UNKNOWN);
        assertEquals(STANDBY_BUCKET_ACTIVE, getStandbyBucket(mController, PACKAGE_UNKNOWN));

        // On uninstall, package should not exist in history and should return a NEVER bucket
        mController.clearAppIdleForPackage(PACKAGE_UNKNOWN, USER_ID);
        assertEquals(STANDBY_BUCKET_NEVER, getStandbyBucket(mController, PACKAGE_UNKNOWN));
        // Ensure uninstalled app is not in history
        List<AppStandbyInfo> buckets = mController.getAppStandbyBuckets(USER_ID);
        for(AppStandbyInfo bucket : buckets) {
            if (bucket.mPackageName.equals(PACKAGE_UNKNOWN)) {
                fail("packageName found in app idle history after uninstall.");
            }
        }
    }

    @Test
    public void testScreenTimeAndBuckets() throws Exception {
        mInjector.setDisplayOn(false);

        assertTimeout(mController, 0, STANDBY_BUCKET_NEVER);

        reportEvent(mController, USER_INTERACTION, 0, PACKAGE_1);

        // ACTIVE bucket
        assertTimeout(mController, WORKING_SET_THRESHOLD - 1, STANDBY_BUCKET_ACTIVE);

        // WORKING_SET bucket
        assertTimeout(mController, WORKING_SET_THRESHOLD + 1, STANDBY_BUCKET_WORKING_SET);

        // RARE bucket, should fail because the screen wasn't ON.
        mInjector.mElapsedRealtime = RARE_THRESHOLD + 1;
        mController.checkIdleStates(USER_ID);
        assertNotEquals(STANDBY_BUCKET_RARE, getStandbyBucket(mController, PACKAGE_1));

        mInjector.setDisplayOn(true);
        assertTimeout(mController, RARE_THRESHOLD + 2 * HOUR_MS + 1, STANDBY_BUCKET_RARE);
    }

    @Test
    public void testForcedIdle() throws Exception {
        mController.forceIdleState(PACKAGE_1, USER_ID, true);
        assertEquals(STANDBY_BUCKET_RARE, getStandbyBucket(mController, PACKAGE_1));
        assertTrue(mController.isAppIdleFiltered(PACKAGE_1, UID_1, USER_ID, 0));

        mController.forceIdleState(PACKAGE_1, USER_ID, false);
        assertEquals(STANDBY_BUCKET_ACTIVE, mController.getAppStandbyBucket(PACKAGE_1, USER_ID, 0,
                true));
        assertFalse(mController.isAppIdleFiltered(PACKAGE_1, UID_1, USER_ID, 0));
    }

    @Test
    public void testNotificationEvent() throws Exception {
        reportEvent(mController, USER_INTERACTION, 0, PACKAGE_1);
        assertEquals(STANDBY_BUCKET_ACTIVE, getStandbyBucket(mController, PACKAGE_1));
        mInjector.mElapsedRealtime = 1;
        reportEvent(mController, NOTIFICATION_SEEN, mInjector.mElapsedRealtime, PACKAGE_1);
        assertEquals(STANDBY_BUCKET_ACTIVE, getStandbyBucket(mController, PACKAGE_1));

        mController.forceIdleState(PACKAGE_1, USER_ID, true);
        reportEvent(mController, NOTIFICATION_SEEN, mInjector.mElapsedRealtime, PACKAGE_1);
        assertEquals(STANDBY_BUCKET_WORKING_SET, getStandbyBucket(mController, PACKAGE_1));
    }

    @Test
    public void testSlicePinnedEvent() throws Exception {
        reportEvent(mController, USER_INTERACTION, 0, PACKAGE_1);
        assertEquals(STANDBY_BUCKET_ACTIVE, getStandbyBucket(mController, PACKAGE_1));
        mInjector.mElapsedRealtime = 1;
        reportEvent(mController, SLICE_PINNED, mInjector.mElapsedRealtime, PACKAGE_1);
        assertEquals(STANDBY_BUCKET_ACTIVE, getStandbyBucket(mController, PACKAGE_1));

        mController.forceIdleState(PACKAGE_1, USER_ID, true);
        reportEvent(mController, SLICE_PINNED, mInjector.mElapsedRealtime, PACKAGE_1);
        assertEquals(STANDBY_BUCKET_WORKING_SET, getStandbyBucket(mController, PACKAGE_1));
    }

    @Test
    public void testSlicePinnedPrivEvent() throws Exception {
        mController.forceIdleState(PACKAGE_1, USER_ID, true);
        reportEvent(mController, SLICE_PINNED_PRIV, mInjector.mElapsedRealtime, PACKAGE_1);
        assertEquals(STANDBY_BUCKET_ACTIVE, getStandbyBucket(mController, PACKAGE_1));
    }

    @Test
    public void testPredictionTimedOut() throws Exception {
        // Set it to timeout or usage, so that prediction can override it
        mInjector.mElapsedRealtime = HOUR_MS;
        mController.setAppStandbyBucket(PACKAGE_1, USER_ID, STANDBY_BUCKET_RARE,
                REASON_MAIN_TIMEOUT);
        assertEquals(STANDBY_BUCKET_RARE, getStandbyBucket(mController, PACKAGE_1));

        mController.setAppStandbyBucket(PACKAGE_1, USER_ID, STANDBY_BUCKET_ACTIVE,
                REASON_MAIN_PREDICTED);
        assertEquals(STANDBY_BUCKET_ACTIVE, getStandbyBucket(mController, PACKAGE_1));

        // Fast forward 12 hours
        mInjector.mElapsedRealtime += WORKING_SET_THRESHOLD;
        mController.checkIdleStates(USER_ID);
        // Should still be in predicted bucket, since prediction timeout is 1 day since prediction
        assertEquals(STANDBY_BUCKET_ACTIVE, getStandbyBucket(mController, PACKAGE_1));
        // Fast forward two more hours
        mInjector.mElapsedRealtime += 2 * HOUR_MS;
        mController.checkIdleStates(USER_ID);
        // Should have now applied prediction timeout
        assertEquals(STANDBY_BUCKET_WORKING_SET, getStandbyBucket(mController, PACKAGE_1));

        // Fast forward RARE bucket
        mInjector.mElapsedRealtime += RARE_THRESHOLD;
        mController.checkIdleStates(USER_ID);
        // Should continue to apply prediction timeout
        assertEquals(STANDBY_BUCKET_RARE, getStandbyBucket(mController, PACKAGE_1));
    }

    @Test
    public void testOverrides() throws Exception {
        // Can force to NEVER
        mInjector.mElapsedRealtime = HOUR_MS;
        mController.setAppStandbyBucket(PACKAGE_1, USER_ID, STANDBY_BUCKET_NEVER,
                REASON_MAIN_FORCED_BY_USER);
        assertEquals(STANDBY_BUCKET_NEVER, getStandbyBucket(mController, PACKAGE_1));

        // Prediction can't override FORCED reasons
        mController.setAppStandbyBucket(PACKAGE_1, USER_ID, STANDBY_BUCKET_RARE,
                REASON_MAIN_FORCED_BY_SYSTEM);
        mController.setAppStandbyBucket(PACKAGE_1, USER_ID, STANDBY_BUCKET_WORKING_SET,
                REASON_MAIN_PREDICTED);
        assertEquals(STANDBY_BUCKET_RARE, getStandbyBucket(mController, PACKAGE_1));

        mController.setAppStandbyBucket(PACKAGE_1, USER_ID, STANDBY_BUCKET_FREQUENT,
                REASON_MAIN_FORCED_BY_USER);
        mController.setAppStandbyBucket(PACKAGE_1, USER_ID, STANDBY_BUCKET_WORKING_SET,
                REASON_MAIN_PREDICTED);
        assertEquals(STANDBY_BUCKET_FREQUENT, getStandbyBucket(mController, PACKAGE_1));

        // Prediction can't override NEVER
        mInjector.mElapsedRealtime = 2 * HOUR_MS;
        mController.setAppStandbyBucket(PACKAGE_1, USER_ID, STANDBY_BUCKET_NEVER,
                REASON_MAIN_DEFAULT);
        mController.setAppStandbyBucket(PACKAGE_1, USER_ID, STANDBY_BUCKET_ACTIVE,
                REASON_MAIN_PREDICTED);
        assertEquals(STANDBY_BUCKET_NEVER, getStandbyBucket(mController, PACKAGE_1));

        // Prediction can't set to NEVER
        mController.setAppStandbyBucket(PACKAGE_1, USER_ID, STANDBY_BUCKET_ACTIVE,
                REASON_MAIN_USAGE);
        mController.setAppStandbyBucket(PACKAGE_1, USER_ID, STANDBY_BUCKET_NEVER,
                REASON_MAIN_PREDICTED);
        assertEquals(STANDBY_BUCKET_ACTIVE, getStandbyBucket(mController, PACKAGE_1));

        // Prediction can't remove from RESTRICTED
        mController.setAppStandbyBucket(PACKAGE_1, USER_ID, STANDBY_BUCKET_RESTRICTED,
                REASON_MAIN_FORCED_BY_USER);
        mController.setAppStandbyBucket(PACKAGE_1, USER_ID, STANDBY_BUCKET_WORKING_SET,
                REASON_MAIN_PREDICTED);
        assertEquals(STANDBY_BUCKET_RESTRICTED, getStandbyBucket(mController, PACKAGE_1));

        mController.setAppStandbyBucket(PACKAGE_1, USER_ID, STANDBY_BUCKET_RESTRICTED,
                REASON_MAIN_FORCED_BY_SYSTEM);
        mController.setAppStandbyBucket(PACKAGE_1, USER_ID, STANDBY_BUCKET_WORKING_SET,
                REASON_MAIN_PREDICTED);
        assertEquals(STANDBY_BUCKET_RESTRICTED, getStandbyBucket(mController, PACKAGE_1));

        // Force from user can remove from RESTRICTED
        mController.setAppStandbyBucket(PACKAGE_1, USER_ID, STANDBY_BUCKET_RESTRICTED,
                REASON_MAIN_FORCED_BY_USER);
        mController.setAppStandbyBucket(PACKAGE_1, USER_ID, STANDBY_BUCKET_WORKING_SET,
                REASON_MAIN_FORCED_BY_USER);
        assertEquals(STANDBY_BUCKET_WORKING_SET, getStandbyBucket(mController, PACKAGE_1));

        mController.setAppStandbyBucket(PACKAGE_1, USER_ID, STANDBY_BUCKET_RESTRICTED,
                REASON_MAIN_FORCED_BY_SYSTEM);
        mController.setAppStandbyBucket(PACKAGE_1, USER_ID, STANDBY_BUCKET_WORKING_SET,
                REASON_MAIN_FORCED_BY_USER);
        assertEquals(STANDBY_BUCKET_WORKING_SET, getStandbyBucket(mController, PACKAGE_1));

        // Force from system can remove from RESTRICTED if it was put it in due to system
        mController.setAppStandbyBucket(PACKAGE_1, USER_ID, STANDBY_BUCKET_RESTRICTED,
                REASON_MAIN_FORCED_BY_SYSTEM);
        mController.setAppStandbyBucket(PACKAGE_1, USER_ID, STANDBY_BUCKET_WORKING_SET,
                REASON_MAIN_FORCED_BY_SYSTEM);
        assertEquals(STANDBY_BUCKET_WORKING_SET, getStandbyBucket(mController, PACKAGE_1));

        mController.setAppStandbyBucket(PACKAGE_1, USER_ID, STANDBY_BUCKET_RESTRICTED,
                REASON_MAIN_FORCED_BY_USER);
        mController.setAppStandbyBucket(PACKAGE_1, USER_ID, STANDBY_BUCKET_WORKING_SET,
                REASON_MAIN_FORCED_BY_SYSTEM);
        assertEquals(STANDBY_BUCKET_RESTRICTED, getStandbyBucket(mController, PACKAGE_1));

        mController.setAppStandbyBucket(PACKAGE_1, USER_ID, STANDBY_BUCKET_RESTRICTED,
                REASON_MAIN_PREDICTED);
        mController.setAppStandbyBucket(PACKAGE_1, USER_ID, STANDBY_BUCKET_WORKING_SET,
                REASON_MAIN_FORCED_BY_SYSTEM);
        assertEquals(STANDBY_BUCKET_RESTRICTED, getStandbyBucket(mController, PACKAGE_1));

        // Non-user usage can't remove from RESTRICTED
        mController.setAppStandbyBucket(PACKAGE_1, USER_ID, STANDBY_BUCKET_RESTRICTED,
                REASON_MAIN_FORCED_BY_SYSTEM);
        mController.setAppStandbyBucket(PACKAGE_1, USER_ID, STANDBY_BUCKET_WORKING_SET,
                REASON_MAIN_USAGE);
        assertEquals(STANDBY_BUCKET_RESTRICTED, getStandbyBucket(mController, PACKAGE_1));
        mController.setAppStandbyBucket(PACKAGE_1, USER_ID, STANDBY_BUCKET_WORKING_SET,
                REASON_MAIN_USAGE | REASON_SUB_USAGE_SYSTEM_INTERACTION);
        assertEquals(STANDBY_BUCKET_RESTRICTED, getStandbyBucket(mController, PACKAGE_1));
        mController.setAppStandbyBucket(PACKAGE_1, USER_ID, STANDBY_BUCKET_WORKING_SET,
                REASON_MAIN_USAGE | REASON_SUB_USAGE_SYNC_ADAPTER);
        assertEquals(STANDBY_BUCKET_RESTRICTED, getStandbyBucket(mController, PACKAGE_1));
        mController.setAppStandbyBucket(PACKAGE_1, USER_ID, STANDBY_BUCKET_WORKING_SET,
                REASON_MAIN_USAGE | REASON_SUB_USAGE_EXEMPTED_SYNC_SCHEDULED_NON_DOZE);
        assertEquals(STANDBY_BUCKET_RESTRICTED, getStandbyBucket(mController, PACKAGE_1));

        // Explicit user usage can remove from RESTRICTED
        mController.setAppStandbyBucket(PACKAGE_1, USER_ID, STANDBY_BUCKET_RESTRICTED,
                REASON_MAIN_FORCED_BY_USER);
        mController.setAppStandbyBucket(PACKAGE_1, USER_ID, STANDBY_BUCKET_WORKING_SET,
                REASON_MAIN_USAGE | REASON_SUB_USAGE_USER_INTERACTION);
        assertEquals(STANDBY_BUCKET_WORKING_SET, getStandbyBucket(mController, PACKAGE_1));
        mController.setAppStandbyBucket(PACKAGE_1, USER_ID, STANDBY_BUCKET_RESTRICTED,
                REASON_MAIN_FORCED_BY_SYSTEM);
        mController.setAppStandbyBucket(PACKAGE_1, USER_ID, STANDBY_BUCKET_ACTIVE,
                REASON_MAIN_USAGE | REASON_SUB_USAGE_MOVE_TO_FOREGROUND);
        assertEquals(STANDBY_BUCKET_ACTIVE, getStandbyBucket(mController, PACKAGE_1));
    }

    @Test
    public void testTimeout() throws Exception {
        reportEvent(mController, USER_INTERACTION, 0, PACKAGE_1);
        assertBucket(STANDBY_BUCKET_ACTIVE);

        mInjector.mElapsedRealtime = 2000;
        mController.setAppStandbyBucket(PACKAGE_1, USER_ID, STANDBY_BUCKET_FREQUENT,
                REASON_MAIN_PREDICTED);
        assertBucket(STANDBY_BUCKET_ACTIVE);

        // bucketing works after timeout
        mInjector.mElapsedRealtime = mController.mPredictionTimeoutMillis - 100;
        mController.checkIdleStates(USER_ID);
        // Use recent prediction
        assertBucket(STANDBY_BUCKET_FREQUENT);

        // Way past prediction timeout, use system thresholds
        mInjector.mElapsedRealtime = RARE_THRESHOLD;
        mController.checkIdleStates(USER_ID);
        assertBucket(STANDBY_BUCKET_RARE);
    }

    /**
     * Test that setAppStandbyBucket to RESTRICTED doesn't change the bucket until the usage
     * timeout has passed.
     */
    @Test
    public void testTimeoutBeforeRestricted() throws Exception {
        reportEvent(mController, USER_INTERACTION, 0, PACKAGE_1);
        assertBucket(STANDBY_BUCKET_ACTIVE);

        mInjector.mElapsedRealtime += WORKING_SET_THRESHOLD;
        mController.setAppStandbyBucket(PACKAGE_1, USER_ID, STANDBY_BUCKET_RESTRICTED,
                REASON_MAIN_FORCED_BY_SYSTEM);
        // Bucket shouldn't change
        assertBucket(STANDBY_BUCKET_ACTIVE);

        // bucketing works after timeout
        mInjector.mElapsedRealtime += DAY_MS;
        mController.setAppStandbyBucket(PACKAGE_1, USER_ID, STANDBY_BUCKET_RESTRICTED,
                REASON_MAIN_FORCED_BY_SYSTEM);
        assertBucket(STANDBY_BUCKET_RESTRICTED);

        // Way past all timeouts. Make sure timeout processing doesn't raise bucket.
        mInjector.mElapsedRealtime += RESTRICTED_THRESHOLD * 4;
        mController.checkIdleStates(USER_ID);
        assertBucket(STANDBY_BUCKET_RESTRICTED);
    }

    /**
     * Test that an app is put into the RESTRICTED bucket after enough time has passed.
     */
    @Test
    public void testRestrictedDelay() throws Exception {
        reportEvent(mController, USER_INTERACTION, 0, PACKAGE_1);
        assertBucket(STANDBY_BUCKET_ACTIVE);

        mInjector.mElapsedRealtime += mInjector.getAutoRestrictedBucketDelayMs() - 5000;
        mController.setAppStandbyBucket(PACKAGE_1, USER_ID, STANDBY_BUCKET_RESTRICTED,
                REASON_MAIN_FORCED_BY_SYSTEM);
        // Bucket shouldn't change
        assertBucket(STANDBY_BUCKET_ACTIVE);

        // bucketing works after timeout
        mInjector.mElapsedRealtime += 6000;

        Thread.sleep(6000);
        // Enough time has passed. The app should automatically be put into the RESTRICTED bucket.
        assertBucket(STANDBY_BUCKET_RESTRICTED);
    }

    /**
     * Test that an app is put into the RESTRICTED bucket after enough time has passed.
     */
    @Test
    public void testRestrictedDelay_DelayChange() throws Exception {
        reportEvent(mController, USER_INTERACTION, 0, PACKAGE_1);
        assertBucket(STANDBY_BUCKET_ACTIVE);

        mInjector.mAutoRestrictedBucketDelayMs = 2 * HOUR_MS;
        mInjector.mElapsedRealtime += 2 * HOUR_MS - 5000;
        mController.setAppStandbyBucket(PACKAGE_1, USER_ID, STANDBY_BUCKET_RESTRICTED,
                REASON_MAIN_FORCED_BY_SYSTEM);
        // Bucket shouldn't change
        assertBucket(STANDBY_BUCKET_ACTIVE);

        // bucketing works after timeout
        mInjector.mElapsedRealtime += 6000;

        Thread.sleep(6000);
        // Enough time has passed. The app should automatically be put into the RESTRICTED bucket.
        assertBucket(STANDBY_BUCKET_RESTRICTED);
    }

    /**
     * Test that an app is "timed out" into the RESTRICTED bucket if prediction tries to put it into
     * a low bucket after the RESTRICTED timeout.
     */
    @Test
    public void testRestrictedTimeoutOverridesRestoredLowBucketPrediction() {
        reportEvent(mController, USER_INTERACTION, mInjector.mElapsedRealtime, PACKAGE_1);
        assertBucket(STANDBY_BUCKET_ACTIVE);

        // Predict to RARE Not long enough to time out into RESTRICTED.
        mInjector.mElapsedRealtime += RARE_THRESHOLD;
        mController.setAppStandbyBucket(PACKAGE_1, USER_ID, STANDBY_BUCKET_RARE,
                REASON_MAIN_PREDICTED);
        assertBucket(STANDBY_BUCKET_RARE);

        // Add a short timeout event
        mInjector.mElapsedRealtime += 1000;
        reportEvent(mController, SYSTEM_INTERACTION, mInjector.mElapsedRealtime, PACKAGE_1);
        assertBucket(STANDBY_BUCKET_ACTIVE);
        mInjector.mElapsedRealtime += 1000;
        mController.checkIdleStates(USER_ID);
        assertBucket(STANDBY_BUCKET_ACTIVE);

        // Long enough that it could have timed out into RESTRICTED. Instead of reverting to
        // predicted RARE, should go into RESTRICTED
        mInjector.mElapsedRealtime += RESTRICTED_THRESHOLD * 4;
        mController.checkIdleStates(USER_ID);
        assertBucket(STANDBY_BUCKET_RESTRICTED);

        // Ensure that prediction can still raise it out despite this override.
        mInjector.mElapsedRealtime += 1;
        mController.setAppStandbyBucket(PACKAGE_1, USER_ID, STANDBY_BUCKET_ACTIVE,
                REASON_MAIN_PREDICTED);
        assertBucket(STANDBY_BUCKET_ACTIVE);
    }

    /**
     * Test that an app is "timed out" into the RESTRICTED bucket if prediction tries to put it into
     * a low bucket after the RESTRICTED timeout.
     */
    @Test
    public void testRestrictedTimeoutOverridesPredictionLowBucket() {
        reportEvent(mController, USER_INTERACTION, mInjector.mElapsedRealtime, PACKAGE_1);

        // Not long enough to time out into RESTRICTED.
        mInjector.mElapsedRealtime += RARE_THRESHOLD;
        mController.setAppStandbyBucket(PACKAGE_1, USER_ID, STANDBY_BUCKET_RARE,
                REASON_MAIN_PREDICTED);
        assertBucket(STANDBY_BUCKET_RARE);

        mInjector.mElapsedRealtime += 1;
        reportEvent(mController, USER_INTERACTION, mInjector.mElapsedRealtime, PACKAGE_1);

        // Long enough that it could have timed out into RESTRICTED.
        mInjector.mElapsedRealtime += RESTRICTED_THRESHOLD * 4;
        mController.setAppStandbyBucket(PACKAGE_1, USER_ID, STANDBY_BUCKET_ACTIVE,
                REASON_MAIN_PREDICTED);
        assertBucket(STANDBY_BUCKET_ACTIVE);
        mController.setAppStandbyBucket(PACKAGE_1, USER_ID, STANDBY_BUCKET_RARE,
                REASON_MAIN_PREDICTED);
        assertBucket(STANDBY_BUCKET_RESTRICTED);
    }

    @Test
    public void testRestrictedBucketDisabled() {
        mInjector.mIsRestrictedBucketEnabled = false;
        // Get the controller to read the new value. Capturing the ContentObserver isn't possible
        // at the moment.
        mController.onBootPhase(SystemService.PHASE_SYSTEM_SERVICES_READY);

        reportEvent(mController, USER_INTERACTION, mInjector.mElapsedRealtime, PACKAGE_1);
        mInjector.mElapsedRealtime += RESTRICTED_THRESHOLD;

        // Nothing should be able to put it into the RESTRICTED bucket.
        mController.setAppStandbyBucket(PACKAGE_1, USER_ID, STANDBY_BUCKET_RESTRICTED,
                REASON_MAIN_TIMEOUT);
        assertNotBucket(STANDBY_BUCKET_RESTRICTED);
        mController.setAppStandbyBucket(PACKAGE_1, USER_ID, STANDBY_BUCKET_RESTRICTED,
                REASON_MAIN_PREDICTED);
        assertNotBucket(STANDBY_BUCKET_RESTRICTED);
        mController.setAppStandbyBucket(PACKAGE_1, USER_ID, STANDBY_BUCKET_RESTRICTED,
                REASON_MAIN_FORCED_BY_SYSTEM);
        assertNotBucket(STANDBY_BUCKET_RESTRICTED);
        mController.setAppStandbyBucket(PACKAGE_1, USER_ID, STANDBY_BUCKET_RESTRICTED,
                REASON_MAIN_FORCED_BY_USER);
        assertNotBucket(STANDBY_BUCKET_RESTRICTED);
    }

    @Test
    public void testRestrictedBucket_EnabledToDisabled() {
        reportEvent(mController, USER_INTERACTION, mInjector.mElapsedRealtime, PACKAGE_1);
        mInjector.mElapsedRealtime += RESTRICTED_THRESHOLD;
        mController.setAppStandbyBucket(PACKAGE_1, USER_ID, STANDBY_BUCKET_RESTRICTED,
                REASON_MAIN_FORCED_BY_SYSTEM);
        assertBucket(STANDBY_BUCKET_RESTRICTED);

        mInjector.mIsRestrictedBucketEnabled = false;
        // Get the controller to read the new value. Capturing the ContentObserver isn't possible
        // at the moment.
        mController.onBootPhase(SystemService.PHASE_SYSTEM_SERVICES_READY);

        mController.checkIdleStates(USER_ID);
        assertNotBucket(STANDBY_BUCKET_RESTRICTED);
    }

    @Test
    public void testPredictionRaiseFromRestrictedTimeout_highBucket() {
        reportEvent(mController, USER_INTERACTION, mInjector.mElapsedRealtime, PACKAGE_1);

        // Way past all timeouts. App times out into RESTRICTED bucket.
        mInjector.mElapsedRealtime += RESTRICTED_THRESHOLD * 4;
        mController.checkIdleStates(USER_ID);
        assertBucket(STANDBY_BUCKET_RESTRICTED);

        // Since the app timed out into RESTRICTED, prediction should be able to remove from the
        // bucket.
        mInjector.mElapsedRealtime += RESTRICTED_THRESHOLD;
        mController.setAppStandbyBucket(PACKAGE_1, USER_ID, STANDBY_BUCKET_ACTIVE,
                REASON_MAIN_PREDICTED);
        assertBucket(STANDBY_BUCKET_ACTIVE);
    }

    @Test
    public void testPredictionRaiseFromRestrictedTimeout_lowBucket() {
        reportEvent(mController, USER_INTERACTION, mInjector.mElapsedRealtime, PACKAGE_1);

        // Way past all timeouts. App times out into RESTRICTED bucket.
        mInjector.mElapsedRealtime += RESTRICTED_THRESHOLD * 4;
        mController.checkIdleStates(USER_ID);
        assertBucket(STANDBY_BUCKET_RESTRICTED);

        // Prediction into a low bucket means no expectation of the app being used, so we shouldn't
        // elevate the app from RESTRICTED.
        mInjector.mElapsedRealtime += RESTRICTED_THRESHOLD;
        mController.setAppStandbyBucket(PACKAGE_1, USER_ID, STANDBY_BUCKET_RARE,
                REASON_MAIN_PREDICTED);
        assertBucket(STANDBY_BUCKET_RESTRICTED);
    }

    @Test
    public void testCascadingTimeouts() throws Exception {
        reportEvent(mController, USER_INTERACTION, 0, PACKAGE_1);
        assertBucket(STANDBY_BUCKET_ACTIVE);

        reportEvent(mController, NOTIFICATION_SEEN, 1000, PACKAGE_1);
        assertBucket(STANDBY_BUCKET_ACTIVE);

        mController.setAppStandbyBucket(PACKAGE_1, USER_ID, STANDBY_BUCKET_WORKING_SET,
                REASON_MAIN_PREDICTED);
        assertBucket(STANDBY_BUCKET_ACTIVE);

        mInjector.mElapsedRealtime = 2000 + mController.mStrongUsageTimeoutMillis;
        mController.setAppStandbyBucket(PACKAGE_1, USER_ID, STANDBY_BUCKET_FREQUENT,
                REASON_MAIN_PREDICTED);
        assertBucket(STANDBY_BUCKET_WORKING_SET);

        mInjector.mElapsedRealtime = 2000 + mController.mNotificationSeenTimeoutMillis;
        mController.setAppStandbyBucket(PACKAGE_1, USER_ID, STANDBY_BUCKET_FREQUENT,
                REASON_MAIN_PREDICTED);
        assertBucket(STANDBY_BUCKET_FREQUENT);
    }

    @Test
    public void testOverlappingTimeouts() throws Exception {
        reportEvent(mController, USER_INTERACTION, 0, PACKAGE_1);
        assertBucket(STANDBY_BUCKET_ACTIVE);

        reportEvent(mController, NOTIFICATION_SEEN, 1000, PACKAGE_1);
        assertBucket(STANDBY_BUCKET_ACTIVE);

        // Overlapping USER_INTERACTION before previous one times out
        reportEvent(mController, USER_INTERACTION, mController.mStrongUsageTimeoutMillis - 1000,
                PACKAGE_1);
        assertBucket(STANDBY_BUCKET_ACTIVE);

        // Still in ACTIVE after first USER_INTERACTION times out
        mInjector.mElapsedRealtime = mController.mStrongUsageTimeoutMillis + 1000;
        mController.setAppStandbyBucket(PACKAGE_1, USER_ID, STANDBY_BUCKET_FREQUENT,
                REASON_MAIN_PREDICTED);
        assertBucket(STANDBY_BUCKET_ACTIVE);

        // Both timed out, so NOTIFICATION_SEEN timeout should be effective
        mInjector.mElapsedRealtime = mController.mStrongUsageTimeoutMillis * 2 + 2000;
        mController.setAppStandbyBucket(PACKAGE_1, USER_ID, STANDBY_BUCKET_FREQUENT,
                REASON_MAIN_PREDICTED);
        assertBucket(STANDBY_BUCKET_WORKING_SET);

        mInjector.mElapsedRealtime = mController.mNotificationSeenTimeoutMillis + 2000;
        mController.setAppStandbyBucket(PACKAGE_1, USER_ID, STANDBY_BUCKET_RARE,
                REASON_MAIN_PREDICTED);
        assertBucket(STANDBY_BUCKET_RARE);
    }

    @Test
    public void testSystemInteractionTimeout() throws Exception {
        reportEvent(mController, USER_INTERACTION, 0, PACKAGE_1);
        // Fast forward to RARE
        mInjector.mElapsedRealtime = RARE_THRESHOLD + 100;
        mController.checkIdleStates(USER_ID);
        assertBucket(STANDBY_BUCKET_RARE);

        // Trigger a SYSTEM_INTERACTION and verify bucket
        reportEvent(mController, SYSTEM_INTERACTION, mInjector.mElapsedRealtime, PACKAGE_1);
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
    public void testInitialForegroundServiceTimeout() throws Exception {
        mInjector.mElapsedRealtime = 1 * RARE_THRESHOLD + 100;
        // Make sure app is in NEVER bucket
        mController.setAppStandbyBucket(PACKAGE_1, USER_ID, STANDBY_BUCKET_NEVER,
                REASON_MAIN_FORCED_BY_USER);
        mController.checkIdleStates(USER_ID);
        assertBucket(STANDBY_BUCKET_NEVER);

        mInjector.mElapsedRealtime += 100;

        // Trigger a FOREGROUND_SERVICE_START and verify bucket
        reportEvent(mController, FOREGROUND_SERVICE_START, mInjector.mElapsedRealtime, PACKAGE_1);
        mController.checkIdleStates(USER_ID);
        assertBucket(STANDBY_BUCKET_ACTIVE);

        // Verify it's still in ACTIVE close to end of timeout
        mInjector.mElapsedRealtime += mController.mInitialForegroundServiceStartTimeoutMillis - 100;
        mController.checkIdleStates(USER_ID);
        assertBucket(STANDBY_BUCKET_ACTIVE);

        // Verify bucket moves to RARE after timeout
        mInjector.mElapsedRealtime += 200;
        mController.checkIdleStates(USER_ID);
        assertBucket(STANDBY_BUCKET_RARE);

        // Trigger a FOREGROUND_SERVICE_START again
        reportEvent(mController, FOREGROUND_SERVICE_START, mInjector.mElapsedRealtime, PACKAGE_1);
        mController.checkIdleStates(USER_ID);
        // Bucket should not be immediately elevated on subsequent service starts
        assertBucket(STANDBY_BUCKET_RARE);
    }

    @Test
    public void testPredictionNotOverridden() throws Exception {
        reportEvent(mController, USER_INTERACTION, 0, PACKAGE_1);
        assertBucket(STANDBY_BUCKET_ACTIVE);

        mInjector.mElapsedRealtime = WORKING_SET_THRESHOLD - 1000;
        reportEvent(mController, NOTIFICATION_SEEN, mInjector.mElapsedRealtime, PACKAGE_1);
        assertBucket(STANDBY_BUCKET_ACTIVE);

        // Falls back to WORKING_SET
        mInjector.mElapsedRealtime += 5000;
        mController.checkIdleStates(USER_ID);
        assertBucket(STANDBY_BUCKET_WORKING_SET);

        // Predict to ACTIVE
        mInjector.mElapsedRealtime += 1000;
        mController.setAppStandbyBucket(PACKAGE_1, USER_ID, STANDBY_BUCKET_ACTIVE,
                REASON_MAIN_PREDICTED);
        assertBucket(STANDBY_BUCKET_ACTIVE);

        // CheckIdleStates should not change the prediction
        mInjector.mElapsedRealtime += 1000;
        mController.checkIdleStates(USER_ID);
        assertBucket(STANDBY_BUCKET_ACTIVE);
    }

    @Test
    public void testPredictionStrikesBack() throws Exception {
        reportEvent(mController, USER_INTERACTION, 0, PACKAGE_1);
        assertBucket(STANDBY_BUCKET_ACTIVE);

        // Predict to FREQUENT
        mInjector.mElapsedRealtime = RARE_THRESHOLD;
        mController.setAppStandbyBucket(PACKAGE_1, USER_ID, STANDBY_BUCKET_FREQUENT,
                REASON_MAIN_PREDICTED);
        assertBucket(STANDBY_BUCKET_FREQUENT);

        // Add a short timeout event
        mInjector.mElapsedRealtime += 1000;
        reportEvent(mController, SYSTEM_INTERACTION, mInjector.mElapsedRealtime, PACKAGE_1);
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
    public void testSystemForcedFlags_NotAddedForUserForce() throws Exception {
        final int expectedReason = REASON_MAIN_FORCED_BY_USER;
        mController.setAppStandbyBucket(PACKAGE_1, USER_ID, STANDBY_BUCKET_RESTRICTED,
                REASON_MAIN_FORCED_BY_USER);
        assertEquals(STANDBY_BUCKET_RESTRICTED, getStandbyBucket(mController, PACKAGE_1));
        assertEquals(expectedReason, getStandbyBucketReason(PACKAGE_1));

        mController.setAppStandbyBucket(PACKAGE_1, USER_ID, STANDBY_BUCKET_RESTRICTED,
                REASON_MAIN_FORCED_BY_SYSTEM | REASON_SUB_FORCED_SYSTEM_FLAG_ABUSE);
        assertEquals(STANDBY_BUCKET_RESTRICTED, getStandbyBucket(mController, PACKAGE_1));
        assertEquals(expectedReason, getStandbyBucketReason(PACKAGE_1));
    }

    @Test
    public void testSystemForcedFlags_AddedForSystemForce() throws Exception {
        mController.setAppStandbyBucket(PACKAGE_1, USER_ID, STANDBY_BUCKET_ACTIVE,
                REASON_MAIN_DEFAULT);
        mInjector.mElapsedRealtime += 4 * RESTRICTED_THRESHOLD;

        mController.setAppStandbyBucket(PACKAGE_1, USER_ID, STANDBY_BUCKET_RESTRICTED,
                REASON_MAIN_FORCED_BY_SYSTEM
                        | REASON_SUB_FORCED_SYSTEM_FLAG_BACKGROUND_RESOURCE_USAGE);
        assertEquals(STANDBY_BUCKET_RESTRICTED, getStandbyBucket(mController, PACKAGE_1));
        assertEquals(REASON_MAIN_FORCED_BY_SYSTEM
                        | REASON_SUB_FORCED_SYSTEM_FLAG_BACKGROUND_RESOURCE_USAGE,
                getStandbyBucketReason(PACKAGE_1));

        mController.restrictApp(PACKAGE_1, USER_ID, REASON_SUB_FORCED_SYSTEM_FLAG_ABUSE);
        assertEquals(STANDBY_BUCKET_RESTRICTED, getStandbyBucket(mController, PACKAGE_1));
        // Flags should be combined
        assertEquals(REASON_MAIN_FORCED_BY_SYSTEM
                | REASON_SUB_FORCED_SYSTEM_FLAG_BACKGROUND_RESOURCE_USAGE
                | REASON_SUB_FORCED_SYSTEM_FLAG_ABUSE, getStandbyBucketReason(PACKAGE_1));
    }

    @Test
    public void testSystemForcedFlags_SystemForceChangesBuckets() throws Exception {
        mController.setAppStandbyBucket(PACKAGE_1, USER_ID, STANDBY_BUCKET_ACTIVE,
                REASON_MAIN_DEFAULT);
        mInjector.mElapsedRealtime += 4 * RESTRICTED_THRESHOLD;

        mController.setAppStandbyBucket(PACKAGE_1, USER_ID, STANDBY_BUCKET_FREQUENT,
                REASON_MAIN_FORCED_BY_SYSTEM
                        | REASON_SUB_FORCED_SYSTEM_FLAG_BACKGROUND_RESOURCE_USAGE);
        assertEquals(STANDBY_BUCKET_FREQUENT, getStandbyBucket(mController, PACKAGE_1));
        assertEquals(REASON_MAIN_FORCED_BY_SYSTEM
                        | REASON_SUB_FORCED_SYSTEM_FLAG_BACKGROUND_RESOURCE_USAGE,
                getStandbyBucketReason(PACKAGE_1));

        mController.setAppStandbyBucket(PACKAGE_1, USER_ID, STANDBY_BUCKET_FREQUENT,
                REASON_MAIN_FORCED_BY_SYSTEM | REASON_SUB_FORCED_SYSTEM_FLAG_ABUSE);
        assertEquals(STANDBY_BUCKET_FREQUENT, getStandbyBucket(mController, PACKAGE_1));
        // Flags should be combined
        assertEquals(REASON_MAIN_FORCED_BY_SYSTEM
                        | REASON_SUB_FORCED_SYSTEM_FLAG_BACKGROUND_RESOURCE_USAGE
                        | REASON_SUB_FORCED_SYSTEM_FLAG_ABUSE,
                getStandbyBucketReason(PACKAGE_1));

        mController.setAppStandbyBucket(PACKAGE_1, USER_ID, STANDBY_BUCKET_RARE,
                REASON_MAIN_FORCED_BY_SYSTEM | REASON_SUB_FORCED_SYSTEM_FLAG_BUGGY);
        assertEquals(STANDBY_BUCKET_RARE, getStandbyBucket(mController, PACKAGE_1));
        // Flags should be combined
        assertEquals(REASON_MAIN_FORCED_BY_SYSTEM | REASON_SUB_FORCED_SYSTEM_FLAG_BUGGY,
                getStandbyBucketReason(PACKAGE_1));

        mController.restrictApp(PACKAGE_1, USER_ID, REASON_SUB_FORCED_SYSTEM_FLAG_UNDEFINED);
        assertEquals(STANDBY_BUCKET_RESTRICTED, getStandbyBucket(mController, PACKAGE_1));
        // Flags should not be combined since the bucket changed.
        assertEquals(REASON_MAIN_FORCED_BY_SYSTEM | REASON_SUB_FORCED_SYSTEM_FLAG_UNDEFINED,
                getStandbyBucketReason(PACKAGE_1));
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

    @Test
    public void testUserInteraction_CrossProfile() throws Exception {
        mInjector.mRunningUsers = new int[] {USER_ID, USER_ID2, USER_ID3};
        mInjector.mCrossProfileTargets = Arrays.asList(USER_HANDLE_USER2);
        reportEvent(mController, USER_INTERACTION, 0, PACKAGE_1);
        assertEquals("Cross profile connected package bucket should be elevated on usage",
                STANDBY_BUCKET_ACTIVE, getStandbyBucket(USER_ID2, mController, PACKAGE_1));
        assertEquals("Not Cross profile connected package bucket should not be elevated on usage",
                STANDBY_BUCKET_NEVER, getStandbyBucket(USER_ID3, mController, PACKAGE_1));

        assertTimeout(mController, WORKING_SET_THRESHOLD - 1, STANDBY_BUCKET_ACTIVE, USER_ID);
        assertTimeout(mController, WORKING_SET_THRESHOLD - 1, STANDBY_BUCKET_ACTIVE, USER_ID2);

        assertTimeout(mController, WORKING_SET_THRESHOLD + 1, STANDBY_BUCKET_WORKING_SET, USER_ID);
        assertTimeout(mController, WORKING_SET_THRESHOLD + 1, STANDBY_BUCKET_WORKING_SET, USER_ID2);

        mInjector.mCrossProfileTargets = Collections.emptyList();
        reportEvent(mController, USER_INTERACTION, 0, PACKAGE_1);
        assertEquals("No longer cross profile connected package bucket should not be "
                        + "elevated on usage",
                STANDBY_BUCKET_WORKING_SET, getStandbyBucket(USER_ID2, mController, PACKAGE_1));
    }

    @Test
    public void testUnexemptedSyncScheduled() throws Exception {
        rearmLatch(PACKAGE_1);
        mController.addListener(mListener);
        assertEquals("Test package did not start in the Never bucket", STANDBY_BUCKET_NEVER,
                getStandbyBucket(mController, PACKAGE_1));

        mController.postReportSyncScheduled(PACKAGE_1, USER_ID, false);
        mStateChangedLatch.await(100, TimeUnit.MILLISECONDS);
        assertEquals("Unexempted sync scheduled should bring the package out of the Never bucket",
                STANDBY_BUCKET_WORKING_SET, getStandbyBucket(mController, PACKAGE_1));

        setAndAssertBucket(PACKAGE_1, USER_ID, STANDBY_BUCKET_RARE, REASON_MAIN_FORCED_BY_SYSTEM);

        rearmLatch(PACKAGE_1);
        mController.postReportSyncScheduled(PACKAGE_1, USER_ID, false);
        mStateChangedLatch.await(100, TimeUnit.MILLISECONDS);
        assertEquals("Unexempted sync scheduled should not elevate a non Never bucket",
                STANDBY_BUCKET_RARE, getStandbyBucket(mController, PACKAGE_1));
    }

    @Test
    public void testExemptedSyncScheduled() throws Exception {
        setAndAssertBucket(PACKAGE_1, USER_ID, STANDBY_BUCKET_RARE, REASON_MAIN_FORCED_BY_SYSTEM);
        mInjector.mDeviceIdleMode = true;
        rearmLatch(PACKAGE_1);
        mController.postReportSyncScheduled(PACKAGE_1, USER_ID, true);
        mStateChangedLatch.await(100, TimeUnit.MILLISECONDS);
        assertEquals("Exempted sync scheduled in doze should set bucket to working set",
                STANDBY_BUCKET_WORKING_SET, getStandbyBucket(mController, PACKAGE_1));

        setAndAssertBucket(PACKAGE_1, USER_ID, STANDBY_BUCKET_RARE, REASON_MAIN_FORCED_BY_SYSTEM);
        mInjector.mDeviceIdleMode = false;
        rearmLatch(PACKAGE_1);
        mController.postReportSyncScheduled(PACKAGE_1, USER_ID, true);
        mStateChangedLatch.await(100, TimeUnit.MILLISECONDS);
        assertEquals("Exempted sync scheduled while not in doze should set bucket to active",
                STANDBY_BUCKET_ACTIVE, getStandbyBucket(mController, PACKAGE_1));
    }

    @Test
    public void testAppUpdateOnRestrictedBucketStatus() {
        // Updates shouldn't change bucket if the app timed out.
        // Way past all timeouts. App times out into RESTRICTED bucket.
        reportEvent(mController, USER_INTERACTION, mInjector.mElapsedRealtime, PACKAGE_1);
        mInjector.mElapsedRealtime += RESTRICTED_THRESHOLD * 4;
        mController.checkIdleStates(USER_ID);
        assertBucket(STANDBY_BUCKET_RESTRICTED);

        mController.maybeUnrestrictBuggyApp(PACKAGE_1, USER_ID);
        assertBucket(STANDBY_BUCKET_RESTRICTED);
        mController.maybeUnrestrictBuggyApp(PACKAGE_1, USER_ID2);
        assertBucket(STANDBY_BUCKET_RESTRICTED);
        mController.maybeUnrestrictBuggyApp("com.random.package", USER_ID);
        assertBucket(STANDBY_BUCKET_RESTRICTED);

        // Updates shouldn't change bucket if the app was forced by the system for a non-buggy
        // reason.
        reportEvent(mController, USER_INTERACTION, mInjector.mElapsedRealtime, PACKAGE_1);
        mInjector.mElapsedRealtime += RESTRICTED_THRESHOLD * 4;
        mController.setAppStandbyBucket(PACKAGE_1, USER_ID, STANDBY_BUCKET_RESTRICTED,
                REASON_MAIN_FORCED_BY_SYSTEM
                        | REASON_SUB_FORCED_SYSTEM_FLAG_BACKGROUND_RESOURCE_USAGE);

        mController.maybeUnrestrictBuggyApp(PACKAGE_1, USER_ID);
        assertBucket(STANDBY_BUCKET_RESTRICTED);
        mController.maybeUnrestrictBuggyApp(PACKAGE_1, USER_ID2);
        assertBucket(STANDBY_BUCKET_RESTRICTED);
        mController.maybeUnrestrictBuggyApp("com.random.package", USER_ID);
        assertBucket(STANDBY_BUCKET_RESTRICTED);

        // Updates should change bucket if the app was forced by the system for a buggy reason.
        reportEvent(mController, USER_INTERACTION, mInjector.mElapsedRealtime, PACKAGE_1);
        mInjector.mElapsedRealtime += RESTRICTED_THRESHOLD * 4;
        mController.setAppStandbyBucket(PACKAGE_1, USER_ID, STANDBY_BUCKET_RESTRICTED,
                REASON_MAIN_FORCED_BY_SYSTEM | REASON_SUB_FORCED_SYSTEM_FLAG_BUGGY);

        mController.maybeUnrestrictBuggyApp(PACKAGE_1, USER_ID2);
        assertBucket(STANDBY_BUCKET_RESTRICTED);
        mController.maybeUnrestrictBuggyApp("com.random.package", USER_ID);
        assertBucket(STANDBY_BUCKET_RESTRICTED);
        mController.maybeUnrestrictBuggyApp(PACKAGE_1, USER_ID);
        assertNotEquals(STANDBY_BUCKET_RESTRICTED, getStandbyBucket(mController, PACKAGE_1));

        // Updates shouldn't change bucket if the app was forced by the system for more than just
        // a buggy reason.
        reportEvent(mController, USER_INTERACTION, mInjector.mElapsedRealtime, PACKAGE_1);
        mInjector.mElapsedRealtime += RESTRICTED_THRESHOLD * 4;
        mController.setAppStandbyBucket(PACKAGE_1, USER_ID, STANDBY_BUCKET_RESTRICTED,
                REASON_MAIN_FORCED_BY_SYSTEM | REASON_SUB_FORCED_SYSTEM_FLAG_ABUSE
                        | REASON_SUB_FORCED_SYSTEM_FLAG_BUGGY);

        mController.maybeUnrestrictBuggyApp(PACKAGE_1, USER_ID);
        assertBucket(STANDBY_BUCKET_RESTRICTED);
        assertEquals(REASON_MAIN_FORCED_BY_SYSTEM | REASON_SUB_FORCED_SYSTEM_FLAG_ABUSE,
                getStandbyBucketReason(PACKAGE_1));
        mController.maybeUnrestrictBuggyApp(PACKAGE_1, USER_ID2);
        assertBucket(STANDBY_BUCKET_RESTRICTED);
        mController.maybeUnrestrictBuggyApp("com.random.package", USER_ID);
        assertBucket(STANDBY_BUCKET_RESTRICTED);

        // Updates shouldn't change bucket if the app was forced by the user.
        reportEvent(mController, USER_INTERACTION, mInjector.mElapsedRealtime, PACKAGE_1);
        mInjector.mElapsedRealtime += RESTRICTED_THRESHOLD * 4;
        mController.setAppStandbyBucket(PACKAGE_1, USER_ID, STANDBY_BUCKET_RESTRICTED,
                REASON_MAIN_FORCED_BY_USER);

        mController.maybeUnrestrictBuggyApp(PACKAGE_1, USER_ID);
        assertBucket(STANDBY_BUCKET_RESTRICTED);
        mController.maybeUnrestrictBuggyApp(PACKAGE_1, USER_ID2);
        assertBucket(STANDBY_BUCKET_RESTRICTED);
        mController.maybeUnrestrictBuggyApp("com.random.package", USER_ID);
        assertBucket(STANDBY_BUCKET_RESTRICTED);
    }

    @Test
    public void testSystemHeadlessAppElevated() {
        reportEvent(mController, USER_INTERACTION, mInjector.mElapsedRealtime, PACKAGE_1);
        reportEvent(mController, USER_INTERACTION, mInjector.mElapsedRealtime,
                PACKAGE_SYSTEM_HEADFULL);
        reportEvent(mController, USER_INTERACTION, mInjector.mElapsedRealtime,
                PACKAGE_SYSTEM_HEADLESS);
        mInjector.mElapsedRealtime += RESTRICTED_THRESHOLD;


        mController.setAppStandbyBucket(PACKAGE_SYSTEM_HEADFULL, USER_ID, STANDBY_BUCKET_RARE,
                REASON_MAIN_TIMEOUT);
        assertBucket(STANDBY_BUCKET_RARE, PACKAGE_SYSTEM_HEADFULL);

        // Make sure headless system apps don't get lowered.
        mController.setAppStandbyBucket(PACKAGE_SYSTEM_HEADLESS, USER_ID, STANDBY_BUCKET_RARE,
                REASON_MAIN_TIMEOUT);
        assertBucket(STANDBY_BUCKET_ACTIVE, PACKAGE_SYSTEM_HEADLESS);

        // Package 1 doesn't have activities and is headless, but is not a system app, so it can
        // be lowered.
        mController.setAppStandbyBucket(PACKAGE_1, USER_ID, STANDBY_BUCKET_RARE,
                REASON_MAIN_TIMEOUT);
        assertBucket(STANDBY_BUCKET_RARE, PACKAGE_1);
    }

    @Test
    public void testWellbeingAppElevated() {
        reportEvent(mController, USER_INTERACTION, mInjector.mElapsedRealtime, PACKAGE_WELLBEING);
        assertBucket(STANDBY_BUCKET_ACTIVE, PACKAGE_WELLBEING);
        reportEvent(mController, USER_INTERACTION, mInjector.mElapsedRealtime, PACKAGE_1);
        assertBucket(STANDBY_BUCKET_ACTIVE, PACKAGE_1);
        mInjector.mElapsedRealtime += RESTRICTED_THRESHOLD;

        // Make sure the default wellbeing app does not get lowered below WORKING_SET.
        mController.setAppStandbyBucket(PACKAGE_WELLBEING, USER_ID, STANDBY_BUCKET_RARE,
                REASON_MAIN_TIMEOUT);
        assertBucket(STANDBY_BUCKET_WORKING_SET, PACKAGE_WELLBEING);

        // A non default wellbeing app should be able to fall lower than WORKING_SET.
        mController.setAppStandbyBucket(PACKAGE_1, USER_ID, STANDBY_BUCKET_RARE,
                REASON_MAIN_TIMEOUT);
        assertBucket(STANDBY_BUCKET_RARE, PACKAGE_1);
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

    private void setAndAssertBucket(String pkg, int user, int bucket, int reason) throws Exception {
        rearmLatch(pkg);
        mController.setAppStandbyBucket(pkg, user, bucket, reason);
        mStateChangedLatch.await(100, TimeUnit.MILLISECONDS);
        assertEquals("Failed to set package bucket", bucket,
                getStandbyBucket(mController, PACKAGE_1));
    }

    private void rearmLatch(String pkgName) {
        mLatchPkgName = pkgName;
        mStateChangedLatch = new CountDownLatch(1);
    }

    private void rearmLatch() {
        rearmLatch(null);
    }
}
