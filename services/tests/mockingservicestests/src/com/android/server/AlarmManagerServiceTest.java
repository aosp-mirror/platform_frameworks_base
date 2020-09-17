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

import static android.app.AlarmManager.ELAPSED_REALTIME;
import static android.app.AlarmManager.ELAPSED_REALTIME_WAKEUP;
import static android.app.AlarmManager.RTC;
import static android.app.AlarmManager.RTC_WAKEUP;
import static android.app.usage.UsageStatsManager.STANDBY_BUCKET_ACTIVE;
import static android.app.usage.UsageStatsManager.STANDBY_BUCKET_FREQUENT;
import static android.app.usage.UsageStatsManager.STANDBY_BUCKET_RARE;
import static android.app.usage.UsageStatsManager.STANDBY_BUCKET_RESTRICTED;
import static android.app.usage.UsageStatsManager.STANDBY_BUCKET_WORKING_SET;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.doCallRealMethod;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.doNothing;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.doThrow;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.mock;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.mockitoSession;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.spyOn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.verify;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.when;
import static com.android.server.AlarmManagerService.ACTIVE_INDEX;
import static com.android.server.AlarmManagerService.AlarmHandler.APP_STANDBY_BUCKET_CHANGED;
import static com.android.server.AlarmManagerService.AlarmHandler.CHARGING_STATUS_CHANGED;
import static com.android.server.AlarmManagerService.AlarmHandler.REMOVE_FOR_CANCELED;
import static com.android.server.AlarmManagerService.Constants.KEY_ALLOW_WHILE_IDLE_LONG_TIME;
import static com.android.server.AlarmManagerService.Constants.KEY_ALLOW_WHILE_IDLE_SHORT_TIME;
import static com.android.server.AlarmManagerService.Constants.KEY_ALLOW_WHILE_IDLE_WHITELIST_DURATION;
import static com.android.server.AlarmManagerService.Constants.KEY_LISTENER_TIMEOUT;
import static com.android.server.AlarmManagerService.Constants.KEY_MAX_INTERVAL;
import static com.android.server.AlarmManagerService.Constants.KEY_MIN_FUTURITY;
import static com.android.server.AlarmManagerService.Constants.KEY_MIN_INTERVAL;
import static com.android.server.AlarmManagerService.IS_WAKEUP_MASK;
import static com.android.server.AlarmManagerService.TIME_CHANGED_MASK;
import static com.android.server.AlarmManagerService.WORKING_INDEX;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeastOnce;

import android.app.ActivityManager;
import android.app.AlarmManager;
import android.app.IActivityManager;
import android.app.IAlarmCompleteListener;
import android.app.IAlarmListener;
import android.app.IUidObserver;
import android.app.PendingIntent;
import android.app.usage.UsageStatsManagerInternal;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.UserHandle;
import android.platform.test.annotations.Presubmit;
import android.provider.Settings;
import android.util.Log;
import android.util.SparseArray;

import androidx.test.runner.AndroidJUnit4;

import com.android.dx.mockito.inline.extended.MockedVoidMethod;
import com.android.internal.annotations.GuardedBy;
import com.android.server.usage.AppStandbyInternal;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Answers;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoSession;
import org.mockito.quality.Strictness;

import java.util.ArrayList;

@Presubmit
@RunWith(AndroidJUnit4.class)
public class AlarmManagerServiceTest {
    private static final String TAG = AlarmManagerServiceTest.class.getSimpleName();
    private static final String TEST_CALLING_PACKAGE = "com.android.framework.test-package";
    private static final int SYSTEM_UI_UID = 12345;
    private static final int TEST_CALLING_UID = 67890;
    private static final int TEST_CALLING_USER = UserHandle.getUserId(TEST_CALLING_UID);

    private long mAppStandbyWindow;
    private AlarmManagerService mService;
    private AppStandbyInternal.AppIdleStateChangeListener mAppStandbyListener;
    private AlarmManagerService.ChargingReceiver mChargingReceiver;
    @Mock
    private ContentResolver mMockResolver;
    @Mock
    private Context mMockContext;
    @Mock
    private IActivityManager mIActivityManager;
    @Mock
    private UsageStatsManagerInternal mUsageStatsManagerInternal;
    @Mock
    private AppStandbyInternal mAppStandbyInternal;
    @Mock
    private AppStateTracker mAppStateTracker;
    @Mock
    private AlarmManagerService.ClockReceiver mClockReceiver;
    @Mock
    private PowerManager.WakeLock mWakeLock;

    private MockitoSession mMockingSession;
    private Injector mInjector;
    private volatile long mNowElapsedTest;
    private volatile long mNowRtcTest;
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
        void setKernelTimezone(int minutesWest) {
            // Do nothing.
        }

        @Override
        void setAlarm(int type, long millis) {
            mTestTimer.set(type, millis);
        }

        @Override
        void setKernelTime(long millis) {
        }

        @Override
        int getSystemUiUid() {
            return SYSTEM_UI_UID;
        }

        @Override
        boolean isAlarmDriverPresent() {
            // Pretend the driver is present, so code does not fall back to handler
            return true;
        }

        @Override
        long getElapsedRealtime() {
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
    }

    @Before
    public final void setUp() throws Exception {
        mMockingSession = mockitoSession()
                .initMocks(this)
                .spyStatic(ActivityManager.class)
                .mockStatic(LocalServices.class)
                .spyStatic(Looper.class)
                .spyStatic(Settings.Global.class)
                .spyStatic(UserHandle.class)
                .strictness(Strictness.WARN)
                .startMocking();
        doReturn(mIActivityManager).when(ActivityManager::getService);
        doReturn(mAppStateTracker).when(() -> LocalServices.getService(AppStateTracker.class));
        doReturn(mAppStandbyInternal).when(
                () -> LocalServices.getService(AppStandbyInternal.class));
        doReturn(mUsageStatsManagerInternal).when(
                () -> LocalServices.getService(UsageStatsManagerInternal.class));
        doCallRealMethod().when((MockedVoidMethod) () ->
                LocalServices.addService(eq(AlarmManagerInternal.class), any()));
        doCallRealMethod().when(() -> LocalServices.getService(AlarmManagerInternal.class));
        doReturn(false).when(() -> UserHandle.isCore(TEST_CALLING_UID));
        when(mUsageStatsManagerInternal.getAppStandbyBucket(eq(TEST_CALLING_PACKAGE),
                eq(TEST_CALLING_USER), anyLong())).thenReturn(STANDBY_BUCKET_ACTIVE);
        doReturn(Looper.getMainLooper()).when(Looper::myLooper);

        when(mMockContext.getContentResolver()).thenReturn(mMockResolver);
        doReturn("min_futurity=0,min_interval=0").when(() ->
                Settings.Global.getString(mMockResolver, Settings.Global.ALARM_MANAGER_CONSTANTS));

        mInjector = new Injector(mMockContext);
        mService = new AlarmManagerService(mMockContext, mInjector);
        spyOn(mService);
        doNothing().when(mService).publishBinderService(any(), any());

        mService.onStart();
        spyOn(mService.mHandler);
        // Stubbing the handler. Test should simulate any handling of messages synchronously.
        doReturn(true).when(mService.mHandler).sendMessageAtTime(any(Message.class), anyLong());

        assertEquals(mService.mSystemUiUid, SYSTEM_UI_UID);
        assertEquals(mService.mClockReceiver, mClockReceiver);
        assertEquals(mService.mWakeLock, mWakeLock);
        verify(mIActivityManager).registerUidObserver(any(IUidObserver.class), anyInt(), anyInt(),
                isNull());

        // Other boot phases don't matter
        mService.onBootPhase(SystemService.PHASE_SYSTEM_SERVICES_READY);
        assertEquals(0, mService.mConstants.MIN_FUTURITY);
        assertEquals(0, mService.mConstants.MIN_INTERVAL);
        mAppStandbyWindow = mService.mConstants.APP_STANDBY_WINDOW;
        ArgumentCaptor<AppStandbyInternal.AppIdleStateChangeListener> captor =
                ArgumentCaptor.forClass(AppStandbyInternal.AppIdleStateChangeListener.class);
        verify(mAppStandbyInternal).addListener(captor.capture());
        mAppStandbyListener = captor.getValue();

        ArgumentCaptor<AlarmManagerService.ChargingReceiver> chargingReceiverCaptor =
                ArgumentCaptor.forClass(AlarmManagerService.ChargingReceiver.class);
        verify(mMockContext).registerReceiver(chargingReceiverCaptor.capture(),
                argThat((filter) -> filter.hasAction(BatteryManager.ACTION_CHARGING)
                        && filter.hasAction(BatteryManager.ACTION_DISCHARGING)));
        mChargingReceiver = chargingReceiverCaptor.getValue();

        setTestableQuotas();
    }

    private void setTestAlarm(int type, long triggerTime, PendingIntent operation) {
        setTestAlarm(type, triggerTime, operation, 0, TEST_CALLING_UID);
    }

    private void setRepeatingTestAlarm(int type, long firstTrigger, long interval,
            PendingIntent pi) {
        setTestAlarm(type, firstTrigger, pi, interval, TEST_CALLING_UID);
    }

    private void setTestAlarm(int type, long triggerTime, PendingIntent operation, long interval,
            int callingUid) {
        mService.setImpl(type, triggerTime, AlarmManager.WINDOW_EXACT, interval,
                operation, null, "test", AlarmManager.FLAG_STANDALONE, null, null,
                callingUid, TEST_CALLING_PACKAGE);
    }

    private void setTestAlarmWithListener(int type, long triggerTime, IAlarmListener listener) {
        mService.setImpl(type, triggerTime, AlarmManager.WINDOW_EXACT, 0,
                null, listener, "test", AlarmManager.FLAG_STANDALONE, null, null,
                TEST_CALLING_UID, TEST_CALLING_PACKAGE);
    }


    private PendingIntent getNewMockPendingIntent() {
        return getNewMockPendingIntent(TEST_CALLING_UID);
    }

    private PendingIntent getNewMockPendingIntent(int mockUid) {
        final PendingIntent mockPi = mock(PendingIntent.class, Answers.RETURNS_DEEP_STUBS);
        when(mockPi.getCreatorUid()).thenReturn(mockUid);
        when(mockPi.getCreatorPackage()).thenReturn(TEST_CALLING_PACKAGE);
        return mockPi;
    }

    /**
     * Lowers quotas to make testing feasible. Careful while calling as this will replace any
     * existing settings for the calling test.
     */
    private void setTestableQuotas() {
        final StringBuilder constantsBuilder = new StringBuilder();
        constantsBuilder.append(KEY_MIN_FUTURITY);
        constantsBuilder.append("=0,");
        // Capping active and working quotas to make testing feasible.
        constantsBuilder.append(mService.mConstants.KEYS_APP_STANDBY_QUOTAS[ACTIVE_INDEX]);
        constantsBuilder.append("=8,");
        constantsBuilder.append(mService.mConstants.KEYS_APP_STANDBY_QUOTAS[WORKING_INDEX]);
        constantsBuilder.append("=5,");
        doReturn(constantsBuilder.toString()).when(() -> Settings.Global.getString(mMockResolver,
                Settings.Global.ALARM_MANAGER_CONSTANTS));
        mService.mConstants.onChange(false, null);
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
    public void testUpdateConstants() {
        final StringBuilder constantsBuilder = new StringBuilder();
        constantsBuilder.append(KEY_MIN_FUTURITY);
        constantsBuilder.append("=5,");
        constantsBuilder.append(KEY_MIN_INTERVAL);
        constantsBuilder.append("=10,");
        constantsBuilder.append(KEY_MAX_INTERVAL);
        constantsBuilder.append("=15,");
        constantsBuilder.append(KEY_ALLOW_WHILE_IDLE_SHORT_TIME);
        constantsBuilder.append("=20,");
        constantsBuilder.append(KEY_ALLOW_WHILE_IDLE_LONG_TIME);
        constantsBuilder.append("=25,");
        constantsBuilder.append(KEY_ALLOW_WHILE_IDLE_WHITELIST_DURATION);
        constantsBuilder.append("=30,");
        constantsBuilder.append(KEY_LISTENER_TIMEOUT);
        constantsBuilder.append("=35,");

        doReturn(constantsBuilder.toString()).when(() -> Settings.Global.getString(mMockResolver,
                Settings.Global.ALARM_MANAGER_CONSTANTS));
        mService.mConstants.onChange(false, null);
        assertEquals(5, mService.mConstants.MIN_FUTURITY);
        assertEquals(10, mService.mConstants.MIN_INTERVAL);
        assertEquals(15, mService.mConstants.MAX_INTERVAL);
        assertEquals(20, mService.mConstants.ALLOW_WHILE_IDLE_SHORT_TIME);
        assertEquals(25, mService.mConstants.ALLOW_WHILE_IDLE_LONG_TIME);
        assertEquals(30, mService.mConstants.ALLOW_WHILE_IDLE_WHITELIST_DURATION);
        assertEquals(35, mService.mConstants.LISTENER_TIMEOUT);
    }

    @Test
    public void testMinFuturity() {
        doReturn("min_futurity=10").when(() ->
                Settings.Global.getString(mMockResolver, Settings.Global.ALARM_MANAGER_CONSTANTS));
        mService.mConstants.onChange(false, null);
        assertEquals(10, mService.mConstants.MIN_FUTURITY);
        final long triggerTime = mNowElapsedTest + 1;
        final long expectedTriggerTime = mNowElapsedTest + mService.mConstants.MIN_FUTURITY;
        setTestAlarm(ELAPSED_REALTIME_WAKEUP, triggerTime, getNewMockPendingIntent());
        assertEquals(expectedTriggerTime, mTestTimer.getElapsed());
    }

    @Test
    public void testMinFuturityCoreUid() {
        doReturn("min_futurity=10").when(() ->
                Settings.Global.getString(mMockResolver, Settings.Global.ALARM_MANAGER_CONSTANTS));
        mService.mConstants.onChange(false, null);
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

        mService.removeLocked(pi6, null);
        assertEquals(mNowElapsedTest + 8, mTestTimer.getElapsed());

        mService.removeLocked(pi8, null);
        assertEquals(mNowElapsedTest + 9, mTestTimer.getElapsed());
    }

    private void testQuotasDeferralOnSet(int standbyBucket) throws Exception {
        final int quota = mService.getQuotaForBucketLocked(standbyBucket);
        when(mUsageStatsManagerInternal.getAppStandbyBucket(eq(TEST_CALLING_PACKAGE), anyInt(),
                anyLong())).thenReturn(standbyBucket);
        final long firstTrigger = mNowElapsedTest + 10;
        for (int i = 0; i < quota; i++) {
            setTestAlarm(ELAPSED_REALTIME_WAKEUP, firstTrigger + i,
                    getNewMockPendingIntent());
            mNowElapsedTest = mTestTimer.getElapsed();
            mTestTimer.expire();
        }
        // This one should get deferred on set
        setTestAlarm(ELAPSED_REALTIME_WAKEUP, firstTrigger + quota,
                getNewMockPendingIntent());
        final long expectedNextTrigger = firstTrigger + mAppStandbyWindow;
        assertEquals("Incorrect next alarm trigger", expectedNextTrigger, mTestTimer.getElapsed());
    }

    private void testQuotasDeferralOnExpiration(int standbyBucket) throws Exception {
        final int quota = mService.getQuotaForBucketLocked(standbyBucket);
        when(mUsageStatsManagerInternal.getAppStandbyBucket(eq(TEST_CALLING_PACKAGE), anyInt(),
                anyLong())).thenReturn(standbyBucket);
        final long firstTrigger = mNowElapsedTest + 10;
        for (int i = 0; i < quota; i++) {
            setTestAlarm(ELAPSED_REALTIME_WAKEUP, firstTrigger + i,
                    getNewMockPendingIntent());
        }
        // This one should get deferred after the latest alarm expires
        setTestAlarm(ELAPSED_REALTIME_WAKEUP, firstTrigger + quota,
                getNewMockPendingIntent());
        for (int i = 0; i < quota; i++) {
            mNowElapsedTest = mTestTimer.getElapsed();
            mTestTimer.expire();
        }
        final long expectedNextTrigger = firstTrigger + mAppStandbyWindow;
        assertEquals("Incorrect next alarm trigger", expectedNextTrigger, mTestTimer.getElapsed());
    }

    private void testQuotasNoDeferral(int standbyBucket) throws Exception {
        final int quota = mService.getQuotaForBucketLocked(standbyBucket);
        when(mUsageStatsManagerInternal.getAppStandbyBucket(eq(TEST_CALLING_PACKAGE), anyInt(),
                anyLong())).thenReturn(standbyBucket);
        final long firstTrigger = mNowElapsedTest + 10;
        for (int i = 0; i < quota; i++) {
            setTestAlarm(ELAPSED_REALTIME_WAKEUP, mNowElapsedTest + 10 + i,
                    getNewMockPendingIntent());
        }
        // This delivery time maintains the quota invariant. Should not be deferred.
        final long expectedNextTrigger = firstTrigger + mAppStandbyWindow + 5;
        setTestAlarm(ELAPSED_REALTIME_WAKEUP, expectedNextTrigger, getNewMockPendingIntent());
        for (int i = 0; i < quota; i++) {
            mNowElapsedTest = mTestTimer.getElapsed();
            mTestTimer.expire();
        }
        assertEquals("Incorrect next alarm trigger", expectedNextTrigger, mTestTimer.getElapsed());
    }

    @Test
    public void testActiveQuota_deferredOnSet() throws Exception {
        testQuotasDeferralOnSet(STANDBY_BUCKET_ACTIVE);
    }

    @Test
    public void testActiveQuota_deferredOnExpiration() throws Exception {
        testQuotasDeferralOnExpiration(STANDBY_BUCKET_ACTIVE);
    }

    @Test
    public void testActiveQuota_notDeferred() throws Exception {
        testQuotasNoDeferral(STANDBY_BUCKET_ACTIVE);
    }

    @Test
    public void testWorkingQuota_deferredOnSet() throws Exception {
        testQuotasDeferralOnSet(STANDBY_BUCKET_WORKING_SET);
    }

    @Test
    public void testWorkingQuota_deferredOnExpiration() throws Exception {
        testQuotasDeferralOnExpiration(STANDBY_BUCKET_WORKING_SET);
    }

    @Test
    public void testWorkingQuota_notDeferred() throws Exception {
        testQuotasNoDeferral(STANDBY_BUCKET_WORKING_SET);
    }

    @Test
    public void testFrequentQuota_deferredOnSet() throws Exception {
        testQuotasDeferralOnSet(STANDBY_BUCKET_FREQUENT);
    }

    @Test
    public void testFrequentQuota_deferredOnExpiration() throws Exception {
        testQuotasDeferralOnExpiration(STANDBY_BUCKET_FREQUENT);
    }

    @Test
    public void testFrequentQuota_notDeferred() throws Exception {
        testQuotasNoDeferral(STANDBY_BUCKET_FREQUENT);
    }

    @Test
    public void testRareQuota_deferredOnSet() throws Exception {
        testQuotasDeferralOnSet(STANDBY_BUCKET_RARE);
    }

    @Test
    public void testRareQuota_deferredOnExpiration() throws Exception {
        testQuotasDeferralOnExpiration(STANDBY_BUCKET_RARE);
    }

    @Test
    public void testRareQuota_notDeferred() throws Exception {
        testQuotasNoDeferral(STANDBY_BUCKET_RARE);
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
        verify(mService.mHandler, atLeastOnce()).sendMessage(messageCaptor.capture());
        final Message lastMessage = messageCaptor.getValue();
        assertEquals("Unexpected message send to handler", lastMessage.what,
                what);
        mService.mHandler.handleMessage(lastMessage);
    }

    @Test
    public void testQuotaDowngrade() throws Exception {
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
    public void testQuotaUpgrade() throws Exception {
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
    public void testAlarmRestrictedInBatterSaver() throws Exception {
        final ArgumentCaptor<AppStateTracker.Listener> listenerArgumentCaptor =
                ArgumentCaptor.forClass(AppStateTracker.Listener.class);
        verify(mAppStateTracker).addListener(listenerArgumentCaptor.capture());

        final PendingIntent alarmPi = getNewMockPendingIntent();
        when(mAppStateTracker.areAlarmsRestricted(TEST_CALLING_UID, TEST_CALLING_PACKAGE,
                false)).thenReturn(true);
        setTestAlarm(ELAPSED_REALTIME_WAKEUP, mNowElapsedTest + 2, alarmPi);
        assertEquals(mNowElapsedTest + 2, mTestTimer.getElapsed());

        final SparseArray<ArrayList<AlarmManagerService.Alarm>> restrictedAlarms =
                mService.mPendingBackgroundAlarms;
        assertNull(restrictedAlarms.get(TEST_CALLING_UID));

        mNowElapsedTest = mTestTimer.getElapsed();
        mTestTimer.expire();
        assertNotNull(restrictedAlarms.get(TEST_CALLING_UID));

        listenerArgumentCaptor.getValue().unblockAlarmsForUid(TEST_CALLING_UID);
        verify(alarmPi).send(eq(mMockContext), eq(0), any(Intent.class), any(),
                any(Handler.class), isNull(), any());
        assertNull(restrictedAlarms.get(TEST_CALLING_UID));
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
        verify(mMockContext).sendBroadcastAsUser(mService.mTimeTickIntent, UserHandle.ALL);
    }

    @Test
    public void alarmCountKeyedOnCallingUid() {
        final int mockCreatorUid = 431412;
        setTestAlarm(ELAPSED_REALTIME, mNowElapsedTest + 5,
                getNewMockPendingIntent(mockCreatorUid));
        assertEquals(1, mService.mAlarmsPerUid.get(TEST_CALLING_UID));
        assertEquals(-1, mService.mAlarmsPerUid.get(mockCreatorUid, -1));
    }

    @Test
    public void alarmCountOnSetPi() {
        final int numAlarms = 103;
        final int[] types = {RTC_WAKEUP, RTC, ELAPSED_REALTIME_WAKEUP, ELAPSED_REALTIME};
        for (int i = 1; i <= numAlarms; i++) {
            setTestAlarm(types[i % 4], mNowElapsedTest + i, getNewMockPendingIntent());
            assertEquals(i, mService.mAlarmsPerUid.get(TEST_CALLING_UID));
        }
    }

    @Test
    public void alarmCountOnSetListener() {
        final int numAlarms = 103;
        final int[] types = {RTC_WAKEUP, RTC, ELAPSED_REALTIME_WAKEUP, ELAPSED_REALTIME};
        for (int i = 1; i <= numAlarms; i++) {
            setTestAlarmWithListener(types[i % 4], mNowElapsedTest + i, new IAlarmListener.Stub() {
                @Override
                public void doAlarm(IAlarmCompleteListener callback) throws RemoteException {
                }
            });
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
                    new IAlarmListener.Stub() {
                        @Override
                        public void doAlarm(IAlarmCompleteListener callback)
                                throws RemoteException {
                        }
                    });
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
            final IAlarmListener listener = new IAlarmListener.Stub() {
                @Override
                public void doAlarm(IAlarmCompleteListener callback) throws RemoteException {
                    throw new RemoteException("For testing behavior on exception");
                }
            };
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
        mService.removeLocked(TEST_CALLING_UID);
        assertEquals(0, mService.mAlarmsPerUid.get(TEST_CALLING_UID, 0));
    }

    @Test
    public void alarmCountOnPackageRemoved() {
        final int numAlarms = 10;
        for (int i = 0; i < numAlarms; i++) {
            setTestAlarm(ELAPSED_REALTIME, mNowElapsedTest + i + 10, getNewMockPendingIntent());
        }
        assertEquals(numAlarms, mService.mAlarmsPerUid.get(TEST_CALLING_UID));
        mService.removeLocked(TEST_CALLING_PACKAGE);
        assertEquals(0, mService.mAlarmsPerUid.get(TEST_CALLING_UID, 0));
    }

    @Test
    public void alarmCountOnUserRemoved() {
        final int mockUserId = 15;
        final int numAlarms = 10;
        for (int i = 0; i < numAlarms; i++) {
            int mockUid = UserHandle.getUid(mockUserId, 1234 + i);
            setTestAlarm(ELAPSED_REALTIME, mNowElapsedTest + i + 10,
                    getNewMockPendingIntent(mockUid), 0, mockUid);
        }
        assertEquals(numAlarms, mService.mAlarmsPerUid.size());
        mService.removeUserLocked(mockUserId);
        assertEquals(0, mService.mAlarmsPerUid.size());
    }

    @Test
    public void alarmCountOnRemoveFromPendingWhileIdle() {
        mService.mPendingIdleUntil = mock(AlarmManagerService.Alarm.class);
        final int numAlarms = 15;
        final PendingIntent[] pis = new PendingIntent[numAlarms];
        for (int i = 0; i < numAlarms; i++) {
            pis[i] = getNewMockPendingIntent();
            setTestAlarm(ELAPSED_REALTIME, mNowElapsedTest + i + 5, pis[i]);
        }
        assertEquals(numAlarms, mService.mAlarmsPerUid.get(TEST_CALLING_UID));
        assertEquals(numAlarms, mService.mPendingWhileIdleAlarms.size());
        final int toRemove = 8;
        for (int i = 0; i < toRemove; i++) {
            mService.removeLocked(pis[i], null);
            assertEquals(numAlarms - i - 1, mService.mAlarmsPerUid.get(TEST_CALLING_UID, 0));
        }
        mService.removeLocked(TEST_CALLING_UID);
        assertEquals(0, mService.mAlarmsPerUid.get(TEST_CALLING_UID, 0));
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
            mService.removeLocked(pis[i], null);
            assertEquals(numAlarms - i - 1, mService.mAlarmsPerUid.get(TEST_CALLING_UID, 0));
        }
    }

    @Test
    public void alarmTypes() throws Exception {
        final int[] typesToSet = {ELAPSED_REALTIME_WAKEUP, ELAPSED_REALTIME, RTC_WAKEUP, RTC};
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
            listeners[i] = new IAlarmListener.Stub() {
                @Override
                public void doAlarm(IAlarmCompleteListener callback) throws RemoteException {
                }
            };
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
            mService.removeLocked(pis[i], null);
            assertEquals(numAlarms - i - 1, mService.mAlarmsPerUid.get(TEST_CALLING_UID, 0));
        }
    }

    @After
    public void tearDown() {
        if (mMockingSession != null) {
            mMockingSession.finishMocking();
        }
        LocalServices.removeServiceForTest(AlarmManagerInternal.class);
    }
}
