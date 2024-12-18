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
import static android.content.pm.PackageManager.PERMISSION_DENIED;
import static android.content.pm.PackageManager.PERMISSION_GRANTED;

import static com.android.server.usage.AppStandbyController.DEFAULT_ELAPSED_TIME_THRESHOLDS;
import static com.android.server.usage.AppStandbyController.DEFAULT_SCREEN_TIME_THRESHOLDS;
import static com.android.server.usage.AppStandbyController.MINIMUM_ELAPSED_TIME_THRESHOLDS;
import static com.android.server.usage.AppStandbyController.MINIMUM_SCREEN_TIME_THRESHOLDS;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeTrue;
import static org.mockito.AdditionalMatchers.not;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyInt;
import static org.mockito.Matchers.intThat;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import android.annotation.DurationMillisLong;
import android.annotation.NonNull;
import android.app.ActivityManager;
import android.app.usage.AppStandbyInfo;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.app.usage.UsageStatsManagerInternal;
import android.appwidget.AppWidgetManager;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManagerInternal;
import android.content.pm.ResolveInfo;
import android.hardware.display.DisplayManager;
import android.os.BatteryStats;
import android.os.Handler;
import android.os.Looper;
import android.os.RemoteException;
import android.os.UserHandle;
import android.platform.test.annotations.DisableFlags;
import android.platform.test.annotations.EnableFlags;
import android.platform.test.annotations.Presubmit;
import android.platform.test.flag.junit.SetFlagsRule;
import android.provider.DeviceConfig;
import android.util.ArraySet;
import android.util.Pair;
import android.view.Display;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.internal.util.ArrayUtils;
import com.android.server.LocalServices;
import com.android.server.SystemService;
import com.android.server.usage.AppStandbyInternal.AppIdleStateChangeListener;

import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;

/**
 * Unit test for AppStandbyController.
 */
@RunWith(AndroidJUnit4.class)
@Presubmit
@SmallTest
public class AppStandbyControllerTests {

    private static final String PACKAGE_1 = "com.example.foo.1";
    private static final int UID_1 = 10000;
    private static final String PACKAGE_2 = "com.example.foo.2";
    private static final int UID_2 = 20000;
    private static final String PACKAGE_EXEMPTED_1 = "com.android.exempted";
    private static final int UID_EXEMPTED_1 = 10001;
    private static final String PACKAGE_SYSTEM_HEADFULL = "com.example.system.headfull";
    private static final int UID_SYSTEM_HEADFULL = 10002;
    private static final String PACKAGE_SYSTEM_HEADLESS = "com.example.system.headless";
    private static final int UID_SYSTEM_HEADLESS = 10003;
    private static final String PACKAGE_WELLBEING = "com.example.wellbeing";
    private static final int UID_WELLBEING = 10004;
    private static final String PACKAGE_BACKGROUND_LOCATION = "com.example.backgroundLocation";
    private static final int UID_BACKGROUND_LOCATION = 10005;
    private static final int USER_ID = 0;
    private static final int USER_ID2 = 10;
    private static final UserHandle USER_HANDLE_USER2 = new UserHandle(USER_ID2);
    private static final int USER_ID3 = 11;

    private static final String PACKAGE_UNKNOWN = "com.example.unknown";

    private static final String ADMIN_PKG = "com.android.admin";
    private static final String ADMIN_PKG2 = "com.android.admin2";
    private static final String ADMIN_PKG3 = "com.android.admin3";

    private static final String ADMIN_PROTECTED_PKG = "com.android.admin.protected";
    private static final String ADMIN_PROTECTED_PKG2 = "com.android.admin.protected2";

    private static final long MINUTE_MS = 60 * 1000;
    private static final long HOUR_MS = 60 * MINUTE_MS;
    private static final long DAY_MS = 24 * HOUR_MS;

    private static final long WORKING_SET_THRESHOLD = 12 * HOUR_MS;
    private static final long FREQUENT_THRESHOLD = 24 * HOUR_MS;
    private static final long RARE_THRESHOLD = 48 * HOUR_MS;
    private static final long RESTRICTED_THRESHOLD = 96 * HOUR_MS;

    private static final int ASSERT_RETRY_ATTEMPTS = 20;
    private static final int ASSERT_RETRY_DELAY_MILLISECONDS = 500;

    @DurationMillisLong
    private static final long FLUSH_TIMEOUT_MILLISECONDS = 5_000;

    /** Mock variable used in {@link MyInjector#isPackageInstalled(String, int, int)} */
    private static boolean isPackageInstalled = true;

    private static final Random sRandom = new Random();

    @Rule
    public final SetFlagsRule mSetFlagsRule = new SetFlagsRule();

    private MyInjector mInjector;
    private AppStandbyController mController;

    private CountDownLatch mStateChangedLatch = new CountDownLatch(1);
    private CountDownLatch mQuotaBumpLatch = new CountDownLatch(1);
    private String mLatchPkgName = null;
    private int mLatchUserId = -1;
    private AppIdleStateChangeListener mListener = new AppIdleStateChangeListener() {
        @Override
        public void onAppIdleStateChanged(String packageName, int userId,
                boolean idle, int bucket, int reason) {
            // Ignore events not related to mLatchPkgName, if set.
            if (mLatchPkgName != null && !mLatchPkgName.equals(packageName)) return;
            mStateChangedLatch.countDown();
        }

        @Override
        public void triggerTemporaryQuotaBump(String packageName, int userId) {
            // Ignore events not related to mLatchPkgName, if set.
            if ((mLatchPkgName != null && !mLatchPkgName.equals(packageName))
                    || (mLatchUserId != -1 && mLatchUserId != userId)) {
                return;
            }
            mQuotaBumpLatch.countDown();
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
        @Mock
        private PackageManagerInternal mPackageManagerInternal;
        long mElapsedRealtime;
        boolean mIsAppIdleEnabled = true;
        boolean mIsCharging;
        List<String> mNonIdleWhitelistApps = new ArrayList<>();
        boolean mDisplayOn;
        DisplayManager.DisplayListener mDisplayListener;
        String mBoundWidgetPackage = PACKAGE_EXEMPTED_1;
        int[] mRunningUsers = new int[] {USER_ID};
        List<UserHandle> mCrossProfileTargets = Collections.emptyList();
        boolean mDeviceIdleMode = false;
        Set<Pair<String, Integer>> mClockApps = new ArraySet<>();
        DeviceConfig.Properties.Builder mSettingsBuilder =
                new DeviceConfig.Properties.Builder(DeviceConfig.NAMESPACE_APP_STANDBY)
                        .setLong("screen_threshold_active", 0)
                        .setLong("screen_threshold_working_set", 0)
                        .setLong("screen_threshold_frequent", 0)
                        .setLong("screen_threshold_rare", HOUR_MS)
                        // screen_threshold_restricted intentionally skipped
                        .setLong("elapsed_threshold_active", 0)
                        .setLong("elapsed_threshold_working_set", WORKING_SET_THRESHOLD)
                        .setLong("elapsed_threshold_frequent", FREQUENT_THRESHOLD)
                        .setLong("elapsed_threshold_rare", RARE_THRESHOLD)
                        .setLong("elapsed_threshold_restricted", RESTRICTED_THRESHOLD);
        DeviceConfig.OnPropertiesChangedListener mPropertiesChangedListener;
        String mExpectedNoteEventPackage = null;
        int mLastNoteEvent = BatteryStats.HistoryItem.EVENT_NONE;

        MyInjector(Context context, Looper looper) {
            super(context, looper);
            MockitoAnnotations.initMocks(this);
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
        boolean shouldGetExactAlarmBucketElevation(String packageName, int uid) {
            return mClockApps.contains(Pair.create(packageName, uid));
        }

        @Override
        PackageManagerInternal getPackageManagerInternal() {
            return mPackageManagerInternal;
        }

        @Override
        void updatePowerWhitelistCache() {
        }

        @Override
        File getDataSystemDirectory() {
            return new File(getContext().getFilesDir(), Long.toString(sRandom.nextLong()));
        }

        @Override
        void noteEvent(int event, String packageName, int uid) throws RemoteException {
            if (Objects.equals(mExpectedNoteEventPackage, packageName)) {
                mLastNoteEvent = event;
            }
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
        @NonNull
        DeviceConfig.Properties getDeviceConfigProperties(String... keys) {
            return mSettingsBuilder.build();
        }

        @Override
        public boolean isDeviceIdleMode() {
            return mDeviceIdleMode;
        }

        @Override
        public List<UserHandle> getValidCrossProfileTargets(String pkg, int userId) {
            return mCrossProfileTargets;
        }

        @Override
        public void registerDeviceConfigPropertiesChangedListener(
                @NonNull DeviceConfig.OnPropertiesChangedListener listener) {
            mPropertiesChangedListener = listener;
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

        PackageInfo pInfo = new PackageInfo();
        pInfo.applicationInfo = new ApplicationInfo();
        pInfo.applicationInfo.uid = UID_2;
        pInfo.packageName = PACKAGE_2;
        packages.add(pInfo);

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

        PackageInfo pib = new PackageInfo();
        pib.applicationInfo = new ApplicationInfo();
        pib.applicationInfo.uid = UID_BACKGROUND_LOCATION;
        pib.packageName = PACKAGE_BACKGROUND_LOCATION;
        packages.add(pib);


        // Set up getInstalledPackagesAsUser().
        doReturn(packages).when(mockPm).getInstalledPackagesAsUser(anyInt(),
                anyInt());

        // Set up getInstalledPackagesAsUser() for "MATCH_ONLY_SYSTEM"
        doReturn(
                packages.stream().filter(pinfo -> pinfo.applicationInfo.isSystemApp())
                .collect(Collectors.toList())
        ).when(mockPm).getInstalledPackagesAsUser(
                intThat(i -> (i & PackageManager.MATCH_SYSTEM_ONLY) != 0),
                anyInt());

        // Set up queryIntentActivitiesAsUser()
        final ArrayList<ResolveInfo> systemFrontDoorActivities = new ArrayList<>();
        final ResolveInfo frontDoorActivity = new ResolveInfo();
        frontDoorActivity.activityInfo = new ActivityInfo();
        frontDoorActivity.activityInfo.packageName = pis.packageName;
        systemFrontDoorActivities.add(frontDoorActivity);
        doReturn(systemFrontDoorActivities).when(mockPm)
                .queryIntentActivitiesAsUser(any(Intent.class),
                intThat(i -> (i & PackageManager.MATCH_SYSTEM_ONLY) != 0),
                anyInt());

        // Set up other APIs.
        try {
            for (int i = 0; i < packages.size(); ++i) {
                PackageInfo pkg = packages.get(i);

                doReturn(pkg.applicationInfo.uid).when(mockPm)
                        .getPackageUidAsUser(eq(pkg.packageName), anyInt());
                doReturn(pkg.applicationInfo.uid).when(mockPm)
                        .getPackageUidAsUser(eq(pkg.packageName), anyInt(), anyInt());
                doReturn(pkg.applicationInfo).when(mockPm)
                        .getApplicationInfo(eq(pkg.packageName), anyInt());

                if (pkg.packageName.equals(PACKAGE_BACKGROUND_LOCATION)) {
                    doReturn(PERMISSION_GRANTED).when(mockPm).checkPermission(
                            eq(android.Manifest.permission.ACCESS_BACKGROUND_LOCATION),
                            eq(pkg.packageName));
                    doReturn(PERMISSION_DENIED).when(mockPm).checkPermission(
                            not(eq(android.Manifest.permission.ACCESS_BACKGROUND_LOCATION)),
                            eq(pkg.packageName));
                } else {
                    doReturn(PERMISSION_DENIED).when(mockPm).checkPermission(anyString(),
                            eq(pkg.packageName));
                }
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

    private void setupInitialUsageHistory() throws Exception {
        final int[] userIds = new int[] { USER_ID, USER_ID2, USER_ID3 };
        final String[] packages = new String[] {
                PACKAGE_1,
                PACKAGE_2,
                PACKAGE_EXEMPTED_1,
                PACKAGE_SYSTEM_HEADFULL,
                PACKAGE_SYSTEM_HEADLESS,
                PACKAGE_WELLBEING,
                PACKAGE_BACKGROUND_LOCATION,
                ADMIN_PKG,
                ADMIN_PKG2,
                ADMIN_PKG3
        };
        for (int userId : userIds) {
            for (String pkg : packages) {
                final AppIdleHistory.AppUsageHistory usageHistory = mController
                        .getAppIdleHistoryForTest().getAppUsageHistory(
                                pkg, userId, mInjector.mElapsedRealtime);
                usageHistory.lastUsedElapsedTime = 0;
                usageHistory.lastUsedByUserElapsedTime = 0;
                usageHistory.lastUsedScreenTime = 0;
            }
        }
    }

    @Before
    public void setUp() throws Exception {
        LocalServices.removeServiceForTest(UsageStatsManagerInternal.class);
        LocalServices.addService(
                UsageStatsManagerInternal.class, mock(UsageStatsManagerInternal.class));
        MyContextWrapper myContext = new MyContextWrapper(InstrumentationRegistry.getContext());
        mInjector = new MyInjector(myContext, Looper.getMainLooper());
        mController = setupController();
        setupInitialUsageHistory();
        flushHandler(mController);
    }

    @After
    public void tearDown() {
        LocalServices.removeServiceForTest(UsageStatsManagerInternal.class);
    }

    @Test
    public void testBoundWidgetPackageExempt() throws Exception {
        assumeTrue(mInjector.getContext().getSystemService(AppWidgetManager.class) != null);
        waitAndAssertBucket(STANDBY_BUCKET_ACTIVE, PACKAGE_EXEMPTED_1);
    }

    @Test
    public void testGetIdleUidsForUser() {
        final AppStandbyController controllerUnderTest = spy(mController);

        final int userIdForTest = 325;
        final int[] uids = new int[]{129, 23, 129, 129, 44, 23, 41, 751};
        final boolean[] idle = new boolean[]{true, true, false, true, false, true, false, true};
        // Based on uids[] and idle[], the only two uids that have all true's in idle[].
        final int[] expectedIdleUids = new int[]{23, 751};

        final List<ApplicationInfo> installedApps = new ArrayList<>();
        for (int i = 0; i < uids.length; i++) {
            final ApplicationInfo ai = mock(ApplicationInfo.class);
            ai.uid = uids[i];
            ai.packageName = "example.package.name." + i;
            installedApps.add(ai);
            when(controllerUnderTest.isAppIdleFiltered(eq(ai.packageName),
                    eq(UserHandle.getAppId(ai.uid)), eq(userIdForTest), anyLong()))
                    .thenReturn(idle[i]);
        }
        when(mInjector.mPackageManagerInternal.getInstalledApplications(anyLong(),
                eq(userIdForTest), anyInt())).thenReturn(installedApps);
        final int[] returnedIdleUids = controllerUnderTest.getIdleUidsForUser(userIdForTest);

        assertEquals(expectedIdleUids.length, returnedIdleUids.length);
        for (final int uid : expectedIdleUids) {
            assertTrue("Idle uid: " + uid + " not found in result: " + Arrays.toString(
                    returnedIdleUids), ArrayUtils.contains(returnedIdleUids, uid));
        }
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
        waitAndAssertBucket(STANDBY_BUCKET_RARE, PACKAGE_1);
        assertTrue(mController.isAppIdleFiltered(PACKAGE_1, UID_1, USER_ID, 0));
        assertTrue(mController.isAppIdleFiltered(PACKAGE_1, UID_1, USER_ID, false));
        assertFalse(mController.isInParole());

        paroleListener.rearmLatch(true);
        setChargingState(mController, true);
        paroleListener.awaitOnLatch(2000);
        assertTrue(paroleListener.getParoleState());
        waitAndAssertBucket(STANDBY_BUCKET_RARE, PACKAGE_1);
        assertFalse(mController.isAppIdleFiltered(PACKAGE_1, UID_1, USER_ID, 0));
        assertFalse(mController.isAppIdleFiltered(PACKAGE_1, UID_1, USER_ID, false));
        assertTrue(mController.isInParole());

        paroleListener.rearmLatch(false);
        setChargingState(mController, false);
        paroleListener.awaitOnLatch(2000);
        assertFalse(paroleListener.getParoleState());
        waitAndAssertBucket(STANDBY_BUCKET_RARE, PACKAGE_1);
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
        waitAndAssertBucket(STANDBY_BUCKET_RARE, PACKAGE_1);
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
        waitAndAssertBucket(STANDBY_BUCKET_RARE, PACKAGE_1);
        assertTrue(mController.isAppIdleFiltered(PACKAGE_1, UID_1, USER_ID, 0));
        assertTrue(mController.isAppIdleFiltered(PACKAGE_1, UID_1, USER_ID, false));
    }

    private void assertTimeout(AppStandbyController controller, long elapsedTime, int bucket) {
        assertTimeout(controller, elapsedTime, bucket, USER_ID);
    }

    private void assertTimeout(AppStandbyController controller, long elapsedTime, int bucket,
            int userId) {
        mInjector.mElapsedRealtime = elapsedTime;
        flushHandler(controller);
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
        controller.onUsageEvent(USER_ID, ev);
    }

    private int getStandbyBucket(AppStandbyController controller, String packageName) {
        return getStandbyBucket(USER_ID, controller, packageName);
    }

    private int getStandbyBucket(int userId, AppStandbyController controller, String packageName) {
        flushHandler(controller);
        return controller.getAppStandbyBucket(packageName, userId, mInjector.mElapsedRealtime,
                true);
    }

    private List<AppStandbyInfo> getStandbyBuckets(int userId) {
        flushHandler(mController);
        return mController.getAppStandbyBuckets(userId);
    }

    private int getStandbyBucketReason(String packageName) {
        flushHandler(mController);
        return mController.getAppStandbyBucketReason(packageName, USER_ID,
                mInjector.mElapsedRealtime);
    }

    private void waitAndAssertBucket(int bucket, String pkg) {
        waitAndAssertBucket(mController, bucket, pkg);
    }

    private void waitAndAssertBucket(AppStandbyController controller, int bucket, String pkg) {
        StringBuilder sb = new StringBuilder();
        sb.append(pkg);
        sb.append(" was not in the ");
        sb.append(UsageStatsManager.standbyBucketToString(bucket));
        sb.append(" (");
        sb.append(bucket);
        sb.append(") bucket.");
        waitAndAssertBucket(sb.toString(), controller, bucket, pkg);
    }

    private void waitAndAssertBucket(String msg, int bucket, String pkg) {
        waitAndAssertBucket(msg, mController, bucket, pkg);
    }

    private void waitAndAssertBucket(String msg, AppStandbyController controller, int bucket,
            String pkg) {
        waitAndAssertBucket(msg, controller, bucket, USER_ID, pkg);
    }

    private void waitAndAssertBucket(String msg, AppStandbyController controller, int bucket,
            int userId,
            String pkg) {
        waitUntil(() -> bucket == getStandbyBucket(userId, controller, pkg));
        assertEquals(msg, bucket, getStandbyBucket(userId, controller, pkg));
    }

    private void waitAndAssertNotBucket(int bucket, String pkg) {
        waitAndAssertNotBucket(mController, bucket, pkg);
    }

    private void waitAndAssertNotBucket(AppStandbyController controller, int bucket, String pkg) {
        waitUntil(() -> bucket != getStandbyBucket(controller, pkg));
        assertNotEquals(bucket, getStandbyBucket(controller, pkg));
    }

    private void waitAndAssertLastNoteEvent(int event) {
        waitUntil(() -> {
            flushHandler(mController);
            return event == mInjector.mLastNoteEvent;
        });
        assertEquals(event, mInjector.mLastNoteEvent);
    }

    // Waits until condition is true or times out.
    private void waitUntil(BooleanSupplier resultSupplier) {
        int retries = 0;
        do {
            if (resultSupplier.getAsBoolean()) return;
            try {
                Thread.sleep(ASSERT_RETRY_DELAY_MILLISECONDS);
            } catch (InterruptedException ie) {
                // Do nothing
            }
            retries++;
        } while (retries < ASSERT_RETRY_ATTEMPTS);
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
        waitAndAssertBucket(STANDBY_BUCKET_ACTIVE, PACKAGE_1);

        // For an unknown package, standby bucket should not be set, hence NEVER is returned
        // Ensure the unknown package is not already in history by removing it
        mController.clearAppIdleForPackage(PACKAGE_UNKNOWN, USER_ID);
        isPackageInstalled = false; // Mock package is not installed
        mController.setAppStandbyBucket(PACKAGE_UNKNOWN, USER_ID, STANDBY_BUCKET_ACTIVE,
                REASON_MAIN_TIMEOUT);
        isPackageInstalled = true; // Reset mocked variable for other tests
        waitAndAssertBucket(STANDBY_BUCKET_NEVER, PACKAGE_UNKNOWN);
    }

    @Test
    public void testAppStandbyBucketOnInstallAndUninstall() throws Exception {
        // On package install, standby bucket should be ACTIVE
        reportEvent(mController, USER_INTERACTION, 0, PACKAGE_UNKNOWN);
        waitAndAssertBucket(STANDBY_BUCKET_ACTIVE, PACKAGE_UNKNOWN);

        // On uninstall, package should not exist in history and should return a NEVER bucket
        mController.clearAppIdleForPackage(PACKAGE_UNKNOWN, USER_ID);
        waitAndAssertBucket(STANDBY_BUCKET_NEVER, PACKAGE_UNKNOWN);
        // Ensure uninstalled app is not in history
        List<AppStandbyInfo> buckets = getStandbyBuckets(USER_ID);
        for(AppStandbyInfo bucket : buckets) {
            if (bucket.mPackageName.equals(PACKAGE_UNKNOWN)) {
                fail("packageName found in app idle history after uninstall.");
            }
        }
    }

    @Test
    @DisableFlags(Flags.FLAG_SCREEN_TIME_BYPASS)
    public void testScreenTimeAndBuckets_Legacy() throws Exception {
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
        waitAndAssertNotBucket(STANDBY_BUCKET_RARE, PACKAGE_1);

        mInjector.setDisplayOn(true);
        assertTimeout(mController, RARE_THRESHOLD + 2 * HOUR_MS + 1, STANDBY_BUCKET_RARE);
    }

    @Test
    @EnableFlags(Flags.FLAG_SCREEN_TIME_BYPASS)
    public void testScreenTimeAndBuckets_Bypass() throws Exception {
        mInjector.setDisplayOn(false);

        assertTimeout(mController, 0, STANDBY_BUCKET_NEVER);

        reportEvent(mController, USER_INTERACTION, 0, PACKAGE_1);

        // ACTIVE bucket
        assertTimeout(mController, WORKING_SET_THRESHOLD - 1, STANDBY_BUCKET_ACTIVE);

        // WORKING_SET bucket
        assertTimeout(mController, WORKING_SET_THRESHOLD + 1, STANDBY_BUCKET_WORKING_SET);

        // RARE bucket, should failed due to timeout hasn't reached yet.
        mInjector.mElapsedRealtime = RARE_THRESHOLD - 1;
        mController.checkIdleStates(USER_ID);
        waitAndAssertNotBucket(STANDBY_BUCKET_RARE, PACKAGE_1);

        mInjector.setDisplayOn(true);
        // screen on time doesn't count.
        assertTimeout(mController, RARE_THRESHOLD + 1, STANDBY_BUCKET_RARE);
    }

    @Test
    public void testForcedIdle() throws Exception {
        mController.forceIdleState(PACKAGE_1, USER_ID, true);
        waitAndAssertBucket(STANDBY_BUCKET_RARE, PACKAGE_1);
        assertTrue(mController.isAppIdleFiltered(PACKAGE_1, UID_1, USER_ID, 0));

        mController.forceIdleState(PACKAGE_1, USER_ID, false);

        waitAndAssertBucket(STANDBY_BUCKET_ACTIVE, PACKAGE_1);
        assertFalse(mController.isAppIdleFiltered(PACKAGE_1, UID_1, USER_ID, 0));
    }

    @Test
    public void testNotificationEvent_bucketPromotion() throws Exception {
        mInjector.mPropertiesChangedListener
                .onPropertiesChanged(mInjector.getDeviceConfigProperties());

        reportEvent(mController, USER_INTERACTION, 0, PACKAGE_1);
        waitAndAssertBucket(STANDBY_BUCKET_ACTIVE, PACKAGE_1);
        mInjector.mElapsedRealtime = 1;
        rearmQuotaBumpLatch(PACKAGE_1, USER_ID);
        reportEvent(mController, NOTIFICATION_SEEN, mInjector.mElapsedRealtime, PACKAGE_1);
        waitAndAssertBucket(STANDBY_BUCKET_ACTIVE, PACKAGE_1);

        mController.forceIdleState(PACKAGE_1, USER_ID, true);
        reportEvent(mController, NOTIFICATION_SEEN, mInjector.mElapsedRealtime, PACKAGE_1);
        waitAndAssertBucket(STANDBY_BUCKET_WORKING_SET, PACKAGE_1);
        assertFalse(mQuotaBumpLatch.await(1, TimeUnit.SECONDS));
    }

    @Test
    public void testNotificationEvent_bucketPromotion_changePromotedBucket() throws Exception {
        mInjector.mPropertiesChangedListener
                .onPropertiesChanged(mInjector.getDeviceConfigProperties());
        mInjector.mElapsedRealtime += RARE_THRESHOLD + 1;
        mController.forceIdleState(PACKAGE_1, USER_ID, true);
        reportEvent(mController, NOTIFICATION_SEEN, mInjector.mElapsedRealtime, PACKAGE_1);
        waitAndAssertBucket(STANDBY_BUCKET_WORKING_SET, PACKAGE_1);

        // TODO: Avoid hardcoding these string constants.
        mInjector.mSettingsBuilder.setInt("notification_seen_promoted_bucket",
                STANDBY_BUCKET_FREQUENT);
        mInjector.mPropertiesChangedListener.onPropertiesChanged(
                mInjector.getDeviceConfigProperties());
        mController.forceIdleState(PACKAGE_1, USER_ID, true);
        reportEvent(mController, NOTIFICATION_SEEN, mInjector.mElapsedRealtime, PACKAGE_1);
        waitAndAssertBucket(STANDBY_BUCKET_FREQUENT, PACKAGE_1);
    }

    @Test
    public void testNotificationEvent_quotaBump() throws Exception {
        mInjector.mSettingsBuilder
                .setBoolean("trigger_quota_bump_on_notification_seen", true);
        mInjector.mSettingsBuilder
                .setInt("notification_seen_promoted_bucket", STANDBY_BUCKET_NEVER);
        mInjector.mPropertiesChangedListener
                .onPropertiesChanged(mInjector.getDeviceConfigProperties());

        reportEvent(mController, USER_INTERACTION, 0, PACKAGE_1);
        waitAndAssertBucket(STANDBY_BUCKET_ACTIVE, PACKAGE_1);
        mInjector.mElapsedRealtime = RARE_THRESHOLD * 2;
        setAndAssertBucket(PACKAGE_1, USER_ID, STANDBY_BUCKET_RARE, REASON_MAIN_FORCED_BY_SYSTEM);

        rearmQuotaBumpLatch(PACKAGE_1, USER_ID);
        mInjector.mElapsedRealtime += 1;

        reportEvent(mController, NOTIFICATION_SEEN, mInjector.mElapsedRealtime, PACKAGE_1);
        assertTrue(mQuotaBumpLatch.await(1, TimeUnit.SECONDS));
        waitAndAssertBucket(STANDBY_BUCKET_RARE, PACKAGE_1);
    }

    @Test
    public void testSlicePinnedEvent() throws Exception {
        reportEvent(mController, USER_INTERACTION, 0, PACKAGE_1);
        waitAndAssertBucket(STANDBY_BUCKET_ACTIVE, PACKAGE_1);
        mInjector.mElapsedRealtime = 1;
        reportEvent(mController, SLICE_PINNED, mInjector.mElapsedRealtime, PACKAGE_1);
        waitAndAssertBucket(STANDBY_BUCKET_ACTIVE, PACKAGE_1);

        mController.forceIdleState(PACKAGE_1, USER_ID, true);
        reportEvent(mController, SLICE_PINNED, mInjector.mElapsedRealtime, PACKAGE_1);
        waitAndAssertBucket(STANDBY_BUCKET_WORKING_SET, PACKAGE_1);
    }

    @Test
    public void testSlicePinnedPrivEvent() throws Exception {
        mController.forceIdleState(PACKAGE_1, USER_ID, true);
        reportEvent(mController, SLICE_PINNED_PRIV, mInjector.mElapsedRealtime, PACKAGE_1);
        waitAndAssertBucket(STANDBY_BUCKET_ACTIVE, PACKAGE_1);
    }

    @Test
    public void testPredictionTimedOut() throws Exception {
        // Set it to timeout or usage, so that prediction can override it
        mInjector.mElapsedRealtime = HOUR_MS;
        mController.setAppStandbyBucket(PACKAGE_1, USER_ID, STANDBY_BUCKET_RARE,
                REASON_MAIN_TIMEOUT);
        waitAndAssertBucket(STANDBY_BUCKET_RARE, PACKAGE_1);

        mController.setAppStandbyBucket(PACKAGE_1, USER_ID, STANDBY_BUCKET_ACTIVE,
                REASON_MAIN_PREDICTED);
        waitAndAssertBucket(STANDBY_BUCKET_ACTIVE, PACKAGE_1);

        // Fast forward 12 hours
        mInjector.mElapsedRealtime += WORKING_SET_THRESHOLD;
        mController.checkIdleStates(USER_ID);
        // Should still be in predicted bucket, since prediction timeout is 1 day since prediction
        waitAndAssertBucket(STANDBY_BUCKET_ACTIVE, PACKAGE_1);
        // Fast forward two more hours
        mInjector.mElapsedRealtime += 2 * HOUR_MS;
        mController.checkIdleStates(USER_ID);
        // Should have now applied prediction timeout
        waitAndAssertBucket(STANDBY_BUCKET_WORKING_SET, PACKAGE_1);

        // Fast forward RARE bucket
        mInjector.mElapsedRealtime += RARE_THRESHOLD;
        mController.checkIdleStates(USER_ID);
        // Should continue to apply prediction timeout
        waitAndAssertBucket(STANDBY_BUCKET_RARE, PACKAGE_1);
    }

    @Test
    @Ignore("b/317086276")
    public void testOverrides() throws Exception {
        // Can force to NEVER
        mInjector.mElapsedRealtime = HOUR_MS;
        mController.setAppStandbyBucket(PACKAGE_1, USER_ID, STANDBY_BUCKET_NEVER,
                REASON_MAIN_FORCED_BY_USER);
        waitAndAssertBucket(STANDBY_BUCKET_NEVER, PACKAGE_1);

        // Prediction can't override FORCED reasons
        mController.setAppStandbyBucket(PACKAGE_1, USER_ID, STANDBY_BUCKET_RARE,
                REASON_MAIN_FORCED_BY_SYSTEM);
        mController.setAppStandbyBucket(PACKAGE_1, USER_ID, STANDBY_BUCKET_WORKING_SET,
                REASON_MAIN_PREDICTED);
        waitAndAssertBucket(STANDBY_BUCKET_RARE, PACKAGE_1);

        mController.setAppStandbyBucket(PACKAGE_1, USER_ID, STANDBY_BUCKET_FREQUENT,
                REASON_MAIN_FORCED_BY_USER);
        mController.setAppStandbyBucket(PACKAGE_1, USER_ID, STANDBY_BUCKET_WORKING_SET,
                REASON_MAIN_PREDICTED);
        waitAndAssertBucket(STANDBY_BUCKET_FREQUENT, PACKAGE_1);

        // Prediction can't override NEVER
        mInjector.mElapsedRealtime = 2 * HOUR_MS;
        mController.setAppStandbyBucket(PACKAGE_1, USER_ID, STANDBY_BUCKET_NEVER,
                REASON_MAIN_DEFAULT);
        mController.setAppStandbyBucket(PACKAGE_1, USER_ID, STANDBY_BUCKET_ACTIVE,
                REASON_MAIN_PREDICTED);
        waitAndAssertBucket(STANDBY_BUCKET_NEVER, PACKAGE_1);

        // Prediction can't set to NEVER
        mController.setAppStandbyBucket(PACKAGE_1, USER_ID, STANDBY_BUCKET_ACTIVE,
                REASON_MAIN_USAGE);
        mController.setAppStandbyBucket(PACKAGE_1, USER_ID, STANDBY_BUCKET_NEVER,
                REASON_MAIN_PREDICTED);
        waitAndAssertBucket(STANDBY_BUCKET_ACTIVE, PACKAGE_1);

        // Prediction can't remove from RESTRICTED
        mController.setAppStandbyBucket(PACKAGE_1, USER_ID, STANDBY_BUCKET_RESTRICTED,
                REASON_MAIN_FORCED_BY_USER);
        mController.setAppStandbyBucket(PACKAGE_1, USER_ID, STANDBY_BUCKET_WORKING_SET,
                REASON_MAIN_PREDICTED);
        waitAndAssertBucket(STANDBY_BUCKET_RESTRICTED, PACKAGE_1);

        mController.setAppStandbyBucket(PACKAGE_1, USER_ID, STANDBY_BUCKET_RESTRICTED,
                REASON_MAIN_FORCED_BY_SYSTEM);
        mController.setAppStandbyBucket(PACKAGE_1, USER_ID, STANDBY_BUCKET_WORKING_SET,
                REASON_MAIN_PREDICTED);
        waitAndAssertBucket(STANDBY_BUCKET_RESTRICTED, PACKAGE_1);

        // Force from user can remove from RESTRICTED
        mController.setAppStandbyBucket(PACKAGE_1, USER_ID, STANDBY_BUCKET_RESTRICTED,
                REASON_MAIN_FORCED_BY_USER);
        mController.setAppStandbyBucket(PACKAGE_1, USER_ID, STANDBY_BUCKET_WORKING_SET,
                REASON_MAIN_FORCED_BY_USER);
        waitAndAssertBucket(STANDBY_BUCKET_WORKING_SET, PACKAGE_1);

        mController.setAppStandbyBucket(PACKAGE_1, USER_ID, STANDBY_BUCKET_RESTRICTED,
                REASON_MAIN_FORCED_BY_SYSTEM);
        mController.setAppStandbyBucket(PACKAGE_1, USER_ID, STANDBY_BUCKET_WORKING_SET,
                REASON_MAIN_FORCED_BY_USER);
        waitAndAssertBucket(STANDBY_BUCKET_WORKING_SET, PACKAGE_1);

        // Force from system can remove from RESTRICTED if it was put it in due to system
        mController.setAppStandbyBucket(PACKAGE_1, USER_ID, STANDBY_BUCKET_RESTRICTED,
                REASON_MAIN_FORCED_BY_SYSTEM);
        mController.setAppStandbyBucket(PACKAGE_1, USER_ID, STANDBY_BUCKET_WORKING_SET,
                REASON_MAIN_FORCED_BY_SYSTEM);
        waitAndAssertBucket(STANDBY_BUCKET_WORKING_SET, PACKAGE_1);

        mController.setAppStandbyBucket(PACKAGE_1, USER_ID, STANDBY_BUCKET_RESTRICTED,
                REASON_MAIN_FORCED_BY_USER);
        mController.setAppStandbyBucket(PACKAGE_1, USER_ID, STANDBY_BUCKET_WORKING_SET,
                REASON_MAIN_FORCED_BY_SYSTEM);
        waitAndAssertBucket(STANDBY_BUCKET_RESTRICTED, PACKAGE_1);

        mController.setAppStandbyBucket(PACKAGE_1, USER_ID, STANDBY_BUCKET_RESTRICTED,
                REASON_MAIN_PREDICTED);
        mController.setAppStandbyBucket(PACKAGE_1, USER_ID, STANDBY_BUCKET_WORKING_SET,
                REASON_MAIN_FORCED_BY_SYSTEM);
        waitAndAssertBucket(STANDBY_BUCKET_RESTRICTED, PACKAGE_1);

        // Non-user usage can't remove from RESTRICTED
        mController.setAppStandbyBucket(PACKAGE_1, USER_ID, STANDBY_BUCKET_RESTRICTED,
                REASON_MAIN_FORCED_BY_SYSTEM);
        mController.setAppStandbyBucket(PACKAGE_1, USER_ID, STANDBY_BUCKET_WORKING_SET,
                REASON_MAIN_USAGE);
        waitAndAssertBucket(STANDBY_BUCKET_RESTRICTED, PACKAGE_1);
        mController.setAppStandbyBucket(PACKAGE_1, USER_ID, STANDBY_BUCKET_WORKING_SET,
                REASON_MAIN_USAGE | REASON_SUB_USAGE_SYSTEM_INTERACTION);
        waitAndAssertBucket(STANDBY_BUCKET_RESTRICTED, PACKAGE_1);
        mController.setAppStandbyBucket(PACKAGE_1, USER_ID, STANDBY_BUCKET_WORKING_SET,
                REASON_MAIN_USAGE | REASON_SUB_USAGE_SYNC_ADAPTER);
        waitAndAssertBucket(STANDBY_BUCKET_RESTRICTED, PACKAGE_1);
        mController.setAppStandbyBucket(PACKAGE_1, USER_ID, STANDBY_BUCKET_WORKING_SET,
                REASON_MAIN_USAGE | REASON_SUB_USAGE_EXEMPTED_SYNC_SCHEDULED_NON_DOZE);
        waitAndAssertBucket(STANDBY_BUCKET_RESTRICTED, PACKAGE_1);

        // Explicit user usage can remove from RESTRICTED
        mController.setAppStandbyBucket(PACKAGE_1, USER_ID, STANDBY_BUCKET_RESTRICTED,
                REASON_MAIN_FORCED_BY_USER);
        mController.setAppStandbyBucket(PACKAGE_1, USER_ID, STANDBY_BUCKET_WORKING_SET,
                REASON_MAIN_USAGE | REASON_SUB_USAGE_USER_INTERACTION);
        waitAndAssertBucket(STANDBY_BUCKET_WORKING_SET, PACKAGE_1);
        mController.setAppStandbyBucket(PACKAGE_1, USER_ID, STANDBY_BUCKET_RESTRICTED,
                REASON_MAIN_FORCED_BY_SYSTEM);
        mController.setAppStandbyBucket(PACKAGE_1, USER_ID, STANDBY_BUCKET_ACTIVE,
                REASON_MAIN_USAGE | REASON_SUB_USAGE_MOVE_TO_FOREGROUND);
        waitAndAssertBucket(STANDBY_BUCKET_ACTIVE, PACKAGE_1);
    }

    @Test
    public void testTimeout() throws Exception {
        reportEvent(mController, USER_INTERACTION, 0, PACKAGE_1);
        waitAndAssertBucket(STANDBY_BUCKET_ACTIVE, PACKAGE_1);

        mInjector.mElapsedRealtime = 2000;
        mController.setAppStandbyBucket(PACKAGE_1, USER_ID, STANDBY_BUCKET_FREQUENT,
                REASON_MAIN_PREDICTED);
        waitAndAssertBucket(STANDBY_BUCKET_ACTIVE, PACKAGE_1);

        // bucketing works after timeout
        mInjector.mElapsedRealtime = mController.mPredictionTimeoutMillis - 100;
        mController.checkIdleStates(USER_ID);
        // Use recent prediction
        waitAndAssertBucket(STANDBY_BUCKET_FREQUENT, PACKAGE_1);
        waitAndAssertBucket(STANDBY_BUCKET_FREQUENT, PACKAGE_1);

        // Way past prediction timeout, use system thresholds
        mInjector.mElapsedRealtime = RARE_THRESHOLD;
        mController.checkIdleStates(USER_ID);
        waitAndAssertBucket(STANDBY_BUCKET_RARE, PACKAGE_1);
    }

    /** Test that timeouts still work properly even if invalid configuration values are set. */
    @Test
    public void testTimeout_InvalidThresholds() throws Exception {
        mInjector.mSettingsBuilder
                .setLong("screen_threshold_active", -1)
                .setLong("screen_threshold_working_set", -1)
                .setLong("screen_threshold_frequent", -1)
                .setLong("screen_threshold_rare", -1)
                .setLong("screen_threshold_restricted", -1)
                .setLong("elapsed_threshold_active", -1)
                .setLong("elapsed_threshold_working_set", -1)
                .setLong("elapsed_threshold_frequent", -1)
                .setLong("elapsed_threshold_rare", -1)
                .setLong("elapsed_threshold_restricted", -1);
        mInjector.mPropertiesChangedListener
                .onPropertiesChanged(mInjector.getDeviceConfigProperties());

        reportEvent(mController, USER_INTERACTION, 0, PACKAGE_1);
        mController.checkIdleStates(USER_ID);
        waitAndAssertBucket(STANDBY_BUCKET_ACTIVE, PACKAGE_1);

        mInjector.mElapsedRealtime = HOUR_MS;
        mController.checkIdleStates(USER_ID);
        waitAndAssertBucket(STANDBY_BUCKET_FREQUENT, PACKAGE_1);

        mInjector.mElapsedRealtime = 2 * HOUR_MS;
        mController.checkIdleStates(USER_ID);
        waitAndAssertBucket(STANDBY_BUCKET_RARE, PACKAGE_1);

        mInjector.mElapsedRealtime = 4 * HOUR_MS;
        mController.checkIdleStates(USER_ID);
        waitAndAssertBucket(STANDBY_BUCKET_RESTRICTED, PACKAGE_1);
    }

    /**
     * Test that setAppStandbyBucket to RESTRICTED doesn't change the bucket until the usage
     * timeout has passed.
     */
    @Test
    @Ignore("b/317086276")
    public void testTimeoutBeforeRestricted() throws Exception {
        reportEvent(mController, USER_INTERACTION, 0, PACKAGE_1);
        waitAndAssertBucket(STANDBY_BUCKET_ACTIVE, PACKAGE_1);

        mInjector.mElapsedRealtime += WORKING_SET_THRESHOLD;
        mController.setAppStandbyBucket(PACKAGE_1, USER_ID, STANDBY_BUCKET_RESTRICTED,
                REASON_MAIN_FORCED_BY_SYSTEM);
        // Bucket shouldn't change
        waitAndAssertBucket(STANDBY_BUCKET_ACTIVE, PACKAGE_1);

        // bucketing works after timeout
        mInjector.mElapsedRealtime += DAY_MS;
        mController.setAppStandbyBucket(PACKAGE_1, USER_ID, STANDBY_BUCKET_RESTRICTED,
                REASON_MAIN_FORCED_BY_SYSTEM);
        waitAndAssertBucket(STANDBY_BUCKET_RESTRICTED, PACKAGE_1);

        // Way past all timeouts. Make sure timeout processing doesn't raise bucket.
        mInjector.mElapsedRealtime += RESTRICTED_THRESHOLD * 4;
        mController.checkIdleStates(USER_ID);
        waitAndAssertBucket(STANDBY_BUCKET_RESTRICTED, PACKAGE_1);
    }

    /**
     * Test that an app is put into the RESTRICTED bucket after enough time has passed.
     */
    @Test
    public void testRestrictedDelay() throws Exception {
        reportEvent(mController, USER_INTERACTION, 0, PACKAGE_1);
        waitAndAssertBucket(STANDBY_BUCKET_ACTIVE, PACKAGE_1);

        mInjector.mElapsedRealtime += mInjector.getAutoRestrictedBucketDelayMs() - 5000;
        mController.setAppStandbyBucket(PACKAGE_1, USER_ID, STANDBY_BUCKET_RESTRICTED,
                REASON_MAIN_FORCED_BY_SYSTEM);
        // Bucket shouldn't change
        waitAndAssertBucket(STANDBY_BUCKET_ACTIVE, PACKAGE_1);

        // bucketing works after timeout
        mInjector.mElapsedRealtime += 6000;

        Thread.sleep(6000);
        // Enough time has passed. The app should automatically be put into the RESTRICTED bucket.
        waitAndAssertBucket(STANDBY_BUCKET_RESTRICTED, PACKAGE_1);
    }

    /**
     * Test that an app is put into the RESTRICTED bucket after enough time has passed.
     */
    @Test
    public void testRestrictedDelay_DelayChange() throws Exception {
        reportEvent(mController, USER_INTERACTION, 0, PACKAGE_1);
        waitAndAssertBucket(STANDBY_BUCKET_ACTIVE, PACKAGE_1);

        mInjector.mAutoRestrictedBucketDelayMs = 2 * HOUR_MS;
        mInjector.mElapsedRealtime += 2 * HOUR_MS - 5000;
        mController.setAppStandbyBucket(PACKAGE_1, USER_ID, STANDBY_BUCKET_RESTRICTED,
                REASON_MAIN_FORCED_BY_SYSTEM);
        // Bucket shouldn't change
        waitAndAssertBucket(STANDBY_BUCKET_ACTIVE, PACKAGE_1);

        // bucketing works after timeout
        mInjector.mElapsedRealtime += 6000;

        Thread.sleep(6000);
        // Enough time has passed. The app should automatically be put into the RESTRICTED bucket.
        waitAndAssertBucket(STANDBY_BUCKET_RESTRICTED, PACKAGE_1);
    }

    /**
     * Test that an app is "timed out" into the RESTRICTED bucket if prediction tries to put it into
     * a low bucket after the RESTRICTED timeout.
     */
    @Test
    public void testRestrictedTimeoutOverridesRestoredLowBucketPrediction() throws Exception {
        reportEvent(mController, USER_INTERACTION, mInjector.mElapsedRealtime, PACKAGE_1);
        waitAndAssertBucket(STANDBY_BUCKET_ACTIVE, PACKAGE_1);

        // Predict to RARE Not long enough to time out into RESTRICTED.
        mInjector.mElapsedRealtime += RARE_THRESHOLD;
        mController.setAppStandbyBucket(PACKAGE_1, USER_ID, STANDBY_BUCKET_RARE,
                REASON_MAIN_PREDICTED);
        waitAndAssertBucket(STANDBY_BUCKET_RARE, PACKAGE_1);

        // Add a short timeout event
        mInjector.mElapsedRealtime += 1000;
        reportEvent(mController, SYSTEM_INTERACTION, mInjector.mElapsedRealtime, PACKAGE_1);
        waitAndAssertBucket(STANDBY_BUCKET_ACTIVE, PACKAGE_1);
        mInjector.mElapsedRealtime += 1000;
        mController.checkIdleStates(USER_ID);
        waitAndAssertBucket(STANDBY_BUCKET_ACTIVE, PACKAGE_1);

        // Long enough that it could have timed out into RESTRICTED. Instead of reverting to
        // predicted RARE, should go into RESTRICTED
        mInjector.mElapsedRealtime += RESTRICTED_THRESHOLD * 4;
        mController.checkIdleStates(USER_ID);
        waitAndAssertBucket(STANDBY_BUCKET_RESTRICTED, PACKAGE_1);

        // Ensure that prediction can still raise it out despite this override.
        mInjector.mElapsedRealtime += 1;
        mController.setAppStandbyBucket(PACKAGE_1, USER_ID, STANDBY_BUCKET_ACTIVE,
                REASON_MAIN_PREDICTED);
        waitAndAssertBucket(STANDBY_BUCKET_ACTIVE, PACKAGE_1);
    }

    /**
     * Test that an app is "timed out" into the RESTRICTED bucket if prediction tries to put it into
     * a low bucket after the RESTRICTED timeout.
     */
    @Test
    public void testRestrictedTimeoutOverridesPredictionLowBucket() throws Exception {
        reportEvent(mController, USER_INTERACTION, mInjector.mElapsedRealtime, PACKAGE_1);

        // Not long enough to time out into RESTRICTED.
        mInjector.mElapsedRealtime += RARE_THRESHOLD;
        mController.setAppStandbyBucket(PACKAGE_1, USER_ID, STANDBY_BUCKET_RARE,
                REASON_MAIN_PREDICTED);
        waitAndAssertBucket(STANDBY_BUCKET_RARE, PACKAGE_1);

        mInjector.mElapsedRealtime += 1;
        reportEvent(mController, USER_INTERACTION, mInjector.mElapsedRealtime, PACKAGE_1);

        // Long enough that it could have timed out into RESTRICTED.
        mInjector.mElapsedRealtime += RESTRICTED_THRESHOLD * 4;
        mController.setAppStandbyBucket(PACKAGE_1, USER_ID, STANDBY_BUCKET_ACTIVE,
                REASON_MAIN_PREDICTED);
        waitAndAssertBucket(STANDBY_BUCKET_ACTIVE, PACKAGE_1);
        mController.setAppStandbyBucket(PACKAGE_1, USER_ID, STANDBY_BUCKET_RARE,
                REASON_MAIN_PREDICTED);
        waitAndAssertBucket(STANDBY_BUCKET_RESTRICTED, PACKAGE_1);
    }

    /**
     * Test that an app that "timed out" into the RESTRICTED bucket can be raised out by system
     * interaction.
     */
    @Test
    public void testSystemInteractionOverridesRestrictedTimeout() throws Exception {
        reportEvent(mController, USER_INTERACTION, mInjector.mElapsedRealtime, PACKAGE_1);
        waitAndAssertBucket(STANDBY_BUCKET_ACTIVE, PACKAGE_1);

        // Long enough that it could have timed out into RESTRICTED.
        mInjector.mElapsedRealtime += RESTRICTED_THRESHOLD * 4;
        mController.checkIdleStates(USER_ID);
        waitAndAssertBucket(STANDBY_BUCKET_RESTRICTED, PACKAGE_1);

        // Report system interaction.
        mInjector.mElapsedRealtime += 1000;
        reportEvent(mController, SYSTEM_INTERACTION, mInjector.mElapsedRealtime, PACKAGE_1);

        // Ensure that it's raised out of RESTRICTED for the system interaction elevation duration.
        waitAndAssertBucket(STANDBY_BUCKET_ACTIVE, PACKAGE_1);
        mInjector.mElapsedRealtime += 1000;
        mController.checkIdleStates(USER_ID);
        waitAndAssertBucket(STANDBY_BUCKET_ACTIVE, PACKAGE_1);

        // Elevation duration over. Should fall back down.
        mInjector.mElapsedRealtime += 10 * MINUTE_MS;
        mController.checkIdleStates(USER_ID);
        waitAndAssertBucket(STANDBY_BUCKET_RESTRICTED, PACKAGE_1);
    }

    @Test
    public void testPredictionRaiseFromRestrictedTimeout_highBucket() throws Exception {
        reportEvent(mController, USER_INTERACTION, mInjector.mElapsedRealtime, PACKAGE_1);

        // Way past all timeouts. App times out into RESTRICTED bucket.
        mInjector.mElapsedRealtime += RESTRICTED_THRESHOLD * 4;
        mController.checkIdleStates(USER_ID);
        waitAndAssertBucket(STANDBY_BUCKET_RESTRICTED, PACKAGE_1);

        // Since the app timed out into RESTRICTED, prediction should be able to remove from the
        // bucket.
        mInjector.mElapsedRealtime += RESTRICTED_THRESHOLD;
        mController.setAppStandbyBucket(PACKAGE_1, USER_ID, STANDBY_BUCKET_ACTIVE,
                REASON_MAIN_PREDICTED);
        waitAndAssertBucket(STANDBY_BUCKET_ACTIVE, PACKAGE_1);
    }

    @Test
    public void testPredictionRaiseFromRestrictedTimeout_lowBucket() throws Exception {
        reportEvent(mController, USER_INTERACTION, mInjector.mElapsedRealtime, PACKAGE_1);

        // Way past all timeouts. App times out into RESTRICTED bucket.
        mInjector.mElapsedRealtime += RESTRICTED_THRESHOLD * 4;
        mController.checkIdleStates(USER_ID);
        waitAndAssertBucket(STANDBY_BUCKET_RESTRICTED, PACKAGE_1);

        // Prediction into a low bucket means no expectation of the app being used, so we shouldn't
        // elevate the app from RESTRICTED.
        mInjector.mElapsedRealtime += RESTRICTED_THRESHOLD;
        mController.setAppStandbyBucket(PACKAGE_1, USER_ID, STANDBY_BUCKET_RARE,
                REASON_MAIN_PREDICTED);
        waitAndAssertBucket(STANDBY_BUCKET_RESTRICTED, PACKAGE_1);
    }

    @Test
    public void testCascadingTimeouts() throws Exception {
        mInjector.mPropertiesChangedListener
                .onPropertiesChanged(mInjector.getDeviceConfigProperties());

        reportEvent(mController, USER_INTERACTION, 0, PACKAGE_1);
        waitAndAssertBucket(STANDBY_BUCKET_ACTIVE, PACKAGE_1);

        reportEvent(mController, NOTIFICATION_SEEN, 1000, PACKAGE_1);
        waitAndAssertBucket(STANDBY_BUCKET_ACTIVE, PACKAGE_1);

        mController.setAppStandbyBucket(PACKAGE_1, USER_ID, STANDBY_BUCKET_WORKING_SET,
                REASON_MAIN_PREDICTED);
        waitAndAssertBucket(STANDBY_BUCKET_ACTIVE, PACKAGE_1);

        mInjector.mElapsedRealtime = 2000 + mController.mStrongUsageTimeoutMillis;
        mController.setAppStandbyBucket(PACKAGE_1, USER_ID, STANDBY_BUCKET_FREQUENT,
                REASON_MAIN_PREDICTED);
        waitAndAssertBucket(STANDBY_BUCKET_WORKING_SET, PACKAGE_1);

        mInjector.mElapsedRealtime = 2000 + mController.mNotificationSeenTimeoutMillis;
        mController.setAppStandbyBucket(PACKAGE_1, USER_ID, STANDBY_BUCKET_FREQUENT,
                REASON_MAIN_PREDICTED);
        waitAndAssertBucket(STANDBY_BUCKET_FREQUENT, PACKAGE_1);
    }

    @Test
    public void testOverlappingTimeouts() throws Exception {
        mInjector.mPropertiesChangedListener
                .onPropertiesChanged(mInjector.getDeviceConfigProperties());

        reportEvent(mController, USER_INTERACTION, 0, PACKAGE_1);
        waitAndAssertBucket(STANDBY_BUCKET_ACTIVE, PACKAGE_1);

        reportEvent(mController, NOTIFICATION_SEEN, 1000, PACKAGE_1);
        waitAndAssertBucket(STANDBY_BUCKET_ACTIVE, PACKAGE_1);

        // Overlapping USER_INTERACTION before previous one times out
        reportEvent(mController, USER_INTERACTION, mController.mStrongUsageTimeoutMillis - 1000,
                PACKAGE_1);
        waitAndAssertBucket(STANDBY_BUCKET_ACTIVE, PACKAGE_1);

        // Still in ACTIVE after first USER_INTERACTION times out
        mInjector.mElapsedRealtime = mController.mStrongUsageTimeoutMillis + 1000;
        mController.setAppStandbyBucket(PACKAGE_1, USER_ID, STANDBY_BUCKET_FREQUENT,
                REASON_MAIN_PREDICTED);
        waitAndAssertBucket(STANDBY_BUCKET_ACTIVE, PACKAGE_1);

        // Both timed out, so NOTIFICATION_SEEN timeout should be effective
        mInjector.mElapsedRealtime = mController.mStrongUsageTimeoutMillis * 2 + 2000;
        mController.setAppStandbyBucket(PACKAGE_1, USER_ID, STANDBY_BUCKET_FREQUENT,
                REASON_MAIN_PREDICTED);
        waitAndAssertBucket(STANDBY_BUCKET_WORKING_SET, PACKAGE_1);

        mInjector.mElapsedRealtime = mController.mNotificationSeenTimeoutMillis + 2000;
        mController.setAppStandbyBucket(PACKAGE_1, USER_ID, STANDBY_BUCKET_RARE,
                REASON_MAIN_PREDICTED);
        waitAndAssertBucket(STANDBY_BUCKET_RARE, PACKAGE_1);
    }

    @Test
    public void testSystemInteractionTimeout() throws Exception {
        reportEvent(mController, USER_INTERACTION, 0, PACKAGE_1);
        // Fast forward to RARE
        mInjector.mElapsedRealtime = RARE_THRESHOLD + 100;
        mController.checkIdleStates(USER_ID);
        waitAndAssertBucket(STANDBY_BUCKET_RARE, PACKAGE_1);

        // Trigger a SYSTEM_INTERACTION and verify bucket
        reportEvent(mController, SYSTEM_INTERACTION, mInjector.mElapsedRealtime, PACKAGE_1);
        waitAndAssertBucket(STANDBY_BUCKET_ACTIVE, PACKAGE_1);

        // Verify it's still in ACTIVE close to end of timeout
        mInjector.mElapsedRealtime += mController.mSystemInteractionTimeoutMillis - 100;
        mController.checkIdleStates(USER_ID);
        waitAndAssertBucket(STANDBY_BUCKET_ACTIVE, PACKAGE_1);

        // Verify bucket moves to RARE after timeout
        mInjector.mElapsedRealtime += 200;
        mController.checkIdleStates(USER_ID);
        waitAndAssertBucket(STANDBY_BUCKET_RARE, PACKAGE_1);
    }

    @Test
    public void testInitialForegroundServiceTimeout() throws Exception {
        mInjector.mElapsedRealtime = 1 * RARE_THRESHOLD + 100;
        // Make sure app is in NEVER bucket
        mController.setAppStandbyBucket(PACKAGE_1, USER_ID, STANDBY_BUCKET_NEVER,
                REASON_MAIN_FORCED_BY_USER);
        mController.checkIdleStates(USER_ID);
        waitAndAssertBucket(STANDBY_BUCKET_NEVER, PACKAGE_1);

        mInjector.mElapsedRealtime += 100;

        // Trigger a FOREGROUND_SERVICE_START and verify bucket
        reportEvent(mController, FOREGROUND_SERVICE_START, mInjector.mElapsedRealtime, PACKAGE_1);
        mController.checkIdleStates(USER_ID);
        waitAndAssertBucket(STANDBY_BUCKET_ACTIVE, PACKAGE_1);

        // Verify it's still in ACTIVE close to end of timeout
        mInjector.mElapsedRealtime += mController.mInitialForegroundServiceStartTimeoutMillis - 100;
        mController.checkIdleStates(USER_ID);
        waitAndAssertBucket(STANDBY_BUCKET_ACTIVE, PACKAGE_1);

        // Verify bucket moves to RARE after timeout
        mInjector.mElapsedRealtime += 200;
        mController.checkIdleStates(USER_ID);
        waitAndAssertBucket(STANDBY_BUCKET_RARE, PACKAGE_1);

        // Trigger a FOREGROUND_SERVICE_START again
        reportEvent(mController, FOREGROUND_SERVICE_START, mInjector.mElapsedRealtime, PACKAGE_1);
        mController.checkIdleStates(USER_ID);
        // Bucket should not be immediately elevated on subsequent service starts
        waitAndAssertBucket(STANDBY_BUCKET_RARE, PACKAGE_1);
    }

    @Test
    public void testPredictionNotOverridden() throws Exception {
        mInjector.mPropertiesChangedListener
                .onPropertiesChanged(mInjector.getDeviceConfigProperties());

        reportEvent(mController, USER_INTERACTION, 0, PACKAGE_1);
        waitAndAssertBucket(STANDBY_BUCKET_ACTIVE, PACKAGE_1);

        mInjector.mElapsedRealtime = WORKING_SET_THRESHOLD - 1000;
        reportEvent(mController, NOTIFICATION_SEEN, mInjector.mElapsedRealtime, PACKAGE_1);
        waitAndAssertBucket(STANDBY_BUCKET_ACTIVE, PACKAGE_1);

        // Falls back to WORKING_SET
        mInjector.mElapsedRealtime += 5000;
        mController.checkIdleStates(USER_ID);
        waitAndAssertBucket(STANDBY_BUCKET_WORKING_SET, PACKAGE_1);

        // Predict to ACTIVE
        mInjector.mElapsedRealtime += 1000;
        mController.setAppStandbyBucket(PACKAGE_1, USER_ID, STANDBY_BUCKET_ACTIVE,
                REASON_MAIN_PREDICTED);
        waitAndAssertBucket(STANDBY_BUCKET_ACTIVE, PACKAGE_1);

        // CheckIdleStates should not change the prediction
        mInjector.mElapsedRealtime += 1000;
        mController.checkIdleStates(USER_ID);
        waitAndAssertBucket(STANDBY_BUCKET_ACTIVE, PACKAGE_1);
    }

    @Test
    public void testPredictionStrikesBack() throws Exception {
        reportEvent(mController, USER_INTERACTION, 0, PACKAGE_1);
        waitAndAssertBucket(STANDBY_BUCKET_ACTIVE, PACKAGE_1);

        // Predict to FREQUENT
        mInjector.mElapsedRealtime = RARE_THRESHOLD;
        mController.setAppStandbyBucket(PACKAGE_1, USER_ID, STANDBY_BUCKET_FREQUENT,
                REASON_MAIN_PREDICTED);
        waitAndAssertBucket(STANDBY_BUCKET_FREQUENT, PACKAGE_1);

        // Add a short timeout event
        mInjector.mElapsedRealtime += 1000;
        reportEvent(mController, SYSTEM_INTERACTION, mInjector.mElapsedRealtime, PACKAGE_1);
        waitAndAssertBucket(STANDBY_BUCKET_ACTIVE, PACKAGE_1);
        mInjector.mElapsedRealtime += 1000;
        mController.checkIdleStates(USER_ID);
        waitAndAssertBucket(STANDBY_BUCKET_ACTIVE, PACKAGE_1);

        // Verify it reverted to predicted
        mInjector.mElapsedRealtime += WORKING_SET_THRESHOLD / 2;
        mController.checkIdleStates(USER_ID);
        waitAndAssertBucket(STANDBY_BUCKET_FREQUENT, PACKAGE_1);
    }

    @Test
    public void testSystemForcedFlags_NotAddedForUserForce() throws Exception {
        final int expectedReason = REASON_MAIN_FORCED_BY_USER;
        mController.setAppStandbyBucket(PACKAGE_1, USER_ID, STANDBY_BUCKET_RESTRICTED,
                REASON_MAIN_FORCED_BY_USER);
        waitAndAssertBucket(STANDBY_BUCKET_RESTRICTED, PACKAGE_1);
        assertEquals(expectedReason, getStandbyBucketReason(PACKAGE_1));

        mController.setAppStandbyBucket(PACKAGE_1, USER_ID, STANDBY_BUCKET_RESTRICTED,
                REASON_MAIN_FORCED_BY_SYSTEM | REASON_SUB_FORCED_SYSTEM_FLAG_ABUSE);
        waitAndAssertBucket(STANDBY_BUCKET_RESTRICTED, PACKAGE_1);
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
        waitAndAssertBucket(STANDBY_BUCKET_RESTRICTED, PACKAGE_1);
        assertEquals(REASON_MAIN_FORCED_BY_SYSTEM
                        | REASON_SUB_FORCED_SYSTEM_FLAG_BACKGROUND_RESOURCE_USAGE,
                getStandbyBucketReason(PACKAGE_1));

        mController.restrictApp(PACKAGE_1, USER_ID, REASON_SUB_FORCED_SYSTEM_FLAG_ABUSE);
        waitAndAssertBucket(STANDBY_BUCKET_RESTRICTED, PACKAGE_1);
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
        waitAndAssertBucket(STANDBY_BUCKET_FREQUENT, PACKAGE_1);
        assertEquals(REASON_MAIN_FORCED_BY_SYSTEM
                        | REASON_SUB_FORCED_SYSTEM_FLAG_BACKGROUND_RESOURCE_USAGE,
                getStandbyBucketReason(PACKAGE_1));

        mController.setAppStandbyBucket(PACKAGE_1, USER_ID, STANDBY_BUCKET_FREQUENT,
                REASON_MAIN_FORCED_BY_SYSTEM | REASON_SUB_FORCED_SYSTEM_FLAG_ABUSE);
        waitAndAssertBucket(STANDBY_BUCKET_FREQUENT, PACKAGE_1);
        // Flags should be combined
        assertEquals(REASON_MAIN_FORCED_BY_SYSTEM
                        | REASON_SUB_FORCED_SYSTEM_FLAG_BACKGROUND_RESOURCE_USAGE
                        | REASON_SUB_FORCED_SYSTEM_FLAG_ABUSE,
                getStandbyBucketReason(PACKAGE_1));

        mController.setAppStandbyBucket(PACKAGE_1, USER_ID, STANDBY_BUCKET_RARE,
                REASON_MAIN_FORCED_BY_SYSTEM | REASON_SUB_FORCED_SYSTEM_FLAG_BUGGY);
        waitAndAssertBucket(STANDBY_BUCKET_RARE, PACKAGE_1);
        // Flags should be combined
        assertEquals(REASON_MAIN_FORCED_BY_SYSTEM | REASON_SUB_FORCED_SYSTEM_FLAG_BUGGY,
                getStandbyBucketReason(PACKAGE_1));

        mController.restrictApp(PACKAGE_1, USER_ID, REASON_SUB_FORCED_SYSTEM_FLAG_UNDEFINED);
        waitAndAssertBucket(STANDBY_BUCKET_RESTRICTED, PACKAGE_1);
        // Flags should not be combined since the bucket changed.
        assertEquals(REASON_MAIN_FORCED_BY_SYSTEM | REASON_SUB_FORCED_SYSTEM_FLAG_UNDEFINED,
                getStandbyBucketReason(PACKAGE_1));
    }

    @Test
    public void testRestrictApp_MainReason() throws Exception {
        mController.setAppStandbyBucket(PACKAGE_1, USER_ID, STANDBY_BUCKET_ACTIVE,
                REASON_MAIN_DEFAULT);
        mInjector.mElapsedRealtime += 4 * RESTRICTED_THRESHOLD;

        mController.restrictApp(PACKAGE_1, USER_ID, REASON_MAIN_PREDICTED, 0);
        // Call should be ignored.
        waitAndAssertBucket(STANDBY_BUCKET_ACTIVE, PACKAGE_1);

        mController.restrictApp(PACKAGE_1, USER_ID, REASON_MAIN_FORCED_BY_USER, 0);
        // Call should go through
        waitAndAssertBucket(STANDBY_BUCKET_RESTRICTED, PACKAGE_1);
    }

    @Test
    public void testAddActiveDeviceAdmin() throws Exception {
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
    public void isActiveDeviceAdmin() throws Exception {
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
    public void testSetAdminProtectedPackages() {
        assertAdminProtectedPackagesForTest(USER_ID, (String[]) null);
        assertAdminProtectedPackagesForTest(USER_ID2, (String[]) null);

        setAdminProtectedPackages(USER_ID, ADMIN_PROTECTED_PKG, ADMIN_PROTECTED_PKG2);
        assertAdminProtectedPackagesForTest(USER_ID, ADMIN_PROTECTED_PKG, ADMIN_PROTECTED_PKG2);
        assertAdminProtectedPackagesForTest(USER_ID2, (String[]) null);

        setAdminProtectedPackages(USER_ID, (String[]) null);
        assertAdminProtectedPackagesForTest(USER_ID, (String[]) null);
    }

    @Test
    public void testUserInteraction_CrossProfile() throws Exception {
        mInjector.mRunningUsers = new int[] {USER_ID, USER_ID2, USER_ID3};
        mInjector.mCrossProfileTargets = Arrays.asList(USER_HANDLE_USER2);
        reportEvent(mController, USER_INTERACTION, 0, PACKAGE_1);
        waitAndAssertBucket("Cross profile connected package bucket should be elevated on usage",
                mController, STANDBY_BUCKET_ACTIVE, USER_ID2, PACKAGE_1);
        waitAndAssertBucket(
                "Not Cross profile connected package bucket should not be elevated on usage",
                mController, STANDBY_BUCKET_NEVER, USER_ID3, PACKAGE_1);

        assertTimeout(mController, WORKING_SET_THRESHOLD - 1, STANDBY_BUCKET_ACTIVE, USER_ID);
        assertTimeout(mController, WORKING_SET_THRESHOLD - 1, STANDBY_BUCKET_ACTIVE, USER_ID2);

        assertTimeout(mController, WORKING_SET_THRESHOLD + 1, STANDBY_BUCKET_WORKING_SET, USER_ID);
        assertTimeout(mController, WORKING_SET_THRESHOLD + 1, STANDBY_BUCKET_WORKING_SET, USER_ID2);

        mInjector.mCrossProfileTargets = Collections.emptyList();
        reportEvent(mController, USER_INTERACTION, 0, PACKAGE_1);
        waitAndAssertBucket("No longer cross profile connected package bucket should not be "
                        + "elevated on usage", mController, STANDBY_BUCKET_WORKING_SET, USER_ID2,
                PACKAGE_1);
    }

    @Test
    public void testUnexemptedSyncScheduled() throws Exception {
        rearmLatch(PACKAGE_1);
        mController.addListener(mListener);
        waitAndAssertBucket("Test package did not start in the Never bucket", STANDBY_BUCKET_NEVER,
                PACKAGE_1);

        mController.postReportSyncScheduled(PACKAGE_1, USER_ID, false);
        mStateChangedLatch.await(1000, TimeUnit.MILLISECONDS);
        waitAndAssertBucket(
                "Unexempted sync scheduled should bring the package out of the Never bucket",
                STANDBY_BUCKET_WORKING_SET, PACKAGE_1);

        setAndAssertBucket(PACKAGE_1, USER_ID, STANDBY_BUCKET_RARE, REASON_MAIN_FORCED_BY_SYSTEM);

        rearmLatch(PACKAGE_1);
        mController.postReportSyncScheduled(PACKAGE_1, USER_ID, false);
        mStateChangedLatch.await(1000, TimeUnit.MILLISECONDS);
        waitAndAssertBucket("Unexempted sync scheduled should not elevate a non Never bucket",
                STANDBY_BUCKET_RARE, PACKAGE_1);
    }

    @Test
    public void testExemptedSyncScheduled() throws Exception {
        setAndAssertBucket(PACKAGE_1, USER_ID, STANDBY_BUCKET_RARE, REASON_MAIN_FORCED_BY_SYSTEM);
        mInjector.mDeviceIdleMode = true;
        rearmLatch(PACKAGE_1);
        mController.postReportSyncScheduled(PACKAGE_1, USER_ID, true);
        mStateChangedLatch.await(1000, TimeUnit.MILLISECONDS);
        waitAndAssertBucket("Exempted sync scheduled in doze should set bucket to working set",
                STANDBY_BUCKET_WORKING_SET, PACKAGE_1);

        setAndAssertBucket(PACKAGE_1, USER_ID, STANDBY_BUCKET_RARE, REASON_MAIN_FORCED_BY_SYSTEM);
        mInjector.mDeviceIdleMode = false;
        rearmLatch(PACKAGE_1);
        mController.postReportSyncScheduled(PACKAGE_1, USER_ID, true);
        mStateChangedLatch.await(1000, TimeUnit.MILLISECONDS);
        waitAndAssertBucket("Exempted sync scheduled while not in doze should set bucket to active",
                STANDBY_BUCKET_ACTIVE, PACKAGE_1);
    }

    @Test
    public void testAppUpdateOnRestrictedBucketStatus() throws Exception {
        // Updates shouldn't change bucket if the app timed out.
        // Way past all timeouts. App times out into RESTRICTED bucket.
        reportEvent(mController, USER_INTERACTION, mInjector.mElapsedRealtime, PACKAGE_1);
        mInjector.mElapsedRealtime += RESTRICTED_THRESHOLD * 4;
        mController.checkIdleStates(USER_ID);
        waitAndAssertBucket(STANDBY_BUCKET_RESTRICTED, PACKAGE_1);

        mController.maybeUnrestrictBuggyApp(PACKAGE_1, USER_ID);
        waitAndAssertBucket(STANDBY_BUCKET_RESTRICTED, PACKAGE_1);
        mController.maybeUnrestrictBuggyApp(PACKAGE_1, USER_ID2);
        waitAndAssertBucket(STANDBY_BUCKET_RESTRICTED, PACKAGE_1);
        mController.maybeUnrestrictBuggyApp("com.random.package", USER_ID);
        waitAndAssertBucket(STANDBY_BUCKET_RESTRICTED, PACKAGE_1);

        // Updates shouldn't change bucket if the app was forced by the system for a non-buggy
        // reason.
        reportEvent(mController, USER_INTERACTION, mInjector.mElapsedRealtime, PACKAGE_1);
        mInjector.mElapsedRealtime += RESTRICTED_THRESHOLD * 4;
        mController.setAppStandbyBucket(PACKAGE_1, USER_ID, STANDBY_BUCKET_RESTRICTED,
                REASON_MAIN_FORCED_BY_SYSTEM
                        | REASON_SUB_FORCED_SYSTEM_FLAG_BACKGROUND_RESOURCE_USAGE);

        mController.maybeUnrestrictBuggyApp(PACKAGE_1, USER_ID);
        waitAndAssertBucket(STANDBY_BUCKET_RESTRICTED, PACKAGE_1);
        mController.maybeUnrestrictBuggyApp(PACKAGE_1, USER_ID2);
        waitAndAssertBucket(STANDBY_BUCKET_RESTRICTED, PACKAGE_1);
        mController.maybeUnrestrictBuggyApp("com.random.package", USER_ID);
        waitAndAssertBucket(STANDBY_BUCKET_RESTRICTED, PACKAGE_1);

        // Updates should change bucket if the app was forced by the system for a buggy reason.
        reportEvent(mController, USER_INTERACTION, mInjector.mElapsedRealtime, PACKAGE_1);
        mInjector.mElapsedRealtime += RESTRICTED_THRESHOLD * 4;
        mController.setAppStandbyBucket(PACKAGE_1, USER_ID, STANDBY_BUCKET_RESTRICTED,
                REASON_MAIN_FORCED_BY_SYSTEM | REASON_SUB_FORCED_SYSTEM_FLAG_BUGGY);

        mController.maybeUnrestrictBuggyApp(PACKAGE_1, USER_ID2);
        waitAndAssertBucket(STANDBY_BUCKET_RESTRICTED, PACKAGE_1);
        mController.maybeUnrestrictBuggyApp("com.random.package", USER_ID);
        waitAndAssertBucket(STANDBY_BUCKET_RESTRICTED, PACKAGE_1);
        mController.maybeUnrestrictBuggyApp(PACKAGE_1, USER_ID);
        waitAndAssertNotBucket(STANDBY_BUCKET_RESTRICTED, PACKAGE_1);

        // Updates shouldn't change bucket if the app was forced by the system for more than just
        // a buggy reason.
        reportEvent(mController, USER_INTERACTION, mInjector.mElapsedRealtime, PACKAGE_1);
        mInjector.mElapsedRealtime += RESTRICTED_THRESHOLD * 4;
        mController.setAppStandbyBucket(PACKAGE_1, USER_ID, STANDBY_BUCKET_RESTRICTED,
                REASON_MAIN_FORCED_BY_SYSTEM | REASON_SUB_FORCED_SYSTEM_FLAG_ABUSE
                        | REASON_SUB_FORCED_SYSTEM_FLAG_BUGGY);

        mController.maybeUnrestrictBuggyApp(PACKAGE_1, USER_ID);
        waitAndAssertBucket(STANDBY_BUCKET_RESTRICTED, PACKAGE_1);
        assertEquals(REASON_MAIN_FORCED_BY_SYSTEM | REASON_SUB_FORCED_SYSTEM_FLAG_ABUSE,
                getStandbyBucketReason(PACKAGE_1));
        mController.maybeUnrestrictBuggyApp(PACKAGE_1, USER_ID2);
        waitAndAssertBucket(STANDBY_BUCKET_RESTRICTED, PACKAGE_1);
        mController.maybeUnrestrictBuggyApp("com.random.package", USER_ID);
        waitAndAssertBucket(STANDBY_BUCKET_RESTRICTED, PACKAGE_1);

        // Updates shouldn't change bucket if the app was forced by the user.
        reportEvent(mController, USER_INTERACTION, mInjector.mElapsedRealtime, PACKAGE_1);
        mInjector.mElapsedRealtime += RESTRICTED_THRESHOLD * 4;
        mController.setAppStandbyBucket(PACKAGE_1, USER_ID, STANDBY_BUCKET_RESTRICTED,
                REASON_MAIN_FORCED_BY_USER);

        mController.maybeUnrestrictBuggyApp(PACKAGE_1, USER_ID);
        waitAndAssertBucket(STANDBY_BUCKET_RESTRICTED, PACKAGE_1);
        mController.maybeUnrestrictBuggyApp(PACKAGE_1, USER_ID2);
        waitAndAssertBucket(STANDBY_BUCKET_RESTRICTED, PACKAGE_1);
        mController.maybeUnrestrictBuggyApp("com.random.package", USER_ID);
        waitAndAssertBucket(STANDBY_BUCKET_RESTRICTED, PACKAGE_1);
    }

    @Test
    public void testSystemHeadlessAppElevated() throws Exception {
        reportEvent(mController, USER_INTERACTION, mInjector.mElapsedRealtime, PACKAGE_1);
        reportEvent(mController, USER_INTERACTION, mInjector.mElapsedRealtime,
                PACKAGE_SYSTEM_HEADFULL);
        reportEvent(mController, USER_INTERACTION, mInjector.mElapsedRealtime,
                PACKAGE_SYSTEM_HEADLESS);
        mInjector.mElapsedRealtime += RESTRICTED_THRESHOLD;


        mController.setAppStandbyBucket(PACKAGE_SYSTEM_HEADFULL, USER_ID, STANDBY_BUCKET_RARE,
                REASON_MAIN_TIMEOUT);
        waitAndAssertBucket(STANDBY_BUCKET_RARE, PACKAGE_SYSTEM_HEADFULL);

        // Make sure headless system apps don't get lowered.
        mController.setAppStandbyBucket(PACKAGE_SYSTEM_HEADLESS, USER_ID, STANDBY_BUCKET_RARE,
                REASON_MAIN_TIMEOUT);
        waitAndAssertBucket(STANDBY_BUCKET_ACTIVE, PACKAGE_SYSTEM_HEADLESS);

        // Package 1 doesn't have activities and is headless, but is not a system app, so it can
        // be lowered.
        mController.setAppStandbyBucket(PACKAGE_1, USER_ID, STANDBY_BUCKET_RARE,
                REASON_MAIN_TIMEOUT);
        waitAndAssertBucket(STANDBY_BUCKET_RARE, PACKAGE_1);
    }

    @Test
    public void testWellbeingAppElevated() throws Exception {
        reportEvent(mController, USER_INTERACTION, mInjector.mElapsedRealtime, PACKAGE_WELLBEING);
        waitAndAssertBucket(STANDBY_BUCKET_ACTIVE, PACKAGE_WELLBEING);
        reportEvent(mController, USER_INTERACTION, mInjector.mElapsedRealtime, PACKAGE_1);
        waitAndAssertBucket(STANDBY_BUCKET_ACTIVE, PACKAGE_1);
        mInjector.mElapsedRealtime += RESTRICTED_THRESHOLD;

        // Make sure the default wellbeing app does not get lowered below WORKING_SET.
        mController.setAppStandbyBucket(PACKAGE_WELLBEING, USER_ID, STANDBY_BUCKET_RARE,
                REASON_MAIN_TIMEOUT);
        waitAndAssertBucket(STANDBY_BUCKET_WORKING_SET, PACKAGE_WELLBEING);

        // A non default wellbeing app should be able to fall lower than WORKING_SET.
        mController.setAppStandbyBucket(PACKAGE_1, USER_ID, STANDBY_BUCKET_RARE,
                REASON_MAIN_TIMEOUT);
        waitAndAssertBucket(STANDBY_BUCKET_RARE, PACKAGE_1);
    }

    @Test
    public void testClockAppElevated() throws Exception {
        mInjector.mClockApps.add(Pair.create(PACKAGE_1, UID_1));

        reportEvent(mController, USER_INTERACTION, mInjector.mElapsedRealtime, PACKAGE_1);
        waitAndAssertBucket(STANDBY_BUCKET_ACTIVE, PACKAGE_1);

        reportEvent(mController, USER_INTERACTION, mInjector.mElapsedRealtime, PACKAGE_2);
        waitAndAssertBucket(STANDBY_BUCKET_ACTIVE, PACKAGE_2);

        mInjector.mElapsedRealtime += RESTRICTED_THRESHOLD;

        // Make sure a clock app does not get lowered below WORKING_SET.
        mController.setAppStandbyBucket(PACKAGE_1, USER_ID, STANDBY_BUCKET_RARE,
                REASON_MAIN_TIMEOUT);
        waitAndAssertBucket(STANDBY_BUCKET_WORKING_SET, PACKAGE_1);

        // A non clock app should be able to fall lower than WORKING_SET.
        mController.setAppStandbyBucket(PACKAGE_2, USER_ID, STANDBY_BUCKET_RARE,
                REASON_MAIN_TIMEOUT);
        waitAndAssertBucket(STANDBY_BUCKET_RARE, PACKAGE_2);
    }

    @Test
    public void testChangingSettings_ElapsedThreshold_Invalid() {
        mInjector.mSettingsBuilder
                .setLong("elapsed_threshold_active", -1)
                .setLong("elapsed_threshold_working_set", -1)
                .setLong("elapsed_threshold_frequent", -1)
                .setLong("elapsed_threshold_rare", -1)
                .setLong("elapsed_threshold_restricted", -1);
        mInjector.mPropertiesChangedListener
                .onPropertiesChanged(mInjector.getDeviceConfigProperties());
        for (int i = 0; i < MINIMUM_ELAPSED_TIME_THRESHOLDS.length; ++i) {
            assertEquals(MINIMUM_ELAPSED_TIME_THRESHOLDS[i],
                    mController.mAppStandbyElapsedThresholds[i]);
        }
    }

    @Test
    public void testChangingSettings_ElapsedThreshold_Valid() {
        // Effectively clear values
        mInjector.mSettingsBuilder
                .setString("elapsed_threshold_active", null)
                .setString("elapsed_threshold_working_set", null)
                .setString("elapsed_threshold_frequent", null)
                .setString("elapsed_threshold_rare", null)
                .setString("elapsed_threshold_restricted", null);
        mInjector.mPropertiesChangedListener
                .onPropertiesChanged(mInjector.getDeviceConfigProperties());
        for (int i = 0; i < DEFAULT_ELAPSED_TIME_THRESHOLDS.length; ++i) {
            assertEquals(DEFAULT_ELAPSED_TIME_THRESHOLDS[i],
                    mController.mAppStandbyElapsedThresholds[i]);
        }

        // Set really high thresholds
        mInjector.mSettingsBuilder
                .setLong("elapsed_threshold_active", 90 * DAY_MS)
                .setLong("elapsed_threshold_working_set", 91 * DAY_MS)
                .setLong("elapsed_threshold_frequent", 92 * DAY_MS)
                .setLong("elapsed_threshold_rare", 93 * DAY_MS)
                .setLong("elapsed_threshold_restricted", 94 * DAY_MS);
        mInjector.mPropertiesChangedListener
                .onPropertiesChanged(mInjector.getDeviceConfigProperties());
        for (int i = 0; i < mController.mAppStandbyElapsedThresholds.length; ++i) {
            assertEquals((90 + i) * DAY_MS, mController.mAppStandbyElapsedThresholds[i]);
        }

        // Only set a few values
        mInjector.mSettingsBuilder
                .setString("elapsed_threshold_active", null)
                .setLong("elapsed_threshold_working_set", 31 * DAY_MS)
                .setLong("elapsed_threshold_frequent", 62 * DAY_MS)
                .setString("elapsed_threshold_rare", null)
                .setLong("elapsed_threshold_restricted", 93 * DAY_MS);
        mInjector.mPropertiesChangedListener
                .onPropertiesChanged(mInjector.getDeviceConfigProperties());

        assertEquals(DEFAULT_ELAPSED_TIME_THRESHOLDS[0],
                mController.mAppStandbyElapsedThresholds[0]);
        assertEquals(31 * DAY_MS, mController.mAppStandbyElapsedThresholds[1]);
        assertEquals(62 * DAY_MS, mController.mAppStandbyElapsedThresholds[2]);
        assertEquals(DEFAULT_ELAPSED_TIME_THRESHOLDS[3],
                mController.mAppStandbyElapsedThresholds[3]);
        assertEquals(93 * DAY_MS, mController.mAppStandbyElapsedThresholds[4]);
    }

    @Test
    public void testChangingSettings_ScreenThreshold_Invalid() {
        mInjector.mSettingsBuilder
                .setLong("screen_threshold_active", -1)
                .setLong("screen_threshold_working_set", -1)
                .setLong("screen_threshold_frequent", -1)
                .setLong("screen_threshold_rare", -1)
                .setLong("screen_threshold_restricted", -1);
        mInjector.mPropertiesChangedListener
                .onPropertiesChanged(mInjector.getDeviceConfigProperties());
        for (int i = 0; i < MINIMUM_SCREEN_TIME_THRESHOLDS.length; ++i) {
            assertEquals(MINIMUM_SCREEN_TIME_THRESHOLDS[i],
                    mController.mAppStandbyScreenThresholds[i]);
        }
    }

    @Test
    public void testChangingSettings_ScreenThreshold_Valid() {
        // Effectively clear values
        mInjector.mSettingsBuilder
                .setString("screen_threshold_active", null)
                .setString("screen_threshold_working_set", null)
                .setString("screen_threshold_frequent", null)
                .setString("screen_threshold_rare", null)
                .setString("screen_threshold_restricted", null);
        mInjector.mPropertiesChangedListener
                .onPropertiesChanged(mInjector.getDeviceConfigProperties());
        for (int i = 0; i < DEFAULT_SCREEN_TIME_THRESHOLDS.length; ++i) {
            assertEquals(DEFAULT_SCREEN_TIME_THRESHOLDS[i],
                    mController.mAppStandbyScreenThresholds[i]);
        }

        // Set really high thresholds
        mInjector.mSettingsBuilder
                .setLong("screen_threshold_active", 90 * DAY_MS)
                .setLong("screen_threshold_working_set", 91 * DAY_MS)
                .setLong("screen_threshold_frequent", 92 * DAY_MS)
                .setLong("screen_threshold_rare", 93 * DAY_MS)
                .setLong("screen_threshold_restricted", 94 * DAY_MS);
        mInjector.mPropertiesChangedListener
                .onPropertiesChanged(mInjector.getDeviceConfigProperties());
        for (int i = 0; i < mController.mAppStandbyScreenThresholds.length; ++i) {
            assertEquals((90 + i) * DAY_MS, mController.mAppStandbyScreenThresholds[i]);
        }

        // Only set a few values
        mInjector.mSettingsBuilder
                .setString("screen_threshold_active", null)
                .setLong("screen_threshold_working_set", 31 * DAY_MS)
                .setLong("screen_threshold_frequent", 62 * DAY_MS)
                .setString("screen_threshold_rare", null)
                .setLong("screen_threshold_restricted", 93 * DAY_MS);
        mInjector.mPropertiesChangedListener
                .onPropertiesChanged(mInjector.getDeviceConfigProperties());

        assertEquals(DEFAULT_SCREEN_TIME_THRESHOLDS[0], mController.mAppStandbyScreenThresholds[0]);
        assertEquals(31 * DAY_MS, mController.mAppStandbyScreenThresholds[1]);
        assertEquals(62 * DAY_MS, mController.mAppStandbyScreenThresholds[2]);
        assertEquals(DEFAULT_SCREEN_TIME_THRESHOLDS[3], mController.mAppStandbyScreenThresholds[3]);
        assertEquals(93 * DAY_MS, mController.mAppStandbyScreenThresholds[4]);
    }

    /**
     * Package with ACCESS_BACKGROUND_LOCATION permission has minimum bucket
     * STANDBY_BUCKET_FREQUENT.
     * @throws Exception
     */
    @Test
    public void testBackgroundLocationBucket() throws Exception {
        reportEvent(mController, USER_INTERACTION, mInjector.mElapsedRealtime,
                PACKAGE_BACKGROUND_LOCATION);
        waitAndAssertBucket(STANDBY_BUCKET_ACTIVE, PACKAGE_BACKGROUND_LOCATION);

        mInjector.mElapsedRealtime += RESTRICTED_THRESHOLD;
        // Make sure PACKAGE_BACKGROUND_LOCATION does not get lowered than STANDBY_BUCKET_FREQUENT.
        mController.setAppStandbyBucket(PACKAGE_BACKGROUND_LOCATION, USER_ID, STANDBY_BUCKET_RARE,
                REASON_MAIN_TIMEOUT);
        waitAndAssertBucket(STANDBY_BUCKET_FREQUENT, PACKAGE_BACKGROUND_LOCATION);
    }

    @Test
    public void testBatteryStatsNoteEvent() throws Exception {
        mInjector.mExpectedNoteEventPackage = PACKAGE_1;
        reportEvent(mController, USER_INTERACTION, 0, PACKAGE_1);

        mController.setAppStandbyBucket(PACKAGE_1, USER_ID, STANDBY_BUCKET_RARE,
                REASON_MAIN_FORCED_BY_USER);
        waitAndAssertLastNoteEvent(BatteryStats.HistoryItem.EVENT_PACKAGE_INACTIVE);

        mController.setAppStandbyBucket(PACKAGE_1, USER_ID, STANDBY_BUCKET_ACTIVE,
                REASON_MAIN_FORCED_BY_USER);
        waitAndAssertLastNoteEvent(BatteryStats.HistoryItem.EVENT_PACKAGE_ACTIVE);

        // Since we're staying on the PACKAGE_ACTIVE side, noteEvent shouldn't be called.
        // Reset the last event to confirm the method isn't called.
        mInjector.mLastNoteEvent = BatteryStats.HistoryItem.EVENT_NONE;
        mController.setAppStandbyBucket(PACKAGE_1, USER_ID, STANDBY_BUCKET_WORKING_SET,
                REASON_MAIN_FORCED_BY_USER);
        waitAndAssertLastNoteEvent(BatteryStats.HistoryItem.EVENT_NONE);

        mController.setAppStandbyBucket(PACKAGE_1, USER_ID, STANDBY_BUCKET_RARE,
                REASON_MAIN_FORCED_BY_USER);
        waitAndAssertLastNoteEvent(BatteryStats.HistoryItem.EVENT_PACKAGE_INACTIVE);

        // Since we're staying on the PACKAGE_ACTIVE side, noteEvent shouldn't be called.
        // Reset the last event to confirm the method isn't called.
        mInjector.mLastNoteEvent = BatteryStats.HistoryItem.EVENT_NONE;
        mController.setAppStandbyBucket(PACKAGE_1, USER_ID, STANDBY_BUCKET_RESTRICTED,
                REASON_MAIN_FORCED_BY_USER);
        waitAndAssertLastNoteEvent(BatteryStats.HistoryItem.EVENT_NONE);

        mController.setAppStandbyBucket(PACKAGE_1, USER_ID, STANDBY_BUCKET_FREQUENT,
                REASON_MAIN_FORCED_BY_USER);
        waitAndAssertLastNoteEvent(BatteryStats.HistoryItem.EVENT_PACKAGE_ACTIVE);

        mController.setAppStandbyBucket(PACKAGE_1, USER_ID, STANDBY_BUCKET_RESTRICTED,
                REASON_MAIN_FORCED_BY_USER);
        waitAndAssertLastNoteEvent(BatteryStats.HistoryItem.EVENT_PACKAGE_INACTIVE);

        mController.setAppStandbyBucket(PACKAGE_1, USER_ID, STANDBY_BUCKET_EXEMPTED,
                REASON_MAIN_FORCED_BY_USER);
        waitAndAssertLastNoteEvent(BatteryStats.HistoryItem.EVENT_PACKAGE_ACTIVE);
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

    private void setAdminProtectedPackages(int userId, String... packageNames) {
        Set<String> adminProtectedPackages = packageNames != null ? new ArraySet<>(
                Arrays.asList(packageNames)) : null;
        mController.setAdminProtectedPackages(adminProtectedPackages, userId);
    }

    private void assertAdminProtectedPackagesForTest(int userId, String... packageNames) {
        final Set<String> actualAdminProtectedPackages =
                mController.getAdminProtectedPackagesForTest(userId);
        if (packageNames == null) {
            if (actualAdminProtectedPackages != null && !actualAdminProtectedPackages.isEmpty()) {
                fail("Admin protected packages should be null; " + getAdminAppsStr(userId,
                        actualAdminProtectedPackages));
            }
            return;
        }
        assertEquals(packageNames.length, actualAdminProtectedPackages.size());
        for (String adminProtectedPackage : packageNames) {
            assertTrue(actualAdminProtectedPackages.contains(adminProtectedPackage));
        }
    }

    private void setAndAssertBucket(String pkg, int user, int bucket, int reason) throws Exception {
        rearmLatch(pkg);
        mController.setAppStandbyBucket(pkg, user, bucket, reason);
        mStateChangedLatch.await(1, TimeUnit.SECONDS);
        waitAndAssertBucket("Failed to set package bucket", bucket, PACKAGE_1);
    }

    private void rearmLatch(String pkgName) {
        mLatchPkgName = pkgName;
        mStateChangedLatch = new CountDownLatch(1);
    }

    private void rearmLatch() {
        rearmLatch(null);
    }

    private void rearmQuotaBumpLatch(String pkgName, int userId) {
        mLatchPkgName = pkgName;
        mLatchUserId = userId;
        mQuotaBumpLatch = new CountDownLatch(1);
    }

    private void flushHandler(AppStandbyController controller) {
        assertTrue("Failed to flush handler!", controller.flushHandler(FLUSH_TIMEOUT_MILLISECONDS));
        // Some AppStandbyController handler messages queue another handler message. Flush again
        // to catch those as well.
        assertTrue("Failed to flush handler (the second time)!",
                controller.flushHandler(FLUSH_TIMEOUT_MILLISECONDS));
    }
}
