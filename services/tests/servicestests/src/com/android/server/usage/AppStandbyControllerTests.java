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
import static android.app.usage.UsageEvents.Event.USER_INTERACTION;
import static android.app.usage.UsageStatsManager.STANDBY_BUCKET_ACTIVE;
import static android.app.usage.UsageStatsManager.STANDBY_BUCKET_FREQUENT;
import static android.app.usage.UsageStatsManager.STANDBY_BUCKET_RARE;
import static android.app.usage.UsageStatsManager.STANDBY_BUCKET_WORKING_SET;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import static org.mockito.Matchers.anyInt;
import static org.mockito.Matchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
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
import android.support.test.InstrumentationRegistry;
import android.support.test.runner.AndroidJUnit4;
import android.view.Display;

import com.android.server.SystemService;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Unit test for AppStandbyController.
 */
@RunWith(AndroidJUnit4.class)
public class AppStandbyControllerTests {

    private static final String PACKAGE_1 = "com.example.foo";
    private static final int UID_1 = 10000;
    private static final int USER_ID = 0;

    private static final long MINUTE_MS = 60 * 1000;
    private static final long HOUR_MS = 60 * MINUTE_MS;
    private static final long DAY_MS = 24 * HOUR_MS;

    private static final long WORKING_SET_THRESHOLD = 12 * HOUR_MS;
    private static final long FREQUENT_THRESHOLD = 24 * HOUR_MS;
    private static final long RARE_THRESHOLD = 48 * HOUR_MS;

    private MyInjector mInjector;

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
        boolean mIsCharging;
        List<String> mPowerSaveWhitelistExceptIdle = new ArrayList<>();
        boolean mDisplayOn;
        DisplayManager.DisplayListener mDisplayListener;
        String mBoundWidgetPackage;

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
            return true;
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
                    + RARE_THRESHOLD;
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

        doReturn(packages).when(mockPm).getInstalledPackagesAsUser(anyInt(), anyInt());
        try {
            doReturn(UID_1).when(mockPm).getPackageUidAsUser(anyString(), anyInt(), anyInt());
            doReturn(pi.applicationInfo).when(mockPm).getApplicationInfo(anyString(), anyInt());
        } catch (PackageManager.NameNotFoundException nnfe) {}
    }

    private void setChargingState(AppStandbyController controller, boolean charging) {
        mInjector.mIsCharging = charging;
        if (controller != null) {
            controller.setChargingState(charging);
        }
    }

    private AppStandbyController setupController() throws Exception {
        mInjector.mElapsedRealtime = 0;
        AppStandbyController controller = new AppStandbyController(mInjector);
        controller.onBootPhase(SystemService.PHASE_SYSTEM_SERVICES_READY);
        controller.onBootPhase(SystemService.PHASE_BOOT_COMPLETED);
        mInjector.setDisplayOn(false);
        mInjector.setDisplayOn(true);
        setChargingState(controller, false);
        setupPm(mInjector.getContext().getPackageManager());
        controller.checkIdleStates(USER_ID);

        return controller;
    }

    @Before
    public void setUp() throws Exception {
        MyContextWrapper myContext = new MyContextWrapper(InstrumentationRegistry.getContext());
        mInjector = new MyInjector(myContext, Looper.getMainLooper());
    }

    @Test
    public void testCharging() throws Exception {
        AppStandbyController controller = setupController();

        setChargingState(controller, true);
        mInjector.mElapsedRealtime = RARE_THRESHOLD + 1;
        assertFalse(controller.isAppIdleFilteredOrParoled(PACKAGE_1, USER_ID,
                mInjector.mElapsedRealtime, false));

        setChargingState(controller, false);
        mInjector.mElapsedRealtime = 2 * RARE_THRESHOLD + 2;
        controller.checkIdleStates(USER_ID);
        assertTrue(controller.isAppIdleFilteredOrParoled(PACKAGE_1, USER_ID,
                mInjector.mElapsedRealtime, false));
        setChargingState(controller, true);
        assertFalse(controller.isAppIdleFilteredOrParoled(PACKAGE_1,USER_ID,
                mInjector.mElapsedRealtime, false));
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
        UsageEvents.Event ev = new UsageEvents.Event();
        ev.mPackage = PACKAGE_1;
        ev.mEventType = eventType;
        controller.reportEvent(ev, elapsedTime, USER_ID);
    }

    private int getStandbyBucket(AppStandbyController controller) {
        return controller.getAppStandbyBucket(PACKAGE_1, USER_ID, mInjector.mElapsedRealtime,
                true);
    }

    @Test
    public void testBuckets() throws Exception {
        AppStandbyController controller = setupController();

        assertTimeout(controller, 0, UsageStatsManager.STANDBY_BUCKET_NEVER);

        reportEvent(controller, USER_INTERACTION, 0);

        // ACTIVE bucket
        assertTimeout(controller, WORKING_SET_THRESHOLD - 1, STANDBY_BUCKET_ACTIVE);

        // WORKING_SET bucket
        assertTimeout(controller, WORKING_SET_THRESHOLD + 1, STANDBY_BUCKET_WORKING_SET);

        // WORKING_SET bucket
        assertTimeout(controller, FREQUENT_THRESHOLD - 1, STANDBY_BUCKET_WORKING_SET);

        // FREQUENT bucket
        assertTimeout(controller, FREQUENT_THRESHOLD + 1, STANDBY_BUCKET_FREQUENT);

        // RARE bucket
        assertTimeout(controller, RARE_THRESHOLD + 1, STANDBY_BUCKET_RARE);

        reportEvent(controller, USER_INTERACTION, RARE_THRESHOLD + 1);

        assertTimeout(controller, RARE_THRESHOLD + 1, STANDBY_BUCKET_ACTIVE);

        // RARE bucket
        assertTimeout(controller, RARE_THRESHOLD * 2 + 2, STANDBY_BUCKET_RARE);
    }

    @Test
    public void testScreenTimeAndBuckets() throws Exception {
        AppStandbyController controller = setupController();
        mInjector.setDisplayOn(false);

        assertTimeout(controller, 0, UsageStatsManager.STANDBY_BUCKET_NEVER);

        reportEvent(controller, USER_INTERACTION, 0);

        // ACTIVE bucket
        assertTimeout(controller, WORKING_SET_THRESHOLD - 1, STANDBY_BUCKET_ACTIVE);

        // WORKING_SET bucket
        assertTimeout(controller, WORKING_SET_THRESHOLD + 1, STANDBY_BUCKET_WORKING_SET);

        // RARE bucket, should fail because the screen wasn't ON.
        mInjector.mElapsedRealtime = RARE_THRESHOLD + 1;
        controller.checkIdleStates(USER_ID);
        assertNotEquals(STANDBY_BUCKET_RARE, getStandbyBucket(controller));

        mInjector.setDisplayOn(true);
        assertTimeout(controller, RARE_THRESHOLD * 2 + 2, STANDBY_BUCKET_RARE);
    }

    @Test
    public void testForcedIdle() throws Exception {
        AppStandbyController controller = setupController();
        setChargingState(controller, false);

        controller.forceIdleState(PACKAGE_1, USER_ID, true);
        assertEquals(STANDBY_BUCKET_RARE, getStandbyBucket(controller));
        assertTrue(controller.isAppIdleFiltered(PACKAGE_1, UID_1, USER_ID, 0));

        controller.forceIdleState(PACKAGE_1, USER_ID, false);
        assertEquals(STANDBY_BUCKET_ACTIVE, controller.getAppStandbyBucket(PACKAGE_1, USER_ID, 0,
                true));
        assertFalse(controller.isAppIdleFiltered(PACKAGE_1, UID_1, USER_ID, 0));
    }

    @Test
    public void testNotificationEvent() throws Exception {
        AppStandbyController controller = setupController();
        setChargingState(controller, false);

        reportEvent(controller, USER_INTERACTION, 0);
        assertEquals(STANDBY_BUCKET_ACTIVE, getStandbyBucket(controller));
        mInjector.mElapsedRealtime = 1;
        reportEvent(controller, NOTIFICATION_SEEN, mInjector.mElapsedRealtime);
        assertEquals(STANDBY_BUCKET_ACTIVE, getStandbyBucket(controller));

        controller.forceIdleState(PACKAGE_1, USER_ID, true);
        reportEvent(controller, NOTIFICATION_SEEN, mInjector.mElapsedRealtime);
        assertEquals(STANDBY_BUCKET_WORKING_SET, getStandbyBucket(controller));
    }
}
