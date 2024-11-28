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
package com.android.server.alarm;

import static android.Manifest.permission.SCHEDULE_EXACT_ALARM;
import static android.app.ActivityManager.UidFrozenStateChangedCallback.UID_FROZEN_STATE_FROZEN;
import static android.app.ActivityManager.UidFrozenStateChangedCallback.UID_FROZEN_STATE_UNFROZEN;
import static android.app.AlarmManager.ELAPSED_REALTIME;
import static android.app.AlarmManager.ELAPSED_REALTIME_WAKEUP;
import static android.app.AlarmManager.EXACT_LISTENER_ALARMS_DROPPED_ON_CACHED;
import static android.app.AlarmManager.FLAG_ALLOW_WHILE_IDLE;
import static android.app.AlarmManager.FLAG_ALLOW_WHILE_IDLE_COMPAT;
import static android.app.AlarmManager.FLAG_ALLOW_WHILE_IDLE_UNRESTRICTED;
import static android.app.AlarmManager.FLAG_IDLE_UNTIL;
import static android.app.AlarmManager.FLAG_PRIORITIZE;
import static android.app.AlarmManager.FLAG_STANDALONE;
import static android.app.AlarmManager.FLAG_WAKE_FROM_IDLE;
import static android.app.AlarmManager.RTC;
import static android.app.AlarmManager.RTC_WAKEUP;
import static android.app.AlarmManager.WINDOW_EXACT;
import static android.app.AlarmManager.WINDOW_HEURISTIC;
import static android.app.AppOpsManager.MODE_ALLOWED;
import static android.app.AppOpsManager.MODE_DEFAULT;
import static android.app.AppOpsManager.MODE_ERRORED;
import static android.app.AppOpsManager.MODE_IGNORED;
import static android.app.AppOpsManager.OP_SCHEDULE_EXACT_ALARM;
import static android.app.usage.UsageStatsManager.STANDBY_BUCKET_ACTIVE;
import static android.app.usage.UsageStatsManager.STANDBY_BUCKET_FREQUENT;
import static android.app.usage.UsageStatsManager.STANDBY_BUCKET_RARE;
import static android.app.usage.UsageStatsManager.STANDBY_BUCKET_RESTRICTED;
import static android.app.usage.UsageStatsManager.STANDBY_BUCKET_WORKING_SET;
import static android.os.PowerWhitelistManager.TEMPORARY_ALLOWLIST_TYPE_FOREGROUND_SERVICE_ALLOWED;
import static android.os.PowerWhitelistManager.TEMPORARY_ALLOWLIST_TYPE_FOREGROUND_SERVICE_NOT_ALLOWED;
import static android.platform.test.flag.junit.SetFlagsRule.DefaultInitValueType.NULL_DEFAULT;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.doAnswer;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.doCallRealMethod;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.doThrow;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.mock;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.spyOn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.verify;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.when;
import static com.android.server.SystemTimeZone.TIME_ZONE_CONFIDENCE_HIGH;
import static com.android.server.alarm.Alarm.EXACT_ALLOW_REASON_ALLOW_LIST;
import static com.android.server.alarm.Alarm.EXACT_ALLOW_REASON_COMPAT;
import static com.android.server.alarm.Alarm.EXACT_ALLOW_REASON_LISTENER;
import static com.android.server.alarm.Alarm.EXACT_ALLOW_REASON_NOT_APPLICABLE;
import static com.android.server.alarm.Alarm.EXACT_ALLOW_REASON_PERMISSION;
import static com.android.server.alarm.Alarm.EXACT_ALLOW_REASON_POLICY_PERMISSION;
import static com.android.server.alarm.AlarmManagerService.ACTIVE_INDEX;
import static com.android.server.alarm.AlarmManagerService.AlarmHandler.APP_STANDBY_BUCKET_CHANGED;
import static com.android.server.alarm.AlarmManagerService.AlarmHandler.CHARGING_STATUS_CHANGED;
import static com.android.server.alarm.AlarmManagerService.AlarmHandler.CHECK_EXACT_ALARM_PERMISSION_ON_UPDATE;
import static com.android.server.alarm.AlarmManagerService.AlarmHandler.REFRESH_EXACT_ALARM_CANDIDATES;
import static com.android.server.alarm.AlarmManagerService.AlarmHandler.REMOVE_EXACT_ALARMS;
import static com.android.server.alarm.AlarmManagerService.AlarmHandler.REMOVE_FOR_CANCELED;
import static com.android.server.alarm.AlarmManagerService.AlarmHandler.TEMPORARY_QUOTA_CHANGED;
import static com.android.server.alarm.AlarmManagerService.Constants.KEY_ALLOW_WHILE_IDLE_COMPAT_QUOTA;
import static com.android.server.alarm.AlarmManagerService.Constants.KEY_ALLOW_WHILE_IDLE_COMPAT_WINDOW;
import static com.android.server.alarm.AlarmManagerService.Constants.KEY_ALLOW_WHILE_IDLE_QUOTA;
import static com.android.server.alarm.AlarmManagerService.Constants.KEY_ALLOW_WHILE_IDLE_WHITELIST_DURATION;
import static com.android.server.alarm.AlarmManagerService.Constants.KEY_ALLOW_WHILE_IDLE_WINDOW;
import static com.android.server.alarm.AlarmManagerService.Constants.KEY_LISTENER_TIMEOUT;
import static com.android.server.alarm.AlarmManagerService.Constants.KEY_MAX_DEVICE_IDLE_FUZZ;
import static com.android.server.alarm.AlarmManagerService.Constants.KEY_MAX_INTERVAL;
import static com.android.server.alarm.AlarmManagerService.Constants.KEY_MIN_DEVICE_IDLE_FUZZ;
import static com.android.server.alarm.AlarmManagerService.Constants.KEY_MIN_FUTURITY;
import static com.android.server.alarm.AlarmManagerService.Constants.KEY_MIN_INTERVAL;
import static com.android.server.alarm.AlarmManagerService.Constants.KEY_MIN_WINDOW;
import static com.android.server.alarm.AlarmManagerService.Constants.KEY_PRIORITY_ALARM_DELAY;
import static com.android.server.alarm.AlarmManagerService.Constants.KEY_TEMPORARY_QUOTA_BUMP;
import static com.android.server.alarm.AlarmManagerService.FREQUENT_INDEX;
import static com.android.server.alarm.AlarmManagerService.INDEFINITE_DELAY;
import static com.android.server.alarm.AlarmManagerService.IS_WAKEUP_MASK;
import static com.android.server.alarm.AlarmManagerService.RemovedAlarm.REMOVE_REASON_UNDEFINED;
import static com.android.server.alarm.AlarmManagerService.TIME_CHANGED_MASK;
import static com.android.server.alarm.AlarmManagerService.WORKING_INDEX;
import static com.android.server.alarm.Constants.TEST_CALLING_PACKAGE;
import static com.android.server.alarm.Constants.TEST_CALLING_UID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verifyZeroInteractions;

import android.Manifest;
import android.app.ActivityManager;
import android.app.ActivityManagerInternal;
import android.app.ActivityOptions;
import android.app.AlarmManager;
import android.app.AppOpsManager;
import android.app.BroadcastOptions;
import android.app.IActivityManager;
import android.app.IAlarmCompleteListener;
import android.app.IAlarmListener;
import android.app.IAlarmManager;
import android.app.PendingIntent;
import android.app.compat.CompatChanges;
import android.app.usage.UsageStatsManagerInternal;
import android.companion.virtual.VirtualDeviceManager;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.PermissionChecker;
import android.content.pm.PackageManagerInternal;
import android.content.pm.UserInfo;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Bundle;
import android.os.Environment;
import android.os.FileUtils;
import android.os.Handler;
import android.os.HandlerExecutor;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.PowerExemptionManager;
import android.os.PowerManager;
import android.os.Process;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.os.UserManager;
import android.platform.test.annotations.DisableFlags;
import android.platform.test.annotations.Presubmit;
import android.platform.test.flag.junit.SetFlagsRule;
import android.platform.test.flag.util.FlagSetException;
import android.provider.DeviceConfig;
import android.text.format.DateFormat;
import android.util.ArraySet;
import android.util.Log;
import android.util.SparseArray;

import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import com.android.dx.mockito.inline.extended.MockedVoidMethod;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.app.IAppOpsCallback;
import com.android.internal.app.IAppOpsService;
import com.android.internal.util.ArrayUtils;
import com.android.modules.utils.testing.ExtendedMockitoRule;
import com.android.server.AlarmManagerInternal;
import com.android.server.AppStateTracker;
import com.android.server.AppStateTrackerImpl;
import com.android.server.DeviceIdleInternal;
import com.android.server.LocalServices;
import com.android.server.SystemClockTime.TimeConfidence;
import com.android.server.SystemService;
import com.android.server.pm.UserManagerInternal;
import com.android.server.pm.permission.PermissionManagerService;
import com.android.server.pm.permission.PermissionManagerServiceInternal;
import com.android.server.pm.pkg.AndroidPackage;
import com.android.server.usage.AppStandbyInternal;

import libcore.util.EmptyArray;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Answers;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.quality.Strictness;
import org.mockito.stubbing.Answer;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongConsumer;

@Presubmit
@SuppressWarnings("GuardedBy")  // This test enforces synchronous behavior.
@RunWith(AndroidJUnit4.class)
public final class AlarmManagerServiceTest {
    private static final String TAG = AlarmManagerServiceTest.class.getSimpleName();
    private static final int SYSTEM_UI_UID = 12345;
    private static final int TEST_CALLING_USER = UserHandle.getUserId(TEST_CALLING_UID);
    private static final int TEST_CALLING_UID_2 = TEST_CALLING_UID + 1;
    private static final int[] ALARM_TYPES =
            {RTC_WAKEUP, RTC, ELAPSED_REALTIME_WAKEUP, ELAPSED_REALTIME};

    private long mAppStandbyWindow;
    private long mAllowWhileIdleWindow;
    private AlarmManagerService mService;
    private AppStandbyInternal.AppIdleStateChangeListener mAppStandbyListener;
    private AppStateTrackerImpl.Listener mListener;
    private AlarmManagerService.UninstallReceiver mPackageChangesReceiver;
    private AlarmManagerService.ChargingReceiver mChargingReceiver;
    private ActivityManager.UidFrozenStateChangedCallback mUidFrozenStateCallback;
    private IAppOpsCallback mIAppOpsCallback;
    private IAlarmManager mBinder;
    private File mTestDir;
    @Mock
    private Context mMockContext;
    @Mock
    private ContentResolver mContentResolver;
    @Mock
    private IActivityManager mIActivityManager;
    @Mock
    private IAppOpsService mIAppOpsService;
    @Mock
    private AppOpsManager mAppOpsManager;
    @Mock
    private BatteryManager mBatteryManager;
    @Mock
    private DeviceIdleInternal mDeviceIdleInternal;
    @Mock
    private PermissionManagerServiceInternal mPermissionManagerInternal;
    @Mock
    private UsageStatsManagerInternal mUsageStatsManagerInternal;
    @Mock
    private AppStandbyInternal mAppStandbyInternal;
    @Mock
    private ActivityManagerInternal mActivityManagerInternal;
    @Mock
    private UserManagerInternal mUserManagerInternal;
    @Mock
    private ActivityManager mActivityManager;
    @Mock
    private PackageManagerInternal mPackageManagerInternal;
    @Mock
    private AppStateTrackerImpl mAppStateTracker;
    @Mock
    private AlarmManagerService.ClockReceiver mClockReceiver;
    @Mock
    private PowerManager.WakeLock mWakeLock;
    @Mock
    DeviceConfig.Properties mDeviceConfigProperties;
    HashSet<String> mDeviceConfigKeys = new HashSet<>();

    private Injector mInjector;
    private volatile long mNowElapsedTest;
    private volatile long mNowRtcTest;
    private volatile int mTestCallingUid = TEST_CALLING_UID;
    @GuardedBy("mTestTimer")
    private TestTimer mTestTimer = new TestTimer();

    static class TestTimer {
        private long mElapsed;
        boolean mExpired;
        private int mType;
        private int mFlags; // Flags used to decide what needs to be evaluated.

        synchronized long getElapsed() {
            return mElapsed;
        }

        synchronized void set(int type, long millisElapsed) {
            mType = type;
            mElapsed = millisElapsed;
        }

        synchronized int getType() {
            return mType;
        }

        synchronized int getFlags() {
            return mFlags;
        }

        synchronized void expire() throws InterruptedException {
            expire(IS_WAKEUP_MASK); // Default: evaluate eligibility of all alarms
        }

        synchronized void expire(int flags) throws InterruptedException {
            mFlags = flags;
            mExpired = true;
            notifyAll();
            // Now wait for the alarm thread to finish execution.
            wait();
        }
    }

    public class Injector extends AlarmManagerService.Injector {

        Injector(Context context) {
            super(context);
        }

        @Override
        void init() {
            // Do nothing.
        }

        @Override
        int waitForAlarm() {
            synchronized (mTestTimer) {
                mTestTimer.notifyAll();
                if (!mTestTimer.mExpired) {
                    try {
                        mTestTimer.wait();
                    } catch (InterruptedException ie) {
                        Log.e(TAG, "Wait interrupted!", ie);
                        return 0;
                    }
                }
                mTestTimer.mExpired = false;
            }
            return mTestTimer.getFlags();
        }

        @Override
        int getCallingUid() {
            return mTestCallingUid;
        }

        @Override
        void setAlarm(int type, long millis) {
            mTestTimer.set(type, millis);
        }

        @Override
        int getSystemUiUid(PackageManagerInternal unused) {
            return SYSTEM_UI_UID;
        }

        @Override
        boolean isAlarmDriverPresent() {
            // Pretend the driver is present, so code does not fall back to handler
            return true;
        }

        @Override
        void initializeTimeIfRequired() {
            // No-op
        }

        @Override
        void setCurrentTimeMillis(long unixEpochMillis,
                @TimeConfidence int confidence, String logMsg) {
            mNowRtcTest = unixEpochMillis;
        }

        @Override
        long getElapsedRealtimeMillis() {
            return mNowElapsedTest;
        }

        @Override
        long getCurrentTimeMillis() {
            return mNowRtcTest;
        }

        @Override
        AlarmManagerService.ClockReceiver getClockReceiver(AlarmManagerService service) {
            return mClockReceiver;
        }

        @Override
        PowerManager.WakeLock getAlarmWakeLock() {
            return mWakeLock;
        }

        @Override
        void registerDeviceConfigListener(DeviceConfig.OnPropertiesChangedListener listener) {
            // Do nothing.
            // The tests become flaky with an error message of
            // "IllegalStateException: Querying activity state off main thread is not allowed."
            // when AlarmManager calls DeviceConfig.addOnPropertiesChangedListener().
        }

        @Override
        IAppOpsService getAppOpsService() {
            return mIAppOpsService;
        }
    }

    @Rule
    public final ExtendedMockitoRule mExtendedMockitoRule = new ExtendedMockitoRule.Builder(this)
            .setStrictness(Strictness.WARN)
            .spyStatic(ActivityManager.class)
            .mockStatic(CompatChanges.class)
            .spyStatic(DateFormat.class)
            .spyStatic(DeviceConfig.class)
            .mockStatic(LocalServices.class)
            .spyStatic(Looper.class)
            .mockStatic(MetricsHelper.class)
            .mockStatic(PermissionChecker.class)
            .mockStatic(PermissionManagerService.class)
            .mockStatic(ServiceManager.class)
            .mockStatic(SystemProperties.class)
            .mockStatic(Environment.class)
            .spyStatic(UserHandle.class)
            .afterSessionFinished(
                    () -> LocalServices.removeServiceForTest(AlarmManagerInternal.class))
            .build();

    @Rule
    public final SetFlagsRule mSetFlagsRule = new SetFlagsRule(NULL_DEFAULT);

    /**
     * Have to do this to switch the {@link Flags} implementation to {@link FakeFeatureFlagsImpl}.
     * All methods that need any flag enabled should use the
     * {@link android.platform.test.annotations.EnableFlags} annotation, in which case disabling
     * the flag will fail with an exception that we will swallow here.
     */
    private void disableFlagsNotSetByAnnotation() {
        try {
            mSetFlagsRule.disableFlags(Flags.FLAG_START_USER_BEFORE_SCHEDULED_ALARMS);
        } catch (FlagSetException fse) {
            // Expected if the test about to be run requires this enabled.
        }
    }

    @Before
    public void setUp() {
        doReturn(mIActivityManager).when(ActivityManager::getService);
        doReturn(mDeviceIdleInternal).when(
                () -> LocalServices.getService(DeviceIdleInternal.class));
        doReturn(mPermissionManagerInternal).when(
                () -> LocalServices.getService(PermissionManagerServiceInternal.class));
        doReturn(mActivityManagerInternal).when(
                () -> LocalServices.getService(ActivityManagerInternal.class));
        doReturn(mUserManagerInternal).when(
                () -> LocalServices.getService(UserManagerInternal.class));
        doReturn(mPackageManagerInternal).when(
                () -> LocalServices.getService(PackageManagerInternal.class));
        doReturn(mAppStateTracker).when(() -> LocalServices.getService(AppStateTracker.class));
        doReturn(mAppStandbyInternal).when(
                () -> LocalServices.getService(AppStandbyInternal.class));
        doReturn(mUsageStatsManagerInternal).when(
                () -> LocalServices.getService(UsageStatsManagerInternal.class));
        doCallRealMethod().when((MockedVoidMethod) () ->
                LocalServices.addService(eq(AlarmManagerInternal.class), any()));
        doCallRealMethod().when(() -> LocalServices.getService(AlarmManagerInternal.class));
        doReturn(false).when(() -> UserHandle.isCore(anyInt()));
        when(mUsageStatsManagerInternal.getAppStandbyBucket(eq(TEST_CALLING_PACKAGE),
                eq(TEST_CALLING_USER), anyLong())).thenReturn(STANDBY_BUCKET_ACTIVE);
        doReturn(Looper.getMainLooper()).when(Looper::myLooper);
        mTestDir = new File(InstrumentationRegistry.getInstrumentation().getTargetContext()
                .getFilesDir(), "alarmsTestDir");
        mTestDir.mkdirs();
        doReturn(mTestDir).when(Environment::getDataSystemDirectory);
        when(mMockContext.getContentResolver()).thenReturn(mContentResolver);

        doReturn(mDeviceConfigKeys).when(mDeviceConfigProperties).getKeyset();
        when(mDeviceConfigProperties.getLong(anyString(), anyLong()))
                .thenAnswer((Answer<Long>) invocationOnMock -> {
                    Object[] args = invocationOnMock.getArguments();
                    return (Long) args[1];
                });
        when(mDeviceConfigProperties.getInt(anyString(), anyInt()))
                .thenAnswer((Answer<Integer>) invocationOnMock -> {
                    Object[] args = invocationOnMock.getArguments();
                    return (Integer) args[1];
                });
        doAnswer((Answer<Void>) invocationOnMock -> null)
                .when(() -> DeviceConfig.addOnPropertiesChangedListener(
                        anyString(), any(Executor.class),
                        any(DeviceConfig.OnPropertiesChangedListener.class)));
        doReturn(mDeviceConfigProperties).when(
                () -> DeviceConfig.getProperties(
                        eq(DeviceConfig.NAMESPACE_ALARM_MANAGER), ArgumentMatchers.<String>any()));
        // Needed to ensure logging doesn't cause tests to fail.
        doReturn(true)
                .when(() -> DateFormat.is24HourFormat(eq(mMockContext), anyInt()));

        doReturn(PermissionChecker.PERMISSION_HARD_DENIED).when(
                () -> PermissionChecker.checkPermissionForPreflight(any(),
                        eq(Manifest.permission.USE_EXACT_ALARM), anyInt(), anyInt(), anyString()));
        doReturn(PermissionChecker.PERMISSION_HARD_DENIED).when(
                () -> PermissionChecker.checkPermissionForPreflight(any(), eq(SCHEDULE_EXACT_ALARM),
                        anyInt(), anyInt(), anyString()));

        when(mMockContext.getSystemService(Context.APP_OPS_SERVICE)).thenReturn(mAppOpsManager);
        when(mMockContext.getSystemService(BatteryManager.class)).thenReturn(mBatteryManager);
        when(mMockContext.getSystemService(ActivityManager.class)).thenReturn(mActivityManager);

        registerAppIds(new String[]{TEST_CALLING_PACKAGE},
                new Integer[]{UserHandle.getAppId(TEST_CALLING_UID)});
        when(mPermissionManagerInternal.getAppOpPermissionPackages(
                SCHEDULE_EXACT_ALARM)).thenReturn(EmptyArray.STRING);

        // Initialize timestamps with arbitrary values of time
        mNowElapsedTest = 12;
        mNowRtcTest = 345;
        mInjector = new Injector(mMockContext);
        mService = new AlarmManagerService(mMockContext, mInjector);
        spyOn(mService);

        disableFlagsNotSetByAnnotation();

        mService.onStart();

        final ArgumentCaptor<ActivityManager.UidFrozenStateChangedCallback> frozenCaptor =
                ArgumentCaptor.forClass(ActivityManager.UidFrozenStateChangedCallback.class);
        verify(mActivityManager).registerUidFrozenStateChangedCallback(
                any(HandlerExecutor.class), frozenCaptor.capture());
        mUidFrozenStateCallback = frozenCaptor.getValue();

        // Unable to mock mMockContext to return a mock stats manager.
        // So just mocking the whole MetricsHelper instance.
        mService.mMetricsHelper = mock(MetricsHelper.class);
        spyOn(mService.mHandler);
        // Stubbing the handler. Test should simulate any handling of messages synchronously.
        doReturn(true).when(mService.mHandler).sendMessageAtTime(any(Message.class), anyLong());

        assertEquals(mService.mSystemUiUid, SYSTEM_UI_UID);
        assertEquals(mService.mClockReceiver, mClockReceiver);
        assertEquals(mService.mWakeLock, mWakeLock);

        // Other boot phases don't matter
        mService.onBootPhase(SystemService.PHASE_SYSTEM_SERVICES_READY);

        verify(mBatteryManager).isCharging();
        mAppStandbyWindow = mService.mConstants.APP_STANDBY_WINDOW;
        mAllowWhileIdleWindow = mService.mConstants.ALLOW_WHILE_IDLE_WINDOW;

        ArgumentCaptor<AppStandbyInternal.AppIdleStateChangeListener> idleListenerCaptor =
                ArgumentCaptor.forClass(AppStandbyInternal.AppIdleStateChangeListener.class);
        verify(mAppStandbyInternal).addListener(idleListenerCaptor.capture());
        mAppStandbyListener = idleListenerCaptor.getValue();

        final ArgumentCaptor<AppStateTrackerImpl.Listener> trackerListenerCaptor =
                ArgumentCaptor.forClass(AppStateTrackerImpl.Listener.class);
        verify(mAppStateTracker).addListener(trackerListenerCaptor.capture());
        mListener = trackerListenerCaptor.getValue();

        final ArgumentCaptor<AlarmManagerService.ChargingReceiver> chargingReceiverCaptor =
                ArgumentCaptor.forClass(AlarmManagerService.ChargingReceiver.class);
        verify(mMockContext).registerReceiver(chargingReceiverCaptor.capture(),
                argThat((filter) -> filter.hasAction(BatteryManager.ACTION_CHARGING)
                        && filter.hasAction(BatteryManager.ACTION_DISCHARGING)));
        mChargingReceiver = chargingReceiverCaptor.getValue();

        final ArgumentCaptor<AlarmManagerService.UninstallReceiver> packageReceiverCaptor =
                ArgumentCaptor.forClass(AlarmManagerService.UninstallReceiver.class);
        verify(mMockContext).registerReceiverForAllUsers(packageReceiverCaptor.capture(),
                argThat((filter) -> filter.hasAction(Intent.ACTION_PACKAGE_ADDED)
                        && filter.hasAction(Intent.ACTION_PACKAGE_REMOVED)), isNull(), isNull());
        mPackageChangesReceiver = packageReceiverCaptor.getValue();

        assertEquals(mService.mExactAlarmCandidates, Collections.emptySet());

        ArgumentCaptor<IBinder> binderCaptor = ArgumentCaptor.forClass(IBinder.class);
        verify(() -> ServiceManager.addService(eq(Context.ALARM_SERVICE), binderCaptor.capture(),
                anyBoolean(), anyInt()));
        mBinder = IAlarmManager.Stub.asInterface(binderCaptor.getValue());

        ArgumentCaptor<IAppOpsCallback> appOpsCallbackCaptor = ArgumentCaptor.forClass(
                IAppOpsCallback.class);
        try {
            verify(mIAppOpsService).startWatchingMode(eq(OP_SCHEDULE_EXACT_ALARM),
                    isNull(), appOpsCallbackCaptor.capture());
        } catch (RemoteException e) {
            // Not expected on a mock.
        }
        mIAppOpsCallback = appOpsCallbackCaptor.getValue();
        setTestableQuotas();
    }

    @After
    public void tearDown() {
        // Clean up test dir to remove persisted user files.
        FileUtils.deleteContentsAndDir(mTestDir);
    }

    private void setTestAlarm(int type, long triggerTime, PendingIntent operation) {
        setTestAlarm(type, triggerTime, operation, 0, FLAG_STANDALONE, TEST_CALLING_UID);
    }

    private void setRepeatingTestAlarm(int type, long firstTrigger, long interval,
            PendingIntent pi) {
        setTestAlarm(type, firstTrigger, pi, interval, FLAG_STANDALONE, TEST_CALLING_UID);
    }

    private void setIdleUntilAlarm(int type, long triggerTime, PendingIntent pi) {
        setTestAlarm(type, triggerTime, pi, 0, FLAG_IDLE_UNTIL | FLAG_STANDALONE, TEST_CALLING_UID);
    }

    private void setWakeFromIdle(int type, long triggerTime, PendingIntent pi) {
        // Note: Only alarm clock alarms are allowed to include this flag in the actual service.
        // But this is a unit test so we'll only test the flag for granularity and convenience.
        setTestAlarm(type, triggerTime, pi, 0, FLAG_WAKE_FROM_IDLE | FLAG_STANDALONE,
                TEST_CALLING_UID);
    }

    private void setPrioritizedAlarm(int type, long triggerTime, long windowLength,
            IAlarmListener listener) {
        mService.setImpl(type, triggerTime, windowLength, 0, null, listener, "test",
                FLAG_STANDALONE | FLAG_PRIORITIZE, null, null, TEST_CALLING_UID,
                TEST_CALLING_PACKAGE, null, 0);
    }

    private void setAllowWhileIdleAlarm(int type, long triggerTime, PendingIntent pi,
            boolean unrestricted, boolean compat) {
        assertFalse("Alarm cannot be compat and unrestricted", unrestricted && compat);
        final int flags;
        if (unrestricted) {
            flags = FLAG_ALLOW_WHILE_IDLE_UNRESTRICTED;
        } else if (compat) {
            flags = FLAG_ALLOW_WHILE_IDLE_COMPAT;
        } else {
            flags = FLAG_ALLOW_WHILE_IDLE;
        }
        setTestAlarm(type, triggerTime, pi, 0, flags, TEST_CALLING_UID);
    }

    private void setTestAlarm(int type, long triggerTime, PendingIntent operation, long interval,
            int flags, int callingUid) {
        setTestAlarm(type, triggerTime, 0, operation, interval, flags, callingUid, null);
    }

    private void setTestAlarm(int type, long triggerTime, long windowLength,
            PendingIntent operation, long interval, int flags, int callingUid, Bundle idleOptions) {
        setTestAlarm(type, triggerTime, windowLength, operation, interval, flags, callingUid,
                TEST_CALLING_PACKAGE, idleOptions);
    }

    private void setTestAlarm(int type, long triggerTime, long windowLength,
            PendingIntent operation, long interval, int flags, int callingUid,
            String callingPackage, Bundle idleOptions) {
        mService.setImpl(type, triggerTime, windowLength, interval, operation, null, "test", flags,
                null, null, callingUid, callingPackage, idleOptions, 0);
    }

    private void setTestAlarmWithListener(int type, long triggerTime, IAlarmListener listener) {
        setTestAlarmWithListener(type, triggerTime, listener, WINDOW_EXACT, TEST_CALLING_UID);
    }

    private void setTestAlarmWithListener(int type, long triggerTime, IAlarmListener listener,
            long windowLength, int callingUid) {
        mService.setImpl(type, triggerTime, windowLength, 0, null, listener, "test",
                FLAG_STANDALONE, null, null, callingUid, TEST_CALLING_PACKAGE, null, 0);
    }

    private PendingIntent getNewMockPendingIntent() {
        return getNewMockPendingIntent(false);
    }

    private PendingIntent getNewMockPendingIntent(boolean isActivity) {
        return getNewMockPendingIntent(TEST_CALLING_UID, TEST_CALLING_PACKAGE, isActivity);
    }

    private PendingIntent getNewMockPendingIntent(int creatorUid, String creatorPackage) {
        return getNewMockPendingIntent(creatorUid, creatorPackage, false);
    }

    private PendingIntent getNewMockPendingIntent(int creatorUid, String creatorPackage,
            boolean isActivity) {
        final PendingIntent mockPi = mock(PendingIntent.class, Answers.RETURNS_DEEP_STUBS);
        when(mockPi.getCreatorUid()).thenReturn(creatorUid);
        when(mockPi.getCreatorPackage()).thenReturn(creatorPackage);
        when(mockPi.isActivity()).thenReturn(isActivity);
        return mockPi;
    }

    private IAlarmListener getNewListener(Runnable onAlarm) {
        return new IAlarmListener.Stub() {
            @Override
            public void doAlarm(IAlarmCompleteListener callback) throws RemoteException {
                onAlarm.run();
            }
        };
    }

    private void setDeviceConfigInt(String key, int val) {
        mDeviceConfigKeys.add(key);
        doReturn(val).when(mDeviceConfigProperties).getInt(eq(key), anyInt());
        mService.mConstants.onPropertiesChanged(mDeviceConfigProperties);
    }

    private void setDeviceConfigLong(String key, long val) {
        mDeviceConfigKeys.add(key);
        doReturn(val).when(mDeviceConfigProperties).getLong(eq(key), anyLong());
        mService.mConstants.onPropertiesChanged(mDeviceConfigProperties);
    }

    private void setDeviceConfigBoolean(String key, boolean val) {
        mDeviceConfigKeys.add(key);
        doReturn(val).when(mDeviceConfigProperties).getBoolean(eq(key), anyBoolean());
        mService.mConstants.onPropertiesChanged(mDeviceConfigProperties);
    }

    private void setDeviceConfigString(String key, String val) {
        mDeviceConfigKeys.add(key);
        doReturn(val).when(mDeviceConfigProperties).getString(eq(key), anyString());
        mService.mConstants.onPropertiesChanged(mDeviceConfigProperties);
    }

    /**
     * Lowers quotas to make testing feasible. Careful while calling as this will replace any
     * existing settings for the calling test.
     */
    private void setTestableQuotas() {
        setDeviceConfigLong(KEY_MIN_FUTURITY, 0);
        setDeviceConfigLong(KEY_MIN_INTERVAL, 0);
        mDeviceConfigKeys.add(mService.mConstants.KEYS_APP_STANDBY_QUOTAS[ACTIVE_INDEX]);
        mDeviceConfigKeys.add(mService.mConstants.KEYS_APP_STANDBY_QUOTAS[WORKING_INDEX]);
        mDeviceConfigKeys.add(mService.mConstants.KEYS_APP_STANDBY_QUOTAS[FREQUENT_INDEX]);
        doReturn(50).when(mDeviceConfigProperties)
                .getInt(eq(mService.mConstants.KEYS_APP_STANDBY_QUOTAS[ACTIVE_INDEX]), anyInt());
        doReturn(35).when(mDeviceConfigProperties)
                .getInt(eq(mService.mConstants.KEYS_APP_STANDBY_QUOTAS[WORKING_INDEX]), anyInt());
        doReturn(20).when(mDeviceConfigProperties)
                .getInt(eq(mService.mConstants.KEYS_APP_STANDBY_QUOTAS[FREQUENT_INDEX]), anyInt());

        mService.mConstants.onPropertiesChanged(mDeviceConfigProperties);
    }

    @Test
    public void singleElapsedAlarmSet() {
        final long triggerTime = mNowElapsedTest + 5000;
        final PendingIntent alarmPi = getNewMockPendingIntent();
        setTestAlarm(ELAPSED_REALTIME_WAKEUP, triggerTime, alarmPi);
        assertEquals(triggerTime, mTestTimer.getElapsed());
    }

    @Test
    public void singleRtcAlarmSet() {
        mNowElapsedTest = 54;
        mNowRtcTest = 1243;     // arbitrary values of time
        final long triggerRtc = mNowRtcTest + 5000;
        final PendingIntent alarmPi = getNewMockPendingIntent();
        setTestAlarm(RTC_WAKEUP, triggerRtc, alarmPi);
        final long triggerElapsed = triggerRtc - (mNowRtcTest - mNowElapsedTest);
        assertEquals(triggerElapsed, mTestTimer.getElapsed());
    }

    @Test
    public void timeChangeMovesRtcAlarm() throws Exception {
        mNowElapsedTest = 42;
        mNowRtcTest = 4123;     // arbitrary values of time
        final long triggerRtc = mNowRtcTest + 5000;
        final PendingIntent alarmPi = getNewMockPendingIntent();
        setTestAlarm(RTC_WAKEUP, triggerRtc, alarmPi);
        final long triggerElapsed1 = mTestTimer.getElapsed();
        final long timeDelta = -123;
        mNowRtcTest += timeDelta;
        mTestTimer.expire(TIME_CHANGED_MASK);
        final long triggerElapsed2 = mTestTimer.getElapsed();
        assertEquals("Invalid movement of triggerElapsed following time change", triggerElapsed2,
                triggerElapsed1 - timeDelta);
    }

    @Test
    public void timeChangeBroadcastForward() throws Exception {
        final long timeDelta = 12345;
        // AlarmManagerService sends the broadcast if real time clock proceeds 1000ms more than boot
        // time clock.
        mNowRtcTest += timeDelta + 1001;
        mNowElapsedTest += timeDelta;
        mTestTimer.expire(TIME_CHANGED_MASK);

        verify(mMockContext)
                .sendBroadcastAsUser(
                        argThat((intent) -> intent.getAction() == Intent.ACTION_TIME_CHANGED),
                        eq(UserHandle.ALL),
                        isNull(),
                        any());
    }

    @Test
    public void timeChangeBroadcastBackward() throws Exception {
        final long timeDelta = 12345;
        // AlarmManagerService sends the broadcast if real time clock proceeds 1000ms less than boot
        // time clock.
        mNowRtcTest += timeDelta - 1001;
        mNowElapsedTest += timeDelta;
        mTestTimer.expire(TIME_CHANGED_MASK);

        verify(mMockContext)
                .sendBroadcastAsUser(
                        argThat((intent) -> intent.getAction() == Intent.ACTION_TIME_CHANGED),
                        eq(UserHandle.ALL),
                        isNull(),
                        any());
    }

    @Test
    public void timeChangeFilterMinorAdjustment() throws Exception {
        final long timeDelta = 12345;
        // AlarmManagerService does not send the broadcast if real time clock proceeds within 1000ms
        // than boot time clock.
        mNowRtcTest += timeDelta + 1000;
        mNowElapsedTest += timeDelta;
        mTestTimer.expire(TIME_CHANGED_MASK);

        mNowRtcTest += timeDelta - 1000;
        mNowElapsedTest += timeDelta;
        mTestTimer.expire(TIME_CHANGED_MASK);

        verify(mMockContext, never())
                .sendBroadcastAsUser(
                        argThat((intent) -> intent.getAction() == Intent.ACTION_TIME_CHANGED),
                        any(),
                        any(),
                        any());
    }

    @Test
    public void testSingleAlarmExpiration() throws Exception {
        final long triggerTime = mNowElapsedTest + 5000;
        final PendingIntent alarmPi = getNewMockPendingIntent();
        setTestAlarm(ELAPSED_REALTIME_WAKEUP, triggerTime, alarmPi);

        mNowElapsedTest = mTestTimer.getElapsed();
        mTestTimer.expire();

        final ArgumentCaptor<PendingIntent.OnFinished> onFinishedCaptor =
                ArgumentCaptor.forClass(PendingIntent.OnFinished.class);
        verify(alarmPi).send(eq(mMockContext), eq(0), any(Intent.class),
                onFinishedCaptor.capture(), any(Handler.class), isNull(), any());
        verify(mWakeLock).acquire();
        onFinishedCaptor.getValue().onSendFinished(alarmPi, null, 0, null, null);
        verify(mWakeLock).release();
    }

    @Test
    public void testAlarmBroadcastOption() throws Exception {
        final long triggerTime = mNowElapsedTest + 5000;
        final PendingIntent alarmPi = getNewMockPendingIntent();
        setTestAlarm(ELAPSED_REALTIME_WAKEUP, triggerTime, alarmPi);

        mNowElapsedTest = mTestTimer.getElapsed();
        mTestTimer.expire();

        final ArgumentCaptor<PendingIntent.OnFinished> onFinishedCaptor =
                ArgumentCaptor.forClass(PendingIntent.OnFinished.class);
        final ArgumentCaptor<Bundle> optionsCaptor = ArgumentCaptor.forClass(Bundle.class);
        verify(alarmPi).send(eq(mMockContext), eq(0), any(Intent.class),
                onFinishedCaptor.capture(), any(Handler.class), isNull(),
                optionsCaptor.capture());
        final BroadcastOptions options = new BroadcastOptions(optionsCaptor.getValue());
        assertTrue(options.isAlarmBroadcast());
    }

    @Test
    public void testUpdateConstants() {
        setDeviceConfigLong(KEY_MIN_FUTURITY, 5);
        setDeviceConfigLong(KEY_MIN_INTERVAL, 10);
        setDeviceConfigLong(KEY_MAX_INTERVAL, 15);
        setDeviceConfigInt(KEY_ALLOW_WHILE_IDLE_QUOTA, 20);
        setDeviceConfigInt(KEY_ALLOW_WHILE_IDLE_COMPAT_QUOTA, 25);
        setDeviceConfigLong(KEY_ALLOW_WHILE_IDLE_WINDOW, 30);
        setDeviceConfigLong(KEY_ALLOW_WHILE_IDLE_COMPAT_WINDOW, 35);
        setDeviceConfigLong(KEY_ALLOW_WHILE_IDLE_WHITELIST_DURATION, 40);
        setDeviceConfigLong(KEY_LISTENER_TIMEOUT, 45);
        setDeviceConfigLong(KEY_MIN_WINDOW, 50);
        setDeviceConfigLong(KEY_PRIORITY_ALARM_DELAY, 55);
        setDeviceConfigLong(KEY_MIN_DEVICE_IDLE_FUZZ, 60);
        setDeviceConfigLong(KEY_MAX_DEVICE_IDLE_FUZZ, 65);
        setDeviceConfigInt(KEY_TEMPORARY_QUOTA_BUMP, 70);
        assertEquals(5, mService.mConstants.MIN_FUTURITY);
        assertEquals(10, mService.mConstants.MIN_INTERVAL);
        assertEquals(15, mService.mConstants.MAX_INTERVAL);
        assertEquals(20, mService.mConstants.ALLOW_WHILE_IDLE_QUOTA);
        assertEquals(25, mService.mConstants.ALLOW_WHILE_IDLE_COMPAT_QUOTA);
        assertEquals(30, mService.mConstants.ALLOW_WHILE_IDLE_WINDOW);
        assertEquals(35, mService.mConstants.ALLOW_WHILE_IDLE_COMPAT_WINDOW);
        assertEquals(40, mService.mConstants.ALLOW_WHILE_IDLE_WHITELIST_DURATION);
        assertEquals(45, mService.mConstants.LISTENER_TIMEOUT);
        assertEquals(50, mService.mConstants.MIN_WINDOW);
        assertEquals(55, mService.mConstants.PRIORITY_ALARM_DELAY);
        assertEquals(60, mService.mConstants.MIN_DEVICE_IDLE_FUZZ);
        assertEquals(65, mService.mConstants.MAX_DEVICE_IDLE_FUZZ);
        assertEquals(70, mService.mConstants.TEMPORARY_QUOTA_BUMP);
    }

    @Test
    public void positiveWhileIdleQuotas() {
        setDeviceConfigInt(KEY_ALLOW_WHILE_IDLE_QUOTA, -3);
        assertEquals(1, mService.mConstants.ALLOW_WHILE_IDLE_QUOTA);
        setDeviceConfigInt(KEY_ALLOW_WHILE_IDLE_QUOTA, 0);
        assertEquals(1, mService.mConstants.ALLOW_WHILE_IDLE_QUOTA);

        setDeviceConfigInt(KEY_ALLOW_WHILE_IDLE_COMPAT_QUOTA, -8);
        assertEquals(1, mService.mConstants.ALLOW_WHILE_IDLE_COMPAT_QUOTA);
        setDeviceConfigInt(KEY_ALLOW_WHILE_IDLE_COMPAT_QUOTA, 0);
        assertEquals(1, mService.mConstants.ALLOW_WHILE_IDLE_COMPAT_QUOTA);
    }

    @Test
    public void whileIdleWindowsDontExceedAnHour() {
        setDeviceConfigLong(KEY_ALLOW_WHILE_IDLE_WINDOW, AlarmManager.INTERVAL_DAY);
        assertEquals(AlarmManager.INTERVAL_HOUR, mService.mConstants.ALLOW_WHILE_IDLE_WINDOW);
        setDeviceConfigLong(KEY_ALLOW_WHILE_IDLE_WINDOW, AlarmManager.INTERVAL_HOUR + 1);
        assertEquals(AlarmManager.INTERVAL_HOUR, mService.mConstants.ALLOW_WHILE_IDLE_WINDOW);

        setDeviceConfigLong(KEY_ALLOW_WHILE_IDLE_COMPAT_WINDOW, AlarmManager.INTERVAL_DAY);
        assertEquals(AlarmManager.INTERVAL_HOUR,
                mService.mConstants.ALLOW_WHILE_IDLE_COMPAT_WINDOW);
        setDeviceConfigLong(KEY_ALLOW_WHILE_IDLE_COMPAT_WINDOW, AlarmManager.INTERVAL_HOUR + 1);
        assertEquals(AlarmManager.INTERVAL_HOUR,
                mService.mConstants.ALLOW_WHILE_IDLE_COMPAT_WINDOW);
    }

    @Test
    public void deviceIdleFuzzRangeNonNegative() {
        final long newMinFuzz = mService.mConstants.MAX_DEVICE_IDLE_FUZZ + 1542;
        final long newMaxFuzz = mService.mConstants.MIN_DEVICE_IDLE_FUZZ - 131;

        setDeviceConfigLong(KEY_MIN_DEVICE_IDLE_FUZZ, newMinFuzz);
        assertTrue("Negative device-idle fuzz range", mService.mConstants.MAX_DEVICE_IDLE_FUZZ
                >= mService.mConstants.MIN_DEVICE_IDLE_FUZZ);

        setDeviceConfigLong(KEY_MAX_DEVICE_IDLE_FUZZ, newMaxFuzz);
        assertTrue("Negative device-idle fuzz range", mService.mConstants.MAX_DEVICE_IDLE_FUZZ
                >= mService.mConstants.MIN_DEVICE_IDLE_FUZZ);
    }

    @Test
    public void testMinFuturity() {
        setDeviceConfigLong(KEY_MIN_FUTURITY, 10L);
        assertEquals(10, mService.mConstants.MIN_FUTURITY);
        final long triggerTime = mNowElapsedTest + 1;
        final long expectedTriggerTime = mNowElapsedTest + mService.mConstants.MIN_FUTURITY;
        setTestAlarm(ELAPSED_REALTIME_WAKEUP, triggerTime, getNewMockPendingIntent());
        assertEquals(expectedTriggerTime, mTestTimer.getElapsed());
    }

    @Test
    public void testMinFuturityCoreUid() {
        setDeviceConfigLong(KEY_MIN_FUTURITY, 10L);
        assertEquals(10, mService.mConstants.MIN_FUTURITY);
        final long triggerTime = mNowElapsedTest + 1;
        doReturn(true).when(() -> UserHandle.isCore(TEST_CALLING_UID));
        final long expectedTriggerTime = triggerTime;
        setTestAlarm(ELAPSED_REALTIME_WAKEUP, triggerTime, getNewMockPendingIntent());
        assertEquals(expectedTriggerTime, mTestTimer.getElapsed());
    }

    @Test
    public void testEarliestAlarmSet() {
        final PendingIntent pi6 = getNewMockPendingIntent();
        final PendingIntent pi8 = getNewMockPendingIntent();
        final PendingIntent pi9 = getNewMockPendingIntent();

        setTestAlarm(ELAPSED_REALTIME_WAKEUP, mNowElapsedTest + 8, pi8);
        assertEquals(mNowElapsedTest + 8, mTestTimer.getElapsed());

        setTestAlarm(ELAPSED_REALTIME_WAKEUP, mNowElapsedTest + 9, pi9);
        assertEquals(mNowElapsedTest + 8, mTestTimer.getElapsed());

        setTestAlarm(ELAPSED_REALTIME_WAKEUP, mNowElapsedTest + 6, pi6);
        assertEquals(mNowElapsedTest + 6, mTestTimer.getElapsed());

        mService.removeLocked(pi6, null, REMOVE_REASON_UNDEFINED);
        assertEquals(mNowElapsedTest + 8, mTestTimer.getElapsed());

        mService.removeLocked(pi8, null, REMOVE_REASON_UNDEFINED);
        assertEquals(mNowElapsedTest + 9, mTestTimer.getElapsed());
    }

    private void testQuotasDeferralOnSet(LongConsumer alarmSetter, int quota, long window)
            throws Exception {
        final long firstTrigger = mNowElapsedTest + 10;
        for (int i = 0; i < quota; i++) {
            alarmSetter.accept(firstTrigger + i);
            mNowElapsedTest = mTestTimer.getElapsed();
            assertEquals("Incorrect trigger time at i=" + i, firstTrigger + i, mNowElapsedTest);
            mTestTimer.expire();
        }
        // This one should get deferred on set
        alarmSetter.accept(firstTrigger + quota);
        final long expectedNextTrigger = firstTrigger + window;
        assertEquals("Incorrect next alarm trigger", expectedNextTrigger, mTestTimer.getElapsed());
    }

    private void testQuotasDeferralOnExpiration(LongConsumer alarmSetter, int quota, long window)
            throws Exception {
        final long firstTrigger = mNowElapsedTest + 10;
        for (int i = 0; i < quota; i++) {
            alarmSetter.accept(firstTrigger + i);
        }
        // This one should get deferred after the latest alarm expires.
        alarmSetter.accept(firstTrigger + quota);
        for (int i = 0; i < quota; i++) {
            mNowElapsedTest = mTestTimer.getElapsed();
            assertEquals("Incorrect trigger time at i=" + i, firstTrigger + i, mNowElapsedTest);
            mTestTimer.expire();
        }
        final long expectedNextTrigger = firstTrigger + window;
        assertEquals("Incorrect next alarm trigger", expectedNextTrigger, mTestTimer.getElapsed());
    }

    private void testQuotasNoDeferral(LongConsumer alarmSetter, int quota, long window)
            throws Exception {
        final long firstTrigger = mNowElapsedTest + 10;
        for (int i = 0; i < quota; i++) {
            alarmSetter.accept(firstTrigger + i);
        }
        // This delivery time maintains the quota invariant. Should not be deferred.
        final long expectedNextTrigger = firstTrigger + window + 5;
        alarmSetter.accept(expectedNextTrigger);
        for (int i = 0; i < quota; i++) {
            mNowElapsedTest = mTestTimer.getElapsed();
            assertEquals("Incorrect trigger time at i=" + i, firstTrigger + i, mNowElapsedTest);
            mTestTimer.expire();
        }
        assertEquals("Incorrect next alarm trigger", expectedNextTrigger, mTestTimer.getElapsed());
    }

    private void testStandbyQuotasDeferralOnSet(int standbyBucket) throws Exception {
        final int quota = mService.getQuotaForBucketLocked(standbyBucket);
        when(mUsageStatsManagerInternal.getAppStandbyBucket(eq(TEST_CALLING_PACKAGE), anyInt(),
                anyLong())).thenReturn(standbyBucket);
        testQuotasDeferralOnSet(trigger -> setTestAlarm(ELAPSED_REALTIME_WAKEUP, trigger,
                getNewMockPendingIntent()), quota, mAppStandbyWindow);
    }

    private void testStandbyQuotasDeferralOnExpiration(int standbyBucket) throws Exception {
        final int quota = mService.getQuotaForBucketLocked(standbyBucket);
        when(mUsageStatsManagerInternal.getAppStandbyBucket(eq(TEST_CALLING_PACKAGE), anyInt(),
                anyLong())).thenReturn(standbyBucket);
        testQuotasDeferralOnExpiration(trigger -> setTestAlarm(ELAPSED_REALTIME_WAKEUP, trigger,
                getNewMockPendingIntent()), quota, mAppStandbyWindow);
    }

    private void testStandbyQuotasNoDeferral(int standbyBucket) throws Exception {
        final int quota = mService.getQuotaForBucketLocked(standbyBucket);
        when(mUsageStatsManagerInternal.getAppStandbyBucket(eq(TEST_CALLING_PACKAGE), anyInt(),
                anyLong())).thenReturn(standbyBucket);
        testQuotasNoDeferral(trigger -> setTestAlarm(ELAPSED_REALTIME_WAKEUP, trigger,
                getNewMockPendingIntent()), quota, mAppStandbyWindow);
    }

    @Test
    public void testActiveQuota_deferredOnSet() throws Exception {
        testStandbyQuotasDeferralOnSet(STANDBY_BUCKET_ACTIVE);
    }

    @Test
    public void testActiveQuota_deferredOnExpiration() throws Exception {
        testStandbyQuotasDeferralOnExpiration(STANDBY_BUCKET_ACTIVE);
    }

    @Test
    public void testActiveQuota_notDeferred() throws Exception {
        testStandbyQuotasNoDeferral(STANDBY_BUCKET_ACTIVE);
    }

    @Test
    public void testWorkingQuota_deferredOnSet() throws Exception {
        testStandbyQuotasDeferralOnSet(STANDBY_BUCKET_WORKING_SET);
    }

    @Test
    public void testWorkingQuota_deferredOnExpiration() throws Exception {
        testStandbyQuotasDeferralOnExpiration(STANDBY_BUCKET_WORKING_SET);
    }

    @Test
    public void testWorkingQuota_notDeferred() throws Exception {
        testStandbyQuotasNoDeferral(STANDBY_BUCKET_WORKING_SET);
    }

    @Test
    public void testFrequentQuota_deferredOnSet() throws Exception {
        testStandbyQuotasDeferralOnSet(STANDBY_BUCKET_FREQUENT);
    }

    @Test
    public void testFrequentQuota_deferredOnExpiration() throws Exception {
        testStandbyQuotasDeferralOnExpiration(STANDBY_BUCKET_FREQUENT);
    }

    @Test
    public void testFrequentQuota_notDeferred() throws Exception {
        testStandbyQuotasNoDeferral(STANDBY_BUCKET_FREQUENT);
    }

    @Test
    public void testRareQuota_deferredOnSet() throws Exception {
        testStandbyQuotasDeferralOnSet(STANDBY_BUCKET_RARE);
    }

    @Test
    public void testRareQuota_deferredOnExpiration() throws Exception {
        testStandbyQuotasDeferralOnExpiration(STANDBY_BUCKET_RARE);
    }

    @Test
    public void testRareQuota_notDeferred() throws Exception {
        testStandbyQuotasNoDeferral(STANDBY_BUCKET_RARE);
    }

    @Test
    public void testRestrictedBucketAlarmsDeferredOnSet() throws Exception {
        when(mUsageStatsManagerInternal
                .getAppStandbyBucket(eq(TEST_CALLING_PACKAGE), anyInt(), anyLong()))
                .thenReturn(STANDBY_BUCKET_RESTRICTED);
        // This one should go off
        final long firstTrigger = mNowElapsedTest + 10;
        setTestAlarm(ELAPSED_REALTIME_WAKEUP, firstTrigger, getNewMockPendingIntent());
        mNowElapsedTest = mTestTimer.getElapsed();
        mTestTimer.expire();

        // This one should get deferred on set
        setTestAlarm(ELAPSED_REALTIME_WAKEUP, firstTrigger + 1, getNewMockPendingIntent());
        final long expectedNextTrigger =
                firstTrigger + mService.mConstants.APP_STANDBY_RESTRICTED_WINDOW;
        assertEquals("Incorrect next alarm trigger", expectedNextTrigger, mTestTimer.getElapsed());
    }

    @Test
    public void testRestrictedBucketAlarmsDeferredOnExpiration() throws Exception {
        when(mUsageStatsManagerInternal
                .getAppStandbyBucket(eq(TEST_CALLING_PACKAGE), anyInt(), anyLong()))
                .thenReturn(STANDBY_BUCKET_RESTRICTED);
        // This one should go off
        final long firstTrigger = mNowElapsedTest + 10;
        setTestAlarm(ELAPSED_REALTIME_WAKEUP, firstTrigger, getNewMockPendingIntent());

        // This one should get deferred after the latest alarm expires
        setTestAlarm(ELAPSED_REALTIME_WAKEUP, firstTrigger + 1, getNewMockPendingIntent());

        mNowElapsedTest = mTestTimer.getElapsed();
        mTestTimer.expire();
        final long expectedNextTrigger =
                firstTrigger + mService.mConstants.APP_STANDBY_RESTRICTED_WINDOW;
        assertEquals("Incorrect next alarm trigger", expectedNextTrigger, mTestTimer.getElapsed());
    }

    private void assertAndHandleBucketChanged(int bucket) {
        when(mUsageStatsManagerInternal.getAppStandbyBucket(eq(TEST_CALLING_PACKAGE), anyInt(),
                anyLong())).thenReturn(bucket);
        mAppStandbyListener.onAppIdleStateChanged(TEST_CALLING_PACKAGE,
                UserHandle.getUserId(TEST_CALLING_UID), false, bucket, 0);
        assertAndHandleMessageSync(APP_STANDBY_BUCKET_CHANGED);
    }

    private void assertAndHandleMessageSync(int what) {
        final ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);
        verify(mService.mHandler, atLeastOnce()).sendMessageAtTime(messageCaptor.capture(),
                anyLong());

        Message expectedMessage = null;
        final ArrayList<Integer> sentMessages = new ArrayList<>();
        for (Message sentMessage : messageCaptor.getAllValues()) {
            if (sentMessage.what == what) {
                expectedMessage = sentMessage;
            }
            sentMessages.add(sentMessage.what);
        }

        assertNotNull("Unexpected messages sent to handler. Expected: " + what + ", sent: "
                + sentMessages, expectedMessage);
        mService.mHandler.handleMessage(expectedMessage);
    }

    @Test
    public void testStandbyQuotaDowngrade() throws Exception {
        final int workingQuota = mService.getQuotaForBucketLocked(STANDBY_BUCKET_WORKING_SET);
        when(mUsageStatsManagerInternal.getAppStandbyBucket(eq(TEST_CALLING_PACKAGE), anyInt(),
                anyLong())).thenReturn(STANDBY_BUCKET_WORKING_SET);

        final long firstTrigger = mNowElapsedTest + 10;
        for (int i = 0; i < workingQuota; i++) {
            setTestAlarm(ELAPSED_REALTIME_WAKEUP, firstTrigger + i, getNewMockPendingIntent());
        }
        // No deferrals now.
        for (int i = 0; i < workingQuota - 1; i++) {
            mNowElapsedTest = mTestTimer.getElapsed();
            assertEquals(firstTrigger + i, mNowElapsedTest);
            mTestTimer.expire();
        }
        // The next upcoming alarm in queue should also be set as expected.
        assertEquals(firstTrigger + workingQuota - 1, mTestTimer.getElapsed());
        // Downgrading the bucket now
        assertAndHandleBucketChanged(STANDBY_BUCKET_RARE);
        final int rareQuota = mService.getQuotaForBucketLocked(STANDBY_BUCKET_RARE);
        // The last alarm should now be deferred.
        final long expectedNextTrigger = (firstTrigger + workingQuota - 1 - rareQuota)
                + mAppStandbyWindow;
        assertEquals("Incorrect next alarm trigger", expectedNextTrigger, mTestTimer.getElapsed());
    }

    @Test
    public void testStandbyQuotaUpgrade() throws Exception {
        final int frequentQuota = mService.getQuotaForBucketLocked(STANDBY_BUCKET_FREQUENT);
        when(mUsageStatsManagerInternal.getAppStandbyBucket(eq(TEST_CALLING_PACKAGE), anyInt(),
                anyLong())).thenReturn(STANDBY_BUCKET_FREQUENT);

        final long firstTrigger = mNowElapsedTest + 10;
        for (int i = 0; i < frequentQuota + 1; i++) {
            setTestAlarm(ELAPSED_REALTIME_WAKEUP, firstTrigger + i, getNewMockPendingIntent());
            if (i < frequentQuota) {
                mNowElapsedTest = mTestTimer.getElapsed();
                mTestTimer.expire();
            }
        }
        // The last alarm should be deferred due to exceeding the quota
        final long deferredTrigger = firstTrigger + mAppStandbyWindow;
        assertEquals(deferredTrigger, mTestTimer.getElapsed());

        // Upgrading the bucket now
        assertAndHandleBucketChanged(STANDBY_BUCKET_ACTIVE);
        // The last alarm should now be rescheduled to go as per original expectations
        final long originalTrigger = firstTrigger + frequentQuota;
        assertEquals("Incorrect next alarm trigger", originalTrigger, mTestTimer.getElapsed());
    }

    private void assertAndHandleParoleChanged(boolean parole) {
        mChargingReceiver.onReceive(mMockContext,
                new Intent(parole ? BatteryManager.ACTION_CHARGING
                        : BatteryManager.ACTION_DISCHARGING));
        assertAndHandleMessageSync(CHARGING_STATUS_CHANGED);
        assertEquals(parole, mService.mAppStandbyParole);
    }

    @Test
    public void testCharging() throws Exception {
        final int workingQuota = mService.getQuotaForBucketLocked(STANDBY_BUCKET_WORKING_SET);
        when(mUsageStatsManagerInternal.getAppStandbyBucket(eq(TEST_CALLING_PACKAGE), anyInt(),
                anyLong())).thenReturn(STANDBY_BUCKET_WORKING_SET);

        final long firstTrigger = mNowElapsedTest + 10;
        final int totalAlarms = workingQuota + 10;
        for (int i = 0; i < totalAlarms; i++) {
            setTestAlarm(ELAPSED_REALTIME_WAKEUP, firstTrigger + i, getNewMockPendingIntent());
        }
        // Use up the quota, no deferrals expected.
        for (int i = 0; i < workingQuota; i++) {
            mNowElapsedTest = mTestTimer.getElapsed();
            assertEquals(firstTrigger + i, mNowElapsedTest);
            mTestTimer.expire();
        }
        // Any subsequent alarms in queue should all be deferred
        assertEquals(firstTrigger + mAppStandbyWindow, mTestTimer.getElapsed());
        // Paroling now
        assertAndHandleParoleChanged(true);

        // Subsequent alarms should now go off as per original expectations.
        for (int i = 0; i < 5; i++) {
            mNowElapsedTest = mTestTimer.getElapsed();
            assertEquals(firstTrigger + workingQuota + i, mNowElapsedTest);
            mTestTimer.expire();
        }
        // Come out of parole
        assertAndHandleParoleChanged(false);

        // Subsequent alarms should again get deferred
        final long expectedNextTrigger = (firstTrigger + 5) + mAppStandbyWindow;
        assertEquals("Incorrect next alarm trigger", expectedNextTrigger, mTestTimer.getElapsed());
    }

    @Test
    public void testAlarmRestrictedByFAS() throws Exception {
        final PendingIntent alarmPi = getNewMockPendingIntent();
        when(mAppStateTracker.areAlarmsRestricted(TEST_CALLING_UID,
                TEST_CALLING_PACKAGE)).thenReturn(true);
        setTestAlarm(ELAPSED_REALTIME_WAKEUP, mNowElapsedTest + 2, alarmPi);
        assertEquals(mNowElapsedTest + 2, mTestTimer.getElapsed());

        final SparseArray<ArrayList<Alarm>> restrictedAlarms =
                mService.mPendingBackgroundAlarms;
        assertNull(restrictedAlarms.get(TEST_CALLING_UID));

        mNowElapsedTest = mTestTimer.getElapsed();
        mTestTimer.expire();
        assertNotNull(restrictedAlarms.get(TEST_CALLING_UID));

        mListener.unblockAlarmsForUid(TEST_CALLING_UID);
        verify(alarmPi).send(eq(mMockContext), eq(0), any(Intent.class), any(),
                any(Handler.class), isNull(), any());
        assertNull(restrictedAlarms.get(TEST_CALLING_UID));
    }

    @Test
    public void alarmsRemovedOnAppStartModeDisabled() {
        final PendingIntent alarmPi1 = getNewMockPendingIntent();
        final PendingIntent alarmPi2 = getNewMockPendingIntent();

        setTestAlarm(ELAPSED_REALTIME_WAKEUP, mNowElapsedTest + 2, alarmPi1);
        setTestAlarm(ELAPSED_REALTIME_WAKEUP, mNowElapsedTest + 4, alarmPi2);

        assertEquals(2, mService.mAlarmsPerUid.get(TEST_CALLING_UID));

        when(mActivityManagerInternal.isAppStartModeDisabled(TEST_CALLING_UID,
                TEST_CALLING_PACKAGE)).thenReturn(true);
        mListener.removeAlarmsForUid(TEST_CALLING_UID);
        assertEquals(0, mService.mAlarmsPerUid.get(TEST_CALLING_UID));
    }

    @Test
    public void wakeupShouldBeScheduledForFullUsers_skipsGuestSystemAndProfiles() {
        final int systemUserId = 0;
        final int fullUserId = 10;
        final int privateProfileId = 12;
        final int guestUserId = 13;
        when(mUserManagerInternal.getUserInfo(fullUserId)).thenReturn(new UserInfo(fullUserId,
                "TestUser2", UserInfo.FLAG_FULL));
        when(mUserManagerInternal.getUserInfo(privateProfileId)).thenReturn(new UserInfo(
                privateProfileId, "TestUser3", UserInfo.FLAG_PROFILE));
        when(mUserManagerInternal.getUserInfo(guestUserId)).thenReturn(new UserInfo(
                guestUserId, "TestUserGuest", null, 0, UserManager.USER_TYPE_FULL_GUEST));
        when(mUserManagerInternal.getUserInfo(systemUserId)).thenReturn(new UserInfo(
                systemUserId, "TestUserSystem", null, 0, UserManager.USER_TYPE_FULL_SYSTEM));
        assertTrue(mService.shouldAddWakeupForUser(fullUserId));
        assertFalse(mService.shouldAddWakeupForUser(systemUserId));
        assertFalse(mService.shouldAddWakeupForUser(privateProfileId));
        assertFalse(mService.shouldAddWakeupForUser(guestUserId));
    }

    @Test
    public void sendsTimeTickOnInteractive() {
        final ArgumentCaptor<Runnable> runnableCaptor = ArgumentCaptor.forClass(Runnable.class);
        // Stubbing so the handler doesn't actually run the runnable.
        doReturn(true).when(mService.mHandler).post(runnableCaptor.capture());
        // change interactive state: false -> true
        mService.interactiveStateChangedLocked(false);
        mService.interactiveStateChangedLocked(true);
        runnableCaptor.getValue().run();
        final ArgumentCaptor<Bundle> optionsCaptor = ArgumentCaptor.forClass(Bundle.class);
        verify(mMockContext).sendBroadcastAsUser(eq(mService.mTimeTickIntent), eq(UserHandle.ALL),
                isNull(), optionsCaptor.capture());
        verifyTimeTickBroadcastOptions(optionsCaptor.getValue());
    }

    @Test
    public void sendsTimeTickOnAlarmTrigger() throws Exception {
        final ArgumentCaptor<Runnable> runnableCaptor = ArgumentCaptor.forClass(Runnable.class);
        // Stubbing so the handler doesn't actually run the runnable.
        doReturn(true).when(mService.mHandler).post(runnableCaptor.capture());
        mService.mTimeTickTrigger.doAlarm(mock(IAlarmCompleteListener.class));
        runnableCaptor.getValue().run();
        final ArgumentCaptor<Bundle> optionsCaptor = ArgumentCaptor.forClass(Bundle.class);
        verify(mMockContext).sendBroadcastAsUser(eq(mService.mTimeTickIntent), eq(UserHandle.ALL),
                isNull(), optionsCaptor.capture());
        verifyTimeTickBroadcastOptions(optionsCaptor.getValue());
    }

    private void verifyTimeTickBroadcastOptions(Bundle actualOptionsBundle) {
        final BroadcastOptions actualOptions = new BroadcastOptions(actualOptionsBundle);
        assertEquals(BroadcastOptions.DELIVERY_GROUP_POLICY_MOST_RECENT,
                actualOptions.getDeliveryGroupPolicy());
        assertEquals(BroadcastOptions.DEFERRAL_POLICY_UNTIL_ACTIVE,
                actualOptions.getDeferralPolicy());
    }

    @Test
    public void alarmCountKeyedOnCallingUid() {
        final int mockCreatorUid = 431412;
        setTestAlarm(ELAPSED_REALTIME, mNowElapsedTest + 5,
                getNewMockPendingIntent(mockCreatorUid, TEST_CALLING_PACKAGE));
        assertEquals(1, mService.mAlarmsPerUid.get(TEST_CALLING_UID));
        assertEquals(-1, mService.mAlarmsPerUid.get(mockCreatorUid, -1));
    }

    @Test
    public void alarmCountOnSetPi() {
        final int numAlarms = 103;
        for (int i = 1; i <= numAlarms; i++) {
            setTestAlarm(ALARM_TYPES[i % 4], mNowElapsedTest + i, getNewMockPendingIntent());
            assertEquals(i, mService.mAlarmsPerUid.get(TEST_CALLING_UID));
        }
    }

    @Test
    public void alarmCountOnSetListener() {
        final int numAlarms = 103;
        for (int i = 1; i <= numAlarms; i++) {
            setTestAlarmWithListener(ALARM_TYPES[i % 4], mNowElapsedTest + i,
                    getNewListener(() -> {}));
            assertEquals(i, mService.mAlarmsPerUid.get(TEST_CALLING_UID));
        }
    }

    @Test
    public void alarmCountOnExpirationPi() throws InterruptedException {
        final int numAlarms = 8; // This test is slow
        for (int i = 0; i < numAlarms; i++) {
            setTestAlarm(ELAPSED_REALTIME, mNowElapsedTest + i + 10, getNewMockPendingIntent());
        }
        int expired = 0;
        while (expired < numAlarms) {
            mNowElapsedTest = mTestTimer.getElapsed();
            mTestTimer.expire();
            expired++;
            assertEquals(numAlarms - expired, mService.mAlarmsPerUid.get(TEST_CALLING_UID, 0));
        }
    }

    @Test
    public void alarmCountOnExpirationListener() throws InterruptedException {
        final int numAlarms = 8; // This test is slow
        for (int i = 0; i < numAlarms; i++) {
            setTestAlarmWithListener(ELAPSED_REALTIME, mNowElapsedTest + i + 10,
                    getNewListener(() -> {}));
        }
        int expired = 0;
        while (expired < numAlarms) {
            mNowElapsedTest = mTestTimer.getElapsed();
            mTestTimer.expire();
            expired++;
            assertEquals(numAlarms - expired, mService.mAlarmsPerUid.get(TEST_CALLING_UID, 0));
        }
    }

    @Test
    public void alarmCountOnExceptionWhileSendingPi() throws Exception {
        final int numAlarms = 5; // This test is slow
        for (int i = 0; i < numAlarms; i++) {
            final PendingIntent pi = getNewMockPendingIntent();
            doThrow(PendingIntent.CanceledException.class).when(pi).send(eq(mMockContext), eq(0),
                    any(), any(), any(), any(), any());
            setTestAlarm(ELAPSED_REALTIME, mNowElapsedTest + i + 10, pi);
        }
        int expired = 0;
        while (expired < numAlarms) {
            mNowElapsedTest = mTestTimer.getElapsed();
            mTestTimer.expire();
            expired++;
            assertEquals(numAlarms - expired, mService.mAlarmsPerUid.get(TEST_CALLING_UID, 0));
        }
    }

    @Test
    public void alarmCountOnExceptionWhileCallingListener() throws Exception {
        final int numAlarms = 5; // This test is slow
        for (int i = 0; i < numAlarms; i++) {
            final IAlarmListener listener = getNewListener(() -> {
                throw new RuntimeException("For testing behavior on exception");
            });
            setTestAlarmWithListener(ELAPSED_REALTIME, mNowElapsedTest + i + 10, listener);
        }
        int expired = 0;
        while (expired < numAlarms) {
            mNowElapsedTest = mTestTimer.getElapsed();
            mTestTimer.expire();
            expired++;
            assertEquals(numAlarms - expired, mService.mAlarmsPerUid.get(TEST_CALLING_UID, 0));
        }
    }

    @Test
    public void alarmCountForRepeatingAlarms() throws Exception {
        final long interval = 1231;
        final long firstTrigger = mNowElapsedTest + 321;
        final PendingIntent pi = getNewMockPendingIntent();
        setRepeatingTestAlarm(ELAPSED_REALTIME, firstTrigger, interval, pi);
        assertEquals(1, mService.mAlarmsPerUid.get(TEST_CALLING_UID));

        for (int i = 0; i < 5; i++) {
            mNowElapsedTest = mTestTimer.getElapsed();
            mTestTimer.expire();
            assertEquals(1, mService.mAlarmsPerUid.get(TEST_CALLING_UID));
        }
        doThrow(PendingIntent.CanceledException.class).when(pi).send(eq(mMockContext), eq(0),
                any(), any(), any(), any(), any());
        mNowElapsedTest = mTestTimer.getElapsed();
        mTestTimer.expire();
        assertEquals(-1, mService.mAlarmsPerUid.get(TEST_CALLING_UID, -1));
    }

    @Test
    public void alarmCountOnUidRemoved() {
        final int numAlarms = 10;
        for (int i = 0; i < numAlarms; i++) {
            setTestAlarm(ELAPSED_REALTIME, mNowElapsedTest + i + 10, getNewMockPendingIntent());
        }
        assertEquals(numAlarms, mService.mAlarmsPerUid.get(TEST_CALLING_UID));
        mService.removeLocked(TEST_CALLING_UID,
                REMOVE_REASON_UNDEFINED);
        assertEquals(0, mService.mAlarmsPerUid.get(TEST_CALLING_UID, 0));
    }

    @Test
    public void alarmCountOnPackageRemoved() {
        final int numAlarms = 10;
        for (int i = 0; i < numAlarms; i++) {
            setTestAlarm(ELAPSED_REALTIME, mNowElapsedTest + i + 10, getNewMockPendingIntent());
        }
        assertEquals(numAlarms, mService.mAlarmsPerUid.get(TEST_CALLING_UID));
        mService.removeLocked(TEST_CALLING_PACKAGE, REMOVE_REASON_UNDEFINED);
        assertEquals(0, mService.mAlarmsPerUid.get(TEST_CALLING_UID, 0));
    }

    @Test
    public void alarmCountOnUserRemoved() {
        final int mockUserId = 15;
        final int numAlarms = 10;
        for (int i = 0; i < numAlarms; i++) {
            int mockUid = UserHandle.getUid(mockUserId, 1234 + i);
            setTestAlarm(ELAPSED_REALTIME, mNowElapsedTest + i + 10,
                    getNewMockPendingIntent(mockUid, TEST_CALLING_PACKAGE), 0, FLAG_STANDALONE,
                    mockUid);
        }
        assertEquals(numAlarms, mService.mAlarmsPerUid.size());
        mService.removeUserLocked(mockUserId);
        assertEquals(0, mService.mAlarmsPerUid.size());
    }

    @Test
    public void alarmCountOnAlarmRemoved() {
        final int numAlarms = 10;
        final PendingIntent[] pis = new PendingIntent[numAlarms];
        for (int i = 0; i < numAlarms; i++) {
            pis[i] = getNewMockPendingIntent();
            setTestAlarm(ELAPSED_REALTIME, mNowElapsedTest + i + 5, pis[i]);
        }
        assertEquals(numAlarms, mService.mAlarmsPerUid.get(TEST_CALLING_UID));
        for (int i = 0; i < numAlarms; i++) {
            mService.removeLocked(pis[i], null, REMOVE_REASON_UNDEFINED);
            assertEquals(numAlarms - i - 1, mService.mAlarmsPerUid.get(TEST_CALLING_UID, 0));
        }
    }

    @Test
    public void alarmTypes() throws Exception {
        final int[] typesToSet = ALARM_TYPES;
        final int[] typesExpected = {ELAPSED_REALTIME_WAKEUP, ELAPSED_REALTIME,
                ELAPSED_REALTIME_WAKEUP, ELAPSED_REALTIME};
        assertAlarmTypeConversion(typesToSet, typesExpected);
    }

    private void assertAlarmTypeConversion(int[] typesToSet, int[] typesExpected) throws Exception {
        for (int i = 0; i < typesToSet.length; i++) {
            setTestAlarm(typesToSet[i], 1234, getNewMockPendingIntent());
            final int typeSet = mTestTimer.getType();
            assertEquals("Alarm of type " + typesToSet[i] + " was set to type " + typeSet,
                    typesExpected[i], typeSet);
            mNowElapsedTest = mTestTimer.getElapsed();
            mTestTimer.expire();
        }
    }

    @Test
    public void alarmCountOnInvalidSet() {
        setTestAlarm(ELAPSED_REALTIME, mNowElapsedTest + 12345, null);
        assertEquals(-1, mService.mAlarmsPerUid.get(TEST_CALLING_UID, -1));
    }

    @Test
    public void alarmCountOnRemoveForCanceled() {
        final AlarmManagerInternal ami = LocalServices.getService(AlarmManagerInternal.class);
        final PendingIntent pi = getNewMockPendingIntent();
        setTestAlarm(ELAPSED_REALTIME, mNowElapsedTest + 12345, pi);
        assertEquals(1, mService.mAlarmsPerUid.get(TEST_CALLING_UID));
        ami.remove(pi);
        assertAndHandleMessageSync(REMOVE_FOR_CANCELED);
        assertEquals(0, mService.mAlarmsPerUid.get(TEST_CALLING_UID));
    }

    @Test
    public void alarmCountOnListenerBinderDied() {
        final int numAlarms = 10;
        final IAlarmListener[] listeners = new IAlarmListener[numAlarms];
        for (int i = 0; i < numAlarms; i++) {
            listeners[i] = getNewListener(() -> {});
            setTestAlarmWithListener(ELAPSED_REALTIME_WAKEUP, mNowElapsedTest + i, listeners[i]);
        }
        assertEquals(numAlarms, mService.mAlarmsPerUid.get(TEST_CALLING_UID));
        for (int i = 0; i < numAlarms; i++) {
            mService.mListenerDeathRecipient.binderDied(listeners[i].asBinder());
            assertEquals(numAlarms - i - 1, mService.mAlarmsPerUid.get(TEST_CALLING_UID));
        }
    }

    @Test
    public void nonWakeupAlarmsDeferred() throws Exception {
        final int numAlarms = 10;
        final PendingIntent[] pis = new PendingIntent[numAlarms];
        for (int i = 0; i < numAlarms; i++) {
            pis[i] = getNewMockPendingIntent();
            setTestAlarm(ELAPSED_REALTIME, mNowElapsedTest + i + 5, pis[i]);
        }
        doReturn(true).when(mService).checkAllowNonWakeupDelayLocked(anyLong());
        // Advance time past all expirations.
        mNowElapsedTest += numAlarms + 5;
        mTestTimer.expire();
        assertEquals(numAlarms, mService.mPendingNonWakeupAlarms.size());

        // These alarms should be sent on interactive state change to true
        mService.interactiveStateChangedLocked(false);
        mService.interactiveStateChangedLocked(true);

        for (int i = 0; i < numAlarms; i++) {
            verify(pis[i]).send(eq(mMockContext), eq(0), any(Intent.class), any(),
                    any(Handler.class), isNull(), any());
        }
    }

    /**
     * This tests that all non wakeup alarms are sent even when the mPendingNonWakeupAlarms gets
     * modified before the send is complete. This avoids bugs like b/175701084.
     */
    @Test
    public void allNonWakeupAlarmsSentAtomically() throws Exception {
        final int numAlarms = 5;
        final AtomicInteger alarmsFired = new AtomicInteger(0);
        for (int i = 0; i < numAlarms; i++) {
            final IAlarmListener listener = getNewListener(() -> {
                alarmsFired.incrementAndGet();
                mService.mPendingNonWakeupAlarms.clear();
            });
            setTestAlarmWithListener(ELAPSED_REALTIME, mNowElapsedTest + i + 5, listener);
        }
        doReturn(true).when(mService).checkAllowNonWakeupDelayLocked(anyLong());
        // Advance time past all expirations.
        mNowElapsedTest += numAlarms + 5;
        mTestTimer.expire();
        assertEquals(numAlarms, mService.mPendingNonWakeupAlarms.size());

        // All of these alarms should be sent on interactive state change to true
        mService.interactiveStateChangedLocked(false);
        mService.interactiveStateChangedLocked(true);

        assertEquals(numAlarms, alarmsFired.get());
        assertEquals(0, mService.mPendingNonWakeupAlarms.size());
    }

    @Test
    public void alarmCountOnPendingNonWakeupAlarmsRemoved() throws Exception {
        final int numAlarms = 10;
        final PendingIntent[] pis = new PendingIntent[numAlarms];
        for (int i = 0; i < numAlarms; i++) {
            pis[i] = getNewMockPendingIntent();
            setTestAlarm(ELAPSED_REALTIME, mNowElapsedTest + i + 5, pis[i]);
        }
        doReturn(true).when(mService).checkAllowNonWakeupDelayLocked(anyLong());
        // Advance time past all expirations.
        mNowElapsedTest += numAlarms + 5;
        mTestTimer.expire();
        assertEquals(numAlarms, mService.mPendingNonWakeupAlarms.size());
        for (int i = 0; i < numAlarms; i++) {
            mService.removeLocked(pis[i], null, REMOVE_REASON_UNDEFINED);
            assertEquals(numAlarms - i - 1, mService.mAlarmsPerUid.get(TEST_CALLING_UID, 0));
        }
    }

    @Test
    public void singleIdleUntil() {
        setDeviceConfigLong(KEY_MAX_DEVICE_IDLE_FUZZ, 0);

        final PendingIntent idleUntilPi6 = getNewMockPendingIntent();
        setIdleUntilAlarm(ELAPSED_REALTIME_WAKEUP, mNowElapsedTest + 6, idleUntilPi6);

        assertTrue(mService.mPendingIdleUntil.matches(idleUntilPi6, null));
        assertEquals(mNowElapsedTest + 6, mTestTimer.getElapsed());
        assertEquals(mNowElapsedTest + 6, mService.mPendingIdleUntil.getWhenElapsed());

        final PendingIntent idleUntilPi2 = getNewMockPendingIntent();
        setIdleUntilAlarm(ELAPSED_REALTIME_WAKEUP, mNowElapsedTest + 2, idleUntilPi2);

        // The same mPendingIdleUntil should get updated, even with a different PendingIntent.
        assertTrue(mService.mPendingIdleUntil.matches(idleUntilPi2, null));
        assertEquals(mNowElapsedTest + 2, mTestTimer.getElapsed());
        assertEquals(1, mService.mAlarmStore.size());

        final PendingIntent idleUntilPi10 = getNewMockPendingIntent();
        setIdleUntilAlarm(ELAPSED_REALTIME_WAKEUP, mNowElapsedTest + 10, idleUntilPi10);

        // The same thing should happen even when the new alarm is in farther in the future.
        assertTrue(mService.mPendingIdleUntil.matches(idleUntilPi10, null));
        assertEquals(mNowElapsedTest + 10, mTestTimer.getElapsed());
        assertEquals(1, mService.mAlarmStore.size());
    }

    @Test
    public void nextWakeFromIdle() throws Exception {
        assertNull(mService.mNextWakeFromIdle);

        final PendingIntent wakeFromIdle6 = getNewMockPendingIntent();
        final long trigger6 = mNowElapsedTest + 6;
        setWakeFromIdle(ELAPSED_REALTIME_WAKEUP, trigger6, wakeFromIdle6);

        assertTrue(mService.mNextWakeFromIdle.matches(wakeFromIdle6, null));
        assertEquals(trigger6, mService.mNextWakeFromIdle.getWhenElapsed());
        assertEquals(trigger6, mTestTimer.getElapsed());

        final PendingIntent wakeFromIdle10 = getNewMockPendingIntent();
        final long trigger10 = mNowElapsedTest + 10;
        setWakeFromIdle(ELAPSED_REALTIME_WAKEUP, trigger10, wakeFromIdle10);

        // mNextWakeFromIdle should not get updated.
        assertTrue(mService.mNextWakeFromIdle.matches(wakeFromIdle6, null));
        assertEquals(trigger6, mTestTimer.getElapsed());
        assertEquals(trigger6, mService.mNextWakeFromIdle.getWhenElapsed());

        final PendingIntent wakeFromIdle3 = getNewMockPendingIntent();
        final long trigger3 = mNowElapsedTest + 3;
        setWakeFromIdle(ELAPSED_REALTIME_WAKEUP, trigger3, wakeFromIdle3);

        // mNextWakeFromIdle should always reflect the next earliest wake_from_idle alarm.
        assertTrue(mService.mNextWakeFromIdle.matches(wakeFromIdle3, null));
        assertEquals(trigger3, mTestTimer.getElapsed());
        assertEquals(trigger3, mService.mNextWakeFromIdle.getWhenElapsed());

        mNowElapsedTest = trigger3;
        mTestTimer.expire();

        assertTrue(mService.mNextWakeFromIdle.matches(wakeFromIdle6, null));
        assertEquals(trigger6, mTestTimer.getElapsed());
        assertEquals(trigger6, mService.mNextWakeFromIdle.getWhenElapsed());

        mService.removeLocked(wakeFromIdle6, null, REMOVE_REASON_UNDEFINED);

        assertTrue(mService.mNextWakeFromIdle.matches(wakeFromIdle10, null));
        assertEquals(trigger10, mTestTimer.getElapsed());
        assertEquals(trigger10, mService.mNextWakeFromIdle.getWhenElapsed());

        mService.removeLocked(wakeFromIdle10, null, REMOVE_REASON_UNDEFINED);
        assertNull(mService.mNextWakeFromIdle);
    }

    private static void assertInRange(String message, long minIncl, long maxIncl, long val) {
        assertTrue(message, val >= minIncl && val <= maxIncl);
    }

    @Test
    public void idleUntilFuzzedBeforeWakeFromIdle() {
        final long minFuzz = 6;
        final long maxFuzz = 17;
        setDeviceConfigLong(KEY_MIN_DEVICE_IDLE_FUZZ, minFuzz);
        setDeviceConfigLong(KEY_MAX_DEVICE_IDLE_FUZZ, maxFuzz);

        mNowElapsedTest = 119; // Arbitrary, just to ensure we are not testing on 0.

        final PendingIntent idleUntilPi = getNewMockPendingIntent();
        final long requestedIdleUntil = mNowElapsedTest + 12;
        setIdleUntilAlarm(ELAPSED_REALTIME_WAKEUP, requestedIdleUntil, idleUntilPi);

        assertEquals(requestedIdleUntil, mService.mPendingIdleUntil.getWhenElapsed());

        final PendingIntent wakeFromIdle5 = getNewMockPendingIntent();
        setWakeFromIdle(ELAPSED_REALTIME_WAKEUP, mNowElapsedTest + 5, wakeFromIdle5);
        // Anything before now, gets snapped to now. It is not necessary for it to fire
        // immediately, just how it is implemented today for simplicity.
        assertEquals(mNowElapsedTest, mService.mPendingIdleUntil.getWhenElapsed());

        final PendingIntent wakeFromIdle8 = getNewMockPendingIntent();
        setWakeFromIdle(ELAPSED_REALTIME_WAKEUP, mNowElapsedTest + 8, wakeFromIdle8);
        // Next wake from idle is still the same.
        assertEquals(mNowElapsedTest, mService.mPendingIdleUntil.getWhenElapsed());

        final PendingIntent wakeFromIdle19 = getNewMockPendingIntent();
        setWakeFromIdle(ELAPSED_REALTIME_WAKEUP, mNowElapsedTest + 19, wakeFromIdle19);
        // Next wake from idle is still the same.
        assertEquals(mNowElapsedTest, mService.mPendingIdleUntil.getWhenElapsed());

        mService.removeLocked(wakeFromIdle5, null, REMOVE_REASON_UNDEFINED);
        // Next wake from idle is at now + 8.
        long min = mNowElapsedTest;
        long max = mNowElapsedTest + 8 - minFuzz;
        assertInRange("Idle until alarm time not in expected range [" + min + ", " + max + "]",
                min, max, mService.mPendingIdleUntil.getWhenElapsed());

        mService.removeLocked(wakeFromIdle8, null, REMOVE_REASON_UNDEFINED);
        // Next wake from idle is at now + 19, which is > minFuzz distance from
        // the requested idle until time: now + 12.
        assertEquals(requestedIdleUntil, mService.mPendingIdleUntil.getWhenElapsed());

        mService.removeLocked(idleUntilPi, null, REMOVE_REASON_UNDEFINED);
        assertNull(mService.mPendingIdleUntil);

        setIdleUntilAlarm(ELAPSED_REALTIME_WAKEUP, mNowElapsedTest + 21, idleUntilPi);
        // Next wake from idle is at now + 19, which means this alarm should get pulled back.
        min = mNowElapsedTest + 19 - maxFuzz;
        max = mNowElapsedTest + 19 - minFuzz;
        assertInRange("Idle until alarm time not in expected range [" + min + ", " + max + "]",
                min, max, mService.mPendingIdleUntil.getWhenElapsed());
    }

    @Test
    public void allowWhileIdleAlarmsWhileDeviceIdle() throws Exception {
        setDeviceConfigLong(KEY_MAX_DEVICE_IDLE_FUZZ, 0);

        setIdleUntilAlarm(ELAPSED_REALTIME_WAKEUP, mNowElapsedTest + mAllowWhileIdleWindow + 1000,
                getNewMockPendingIntent());
        assertNotNull(mService.mPendingIdleUntil);

        final int quota = mService.mConstants.ALLOW_WHILE_IDLE_QUOTA;
        final long firstTrigger = mNowElapsedTest + 10;
        for (int i = 0; i < quota; i++) {
            setAllowWhileIdleAlarm(ELAPSED_REALTIME_WAKEUP, firstTrigger + i,
                    getNewMockPendingIntent(), false, false);
            mNowElapsedTest = mTestTimer.getElapsed();
            mTestTimer.expire();
        }
        // This one should get deferred on set.
        setAllowWhileIdleAlarm(ELAPSED_REALTIME_WAKEUP, firstTrigger + quota,
                getNewMockPendingIntent(), false, false);
        final long expectedNextTrigger = firstTrigger + mAllowWhileIdleWindow;
        assertEquals("Incorrect trigger when no quota left", expectedNextTrigger,
                mTestTimer.getElapsed());

        // Bring the idle until alarm back.
        setIdleUntilAlarm(ELAPSED_REALTIME_WAKEUP, expectedNextTrigger - 50,
                getNewMockPendingIntent());
        assertEquals(expectedNextTrigger - 50, mService.mPendingIdleUntil.getWhenElapsed());
        assertEquals(expectedNextTrigger - 50, mTestTimer.getElapsed());
    }

    @Test
    public void allowWhileIdleCompatAlarmsWhileDeviceIdle() throws Exception {
        setDeviceConfigLong(KEY_MAX_DEVICE_IDLE_FUZZ, 0);

        final long window = mService.mConstants.ALLOW_WHILE_IDLE_COMPAT_WINDOW;
        setIdleUntilAlarm(ELAPSED_REALTIME_WAKEUP, mNowElapsedTest + window + 1000,
                getNewMockPendingIntent());
        assertNotNull(mService.mPendingIdleUntil);

        final int quota = mService.mConstants.ALLOW_WHILE_IDLE_COMPAT_QUOTA;
        final long firstTrigger = mNowElapsedTest + 10;
        for (int i = 0; i < quota; i++) {
            setAllowWhileIdleAlarm(ELAPSED_REALTIME_WAKEUP, firstTrigger + i,
                    getNewMockPendingIntent(), false, true);
            mNowElapsedTest = mTestTimer.getElapsed();
            mTestTimer.expire();
        }
        // This one should get deferred on set.
        setAllowWhileIdleAlarm(ELAPSED_REALTIME_WAKEUP, firstTrigger + quota,
                getNewMockPendingIntent(), false, true);
        final long expectedNextTrigger = firstTrigger + window;
        assertEquals("Incorrect trigger when no quota left", expectedNextTrigger,
                mTestTimer.getElapsed());

        // Bring the idle until alarm back.
        setIdleUntilAlarm(ELAPSED_REALTIME_WAKEUP, expectedNextTrigger - 50,
                getNewMockPendingIntent());
        assertEquals(expectedNextTrigger - 50, mService.mPendingIdleUntil.getWhenElapsed());
        assertEquals(expectedNextTrigger - 50, mTestTimer.getElapsed());
    }

    @Test
    public void allowWhileIdleCompatHistorySeparate() throws Exception {
        when(mAppStateTracker.areAlarmsRestrictedByBatterySaver(TEST_CALLING_UID,
                TEST_CALLING_PACKAGE)).thenReturn(true);
        when(mAppStateTracker.isForceAllAppsStandbyEnabled()).thenReturn(true);

        final int fullQuota = mService.mConstants.ALLOW_WHILE_IDLE_QUOTA;
        final int compatQuota = mService.mConstants.ALLOW_WHILE_IDLE_COMPAT_QUOTA;

        final long fullWindow = mAllowWhileIdleWindow;
        final long compatWindow = mService.mConstants.ALLOW_WHILE_IDLE_COMPAT_WINDOW;

        final long firstFullTrigger = mNowElapsedTest + 10;
        for (int i = 0; i < fullQuota; i++) {
            setAllowWhileIdleAlarm(ELAPSED_REALTIME_WAKEUP, firstFullTrigger + i,
                    getNewMockPendingIntent(), false, false);
            mNowElapsedTest = mTestTimer.getElapsed();
            mTestTimer.expire();
        }
        // This one should get deferred on set, as full quota is not available.
        setAllowWhileIdleAlarm(ELAPSED_REALTIME_WAKEUP, firstFullTrigger + fullQuota,
                getNewMockPendingIntent(), false, false);
        final long expectedNextFullTrigger = firstFullTrigger + fullWindow;
        assertEquals("Incorrect trigger when no quota left", expectedNextFullTrigger,
                mTestTimer.getElapsed());
        mService.removeLocked(TEST_CALLING_UID, REMOVE_REASON_UNDEFINED);

        // The following should be allowed, as compat quota should be free.
        for (int i = 0; i < compatQuota; i++) {
            final long trigger = mNowElapsedTest + 1;
            setAllowWhileIdleAlarm(ELAPSED_REALTIME_WAKEUP, trigger, getNewMockPendingIntent(),
                    false, true);
            assertEquals(trigger, mTestTimer.getElapsed());
            mNowElapsedTest = mTestTimer.getElapsed();
            mTestTimer.expire();
        }

        // Refresh the state
        mService.removeLocked(TEST_CALLING_UID, REMOVE_REASON_UNDEFINED);
        mService.mAllowWhileIdleHistory.removeForPackage(TEST_CALLING_PACKAGE, TEST_CALLING_USER);
        mService.mAllowWhileIdleCompatHistory.removeForPackage(TEST_CALLING_PACKAGE,
                TEST_CALLING_USER);

        // Now test with flipped order

        final long firstCompatTrigger = mNowElapsedTest + 10;
        for (int i = 0; i < compatQuota; i++) {
            setAllowWhileIdleAlarm(ELAPSED_REALTIME_WAKEUP, firstCompatTrigger + i,
                    getNewMockPendingIntent(), false, true);
            mNowElapsedTest = mTestTimer.getElapsed();
            mTestTimer.expire();
        }
        // This one should get deferred on set, as full quota is not available.
        setAllowWhileIdleAlarm(ELAPSED_REALTIME_WAKEUP, firstCompatTrigger + compatQuota,
                getNewMockPendingIntent(), false, true);
        final long expectedNextCompatTrigger = firstCompatTrigger + compatWindow;
        assertEquals("Incorrect trigger when no quota left", expectedNextCompatTrigger,
                mTestTimer.getElapsed());
        mService.removeLocked(TEST_CALLING_UID, REMOVE_REASON_UNDEFINED);

        // The following should be allowed, as full quota should be free.
        for (int i = 0; i < fullQuota; i++) {
            final long trigger = mNowElapsedTest + 1;
            setAllowWhileIdleAlarm(ELAPSED_REALTIME_WAKEUP, trigger, getNewMockPendingIntent(),
                    false, false);
            assertEquals(trigger, mTestTimer.getElapsed());
            mNowElapsedTest = mTestTimer.getElapsed();
            mTestTimer.expire();
        }
    }

    @Test
    public void allowWhileIdleUnrestricted() throws Exception {
        setDeviceConfigLong(KEY_MAX_DEVICE_IDLE_FUZZ, 0);

        // Both battery saver and doze are on.
        setIdleUntilAlarm(ELAPSED_REALTIME_WAKEUP, mNowElapsedTest + 1000,
                getNewMockPendingIntent());
        assertNotNull(mService.mPendingIdleUntil);

        when(mAppStateTracker.areAlarmsRestrictedByBatterySaver(TEST_CALLING_UID,
                TEST_CALLING_PACKAGE)).thenReturn(true);

        final int numAlarms = mService.mConstants.ALLOW_WHILE_IDLE_QUOTA + 100;
        final long firstTrigger = mNowElapsedTest + 10;
        for (int i = 0; i < numAlarms; i++) {
            setAllowWhileIdleAlarm(ELAPSED_REALTIME_WAKEUP, firstTrigger + i,
                    getNewMockPendingIntent(), true, false);
        }
        // All of them should fire as expected.
        for (int i = 0; i < numAlarms; i++) {
            mNowElapsedTest = mTestTimer.getElapsed();
            assertEquals("Incorrect trigger at i=" + i, firstTrigger + i, mNowElapsedTest);
            mTestTimer.expire();
        }
    }

    @Test
    public void deviceIdleDeferralOnSet() throws Exception {
        setDeviceConfigLong(KEY_MAX_DEVICE_IDLE_FUZZ, 0);

        final long deviceIdleUntil = mNowElapsedTest + 1234;
        setIdleUntilAlarm(ELAPSED_REALTIME_WAKEUP, deviceIdleUntil, getNewMockPendingIntent());

        assertEquals(deviceIdleUntil, mTestTimer.getElapsed());

        final int numAlarms = 10;
        final PendingIntent[] pis = new PendingIntent[numAlarms];
        for (int i = 0; i < numAlarms; i++) {
            setTestAlarm(ELAPSED_REALTIME_WAKEUP, mNowElapsedTest + i + 1,
                    pis[i] = getNewMockPendingIntent());
            assertEquals(deviceIdleUntil, mTestTimer.getElapsed());
        }

        mNowElapsedTest = mTestTimer.getElapsed();
        mTestTimer.expire();
        for (int i = 0; i < numAlarms; i++) {
            verify(pis[i]).send(eq(mMockContext), eq(0), any(Intent.class), any(),
                    any(Handler.class), isNull(), any());
        }
    }

    @Test
    public void deviceIdleStateChanges() throws Exception {
        setDeviceConfigLong(KEY_MAX_DEVICE_IDLE_FUZZ, 0);

        final int numAlarms = 10;
        final PendingIntent[] pis = new PendingIntent[numAlarms];
        for (int i = 0; i < numAlarms; i++) {
            setTestAlarm(ELAPSED_REALTIME_WAKEUP, mNowElapsedTest + i + 1,
                    pis[i] = getNewMockPendingIntent());
            assertEquals(mNowElapsedTest + 1, mTestTimer.getElapsed());
        }

        final PendingIntent idleUntil = getNewMockPendingIntent();
        setIdleUntilAlarm(ELAPSED_REALTIME_WAKEUP, mNowElapsedTest + 1234, idleUntil);

        assertEquals(mNowElapsedTest + 1234, mTestTimer.getElapsed());

        mNowElapsedTest += 5;
        mTestTimer.expire();
        // Nothing should happen.
        verify(pis[0], never()).send(eq(mMockContext), eq(0), any(Intent.class), any(),
                any(Handler.class), isNull(), any());

        mService.removeLocked(idleUntil, null, REMOVE_REASON_UNDEFINED);
        mTestTimer.expire();
        // Now, the first 5 alarms (upto i = 4) should expire.
        for (int i = 0; i < 5; i++) {
            verify(pis[i]).send(eq(mMockContext), eq(0), any(Intent.class), any(),
                    any(Handler.class), isNull(), any());
        }
        // Rest should be restored, so the timer should reflect the next alarm.
        assertEquals(mNowElapsedTest + 1, mTestTimer.getElapsed());
    }

    @Test
    public void batterySaverThrottling() {
        when(mAppStateTracker.areAlarmsRestrictedByBatterySaver(TEST_CALLING_UID,
                TEST_CALLING_PACKAGE)).thenReturn(true);

        final PendingIntent alarmPi = getNewMockPendingIntent();
        setTestAlarm(ELAPSED_REALTIME_WAKEUP, mNowElapsedTest + 7, alarmPi);
        assertEquals(mNowElapsedTest + INDEFINITE_DELAY, mTestTimer.getElapsed());

        when(mAppStateTracker.areAlarmsRestrictedByBatterySaver(TEST_CALLING_UID,
                TEST_CALLING_PACKAGE)).thenReturn(false);
        mListener.updateAllAlarms();
        assertEquals(mNowElapsedTest + 7, mTestTimer.getElapsed());

        when(mAppStateTracker.areAlarmsRestrictedByBatterySaver(TEST_CALLING_UID,
                TEST_CALLING_PACKAGE)).thenReturn(true);
        mListener.updateAlarmsForUid(TEST_CALLING_UID);
        assertEquals(mNowElapsedTest + INDEFINITE_DELAY, mTestTimer.getElapsed());
    }

    @Test
    public void allowWhileIdleAlarmsInBatterySaver() throws Exception {
        when(mAppStateTracker.areAlarmsRestrictedByBatterySaver(TEST_CALLING_UID,
                TEST_CALLING_PACKAGE)).thenReturn(true);
        when(mAppStateTracker.isForceAllAppsStandbyEnabled()).thenReturn(true);
        final int quota = mService.mConstants.ALLOW_WHILE_IDLE_QUOTA;

        testQuotasDeferralOnSet(trigger -> setAllowWhileIdleAlarm(ELAPSED_REALTIME_WAKEUP, trigger,
                getNewMockPendingIntent(), false, false), quota, mAllowWhileIdleWindow);

        // Refresh the state
        mService.removeLocked(TEST_CALLING_UID, REMOVE_REASON_UNDEFINED);
        mService.mAllowWhileIdleHistory.removeForPackage(TEST_CALLING_PACKAGE, TEST_CALLING_USER);

        testQuotasDeferralOnExpiration(trigger -> setAllowWhileIdleAlarm(ELAPSED_REALTIME_WAKEUP,
                trigger, getNewMockPendingIntent(), false, false), quota, mAllowWhileIdleWindow);

        // Refresh the state
        mService.removeLocked(TEST_CALLING_UID, REMOVE_REASON_UNDEFINED);
        mService.mAllowWhileIdleHistory.removeForPackage(TEST_CALLING_PACKAGE, TEST_CALLING_USER);

        testQuotasNoDeferral(trigger -> setAllowWhileIdleAlarm(ELAPSED_REALTIME_WAKEUP, trigger,
                getNewMockPendingIntent(), false, false), quota, mAllowWhileIdleWindow);
    }

    @Test
    public void allowWhileIdleCompatAlarmsInBatterySaver() throws Exception {
        when(mAppStateTracker.areAlarmsRestrictedByBatterySaver(TEST_CALLING_UID,
                TEST_CALLING_PACKAGE)).thenReturn(true);
        when(mAppStateTracker.isForceAllAppsStandbyEnabled()).thenReturn(true);

        final int quota = mService.mConstants.ALLOW_WHILE_IDLE_COMPAT_QUOTA;
        final long window = mService.mConstants.ALLOW_WHILE_IDLE_COMPAT_WINDOW;

        testQuotasDeferralOnSet(trigger -> setAllowWhileIdleAlarm(ELAPSED_REALTIME_WAKEUP, trigger,
                getNewMockPendingIntent(), false, true), quota, window);

        // Refresh the state
        mService.removeLocked(TEST_CALLING_UID, REMOVE_REASON_UNDEFINED);
        mService.mAllowWhileIdleCompatHistory.removeForPackage(TEST_CALLING_PACKAGE,
                TEST_CALLING_USER);

        testQuotasDeferralOnExpiration(trigger -> setAllowWhileIdleAlarm(ELAPSED_REALTIME_WAKEUP,
                trigger, getNewMockPendingIntent(), false, true), quota, window);

        // Refresh the state
        mService.removeLocked(TEST_CALLING_UID, REMOVE_REASON_UNDEFINED);
        mService.mAllowWhileIdleCompatHistory.removeForPackage(TEST_CALLING_PACKAGE,
                TEST_CALLING_USER);

        testQuotasNoDeferral(trigger -> setAllowWhileIdleAlarm(ELAPSED_REALTIME_WAKEUP, trigger,
                getNewMockPendingIntent(), false, true), quota, window);
    }

    @Test
    public void prioritizedAlarmsInBatterySaver() throws Exception {
        when(mAppStateTracker.areAlarmsRestrictedByBatterySaver(TEST_CALLING_UID,
                TEST_CALLING_PACKAGE)).thenReturn(true);
        when(mAppStateTracker.isForceAllAppsStandbyEnabled()).thenReturn(true);
        final long minDelay = 5;
        setDeviceConfigLong(KEY_PRIORITY_ALARM_DELAY, minDelay);

        final long firstTrigger = mNowElapsedTest + 4;
        final AtomicInteger alarmsFired = new AtomicInteger(0);
        final int numAlarms = 10;
        for (int i = 0; i < numAlarms; i++) {
            setPrioritizedAlarm(ELAPSED_REALTIME_WAKEUP, firstTrigger + i,
                    0, new IAlarmListener.Stub() {
                        @Override
                        public void doAlarm(IAlarmCompleteListener callback)
                                throws RemoteException {
                            alarmsFired.incrementAndGet();
                        }
                    });
        }
        assertEquals(firstTrigger, mTestTimer.getElapsed());
        mNowElapsedTest = firstTrigger;
        mTestTimer.expire();
        while (alarmsFired.get() < numAlarms) {
            assertEquals(mNowElapsedTest + minDelay, mTestTimer.getElapsed());
            mNowElapsedTest = mTestTimer.getElapsed();
            mTestTimer.expire();
        }
        assertEquals(numAlarms, alarmsFired.get());
    }

    @Test
    public void prioritizedAlarmsInDeviceIdle() throws Exception {
        setDeviceConfigLong(KEY_MAX_DEVICE_IDLE_FUZZ, 0);

        final long minDelay = 5;
        setDeviceConfigLong(KEY_PRIORITY_ALARM_DELAY, minDelay);

        final long idleUntil = mNowElapsedTest + 1000;
        setIdleUntilAlarm(ELAPSED_REALTIME_WAKEUP, idleUntil, getNewMockPendingIntent());
        assertNotNull(mService.mPendingIdleUntil);

        final long firstTrigger = mNowElapsedTest + 4;
        final AtomicInteger alarmsFired = new AtomicInteger(0);
        final int numAlarms = 10;
        for (int i = 0; i < numAlarms; i++) {
            setPrioritizedAlarm(ELAPSED_REALTIME_WAKEUP, firstTrigger + i,
                    0, new IAlarmListener.Stub() {
                        @Override
                        public void doAlarm(IAlarmCompleteListener callback)
                                throws RemoteException {
                            alarmsFired.incrementAndGet();
                        }
                    });
        }
        assertEquals(firstTrigger, mTestTimer.getElapsed());
        mNowElapsedTest = firstTrigger;
        mTestTimer.expire();
        while (alarmsFired.get() < numAlarms) {
            assertEquals(mNowElapsedTest + minDelay, mTestTimer.getElapsed());
            mNowElapsedTest = mTestTimer.getElapsed();
            mTestTimer.expire();
        }
        assertEquals(numAlarms, alarmsFired.get());

        setPrioritizedAlarm(ELAPSED_REALTIME_WAKEUP, idleUntil - 3, 0, new IAlarmListener.Stub() {
            @Override
            public void doAlarm(IAlarmCompleteListener callback) throws RemoteException {
            }
        });
        setPrioritizedAlarm(ELAPSED_REALTIME_WAKEUP, idleUntil - 2, 0, new IAlarmListener.Stub() {
            @Override
            public void doAlarm(IAlarmCompleteListener callback) throws RemoteException {
            }
        });
        assertEquals(idleUntil - 3, mTestTimer.getElapsed());
        mNowElapsedTest = mTestTimer.getElapsed();
        mTestTimer.expire();

        assertEquals(idleUntil, mTestTimer.getElapsed());
    }

    @Test
    public void dispatchOrder() throws Exception {
        setDeviceConfigLong(KEY_MAX_DEVICE_IDLE_FUZZ, 0);

        final long deviceIdleUntil = mNowElapsedTest + 1234;
        final PendingIntent idleUntilPi = getNewMockPendingIntent();
        setIdleUntilAlarm(ELAPSED_REALTIME_WAKEUP, deviceIdleUntil, idleUntilPi);

        assertEquals(deviceIdleUntil, mTestTimer.getElapsed());

        final PendingIntent pi4System = getNewMockPendingIntent(Process.SYSTEM_UID, "android");
        final PendingIntent pi9System = getNewMockPendingIntent(Process.SYSTEM_UID, "android");

        final PendingIntent pi5wakeupPackage = getNewMockPendingIntent();
        final IAlarmListener listener2WakeupPackage = getNewListener(() -> {});
        final PendingIntent pi4wakeupPackage = getNewMockPendingIntent(41, "test.wakeup.package2");
        final PendingIntent pi19wakeupPackage = getNewMockPendingIntent(41, "test.wakeup.package2");

        final PendingIntent pi2nonWakeup = getNewMockPendingIntent(57, "test.nonwakeup.package1");
        final PendingIntent pi8nonWakeup = getNewMockPendingIntent(59, "test.nonwakeup.package2");
        final PendingIntent pi11nonWakeup = getNewMockPendingIntent(51, "test.nonwakeup.package3");

        // Alarms from the system
        setTestAlarm(ELAPSED_REALTIME, mNowElapsedTest + 4, pi4System);
        setTestAlarm(ELAPSED_REALTIME, mNowElapsedTest + 9, pi9System);

        // Alarms from TEST_CALLING_PACKAGE, TEST_CALLING_UID, having one wakeup alarm
        setTestAlarmWithListener(ELAPSED_REALTIME, mNowElapsedTest + 20, mService.mTimeTickTrigger);
        setTestAlarm(ELAPSED_REALTIME_WAKEUP, mNowElapsedTest + 5, pi5wakeupPackage);
        setTestAlarmWithListener(ELAPSED_REALTIME, mNowElapsedTest + 2, listener2WakeupPackage);

        // Alarms from 41, "test.wakeup.package2", having one wakeup alarm
        setTestAlarm(ELAPSED_REALTIME_WAKEUP, mNowElapsedTest + 4, pi4wakeupPackage);
        setTestAlarm(ELAPSED_REALTIME, mNowElapsedTest + 19, pi19wakeupPackage);

        // Alarms from other packages having no wakeup alarms
        setTestAlarm(ELAPSED_REALTIME, mNowElapsedTest + 8, pi8nonWakeup);
        setTestAlarm(ELAPSED_REALTIME, mNowElapsedTest + 11, pi11nonWakeup);
        setTestAlarm(ELAPSED_REALTIME, mNowElapsedTest + 2, pi2nonWakeup);

        mNowElapsedTest = deviceIdleUntil;
        mTestTimer.expire();

        // IdleUntil should always go out first. The others are divided into three classes:
        // System alarms | Alarms of packages with any wakeup alarms | Others.
        // Within each class, time_tick should go first if present, others should be ordered by
        // requested delivery time.
        final PendingIntent[] expectedPiOrder = new PendingIntent[]{
                idleUntilPi, pi4System, pi9System, null, null, pi4wakeupPackage, pi5wakeupPackage,
                pi19wakeupPackage, pi2nonWakeup, pi8nonWakeup, pi11nonWakeup};

        final IAlarmListener[] expectedListenerOrder = new IAlarmListener[expectedPiOrder.length];
        expectedListenerOrder[3] = mService.mTimeTickTrigger;
        expectedListenerOrder[4] = listener2WakeupPackage;

        ArgumentCaptor<ArrayList<Alarm>> listCaptor = ArgumentCaptor.forClass(ArrayList.class);
        verify(mService).deliverAlarmsLocked(listCaptor.capture(), anyLong());
        final ArrayList<Alarm> deliveryList = listCaptor.getValue();

        assertEquals(expectedPiOrder.length, deliveryList.size());
        for (int i = 0; i < expectedPiOrder.length; i++) {
            assertTrue("Unexpected alarm: " + deliveryList.get(i) + " at pos: " + i,
                    deliveryList.get(i).matches(expectedPiOrder[i], expectedListenerOrder[i]));
        }
    }

    private void mockChangeEnabled(long changeId, boolean enabled) {
        doReturn(enabled).when(() -> CompatChanges.isChangeEnabled(eq(changeId), anyString(),
                any(UserHandle.class)));
        doReturn(enabled).when(() -> CompatChanges.isChangeEnabled(eq(changeId), anyInt()));
    }

    @Test
    public void hasScheduleExactAlarmBinderCall() throws RemoteException {
        mockChangeEnabled(AlarmManager.REQUIRE_EXACT_ALARM_PERMISSION, true);
        mockChangeEnabled(AlarmManager.SCHEDULE_EXACT_ALARM_DENIED_BY_DEFAULT, true);

        mockScheduleExactAlarmState(true);
        assertTrue(mBinder.hasScheduleExactAlarm(TEST_CALLING_PACKAGE, TEST_CALLING_USER));

        mockScheduleExactAlarmState(false);
        assertFalse(mBinder.hasScheduleExactAlarm(TEST_CALLING_PACKAGE, TEST_CALLING_USER));
    }

    @Test
    public void hasScheduleExactAlarmBinderCallPreT() throws RemoteException {
        mockChangeEnabled(AlarmManager.REQUIRE_EXACT_ALARM_PERMISSION, true);

        mockScheduleExactAlarmStatePreT(true, MODE_DEFAULT);
        assertTrue(mBinder.hasScheduleExactAlarm(TEST_CALLING_PACKAGE, TEST_CALLING_USER));

        mockScheduleExactAlarmStatePreT(true, MODE_ALLOWED);
        assertTrue(mBinder.hasScheduleExactAlarm(TEST_CALLING_PACKAGE, TEST_CALLING_USER));

        mockScheduleExactAlarmStatePreT(true, MODE_ERRORED);
        assertFalse(mBinder.hasScheduleExactAlarm(TEST_CALLING_PACKAGE, TEST_CALLING_USER));

        mockScheduleExactAlarmStatePreT(true, MODE_IGNORED);
        assertFalse(mBinder.hasScheduleExactAlarm(TEST_CALLING_PACKAGE, TEST_CALLING_USER));
    }

    @Test
    public void hasScheduleExactAlarmBinderCallNotDeclaredPreT() throws RemoteException {
        mockChangeEnabled(AlarmManager.REQUIRE_EXACT_ALARM_PERMISSION, true);

        mockScheduleExactAlarmStatePreT(false, MODE_DEFAULT);
        assertFalse(mBinder.hasScheduleExactAlarm(TEST_CALLING_PACKAGE, TEST_CALLING_USER));

        mockScheduleExactAlarmStatePreT(false, MODE_ALLOWED);
        assertFalse(mBinder.hasScheduleExactAlarm(TEST_CALLING_PACKAGE, TEST_CALLING_USER));
    }

    @Test
    public void canScheduleExactAlarmsBinderCallChangeDisabled() throws RemoteException {
        mockChangeEnabled(AlarmManager.REQUIRE_EXACT_ALARM_PERMISSION, false);

        // canScheduleExactAlarms should be true regardless of any permission state.
        // Both SEA and UEA are denied in setUp.
        assertTrue(mBinder.canScheduleExactAlarms(TEST_CALLING_PACKAGE));

        mockUseExactAlarmState(false);
        assertTrue(mBinder.canScheduleExactAlarms(TEST_CALLING_PACKAGE));

        mockScheduleExactAlarmState(false);
        assertTrue(mBinder.canScheduleExactAlarms(TEST_CALLING_PACKAGE));
    }

    @Test
    public void canScheduleExactAlarmsBinderCallPreT() throws RemoteException {
        // Policy permission is denied in setUp().
        mockChangeEnabled(AlarmManager.REQUIRE_EXACT_ALARM_PERMISSION, true);
        mockChangeEnabled(AlarmManager.ENABLE_USE_EXACT_ALARM, true);

        // No permission, no exemption.
        mockScheduleExactAlarmStatePreT(true, MODE_ERRORED);
        assertFalse(mBinder.canScheduleExactAlarms(TEST_CALLING_PACKAGE));

        // Policy permission only, no exemption.
        mockScheduleExactAlarmStatePreT(true, MODE_ERRORED);
        mockUseExactAlarmState(true);
        assertTrue(mBinder.canScheduleExactAlarms(TEST_CALLING_PACKAGE));

        mockUseExactAlarmState(false);

        // User permission only, no exemption.
        mockScheduleExactAlarmStatePreT(true, MODE_DEFAULT);
        assertTrue(mBinder.canScheduleExactAlarms(TEST_CALLING_PACKAGE));

        // No permission, exemption.
        mockScheduleExactAlarmStatePreT(true, MODE_ERRORED);
        when(mDeviceIdleInternal.isAppOnWhitelist(TEST_CALLING_UID)).thenReturn(true);
        assertTrue(mBinder.canScheduleExactAlarms(TEST_CALLING_PACKAGE));

        // No permission, exemption.
        mockScheduleExactAlarmStatePreT(true, MODE_ERRORED);
        when(mDeviceIdleInternal.isAppOnWhitelist(TEST_CALLING_UID)).thenReturn(false);
        doReturn(true).when(() -> UserHandle.isCore(TEST_CALLING_UID));
        assertTrue(mBinder.canScheduleExactAlarms(TEST_CALLING_PACKAGE));

        // Both permissions and exemption.
        mockScheduleExactAlarmStatePreT(true, MODE_ALLOWED);
        mockUseExactAlarmState(true);
        assertTrue(mBinder.canScheduleExactAlarms(TEST_CALLING_PACKAGE));
    }

    @Test
    public void canScheduleExactAlarmsBinderCall() throws RemoteException {
        // Both permissions are denied in setUp().
        mockChangeEnabled(AlarmManager.REQUIRE_EXACT_ALARM_PERMISSION, true);
        mockChangeEnabled(AlarmManager.ENABLE_USE_EXACT_ALARM, true);
        mockChangeEnabled(AlarmManager.SCHEDULE_EXACT_ALARM_DENIED_BY_DEFAULT, true);

        // No permission, no exemption.
        assertFalse(mBinder.canScheduleExactAlarms(TEST_CALLING_PACKAGE));

        // Policy permission only, no exemption.
        mockUseExactAlarmState(true);
        assertTrue(mBinder.canScheduleExactAlarms(TEST_CALLING_PACKAGE));

        mockUseExactAlarmState(false);

        // User permission only, no exemption.
        mockScheduleExactAlarmState(true);
        assertTrue(mBinder.canScheduleExactAlarms(TEST_CALLING_PACKAGE));

        // No permission, exemption.
        mockScheduleExactAlarmState(false);
        when(mDeviceIdleInternal.isAppOnWhitelist(TEST_CALLING_UID)).thenReturn(true);
        assertTrue(mBinder.canScheduleExactAlarms(TEST_CALLING_PACKAGE));

        // No permission, core uid exemption.
        when(mDeviceIdleInternal.isAppOnWhitelist(TEST_CALLING_UID)).thenReturn(false);
        doReturn(true).when(() -> UserHandle.isCore(TEST_CALLING_UID));
        assertTrue(mBinder.canScheduleExactAlarms(TEST_CALLING_PACKAGE));

        // Both permissions and core uid exemption.
        mockScheduleExactAlarmState(true);
        mockUseExactAlarmState(true);
        assertTrue(mBinder.canScheduleExactAlarms(TEST_CALLING_PACKAGE));
    }

    @Test
    public void noPermissionCheckWhenChangeDisabled() throws RemoteException {
        mockChangeEnabled(AlarmManager.REQUIRE_EXACT_ALARM_PERMISSION, false);

        // alarm clock
        mBinder.set(TEST_CALLING_PACKAGE, ELAPSED_REALTIME_WAKEUP, 1234, WINDOW_EXACT, 0, 0,
                getNewMockPendingIntent(), null, null, null,
                mock(AlarmManager.AlarmClockInfo.class));

        // exact
        mBinder.set(TEST_CALLING_PACKAGE, ELAPSED_REALTIME_WAKEUP, 1234, WINDOW_EXACT, 0,
                0, getNewMockPendingIntent(), null, null, null, null);

        // exact, allow-while-idle
        mBinder.set(TEST_CALLING_PACKAGE, ELAPSED_REALTIME_WAKEUP, 1234, WINDOW_EXACT, 0,
                FLAG_ALLOW_WHILE_IDLE, getNewMockPendingIntent(), null, null, null, null);

        // inexact, allow-while-idle
        mBinder.set(TEST_CALLING_PACKAGE, ELAPSED_REALTIME_WAKEUP, 1234, WINDOW_HEURISTIC, 0,
                FLAG_ALLOW_WHILE_IDLE, getNewMockPendingIntent(), null, null, null, null);

        verify(mService, never()).hasScheduleExactAlarmInternal(TEST_CALLING_PACKAGE,
                TEST_CALLING_UID);
        verify(mService, never()).hasUseExactAlarmInternal(TEST_CALLING_PACKAGE,
                TEST_CALLING_UID);
        verify(mDeviceIdleInternal, never()).isAppOnWhitelist(anyInt());
    }

    @Test
    public void exactBinderCallWhenChangeDisabled() throws Exception {
        mockChangeEnabled(AlarmManager.REQUIRE_EXACT_ALARM_PERMISSION, false);

        final PendingIntent alarmPi = getNewMockPendingIntent();
        mBinder.set(TEST_CALLING_PACKAGE, ELAPSED_REALTIME_WAKEUP, 1234, WINDOW_EXACT, 0,
                0, alarmPi, null, null, null, null);

        verify(mService).setImpl(eq(ELAPSED_REALTIME_WAKEUP), eq(1234L), eq(WINDOW_EXACT), eq(0L),
                eq(alarmPi), isNull(), isNull(),
                eq(FLAG_STANDALONE), isNull(), isNull(),
                eq(TEST_CALLING_UID), eq(TEST_CALLING_PACKAGE), isNull(),
                eq(EXACT_ALLOW_REASON_COMPAT));
    }

    @Test
    public void exactAllowWhileIdleBinderCallWhenChangeDisabled() throws Exception {
        mockChangeEnabled(AlarmManager.REQUIRE_EXACT_ALARM_PERMISSION, false);

        final PendingIntent alarmPi = getNewMockPendingIntent();
        mBinder.set(TEST_CALLING_PACKAGE, ELAPSED_REALTIME_WAKEUP, 1234, WINDOW_EXACT, 0,
                FLAG_ALLOW_WHILE_IDLE, alarmPi, null, null, null, null);

        final ArgumentCaptor<Bundle> bundleCaptor = ArgumentCaptor.forClass(Bundle.class);
        verify(mService).setImpl(eq(ELAPSED_REALTIME_WAKEUP), eq(1234L), eq(WINDOW_EXACT), eq(0L),
                eq(alarmPi), isNull(), isNull(),
                eq(FLAG_ALLOW_WHILE_IDLE_COMPAT | FLAG_STANDALONE), isNull(), isNull(),
                eq(TEST_CALLING_UID), eq(TEST_CALLING_PACKAGE), bundleCaptor.capture(),
                eq(EXACT_ALLOW_REASON_COMPAT));

        final BroadcastOptions idleOptions = new BroadcastOptions(bundleCaptor.getValue());
        final int type = idleOptions.getTemporaryAppAllowlistType();
        assertEquals(TEMPORARY_ALLOWLIST_TYPE_FOREGROUND_SERVICE_ALLOWED, type);
    }

    @Test
    public void inexactAllowWhileIdleBinderCallWhenChangeDisabled() throws Exception {
        mockChangeEnabled(AlarmManager.REQUIRE_EXACT_ALARM_PERMISSION, false);

        final PendingIntent alarmPi = getNewMockPendingIntent();
        mBinder.set(TEST_CALLING_PACKAGE, ELAPSED_REALTIME_WAKEUP, 1234, WINDOW_HEURISTIC, 0,
                FLAG_ALLOW_WHILE_IDLE, alarmPi, null, null, null, null);

        final ArgumentCaptor<Bundle> bundleCaptor = ArgumentCaptor.forClass(Bundle.class);
        verify(mService).setImpl(eq(ELAPSED_REALTIME_WAKEUP), eq(1234L), anyLong(), eq(0L),
                eq(alarmPi), isNull(), isNull(), eq(FLAG_ALLOW_WHILE_IDLE_COMPAT), isNull(),
                isNull(), eq(TEST_CALLING_UID), eq(TEST_CALLING_PACKAGE), bundleCaptor.capture(),
                eq(EXACT_ALLOW_REASON_NOT_APPLICABLE));

        final BroadcastOptions idleOptions = new BroadcastOptions(bundleCaptor.getValue());
        final int type = idleOptions.getTemporaryAppAllowlistType();
        assertEquals(TEMPORARY_ALLOWLIST_TYPE_FOREGROUND_SERVICE_ALLOWED, type);
    }

    @Test
    public void alarmClockBinderCallWhenChangeDisabled() throws Exception {
        mockChangeEnabled(AlarmManager.REQUIRE_EXACT_ALARM_PERMISSION, false);

        final PendingIntent alarmPi = getNewMockPendingIntent();
        final AlarmManager.AlarmClockInfo alarmClock = mock(AlarmManager.AlarmClockInfo.class);
        mBinder.set(TEST_CALLING_PACKAGE, RTC_WAKEUP, 1234, WINDOW_EXACT, 0, 0,
                alarmPi, null, null, null, alarmClock);

        final ArgumentCaptor<Bundle> bundleCaptor = ArgumentCaptor.forClass(Bundle.class);
        verify(mService).setImpl(eq(RTC_WAKEUP), eq(1234L), eq(WINDOW_EXACT), eq(0L),
                eq(alarmPi), isNull(), isNull(), eq(FLAG_STANDALONE | FLAG_WAKE_FROM_IDLE),
                isNull(), eq(alarmClock), eq(TEST_CALLING_UID), eq(TEST_CALLING_PACKAGE),
                bundleCaptor.capture(), eq(EXACT_ALLOW_REASON_COMPAT));

        final BroadcastOptions idleOptions = new BroadcastOptions(bundleCaptor.getValue());
        final int type = idleOptions.getTemporaryAppAllowlistType();
        assertEquals(TEMPORARY_ALLOWLIST_TYPE_FOREGROUND_SERVICE_ALLOWED, type);
    }

    @Test
    public void alarmClockBinderCallWithSEAPermission() throws RemoteException {
        mockChangeEnabled(AlarmManager.REQUIRE_EXACT_ALARM_PERMISSION, true);
        mockChangeEnabled(AlarmManager.SCHEDULE_EXACT_ALARM_DENIED_BY_DEFAULT, true);

        mockScheduleExactAlarmState(true);

        final PendingIntent alarmPi = getNewMockPendingIntent();
        final AlarmManager.AlarmClockInfo alarmClock = mock(AlarmManager.AlarmClockInfo.class);
        mBinder.set(TEST_CALLING_PACKAGE, RTC_WAKEUP, 1234, WINDOW_EXACT, 0, 0,
                alarmPi, null, null, null, alarmClock);

        // Correct permission checks are invoked.
        verify(mService).hasScheduleExactAlarmInternal(TEST_CALLING_PACKAGE, TEST_CALLING_UID);
        verify(mDeviceIdleInternal, never()).isAppOnWhitelist(anyInt());

        final ArgumentCaptor<Bundle> bundleCaptor = ArgumentCaptor.forClass(Bundle.class);
        verify(mService).setImpl(eq(RTC_WAKEUP), eq(1234L), eq(WINDOW_EXACT), eq(0L),
                eq(alarmPi), isNull(), isNull(), eq(FLAG_STANDALONE | FLAG_WAKE_FROM_IDLE),
                isNull(), eq(alarmClock), eq(TEST_CALLING_UID), eq(TEST_CALLING_PACKAGE),
                bundleCaptor.capture(), eq(EXACT_ALLOW_REASON_PERMISSION));

        final BroadcastOptions idleOptions = new BroadcastOptions(bundleCaptor.getValue());
        final int type = idleOptions.getTemporaryAppAllowlistType();
        assertEquals(TEMPORARY_ALLOWLIST_TYPE_FOREGROUND_SERVICE_ALLOWED, type);
    }

    @Test
    public void alarmClockBinderCallWithUEAPermission() throws RemoteException {
        mockChangeEnabled(AlarmManager.REQUIRE_EXACT_ALARM_PERMISSION, true);
        mockChangeEnabled(AlarmManager.ENABLE_USE_EXACT_ALARM, true);
        mockChangeEnabled(AlarmManager.SCHEDULE_EXACT_ALARM_DENIED_BY_DEFAULT, true);

        mockUseExactAlarmState(true);
        mockScheduleExactAlarmState(false);

        final PendingIntent alarmPi = getNewMockPendingIntent();
        final AlarmManager.AlarmClockInfo alarmClock = mock(AlarmManager.AlarmClockInfo.class);
        mBinder.set(TEST_CALLING_PACKAGE, RTC_WAKEUP, 1234, WINDOW_EXACT, 0, 0,
                alarmPi, null, null, null, alarmClock);

        // Correct permission checks are invoked.
        verify(mService).hasUseExactAlarmInternal(TEST_CALLING_PACKAGE, TEST_CALLING_UID);
        verify(mDeviceIdleInternal, never()).isAppOnWhitelist(anyInt());

        final ArgumentCaptor<Bundle> bundleCaptor = ArgumentCaptor.forClass(Bundle.class);
        verify(mService).setImpl(eq(RTC_WAKEUP), eq(1234L), eq(WINDOW_EXACT), eq(0L),
                eq(alarmPi), isNull(), isNull(), eq(FLAG_STANDALONE | FLAG_WAKE_FROM_IDLE),
                isNull(), eq(alarmClock), eq(TEST_CALLING_UID), eq(TEST_CALLING_PACKAGE),
                bundleCaptor.capture(), eq(EXACT_ALLOW_REASON_POLICY_PERMISSION));

        final BroadcastOptions idleOptions = new BroadcastOptions(bundleCaptor.getValue());
        final int type = idleOptions.getTemporaryAppAllowlistType();
        assertEquals(TEMPORARY_ALLOWLIST_TYPE_FOREGROUND_SERVICE_ALLOWED, type);
    }

    private void mockScheduleExactAlarmStatePreT(boolean declared, int mode) {
        String[] requesters = declared ? new String[]{TEST_CALLING_PACKAGE} : EmptyArray.STRING;
        when(mPermissionManagerInternal.getAppOpPermissionPackages(SCHEDULE_EXACT_ALARM))
                .thenReturn(requesters);
        mService.refreshExactAlarmCandidates();

        when(mAppOpsManager.checkOpNoThrow(OP_SCHEDULE_EXACT_ALARM, TEST_CALLING_UID,
                TEST_CALLING_PACKAGE)).thenReturn(mode);
    }

    private void mockScheduleExactAlarmState(boolean granted) {
        String[] requesters = granted ? new String[]{TEST_CALLING_PACKAGE} : EmptyArray.STRING;
        when(mPermissionManagerInternal.getAppOpPermissionPackages(SCHEDULE_EXACT_ALARM))
                .thenReturn(requesters);
        mService.refreshExactAlarmCandidates();

        final int result = granted ? PermissionChecker.PERMISSION_GRANTED
                : PermissionChecker.PERMISSION_HARD_DENIED;
        doReturn(result).when(
                () -> PermissionChecker.checkPermissionForPreflight(eq(mMockContext),
                        eq(SCHEDULE_EXACT_ALARM), anyInt(), eq(TEST_CALLING_UID),
                        eq(TEST_CALLING_PACKAGE)));
    }

    private void mockUseExactAlarmState(boolean granted) {
        final int result = granted ? PermissionChecker.PERMISSION_GRANTED
                : PermissionChecker.PERMISSION_HARD_DENIED;
        doReturn(result).when(
                () -> PermissionChecker.checkPermissionForPreflight(eq(mMockContext),
                        eq(Manifest.permission.USE_EXACT_ALARM), anyInt(), eq(TEST_CALLING_UID),
                        eq(TEST_CALLING_PACKAGE)));
    }

    @Test
    public void alarmClockBinderCallWithoutPermission() throws RemoteException {
        mockChangeEnabled(AlarmManager.REQUIRE_EXACT_ALARM_PERMISSION, true);

        mockScheduleExactAlarmStatePreT(true, MODE_ERRORED);
        when(mDeviceIdleInternal.isAppOnWhitelist(anyInt())).thenReturn(true);

        final PendingIntent alarmPi = getNewMockPendingIntent();
        final AlarmManager.AlarmClockInfo alarmClock = mock(AlarmManager.AlarmClockInfo.class);
        mBinder.set(TEST_CALLING_PACKAGE, RTC_WAKEUP, 1234, WINDOW_EXACT, 0, 0,
                alarmPi, null, null, null, alarmClock);

        // Correct permission checks are invoked.
        verify(mService).hasScheduleExactAlarmInternal(TEST_CALLING_PACKAGE, TEST_CALLING_UID);
        verify(mDeviceIdleInternal).isAppOnWhitelist(UserHandle.getAppId(TEST_CALLING_UID));

        verify(mService).setImpl(eq(RTC_WAKEUP), eq(1234L), eq(WINDOW_EXACT), eq(0L),
                eq(alarmPi), isNull(), isNull(), eq(FLAG_STANDALONE | FLAG_WAKE_FROM_IDLE),
                isNull(), eq(alarmClock), eq(TEST_CALLING_UID), eq(TEST_CALLING_PACKAGE),
                isNull(), eq(EXACT_ALLOW_REASON_ALLOW_LIST));
    }

    @Test
    public void exactBinderCallWithSEAPermission() throws RemoteException {
        mockChangeEnabled(AlarmManager.REQUIRE_EXACT_ALARM_PERMISSION, true);
        mockChangeEnabled(AlarmManager.SCHEDULE_EXACT_ALARM_DENIED_BY_DEFAULT, true);

        mockScheduleExactAlarmState(true);
        final PendingIntent alarmPi = getNewMockPendingIntent();
        mBinder.set(TEST_CALLING_PACKAGE, ELAPSED_REALTIME_WAKEUP, 1234, WINDOW_EXACT, 0,
                0, alarmPi, null, null, null, null);

        verify(mService).hasScheduleExactAlarmInternal(TEST_CALLING_PACKAGE, TEST_CALLING_UID);
        verify(mDeviceIdleInternal, never()).isAppOnWhitelist(anyInt());

        final ArgumentCaptor<Bundle> bundleCaptor = ArgumentCaptor.forClass(Bundle.class);
        verify(mService).setImpl(eq(ELAPSED_REALTIME_WAKEUP), eq(1234L), eq(WINDOW_EXACT), eq(0L),
                eq(alarmPi), isNull(), isNull(),
                eq(FLAG_STANDALONE), isNull(), isNull(),
                eq(TEST_CALLING_UID), eq(TEST_CALLING_PACKAGE), bundleCaptor.capture(),
                eq(EXACT_ALLOW_REASON_PERMISSION));

        final BroadcastOptions idleOptions = new BroadcastOptions(bundleCaptor.getValue());
        final int type = idleOptions.getTemporaryAppAllowlistType();
        assertEquals(TEMPORARY_ALLOWLIST_TYPE_FOREGROUND_SERVICE_ALLOWED, type);
    }

    @Test
    public void exactBinderCallWithUEAPermission() throws RemoteException {
        mockChangeEnabled(AlarmManager.REQUIRE_EXACT_ALARM_PERMISSION, true);
        mockChangeEnabled(AlarmManager.ENABLE_USE_EXACT_ALARM, true);
        mockChangeEnabled(AlarmManager.SCHEDULE_EXACT_ALARM_DENIED_BY_DEFAULT, true);

        mockUseExactAlarmState(true);
        mockScheduleExactAlarmState(false);
        final PendingIntent alarmPi = getNewMockPendingIntent();
        mBinder.set(TEST_CALLING_PACKAGE, ELAPSED_REALTIME_WAKEUP, 1234, WINDOW_EXACT, 0,
                0, alarmPi, null, null, null, null);

        verify(mService).hasUseExactAlarmInternal(TEST_CALLING_PACKAGE, TEST_CALLING_UID);
        verify(mDeviceIdleInternal, never()).isAppOnWhitelist(anyInt());

        final ArgumentCaptor<Bundle> bundleCaptor = ArgumentCaptor.forClass(Bundle.class);
        verify(mService).setImpl(eq(ELAPSED_REALTIME_WAKEUP), eq(1234L), eq(WINDOW_EXACT), eq(0L),
                eq(alarmPi), isNull(), isNull(),
                eq(FLAG_STANDALONE), isNull(), isNull(),
                eq(TEST_CALLING_UID), eq(TEST_CALLING_PACKAGE), bundleCaptor.capture(),
                eq(EXACT_ALLOW_REASON_POLICY_PERMISSION));

        final BroadcastOptions idleOptions = new BroadcastOptions(bundleCaptor.getValue());
        final int type = idleOptions.getTemporaryAppAllowlistType();
        assertEquals(TEMPORARY_ALLOWLIST_TYPE_FOREGROUND_SERVICE_ALLOWED, type);
    }

    @Test
    public void exactBinderCallWithAllowlist() throws RemoteException {
        mockChangeEnabled(AlarmManager.REQUIRE_EXACT_ALARM_PERMISSION, true);
        // If permission is denied, only then allowlist will be checked.
        mockScheduleExactAlarmStatePreT(true, MODE_ERRORED);
        when(mDeviceIdleInternal.isAppOnWhitelist(anyInt())).thenReturn(true);

        final PendingIntent alarmPi = getNewMockPendingIntent();
        mBinder.set(TEST_CALLING_PACKAGE, ELAPSED_REALTIME_WAKEUP, 1234, WINDOW_EXACT, 0,
                0, alarmPi, null, null, null, null);

        verify(mDeviceIdleInternal).isAppOnWhitelist(UserHandle.getAppId(TEST_CALLING_UID));

        verify(mService).setImpl(eq(ELAPSED_REALTIME_WAKEUP), eq(1234L), eq(WINDOW_EXACT), eq(0L),
                eq(alarmPi), isNull(), isNull(),
                eq(FLAG_STANDALONE), isNull(), isNull(),
                eq(TEST_CALLING_UID), eq(TEST_CALLING_PACKAGE), isNull(),
                eq(EXACT_ALLOW_REASON_ALLOW_LIST));
    }

    @Test
    public void exactAllowWhileIdleBinderCallWithSEAPermission() throws RemoteException {
        mockChangeEnabled(AlarmManager.REQUIRE_EXACT_ALARM_PERMISSION, true);

        mockScheduleExactAlarmStatePreT(true, MODE_ALLOWED);
        final PendingIntent alarmPi = getNewMockPendingIntent();
        mBinder.set(TEST_CALLING_PACKAGE, ELAPSED_REALTIME_WAKEUP, 1234, WINDOW_EXACT, 0,
                FLAG_ALLOW_WHILE_IDLE, alarmPi, null, null, null, null);

        verify(mService).hasScheduleExactAlarmInternal(TEST_CALLING_PACKAGE, TEST_CALLING_UID);
        verify(mDeviceIdleInternal, never()).isAppOnWhitelist(anyInt());

        final ArgumentCaptor<Bundle> bundleCaptor = ArgumentCaptor.forClass(Bundle.class);
        verify(mService).setImpl(eq(ELAPSED_REALTIME_WAKEUP), eq(1234L), eq(WINDOW_EXACT), eq(0L),
                eq(alarmPi), isNull(), isNull(),
                eq(FLAG_ALLOW_WHILE_IDLE | FLAG_STANDALONE), isNull(), isNull(),
                eq(TEST_CALLING_UID), eq(TEST_CALLING_PACKAGE), bundleCaptor.capture(),
                eq(EXACT_ALLOW_REASON_PERMISSION));

        final BroadcastOptions idleOptions = new BroadcastOptions(bundleCaptor.getValue());
        final int type = idleOptions.getTemporaryAppAllowlistType();
        assertEquals(TEMPORARY_ALLOWLIST_TYPE_FOREGROUND_SERVICE_ALLOWED, type);
    }

    @Test
    public void exactAllowWhileIdleBinderCallWithUEAPermission() throws RemoteException {
        mockChangeEnabled(AlarmManager.REQUIRE_EXACT_ALARM_PERMISSION, true);
        mockChangeEnabled(AlarmManager.ENABLE_USE_EXACT_ALARM, true);

        mockUseExactAlarmState(true);
        mockScheduleExactAlarmStatePreT(false, MODE_ERRORED);
        final PendingIntent alarmPi = getNewMockPendingIntent();
        mBinder.set(TEST_CALLING_PACKAGE, ELAPSED_REALTIME_WAKEUP, 1234, WINDOW_EXACT, 0,
                FLAG_ALLOW_WHILE_IDLE, alarmPi, null, null, null, null);

        verify(mService).hasUseExactAlarmInternal(TEST_CALLING_PACKAGE, TEST_CALLING_UID);
        verify(mDeviceIdleInternal, never()).isAppOnWhitelist(anyInt());

        final ArgumentCaptor<Bundle> bundleCaptor = ArgumentCaptor.forClass(Bundle.class);
        verify(mService).setImpl(eq(ELAPSED_REALTIME_WAKEUP), eq(1234L), eq(WINDOW_EXACT), eq(0L),
                eq(alarmPi), isNull(), isNull(),
                eq(FLAG_ALLOW_WHILE_IDLE | FLAG_STANDALONE), isNull(), isNull(),
                eq(TEST_CALLING_UID), eq(TEST_CALLING_PACKAGE), bundleCaptor.capture(),
                eq(EXACT_ALLOW_REASON_POLICY_PERMISSION));

        final BroadcastOptions idleOptions = new BroadcastOptions(bundleCaptor.getValue());
        final int type = idleOptions.getTemporaryAppAllowlistType();
        assertEquals(TEMPORARY_ALLOWLIST_TYPE_FOREGROUND_SERVICE_ALLOWED, type);
    }

    @Test
    public void exactAllowWhileIdleBinderCallWithAllowlist() throws RemoteException {
        mockChangeEnabled(AlarmManager.REQUIRE_EXACT_ALARM_PERMISSION, true);
        // If permission is denied, only then allowlist will be checked.
        mockScheduleExactAlarmStatePreT(true, MODE_ERRORED);
        when(mDeviceIdleInternal.isAppOnWhitelist(anyInt())).thenReturn(true);

        final PendingIntent alarmPi = getNewMockPendingIntent();
        mBinder.set(TEST_CALLING_PACKAGE, ELAPSED_REALTIME_WAKEUP, 1234, WINDOW_EXACT, 0,
                FLAG_ALLOW_WHILE_IDLE, alarmPi, null, null, null, null);

        verify(mDeviceIdleInternal).isAppOnWhitelist(UserHandle.getAppId(TEST_CALLING_UID));

        final ArgumentCaptor<Bundle> bundleCaptor = ArgumentCaptor.forClass(Bundle.class);
        verify(mService).setImpl(eq(ELAPSED_REALTIME_WAKEUP), eq(1234L), eq(WINDOW_EXACT), eq(0L),
                eq(alarmPi), isNull(), isNull(),
                eq(FLAG_ALLOW_WHILE_IDLE_COMPAT | FLAG_STANDALONE), isNull(), isNull(),
                eq(TEST_CALLING_UID), eq(TEST_CALLING_PACKAGE), bundleCaptor.capture(),
                eq(EXACT_ALLOW_REASON_ALLOW_LIST));

        final BroadcastOptions idleOptions = new BroadcastOptions(bundleCaptor.getValue());
        final int type = idleOptions.getTemporaryAppAllowlistType();
        // App is on power allowlist, doesn't need explicit FGS grant in broadcast options.
        assertEquals(TEMPORARY_ALLOWLIST_TYPE_FOREGROUND_SERVICE_NOT_ALLOWED, type);
    }

    @Test
    public void exactBinderCallsWithoutPermissionWithoutAllowlist() throws RemoteException {
        mockChangeEnabled(AlarmManager.REQUIRE_EXACT_ALARM_PERMISSION, true);

        mockScheduleExactAlarmStatePreT(true, MODE_ERRORED);
        when(mDeviceIdleInternal.isAppOnWhitelist(anyInt())).thenReturn(false);

        final PendingIntent alarmPi = getNewMockPendingIntent();
        try {
            mBinder.set(TEST_CALLING_PACKAGE, ELAPSED_REALTIME_WAKEUP, 1234, WINDOW_EXACT, 0,
                    0, alarmPi, null, null, null, null);
            fail("exact binder call succeeded without permission");
        } catch (SecurityException se) {
            // Expected.
        }
        try {
            mBinder.set(TEST_CALLING_PACKAGE, ELAPSED_REALTIME_WAKEUP, 1234, WINDOW_EXACT, 0,
                    FLAG_ALLOW_WHILE_IDLE, alarmPi, null, null, null, null);
            fail("exact, allow-while-idle binder call succeeded without permission");
        } catch (SecurityException se) {
            // Expected.
        }
        verify(mDeviceIdleInternal, times(2)).isAppOnWhitelist(anyInt());
    }

    @Test
    public void exactListenerBinderCallWithoutPermissionWithoutAllowlist() throws RemoteException {
        mockChangeEnabled(AlarmManager.REQUIRE_EXACT_ALARM_PERMISSION, true);
        mockChangeEnabled(AlarmManager.ENABLE_USE_EXACT_ALARM, true);

        mockScheduleExactAlarmState(false);
        mockUseExactAlarmState(false);
        when(mDeviceIdleInternal.isAppOnWhitelist(anyInt())).thenReturn(false);

        final IAlarmListener listener = getNewListener(() -> {});
        mBinder.set(TEST_CALLING_PACKAGE, ELAPSED_REALTIME_WAKEUP, 1234, WINDOW_EXACT, 0,
                0, null, listener, "test-tag", null, null);

        verify(mService, never()).hasUseExactAlarmInternal(TEST_CALLING_PACKAGE, TEST_CALLING_UID);
        verify(mService, never()).hasScheduleExactAlarmInternal(TEST_CALLING_PACKAGE,
                TEST_CALLING_UID);
        verify(mDeviceIdleInternal, never()).isAppOnWhitelist(anyInt());

        final ArgumentCaptor<Bundle> bundleCaptor = ArgumentCaptor.forClass(Bundle.class);
        verify(mService).setImpl(eq(ELAPSED_REALTIME_WAKEUP), eq(1234L), eq(WINDOW_EXACT), eq(0L),
                isNull(), eq(listener), eq("test-tag"), eq(FLAG_STANDALONE), isNull(), isNull(),
                eq(TEST_CALLING_UID), eq(TEST_CALLING_PACKAGE), bundleCaptor.capture(),
                eq(EXACT_ALLOW_REASON_LISTENER));

        final BroadcastOptions idleOptions = new BroadcastOptions(bundleCaptor.getValue());
        final int type = idleOptions.getTemporaryAppAllowlistType();
        assertEquals(TEMPORARY_ALLOWLIST_TYPE_FOREGROUND_SERVICE_ALLOWED, type);
    }

    @Test
    public void exactAllowWhileIdleListenerBinderCallWithoutPermissionWithoutAllowlist()
            throws RemoteException {
        mockChangeEnabled(AlarmManager.REQUIRE_EXACT_ALARM_PERMISSION, true);
        mockChangeEnabled(AlarmManager.ENABLE_USE_EXACT_ALARM, true);

        mockScheduleExactAlarmState(false);
        mockUseExactAlarmState(false);
        when(mDeviceIdleInternal.isAppOnWhitelist(anyInt())).thenReturn(false);

        final IAlarmListener listener = getNewListener(() -> {});
        mBinder.set(TEST_CALLING_PACKAGE, ELAPSED_REALTIME_WAKEUP, 1234, WINDOW_EXACT, 0,
                FLAG_ALLOW_WHILE_IDLE, null, listener, "test-tag", null, null);

        verify(mService, never()).hasUseExactAlarmInternal(TEST_CALLING_PACKAGE, TEST_CALLING_UID);
        verify(mService, never()).hasScheduleExactAlarmInternal(TEST_CALLING_PACKAGE,
                TEST_CALLING_UID);
        verify(mDeviceIdleInternal, never()).isAppOnWhitelist(anyInt());

        final ArgumentCaptor<Bundle> bundleCaptor = ArgumentCaptor.forClass(Bundle.class);
        verify(mService).setImpl(eq(ELAPSED_REALTIME_WAKEUP), eq(1234L), eq(WINDOW_EXACT), eq(0L),
                isNull(), eq(listener), eq("test-tag"),
                eq(FLAG_STANDALONE | FLAG_ALLOW_WHILE_IDLE_COMPAT), isNull(), isNull(),
                eq(TEST_CALLING_UID), eq(TEST_CALLING_PACKAGE), bundleCaptor.capture(),
                eq(EXACT_ALLOW_REASON_LISTENER));

        final BroadcastOptions idleOptions = new BroadcastOptions(bundleCaptor.getValue());
        final int type = idleOptions.getTemporaryAppAllowlistType();
        assertEquals(TEMPORARY_ALLOWLIST_TYPE_FOREGROUND_SERVICE_ALLOWED, type);
    }

    @Test
    public void inexactAllowWhileIdleBinderCall() throws RemoteException {
        // Both permission and power exemption status don't matter for these alarms.
        // We only want to test that the flags and idleOptions are correct.
        mockChangeEnabled(AlarmManager.REQUIRE_EXACT_ALARM_PERMISSION, true);

        final PendingIntent alarmPi = getNewMockPendingIntent();
        mBinder.set(TEST_CALLING_PACKAGE, ELAPSED_REALTIME_WAKEUP, 4321, WINDOW_HEURISTIC, 0,
                FLAG_ALLOW_WHILE_IDLE, alarmPi, null, null, null, null);

        verify(mService, never()).hasScheduleExactAlarmInternal(anyString(), anyInt());
        verify(mService, never()).hasUseExactAlarmInternal(anyString(), anyInt());
        verify(mDeviceIdleInternal, never()).isAppOnWhitelist(anyInt());

        final ArgumentCaptor<Bundle> bundleCaptor = ArgumentCaptor.forClass(Bundle.class);
        verify(mService).setImpl(eq(ELAPSED_REALTIME_WAKEUP), eq(4321L), anyLong(), eq(0L),
                eq(alarmPi), isNull(), isNull(), eq(FLAG_ALLOW_WHILE_IDLE_COMPAT), isNull(),
                isNull(), eq(TEST_CALLING_UID), eq(TEST_CALLING_PACKAGE), bundleCaptor.capture(),
                eq(EXACT_ALLOW_REASON_NOT_APPLICABLE));

        final BroadcastOptions idleOptions = new BroadcastOptions(bundleCaptor.getValue());
        final int type = idleOptions.getTemporaryAppAllowlistType();
        assertEquals(TEMPORARY_ALLOWLIST_TYPE_FOREGROUND_SERVICE_NOT_ALLOWED, type);
    }

    @Test
    public void binderCallWithUserAllowlist() throws RemoteException {
        mockChangeEnabled(AlarmManager.REQUIRE_EXACT_ALARM_PERMISSION, true);

        mockScheduleExactAlarmStatePreT(true, MODE_ERRORED);
        when(mDeviceIdleInternal.isAppOnWhitelist(anyInt())).thenReturn(true);
        when(mAppStateTracker.isUidPowerSaveUserExempt(TEST_CALLING_UID)).thenReturn(true);

        final PendingIntent alarmPi = getNewMockPendingIntent();
        mBinder.set(TEST_CALLING_PACKAGE, ELAPSED_REALTIME_WAKEUP, 1234, WINDOW_EXACT, 0,
                FLAG_ALLOW_WHILE_IDLE, alarmPi, null, null, null, null);

        final ArgumentCaptor<Bundle> bundleCaptor = ArgumentCaptor.forClass(Bundle.class);
        verify(mService).setImpl(eq(ELAPSED_REALTIME_WAKEUP), eq(1234L), eq(WINDOW_EXACT), eq(0L),
                eq(alarmPi), isNull(), isNull(),
                eq(FLAG_ALLOW_WHILE_IDLE_UNRESTRICTED | FLAG_STANDALONE), isNull(), isNull(),
                eq(TEST_CALLING_UID), eq(TEST_CALLING_PACKAGE), isNull(),
                eq(EXACT_ALLOW_REASON_ALLOW_LIST));
    }

    @Test
    public void removeAllBinderCall() throws RemoteException {
        for (int i = 0; i < 10; i++) {
            setTestAlarm(ELAPSED_REALTIME, mNowElapsedTest + i + 1, getNewMockPendingIntent());
        }

        final String otherPackage1 = "other.package1";
        final String otherPackage2 = "other.package2";
        final int otherAppId = 1243;
        final int otherUser1 = 31;
        final int otherUser2 = 8;
        final int otherUid1 = UserHandle.getUid(otherUser1, otherAppId);
        final int otherUid2 = UserHandle.getUid(otherUser2, otherAppId);

        registerAppIds(
                new String[]{TEST_CALLING_PACKAGE, otherPackage1, otherPackage2},
                new Integer[]{UserHandle.getAppId(TEST_CALLING_UID), otherAppId, otherAppId}
        );

        for (int i = 0; i < 9; i++) {
            setTestAlarm(ELAPSED_REALTIME, mNowElapsedTest + i + 11, 0,
                    getNewMockPendingIntent(otherUid1, otherPackage1), 0, 0, otherUid1,
                    otherPackage1, null);
        }

        for (int i = 0; i < 8; i++) {
            setTestAlarm(ELAPSED_REALTIME, mNowElapsedTest + i + 20, 0,
                    getNewMockPendingIntent(otherUid1, otherPackage2), 0, 0, otherUid1,
                    otherPackage2, null);
        }

        for (int i = 0; i < 7; i++) {
            setTestAlarm(ELAPSED_REALTIME, mNowElapsedTest + i + 28, 0,
                    getNewMockPendingIntent(otherUid2, otherPackage2), 0, 0, otherUid2,
                    otherPackage2, null);
        }

        assertEquals(34, mService.mAlarmStore.size());

        try {
            mBinder.removeAll(otherPackage1);
            fail("removeAll() for wrong package did not throw SecurityException");
        } catch (SecurityException se) {
            // Expected
        }

        try {
            mBinder.removeAll(otherPackage2);
            fail("removeAll() for wrong package did not throw SecurityException");
        } catch (SecurityException se) {
            // Expected
        }

        mBinder.removeAll(TEST_CALLING_PACKAGE);
        assertEquals(24, mService.mAlarmStore.size());
        assertEquals(0, mService.mAlarmStore.getCount(a -> a.matches(TEST_CALLING_PACKAGE)));

        mTestCallingUid = otherUid1;
        mBinder.removeAll(otherPackage1);
        assertEquals(15, mService.mAlarmStore.size());
        assertEquals(15, mService.mAlarmStore.getCount(a -> a.matches(otherPackage2)));
        assertEquals(0, mService.mAlarmStore.getCount(a -> a.matches(otherPackage1)));

        mBinder.removeAll(otherPackage2);
        assertEquals(7, mService.mAlarmStore.size());
        assertEquals(7, mService.mAlarmStore.getCount(a -> a.matches(otherPackage2)));

        mTestCallingUid = otherUid2;
        mBinder.removeAll(otherPackage2);
        assertEquals(0, mService.mAlarmStore.size());
    }

    @Test
    public void minWindowChangeEnabled() {
        mockChangeEnabled(AlarmManager.ENFORCE_MINIMUM_WINDOW_ON_INEXACT_ALARMS, true);
        final int minWindow = 73;
        setDeviceConfigLong(KEY_MIN_WINDOW, minWindow);

        final Random random = new Random(42);

        // 0 is WINDOW_EXACT and < 0 is WINDOW_HEURISTIC.
        for (int window = 1; window <= minWindow; window++) {
            final PendingIntent pi = getNewMockPendingIntent();
            final long futurity = random.nextInt(minWindow);

            setTestAlarm(ELAPSED_REALTIME, mNowElapsedTest + futurity, window, pi, 0, 0,
                    TEST_CALLING_UID, null);

            final long minAllowed = (long) (futurity * 0.75); // This will always be <= minWindow.

            assertEquals(1, mService.mAlarmStore.size());
            final Alarm a = mService.mAlarmStore.remove(unused -> true).get(0);
            assertEquals(Math.max(minAllowed, window), a.windowLength);
        }

        for (int window = 1; window <= minWindow; window++) {
            final PendingIntent pi = getNewMockPendingIntent();
            final long futurity = 2 * minWindow + window;  // implies (0.75 * futurity) > minWindow

            setTestAlarm(ELAPSED_REALTIME, mNowElapsedTest + futurity, window, pi, 0, 0,
                    TEST_CALLING_UID, null);

            assertEquals(1, mService.mAlarmStore.size());
            final Alarm a = mService.mAlarmStore.remove(unused -> true).get(0);
            assertEquals(minWindow, a.windowLength);
        }

        for (int i = 0; i < 20; i++) {
            final long window = minWindow + random.nextInt(100);
            final PendingIntent pi = getNewMockPendingIntent();
            setTestAlarm(ELAPSED_REALTIME, 0, window, pi, 0, 0, TEST_CALLING_UID, null);

            assertEquals(1, mService.mAlarmStore.size());
            final Alarm a = mService.mAlarmStore.remove(unused -> true).get(0);
            assertEquals(window, a.windowLength);
        }
    }

    @Test
    public void minWindowChangeDisabled() {
        mockChangeEnabled(AlarmManager.ENFORCE_MINIMUM_WINDOW_ON_INEXACT_ALARMS, false);
        final long minWindow = 73;
        final long futurity = 10_000;
        setDeviceConfigLong(KEY_MIN_WINDOW, minWindow);

        // 0 is WINDOW_EXACT and < 0 is WINDOW_HEURISTIC.
        for (int window = 1; window <= minWindow; window++) {
            final PendingIntent pi = getNewMockPendingIntent();
            setTestAlarm(ELAPSED_REALTIME, mNowElapsedTest + futurity, window, pi, 0, 0,
                    TEST_CALLING_UID, null);

            assertEquals(1, mService.mAlarmStore.size());
            final Alarm a = mService.mAlarmStore.remove(unused -> true).get(0);
            assertEquals(window, a.windowLength);
        }
    }

    @Test
    public void minWindowExempted() {
        mockChangeEnabled(AlarmManager.ENFORCE_MINIMUM_WINDOW_ON_INEXACT_ALARMS, true);
        final long minWindow = 73;
        final long futurity = 10_000;

        setDeviceConfigLong(KEY_MIN_WINDOW, minWindow);

        final int coreUid = 2312;
        doReturn(true).when(() -> UserHandle.isCore(coreUid));

        final int allowlisted = 54239;
        when(mDeviceIdleInternal.isAppOnWhitelist(UserHandle.getAppId(allowlisted))).thenReturn(
                true);

        for (final int callingUid : new int[]{SYSTEM_UI_UID, coreUid, coreUid}) {
            // 0 is WINDOW_EXACT and < 0 is WINDOW_HEURISTIC.
            for (int window = 1; window <= minWindow; window++) {
                final PendingIntent pi = getNewMockPendingIntent();
                setTestAlarm(ELAPSED_REALTIME, mNowElapsedTest + futurity, window, pi, 0, 0,
                        callingUid, null);

                assertEquals(1, mService.mAlarmStore.size());
                final Alarm a = mService.mAlarmStore.remove(unused -> true).get(0);
                assertEquals(window, a.windowLength);
            }
        }

        // 0 is WINDOW_EXACT and < 0 is WINDOW_HEURISTIC.
        for (int window = 1; window <= minWindow; window++) {
            final PendingIntent pi = getNewMockPendingIntent();
            setTestAlarm(ELAPSED_REALTIME, mNowElapsedTest + futurity, window, pi, 0, 0,
                    TEST_CALLING_UID, null);

            assertEquals(1, mService.mAlarmStore.size());
            final Alarm a = mService.mAlarmStore.remove(unused -> true).get(0);
            assertEquals(minWindow, a.windowLength);
        }
    }

    @Test
    public void minWindowPriorityAlarm() {
        mockChangeEnabled(AlarmManager.ENFORCE_MINIMUM_WINDOW_ON_INEXACT_ALARMS, true);
        final long minWindow = 73;
        final long futurity = 10_000;
        setDeviceConfigLong(KEY_MIN_WINDOW, minWindow);

        // 0 is WINDOW_EXACT and < 0 is WINDOW_HEURISTIC.
        for (int window = 1; window <= minWindow; window++) {
            setPrioritizedAlarm(ELAPSED_REALTIME, mNowElapsedTest + futurity, window,
                    new IAlarmListener.Stub() {
                        @Override
                        public void doAlarm(IAlarmCompleteListener callback)
                                throws RemoteException {
                        }
                    });
            assertEquals(1, mService.mAlarmStore.size());
            final Alarm a = mService.mAlarmStore.remove(unused -> true).get(0);
            assertEquals(window, a.windowLength);
        }
    }

    @Test
    public void opChangedPermissionRevoked() throws Exception {
        mockChangeEnabled(AlarmManager.REQUIRE_EXACT_ALARM_PERMISSION, true);

        mService.mLastOpScheduleExactAlarm.put(TEST_CALLING_UID, MODE_ALLOWED);
        mockScheduleExactAlarmStatePreT(true, MODE_ERRORED);

        mIAppOpsCallback.opChanged(OP_SCHEDULE_EXACT_ALARM, TEST_CALLING_UID, TEST_CALLING_PACKAGE,
                VirtualDeviceManager.PERSISTENT_DEVICE_ID_DEFAULT);
        assertAndHandleMessageSync(REMOVE_EXACT_ALARMS);
        verify(mService).removeExactAlarmsOnPermissionRevoked(TEST_CALLING_UID,
                TEST_CALLING_PACKAGE, true);
    }

    @Test
    public void opChangedChangeDisabled() throws Exception {
        mockChangeEnabled(AlarmManager.REQUIRE_EXACT_ALARM_PERMISSION, false);

        mService.mLastOpScheduleExactAlarm.put(TEST_CALLING_UID, MODE_ALLOWED);
        mockScheduleExactAlarmStatePreT(true, MODE_ERRORED);

        mIAppOpsCallback.opChanged(OP_SCHEDULE_EXACT_ALARM, TEST_CALLING_UID, TEST_CALLING_PACKAGE,
                VirtualDeviceManager.PERSISTENT_DEVICE_ID_DEFAULT);

        verify(mService.mHandler, never()).sendMessageAtTime(
                argThat(m -> m.what == REMOVE_EXACT_ALARMS), anyLong());
    }

    @Test
    public void opChangedPermissionGranted() throws Exception {
        mockChangeEnabled(AlarmManager.REQUIRE_EXACT_ALARM_PERMISSION, true);

        final long durationMs = 20000L;
        when(mActivityManagerInternal.getBootTimeTempAllowListDuration()).thenReturn(durationMs);

        mService.mLastOpScheduleExactAlarm.put(TEST_CALLING_UID, MODE_ERRORED);
        mockScheduleExactAlarmStatePreT(true, MODE_ALLOWED);
        mIAppOpsCallback.opChanged(OP_SCHEDULE_EXACT_ALARM, TEST_CALLING_UID, TEST_CALLING_PACKAGE,
                VirtualDeviceManager.PERSISTENT_DEVICE_ID_DEFAULT);

        final ArgumentCaptor<Intent> intentCaptor = ArgumentCaptor.forClass(Intent.class);
        final ArgumentCaptor<Bundle> bundleCaptor = ArgumentCaptor.forClass(Bundle.class);

        verify(mMockContext).sendBroadcastAsUser(intentCaptor.capture(), eq(UserHandle.SYSTEM),
                isNull(), bundleCaptor.capture());

        // Validate the intent.
        assertEquals(AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED,
                intentCaptor.getValue().getAction());
        assertEquals(TEST_CALLING_PACKAGE, intentCaptor.getValue().getPackage());

        // Validate the options.
        final BroadcastOptions bOptions = new BroadcastOptions(bundleCaptor.getValue());
        assertEquals(TEMPORARY_ALLOWLIST_TYPE_FOREGROUND_SERVICE_ALLOWED,
                bOptions.getTemporaryAppAllowlistType());
        assertEquals(durationMs, bOptions.getTemporaryAppAllowlistDuration());
        assertEquals(PowerExemptionManager.REASON_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED,
                bOptions.getTemporaryAppAllowlistReasonCode());
    }

    @Test
    public void removeExactAlarmsOnPermissionRevoked() {
        mockChangeEnabled(AlarmManager.REQUIRE_EXACT_ALARM_PERMISSION, true);

        // basic exact alarm
        setTestAlarm(ELAPSED_REALTIME, 0, 0, getNewMockPendingIntent(), 0, 0, TEST_CALLING_UID,
                null);
        // exact and allow-while-idle alarm
        setTestAlarm(ELAPSED_REALTIME, 0, 0, getNewMockPendingIntent(), 0, FLAG_ALLOW_WHILE_IDLE,
                TEST_CALLING_UID, null);
        // alarm clock
        setWakeFromIdle(RTC_WAKEUP, 0, getNewMockPendingIntent());

        final PendingIntent inexact = getNewMockPendingIntent();
        setTestAlarm(ELAPSED_REALTIME, 0, 10, inexact, 0, 0, TEST_CALLING_UID, null);

        final PendingIntent inexactAwi = getNewMockPendingIntent();
        setTestAlarm(ELAPSED_REALTIME, 0, 10, inexactAwi, 0, FLAG_ALLOW_WHILE_IDLE,
                TEST_CALLING_UID, null);

        final PendingIntent exactButDifferentUid = getNewMockPendingIntent();
        setTestAlarm(ELAPSED_REALTIME, 0, 0, exactButDifferentUid, 0, 0, TEST_CALLING_UID + 5,
                null);
        assertEquals(6, mService.mAlarmStore.size());

        mService.removeExactAlarmsOnPermissionRevoked(TEST_CALLING_UID, TEST_CALLING_PACKAGE,
                true);

        final ArrayList<Alarm> remaining = mService.mAlarmStore.asList();
        assertEquals(3, remaining.size());
        assertTrue("Basic inexact alarm removed",
                remaining.removeIf(a -> a.matches(inexact, null)));
        assertTrue("Inexact allow-while-idle alarm removed",
                remaining.removeIf(a -> a.matches(inexactAwi, null)));
        assertTrue("Alarm from different uid removed",
                remaining.removeIf(a -> a.matches(exactButDifferentUid, null)));

        // Mock should return false by default.
        verify(mDeviceIdleInternal, atLeastOnce()).isAppOnWhitelist(
                UserHandle.getAppId(TEST_CALLING_UID));

        verify(() -> PermissionManagerService.killUid(eq(TEST_CALLING_UID), eq(TEST_CALLING_USER),
                anyString()));
    }

    private void optionsSentOnExpiration(boolean isActivity, Bundle idleOptions)
            throws Exception {
        final long triggerTime = mNowElapsedTest + 5000;
        final PendingIntent alarmPi = getNewMockPendingIntent(isActivity);
        setTestAlarm(ELAPSED_REALTIME_WAKEUP, triggerTime, 0, alarmPi, 0, 0, TEST_CALLING_UID,
                idleOptions);

        mNowElapsedTest = mTestTimer.getElapsed();
        mTestTimer.expire();

        ArgumentCaptor<Bundle> bundleCaptor = ArgumentCaptor.forClass(Bundle.class);
        verify(alarmPi).send(eq(mMockContext), eq(0), any(Intent.class),
                any(), any(Handler.class), isNull(), bundleCaptor.capture());
        Bundle bundle = bundleCaptor.getValue();
        if (idleOptions != null) {
            assertEquals(idleOptions, bundle);
        } else {
            ActivityOptions options = ActivityOptions.fromBundle(bundle);
            assertEquals("BAL should not be allowed in alarm manager",
                    ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_DENIED,
                    options.getPendingIntentBackgroundActivityStartMode());
        }
    }

    @Test
    public void activityIdleOptionsSentOnExpiration() throws Exception {
        final Bundle idleOptions = new Bundle();
        idleOptions.putChar("TEST_CHAR_KEY", 'x');
        idleOptions.putInt("TEST_INT_KEY", 53);
        optionsSentOnExpiration(true, idleOptions);
    }

    @Test
    public void broadcastIdleOptionsSentOnExpiration() throws Exception {
        final Bundle idleOptions = new Bundle();
        idleOptions.putChar("TEST_CHAR_KEY", 'x');
        idleOptions.putInt("TEST_INT_KEY", 53);
        optionsSentOnExpiration(false, idleOptions);
    }

    @Test
    public void emptyActivityOptionsSentOnExpiration() throws Exception {
        optionsSentOnExpiration(true, null);
    }

    @Test
    public void emptyBroadcastOptionsSentOnExpiration() throws Exception {
        optionsSentOnExpiration(false, null);
    }

    private void registerAppIds(String[] packages, Integer[] ids) {
        assertEquals(packages.length, ids.length);

        when(mPackageManagerInternal.getPackageUid(anyString(), anyLong(), anyInt())).thenAnswer(
                invocation -> {
                    final String pkg = invocation.getArgument(0);
                    final int index = ArrayUtils.indexOf(packages, pkg);
                    if (index < 0) {
                        return index;
                    }
                    final int userId = invocation.getArgument(2);
                    return UserHandle.getUid(userId, ids[index]);
                });
    }

    @Test
    public void onLastOpScheduleExactAlarmOnUserStart() {
        final int userId = 54;
        SystemService.TargetUser mockTargetUser = mock(SystemService.TargetUser.class);
        when(mockTargetUser.getUserIdentifier()).thenReturn(userId);

        final Integer[] appIds = new Integer[]{43, 254, 7731};
        final int unknownAppId = 2347;
        final String[] packageNames = new String[]{"p43", "p254", "p7731"};
        final int[] appOpModes = new int[]{MODE_ALLOWED, MODE_IGNORED, MODE_ERRORED};
        mService.mExactAlarmCandidates = new ArraySet<>(appIds);
        mService.mExactAlarmCandidates.add(unknownAppId);

        for (int i = 0; i < appIds.length; i++) {
            final int uid = UserHandle.getUid(userId, appIds[i]);
            final AndroidPackage pkg = mock(AndroidPackage.class);
            when(pkg.getPackageName()).thenReturn(packageNames[i]);

            when(mPackageManagerInternal.getPackage(uid)).thenReturn(pkg);
            when(mAppOpsManager.checkOpNoThrow(OP_SCHEDULE_EXACT_ALARM, uid,
                    packageNames[i])).thenReturn(appOpModes[i]);
        }

        final ArgumentCaptor<Runnable> runnableCaptor = ArgumentCaptor.forClass(Runnable.class);
        doReturn(true).when(mService.mHandler).post(runnableCaptor.capture());

        mService.onUserStarting(mockTargetUser);
        runnableCaptor.getValue().run();

        assertEquals(appIds.length, mService.mLastOpScheduleExactAlarm.size());
        for (int i = 0; i < appIds.length; i++) {
            final int uid = UserHandle.getUid(userId, appIds[i]);
            assertEquals(appOpModes[i], mService.mLastOpScheduleExactAlarm.get(uid, -1));
        }
        assertTrue(mService.mLastOpScheduleExactAlarm.indexOfKey(
                UserHandle.getUid(userId, unknownAppId)) < 0);
    }

    @Test
    public void refreshExactAlarmCandidatesOnPackageAdded() {
        final String[] exactAlarmRequesters = new String[]{"p11", "p2", "p9"};
        final Integer[] appIds = new Integer[]{11, 2, 9};
        registerAppIds(exactAlarmRequesters, appIds);

        when(mPermissionManagerInternal.getAppOpPermissionPackages(
                SCHEDULE_EXACT_ALARM)).thenReturn(exactAlarmRequesters);

        final Intent packageAdded = new Intent(Intent.ACTION_PACKAGE_ADDED)
                .setPackage(TEST_CALLING_PACKAGE);
        mPackageChangesReceiver.onReceive(mMockContext, packageAdded);

        assertAndHandleMessageSync(REFRESH_EXACT_ALARM_CANDIDATES);
        assertEquals(new ArraySet<>(appIds), mService.mExactAlarmCandidates);
    }

    @Test
    public void refreshExactAlarmCandidatesOnPackageReplaced() {
        final String[] exactAlarmRequesters = new String[]{"p15", "p21", "p3"};
        final Integer[] appIds = new Integer[]{15, 21, 3};
        registerAppIds(exactAlarmRequesters, appIds);

        when(mPermissionManagerInternal.getAppOpPermissionPackages(
                SCHEDULE_EXACT_ALARM)).thenReturn(exactAlarmRequesters);

        final Intent packageAdded = new Intent(Intent.ACTION_PACKAGE_ADDED)
                .setData(Uri.fromParts("package", TEST_CALLING_PACKAGE, null))
                .putExtra(Intent.EXTRA_REPLACING, true);
        mPackageChangesReceiver.onReceive(mMockContext, packageAdded);

        assertAndHandleMessageSync(REFRESH_EXACT_ALARM_CANDIDATES);
        assertEquals(new ArraySet<>(appIds), mService.mExactAlarmCandidates);
    }

    @Test
    public void refreshExactAlarmCandidatesOnPackageRemoved() {
        final String[] exactAlarmRequesters = new String[]{"p99", "p1", "p19"};
        final Integer[] appIds = new Integer[]{99, 1, 19};
        registerAppIds(exactAlarmRequesters, appIds);

        when(mPermissionManagerInternal.getAppOpPermissionPackages(
                SCHEDULE_EXACT_ALARM)).thenReturn(exactAlarmRequesters);

        final Intent packageRemoved = new Intent(Intent.ACTION_PACKAGE_REMOVED)
                .setPackage(TEST_CALLING_PACKAGE);
        mPackageChangesReceiver.onReceive(mMockContext, packageRemoved);

        assertAndHandleMessageSync(REFRESH_EXACT_ALARM_CANDIDATES);
        assertEquals(new ArraySet<>(appIds), mService.mExactAlarmCandidates);
    }

    @Test
    public void exactAlarmsRemovedIfNeededOnPackageReplaced() {
        mockChangeEnabled(AlarmManager.REQUIRE_EXACT_ALARM_PERMISSION, true);
        mockChangeEnabled(AlarmManager.ENABLE_USE_EXACT_ALARM, true);

        final int otherUid = 2313;
        final String otherPackage = "p1";

        final PendingIntent exactAlarm1 = getNewMockPendingIntent();
        setTestAlarm(ELAPSED_REALTIME, 1, 0, exactAlarm1, 0, 0, TEST_CALLING_UID,
                TEST_CALLING_PACKAGE, null);

        final PendingIntent exactAlarm2 = getNewMockPendingIntent();
        setTestAlarm(ELAPSED_REALTIME, 2, 0, exactAlarm2, 0, 0, TEST_CALLING_UID,
                TEST_CALLING_PACKAGE, null);

        final PendingIntent otherPackageExactAlarm = getNewMockPendingIntent(otherUid,
                otherPackage);
        setTestAlarm(ELAPSED_REALTIME, 0, 0, otherPackageExactAlarm, 0, 0, otherUid, otherPackage,
                null);

        final PendingIntent inexactAlarm = getNewMockPendingIntent();
        setTestAlarm(ELAPSED_REALTIME, 0, 23, inexactAlarm, 0, 0, TEST_CALLING_UID,
                TEST_CALLING_PACKAGE, null);

        final PendingIntent otherPackageInexactAlarm = getNewMockPendingIntent(otherUid,
                otherPackage);
        setTestAlarm(ELAPSED_REALTIME, 0, 23, otherPackageInexactAlarm, 0, 0, otherUid,
                otherPackage, null);

        assertEquals(5, mService.mAlarmStore.size());

        final Intent packageReplacedIntent = new Intent(Intent.ACTION_PACKAGE_ADDED)
                .setData(Uri.fromParts("package", TEST_CALLING_PACKAGE, null))
                .putExtra(Intent.EXTRA_UID, TEST_CALLING_UID)
                .putExtra(Intent.EXTRA_REPLACING, true);

        mockUseExactAlarmState(false);
        mockScheduleExactAlarmStatePreT(true, MODE_ALLOWED);
        mPackageChangesReceiver.onReceive(mMockContext, packageReplacedIntent);
        assertAndHandleMessageSync(CHECK_EXACT_ALARM_PERMISSION_ON_UPDATE);

        // user permission is granted, no alarms should be removed
        assertEquals(5, mService.mAlarmStore.size());

        mockUseExactAlarmState(true);
        mockScheduleExactAlarmStatePreT(true, MODE_ERRORED);
        mPackageChangesReceiver.onReceive(mMockContext, packageReplacedIntent);
        assertAndHandleMessageSync(CHECK_EXACT_ALARM_PERMISSION_ON_UPDATE);

        // policy permission is granted, no alarms should be removed
        assertEquals(5, mService.mAlarmStore.size());

        mockUseExactAlarmState(false);
        mockScheduleExactAlarmStatePreT(true, MODE_ERRORED);
        mPackageChangesReceiver.onReceive(mMockContext, packageReplacedIntent);
        assertAndHandleMessageSync(CHECK_EXACT_ALARM_PERMISSION_ON_UPDATE);

        // no permission is granted, exact alarms should be removed
        assertEquals(3, mService.mAlarmStore.size());

        List<Alarm> remaining = mService.mAlarmStore.asList();
        assertTrue("Inexact alarm removed", remaining.removeIf(a -> a.matches(inexactAlarm, null)));

        assertEquals(2, remaining.size());
        assertTrue("Alarms from other package removed",
                remaining.removeIf(a -> a.matches(otherPackageExactAlarm, null)
                        || a.matches(otherPackageInexactAlarm, null)));

        assertEquals(0, remaining.size());
    }

    @Test
    public void alarmScheduledAtomPushed() {
        for (int i = 0; i < 10; i++) {
            final PendingIntent pi = getNewMockPendingIntent();
            setTestAlarm(ELAPSED_REALTIME, mNowElapsedTest + i, pi);

            verify(() -> MetricsHelper.pushAlarmScheduled(argThat(a -> a.matches(pi, null)),
                    anyInt()));
        }
    }

    @Test
    public void alarmBatchDeliveredAtomPushed() throws InterruptedException {
        for (int i = 0; i < 10; i++) {
            final int type = ((i & 1) == 0) ? ELAPSED_REALTIME : ELAPSED_REALTIME_WAKEUP;
            setTestAlarm(type, mNowElapsedTest + i, getNewMockPendingIntent());
        }
        for (int i = 0; i < 4; i++) {
            final int type = ((i & 1) == 0) ? ELAPSED_REALTIME : ELAPSED_REALTIME_WAKEUP;
            setTestAlarm(
                    type,
                    mNowElapsedTest + i,
                    getNewMockPendingIntent(),
                    0,
                    FLAG_STANDALONE,
                    TEST_CALLING_UID_2);
        }
        mNowElapsedTest += 100;
        mTestTimer.expire();

        final ArgumentCaptor<int[]> uidsCaptor = ArgumentCaptor.forClass(int[].class);
        final ArgumentCaptor<int[]> alarmsPerUidCaptor = ArgumentCaptor.forClass(int[].class);
        final ArgumentCaptor<int[]> wakeupAlarmsPerUidCaptor = ArgumentCaptor.forClass(int[].class);

        verify(() -> MetricsHelper.pushAlarmBatchDelivered(
                eq(14),
                eq(7),
                uidsCaptor.capture(),
                alarmsPerUidCaptor.capture(),
                wakeupAlarmsPerUidCaptor.capture()));
        assertEquals(2, uidsCaptor.getValue().length);
        assertEquals(2, alarmsPerUidCaptor.getValue().length);
        assertEquals(2, wakeupAlarmsPerUidCaptor.getValue().length);
        final int uid1Idx = uidsCaptor.getValue()[0] == TEST_CALLING_UID ? 0 : 1;
        final int uid2Idx = 1 - uid1Idx;
        assertEquals(TEST_CALLING_UID, uidsCaptor.getValue()[uid1Idx]);
        assertEquals(TEST_CALLING_UID_2, uidsCaptor.getValue()[uid2Idx]);
        assertEquals(10, alarmsPerUidCaptor.getValue()[uid1Idx]);
        assertEquals(5, wakeupAlarmsPerUidCaptor.getValue()[uid1Idx]);
        assertEquals(4, alarmsPerUidCaptor.getValue()[uid2Idx]);
        assertEquals(2, wakeupAlarmsPerUidCaptor.getValue()[uid2Idx]);
    }

    @Test
    public void setTimeZoneImpl() {
        final long durationMs = 20000L;
        when(mActivityManagerInternal.getBootTimeTempAllowListDuration()).thenReturn(durationMs);
        mService.setTimeZoneImpl("UTC", TIME_ZONE_CONFIDENCE_HIGH, "AlarmManagerServiceTest");
        final ArgumentCaptor<Intent> intentCaptor = ArgumentCaptor.forClass(Intent.class);
        final ArgumentCaptor<Bundle> bundleCaptor = ArgumentCaptor.forClass(Bundle.class);
        verify(mMockContext).sendBroadcastAsUser(intentCaptor.capture(), eq(UserHandle.ALL),
                isNull(), bundleCaptor.capture());
        assertEquals(Intent.ACTION_TIMEZONE_CHANGED, intentCaptor.getValue().getAction());
        assertEquals("UTC", intentCaptor.getValue().getStringExtra(Intent.EXTRA_TIMEZONE));
        final BroadcastOptions bOptions = new BroadcastOptions(bundleCaptor.getValue());
        assertEquals(durationMs, bOptions.getTemporaryAppAllowlistDuration());
        assertEquals(TEMPORARY_ALLOWLIST_TYPE_FOREGROUND_SERVICE_ALLOWED,
                bOptions.getTemporaryAppAllowlistType());
        assertEquals(PowerExemptionManager.REASON_TIMEZONE_CHANGED,
                bOptions.getTemporaryAppAllowlistReasonCode());
        assertEquals(BroadcastOptions.DELIVERY_GROUP_POLICY_MOST_RECENT,
                bOptions.getDeliveryGroupPolicy());
    }

    @Test
    public void hasUseExactAlarmInternal() {
        mockChangeEnabled(AlarmManager.ENABLE_USE_EXACT_ALARM, true);

        mockUseExactAlarmState(true);
        assertTrue(mService.hasUseExactAlarmInternal(TEST_CALLING_PACKAGE, TEST_CALLING_UID));

        mockUseExactAlarmState(false);
        assertFalse(mService.hasUseExactAlarmInternal(TEST_CALLING_PACKAGE, TEST_CALLING_UID));
    }

    @Test
    public void hasUseExactAlarmInternalChangeDisabled() {
        mockChangeEnabled(AlarmManager.ENABLE_USE_EXACT_ALARM, false);

        mockUseExactAlarmState(true);
        assertFalse(mService.hasUseExactAlarmInternal(TEST_CALLING_PACKAGE, TEST_CALLING_UID));

        mockUseExactAlarmState(false);
        assertFalse(mService.hasUseExactAlarmInternal(TEST_CALLING_PACKAGE, TEST_CALLING_UID));
    }

    @Test
    public void hasScheduleExactAlarmInternal() {
        mockChangeEnabled(AlarmManager.REQUIRE_EXACT_ALARM_PERMISSION, true);
        mockChangeEnabled(AlarmManager.SCHEDULE_EXACT_ALARM_DENIED_BY_DEFAULT, true);

        mockScheduleExactAlarmState(false);
        assertFalse(mService.hasScheduleExactAlarmInternal(TEST_CALLING_PACKAGE, TEST_CALLING_UID));

        mockScheduleExactAlarmState(true);
        assertTrue(mService.hasScheduleExactAlarmInternal(TEST_CALLING_PACKAGE, TEST_CALLING_UID));
    }

    @Test
    public void hasScheduleExactAlarmInternalPreT() {
        mockChangeEnabled(AlarmManager.REQUIRE_EXACT_ALARM_PERMISSION, true);
        mockChangeEnabled(AlarmManager.SCHEDULE_EXACT_ALARM_DENIED_BY_DEFAULT, false);

        mockScheduleExactAlarmStatePreT(true, MODE_DEFAULT);
        assertTrue(mService.hasScheduleExactAlarmInternal(TEST_CALLING_PACKAGE, TEST_CALLING_UID));

        mockScheduleExactAlarmStatePreT(false, MODE_ALLOWED);
        assertFalse(mService.hasScheduleExactAlarmInternal(TEST_CALLING_PACKAGE, TEST_CALLING_UID));

        mockScheduleExactAlarmStatePreT(true, MODE_ERRORED);
        assertFalse(mService.hasScheduleExactAlarmInternal(TEST_CALLING_PACKAGE, TEST_CALLING_UID));

        mockScheduleExactAlarmStatePreT(true, MODE_ALLOWED);
        assertTrue(mService.hasScheduleExactAlarmInternal(TEST_CALLING_PACKAGE, TEST_CALLING_UID));
    }

    @Test
    public void hasScheduleExactAlarmInternalPreS() {
        mockChangeEnabled(AlarmManager.REQUIRE_EXACT_ALARM_PERMISSION, false);

        mockScheduleExactAlarmState(true);
        mockScheduleExactAlarmStatePreT(true, MODE_ALLOWED);
        assertFalse(mService.hasScheduleExactAlarmInternal(TEST_CALLING_PACKAGE, TEST_CALLING_UID));

        mockScheduleExactAlarmState(false);
        mockScheduleExactAlarmStatePreT(true, MODE_ERRORED);
        assertFalse(mService.hasScheduleExactAlarmInternal(TEST_CALLING_PACKAGE, TEST_CALLING_UID));
    }

    @Test
    public void temporaryQuotaReserve_hasQuota() {
        final int quotaToFill = 5;
        final String package1 = "package1";
        final int user1 = 123;
        final long startTime = 54;
        final long quotaDuration = 17;

        final AlarmManagerService.TemporaryQuotaReserve quotaReserve =
                new AlarmManagerService.TemporaryQuotaReserve(quotaDuration);
        quotaReserve.replenishQuota(package1, user1, quotaToFill, startTime);

        for (long time = startTime; time <= startTime + quotaDuration; time++) {
            assertTrue(quotaReserve.hasQuota(package1, user1, time));
            assertFalse(quotaReserve.hasQuota("some.other.package", 21, time));
            assertFalse(quotaReserve.hasQuota(package1, 321, time));
        }

        assertFalse(quotaReserve.hasQuota(package1, user1, startTime + quotaDuration + 1));
        assertFalse(quotaReserve.hasQuota(package1, user1, startTime + quotaDuration + 435421));

        for (int i = 0; i < quotaToFill - 1; i++) {
            assertTrue(i < quotaDuration);
            // Use record usage multiple times with the same timestamp.
            quotaReserve.recordUsage(package1, user1, startTime + i);
            quotaReserve.recordUsage(package1, user1, startTime + i);
            quotaReserve.recordUsage(package1, user1, startTime + i);
            quotaReserve.recordUsage(package1, user1, startTime + i);

            // Quota should not run out in this loop.
            assertTrue(quotaReserve.hasQuota(package1, user1, startTime + i));
        }
        quotaReserve.recordUsage(package1, user1, startTime + quotaDuration);

        // Should be out of quota now.
        for (long time = startTime; time <= startTime + quotaDuration; time++) {
            assertFalse(quotaReserve.hasQuota(package1, user1, time));
        }
    }

    @Test
    public void temporaryQuotaReserve_removeForPackage() {
        final String[] packages = new String[]{"package1", "test.package2"};
        final int userId = 472;
        final long startTime = 59;
        final long quotaDuration = 100;

        final AlarmManagerService.TemporaryQuotaReserve quotaReserve =
                new AlarmManagerService.TemporaryQuotaReserve(quotaDuration);

        quotaReserve.replenishQuota(packages[0], userId, 10, startTime);
        quotaReserve.replenishQuota(packages[1], userId, 10, startTime);

        assertTrue(quotaReserve.hasQuota(packages[0], userId, startTime + 1));
        assertTrue(quotaReserve.hasQuota(packages[1], userId, startTime + 1));

        quotaReserve.removeForPackage(packages[0], userId);

        assertFalse(quotaReserve.hasQuota(packages[0], userId, startTime + 1));
        assertTrue(quotaReserve.hasQuota(packages[1], userId, startTime + 1));
    }

    @Test
    public void temporaryQuotaReserve_removeForUser() {
        final String[] packagesUser1 = new String[]{"test1.package1", "test1.package2"};
        final String[] packagesUser2 = new String[]{"test2.p1", "test2.p2", "test2.p3"};
        final int user1 = 3201;
        final int user2 = 5409;
        final long startTime = 59;
        final long quotaDuration = 100;

        final AlarmManagerService.TemporaryQuotaReserve quotaReserve =
                new AlarmManagerService.TemporaryQuotaReserve(quotaDuration);

        for (String packageUser1 : packagesUser1) {
            quotaReserve.replenishQuota(packageUser1, user1, 10, startTime);
        }
        for (String packageUser2 : packagesUser2) {
            quotaReserve.replenishQuota(packageUser2, user2, 10, startTime);
        }

        for (String packageUser1 : packagesUser1) {
            assertTrue(quotaReserve.hasQuota(packageUser1, user1, startTime));
        }
        for (String packageUser2 : packagesUser2) {
            assertTrue(quotaReserve.hasQuota(packageUser2, user2, startTime));
        }

        quotaReserve.removeForUser(user2);

        for (String packageUser1 : packagesUser1) {
            assertTrue(quotaReserve.hasQuota(packageUser1, user1, startTime));
        }
        for (String packageUser2 : packagesUser2) {
            assertFalse(quotaReserve.hasQuota(packageUser2, user2, startTime));
        }
    }

    @Test
    public void triggerTemporaryQuotaBump_zeroQuota() {
        setDeviceConfigInt(KEY_TEMPORARY_QUOTA_BUMP, 0);

        mAppStandbyListener.triggerTemporaryQuotaBump(TEST_CALLING_PACKAGE, TEST_CALLING_USER);
        verifyZeroInteractions(mPackageManagerInternal);
        verifyZeroInteractions(mService.mHandler);
    }

    private void testTemporaryQuota_bumpedAfterDeferral(int standbyBucket) throws Exception {
        final int temporaryQuota = 31;
        setDeviceConfigInt(KEY_TEMPORARY_QUOTA_BUMP, temporaryQuota);

        final int standbyQuota = mService.getQuotaForBucketLocked(standbyBucket);
        when(mUsageStatsManagerInternal.getAppStandbyBucket(eq(TEST_CALLING_PACKAGE), anyInt(),
                anyLong())).thenReturn(standbyBucket);

        final long firstTrigger = mNowElapsedTest + 10;
        for (int i = 0; i < standbyQuota + 1; i++) {
            setTestAlarm(ELAPSED_REALTIME_WAKEUP, firstTrigger + i, getNewMockPendingIntent());
        }

        for (int i = 0; i < standbyQuota; i++) {
            mNowElapsedTest = mTestTimer.getElapsed();
            assertEquals("Incorrect trigger time at i=" + i, firstTrigger + i, mNowElapsedTest);
            mTestTimer.expire();
        }

        // The last alarm should be deferred due to exceeding the quota
        final long deferredTrigger = firstTrigger + mAppStandbyWindow;
        assertEquals(deferredTrigger, mTestTimer.getElapsed());

        // Triggering temporary quota now.
        mAppStandbyListener.triggerTemporaryQuotaBump(TEST_CALLING_PACKAGE, TEST_CALLING_USER);
        assertAndHandleMessageSync(TEMPORARY_QUOTA_CHANGED);
        // The last alarm should now be rescheduled to go as per original expectations
        final long originalTrigger = firstTrigger + standbyQuota;
        assertEquals("Incorrect next alarm trigger", originalTrigger, mTestTimer.getElapsed());
    }


    @Test
    public void temporaryQuota_bumpedAfterDeferral_active() throws Exception {
        testTemporaryQuota_bumpedAfterDeferral(STANDBY_BUCKET_ACTIVE);
    }

    @Test
    public void temporaryQuota_bumpedAfterDeferral_working() throws Exception {
        testTemporaryQuota_bumpedAfterDeferral(STANDBY_BUCKET_WORKING_SET);
    }

    @Test
    public void temporaryQuota_bumpedAfterDeferral_frequent() throws Exception {
        testTemporaryQuota_bumpedAfterDeferral(STANDBY_BUCKET_FREQUENT);
    }

    @Test
    public void temporaryQuota_bumpedAfterDeferral_rare() throws Exception {
        testTemporaryQuota_bumpedAfterDeferral(STANDBY_BUCKET_RARE);
    }

    private void testTemporaryQuota_bumpedBeforeDeferral(int standbyBucket) throws Exception {
        final int temporaryQuota = 7;
        setDeviceConfigInt(KEY_TEMPORARY_QUOTA_BUMP, temporaryQuota);

        final int standbyQuota = mService.getQuotaForBucketLocked(standbyBucket);
        when(mUsageStatsManagerInternal.getAppStandbyBucket(eq(TEST_CALLING_PACKAGE), anyInt(),
                anyLong())).thenReturn(standbyBucket);

        mAppStandbyListener.triggerTemporaryQuotaBump(TEST_CALLING_PACKAGE, TEST_CALLING_USER);
        // No need to handle message TEMPORARY_QUOTA_CHANGED, as the quota change doesn't need to
        // trigger a re-evaluation in this test.
        testQuotasDeferralOnExpiration(trigger -> setTestAlarm(ELAPSED_REALTIME_WAKEUP, trigger,
                getNewMockPendingIntent()), standbyQuota + temporaryQuota, mAppStandbyWindow);

        // refresh the state.
        mService.removeLocked(TEST_CALLING_PACKAGE, REMOVE_REASON_UNDEFINED);
        mService.mAppWakeupHistory.removeForPackage(TEST_CALLING_PACKAGE, TEST_CALLING_USER);
        mService.mTemporaryQuotaReserve.removeForPackage(TEST_CALLING_PACKAGE, TEST_CALLING_USER);

        mAppStandbyListener.triggerTemporaryQuotaBump(TEST_CALLING_PACKAGE, TEST_CALLING_USER);
        testQuotasDeferralOnSet(trigger -> setTestAlarm(ELAPSED_REALTIME_WAKEUP, trigger,
                getNewMockPendingIntent()), standbyQuota + temporaryQuota, mAppStandbyWindow);

        // refresh the state.
        mService.removeLocked(TEST_CALLING_PACKAGE, REMOVE_REASON_UNDEFINED);
        mService.mAppWakeupHistory.removeForPackage(TEST_CALLING_PACKAGE, TEST_CALLING_USER);
        mService.mTemporaryQuotaReserve.removeForPackage(TEST_CALLING_PACKAGE, TEST_CALLING_USER);

        mAppStandbyListener.triggerTemporaryQuotaBump(TEST_CALLING_PACKAGE, TEST_CALLING_USER);
        testQuotasNoDeferral(trigger -> setTestAlarm(ELAPSED_REALTIME_WAKEUP, trigger,
                getNewMockPendingIntent()), standbyQuota + temporaryQuota, mAppStandbyWindow);
    }

    @Test
    public void temporaryQuota_bumpedBeforeDeferral_active() throws Exception {
        testTemporaryQuota_bumpedBeforeDeferral(STANDBY_BUCKET_ACTIVE);
    }

    @Test
    public void temporaryQuota_bumpedBeforeDeferral_working() throws Exception {
        testTemporaryQuota_bumpedBeforeDeferral(STANDBY_BUCKET_WORKING_SET);
    }

    @Test
    public void temporaryQuota_bumpedBeforeDeferral_frequent() throws Exception {
        testTemporaryQuota_bumpedBeforeDeferral(STANDBY_BUCKET_FREQUENT);
    }

    @Test
    public void temporaryQuota_bumpedBeforeDeferral_rare() throws Exception {
        testTemporaryQuota_bumpedBeforeDeferral(STANDBY_BUCKET_RARE);
    }

    private void executeUidFrozenStateCallback(int[] uids, int[] frozenStates) {
        assertNotNull(mUidFrozenStateCallback);
        mUidFrozenStateCallback.onUidFrozenStateChanged(uids, frozenStates);
    }

    @DisableFlags(Flags.FLAG_START_USER_BEFORE_SCHEDULED_ALARMS)
    @Test
    public void exactListenerAlarmsRemovedOnFrozen() {
        mockChangeEnabled(EXACT_LISTENER_ALARMS_DROPPED_ON_CACHED, true);

        setTestAlarmWithListener(ELAPSED_REALTIME, 31, getNewListener(() -> {}), WINDOW_EXACT,
                TEST_CALLING_UID);
        setTestAlarmWithListener(RTC, 42, getNewListener(() -> {}), 56, TEST_CALLING_UID);
        setTestAlarm(ELAPSED_REALTIME, 54, WINDOW_EXACT, getNewMockPendingIntent(), 0, 0,
                TEST_CALLING_UID, null);
        setTestAlarm(RTC, 49, 154, getNewMockPendingIntent(), 0, 0, TEST_CALLING_UID, null);

        setTestAlarmWithListener(ELAPSED_REALTIME, 21, getNewListener(() -> {}), WINDOW_EXACT,
                TEST_CALLING_UID_2);
        setTestAlarmWithListener(RTC, 412, getNewListener(() -> {}), 561, TEST_CALLING_UID_2);
        setTestAlarm(ELAPSED_REALTIME, 26, WINDOW_EXACT, getNewMockPendingIntent(), 0, 0,
                TEST_CALLING_UID_2, null);
        setTestAlarm(RTC, 549, 234, getNewMockPendingIntent(), 0, 0, TEST_CALLING_UID_2, null);

        assertEquals(8, mService.mAlarmStore.size());

        executeUidFrozenStateCallback(
                new int[] {TEST_CALLING_UID, TEST_CALLING_UID_2},
                new int[] {UID_FROZEN_STATE_FROZEN, UID_FROZEN_STATE_UNFROZEN});
        assertEquals(7, mService.mAlarmStore.size());

        executeUidFrozenStateCallback(
                new int[] {TEST_CALLING_UID_2}, new int[] {UID_FROZEN_STATE_FROZEN});
        assertEquals(6, mService.mAlarmStore.size());
    }

    @DisableFlags(Flags.FLAG_START_USER_BEFORE_SCHEDULED_ALARMS)
    @Test
    public void alarmCountOnListenerFrozen() {
        mockChangeEnabled(EXACT_LISTENER_ALARMS_DROPPED_ON_CACHED, true);

        // Set some alarms for TEST_CALLING_UID.
        final int numExactListenerUid1 = 17;
        for (int i = 0; i < numExactListenerUid1; i++) {
            setTestAlarmWithListener(ALARM_TYPES[i % 4], mNowElapsedTest + i,
                    getNewListener(() -> {}));
        }
        setTestAlarmWithListener(RTC, 42, getNewListener(() -> {}), 56, TEST_CALLING_UID);
        setTestAlarm(ELAPSED_REALTIME, 54, getNewMockPendingIntent());
        setTestAlarm(RTC, 49, 154, getNewMockPendingIntent(), 0, 0, TEST_CALLING_UID, null);

        // Set some alarms for TEST_CALLING_UID_2.
        final int numExactListenerUid2 = 11;
        for (int i = 0; i < numExactListenerUid2; i++) {
            setTestAlarmWithListener(ALARM_TYPES[i % 4], mNowElapsedTest + i,
                    getNewListener(() -> {}), WINDOW_EXACT, TEST_CALLING_UID_2);
        }
        setTestAlarmWithListener(RTC, 412, getNewListener(() -> {}), 561, TEST_CALLING_UID_2);
        setTestAlarm(RTC_WAKEUP, 26, WINDOW_EXACT, getNewMockPendingIntent(), 0, 0,
                TEST_CALLING_UID_2, null);

        assertEquals(numExactListenerUid1 + 3, mService.mAlarmsPerUid.get(TEST_CALLING_UID));
        assertEquals(numExactListenerUid2 + 2, mService.mAlarmsPerUid.get(TEST_CALLING_UID_2));

        executeUidFrozenStateCallback(
                new int[] {TEST_CALLING_UID, TEST_CALLING_UID_2},
                new int[] {UID_FROZEN_STATE_FROZEN, UID_FROZEN_STATE_UNFROZEN});
        assertEquals(3, mService.mAlarmsPerUid.get(TEST_CALLING_UID));
        assertEquals(numExactListenerUid2 + 2, mService.mAlarmsPerUid.get(TEST_CALLING_UID_2));

        executeUidFrozenStateCallback(
                new int[] {TEST_CALLING_UID_2}, new int[] {UID_FROZEN_STATE_FROZEN});
        assertEquals(3, mService.mAlarmsPerUid.get(TEST_CALLING_UID));
        assertEquals(2, mService.mAlarmsPerUid.get(TEST_CALLING_UID_2));
    }

    @Test
    public void lookForPackageLocked() throws Exception {
        final String package2 = "test.package.2";
        final int uid2 = 359712;
        setTestAlarm(ELAPSED_REALTIME, mNowElapsedTest + 10, getNewMockPendingIntent());
        setTestAlarm(ELAPSED_REALTIME_WAKEUP, mNowElapsedTest + 15,
                getNewMockPendingIntent(uid2, package2));

        doReturn(true).when(mService).checkAllowNonWakeupDelayLocked(anyLong());

        assertTrue(mService.lookForPackageLocked(TEST_CALLING_PACKAGE, TEST_CALLING_UID));
        assertTrue(mService.lookForPackageLocked(package2, uid2));

        mNowElapsedTest += 10;  // Advance time past the first alarm only.
        mTestTimer.expire();

        assertTrue(mService.lookForPackageLocked(TEST_CALLING_PACKAGE, TEST_CALLING_UID));
        assertTrue(mService.lookForPackageLocked(package2, uid2));

        // The non-wakeup alarm is sent on interactive state change: false -> true.
        mService.interactiveStateChangedLocked(false);
        mService.interactiveStateChangedLocked(true);

        assertFalse(mService.lookForPackageLocked(TEST_CALLING_PACKAGE, TEST_CALLING_UID));
        assertTrue(mService.lookForPackageLocked(package2, uid2));

        mNowElapsedTest += 10; // Advance time past the second alarm.
        mTestTimer.expire();

        assertFalse(mService.lookForPackageLocked(TEST_CALLING_PACKAGE, TEST_CALLING_UID));
        assertFalse(mService.lookForPackageLocked(package2, uid2));
    }

    @Test
    public void onQueryPackageRestart() {
        final String[] packages = {"p1", "p2", "p3"};
        final int uid = 5421;
        final Intent packageAdded = new Intent(Intent.ACTION_QUERY_PACKAGE_RESTART)
                .setData(Uri.fromParts("package", packages[0], null))
                .putExtra(Intent.EXTRA_PACKAGES, packages)
                .putExtra(Intent.EXTRA_UID, uid);
        mPackageChangesReceiver.onReceive(mMockContext, packageAdded);

        for (String p : packages) {
            verify(mService).lookForPackageLocked(p, uid);
        }
    }
}
