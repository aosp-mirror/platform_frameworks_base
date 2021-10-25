/*
 * Copyright (C) 2022 The Android Open Source Project
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

import static android.app.ActivityManager.RESTRICTION_LEVEL_ADAPTIVE_BUCKET;
import static android.app.ActivityManager.RESTRICTION_LEVEL_BACKGROUND_RESTRICTED;
import static android.app.ActivityManager.RESTRICTION_LEVEL_EXEMPTED;
import static android.app.ActivityManager.RESTRICTION_LEVEL_RESTRICTED_BUCKET;
import static android.app.usage.UsageStatsManager.REASON_MAIN_FORCED_BY_SYSTEM;
import static android.app.usage.UsageStatsManager.REASON_MAIN_FORCED_BY_USER;
import static android.app.usage.UsageStatsManager.REASON_MAIN_USAGE;
import static android.app.usage.UsageStatsManager.REASON_SUB_FORCED_USER_FLAG_INTERACTION;
import static android.app.usage.UsageStatsManager.REASON_SUB_USAGE_USER_INTERACTION;
import static android.app.usage.UsageStatsManager.STANDBY_BUCKET_ACTIVE;
import static android.app.usage.UsageStatsManager.STANDBY_BUCKET_EXEMPTED;
import static android.app.usage.UsageStatsManager.STANDBY_BUCKET_FREQUENT;
import static android.app.usage.UsageStatsManager.STANDBY_BUCKET_NEVER;
import static android.app.usage.UsageStatsManager.STANDBY_BUCKET_RARE;
import static android.app.usage.UsageStatsManager.STANDBY_BUCKET_RESTRICTED;
import static android.app.usage.UsageStatsManager.STANDBY_BUCKET_WORKING_SET;

import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;

import static com.android.server.am.AppRestrictionController.STOCK_PM_FLAGS;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.anyBoolean;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.anyLong;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import android.app.ActivityManagerInternal;
import android.app.AppOpsManager;
import android.app.IActivityManager;
import android.app.IUidObserver;
import android.app.usage.AppStandbyInfo;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.pm.PackageManagerInternal;
import android.os.Handler;
import android.os.MessageQueue;
import android.os.Process;
import android.os.SystemClock;
import android.os.UserHandle;
import android.util.Log;

import androidx.test.runner.AndroidJUnit4;

import com.android.server.AppStateTracker;
import com.android.server.DeviceIdleInternal;
import com.android.server.am.AppRestrictionController.AppRestrictionLevelListener;
import com.android.server.apphibernation.AppHibernationManagerInternal;
import com.android.server.pm.UserManagerInternal;
import com.android.server.usage.AppStandbyInternal;
import com.android.server.usage.AppStandbyInternal.AppIdleStateChangeListener;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Tests for {@link AppRestrictionController}.
 *
 * Build/Install/Run:
 *  atest FrameworksMockingServicesTests:BackgroundRestrictionTest
 */
@RunWith(AndroidJUnit4.class)
public final class BackgroundRestrictionTest {
    private static final String TAG = BackgroundRestrictionTest.class.getSimpleName();

    private static final int TEST_USER0 = UserHandle.USER_SYSTEM;
    private static final int TEST_USER1 = UserHandle.MIN_SECONDARY_USER_ID;
    private static final int[] TEST_USERS = new int[] {TEST_USER0, TEST_USER1};
    private static final String TEST_PACKAGE_BASE = "test_";
    private static final int TEST_PACKAGE_APPID_BASE = Process.FIRST_APPLICATION_UID;
    private static final int[] TEST_PACKAGE_USER0_UIDS = new int[] {
        UserHandle.getUid(TEST_USER0, TEST_PACKAGE_APPID_BASE + 0),
        UserHandle.getUid(TEST_USER0, TEST_PACKAGE_APPID_BASE + 1),
        UserHandle.getUid(TEST_USER0, TEST_PACKAGE_APPID_BASE + 2),
        UserHandle.getUid(TEST_USER0, TEST_PACKAGE_APPID_BASE + 3),
        UserHandle.getUid(TEST_USER0, TEST_PACKAGE_APPID_BASE + 4),
        UserHandle.getUid(TEST_USER0, TEST_PACKAGE_APPID_BASE + 5),
        UserHandle.getUid(TEST_USER0, TEST_PACKAGE_APPID_BASE + 6),
    };
    private static final int[] TEST_PACKAGE_USER1_UIDS = new int[] {
        UserHandle.getUid(TEST_USER1, TEST_PACKAGE_APPID_BASE + 0),
        UserHandle.getUid(TEST_USER1, TEST_PACKAGE_APPID_BASE + 1),
        UserHandle.getUid(TEST_USER1, TEST_PACKAGE_APPID_BASE + 2),
        UserHandle.getUid(TEST_USER1, TEST_PACKAGE_APPID_BASE + 3),
        UserHandle.getUid(TEST_USER1, TEST_PACKAGE_APPID_BASE + 4),
        UserHandle.getUid(TEST_USER1, TEST_PACKAGE_APPID_BASE + 5),
        UserHandle.getUid(TEST_USER1, TEST_PACKAGE_APPID_BASE + 6),
    };
    private static final int[][] TEST_UIDS = new int[][] {
        TEST_PACKAGE_USER0_UIDS,
        TEST_PACKAGE_USER1_UIDS,
    };
    private static final int[] TEST_STANDBY_BUCKETS = new int[] {
        STANDBY_BUCKET_EXEMPTED,
        STANDBY_BUCKET_ACTIVE,
        STANDBY_BUCKET_WORKING_SET,
        STANDBY_BUCKET_FREQUENT,
        STANDBY_BUCKET_RARE,
        STANDBY_BUCKET_RESTRICTED,
        STANDBY_BUCKET_NEVER,
    };

    @Mock private ActivityManagerInternal mActivityManagerInternal;
    @Mock private AppOpsManager mAppOpsManager;
    @Mock private AppStandbyInternal mAppStandbyInternal;
    @Mock private AppHibernationManagerInternal mAppHibernationInternal;
    @Mock private AppStateTracker mAppStateTracker;
    @Mock private DeviceIdleInternal mDeviceIdleInternal;
    @Mock private IActivityManager mIActivityManager;
    @Mock private UserManagerInternal mUserManagerInternal;
    @Mock private PackageManager mPackageManager;
    @Mock private PackageManagerInternal mPackageManagerInternal;

    @Captor private ArgumentCaptor<AppStateTracker.BackgroundRestrictedAppListener> mFasListenerCap;
    private AppStateTracker.BackgroundRestrictedAppListener mFasListener;

    @Captor private ArgumentCaptor<AppIdleStateChangeListener> mIdleStateListenerCap;
    private AppIdleStateChangeListener mIdleStateListener;

    @Captor private ArgumentCaptor<IUidObserver> mUidObserversCap;
    private IUidObserver mUidObservers;

    private Context mContext = getInstrumentation().getTargetContext();
    private TestBgRestrictionInjector mInjector;
    private AppRestrictionController mBgRestrictionController;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        initController();
    }

    private void initController() throws Exception {
        mInjector = new TestBgRestrictionInjector(mContext);
        mBgRestrictionController = spy(new AppRestrictionController(mInjector));

        doReturn(TEST_USERS).when(mUserManagerInternal).getUserIds();
        for (int userId: TEST_USERS) {
            final ArrayList<AppStandbyInfo> appStandbyInfoList = new ArrayList<>();
            for (int i = 0; i < TEST_STANDBY_BUCKETS.length; i++) {
                final String packageName = TEST_PACKAGE_BASE + i;
                final int uid = UserHandle.getUid(userId, TEST_PACKAGE_APPID_BASE + i);
                appStandbyInfoList.add(new AppStandbyInfo(packageName, TEST_STANDBY_BUCKETS[i]));
                doReturn(uid)
                        .when(mPackageManagerInternal)
                        .getPackageUid(packageName, STOCK_PM_FLAGS, userId);
                doReturn(false)
                        .when(mAppStateTracker)
                        .isAppBackgroundRestricted(uid, packageName);
                doReturn(TEST_STANDBY_BUCKETS[i])
                        .when(mAppStandbyInternal)
                        .getAppStandbyBucket(eq(packageName), eq(userId), anyLong(), anyBoolean());
                doReturn(new String[]{packageName})
                        .when(mPackageManager)
                        .getPackagesForUid(eq(uid));
            }
            doReturn(appStandbyInfoList).when(mAppStandbyInternal).getAppStandbyBuckets(userId);
        }

        mBgRestrictionController.onSystemReady();

        verify(mInjector.getAppStateTracker())
                .addBackgroundRestrictedAppListener(mFasListenerCap.capture());
        mFasListener = mFasListenerCap.getValue();
        verify(mInjector.getAppStandbyInternal())
                .addListener(mIdleStateListenerCap.capture());
        mIdleStateListener = mIdleStateListenerCap.getValue();
        verify(mInjector.getIActivityManager())
                .registerUidObserver(mUidObserversCap.capture(),
                    anyInt(), anyInt(), anyString());
        mUidObservers = mUidObserversCap.getValue();
    }

    @After
    public void tearDown() {
        mBgRestrictionController.getBackgroundHandlerThread().quitSafely();
    }

    @Test
    public void testInitialLevels() throws Exception {
        final int[] expectedLevels = {
            RESTRICTION_LEVEL_EXEMPTED,
            RESTRICTION_LEVEL_ADAPTIVE_BUCKET,
            RESTRICTION_LEVEL_ADAPTIVE_BUCKET,
            RESTRICTION_LEVEL_ADAPTIVE_BUCKET,
            RESTRICTION_LEVEL_ADAPTIVE_BUCKET,
            RESTRICTION_LEVEL_RESTRICTED_BUCKET,
            RESTRICTION_LEVEL_BACKGROUND_RESTRICTED,
        };
        for (int i = 0; i < TEST_UIDS.length; i++) {
            final int[] uids = TEST_UIDS[i];
            for (int j = 0; j < uids.length; j++) {
                assertEquals(expectedLevels[j],
                        mBgRestrictionController.getRestrictionLevel(uids[j]));
                assertEquals(expectedLevels[j],
                        mBgRestrictionController.getRestrictionLevel(uids[j],
                                TEST_PACKAGE_BASE + j));
            }
        }
    }

    @Test
    public void testTogglingBackgroundRestrict() throws Exception {
        final int testPkgIndex = 2;
        final String testPkgName = TEST_PACKAGE_BASE + testPkgIndex;
        final int testUser = TEST_USER0;
        final int testUid = UserHandle.getUid(testUser, TEST_PACKAGE_APPID_BASE + testPkgIndex);
        final TestAppRestrictionLevelListener listener = new TestAppRestrictionLevelListener();
        final long timeout = 1_000; // ms

        mBgRestrictionController.addAppRestrictionLevelListener(listener);

        setBackgroundRestrict(testPkgName, testUid, false, listener);

        // Verify the current settings.
        verifyRestrictionLevel(RESTRICTION_LEVEL_ADAPTIVE_BUCKET, testPkgName, testUid);
        assertEquals(STANDBY_BUCKET_WORKING_SET, mInjector.getAppStandbyInternal()
                .getAppStandbyBucket(testPkgName, testUser, SystemClock.elapsedRealtime(), false));

        // Now toggling ON the background restrict.
        setBackgroundRestrict(testPkgName, testUid, true, listener);

        // We should have been in the background restricted level.
        verifyRestrictionLevel(RESTRICTION_LEVEL_BACKGROUND_RESTRICTED, testPkgName, testUid);

        listener.verify(timeout, testUid, testPkgName, RESTRICTION_LEVEL_BACKGROUND_RESTRICTED);

        // The app should have been put into the restricted standby bucket.
        verify(mInjector.getAppStandbyInternal(), atLeast(1)).restrictApp(
                eq(testPkgName),
                eq(testUser),
                eq(REASON_MAIN_FORCED_BY_USER),
                eq(REASON_SUB_FORCED_USER_FLAG_INTERACTION));

        // Changing to the restricted standby bucket won't make a difference.
        listener.mLatchHolder[0] = new CountDownLatch(1);
        mIdleStateListener.onAppIdleStateChanged(testPkgName, testUser, false,
                STANDBY_BUCKET_RESTRICTED, REASON_MAIN_USAGE);
        waitForIdleHandler(mBgRestrictionController.getBackgroundHandler());
        verifyRestrictionLevel(RESTRICTION_LEVEL_BACKGROUND_RESTRICTED, testPkgName, testUid);
        try {
            listener.verify(timeout, testUid, testPkgName, RESTRICTION_LEVEL_BACKGROUND_RESTRICTED);
            fail("There shouldn't be any level change events");
        } catch (Exception e) {
            // Expected.
        }

        clearInvocations(mInjector.getAppStandbyInternal());

        // Toggling back.
        setBackgroundRestrict(testPkgName, testUid, false, listener);

        // It should have gone back to adaptive level.
        verifyRestrictionLevel(RESTRICTION_LEVEL_ADAPTIVE_BUCKET, testPkgName, testUid);

        // The app standby bucket should be the rare.
        verify(mInjector.getAppStandbyInternal(), atLeast(1)).maybeUnrestrictApp(
                eq(testPkgName),
                eq(testUser),
                eq(REASON_MAIN_FORCED_BY_USER),
                eq(REASON_SUB_FORCED_USER_FLAG_INTERACTION),
                eq(REASON_MAIN_USAGE),
                eq(REASON_SUB_USAGE_USER_INTERACTION));

        listener.verify(timeout, testUid, testPkgName, RESTRICTION_LEVEL_ADAPTIVE_BUCKET);

        clearInvocations(mInjector.getAppStandbyInternal());

        // Now set its UID state active.
        mUidObservers.onUidActive(testUid);

        // Now toggling ON the background restrict.
        setBackgroundRestrict(testPkgName, testUid, true, listener);

        // We should have been in the background restricted level.
        verifyRestrictionLevel(RESTRICTION_LEVEL_BACKGROUND_RESTRICTED, testPkgName, testUid);

        listener.verify(timeout, testUid, testPkgName, RESTRICTION_LEVEL_BACKGROUND_RESTRICTED);

        // The app should have NOT been put into the restricted standby bucket.
        verify(mInjector.getAppStandbyInternal(), never()).restrictApp(
                eq(testPkgName),
                eq(testUser),
                eq(REASON_MAIN_FORCED_BY_USER),
                eq(REASON_SUB_FORCED_USER_FLAG_INTERACTION));

        // Now set its UID to idle.
        mUidObservers.onUidIdle(testUid, false);

        // The app should have been put into the restricted standby bucket because we're idle now.
        verify(mInjector.getAppStandbyInternal(), timeout(timeout).times(1)).restrictApp(
                eq(testPkgName),
                eq(testUser),
                eq(REASON_MAIN_FORCED_BY_USER),
                eq(REASON_SUB_FORCED_USER_FLAG_INTERACTION));
    }

    @Test
    public void testTogglingStandbyBucket() throws Exception {
        final int testPkgIndex = 2;
        final String testPkgName = TEST_PACKAGE_BASE + testPkgIndex;
        final int testUser = TEST_USER0;
        final int testUid = UserHandle.getUid(testUser, TEST_PACKAGE_APPID_BASE + testPkgIndex);
        final TestAppRestrictionLevelListener listener = new TestAppRestrictionLevelListener();
        final long timeout = 1_000; // ms

        mBgRestrictionController.addAppRestrictionLevelListener(listener);

        setBackgroundRestrict(testPkgName, testUid, false, listener);

        // Verify the current settings.
        verifyRestrictionLevel(RESTRICTION_LEVEL_ADAPTIVE_BUCKET, testPkgName, testUid);

        for (int bucket: Arrays.asList(STANDBY_BUCKET_ACTIVE, STANDBY_BUCKET_WORKING_SET,
                STANDBY_BUCKET_FREQUENT, STANDBY_BUCKET_RARE)) {
            listener.mLatchHolder[0] = new CountDownLatch(1);
            mIdleStateListener.onAppIdleStateChanged(testPkgName, testUser, false,
                    bucket, REASON_MAIN_USAGE);
            waitForIdleHandler(mBgRestrictionController.getBackgroundHandler());
            verifyRestrictionLevel(RESTRICTION_LEVEL_ADAPTIVE_BUCKET, testPkgName, testUid);

            try {
                listener.verify(timeout, testUid, testPkgName, RESTRICTION_LEVEL_ADAPTIVE_BUCKET);
                fail("There shouldn't be any level change events");
            } catch (Exception e) {
                // Expected.
            }
        }

        // Toggling restricted bucket.
        listener.mLatchHolder[0] = new CountDownLatch(1);
        mIdleStateListener.onAppIdleStateChanged(testPkgName, testUser, false,
                STANDBY_BUCKET_RESTRICTED, REASON_MAIN_USAGE);
        waitForIdleHandler(mBgRestrictionController.getBackgroundHandler());
        verifyRestrictionLevel(RESTRICTION_LEVEL_RESTRICTED_BUCKET, testPkgName, testUid);
        listener.verify(timeout, testUid, testPkgName, RESTRICTION_LEVEL_RESTRICTED_BUCKET);

        // Toggling exempted bucket.
        listener.mLatchHolder[0] = new CountDownLatch(1);
        mIdleStateListener.onAppIdleStateChanged(testPkgName, testUser, false,
                STANDBY_BUCKET_EXEMPTED, REASON_MAIN_FORCED_BY_SYSTEM);
        waitForIdleHandler(mBgRestrictionController.getBackgroundHandler());
        verifyRestrictionLevel(RESTRICTION_LEVEL_EXEMPTED, testPkgName, testUid);
        listener.verify(timeout, testUid, testPkgName, RESTRICTION_LEVEL_EXEMPTED);
    }

    private void setBackgroundRestrict(String pkgName, int uid, boolean restricted,
            TestAppRestrictionLevelListener listener) throws Exception {
        Log.i(TAG, "Setting background restrict to " + restricted + " for " + pkgName + " " + uid);
        listener.mLatchHolder[0] = new CountDownLatch(1);
        doReturn(restricted).when(mAppStateTracker).isAppBackgroundRestricted(uid, pkgName);
        mFasListener.updateBackgroundRestrictedForUidPackage(uid, pkgName, restricted);
        waitForIdleHandler(mBgRestrictionController.getBackgroundHandler());
    }

    private class TestAppRestrictionLevelListener implements AppRestrictionLevelListener {
        final CountDownLatch[] mLatchHolder = new CountDownLatch[1];
        final int[] mUidHolder = new int[1];
        final String[] mPkgNameHolder = new String[1];
        final int[] mLevelHolder = new int[1];

        @Override
        public void onRestrictionLevelChanged(int uid, String packageName, int newLevel) {
            mUidHolder[0] = uid;
            mPkgNameHolder[0] = packageName;
            mLevelHolder[0] = newLevel;
            mLatchHolder[0].countDown();
        };

        void verify(long timeout, int uid, String pkgName, int level) throws Exception {
            if (!mLatchHolder[0].await(timeout, TimeUnit.MILLISECONDS)) {
                throw new TimeoutException();
            }
            assertEquals(uid, mUidHolder[0]);
            assertEquals(pkgName, mPkgNameHolder[0]);
            assertEquals(level, mLevelHolder[0]);
        }
    }

    private void verifyRestrictionLevel(int level, String pkgName, int uid) {
        assertEquals(level, mBgRestrictionController.getRestrictionLevel(uid));
        assertEquals(level, mBgRestrictionController.getRestrictionLevel(uid, pkgName));
    }

    private void waitForIdleHandler(Handler handler) {
        waitForIdleHandler(handler, Duration.ofSeconds(1));
    }

    private void waitForIdleHandler(Handler handler, Duration timeout) {
        final MessageQueue queue = handler.getLooper().getQueue();
        final CountDownLatch latch = new CountDownLatch(1);
        queue.addIdleHandler(() -> {
            latch.countDown();
            // Remove idle handler
            return false;
        });
        try {
            latch.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            fail("Interrupted unexpectedly: " + e);
        }
    }

    private class TestBgRestrictionInjector extends AppRestrictionController.Injector {
        private Context mContext;

        TestBgRestrictionInjector(Context context) {
            super(context);
            mContext = context;
        }

        @Override
        void initAppStateTrackers(AppRestrictionController controller) {
        }

        @Override
        AppRestrictionController getAppRestrictionController() {
            return mBgRestrictionController;
        }

        @Override
        AppOpsManager getAppOpsManager() {
            return mAppOpsManager;
        }

        @Override
        AppStandbyInternal getAppStandbyInternal() {
            return mAppStandbyInternal;
        }

        @Override
        AppHibernationManagerInternal getAppHibernationInternal() {
            return mAppHibernationInternal;
        }

        @Override
        AppStateTracker getAppStateTracker() {
            return mAppStateTracker;
        }

        @Override
        IActivityManager getIActivityManager() {
            return mIActivityManager;
        }

        @Override
        UserManagerInternal getUserManagerInternal() {
            return mUserManagerInternal;
        }

        @Override
        PackageManagerInternal getPackageManagerInternal() {
            return mPackageManagerInternal;
        }

        @Override
        PackageManager getPackageManager() {
            return mPackageManager;
        }
    }

    private class TestBaseTrackerInjector<T extends BaseAppStatePolicy>
            extends BaseAppStateTracker.Injector<T> {
        @Override
        void onSystemReady() {
            getPolicy().onSystemReady();
        }

        @Override
        ActivityManagerInternal getActivityManagerInternal() {
            return BackgroundRestrictionTest.this.mActivityManagerInternal;
        }

        @Override
        DeviceIdleInternal getDeviceIdleInternal() {
            return BackgroundRestrictionTest.this.mDeviceIdleInternal;
        }

        @Override
        UserManagerInternal getUserManagerInternal() {
            return BackgroundRestrictionTest.this.mUserManagerInternal;
        }
    }
}
