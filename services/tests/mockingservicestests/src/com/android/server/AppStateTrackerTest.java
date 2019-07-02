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
package com.android.server;

import static android.app.usage.UsageStatsManager.REASON_MAIN_DEFAULT;
import static android.app.usage.UsageStatsManager.REASON_MAIN_USAGE;

import static com.android.server.AppStateTracker.TARGET_OP;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.ActivityManager;
import android.app.ActivityManagerInternal;
import android.app.AppOpsManager;
import android.app.AppOpsManager.OpEntry;
import android.app.AppOpsManager.PackageOps;
import android.app.IActivityManager;
import android.app.IUidObserver;
import android.app.usage.UsageStatsManager;
import android.app.usage.UsageStatsManagerInternal;
import android.app.usage.UsageStatsManagerInternal.AppIdleStateChangeListener;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager.ServiceType;
import android.os.PowerManagerInternal;
import android.os.PowerSaveState;
import android.os.Process;
import android.os.RemoteException;
import android.os.UserHandle;
import android.provider.Settings.Global;
import android.test.mock.MockContentResolver;
import android.util.ArraySet;
import android.util.Pair;

import com.android.internal.app.IAppOpsCallback;
import com.android.internal.app.IAppOpsService;
import com.android.server.AppStateTracker.Listener;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.stubbing.Answer;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

/**
 * Tests for {@link AppStateTracker}
 *
 * Run with:
 atest $ANDROID_BUILD_TOP/frameworks/base/services/tests/mockingservicestests/src/com/android/server/AppStateTrackerTest.java
 */
@SmallTest
@RunWith(AndroidJUnit4.class)
public class AppStateTrackerTest {

    private class AppStateTrackerTestable extends AppStateTracker {
        AppStateTrackerTestable() {
            super(mMockContext, Looper.getMainLooper());
        }

        @Override
        AppOpsManager injectAppOpsManager() {
            return mMockAppOpsManager;
        }

        @Override
        IAppOpsService injectIAppOpsService() {
            return mMockIAppOpsService;
        }

        @Override
        IActivityManager injectIActivityManager() {
            return mMockIActivityManager;
        }

        @Override
        ActivityManagerInternal injectActivityManagerInternal() {
            return mMockIActivityManagerInternal;
        }

        @Override
        PowerManagerInternal injectPowerManagerInternal() {
            return mMockPowerManagerInternal;
        }

        @Override
        UsageStatsManagerInternal injectUsageStatsManagerInternal() {
            return mMockUsageStatsManagerInternal;
        }

        @Override
        int injectGetGlobalSettingInt(String key, int def) {
            Integer val = mGlobalSettings.get(key);

            return (val == null) ? def : val;
        }

        @Override
        boolean isSmallBatteryDevice() { return mIsSmallBatteryDevice; };
    }

    private static final int UID_1 = Process.FIRST_APPLICATION_UID + 1;
    private static final int UID_2 = Process.FIRST_APPLICATION_UID + 2;
    private static final int UID_3 = Process.FIRST_APPLICATION_UID + 3;
    private static final int UID_10_1 = UserHandle.getUid(10, UID_1);
    private static final int UID_10_2 = UserHandle.getUid(10, UID_2);
    private static final int UID_10_3 = UserHandle.getUid(10, UID_3);
    private static final String PACKAGE_1 = "package1";
    private static final String PACKAGE_2 = "package2";
    private static final String PACKAGE_3 = "package3";
    private static final String PACKAGE_SYSTEM = "android";

    private Handler mMainHandler;

    @Mock
    private Context mMockContext;

    @Mock
    private IActivityManager mMockIActivityManager;

    @Mock
    private ActivityManagerInternal mMockIActivityManagerInternal;

    @Mock
    private AppOpsManager mMockAppOpsManager;

    @Mock
    private IAppOpsService mMockIAppOpsService;

    @Mock
    private PowerManagerInternal mMockPowerManagerInternal;

    @Mock
    private UsageStatsManagerInternal mMockUsageStatsManagerInternal;

    private MockContentResolver mMockContentResolver;

    private IUidObserver mIUidObserver;
    private IAppOpsCallback.Stub mAppOpsCallback;
    private Consumer<PowerSaveState> mPowerSaveObserver;
    private BroadcastReceiver mReceiver;
    private AppIdleStateChangeListener mAppIdleStateChangeListener;

    private boolean mPowerSaveMode;
    private boolean mIsSmallBatteryDevice;

    private final ArraySet<Pair<Integer, String>> mRestrictedPackages = new ArraySet();

    private final HashMap<String, Integer> mGlobalSettings = new HashMap<>();

    private Answer<List<PackageOps>> mGetPackagesForOps =
            inv -> new ArrayList<PackageOps>();

    @Before
    public void setUp() {
        mMainHandler = new Handler(Looper.getMainLooper());
    }

    private void waitUntilMainHandlerDrain() throws Exception {
        final CountDownLatch l = new CountDownLatch(1);
        mMainHandler.post(() -> {
            l.countDown();
        });
        assertTrue(l.await(5, TimeUnit.SECONDS));
    }

    private PowerSaveState getPowerSaveState() {
        return new PowerSaveState.Builder().setBatterySaverEnabled(mPowerSaveMode).build();
    }

    private AppStateTrackerTestable newInstance() throws Exception {
        MockitoAnnotations.initMocks(this);

        when(mMockIAppOpsService.checkOperation(eq(TARGET_OP), anyInt(), anyString()))
                .thenAnswer(inv -> {
                    return mRestrictedPackages.indexOf(
                            Pair.create(inv.getArgument(1), inv.getArgument(2))) >= 0 ?
                            AppOpsManager.MODE_IGNORED : AppOpsManager.MODE_ALLOWED;
                });

        final AppStateTrackerTestable instance = new AppStateTrackerTestable();

        return instance;
    }

    private void callStart(AppStateTrackerTestable instance) throws RemoteException {

        // Set up functions that start() calls.
        when(mMockPowerManagerInternal.getLowPowerState(eq(ServiceType.FORCE_ALL_APPS_STANDBY)))
                .thenAnswer(inv -> getPowerSaveState());
        when(mMockAppOpsManager.getPackagesForOps(
                any(int[].class)
                )).thenAnswer(mGetPackagesForOps);

        mMockContentResolver = new MockContentResolver();
        when(mMockContext.getContentResolver()).thenReturn(mMockContentResolver);

        // Call start.
        instance.onSystemServicesReady();

        // Capture the listeners.
        ArgumentCaptor<IUidObserver> uidObserverArgumentCaptor =
                ArgumentCaptor.forClass(IUidObserver.class);
        ArgumentCaptor<IAppOpsCallback.Stub> appOpsCallbackCaptor =
                ArgumentCaptor.forClass(IAppOpsCallback.Stub.class);
        ArgumentCaptor<Consumer<PowerSaveState>> powerSaveObserverCaptor =
                ArgumentCaptor.forClass(Consumer.class);
        ArgumentCaptor<BroadcastReceiver> receiverCaptor =
                ArgumentCaptor.forClass(BroadcastReceiver.class);
        ArgumentCaptor<AppIdleStateChangeListener> appIdleStateChangeListenerCaptor =
                ArgumentCaptor.forClass(AppIdleStateChangeListener.class);

        verify(mMockIActivityManager).registerUidObserver(
                uidObserverArgumentCaptor.capture(),
                eq(ActivityManager.UID_OBSERVER_GONE | ActivityManager.UID_OBSERVER_IDLE
                        | ActivityManager.UID_OBSERVER_ACTIVE
                        | ActivityManager.UID_OBSERVER_PROCSTATE),
                eq(ActivityManager.PROCESS_STATE_UNKNOWN),
                isNull());
        verify(mMockIAppOpsService).startWatchingMode(
                eq(AppOpsManager.OP_RUN_ANY_IN_BACKGROUND),
                isNull(),
                appOpsCallbackCaptor.capture());
        verify(mMockPowerManagerInternal).registerLowPowerModeObserver(
                eq(ServiceType.FORCE_ALL_APPS_STANDBY),
                powerSaveObserverCaptor.capture());

        verify(mMockContext).registerReceiver(
                receiverCaptor.capture(), any(IntentFilter.class));
        verify(mMockUsageStatsManagerInternal).addAppIdleStateChangeListener(
                appIdleStateChangeListenerCaptor.capture());

        mIUidObserver = uidObserverArgumentCaptor.getValue();
        mAppOpsCallback = appOpsCallbackCaptor.getValue();
        mPowerSaveObserver = powerSaveObserverCaptor.getValue();
        mReceiver = receiverCaptor.getValue();
        mAppIdleStateChangeListener = appIdleStateChangeListenerCaptor.getValue();

        assertNotNull(mIUidObserver);
        assertNotNull(mAppOpsCallback);
        assertNotNull(mPowerSaveObserver);
        assertNotNull(mReceiver);
        assertNotNull(instance.mFlagsObserver);
    }

    private void setAppOps(int uid, String packageName, boolean restrict) throws RemoteException {
        final Pair p = Pair.create(uid, packageName);
        if (restrict) {
            mRestrictedPackages.add(p);
        } else {
            mRestrictedPackages.remove(p);
        }
        if (mAppOpsCallback != null) {
            mAppOpsCallback.opChanged(AppOpsManager.OP_RUN_ANY_IN_BACKGROUND, uid, packageName);
        }
    }

    private static final int NONE = 0;
    private static final int ALARMS_ONLY = 1 << 0;
    private static final int JOBS_ONLY = 1 << 1;
    private static final int JOBS_AND_ALARMS = ALARMS_ONLY | JOBS_ONLY;

    private void areRestricted(AppStateTrackerTestable instance, int uid, String packageName,
            int restrictionTypes, boolean exemptFromBatterySaver) {
        assertEquals(((restrictionTypes & JOBS_ONLY) != 0),
                instance.areJobsRestricted(uid, packageName, exemptFromBatterySaver));
        assertEquals(((restrictionTypes & ALARMS_ONLY) != 0),
                instance.areAlarmsRestricted(uid, packageName, exemptFromBatterySaver));
    }

    private void areRestricted(AppStateTrackerTestable instance, int uid, String packageName,
            int restrictionTypes) {
        areRestricted(instance, uid, packageName, restrictionTypes,
                /*exemptFromBatterySaver=*/ false);
    }

    private void areRestrictedWithExemption(AppStateTrackerTestable instance,
            int uid, String packageName, int restrictionTypes) {
        areRestricted(instance, uid, packageName, restrictionTypes,
                /*exemptFromBatterySaver=*/ true);
    }

    @Test
    public void testAll() throws Exception {
        final AppStateTrackerTestable instance = newInstance();
        callStart(instance);

        assertFalse(instance.isForceAllAppsStandbyEnabled());
        areRestricted(instance, UID_1, PACKAGE_1, NONE);
        areRestricted(instance, UID_2, PACKAGE_2, NONE);
        areRestricted(instance, Process.SYSTEM_UID, PACKAGE_SYSTEM, NONE);

        areRestrictedWithExemption(instance, UID_1, PACKAGE_1, NONE);
        areRestrictedWithExemption(instance, UID_2, PACKAGE_2, NONE);
        areRestrictedWithExemption(instance, Process.SYSTEM_UID, PACKAGE_SYSTEM, NONE);

        mPowerSaveMode = true;
        mPowerSaveObserver.accept(getPowerSaveState());

        assertTrue(instance.isForceAllAppsStandbyEnabled());

        areRestricted(instance, UID_1, PACKAGE_1, JOBS_AND_ALARMS);
        areRestricted(instance, UID_2, PACKAGE_2, JOBS_AND_ALARMS);
        areRestricted(instance, Process.SYSTEM_UID, PACKAGE_SYSTEM, NONE);

        areRestrictedWithExemption(instance, UID_1, PACKAGE_1, NONE);
        areRestrictedWithExemption(instance, UID_2, PACKAGE_2, NONE);
        areRestrictedWithExemption(instance, Process.SYSTEM_UID, PACKAGE_SYSTEM, NONE);

        // Toggle the foreground state.
        mPowerSaveMode = true;
        mPowerSaveObserver.accept(getPowerSaveState());

        assertFalse(instance.isUidActive(UID_1));
        assertFalse(instance.isUidActive(UID_2));
        assertTrue(instance.isUidActive(Process.SYSTEM_UID));

        mIUidObserver.onUidActive(UID_1);
        waitUntilMainHandlerDrain();
        areRestricted(instance, UID_1, PACKAGE_1, NONE);
        areRestricted(instance, UID_2, PACKAGE_2, JOBS_AND_ALARMS);
        areRestricted(instance, Process.SYSTEM_UID, PACKAGE_SYSTEM, NONE);
        assertTrue(instance.isUidActive(UID_1));
        assertFalse(instance.isUidActive(UID_2));

        mIUidObserver.onUidGone(UID_1, /*disable=*/ false);
        waitUntilMainHandlerDrain();
        areRestricted(instance, UID_1, PACKAGE_1, JOBS_AND_ALARMS);
        areRestricted(instance, UID_2, PACKAGE_2, JOBS_AND_ALARMS);
        areRestricted(instance, Process.SYSTEM_UID, PACKAGE_SYSTEM, NONE);
        assertFalse(instance.isUidActive(UID_1));
        assertFalse(instance.isUidActive(UID_2));

        mIUidObserver.onUidActive(UID_1);
        waitUntilMainHandlerDrain();
        areRestricted(instance, UID_1, PACKAGE_1, NONE);
        areRestricted(instance, UID_2, PACKAGE_2, JOBS_AND_ALARMS);
        areRestricted(instance, Process.SYSTEM_UID, PACKAGE_SYSTEM, NONE);

        mIUidObserver.onUidIdle(UID_1, /*disable=*/ false);
        waitUntilMainHandlerDrain();
        areRestricted(instance, UID_1, PACKAGE_1, JOBS_AND_ALARMS);
        areRestricted(instance, UID_2, PACKAGE_2, JOBS_AND_ALARMS);
        areRestricted(instance, Process.SYSTEM_UID, PACKAGE_SYSTEM, NONE);
        assertFalse(instance.isUidActive(UID_1));
        assertFalse(instance.isUidActive(UID_2));

        // Toggle the app ops.
        mPowerSaveMode = false;
        mPowerSaveObserver.accept(getPowerSaveState());

        assertTrue(instance.isRunAnyInBackgroundAppOpsAllowed(UID_1, PACKAGE_1));
        assertTrue(instance.isRunAnyInBackgroundAppOpsAllowed(UID_10_1, PACKAGE_1));
        assertTrue(instance.isRunAnyInBackgroundAppOpsAllowed(UID_2, PACKAGE_2));
        assertTrue(instance.isRunAnyInBackgroundAppOpsAllowed(UID_10_2, PACKAGE_2));

        areRestricted(instance, UID_1, PACKAGE_1, NONE);
        areRestricted(instance, UID_10_1, PACKAGE_1, NONE);
        areRestricted(instance, UID_2, PACKAGE_2, NONE);
        areRestricted(instance, UID_10_2, PACKAGE_2, NONE);
        areRestricted(instance, Process.SYSTEM_UID, PACKAGE_SYSTEM, NONE);

        setAppOps(UID_1, PACKAGE_1, true);
        setAppOps(UID_10_2, PACKAGE_2, true);
        assertFalse(instance.isRunAnyInBackgroundAppOpsAllowed(UID_1, PACKAGE_1));
        assertTrue(instance.isRunAnyInBackgroundAppOpsAllowed(UID_10_1, PACKAGE_1));
        assertTrue(instance.isRunAnyInBackgroundAppOpsAllowed(UID_2, PACKAGE_2));
        assertFalse(instance.isRunAnyInBackgroundAppOpsAllowed(UID_10_2, PACKAGE_2));

        areRestricted(instance, UID_1, PACKAGE_1, JOBS_AND_ALARMS);
        areRestricted(instance, UID_10_1, PACKAGE_1, NONE);
        areRestricted(instance, UID_2, PACKAGE_2, NONE);
        areRestricted(instance, UID_10_2, PACKAGE_2, JOBS_AND_ALARMS);
        areRestricted(instance, Process.SYSTEM_UID, PACKAGE_SYSTEM, NONE);

        // Toggle power saver, should still be the same.
        mPowerSaveMode = true;
        mPowerSaveObserver.accept(getPowerSaveState());

        mPowerSaveMode = false;
        mPowerSaveObserver.accept(getPowerSaveState());

        areRestricted(instance, UID_1, PACKAGE_1, JOBS_AND_ALARMS);
        areRestricted(instance, UID_10_1, PACKAGE_1, NONE);
        areRestricted(instance, UID_2, PACKAGE_2, NONE);
        areRestricted(instance, UID_10_2, PACKAGE_2, JOBS_AND_ALARMS);
        areRestricted(instance, Process.SYSTEM_UID, PACKAGE_SYSTEM, NONE);

        // Clear the app ops and update the whitelist.
        setAppOps(UID_1, PACKAGE_1, false);
        setAppOps(UID_10_2, PACKAGE_2, false);

        mPowerSaveMode = true;
        mPowerSaveObserver.accept(getPowerSaveState());

        areRestricted(instance, UID_1, PACKAGE_1, JOBS_AND_ALARMS);
        areRestricted(instance, UID_10_1, PACKAGE_1, JOBS_AND_ALARMS);
        areRestricted(instance, UID_2, PACKAGE_2, JOBS_AND_ALARMS);
        areRestricted(instance, UID_10_2, PACKAGE_2, JOBS_AND_ALARMS);
        areRestricted(instance, UID_3, PACKAGE_3, JOBS_AND_ALARMS);
        areRestricted(instance, UID_10_3, PACKAGE_3, JOBS_AND_ALARMS);
        areRestricted(instance, Process.SYSTEM_UID, PACKAGE_SYSTEM, NONE);

        instance.setPowerSaveWhitelistAppIds(new int[] {UID_1}, new int[] {}, new int[] {UID_2});

        areRestricted(instance, UID_1, PACKAGE_1, NONE);
        areRestricted(instance, UID_10_1, PACKAGE_1, NONE);
        areRestricted(instance, UID_2, PACKAGE_2, ALARMS_ONLY);
        areRestricted(instance, UID_10_2, PACKAGE_2, ALARMS_ONLY);
        areRestricted(instance, UID_3, PACKAGE_3, JOBS_AND_ALARMS);
        areRestricted(instance, UID_10_3, PACKAGE_3, JOBS_AND_ALARMS);
        areRestricted(instance, Process.SYSTEM_UID, PACKAGE_SYSTEM, NONE);

        // Again, make sure toggling the global state doesn't change it.
        mPowerSaveMode = false;
        mPowerSaveObserver.accept(getPowerSaveState());

        mPowerSaveMode = true;
        mPowerSaveObserver.accept(getPowerSaveState());

        areRestricted(instance, UID_1, PACKAGE_1, NONE);
        areRestricted(instance, UID_10_1, PACKAGE_1, NONE);
        areRestricted(instance, UID_2, PACKAGE_2, ALARMS_ONLY);
        areRestricted(instance, UID_10_2, PACKAGE_2, ALARMS_ONLY);
        areRestricted(instance, UID_3, PACKAGE_3, JOBS_AND_ALARMS);
        areRestricted(instance, UID_10_3, PACKAGE_3, JOBS_AND_ALARMS);
        areRestricted(instance, Process.SYSTEM_UID, PACKAGE_SYSTEM, NONE);

        assertTrue(instance.isUidPowerSaveWhitelisted(UID_1));
        assertTrue(instance.isUidPowerSaveWhitelisted(UID_10_1));
        assertFalse(instance.isUidPowerSaveWhitelisted(UID_2));
        assertFalse(instance.isUidPowerSaveWhitelisted(UID_10_2));

        assertFalse(instance.isUidTempPowerSaveWhitelisted(UID_1));
        assertFalse(instance.isUidTempPowerSaveWhitelisted(UID_10_1));
        assertTrue(instance.isUidTempPowerSaveWhitelisted(UID_2));
        assertTrue(instance.isUidTempPowerSaveWhitelisted(UID_10_2));
    }

    @Test
    public void testPowerSaveUserWhitelist() throws Exception {
        final AppStateTrackerTestable instance = newInstance();
        instance.setPowerSaveWhitelistAppIds(new int[] {}, new int[] {UID_1, UID_2}, new int[] {});
        assertTrue(instance.isUidPowerSaveUserWhitelisted(UID_1));
        assertTrue(instance.isUidPowerSaveUserWhitelisted(UID_2));
        assertFalse(instance.isUidPowerSaveUserWhitelisted(UID_3));
    }

    @Test
    public void testUidStateForeground() throws Exception {
        final AppStateTrackerTestable instance = newInstance();
        callStart(instance);

        mIUidObserver.onUidActive(UID_1);

        waitUntilMainHandlerDrain();
        assertTrue(instance.isUidActive(UID_1));
        assertFalse(instance.isUidActive(UID_2));
        assertTrue(instance.isUidActive(Process.SYSTEM_UID));

        assertTrue(instance.isUidActiveSynced(UID_1));
        assertFalse(instance.isUidActiveSynced(UID_2));
        assertTrue(instance.isUidActiveSynced(Process.SYSTEM_UID));

        assertFalse(instance.isUidInForeground(UID_1));
        assertFalse(instance.isUidInForeground(UID_2));
        assertTrue(instance.isUidInForeground(Process.SYSTEM_UID));


        mIUidObserver.onUidStateChanged(UID_2,
                ActivityManager.PROCESS_STATE_BOUND_FOREGROUND_SERVICE, 0);

        waitUntilMainHandlerDrain();
        assertTrue(instance.isUidActive(UID_1));
        assertFalse(instance.isUidActive(UID_2));
        assertTrue(instance.isUidActive(Process.SYSTEM_UID));

        assertTrue(instance.isUidActiveSynced(UID_1));
        assertFalse(instance.isUidActiveSynced(UID_2));
        assertTrue(instance.isUidActiveSynced(Process.SYSTEM_UID));

        assertFalse(instance.isUidInForeground(UID_1));
        assertTrue(instance.isUidInForeground(UID_2));
        assertTrue(instance.isUidInForeground(Process.SYSTEM_UID));


        mIUidObserver.onUidStateChanged(UID_1,
                ActivityManager.PROCESS_STATE_FOREGROUND_SERVICE, 0);

        waitUntilMainHandlerDrain();
        assertTrue(instance.isUidActive(UID_1));
        assertFalse(instance.isUidActive(UID_2));
        assertTrue(instance.isUidActive(Process.SYSTEM_UID));

        assertTrue(instance.isUidInForeground(UID_1));
        assertTrue(instance.isUidInForeground(UID_2));
        assertTrue(instance.isUidInForeground(Process.SYSTEM_UID));

        mIUidObserver.onUidGone(UID_1, true);

        waitUntilMainHandlerDrain();
        assertFalse(instance.isUidActive(UID_1));
        assertFalse(instance.isUidActive(UID_2));
        assertTrue(instance.isUidActive(Process.SYSTEM_UID));

        assertFalse(instance.isUidInForeground(UID_1));
        assertTrue(instance.isUidInForeground(UID_2));
        assertTrue(instance.isUidInForeground(Process.SYSTEM_UID));

        mIUidObserver.onUidIdle(UID_2, true);

        waitUntilMainHandlerDrain();
        assertFalse(instance.isUidActive(UID_1));
        assertFalse(instance.isUidActive(UID_2));
        assertTrue(instance.isUidActive(Process.SYSTEM_UID));

        assertFalse(instance.isUidInForeground(UID_1));
        assertFalse(instance.isUidInForeground(UID_2));
        assertTrue(instance.isUidInForeground(Process.SYSTEM_UID));

        mIUidObserver.onUidStateChanged(UID_1,
                ActivityManager.PROCESS_STATE_IMPORTANT_FOREGROUND, 0);

        waitUntilMainHandlerDrain();
        assertFalse(instance.isUidActive(UID_1));
        assertFalse(instance.isUidActive(UID_2));
        assertTrue(instance.isUidActive(Process.SYSTEM_UID));

        assertTrue(instance.isUidInForeground(UID_1));
        assertFalse(instance.isUidInForeground(UID_2));
        assertTrue(instance.isUidInForeground(Process.SYSTEM_UID));

        mIUidObserver.onUidStateChanged(UID_1,
                ActivityManager.PROCESS_STATE_TRANSIENT_BACKGROUND, 0);

        waitUntilMainHandlerDrain();
        assertFalse(instance.isUidActive(UID_1));
        assertFalse(instance.isUidActive(UID_2));
        assertTrue(instance.isUidActive(Process.SYSTEM_UID));

        assertFalse(instance.isUidActiveSynced(UID_1));
        assertFalse(instance.isUidActiveSynced(UID_2));
        assertTrue(instance.isUidActiveSynced(Process.SYSTEM_UID));

        assertFalse(instance.isUidInForeground(UID_1));
        assertFalse(instance.isUidInForeground(UID_2));
        assertTrue(instance.isUidInForeground(Process.SYSTEM_UID));

        // The result from AMI.isUidActive() only affects isUidActiveSynced().
        when(mMockIActivityManagerInternal.isUidActive(anyInt())).thenReturn(true);

        assertFalse(instance.isUidActive(UID_1));
        assertFalse(instance.isUidActive(UID_2));
        assertTrue(instance.isUidActive(Process.SYSTEM_UID));

        assertTrue(instance.isUidActiveSynced(UID_1));
        assertTrue(instance.isUidActiveSynced(UID_2));
        assertTrue(instance.isUidActiveSynced(Process.SYSTEM_UID));

        assertFalse(instance.isUidInForeground(UID_1));
        assertFalse(instance.isUidInForeground(UID_2));
        assertTrue(instance.isUidInForeground(Process.SYSTEM_UID));

    }

    @Test
    public void testExempt() throws Exception {
        final AppStateTrackerTestable instance = newInstance();
        callStart(instance);

        assertFalse(instance.isForceAllAppsStandbyEnabled());
        areRestricted(instance, UID_1, PACKAGE_1, NONE);
        areRestricted(instance, UID_2, PACKAGE_2, NONE);
        areRestricted(instance, Process.SYSTEM_UID, PACKAGE_SYSTEM, NONE);

        mPowerSaveMode = true;
        mPowerSaveObserver.accept(getPowerSaveState());

        assertTrue(instance.isForceAllAppsStandbyEnabled());

        areRestricted(instance, UID_1, PACKAGE_1, JOBS_AND_ALARMS);
        areRestricted(instance, UID_2, PACKAGE_2, JOBS_AND_ALARMS);
        areRestricted(instance, UID_10_2, PACKAGE_2, JOBS_AND_ALARMS);
        areRestricted(instance, Process.SYSTEM_UID, PACKAGE_SYSTEM, NONE);

        // Exempt package 2 on user-10.
        mAppIdleStateChangeListener.onAppIdleStateChanged(PACKAGE_2, /*user=*/ 10, false,
                UsageStatsManager.STANDBY_BUCKET_EXEMPTED, REASON_MAIN_DEFAULT);

        areRestricted(instance, UID_1, PACKAGE_1, JOBS_AND_ALARMS);
        areRestricted(instance, UID_2, PACKAGE_2, JOBS_AND_ALARMS);
        areRestricted(instance, UID_10_2, PACKAGE_2, NONE);

        areRestrictedWithExemption(instance, UID_1, PACKAGE_1, NONE);
        areRestrictedWithExemption(instance, UID_2, PACKAGE_2, NONE);
        areRestrictedWithExemption(instance, UID_10_2, PACKAGE_2, NONE);

        // Exempt package 1 on user-0.
        mAppIdleStateChangeListener.onAppIdleStateChanged(PACKAGE_1, /*user=*/ 0, false,
                UsageStatsManager.STANDBY_BUCKET_EXEMPTED, REASON_MAIN_DEFAULT);

        areRestricted(instance, UID_1, PACKAGE_1, NONE);
        areRestricted(instance, UID_2, PACKAGE_2, JOBS_AND_ALARMS);
        areRestricted(instance, UID_10_2, PACKAGE_2, NONE);

        // Unexempt package 2 on user-10.
        mAppIdleStateChangeListener.onAppIdleStateChanged(PACKAGE_2, /*user=*/ 10, false,
                UsageStatsManager.STANDBY_BUCKET_ACTIVE, REASON_MAIN_USAGE);

        areRestricted(instance, UID_1, PACKAGE_1, NONE);
        areRestricted(instance, UID_2, PACKAGE_2, JOBS_AND_ALARMS);
        areRestricted(instance, UID_10_2, PACKAGE_2, JOBS_AND_ALARMS);

        // Check force-app-standby.
        // EXEMPT doesn't exempt from force-app-standby.
        mPowerSaveMode = false;
        mPowerSaveObserver.accept(getPowerSaveState());

        mAppIdleStateChangeListener.onAppIdleStateChanged(PACKAGE_1, /*user=*/ 0, false,
                UsageStatsManager.STANDBY_BUCKET_EXEMPTED, REASON_MAIN_DEFAULT);
        mAppIdleStateChangeListener.onAppIdleStateChanged(PACKAGE_2, /*user=*/ 0, false,
                UsageStatsManager.STANDBY_BUCKET_EXEMPTED, REASON_MAIN_DEFAULT);

        setAppOps(UID_1, PACKAGE_1, true);

        areRestricted(instance, UID_1, PACKAGE_1, JOBS_AND_ALARMS);
        areRestricted(instance, UID_2, PACKAGE_2, NONE);

        areRestrictedWithExemption(instance, UID_1, PACKAGE_1, JOBS_AND_ALARMS);
        areRestrictedWithExemption(instance, UID_2, PACKAGE_2, NONE);
    }

    @Test
    public void loadPersistedAppOps() throws Exception {
        final AppStateTrackerTestable instance = newInstance();

        final List<PackageOps> ops = new ArrayList<>();

        //--------------------------------------------------
        List<OpEntry> entries = new ArrayList<>();
        entries.add(new OpEntry(
                AppOpsManager.OP_ACCESS_NOTIFICATIONS,
                AppOpsManager.MODE_IGNORED));
        entries.add(new OpEntry(
                AppStateTracker.TARGET_OP,
                AppOpsManager.MODE_IGNORED));

        ops.add(new PackageOps(PACKAGE_1, UID_1, entries));

        //--------------------------------------------------
        entries = new ArrayList<>();
        entries.add(new OpEntry(
                AppStateTracker.TARGET_OP,
                AppOpsManager.MODE_IGNORED));

        ops.add(new PackageOps(PACKAGE_2, UID_2, entries));

        //--------------------------------------------------
        entries = new ArrayList<>();
        entries.add(new OpEntry(
                AppStateTracker.TARGET_OP,
                AppOpsManager.MODE_ALLOWED));

        ops.add(new PackageOps(PACKAGE_1, UID_10_1, entries));

        //--------------------------------------------------
        entries = new ArrayList<>();
        entries.add(new OpEntry(
                AppStateTracker.TARGET_OP,
                AppOpsManager.MODE_IGNORED));
        entries.add(new OpEntry(
                AppOpsManager.OP_ACCESS_NOTIFICATIONS,
                AppOpsManager.MODE_IGNORED));

        ops.add(new PackageOps(PACKAGE_3, UID_10_3, entries));

        mGetPackagesForOps = inv -> {
            final int[] arg = (int[]) inv.getArgument(0);
            assertEquals(1, arg.length);
            assertEquals(AppOpsManager.OP_RUN_ANY_IN_BACKGROUND, arg[0]);
            return ops;
        };

        callStart(instance);

        assertFalse(instance.isRunAnyInBackgroundAppOpsAllowed(UID_1, PACKAGE_1));
        assertFalse(instance.isRunAnyInBackgroundAppOpsAllowed(UID_2, PACKAGE_2));
        assertTrue(instance.isRunAnyInBackgroundAppOpsAllowed(UID_3, PACKAGE_3));

        assertTrue(instance.isRunAnyInBackgroundAppOpsAllowed(UID_10_1, PACKAGE_1));
        assertTrue(instance.isRunAnyInBackgroundAppOpsAllowed(UID_10_2, PACKAGE_2));
        assertFalse(instance.isRunAnyInBackgroundAppOpsAllowed(UID_10_3, PACKAGE_3));
    }

    private void assertNoCallbacks(Listener l) throws Exception {
        waitUntilMainHandlerDrain();
        verify(l, times(0)).updateAllJobs();
        verify(l, times(0)).updateJobsForUid(anyInt(), anyBoolean());
        verify(l, times(0)).updateJobsForUidPackage(anyInt(), anyString(), anyBoolean());

        verify(l, times(0)).unblockAllUnrestrictedAlarms();
        verify(l, times(0)).unblockAlarmsForUid(anyInt());
        verify(l, times(0)).unblockAlarmsForUidPackage(anyInt(), anyString());
        reset(l);
    }

    @Test
    public void testPowerSaveListener() throws Exception {
        final AppStateTrackerTestable instance = newInstance();
        callStart(instance);

        Listener l = mock(Listener.class);
        instance.addListener(l);

        // Power save on.
        mPowerSaveMode = true;
        mPowerSaveObserver.accept(getPowerSaveState());

        waitUntilMainHandlerDrain();
        verify(l, times(1)).updateAllJobs();
        verify(l, times(0)).updateJobsForUid(anyInt(), anyBoolean());
        verify(l, times(0)).updateJobsForUidPackage(anyInt(), anyString(), anyBoolean());

        verify(l, times(0)).unblockAllUnrestrictedAlarms();
        verify(l, times(0)).unblockAlarmsForUid(anyInt());
        verify(l, times(0)).unblockAlarmsForUidPackage(anyInt(), anyString());
        reset(l);

        // Power save off.
        mPowerSaveMode = false;
        mPowerSaveObserver.accept(getPowerSaveState());

        waitUntilMainHandlerDrain();
        verify(l, times(1)).updateAllJobs();
        verify(l, times(0)).updateJobsForUid(anyInt(), anyBoolean());
        verify(l, times(0)).updateJobsForUidPackage(anyInt(), anyString(), anyBoolean());

        verify(l, times(1)).unblockAllUnrestrictedAlarms();
        verify(l, times(0)).unblockAlarmsForUid(anyInt());
        verify(l, times(0)).unblockAlarmsForUidPackage(anyInt(), anyString());
        reset(l);

        // Updating to the same state should not fire listener
        mPowerSaveMode = false;
        mPowerSaveObserver.accept(getPowerSaveState());

        assertNoCallbacks(l);
    }

    @Test
    public void testAllListeners() throws Exception {
        final AppStateTrackerTestable instance = newInstance();
        callStart(instance);

        Listener l = mock(Listener.class);
        instance.addListener(l);

        // -------------------------------------------------------------------------
        // Test with apppops.

        setAppOps(UID_10_2, PACKAGE_2, true);

        waitUntilMainHandlerDrain();
        verify(l, times(0)).updateAllJobs();
        verify(l, times(0)).updateJobsForUid(anyInt(), anyBoolean());
        verify(l, times(1)).updateJobsForUidPackage(eq(UID_10_2), eq(PACKAGE_2), anyBoolean());

        verify(l, times(0)).unblockAllUnrestrictedAlarms();
        verify(l, times(0)).unblockAlarmsForUid(anyInt());
        verify(l, times(0)).unblockAlarmsForUidPackage(anyInt(), anyString());
        reset(l);

        setAppOps(UID_10_2, PACKAGE_2, false);

        waitUntilMainHandlerDrain();
        verify(l, times(0)).updateAllJobs();
        verify(l, times(0)).updateJobsForUid(anyInt(), anyBoolean());
        verify(l, times(1)).updateJobsForUidPackage(eq(UID_10_2), eq(PACKAGE_2), anyBoolean());

        verify(l, times(0)).unblockAllUnrestrictedAlarms();
        verify(l, times(0)).unblockAlarmsForUid(anyInt());
        verify(l, times(1)).unblockAlarmsForUidPackage(eq(UID_10_2), eq(PACKAGE_2));
        reset(l);

        setAppOps(UID_10_2, PACKAGE_2, false);

        verify(l, times(0)).updateAllJobs();
        verify(l, times(0)).updateJobsForUid(anyInt(), anyBoolean());
        verify(l, times(0)).updateJobsForUidPackage(anyInt(), anyString(), anyBoolean());

        verify(l, times(0)).unblockAllUnrestrictedAlarms();
        verify(l, times(0)).unblockAlarmsForUid(anyInt());
        verify(l, times(0)).unblockAlarmsForUidPackage(anyInt(), anyString());

        // Unrestrict while battery saver is on. Shouldn't fire.
        mPowerSaveMode = true;
        mPowerSaveObserver.accept(getPowerSaveState());

        // Note toggling appops while BS is on will suppress unblockAlarmsForUidPackage().
        setAppOps(UID_10_2, PACKAGE_2, true);

        waitUntilMainHandlerDrain();
        verify(l, times(1)).updateAllJobs();
        verify(l, times(0)).updateJobsForUid(anyInt(), anyBoolean());
        verify(l, times(1)).updateJobsForUidPackage(eq(UID_10_2), eq(PACKAGE_2), anyBoolean());

        verify(l, times(0)).unblockAllUnrestrictedAlarms();
        verify(l, times(0)).unblockAlarmsForUid(anyInt());
        verify(l, times(0)).unblockAlarmsForUidPackage(anyInt(), anyString());
        reset(l);

        // Battery saver off.
        mPowerSaveMode = false;
        mPowerSaveObserver.accept(getPowerSaveState());

        waitUntilMainHandlerDrain();
        verify(l, times(1)).updateAllJobs();
        verify(l, times(0)).updateJobsForUid(anyInt(), anyBoolean());
        verify(l, times(0)).updateJobsForUidPackage(anyInt(), anyString(), anyBoolean());

        verify(l, times(1)).unblockAllUnrestrictedAlarms();
        verify(l, times(0)).unblockAlarmsForUid(anyInt());
        verify(l, times(0)).unblockAlarmsForUidPackage(anyInt(), anyString());
        reset(l);

        // -------------------------------------------------------------------------
        // Tests with system/user/temp whitelist.

        instance.setPowerSaveWhitelistAppIds(new int[] {UID_1, UID_2}, new int[] {}, new int[] {});

        waitUntilMainHandlerDrain();
        verify(l, times(1)).updateAllJobs();
        verify(l, times(0)).updateJobsForUid(anyInt(), anyBoolean());
        verify(l, times(0)).updateJobsForUidPackage(anyInt(), anyString(), anyBoolean());

        verify(l, times(0)).unblockAllUnrestrictedAlarms();
        verify(l, times(0)).unblockAlarmsForUid(anyInt());
        verify(l, times(0)).unblockAlarmsForUidPackage(anyInt(), anyString());
        reset(l);

        instance.setPowerSaveWhitelistAppIds(new int[] {UID_2}, new int[] {}, new int[] {});

        waitUntilMainHandlerDrain();
        verify(l, times(1)).updateAllJobs();
        verify(l, times(0)).updateJobsForUid(anyInt(), anyBoolean());
        verify(l, times(0)).updateJobsForUidPackage(anyInt(), anyString(), anyBoolean());

        verify(l, times(1)).unblockAllUnrestrictedAlarms();
        verify(l, times(0)).unblockAlarmsForUid(anyInt());
        verify(l, times(0)).unblockAlarmsForUidPackage(anyInt(), anyString());
        reset(l);

        // Update temp whitelist.
        instance.setPowerSaveWhitelistAppIds(new int[] {UID_2}, new int[] {},
                new int[] {UID_1, UID_3});

        waitUntilMainHandlerDrain();
        verify(l, times(1)).updateAllJobs();
        verify(l, times(0)).updateJobsForUid(anyInt(), anyBoolean());
        verify(l, times(0)).updateJobsForUidPackage(anyInt(), anyString(), anyBoolean());

        verify(l, times(0)).unblockAllUnrestrictedAlarms();
        verify(l, times(0)).unblockAlarmsForUid(anyInt());
        verify(l, times(0)).unblockAlarmsForUidPackage(anyInt(), anyString());
        reset(l);

        instance.setPowerSaveWhitelistAppIds(new int[] {UID_2}, new int[] {}, new int[] {UID_3});

        waitUntilMainHandlerDrain();
        verify(l, times(1)).updateAllJobs();
        verify(l, times(0)).updateJobsForUid(anyInt(), anyBoolean());
        verify(l, times(0)).updateJobsForUidPackage(anyInt(), anyString(), anyBoolean());

        verify(l, times(0)).unblockAllUnrestrictedAlarms();
        verify(l, times(0)).unblockAlarmsForUid(anyInt());
        verify(l, times(0)).unblockAlarmsForUidPackage(anyInt(), anyString());
        reset(l);

        // Do the same thing with battery saver on. (Currently same callbacks are called.)
        mPowerSaveMode = true;
        mPowerSaveObserver.accept(getPowerSaveState());

        waitUntilMainHandlerDrain();
        verify(l, times(1)).updateAllJobs();
        verify(l, times(0)).updateJobsForUid(anyInt(), anyBoolean());
        verify(l, times(0)).updateJobsForUidPackage(anyInt(), anyString(), anyBoolean());

        verify(l, times(0)).unblockAllUnrestrictedAlarms();
        verify(l, times(0)).unblockAlarmsForUid(anyInt());
        verify(l, times(0)).unblockAlarmsForUidPackage(anyInt(), anyString());
        reset(l);

        instance.setPowerSaveWhitelistAppIds(new int[] {UID_1, UID_2}, new int[] {}, new int[] {});

        waitUntilMainHandlerDrain();
        // Called once for updating all whitelist and once for updating temp whitelist
        verify(l, times(2)).updateAllJobs();
        verify(l, times(0)).updateJobsForUid(anyInt(), anyBoolean());
        verify(l, times(0)).updateJobsForUidPackage(anyInt(), anyString(), anyBoolean());

        verify(l, times(0)).unblockAllUnrestrictedAlarms();
        verify(l, times(0)).unblockAlarmsForUid(anyInt());
        verify(l, times(0)).unblockAlarmsForUidPackage(anyInt(), anyString());
        reset(l);

        instance.setPowerSaveWhitelistAppIds(new int[] {UID_2}, new int[] {}, new int[] {});

        waitUntilMainHandlerDrain();
        verify(l, times(1)).updateAllJobs();
        verify(l, times(0)).updateJobsForUid(anyInt(), anyBoolean());
        verify(l, times(0)).updateJobsForUidPackage(anyInt(), anyString(), anyBoolean());

        verify(l, times(1)).unblockAllUnrestrictedAlarms();
        verify(l, times(0)).unblockAlarmsForUid(anyInt());
        verify(l, times(0)).unblockAlarmsForUidPackage(anyInt(), anyString());
        reset(l);

        // Update temp whitelist.
        instance.setPowerSaveWhitelistAppIds(new int[] {UID_2}, new int[] {},
                new int[] {UID_1, UID_3});

        waitUntilMainHandlerDrain();
        verify(l, times(1)).updateAllJobs();
        verify(l, times(0)).updateJobsForUid(anyInt(), anyBoolean());
        verify(l, times(0)).updateJobsForUidPackage(anyInt(), anyString(), anyBoolean());

        verify(l, times(0)).unblockAllUnrestrictedAlarms();
        verify(l, times(0)).unblockAlarmsForUid(anyInt());
        verify(l, times(0)).unblockAlarmsForUidPackage(anyInt(), anyString());
        reset(l);

        instance.setPowerSaveWhitelistAppIds(new int[] {UID_2}, new int[] {}, new int[] {UID_3});

        waitUntilMainHandlerDrain();
        verify(l, times(1)).updateAllJobs();
        verify(l, times(0)).updateJobsForUid(anyInt(), anyBoolean());
        verify(l, times(0)).updateJobsForUidPackage(anyInt(), anyString(), anyBoolean());

        verify(l, times(0)).unblockAllUnrestrictedAlarms();
        verify(l, times(0)).unblockAlarmsForUid(anyInt());
        verify(l, times(0)).unblockAlarmsForUidPackage(anyInt(), anyString());
        reset(l);


        // -------------------------------------------------------------------------
        // Tests with proc state changes.

        // With battery save.
        mPowerSaveMode = true;
        mPowerSaveObserver.accept(getPowerSaveState());

        mIUidObserver.onUidActive(UID_10_1);

        waitUntilMainHandlerDrain();
        verify(l, times(0)).updateAllJobs();
        verify(l, times(1)).updateJobsForUid(eq(UID_10_1), anyBoolean());
        verify(l, times(0)).updateJobsForUidPackage(anyInt(), anyString(), anyBoolean());

        verify(l, times(0)).unblockAllUnrestrictedAlarms();
        verify(l, times(1)).unblockAlarmsForUid(eq(UID_10_1));
        verify(l, times(0)).unblockAlarmsForUidPackage(anyInt(), anyString());
        reset(l);

        mIUidObserver.onUidGone(UID_10_1, true);

        waitUntilMainHandlerDrain();
        verify(l, times(0)).updateAllJobs();
        verify(l, times(1)).updateJobsForUid(eq(UID_10_1), anyBoolean());
        verify(l, times(0)).updateJobsForUidPackage(anyInt(), anyString(), anyBoolean());

        verify(l, times(0)).unblockAllUnrestrictedAlarms();
        verify(l, times(0)).unblockAlarmsForUid(anyInt());
        verify(l, times(0)).unblockAlarmsForUidPackage(anyInt(), anyString());
        reset(l);

        mIUidObserver.onUidActive(UID_10_1);

        waitUntilMainHandlerDrain();
        verify(l, times(0)).updateAllJobs();
        verify(l, times(1)).updateJobsForUid(eq(UID_10_1), anyBoolean());
        verify(l, times(0)).updateJobsForUidPackage(anyInt(), anyString(), anyBoolean());

        verify(l, times(0)).unblockAllUnrestrictedAlarms();
        verify(l, times(1)).unblockAlarmsForUid(eq(UID_10_1));
        verify(l, times(0)).unblockAlarmsForUidPackage(anyInt(), anyString());
        reset(l);

        mIUidObserver.onUidIdle(UID_10_1, true);

        waitUntilMainHandlerDrain();
        verify(l, times(0)).updateAllJobs();
        verify(l, times(1)).updateJobsForUid(eq(UID_10_1), anyBoolean());
        verify(l, times(0)).updateJobsForUidPackage(anyInt(), anyString(), anyBoolean());

        verify(l, times(0)).unblockAllUnrestrictedAlarms();
        verify(l, times(0)).unblockAlarmsForUid(anyInt());
        verify(l, times(0)).unblockAlarmsForUidPackage(anyInt(), anyString());
        reset(l);

        // Without battery save.
        mPowerSaveMode = false;
        mPowerSaveObserver.accept(getPowerSaveState());

        waitUntilMainHandlerDrain();
        verify(l, times(1)).updateAllJobs();
        verify(l, times(0)).updateJobsForUid(eq(UID_10_1), anyBoolean());
        verify(l, times(0)).updateJobsForUidPackage(anyInt(), anyString(), anyBoolean());

        verify(l, times(1)).unblockAllUnrestrictedAlarms();
        verify(l, times(0)).unblockAlarmsForUid(anyInt());
        verify(l, times(0)).unblockAlarmsForUidPackage(anyInt(), anyString());
        reset(l);

        mIUidObserver.onUidActive(UID_10_1);

        waitUntilMainHandlerDrain();
        verify(l, times(0)).updateAllJobs();
        verify(l, times(1)).updateJobsForUid(eq(UID_10_1), anyBoolean());
        verify(l, times(0)).updateJobsForUidPackage(anyInt(), anyString(), anyBoolean());

        verify(l, times(0)).unblockAllUnrestrictedAlarms();
        verify(l, times(1)).unblockAlarmsForUid(eq(UID_10_1));
        verify(l, times(0)).unblockAlarmsForUidPackage(anyInt(), anyString());
        reset(l);

        mIUidObserver.onUidGone(UID_10_1, true);

        waitUntilMainHandlerDrain();
        verify(l, times(0)).updateAllJobs();
        verify(l, times(1)).updateJobsForUid(eq(UID_10_1), anyBoolean());
        verify(l, times(0)).updateJobsForUidPackage(anyInt(), anyString(), anyBoolean());

        verify(l, times(0)).unblockAllUnrestrictedAlarms();
        verify(l, times(0)).unblockAlarmsForUid(anyInt());
        verify(l, times(0)).unblockAlarmsForUidPackage(anyInt(), anyString());
        reset(l);

        mIUidObserver.onUidActive(UID_10_1);

        waitUntilMainHandlerDrain();
        verify(l, times(0)).updateAllJobs();
        verify(l, times(1)).updateJobsForUid(eq(UID_10_1), anyBoolean());
        verify(l, times(0)).updateJobsForUidPackage(anyInt(), anyString(), anyBoolean());

        verify(l, times(0)).unblockAllUnrestrictedAlarms();
        verify(l, times(1)).unblockAlarmsForUid(eq(UID_10_1));
        verify(l, times(0)).unblockAlarmsForUidPackage(anyInt(), anyString());
        reset(l);

        mIUidObserver.onUidIdle(UID_10_1, true);

        waitUntilMainHandlerDrain();
        verify(l, times(0)).updateAllJobs();
        verify(l, times(1)).updateJobsForUid(eq(UID_10_1), anyBoolean());
        verify(l, times(0)).updateJobsForUidPackage(anyInt(), anyString(), anyBoolean());

        verify(l, times(0)).unblockAllUnrestrictedAlarms();
        verify(l, times(0)).unblockAlarmsForUid(anyInt());
        verify(l, times(0)).unblockAlarmsForUidPackage(anyInt(), anyString());
        reset(l);
    }

    @Test
    public void testUserRemoved() throws Exception {
        final AppStateTrackerTestable instance = newInstance();
        callStart(instance);

        mIUidObserver.onUidActive(UID_1);
        mIUidObserver.onUidActive(UID_10_1);

        waitUntilMainHandlerDrain();

        setAppOps(UID_2, PACKAGE_2, true);
        setAppOps(UID_10_2, PACKAGE_2, true);

        assertTrue(instance.isUidActive(UID_1));
        assertTrue(instance.isUidActive(UID_10_1));

        assertFalse(instance.isRunAnyInBackgroundAppOpsAllowed(UID_2, PACKAGE_2));
        assertFalse(instance.isRunAnyInBackgroundAppOpsAllowed(UID_10_2, PACKAGE_2));

        final Intent intent = new Intent(Intent.ACTION_USER_REMOVED);
        intent.putExtra(Intent.EXTRA_USER_HANDLE, 10);
        mReceiver.onReceive(mMockContext, intent);

        waitUntilMainHandlerDrain();

        assertTrue(instance.isUidActive(UID_1));
        assertFalse(instance.isUidActive(UID_10_1));

        assertFalse(instance.isRunAnyInBackgroundAppOpsAllowed(UID_2, PACKAGE_2));
        assertTrue(instance.isRunAnyInBackgroundAppOpsAllowed(UID_10_2, PACKAGE_2));
    }

    @Test
    public void testSmallBatteryAndPluggedIn() throws Exception {
        // This is a small battery device
        mIsSmallBatteryDevice = true;

        final AppStateTrackerTestable instance = newInstance();
        callStart(instance);
        assertFalse(instance.isForceAllAppsStandbyEnabled());

        // Setting/experiment for all app standby for small battery is enabled
        mGlobalSettings.put(Global.FORCED_APP_STANDBY_FOR_SMALL_BATTERY_ENABLED, 1);
        instance.mFlagsObserver.onChange(true,
                Global.getUriFor(Global.FORCED_APP_STANDBY_FOR_SMALL_BATTERY_ENABLED));
        assertTrue(instance.isForceAllAppsStandbyEnabled());

        // When battery is plugged in, force app standby is disabled
        Intent intent = new Intent(Intent.ACTION_BATTERY_CHANGED);
        intent.putExtra(BatteryManager.EXTRA_PLUGGED, BatteryManager.BATTERY_PLUGGED_USB);
        mReceiver.onReceive(mMockContext, intent);
        assertFalse(instance.isForceAllAppsStandbyEnabled());

        // When battery stops plugged in, force app standby is enabled
        mReceiver.onReceive(mMockContext, new Intent(Intent.ACTION_BATTERY_CHANGED));
        assertTrue(instance.isForceAllAppsStandbyEnabled());
    }

    @Test
    public void testNotSmallBatteryAndPluggedIn() throws Exception {
        // Not a small battery device, so plugged in status should not affect forced app standby
        mIsSmallBatteryDevice = false;

        final AppStateTrackerTestable instance = newInstance();
        callStart(instance);
        assertFalse(instance.isForceAllAppsStandbyEnabled());

        mPowerSaveMode = true;
        mPowerSaveObserver.accept(getPowerSaveState());
        assertTrue(instance.isForceAllAppsStandbyEnabled());

        // When battery is plugged in, force app standby is unaffected
        Intent intent = new Intent(Intent.ACTION_BATTERY_CHANGED);
        intent.putExtra(BatteryManager.EXTRA_PLUGGED, BatteryManager.BATTERY_PLUGGED_USB);
        mReceiver.onReceive(mMockContext, intent);
        assertTrue(instance.isForceAllAppsStandbyEnabled());

        // When battery stops plugged in, force app standby is unaffected
        mReceiver.onReceive(mMockContext, new Intent(Intent.ACTION_BATTERY_CHANGED));
        assertTrue(instance.isForceAllAppsStandbyEnabled());
    }

    static int[] array(int... appIds) {
        Arrays.sort(appIds);
        return appIds;
    }

    private final Random mRandom = new Random();

    int[] makeRandomArray() {
        final ArrayList<Integer> list = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            if (mRandom.nextDouble() < 0.5) {
                list.add(i);
            }
        }
        return Arrays.stream(list.toArray(new Integer[list.size()]))
                .mapToInt(Integer::intValue).toArray();
    }

    static boolean isAnyAppIdUnwhitelistedSlow(int[] prevArray, int[] newArray) {
        Arrays.sort(newArray); // Just in case...
        for (int p : prevArray) {
            if (Arrays.binarySearch(newArray, p) < 0) {
                return true;
            }
        }
        return false;
    }

    private void checkAnyAppIdUnwhitelisted(int[] prevArray, int[] newArray, boolean expected) {
        assertEquals("Input: " + Arrays.toString(prevArray) + " " + Arrays.toString(newArray),
                expected, AppStateTracker.isAnyAppIdUnwhitelisted(prevArray, newArray));

        // Also test isAnyAppIdUnwhitelistedSlow.
        assertEquals("Input: " + Arrays.toString(prevArray) + " " + Arrays.toString(newArray),
                expected, isAnyAppIdUnwhitelistedSlow(prevArray, newArray));
    }

    @Test
    public void isAnyAppIdUnwhitelisted() {
        checkAnyAppIdUnwhitelisted(array(), array(), false);

        checkAnyAppIdUnwhitelisted(array(1), array(), true);
        checkAnyAppIdUnwhitelisted(array(1), array(1), false);
        checkAnyAppIdUnwhitelisted(array(1), array(0, 1), false);
        checkAnyAppIdUnwhitelisted(array(1), array(0, 1, 2), false);
        checkAnyAppIdUnwhitelisted(array(1), array(0, 1, 2), false);

        checkAnyAppIdUnwhitelisted(array(1, 2, 10), array(), true);
        checkAnyAppIdUnwhitelisted(array(1, 2, 10), array(1, 2), true);
        checkAnyAppIdUnwhitelisted(array(1, 2, 10), array(1, 2, 10), false);
        checkAnyAppIdUnwhitelisted(array(1, 2, 10), array(2, 10), true);
        checkAnyAppIdUnwhitelisted(array(1, 2, 10), array(0, 1, 2, 4, 3, 10), false);
        checkAnyAppIdUnwhitelisted(array(1, 2, 10), array(0, 0, 1, 2, 10), false);

        // Random test
        int trueCount = 0;
        final int count = 10000;
        for (int i = 0; i < count; i++) {
            final int[] array1 = makeRandomArray();
            final int[] array2 = makeRandomArray();

            final boolean expected = isAnyAppIdUnwhitelistedSlow(array1, array2);
            final boolean actual = AppStateTracker.isAnyAppIdUnwhitelisted(array1, array2);

            assertEquals("Input: " + Arrays.toString(array1) + " " + Arrays.toString(array2),
                    expected, actual);
            if (expected) {
                trueCount++;
            }
        }

        // Make sure makeRandomArray() didn't generate all same arrays by accident.
        assertTrue(trueCount > 0);
        assertTrue(trueCount < count);
    }
}
