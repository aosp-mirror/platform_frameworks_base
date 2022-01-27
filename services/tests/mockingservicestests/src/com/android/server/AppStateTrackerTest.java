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

import static com.android.server.AppStateTrackerImpl.TARGET_OP;

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
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager.ServiceType;
import android.os.PowerManagerInternal;
import android.os.PowerSaveState;
import android.os.Process;
import android.os.RemoteException;
import android.os.UserHandle;
import android.platform.test.annotations.Presubmit;
import android.provider.Settings.Global;
import android.test.mock.MockContentResolver;
import android.util.ArraySet;
import android.util.Pair;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.internal.app.IAppOpsCallback;
import com.android.internal.app.IAppOpsService;
import com.android.server.AppStateTrackerImpl.Listener;
import com.android.server.usage.AppStandbyInternal;
import com.android.server.usage.AppStandbyInternal.AppIdleStateChangeListener;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.stubbing.Answer;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Tests for {@link AppStateTrackerImpl}
 *
 * Run with: atest com.android.server.AppStateTrackerTest
 */
@Presubmit
@SmallTest
@RunWith(AndroidJUnit4.class)
public class AppStateTrackerTest {

    private class AppStateTrackerTestable extends AppStateTrackerImpl {
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
        AppStandbyInternal injectAppStandbyInternal() {
            when(mMockAppStandbyInternal.isAppIdleEnabled()).thenReturn(true);
            return mMockAppStandbyInternal;
        }

        @Override
        int injectGetGlobalSettingInt(String key, int def) {
            Integer val = mGlobalSettings.get(key);

            return (val == null) ? def : val;
        }

        @Override
        boolean isSmallBatteryDevice() {
            return mIsSmallBatteryDevice;
        }
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
    private AppStandbyInternal mMockAppStandbyInternal;

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

    /**
     * Enqueues a message and waits for it to complete. This ensures that any messages posted until
     * now have been executed.
     *
     * Note that these messages may have enqueued more messages, which may or may not have executed
     * when this method returns.
     */
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
                        | ActivityManager.UID_OBSERVER_ACTIVE),
                eq(ActivityManager.PROCESS_STATE_UNKNOWN),
                isNull());
        verify(mMockIAppOpsService).startWatchingMode(
                eq(AppOpsManager.OP_RUN_ANY_IN_BACKGROUND),
                isNull(),
                appOpsCallbackCaptor.capture());
        verify(mMockPowerManagerInternal).registerLowPowerModeObserver(
                eq(ServiceType.FORCE_ALL_APPS_STANDBY),
                powerSaveObserverCaptor.capture());

        verify(mMockContext, times(2)).registerReceiver(
                receiverCaptor.capture(), any(IntentFilter.class));
        verify(mMockAppStandbyInternal).addListener(
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

    private void areJobsRestricted(AppStateTrackerTestable instance, int[] uids, String[] packages,
            boolean[] restricted, boolean exemption) {
        assertTrue(uids.length == packages.length && uids.length == restricted.length);
        for (int i = 0; i < uids.length; i++) {
            assertEquals(restricted[i],
                    instance.areJobsRestricted(uids[i], packages[i], exemption));
        }
    }

    private void areAlarmsRestrictedByFAS(AppStateTrackerTestable instance, int[] uids,
            String[] packages, boolean[] restricted) {
        assertTrue(uids.length == packages.length && uids.length == restricted.length);
        for (int i = 0; i < uids.length; i++) {
            assertEquals(restricted[i], instance.areAlarmsRestricted(uids[i], packages[i]));
        }
    }

    private void areAlarmsRestrictedByBatterySaver(AppStateTrackerTestable instance, int[] uids,
            String[] packages, boolean[] restricted) {
        assertTrue(uids.length == packages.length && uids.length == restricted.length);
        for (int i = 0; i < uids.length; i++) {
            assertEquals(restricted[i],
                    instance.areAlarmsRestrictedByBatterySaver(uids[i], packages[i]));
        }
    }

    @Test
    public void testAll() throws Exception {
        final AppStateTrackerTestable instance = newInstance();
        callStart(instance);

        assertFalse(instance.isForceAllAppsStandbyEnabled());

        when(mMockIActivityManagerInternal.isBgAutoRestrictedBucketFeatureFlagEnabled())
                .thenReturn(false);

        areJobsRestricted(instance,
                new int[] {UID_1, UID_2, UID_10_2, Process.SYSTEM_UID},
                new String[] {PACKAGE_1, PACKAGE_2, PACKAGE_2, PACKAGE_SYSTEM},
                new boolean[] {false, false, false, false},
                false);
        areJobsRestricted(instance,
                new int[] {UID_1, UID_2, UID_10_2, Process.SYSTEM_UID},
                new String[] {PACKAGE_1, PACKAGE_2, PACKAGE_2, PACKAGE_SYSTEM},
                new boolean[] {false, false, false, false},
                true);
        areAlarmsRestrictedByBatterySaver(instance,
                new int[] {UID_1, UID_2, UID_10_2, Process.SYSTEM_UID},
                new String[] {PACKAGE_1, PACKAGE_2, PACKAGE_2, PACKAGE_SYSTEM},
                new boolean[] {false, false, false, false});

        // Toggle the auto restricted bucket feature flag on bg restriction, shouldn't make a
        // difference.
        when(mMockIActivityManagerInternal.isBgAutoRestrictedBucketFeatureFlagEnabled())
                .thenReturn(true);

        areJobsRestricted(instance,
                new int[] {UID_1, UID_2, UID_10_2, Process.SYSTEM_UID},
                new String[] {PACKAGE_1, PACKAGE_2, PACKAGE_2, PACKAGE_SYSTEM},
                new boolean[] {false, false, false, false},
                false);
        areJobsRestricted(instance,
                new int[] {UID_1, UID_2, UID_10_2, Process.SYSTEM_UID},
                new String[] {PACKAGE_1, PACKAGE_2, PACKAGE_2, PACKAGE_SYSTEM},
                new boolean[] {false, false, false, false},
                true);
        areAlarmsRestrictedByBatterySaver(instance,
                new int[] {UID_1, UID_2, UID_10_2, Process.SYSTEM_UID},
                new String[] {PACKAGE_1, PACKAGE_2, PACKAGE_2, PACKAGE_SYSTEM},
                new boolean[] {false, false, false, false});

        mPowerSaveMode = true;
        mPowerSaveObserver.accept(getPowerSaveState());

        assertTrue(instance.isForceAllAppsStandbyEnabled());

        when(mMockIActivityManagerInternal.isBgAutoRestrictedBucketFeatureFlagEnabled())
                .thenReturn(false);

        areJobsRestricted(instance,
                new int[] {UID_1, UID_2, UID_10_2, Process.SYSTEM_UID},
                new String[] {PACKAGE_1, PACKAGE_2, PACKAGE_2, PACKAGE_SYSTEM},
                new boolean[] {true, true, true, false},
                false);
        areJobsRestricted(instance,
                new int[] {UID_1, UID_2, UID_10_2, Process.SYSTEM_UID},
                new String[] {PACKAGE_1, PACKAGE_2, PACKAGE_2, PACKAGE_SYSTEM},
                new boolean[] {false, false, false, false},
                true);
        areAlarmsRestrictedByBatterySaver(instance,
                new int[] {UID_1, UID_2, UID_10_2, Process.SYSTEM_UID},
                new String[] {PACKAGE_1, PACKAGE_2, PACKAGE_2, PACKAGE_SYSTEM},
                new boolean[] {true, true, true, false});

        // Toggle the auto restricted bucket feature flag on bg restriction, shouldn't make a
        // difference.
        when(mMockIActivityManagerInternal.isBgAutoRestrictedBucketFeatureFlagEnabled())
                .thenReturn(true);

        areJobsRestricted(instance,
                new int[] {UID_1, UID_2, UID_10_2, Process.SYSTEM_UID},
                new String[] {PACKAGE_1, PACKAGE_2, PACKAGE_2, PACKAGE_SYSTEM},
                new boolean[] {true, true, true, false},
                false);
        areJobsRestricted(instance,
                new int[] {UID_1, UID_2, UID_10_2, Process.SYSTEM_UID},
                new String[] {PACKAGE_1, PACKAGE_2, PACKAGE_2, PACKAGE_SYSTEM},
                new boolean[] {false, false, false, false},
                true);
        areAlarmsRestrictedByBatterySaver(instance,
                new int[] {UID_1, UID_2, UID_10_2, Process.SYSTEM_UID},
                new String[] {PACKAGE_1, PACKAGE_2, PACKAGE_2, PACKAGE_SYSTEM},
                new boolean[] {true, true, true, false});

        when(mMockIActivityManagerInternal.isBgAutoRestrictedBucketFeatureFlagEnabled())
                .thenReturn(false);

        // Toggle the foreground state.

        assertFalse(instance.isUidActive(UID_1));
        assertFalse(instance.isUidActive(UID_2));
        assertTrue(instance.isUidActive(Process.SYSTEM_UID));

        mIUidObserver.onUidActive(UID_1);
        waitUntilMainHandlerDrain();
        waitUntilMainHandlerDrain();

        areJobsRestricted(instance,
                new int[] {UID_1, UID_2, UID_10_2, Process.SYSTEM_UID},
                new String[] {PACKAGE_1, PACKAGE_2, PACKAGE_2, PACKAGE_SYSTEM},
                new boolean[] {false, true, true, false},
                false);
        areAlarmsRestrictedByBatterySaver(instance,
                new int[] {UID_1, UID_2, UID_10_2, Process.SYSTEM_UID},
                new String[] {PACKAGE_1, PACKAGE_2, PACKAGE_2, PACKAGE_SYSTEM},
                new boolean[] {false, true, true, false});

        assertTrue(instance.isUidActive(UID_1));
        assertFalse(instance.isUidActive(UID_2));

        mIUidObserver.onUidGone(UID_1, /*disable=*/ false);
        waitUntilMainHandlerDrain();
        waitUntilMainHandlerDrain();

        areJobsRestricted(instance,
                new int[] {UID_1, UID_2, UID_10_2, Process.SYSTEM_UID},
                new String[] {PACKAGE_1, PACKAGE_2, PACKAGE_2, PACKAGE_SYSTEM},
                new boolean[] {true, true, true, false},
                false);
        areAlarmsRestrictedByBatterySaver(instance,
                new int[] {UID_1, UID_2, UID_10_2, Process.SYSTEM_UID},
                new String[] {PACKAGE_1, PACKAGE_2, PACKAGE_2, PACKAGE_SYSTEM},
                new boolean[] {true, true, true, false});

        assertFalse(instance.isUidActive(UID_1));
        assertFalse(instance.isUidActive(UID_2));

        mIUidObserver.onUidActive(UID_1);
        waitUntilMainHandlerDrain();
        waitUntilMainHandlerDrain();

        areJobsRestricted(instance,
                new int[] {UID_1, UID_2, UID_10_2, Process.SYSTEM_UID},
                new String[] {PACKAGE_1, PACKAGE_2, PACKAGE_2, PACKAGE_SYSTEM},
                new boolean[] {false, true, true, false},
                false);
        areAlarmsRestrictedByBatterySaver(instance,
                new int[] {UID_1, UID_2, UID_10_2, Process.SYSTEM_UID},
                new String[] {PACKAGE_1, PACKAGE_2, PACKAGE_2, PACKAGE_SYSTEM},
                new boolean[] {false, true, true, false});

        mIUidObserver.onUidIdle(UID_1, /*disable=*/ false);
        waitUntilMainHandlerDrain();
        waitUntilMainHandlerDrain();

        areJobsRestricted(instance,
                new int[] {UID_1, UID_2, UID_10_2, Process.SYSTEM_UID},
                new String[] {PACKAGE_1, PACKAGE_2, PACKAGE_2, PACKAGE_SYSTEM},
                new boolean[] {true, true, true, false},
                false);
        areAlarmsRestrictedByBatterySaver(instance,
                new int[] {UID_1, UID_2, UID_10_2, Process.SYSTEM_UID},
                new String[] {PACKAGE_1, PACKAGE_2, PACKAGE_2, PACKAGE_SYSTEM},
                new boolean[] {true, true, true, false});

        assertFalse(instance.isUidActive(UID_1));
        assertFalse(instance.isUidActive(UID_2));

        // Toggle the app ops.
        mPowerSaveMode = false;
        mPowerSaveObserver.accept(getPowerSaveState());

        assertTrue(instance.isRunAnyInBackgroundAppOpsAllowed(UID_1, PACKAGE_1));
        assertTrue(instance.isRunAnyInBackgroundAppOpsAllowed(UID_10_1, PACKAGE_1));
        assertTrue(instance.isRunAnyInBackgroundAppOpsAllowed(UID_2, PACKAGE_2));
        assertTrue(instance.isRunAnyInBackgroundAppOpsAllowed(UID_10_2, PACKAGE_2));

        areJobsRestricted(instance,
                new int[] {UID_1, UID_10_1, UID_2, UID_10_2, Process.SYSTEM_UID},
                new String[] {PACKAGE_1, PACKAGE_1, PACKAGE_2, PACKAGE_2, PACKAGE_SYSTEM},
                new boolean[] {false, false, false, false, false},
                false);
        areAlarmsRestrictedByBatterySaver(instance,
                new int[] {UID_1, UID_10_1, UID_2, UID_10_2, Process.SYSTEM_UID},
                new String[] {PACKAGE_1, PACKAGE_1, PACKAGE_2, PACKAGE_2, PACKAGE_SYSTEM},
                new boolean[] {false, false, false, false, false});
        areAlarmsRestrictedByFAS(instance,
                new int[] {UID_1, UID_10_1, UID_2, UID_10_2, Process.SYSTEM_UID},
                new String[] {PACKAGE_1, PACKAGE_1, PACKAGE_2, PACKAGE_2, PACKAGE_SYSTEM},
                new boolean[] {false, false, false, false, false});

        setAppOps(UID_1, PACKAGE_1, true);
        setAppOps(UID_10_2, PACKAGE_2, true);
        assertFalse(instance.isRunAnyInBackgroundAppOpsAllowed(UID_1, PACKAGE_1));
        assertTrue(instance.isRunAnyInBackgroundAppOpsAllowed(UID_10_1, PACKAGE_1));
        assertTrue(instance.isRunAnyInBackgroundAppOpsAllowed(UID_2, PACKAGE_2));
        assertFalse(instance.isRunAnyInBackgroundAppOpsAllowed(UID_10_2, PACKAGE_2));

        areJobsRestricted(instance,
                new int[] {UID_1, UID_10_1, UID_2, UID_10_2, Process.SYSTEM_UID},
                new String[] {PACKAGE_1, PACKAGE_1, PACKAGE_2, PACKAGE_2, PACKAGE_SYSTEM},
                new boolean[] {true, false, false, true, false},
                false);
        areJobsRestricted(instance,
                new int[] {UID_1, UID_10_1, UID_2, UID_10_2, Process.SYSTEM_UID},
                new String[] {PACKAGE_1, PACKAGE_1, PACKAGE_2, PACKAGE_2, PACKAGE_SYSTEM},
                new boolean[] {true, false, false, true, false},
                true);

        areAlarmsRestrictedByBatterySaver(instance,
                new int[] {UID_1, UID_10_1, UID_2, UID_10_2, Process.SYSTEM_UID},
                new String[] {PACKAGE_1, PACKAGE_1, PACKAGE_2, PACKAGE_2, PACKAGE_SYSTEM},
                new boolean[] {false, false, false, false, false});
        areAlarmsRestrictedByFAS(instance,
                new int[] {UID_1, UID_10_1, UID_2, UID_10_2, Process.SYSTEM_UID},
                new String[] {PACKAGE_1, PACKAGE_1, PACKAGE_2, PACKAGE_2, PACKAGE_SYSTEM},
                new boolean[] {true, false, false, true, false});

        // Toggle the auto restricted bucket feature flag on bg restriction.
        when(mMockIActivityManagerInternal.isBgAutoRestrictedBucketFeatureFlagEnabled())
                .thenReturn(true);

        areJobsRestricted(instance,
                new int[] {UID_1, UID_10_1, UID_2, UID_10_2, Process.SYSTEM_UID},
                new String[] {PACKAGE_1, PACKAGE_1, PACKAGE_2, PACKAGE_2, PACKAGE_SYSTEM},
                new boolean[] {false, false, false, false, false},
                false);
        areJobsRestricted(instance,
                new int[] {UID_1, UID_10_1, UID_2, UID_10_2, Process.SYSTEM_UID},
                new String[] {PACKAGE_1, PACKAGE_1, PACKAGE_2, PACKAGE_2, PACKAGE_SYSTEM},
                new boolean[] {false, false, false, false, false},
                true);

        areAlarmsRestrictedByBatterySaver(instance,
                new int[] {UID_1, UID_10_1, UID_2, UID_10_2, Process.SYSTEM_UID},
                new String[] {PACKAGE_1, PACKAGE_1, PACKAGE_2, PACKAGE_2, PACKAGE_SYSTEM},
                new boolean[] {false, false, false, false, false});
        areAlarmsRestrictedByFAS(instance,
                new int[] {UID_1, UID_10_1, UID_2, UID_10_2, Process.SYSTEM_UID},
                new String[] {PACKAGE_1, PACKAGE_1, PACKAGE_2, PACKAGE_2, PACKAGE_SYSTEM},
                new boolean[] {false, false, false, false, false});

        // Toggle power saver, should still be the same.
        mPowerSaveMode = true;
        mPowerSaveObserver.accept(getPowerSaveState());
        when(mMockIActivityManagerInternal.isBgAutoRestrictedBucketFeatureFlagEnabled())
                .thenReturn(false);

        areJobsRestricted(instance,
                new int[] {UID_1, UID_10_1, UID_2, UID_10_2, Process.SYSTEM_UID},
                new String[] {PACKAGE_1, PACKAGE_1, PACKAGE_2, PACKAGE_2, PACKAGE_SYSTEM},
                new boolean[] {true, true, true, true, false},
                false);
        areJobsRestricted(instance,
                new int[] {UID_1, UID_10_1, UID_2, UID_10_2, Process.SYSTEM_UID},
                new String[] {PACKAGE_1, PACKAGE_1, PACKAGE_2, PACKAGE_2, PACKAGE_SYSTEM},
                new boolean[] {true, false, false, true, false},
                true);

        areAlarmsRestrictedByBatterySaver(instance,
                new int[] {UID_1, UID_10_1, UID_2, UID_10_2, Process.SYSTEM_UID},
                new String[] {PACKAGE_1, PACKAGE_1, PACKAGE_2, PACKAGE_2, PACKAGE_SYSTEM},
                new boolean[] {true, true, true, true, false});
        areAlarmsRestrictedByFAS(instance,
                new int[] {UID_1, UID_10_1, UID_2, UID_10_2, Process.SYSTEM_UID},
                new String[] {PACKAGE_1, PACKAGE_1, PACKAGE_2, PACKAGE_2, PACKAGE_SYSTEM},
                new boolean[] {true, false, false, true, false});

        // Toggle the auto restricted bucket feature flag on bg restriction.
        when(mMockIActivityManagerInternal.isBgAutoRestrictedBucketFeatureFlagEnabled())
                .thenReturn(true);

        areJobsRestricted(instance,
                new int[] {UID_1, UID_10_1, UID_2, UID_10_2, Process.SYSTEM_UID},
                new String[] {PACKAGE_1, PACKAGE_1, PACKAGE_2, PACKAGE_2, PACKAGE_SYSTEM},
                new boolean[] {true, true, true, true, false},
                false);
        areJobsRestricted(instance,
                new int[] {UID_1, UID_10_1, UID_2, UID_10_2, Process.SYSTEM_UID},
                new String[] {PACKAGE_1, PACKAGE_1, PACKAGE_2, PACKAGE_2, PACKAGE_SYSTEM},
                new boolean[] {false, false, false, false, false},
                true);

        areAlarmsRestrictedByBatterySaver(instance,
                new int[] {UID_1, UID_10_1, UID_2, UID_10_2, Process.SYSTEM_UID},
                new String[] {PACKAGE_1, PACKAGE_1, PACKAGE_2, PACKAGE_2, PACKAGE_SYSTEM},
                new boolean[] {true, true, true, true, false});
        areAlarmsRestrictedByFAS(instance,
                new int[] {UID_1, UID_10_1, UID_2, UID_10_2, Process.SYSTEM_UID},
                new String[] {PACKAGE_1, PACKAGE_1, PACKAGE_2, PACKAGE_2, PACKAGE_SYSTEM},
                new boolean[] {false, false, false, false, false});

        mPowerSaveMode = false;
        mPowerSaveObserver.accept(getPowerSaveState());

        when(mMockIActivityManagerInternal.isBgAutoRestrictedBucketFeatureFlagEnabled())
                .thenReturn(false);

        areJobsRestricted(instance,
                new int[] {UID_1, UID_10_1, UID_2, UID_10_2, Process.SYSTEM_UID},
                new String[] {PACKAGE_1, PACKAGE_1, PACKAGE_2, PACKAGE_2, PACKAGE_SYSTEM},
                new boolean[] {true, false, false, true, false},
                false);
        areJobsRestricted(instance,
                new int[] {UID_1, UID_10_1, UID_2, UID_10_2, Process.SYSTEM_UID},
                new String[] {PACKAGE_1, PACKAGE_1, PACKAGE_2, PACKAGE_2, PACKAGE_SYSTEM},
                new boolean[] {true, false, false, true, false},
                true);

        areAlarmsRestrictedByBatterySaver(instance,
                new int[] {UID_1, UID_10_1, UID_2, UID_10_2, Process.SYSTEM_UID},
                new String[] {PACKAGE_1, PACKAGE_1, PACKAGE_2, PACKAGE_2, PACKAGE_SYSTEM},
                new boolean[] {false, false, false, false, false});
        areAlarmsRestrictedByFAS(instance,
                new int[] {UID_1, UID_10_1, UID_2, UID_10_2, Process.SYSTEM_UID},
                new String[] {PACKAGE_1, PACKAGE_1, PACKAGE_2, PACKAGE_2, PACKAGE_SYSTEM},
                new boolean[] {true, false, false, true, false});

        // Clear the app ops and update the exemption list.
        setAppOps(UID_1, PACKAGE_1, false);
        setAppOps(UID_10_2, PACKAGE_2, false);

        mPowerSaveMode = true;
        mPowerSaveObserver.accept(getPowerSaveState());

        areJobsRestricted(instance,
                new int[] {UID_1, UID_10_1, UID_2, UID_10_2, Process.SYSTEM_UID},
                new String[] {PACKAGE_1, PACKAGE_1, PACKAGE_2, PACKAGE_2, PACKAGE_SYSTEM},
                new boolean[] {true, true, true, true, false},
                false);
        areJobsRestricted(instance,
                new int[] {UID_1, UID_10_1, UID_2, UID_10_2, Process.SYSTEM_UID},
                new String[] {PACKAGE_1, PACKAGE_1, PACKAGE_2, PACKAGE_2, PACKAGE_SYSTEM},
                new boolean[] {false, false, false, false, false},
                true);

        areAlarmsRestrictedByBatterySaver(instance,
                new int[] {UID_1, UID_10_1, UID_2, UID_10_2, Process.SYSTEM_UID},
                new String[] {PACKAGE_1, PACKAGE_1, PACKAGE_2, PACKAGE_2, PACKAGE_SYSTEM},
                new boolean[] {true, true, true, true, false});
        areAlarmsRestrictedByFAS(instance,
                new int[] {UID_1, UID_10_1, UID_2, UID_10_2, Process.SYSTEM_UID},
                new String[] {PACKAGE_1, PACKAGE_1, PACKAGE_2, PACKAGE_2, PACKAGE_SYSTEM},
                new boolean[] {false, false, false, false, false});

        instance.setPowerSaveExemptionListAppIds(new int[] {UID_1}, new int[] {},
                new int[] {UID_2});

        areJobsRestricted(instance,
                new int[] {UID_1, UID_10_1, UID_2, UID_10_2, UID_3, UID_10_3, Process.SYSTEM_UID},
                new String[]{PACKAGE_1, PACKAGE_1, PACKAGE_2, PACKAGE_2, PACKAGE_3, PACKAGE_3,
                        PACKAGE_SYSTEM},
                new boolean[] {false, false, false, false, true, true, false},
                false);

        areAlarmsRestrictedByBatterySaver(instance,
                new int[] {UID_1, UID_10_1, UID_2, UID_10_2, UID_3, UID_10_3, Process.SYSTEM_UID},
                new String[]{PACKAGE_1, PACKAGE_1, PACKAGE_2, PACKAGE_2, PACKAGE_3, PACKAGE_3,
                        PACKAGE_SYSTEM},
                new boolean[] {false, false, true, true, true, true, false});

        // Again, make sure toggling the global state doesn't change it.
        mPowerSaveMode = false;
        mPowerSaveObserver.accept(getPowerSaveState());

        mPowerSaveMode = true;
        mPowerSaveObserver.accept(getPowerSaveState());

        areJobsRestricted(instance,
                new int[] {UID_1, UID_10_1, UID_2, UID_10_2, UID_3, UID_10_3, Process.SYSTEM_UID},
                new String[]{PACKAGE_1, PACKAGE_1, PACKAGE_2, PACKAGE_2, PACKAGE_3, PACKAGE_3,
                        PACKAGE_SYSTEM},
                new boolean[] {false, false, false, false, true, true, false},
                false);

        areAlarmsRestrictedByBatterySaver(instance,
                new int[] {UID_1, UID_10_1, UID_2, UID_10_2, UID_3, UID_10_3, Process.SYSTEM_UID},
                new String[]{PACKAGE_1, PACKAGE_1, PACKAGE_2, PACKAGE_2, PACKAGE_3, PACKAGE_3,
                        PACKAGE_SYSTEM},
                new boolean[] {false, false, true, true, true, true, false});

        assertTrue(instance.isUidPowerSaveExempt(UID_1));
        assertTrue(instance.isUidPowerSaveExempt(UID_10_1));
        assertFalse(instance.isUidPowerSaveExempt(UID_2));
        assertFalse(instance.isUidPowerSaveExempt(UID_10_2));

        assertFalse(instance.isUidTempPowerSaveExempt(UID_1));
        assertFalse(instance.isUidTempPowerSaveExempt(UID_10_1));
        assertTrue(instance.isUidTempPowerSaveExempt(UID_2));
        assertTrue(instance.isUidTempPowerSaveExempt(UID_10_2));
    }

    @Test
    public void testPowerSaveUserExemptionList() throws Exception {
        final AppStateTrackerTestable instance = newInstance();
        instance.setPowerSaveExemptionListAppIds(new int[] {}, new int[] {UID_1, UID_2},
                new int[] {});
        assertTrue(instance.isUidPowerSaveUserExempt(UID_1));
        assertTrue(instance.isUidPowerSaveUserExempt(UID_2));
        assertFalse(instance.isUidPowerSaveUserExempt(UID_3));
    }

    @Test
    public void testUidStateForeground() throws Exception {
        final AppStateTrackerTestable instance = newInstance();
        callStart(instance);

        mIUidObserver.onUidActive(UID_1);

        waitUntilMainHandlerDrain();
        waitUntilMainHandlerDrain();

        assertTrue(instance.isUidActive(UID_1));
        assertFalse(instance.isUidActive(UID_2));
        assertTrue(instance.isUidActive(Process.SYSTEM_UID));

        assertTrue(instance.isUidActiveSynced(UID_1));
        assertFalse(instance.isUidActiveSynced(UID_2));
        assertTrue(instance.isUidActiveSynced(Process.SYSTEM_UID));

        mIUidObserver.onUidStateChanged(UID_2,
                ActivityManager.PROCESS_STATE_BOUND_FOREGROUND_SERVICE, 0,
                ActivityManager.PROCESS_CAPABILITY_NONE);

        waitUntilMainHandlerDrain();
        waitUntilMainHandlerDrain();

        assertTrue(instance.isUidActive(UID_1));
        assertFalse(instance.isUidActive(UID_2));
        assertTrue(instance.isUidActive(Process.SYSTEM_UID));

        assertTrue(instance.isUidActiveSynced(UID_1));
        assertFalse(instance.isUidActiveSynced(UID_2));
        assertTrue(instance.isUidActiveSynced(Process.SYSTEM_UID));

        mIUidObserver.onUidStateChanged(UID_1,
                ActivityManager.PROCESS_STATE_FOREGROUND_SERVICE, 0,
                ActivityManager.PROCESS_CAPABILITY_NONE);

        waitUntilMainHandlerDrain();
        waitUntilMainHandlerDrain();

        assertTrue(instance.isUidActive(UID_1));
        assertFalse(instance.isUidActive(UID_2));
        assertTrue(instance.isUidActive(Process.SYSTEM_UID));

        mIUidObserver.onUidGone(UID_1, true);

        waitUntilMainHandlerDrain();
        waitUntilMainHandlerDrain();

        assertFalse(instance.isUidActive(UID_1));
        assertFalse(instance.isUidActive(UID_2));
        assertTrue(instance.isUidActive(Process.SYSTEM_UID));

        mIUidObserver.onUidIdle(UID_2, true);

        waitUntilMainHandlerDrain();
        waitUntilMainHandlerDrain();

        assertFalse(instance.isUidActive(UID_1));
        assertFalse(instance.isUidActive(UID_2));
        assertTrue(instance.isUidActive(Process.SYSTEM_UID));

        mIUidObserver.onUidStateChanged(UID_1,
                ActivityManager.PROCESS_STATE_IMPORTANT_FOREGROUND, 0,
                ActivityManager.PROCESS_CAPABILITY_NONE);

        waitUntilMainHandlerDrain();
        waitUntilMainHandlerDrain();

        assertFalse(instance.isUidActive(UID_1));
        assertFalse(instance.isUidActive(UID_2));
        assertTrue(instance.isUidActive(Process.SYSTEM_UID));

        mIUidObserver.onUidStateChanged(UID_1,
                ActivityManager.PROCESS_STATE_TRANSIENT_BACKGROUND, 0,
                ActivityManager.PROCESS_CAPABILITY_NONE);

        waitUntilMainHandlerDrain();
        waitUntilMainHandlerDrain();

        assertFalse(instance.isUidActive(UID_1));
        assertFalse(instance.isUidActive(UID_2));
        assertTrue(instance.isUidActive(Process.SYSTEM_UID));

        assertFalse(instance.isUidActiveSynced(UID_1));
        assertFalse(instance.isUidActiveSynced(UID_2));
        assertTrue(instance.isUidActiveSynced(Process.SYSTEM_UID));

        // The result from AMI.isUidActive() only affects isUidActiveSynced().
        when(mMockIActivityManagerInternal.isUidActive(anyInt())).thenReturn(true);

        assertFalse(instance.isUidActive(UID_1));
        assertFalse(instance.isUidActive(UID_2));
        assertTrue(instance.isUidActive(Process.SYSTEM_UID));

        assertTrue(instance.isUidActiveSynced(UID_1));
        assertTrue(instance.isUidActiveSynced(UID_2));
        assertTrue(instance.isUidActiveSynced(Process.SYSTEM_UID));
    }

    @Test
    public void testExemptedBucket() throws Exception {
        final AppStateTrackerTestable instance = newInstance();
        callStart(instance);

        assertFalse(instance.isForceAllAppsStandbyEnabled());

        areJobsRestricted(instance,
                new int[] {UID_1, UID_2, Process.SYSTEM_UID},
                new String[] {PACKAGE_1, PACKAGE_2, PACKAGE_SYSTEM},
                new boolean[] {false, false, false},
                false);
        areAlarmsRestrictedByBatterySaver(instance,
                new int[] {UID_1, UID_2, Process.SYSTEM_UID},
                new String[] {PACKAGE_1, PACKAGE_2, PACKAGE_SYSTEM},
                new boolean[] {false, false, false});

        mPowerSaveMode = true;
        mPowerSaveObserver.accept(getPowerSaveState());

        assertTrue(instance.isForceAllAppsStandbyEnabled());

        areJobsRestricted(instance,
                new int[] {UID_1, UID_2, UID_10_2, Process.SYSTEM_UID},
                new String[] {PACKAGE_1, PACKAGE_2, PACKAGE_2, PACKAGE_SYSTEM},
                new boolean[] {true, true, true, false},
                false);
        areJobsRestricted(instance,
                new int[] {UID_1, UID_2, UID_10_2, Process.SYSTEM_UID},
                new String[] {PACKAGE_1, PACKAGE_2, PACKAGE_2, PACKAGE_SYSTEM},
                new boolean[] {false, false, false, false},
                true);
        areAlarmsRestrictedByBatterySaver(instance,
                new int[] {UID_1, UID_2, UID_10_2, Process.SYSTEM_UID},
                new String[] {PACKAGE_1, PACKAGE_2, PACKAGE_2, PACKAGE_SYSTEM},
                new boolean[] {true, true, true, false});

        // Exempt package 2 on user-10.
        mAppIdleStateChangeListener.onAppIdleStateChanged(PACKAGE_2, /*user=*/ 10, false,
                UsageStatsManager.STANDBY_BUCKET_EXEMPTED, REASON_MAIN_DEFAULT);

        areJobsRestricted(instance,
                new int[] {UID_1, UID_2, UID_10_2},
                new String[] {PACKAGE_1, PACKAGE_2, PACKAGE_2},
                new boolean[] {true, true, false},
                false);
        areJobsRestricted(instance,
                new int[] {UID_1, UID_2, UID_10_2},
                new String[] {PACKAGE_1, PACKAGE_2, PACKAGE_2},
                new boolean[] {false, false, false},
                true);
        areAlarmsRestrictedByBatterySaver(instance,
                new int[] {UID_1, UID_2, UID_10_2},
                new String[] {PACKAGE_1, PACKAGE_2, PACKAGE_2},
                new boolean[] {true, true, false});

        // Exempt package 1 on user-0.
        mAppIdleStateChangeListener.onAppIdleStateChanged(PACKAGE_1, /*user=*/ 0, false,
                UsageStatsManager.STANDBY_BUCKET_EXEMPTED, REASON_MAIN_DEFAULT);

        areJobsRestricted(instance,
                new int[] {UID_1, UID_2, UID_10_2},
                new String[] {PACKAGE_1, PACKAGE_2, PACKAGE_2},
                new boolean[] {false, true, false},
                false);
        areJobsRestricted(instance,
                new int[] {UID_1, UID_2, UID_10_2},
                new String[] {PACKAGE_1, PACKAGE_2, PACKAGE_2},
                new boolean[] {false, false, false},
                true);
        areAlarmsRestrictedByBatterySaver(instance,
                new int[] {UID_1, UID_2, UID_10_2},
                new String[] {PACKAGE_1, PACKAGE_2, PACKAGE_2},
                new boolean[] {false, true, false});

        // Unexempt package 2 on user-10.
        mAppIdleStateChangeListener.onAppIdleStateChanged(PACKAGE_2, /*user=*/ 10, false,
                UsageStatsManager.STANDBY_BUCKET_ACTIVE, REASON_MAIN_USAGE);

        areJobsRestricted(instance,
                new int[] {UID_1, UID_2, UID_10_2},
                new String[] {PACKAGE_1, PACKAGE_2, PACKAGE_2},
                new boolean[] {false, true, true},
                false);
        areJobsRestricted(instance,
                new int[] {UID_1, UID_2, UID_10_2},
                new String[] {PACKAGE_1, PACKAGE_2, PACKAGE_2},
                new boolean[] {false, false, false},
                true);
        areAlarmsRestrictedByBatterySaver(instance,
                new int[] {UID_1, UID_2, UID_10_2},
                new String[] {PACKAGE_1, PACKAGE_2, PACKAGE_2},
                new boolean[] {false, true, true});

        // Check force-app-standby.
        // EXEMPT doesn't exempt from force-app-standby.
        mPowerSaveMode = false;
        mPowerSaveObserver.accept(getPowerSaveState());

        mAppIdleStateChangeListener.onAppIdleStateChanged(PACKAGE_1, /*user=*/ 0, false,
                UsageStatsManager.STANDBY_BUCKET_EXEMPTED, REASON_MAIN_DEFAULT);
        mAppIdleStateChangeListener.onAppIdleStateChanged(PACKAGE_2, /*user=*/ 0, false,
                UsageStatsManager.STANDBY_BUCKET_EXEMPTED, REASON_MAIN_DEFAULT);

        // All 3 packages (u0:p1, u0:p2, u10:p2) are now in the exempted bucket.
        setAppOps(UID_1, PACKAGE_1, true);

        areJobsRestricted(instance,
                new int[] {UID_1, UID_2, UID_10_2},
                new String[] {PACKAGE_1, PACKAGE_2, PACKAGE_2},
                new boolean[] {true, false, false},
                false);
        areJobsRestricted(instance,
                new int[] {UID_1, UID_2, UID_10_2},
                new String[] {PACKAGE_1, PACKAGE_2, PACKAGE_2},
                new boolean[] {true, false, false},
                true);

        areAlarmsRestrictedByBatterySaver(instance,
                new int[] {UID_1, UID_2, UID_10_2},
                new String[] {PACKAGE_1, PACKAGE_2, PACKAGE_2},
                new boolean[] {false, false, false});
        areAlarmsRestrictedByFAS(instance,
                new int[] {UID_1, UID_2, UID_10_2},
                new String[] {PACKAGE_1, PACKAGE_2, PACKAGE_2},
                new boolean[] {true, false, false});
    }

    @Test
    public void loadPersistedAppOps() throws Exception {
        final AppStateTrackerTestable instance = newInstance();

        final List<PackageOps> ops = new ArrayList<>();

        //--------------------------------------------------
        List<OpEntry> entries = new ArrayList<>();
        entries.add(new OpEntry(
                AppOpsManager.OP_ACCESS_NOTIFICATIONS,
                AppOpsManager.MODE_IGNORED,
                Collections.emptyMap()));
        entries.add(new OpEntry(
                AppStateTrackerImpl.TARGET_OP,
                AppOpsManager.MODE_IGNORED,
                Collections.emptyMap()));

        ops.add(new PackageOps(PACKAGE_1, UID_1, entries));

        //--------------------------------------------------
        entries = new ArrayList<>();
        entries.add(new OpEntry(
                AppStateTrackerImpl.TARGET_OP,
                AppOpsManager.MODE_IGNORED,
                Collections.emptyMap()));

        ops.add(new PackageOps(PACKAGE_2, UID_2, entries));

        //--------------------------------------------------
        entries = new ArrayList<>();
        entries.add(new OpEntry(
                AppStateTrackerImpl.TARGET_OP,
                AppOpsManager.MODE_ALLOWED,
                Collections.emptyMap()));

        ops.add(new PackageOps(PACKAGE_1, UID_10_1, entries));

        //--------------------------------------------------
        entries = new ArrayList<>();
        entries.add(new OpEntry(
                AppStateTrackerImpl.TARGET_OP,
                AppOpsManager.MODE_IGNORED,
                Collections.emptyMap()));
        entries.add(new OpEntry(
                AppOpsManager.OP_ACCESS_NOTIFICATIONS,
                AppOpsManager.MODE_IGNORED,
                Collections.emptyMap()));

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

        verify(l, times(1)).updateAllAlarms();
        verify(l, times(0)).updateAlarmsForUid(anyInt());
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

        verify(l, times(1)).updateAllAlarms();
        verify(l, times(0)).updateAlarmsForUid(anyInt());
        verify(l, times(0)).unblockAllUnrestrictedAlarms();
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
        verify(l, times(1)).updateBackgroundRestrictedForUidPackage(eq(UID_10_2), eq(PACKAGE_2),
                eq(true));

        verify(l, times(0)).updateAllAlarms();
        verify(l, times(0)).updateAlarmsForUid(anyInt());
        verify(l, times(0)).unblockAllUnrestrictedAlarms();
        verify(l, times(0)).unblockAlarmsForUid(anyInt());
        verify(l, times(0)).unblockAlarmsForUidPackage(anyInt(), anyString());
        reset(l);

        setAppOps(UID_10_2, PACKAGE_2, false);

        waitUntilMainHandlerDrain();
        verify(l, times(0)).updateAllJobs();
        verify(l, times(0)).updateJobsForUid(anyInt(), anyBoolean());
        verify(l, times(1)).updateJobsForUidPackage(eq(UID_10_2), eq(PACKAGE_2), anyBoolean());
        verify(l, times(1)).updateBackgroundRestrictedForUidPackage(eq(UID_10_2), eq(PACKAGE_2),
                eq(false));

        verify(l, times(0)).updateAllAlarms();
        verify(l, times(0)).updateAlarmsForUid(anyInt());
        verify(l, times(0)).unblockAllUnrestrictedAlarms();
        verify(l, times(0)).unblockAlarmsForUid(anyInt());
        verify(l, times(1)).unblockAlarmsForUidPackage(eq(UID_10_2), eq(PACKAGE_2));
        reset(l);

        setAppOps(UID_10_2, PACKAGE_2, false);

        verify(l, times(0)).updateAllJobs();
        verify(l, times(0)).updateJobsForUid(anyInt(), anyBoolean());
        verify(l, times(0)).updateJobsForUidPackage(anyInt(), anyString(), anyBoolean());
        verify(l, times(0)).updateBackgroundRestrictedForUidPackage(anyInt(), anyString(),
                anyBoolean());

        verify(l, times(0)).updateAllAlarms();
        verify(l, times(0)).updateAlarmsForUid(anyInt());
        verify(l, times(0)).unblockAllUnrestrictedAlarms();
        verify(l, times(0)).unblockAlarmsForUid(anyInt());
        verify(l, times(0)).unblockAlarmsForUidPackage(anyInt(), anyString());

        // Test overlap with battery saver
        mPowerSaveMode = true;
        mPowerSaveObserver.accept(getPowerSaveState());

        setAppOps(UID_10_2, PACKAGE_2, true);

        waitUntilMainHandlerDrain();
        verify(l, times(1)).updateAllJobs();
        verify(l, times(0)).updateJobsForUid(anyInt(), anyBoolean());
        verify(l, times(1)).updateJobsForUidPackage(eq(UID_10_2), eq(PACKAGE_2), anyBoolean());
        verify(l, times(1)).updateBackgroundRestrictedForUidPackage(eq(UID_10_2), eq(PACKAGE_2),
                eq(true));

        verify(l, times(1)).updateAllAlarms();
        verify(l, times(0)).updateAlarmsForUid(anyInt());
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
        verify(l, times(0)).updateBackgroundRestrictedForUidPackage(anyInt(), anyString(),
                anyBoolean());

        verify(l, times(1)).updateAllAlarms();
        verify(l, times(0)).updateAlarmsForUid(anyInt());
        verify(l, times(0)).unblockAllUnrestrictedAlarms();
        verify(l, times(0)).unblockAlarmsForUid(anyInt());
        verify(l, times(0)).unblockAlarmsForUidPackage(anyInt(), anyString());
        reset(l);

        // -------------------------------------------------------------------------
        // Tests with system/user/temp exemption list.

        instance.setPowerSaveExemptionListAppIds(new int[] {UID_1, UID_2}, new int[] {},
                new int[] {});

        waitUntilMainHandlerDrain();
        verify(l, times(1)).updateAllJobs();
        verify(l, times(0)).updateJobsForUid(anyInt(), anyBoolean());
        verify(l, times(0)).updateJobsForUidPackage(anyInt(), anyString(), anyBoolean());
        verify(l, times(0)).updateBackgroundRestrictedForUidPackage(anyInt(), anyString(),
                anyBoolean());

        verify(l, times(1)).updateAllAlarms();
        verify(l, times(0)).updateAlarmsForUid(anyInt());
        verify(l, times(1)).unblockAllUnrestrictedAlarms();
        verify(l, times(0)).unblockAlarmsForUid(anyInt());
        verify(l, times(0)).unblockAlarmsForUidPackage(anyInt(), anyString());
        reset(l);

        instance.setPowerSaveExemptionListAppIds(new int[] {UID_2}, new int[] {}, new int[] {});

        waitUntilMainHandlerDrain();
        verify(l, times(1)).updateAllJobs();
        verify(l, times(0)).updateJobsForUid(anyInt(), anyBoolean());
        verify(l, times(0)).updateJobsForUidPackage(anyInt(), anyString(), anyBoolean());
        verify(l, times(0)).updateBackgroundRestrictedForUidPackage(anyInt(), anyString(),
                anyBoolean());

        verify(l, times(1)).updateAllAlarms();
        verify(l, times(0)).updateAlarmsForUid(anyInt());
        verify(l, times(0)).unblockAllUnrestrictedAlarms();
        verify(l, times(0)).unblockAlarmsForUid(anyInt());
        verify(l, times(0)).unblockAlarmsForUidPackage(anyInt(), anyString());
        reset(l);

        // Update temp exemption list.
        instance.setPowerSaveExemptionListAppIds(new int[] {UID_2}, new int[] {},
                new int[] {UID_1, UID_3});

        waitUntilMainHandlerDrain();
        verify(l, times(1)).updateAllJobs();
        verify(l, times(0)).updateJobsForUid(anyInt(), anyBoolean());
        verify(l, times(0)).updateJobsForUidPackage(anyInt(), anyString(), anyBoolean());
        verify(l, times(0)).updateBackgroundRestrictedForUidPackage(anyInt(), anyString(),
                anyBoolean());

        verify(l, times(0)).updateAllAlarms();
        verify(l, times(0)).updateAlarmsForUid(anyInt());
        verify(l, times(0)).unblockAllUnrestrictedAlarms();
        verify(l, times(0)).unblockAlarmsForUid(anyInt());
        verify(l, times(0)).unblockAlarmsForUidPackage(anyInt(), anyString());
        reset(l);

        instance.setPowerSaveExemptionListAppIds(new int[] {UID_2}, new int[] {},
                new int[] {UID_3});

        waitUntilMainHandlerDrain();
        verify(l, times(1)).updateAllJobs();
        verify(l, times(0)).updateJobsForUid(anyInt(), anyBoolean());
        verify(l, times(0)).updateJobsForUidPackage(anyInt(), anyString(), anyBoolean());
        verify(l, times(0)).updateBackgroundRestrictedForUidPackage(anyInt(), anyString(),
                anyBoolean());

        verify(l, times(0)).updateAllAlarms();
        verify(l, times(0)).updateAlarmsForUid(anyInt());
        verify(l, times(0)).unblockAllUnrestrictedAlarms();
        verify(l, times(0)).unblockAlarmsForUid(anyInt());
        verify(l, times(0)).unblockAlarmsForUidPackage(anyInt(), anyString());
        reset(l);

        // Do the same thing with battery saver on.
        mPowerSaveMode = true;
        mPowerSaveObserver.accept(getPowerSaveState());

        waitUntilMainHandlerDrain();
        verify(l, times(1)).updateAllJobs();
        verify(l, times(0)).updateJobsForUid(anyInt(), anyBoolean());
        verify(l, times(0)).updateJobsForUidPackage(anyInt(), anyString(), anyBoolean());
        verify(l, times(0)).updateBackgroundRestrictedForUidPackage(anyInt(), anyString(),
                anyBoolean());

        verify(l, times(1)).updateAllAlarms();
        verify(l, times(0)).updateAlarmsForUid(anyInt());
        verify(l, times(0)).unblockAllUnrestrictedAlarms();
        verify(l, times(0)).unblockAlarmsForUid(anyInt());
        verify(l, times(0)).unblockAlarmsForUidPackage(anyInt(), anyString());
        reset(l);

        instance.setPowerSaveExemptionListAppIds(new int[] {UID_1, UID_2}, new int[] {},
                new int[] {});

        waitUntilMainHandlerDrain();
        // Called once for updating all exemption list and once for updating temp exemption list
        verify(l, times(2)).updateAllJobs();
        verify(l, times(0)).updateJobsForUid(anyInt(), anyBoolean());
        verify(l, times(0)).updateJobsForUidPackage(anyInt(), anyString(), anyBoolean());
        verify(l, times(0)).updateBackgroundRestrictedForUidPackage(anyInt(), anyString(),
                anyBoolean());

        verify(l, times(1)).updateAllAlarms();
        verify(l, times(0)).updateAlarmsForUid(anyInt());
        verify(l, times(1)).unblockAllUnrestrictedAlarms();
        verify(l, times(0)).unblockAlarmsForUid(anyInt());
        verify(l, times(0)).unblockAlarmsForUidPackage(anyInt(), anyString());
        reset(l);

        instance.setPowerSaveExemptionListAppIds(new int[] {UID_2}, new int[] {}, new int[] {});

        waitUntilMainHandlerDrain();
        verify(l, times(1)).updateAllJobs();
        verify(l, times(0)).updateJobsForUid(anyInt(), anyBoolean());
        verify(l, times(0)).updateJobsForUidPackage(anyInt(), anyString(), anyBoolean());
        verify(l, times(0)).updateBackgroundRestrictedForUidPackage(anyInt(), anyString(),
                anyBoolean());

        verify(l, times(1)).updateAllAlarms();
        verify(l, times(0)).updateAlarmsForUid(anyInt());
        verify(l, times(0)).unblockAllUnrestrictedAlarms();
        verify(l, times(0)).unblockAlarmsForUid(anyInt());
        verify(l, times(0)).unblockAlarmsForUidPackage(anyInt(), anyString());
        reset(l);

        // Update temp exemption list.
        instance.setPowerSaveExemptionListAppIds(new int[] {UID_2}, new int[] {},
                new int[] {UID_1, UID_3});

        waitUntilMainHandlerDrain();
        verify(l, times(1)).updateAllJobs();
        verify(l, times(0)).updateJobsForUid(anyInt(), anyBoolean());
        verify(l, times(0)).updateJobsForUidPackage(anyInt(), anyString(), anyBoolean());
        verify(l, times(0)).updateBackgroundRestrictedForUidPackage(anyInt(), anyString(),
                anyBoolean());

        verify(l, times(0)).updateAllAlarms();
        verify(l, times(0)).updateAlarmsForUid(anyInt());
        verify(l, times(0)).unblockAllUnrestrictedAlarms();
        verify(l, times(0)).unblockAlarmsForUid(anyInt());
        verify(l, times(0)).unblockAlarmsForUidPackage(anyInt(), anyString());
        reset(l);

        instance.setPowerSaveExemptionListAppIds(new int[] {UID_2}, new int[] {},
                new int[] {UID_3});

        waitUntilMainHandlerDrain();
        verify(l, times(1)).updateAllJobs();
        verify(l, times(0)).updateJobsForUid(anyInt(), anyBoolean());
        verify(l, times(0)).updateJobsForUidPackage(anyInt(), anyString(), anyBoolean());
        verify(l, times(0)).updateBackgroundRestrictedForUidPackage(anyInt(), anyString(),
                anyBoolean());

        verify(l, times(0)).updateAllAlarms();
        verify(l, times(0)).updateAlarmsForUid(anyInt());
        verify(l, times(0)).unblockAllUnrestrictedAlarms();
        verify(l, times(0)).unblockAlarmsForUid(anyInt());
        verify(l, times(0)).unblockAlarmsForUidPackage(anyInt(), anyString());
        reset(l);


        // -------------------------------------------------------------------------
        // Tests with proc state changes.

        // With battery saver.
        // Battery saver is already on.

        mIUidObserver.onUidActive(UID_10_1);

        waitUntilMainHandlerDrain();
        waitUntilMainHandlerDrain();
        verify(l, times(0)).updateAllJobs();
        verify(l, times(1)).updateJobsForUid(eq(UID_10_1), anyBoolean());
        verify(l, times(0)).updateJobsForUidPackage(anyInt(), anyString(), anyBoolean());
        verify(l, times(0)).updateBackgroundRestrictedForUidPackage(anyInt(), anyString(),
                anyBoolean());

        verify(l, times(0)).updateAllAlarms();
        verify(l, times(1)).updateAlarmsForUid(eq(UID_10_1));
        verify(l, times(0)).unblockAllUnrestrictedAlarms();
        verify(l, times(1)).unblockAlarmsForUid(eq(UID_10_1));
        verify(l, times(0)).unblockAlarmsForUidPackage(anyInt(), anyString());
        reset(l);

        mIUidObserver.onUidGone(UID_10_1, true);

        waitUntilMainHandlerDrain();
        waitUntilMainHandlerDrain();
        verify(l, times(0)).updateAllJobs();
        verify(l, times(1)).updateJobsForUid(eq(UID_10_1), anyBoolean());
        verify(l, times(0)).updateJobsForUidPackage(anyInt(), anyString(), anyBoolean());
        verify(l, times(0)).updateBackgroundRestrictedForUidPackage(anyInt(), anyString(),
                anyBoolean());

        verify(l, times(0)).updateAllAlarms();
        verify(l, times(1)).updateAlarmsForUid(eq(UID_10_1));
        verify(l, times(0)).unblockAllUnrestrictedAlarms();
        verify(l, times(0)).unblockAlarmsForUid(anyInt());
        verify(l, times(0)).unblockAlarmsForUidPackage(anyInt(), anyString());
        reset(l);

        mIUidObserver.onUidActive(UID_10_1);

        waitUntilMainHandlerDrain();
        waitUntilMainHandlerDrain();
        verify(l, times(0)).updateAllJobs();
        verify(l, times(1)).updateJobsForUid(eq(UID_10_1), anyBoolean());
        verify(l, times(0)).updateJobsForUidPackage(anyInt(), anyString(), anyBoolean());
        verify(l, times(0)).updateBackgroundRestrictedForUidPackage(anyInt(), anyString(),
                anyBoolean());

        verify(l, times(0)).updateAllAlarms();
        verify(l, times(1)).updateAlarmsForUid(eq(UID_10_1));
        verify(l, times(0)).unblockAllUnrestrictedAlarms();
        verify(l, times(1)).unblockAlarmsForUid(eq(UID_10_1));
        verify(l, times(0)).unblockAlarmsForUidPackage(anyInt(), anyString());
        reset(l);

        mIUidObserver.onUidIdle(UID_10_1, true);

        waitUntilMainHandlerDrain();
        waitUntilMainHandlerDrain();
        verify(l, times(0)).updateAllJobs();
        verify(l, times(1)).updateJobsForUid(eq(UID_10_1), anyBoolean());
        verify(l, times(0)).updateJobsForUidPackage(anyInt(), anyString(), anyBoolean());
        verify(l, times(0)).updateBackgroundRestrictedForUidPackage(anyInt(), anyString(),
                anyBoolean());

        verify(l, times(0)).updateAllAlarms();
        verify(l, times(1)).updateAlarmsForUid(eq(UID_10_1));
        verify(l, times(0)).unblockAllUnrestrictedAlarms();
        verify(l, times(0)).unblockAlarmsForUid(anyInt());
        verify(l, times(0)).unblockAlarmsForUidPackage(anyInt(), anyString());
        reset(l);

        // Without battery saver.
        mPowerSaveMode = false;
        mPowerSaveObserver.accept(getPowerSaveState());

        waitUntilMainHandlerDrain();
        verify(l, times(1)).updateAllJobs();
        verify(l, times(0)).updateJobsForUid(eq(UID_10_1), anyBoolean());
        verify(l, times(0)).updateJobsForUidPackage(anyInt(), anyString(), anyBoolean());
        verify(l, times(0)).updateBackgroundRestrictedForUidPackage(anyInt(), anyString(),
                anyBoolean());

        verify(l, times(1)).updateAllAlarms();
        verify(l, times(0)).updateAlarmsForUid(eq(UID_10_1));
        verify(l, times(0)).unblockAllUnrestrictedAlarms();
        verify(l, times(0)).unblockAlarmsForUid(anyInt());
        verify(l, times(0)).unblockAlarmsForUidPackage(anyInt(), anyString());
        reset(l);

        mIUidObserver.onUidActive(UID_10_1);

        waitUntilMainHandlerDrain();
        waitUntilMainHandlerDrain();
        verify(l, times(0)).updateAllJobs();
        verify(l, times(1)).updateJobsForUid(eq(UID_10_1), anyBoolean());
        verify(l, times(0)).updateJobsForUidPackage(anyInt(), anyString(), anyBoolean());
        verify(l, times(0)).updateBackgroundRestrictedForUidPackage(anyInt(), anyString(),
                anyBoolean());

        verify(l, times(0)).updateAllAlarms();
        verify(l, times(1)).updateAlarmsForUid(eq(UID_10_1));
        verify(l, times(0)).unblockAllUnrestrictedAlarms();
        verify(l, times(1)).unblockAlarmsForUid(eq(UID_10_1));
        verify(l, times(0)).unblockAlarmsForUidPackage(anyInt(), anyString());
        reset(l);

        mIUidObserver.onUidGone(UID_10_1, true);

        waitUntilMainHandlerDrain();
        waitUntilMainHandlerDrain();
        verify(l, times(0)).updateAllJobs();
        verify(l, times(1)).updateJobsForUid(eq(UID_10_1), anyBoolean());
        verify(l, times(0)).updateJobsForUidPackage(anyInt(), anyString(), anyBoolean());
        verify(l, times(0)).updateBackgroundRestrictedForUidPackage(anyInt(), anyString(),
                anyBoolean());

        verify(l, times(0)).updateAllAlarms();
        verify(l, times(1)).updateAlarmsForUid(eq(UID_10_1));
        verify(l, times(0)).unblockAllUnrestrictedAlarms();
        verify(l, times(0)).unblockAlarmsForUid(anyInt());
        verify(l, times(0)).unblockAlarmsForUidPackage(anyInt(), anyString());
        reset(l);

        mIUidObserver.onUidActive(UID_10_1);

        waitUntilMainHandlerDrain();
        waitUntilMainHandlerDrain();
        verify(l, times(0)).updateAllJobs();
        verify(l, times(1)).updateJobsForUid(eq(UID_10_1), anyBoolean());
        verify(l, times(0)).updateJobsForUidPackage(anyInt(), anyString(), anyBoolean());
        verify(l, times(0)).updateBackgroundRestrictedForUidPackage(anyInt(), anyString(),
                anyBoolean());

        verify(l, times(0)).updateAllAlarms();
        verify(l, times(1)).updateAlarmsForUid(eq(UID_10_1));
        verify(l, times(0)).unblockAllUnrestrictedAlarms();
        verify(l, times(1)).unblockAlarmsForUid(eq(UID_10_1));
        verify(l, times(0)).unblockAlarmsForUidPackage(anyInt(), anyString());
        reset(l);

        mIUidObserver.onUidIdle(UID_10_1, true);

        waitUntilMainHandlerDrain();
        waitUntilMainHandlerDrain();
        verify(l, times(0)).updateAllJobs();
        verify(l, times(1)).updateJobsForUid(eq(UID_10_1), anyBoolean());
        verify(l, times(0)).updateJobsForUidPackage(anyInt(), anyString(), anyBoolean());
        verify(l, times(0)).updateBackgroundRestrictedForUidPackage(anyInt(), anyString(),
                anyBoolean());

        verify(l, times(0)).updateAllAlarms();
        verify(l, times(1)).updateAlarmsForUid(eq(UID_10_1));
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

    @Test
    public void testStateClearedOnPackageRemoved() throws Exception {
        final AppStateTrackerTestable instance = newInstance();
        callStart(instance);

        instance.mActiveUids.put(UID_1, true);
        instance.mRunAnyRestrictedPackages.add(Pair.create(UID_1, PACKAGE_1));
        instance.mExemptedBucketPackages.add(UserHandle.getUserId(UID_2), PACKAGE_2);

        // Replace PACKAGE_1, nothing should change
        Intent packageRemoved = new Intent(Intent.ACTION_PACKAGE_REMOVED)
                .putExtra(Intent.EXTRA_USER_HANDLE, UserHandle.getUserId(UID_1))
                .putExtra(Intent.EXTRA_UID, UID_1)
                .putExtra(Intent.EXTRA_REPLACING, true)
                .setData(Uri.fromParts(IntentFilter.SCHEME_PACKAGE, PACKAGE_1, null));
        mReceiver.onReceive(mMockContext, packageRemoved);

        assertEquals(1, instance.mActiveUids.size());
        assertEquals(1, instance.mRunAnyRestrictedPackages.size());
        assertEquals(1, instance.mExemptedBucketPackages.size());

        // Replace PACKAGE_2, nothing should change
        packageRemoved = new Intent(Intent.ACTION_PACKAGE_REMOVED)
                .putExtra(Intent.EXTRA_USER_HANDLE, UserHandle.getUserId(UID_2))
                .putExtra(Intent.EXTRA_UID, UID_2)
                .putExtra(Intent.EXTRA_REPLACING, true)
                .setData(Uri.fromParts(IntentFilter.SCHEME_PACKAGE, PACKAGE_2, null));
        mReceiver.onReceive(mMockContext, packageRemoved);

        assertEquals(1, instance.mActiveUids.size());
        assertEquals(1, instance.mRunAnyRestrictedPackages.size());
        assertEquals(1, instance.mExemptedBucketPackages.size());

        // Remove PACKAGE_1
        packageRemoved = new Intent(Intent.ACTION_PACKAGE_REMOVED)
                .putExtra(Intent.EXTRA_USER_HANDLE, UserHandle.getUserId(UID_1))
                .putExtra(Intent.EXTRA_UID, UID_1)
                .setData(Uri.fromParts(IntentFilter.SCHEME_PACKAGE, PACKAGE_1, null));
        mReceiver.onReceive(mMockContext, packageRemoved);

        assertEquals(0, instance.mActiveUids.size());
        assertEquals(0, instance.mRunAnyRestrictedPackages.size());
        assertEquals(1, instance.mExemptedBucketPackages.size());

        // Remove PACKAGE_2
        packageRemoved = new Intent(Intent.ACTION_PACKAGE_REMOVED)
                .putExtra(Intent.EXTRA_USER_HANDLE, UserHandle.getUserId(UID_2))
                .putExtra(Intent.EXTRA_UID, UID_2)
                .setData(Uri.fromParts(IntentFilter.SCHEME_PACKAGE, PACKAGE_2, null));
        mReceiver.onReceive(mMockContext, packageRemoved);

        assertEquals(0, instance.mActiveUids.size());
        assertEquals(0, instance.mRunAnyRestrictedPackages.size());
        assertEquals(0, instance.mExemptedBucketPackages.size());
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

    static boolean isAnyAppIdUnexemptSlow(int[] prevArray, int[] newArray) {
        Arrays.sort(newArray); // Just in case...
        for (int p : prevArray) {
            if (Arrays.binarySearch(newArray, p) < 0) {
                return true;
            }
        }
        return false;
    }

    private void checkAnyAppIdUnexempt(int[] prevArray, int[] newArray, boolean expected) {
        assertEquals("Input: " + Arrays.toString(prevArray) + " " + Arrays.toString(newArray),
                expected, AppStateTrackerImpl.isAnyAppIdUnexempt(prevArray, newArray));

        // Also test isAnyAppIdUnexempt.
        assertEquals("Input: " + Arrays.toString(prevArray) + " " + Arrays.toString(newArray),
                expected, isAnyAppIdUnexemptSlow(prevArray, newArray));
    }

    @Test
    public void isAnyAppIdUnexempt() {
        checkAnyAppIdUnexempt(array(), array(), false);

        checkAnyAppIdUnexempt(array(1), array(), true);
        checkAnyAppIdUnexempt(array(1), array(1), false);
        checkAnyAppIdUnexempt(array(1), array(0, 1), false);
        checkAnyAppIdUnexempt(array(1), array(0, 1, 2), false);
        checkAnyAppIdUnexempt(array(1), array(0, 1, 2), false);

        checkAnyAppIdUnexempt(array(1, 2, 10), array(), true);
        checkAnyAppIdUnexempt(array(1, 2, 10), array(1, 2), true);
        checkAnyAppIdUnexempt(array(1, 2, 10), array(1, 2, 10), false);
        checkAnyAppIdUnexempt(array(1, 2, 10), array(2, 10), true);
        checkAnyAppIdUnexempt(array(1, 2, 10), array(0, 1, 2, 4, 3, 10), false);
        checkAnyAppIdUnexempt(array(1, 2, 10), array(0, 0, 1, 2, 10), false);

        // Random test
        int trueCount = 0;
        final int count = 10000;
        for (int i = 0; i < count; i++) {
            final int[] array1 = makeRandomArray();
            final int[] array2 = makeRandomArray();

            final boolean expected = isAnyAppIdUnexemptSlow(array1, array2);
            final boolean actual = AppStateTrackerImpl.isAnyAppIdUnexempt(array1, array2);

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
