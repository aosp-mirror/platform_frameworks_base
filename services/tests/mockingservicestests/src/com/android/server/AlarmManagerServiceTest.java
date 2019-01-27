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

import static android.app.AlarmManager.ELAPSED_REALTIME_WAKEUP;
import static android.app.usage.UsageStatsManager.STANDBY_BUCKET_ACTIVE;
import static android.app.usage.UsageStatsManager.STANDBY_BUCKET_FREQUENT;
import static android.app.usage.UsageStatsManager.STANDBY_BUCKET_RARE;
import static android.app.usage.UsageStatsManager.STANDBY_BUCKET_WORKING_SET;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.doNothing;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.mock;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.mockitoSession;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.spyOn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.verify;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.when;
import static com.android.server.AlarmManagerService.ACTIVE_INDEX;
import static com.android.server.AlarmManagerService.AlarmHandler.APP_STANDBY_BUCKET_CHANGED;
import static com.android.server.AlarmManagerService.AlarmHandler.APP_STANDBY_PAROLE_CHANGED;
import static com.android.server.AlarmManagerService.Constants.KEY_ALLOW_WHILE_IDLE_LONG_TIME;
import static com.android.server.AlarmManagerService.Constants.KEY_ALLOW_WHILE_IDLE_SHORT_TIME;
import static com.android.server.AlarmManagerService.Constants.KEY_ALLOW_WHILE_IDLE_WHITELIST_DURATION;
import static com.android.server.AlarmManagerService.Constants.KEY_APP_STANDBY_QUOTAS_ENABLED;
import static com.android.server.AlarmManagerService.Constants.KEY_LISTENER_TIMEOUT;
import static com.android.server.AlarmManagerService.Constants.KEY_MAX_INTERVAL;
import static com.android.server.AlarmManagerService.Constants.KEY_MIN_FUTURITY;
import static com.android.server.AlarmManagerService.Constants.KEY_MIN_INTERVAL;
import static com.android.server.AlarmManagerService.WORKING_INDEX;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeastOnce;

import android.app.ActivityManager;
import android.app.AlarmManager;
import android.app.IActivityManager;
import android.app.IUidObserver;
import android.app.PendingIntent;
import android.app.usage.UsageStatsManagerInternal;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.PowerManager;
import android.os.UserHandle;
import android.platform.test.annotations.Presubmit;
import android.provider.Settings;
import android.util.Log;
import android.util.SparseArray;

import androidx.test.runner.AndroidJUnit4;

import com.android.internal.annotations.GuardedBy;

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
    private static final int SYSTEM_UI_UID = 123456789;
    private static final int TEST_CALLING_UID = 12345;

    private long mAppStandbyWindow;
    private AlarmManagerService mService;
    private UsageStatsManagerInternal.AppIdleStateChangeListener mAppStandbyListener;
    @Mock
    private ContentResolver mMockResolver;
    @Mock
    private Context mMockContext;
    @Mock
    private IActivityManager mIActivityManager;
    @Mock
    private UsageStatsManagerInternal mUsageStatsManagerInternal;
    @Mock
    private AppStateTracker mAppStateTracker;
    @Mock
    private AlarmManagerService.ClockReceiver mClockReceiver;
    @Mock
    private PowerManager.WakeLock mWakeLock;

    private MockitoSession mMockingSession;
    private Injector mInjector;
    private volatile long mNowElapsedTest;
    @GuardedBy("mTestTimer")
    private TestTimer mTestTimer = new TestTimer();

    static class TestTimer {
        private long mElapsed;
        boolean mExpired;

        synchronized long getElapsed() {
            return mElapsed;
        }

        synchronized void set(long millisElapsed) {
            mElapsed = millisElapsed;
        }

        synchronized void expire() throws InterruptedException {
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
            return AlarmManagerService.IS_WAKEUP_MASK; // Doesn't matter, just evaluate.
        }

        @Override
        void setKernelTimezone(int minutesWest) {
            // Do nothing.
        }

        @Override
        void setAlarm(int type, long millis) {
            mTestTimer.set(millis);
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
                .strictness(Strictness.WARN)
                .startMocking();
        doReturn(mIActivityManager).when(ActivityManager::getService);
        doReturn(mAppStateTracker).when(() -> LocalServices.getService(AppStateTracker.class));
        doReturn(null)
                .when(() -> LocalServices.getService(DeviceIdleController.LocalService.class));
        doReturn(mUsageStatsManagerInternal).when(
                () -> LocalServices.getService(UsageStatsManagerInternal.class));
        when(mUsageStatsManagerInternal.getAppStandbyBucket(eq(TEST_CALLING_PACKAGE),
                eq(UserHandle.getUserId(TEST_CALLING_UID)), anyLong()))
                .thenReturn(STANDBY_BUCKET_ACTIVE);
        doReturn(Looper.getMainLooper()).when(Looper::myLooper);

        when(mMockContext.getContentResolver()).thenReturn(mMockResolver);
        doReturn("min_futurity=0").when(() ->
                Settings.Global.getString(mMockResolver, Settings.Global.ALARM_MANAGER_CONSTANTS));
        mInjector = new Injector(mMockContext);
        mService = new AlarmManagerService(mMockContext, mInjector);
        spyOn(mService);
        doNothing().when(mService).publishBinderService(any(), any());

        mService.onStart();
        spyOn(mService.mHandler);
        assertEquals(mService.mSystemUiUid, SYSTEM_UI_UID);
        assertEquals(mService.mClockReceiver, mClockReceiver);
        assertEquals(mService.mWakeLock, mWakeLock);
        verify(mIActivityManager).registerUidObserver(any(IUidObserver.class), anyInt(), anyInt(),
                isNull());

        // Other boot phases don't matter
        mService.onBootPhase(SystemService.PHASE_SYSTEM_SERVICES_READY);
        assertEquals(0, mService.mConstants.MIN_FUTURITY);
        mAppStandbyWindow = mService.mConstants.APP_STANDBY_WINDOW;
        ArgumentCaptor<UsageStatsManagerInternal.AppIdleStateChangeListener> captor =
                ArgumentCaptor.forClass(UsageStatsManagerInternal.AppIdleStateChangeListener.class);
        verify(mUsageStatsManagerInternal).addAppIdleStateChangeListener(captor.capture());
        mAppStandbyListener = captor.getValue();
    }

    private void setTestAlarm(int type, long triggerTime, PendingIntent operation) {
        mService.setImpl(type, triggerTime, AlarmManager.WINDOW_EXACT, 0,
                operation, null, "test", AlarmManager.FLAG_STANDALONE, null, null,
                TEST_CALLING_UID, TEST_CALLING_PACKAGE);
    }

    private PendingIntent getNewMockPendingIntent() {
        final PendingIntent mockPi = mock(PendingIntent.class, Answers.RETURNS_DEEP_STUBS);
        when(mockPi.getCreatorUid()).thenReturn(TEST_CALLING_UID);
        when(mockPi.getCreatorPackage()).thenReturn(TEST_CALLING_PACKAGE);
        return mockPi;
    }

    /**
     * Careful while calling as this will replace any existing settings for the calling test.
     */
    private void setQuotasEnabled(boolean enabled) {
        final StringBuilder constantsBuilder = new StringBuilder();
        constantsBuilder.append(KEY_MIN_FUTURITY);
        constantsBuilder.append("=0,");
        // Capping active and working quotas to make testing feasible.
        constantsBuilder.append(mService.mConstants.KEYS_APP_STANDBY_QUOTAS[ACTIVE_INDEX]);
        constantsBuilder.append("=8,");
        constantsBuilder.append(mService.mConstants.KEYS_APP_STANDBY_QUOTAS[WORKING_INDEX]);
        constantsBuilder.append("=5,");
        if (!enabled) {
            constantsBuilder.append(KEY_APP_STANDBY_QUOTAS_ENABLED);
            constantsBuilder.append("=false,");
        }
        doReturn(constantsBuilder.toString()).when(() -> Settings.Global.getString(mMockResolver,
                Settings.Global.ALARM_MANAGER_CONSTANTS));
        mService.mConstants.onChange(false, null);
        assertEquals(mService.mConstants.APP_STANDBY_QUOTAS_ENABLED, enabled);
    }

    @Test
    public void testSingleAlarmSet() {
        final long triggerTime = mNowElapsedTest + 5000;
        final PendingIntent alarmPi = getNewMockPendingIntent();
        setTestAlarm(ELAPSED_REALTIME_WAKEUP, triggerTime, alarmPi);
        assertEquals(triggerTime, mTestTimer.getElapsed());
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

    @Test
    public void testStandbyBucketDelay_workingSet() throws Exception {
        setQuotasEnabled(false);
        setTestAlarm(ELAPSED_REALTIME_WAKEUP, mNowElapsedTest + 5, getNewMockPendingIntent());
        setTestAlarm(ELAPSED_REALTIME_WAKEUP, mNowElapsedTest + 6, getNewMockPendingIntent());
        assertEquals(mNowElapsedTest + 5, mTestTimer.getElapsed());

        when(mUsageStatsManagerInternal.getAppStandbyBucket(eq(TEST_CALLING_PACKAGE), anyInt(),
                anyLong())).thenReturn(STANDBY_BUCKET_WORKING_SET);

        mNowElapsedTest = mTestTimer.getElapsed();
        mTestTimer.expire();

        verify(mUsageStatsManagerInternal, atLeastOnce())
                .getAppStandbyBucket(eq(TEST_CALLING_PACKAGE),
                        eq(UserHandle.getUserId(TEST_CALLING_UID)), anyLong());
        final long expectedNextTrigger = mNowElapsedTest
                + mService.getMinDelayForBucketLocked(STANDBY_BUCKET_WORKING_SET);
        assertEquals("Incorrect next alarm trigger", expectedNextTrigger, mTestTimer.getElapsed());
    }

    @Test
    public void testStandbyBucketDelay_frequent() throws Exception {
        setQuotasEnabled(false);
        setTestAlarm(ELAPSED_REALTIME_WAKEUP, mNowElapsedTest + 5, getNewMockPendingIntent());
        setTestAlarm(ELAPSED_REALTIME_WAKEUP, mNowElapsedTest + 6, getNewMockPendingIntent());
        assertEquals(mNowElapsedTest + 5, mTestTimer.getElapsed());

        when(mUsageStatsManagerInternal.getAppStandbyBucket(eq(TEST_CALLING_PACKAGE), anyInt(),
                anyLong())).thenReturn(STANDBY_BUCKET_FREQUENT);
        mNowElapsedTest = mTestTimer.getElapsed();
        mTestTimer.expire();

        verify(mUsageStatsManagerInternal, atLeastOnce())
                .getAppStandbyBucket(eq(TEST_CALLING_PACKAGE),
                        eq(UserHandle.getUserId(TEST_CALLING_UID)), anyLong());
        final long expectedNextTrigger = mNowElapsedTest
                + mService.getMinDelayForBucketLocked(STANDBY_BUCKET_FREQUENT);
        assertEquals("Incorrect next alarm trigger.", expectedNextTrigger, mTestTimer.getElapsed());
    }

    @Test
    public void testStandbyBucketDelay_rare() throws Exception {
        setQuotasEnabled(false);
        setTestAlarm(ELAPSED_REALTIME_WAKEUP, mNowElapsedTest + 5, getNewMockPendingIntent());
        setTestAlarm(ELAPSED_REALTIME_WAKEUP, mNowElapsedTest + 6, getNewMockPendingIntent());
        assertEquals(mNowElapsedTest + 5, mTestTimer.getElapsed());

        when(mUsageStatsManagerInternal.getAppStandbyBucket(eq(TEST_CALLING_PACKAGE), anyInt(),
                anyLong())).thenReturn(STANDBY_BUCKET_RARE);
        mNowElapsedTest = mTestTimer.getElapsed();
        mTestTimer.expire();

        verify(mUsageStatsManagerInternal, atLeastOnce())
                .getAppStandbyBucket(eq(TEST_CALLING_PACKAGE),
                        eq(UserHandle.getUserId(TEST_CALLING_UID)), anyLong());
        final long expectedNextTrigger = mNowElapsedTest
                + mService.getMinDelayForBucketLocked(STANDBY_BUCKET_RARE);
        assertEquals("Incorrect next alarm trigger.", expectedNextTrigger, mTestTimer.getElapsed());
    }

    private void testQuotasDeferralOnSet(int standbyBucket) throws Exception {
        final int quota = mService.getQuotaForBucketLocked(standbyBucket);
        when(mUsageStatsManagerInternal.getAppStandbyBucket(eq(TEST_CALLING_PACKAGE), anyInt(),
                anyLong())).thenReturn(standbyBucket);
        final long firstTrigger = mNowElapsedTest + 10;
        for (int i = 0; i < quota; i++) {
            setTestAlarm(ELAPSED_REALTIME_WAKEUP, mNowElapsedTest + 10 + i,
                    getNewMockPendingIntent());
            mNowElapsedTest = mTestTimer.getElapsed();
            mTestTimer.expire();
        }
        // This one should get deferred on set
        setTestAlarm(ELAPSED_REALTIME_WAKEUP, mNowElapsedTest + quota + 10,
                getNewMockPendingIntent());
        final long expectedNextTrigger = firstTrigger + 1 + mAppStandbyWindow;
        assertEquals("Incorrect next alarm trigger", expectedNextTrigger, mTestTimer.getElapsed());
    }

    private void testQuotasDeferralOnExpiration(int standbyBucket) throws Exception {
        final int quota = mService.getQuotaForBucketLocked(standbyBucket);
        when(mUsageStatsManagerInternal.getAppStandbyBucket(eq(TEST_CALLING_PACKAGE), anyInt(),
                anyLong())).thenReturn(standbyBucket);
        final long firstTrigger = mNowElapsedTest + 10;
        for (int i = 0; i < quota; i++) {
            setTestAlarm(ELAPSED_REALTIME_WAKEUP, mNowElapsedTest + 10 + i,
                    getNewMockPendingIntent());
        }
        // This one should get deferred after the latest alarm expires
        setTestAlarm(ELAPSED_REALTIME_WAKEUP, mNowElapsedTest + quota + 10,
                getNewMockPendingIntent());
        for (int i = 0; i < quota; i++) {
            mNowElapsedTest = mTestTimer.getElapsed();
            mTestTimer.expire();
        }
        final long expectedNextTrigger = firstTrigger + 1 + mAppStandbyWindow;
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
        setQuotasEnabled(true);
        testQuotasDeferralOnSet(STANDBY_BUCKET_ACTIVE);
    }

    @Test
    public void testActiveQuota_deferredOnExpiration() throws Exception {
        setQuotasEnabled(true);
        testQuotasDeferralOnExpiration(STANDBY_BUCKET_ACTIVE);
    }

    @Test
    public void testActiveQuota_notDeferred() throws Exception {
        setQuotasEnabled(true);
        testQuotasNoDeferral(STANDBY_BUCKET_ACTIVE);
    }

    @Test
    public void testWorkingQuota_deferredOnSet() throws Exception {
        setQuotasEnabled(true);
        testQuotasDeferralOnSet(STANDBY_BUCKET_WORKING_SET);
    }

    @Test
    public void testWorkingQuota_deferredOnExpiration() throws Exception {
        setQuotasEnabled(true);
        testQuotasDeferralOnExpiration(STANDBY_BUCKET_WORKING_SET);
    }

    @Test
    public void testWorkingQuota_notDeferred() throws Exception {
        setQuotasEnabled(true);
        testQuotasNoDeferral(STANDBY_BUCKET_WORKING_SET);
    }

    @Test
    public void testFrequentQuota_deferredOnSet() throws Exception {
        setQuotasEnabled(true);
        testQuotasDeferralOnSet(STANDBY_BUCKET_FREQUENT);
    }

    @Test
    public void testFrequentQuota_deferredOnExpiration() throws Exception {
        setQuotasEnabled(true);
        testQuotasDeferralOnExpiration(STANDBY_BUCKET_FREQUENT);
    }

    @Test
    public void testFrequentQuota_notDeferred() throws Exception {
        setQuotasEnabled(true);
        testQuotasNoDeferral(STANDBY_BUCKET_FREQUENT);
    }

    @Test
    public void testRareQuota_deferredOnSet() throws Exception {
        setQuotasEnabled(true);
        testQuotasDeferralOnSet(STANDBY_BUCKET_RARE);
    }

    @Test
    public void testRareQuota_deferredOnExpiration() throws Exception {
        setQuotasEnabled(true);
        testQuotasDeferralOnExpiration(STANDBY_BUCKET_RARE);
    }

    @Test
    public void testRareQuota_notDeferred() throws Exception {
        setQuotasEnabled(true);
        testQuotasNoDeferral(STANDBY_BUCKET_RARE);
    }

    private void sendAndHandleBucketChanged(int bucket) {
        when(mUsageStatsManagerInternal.getAppStandbyBucket(eq(TEST_CALLING_PACKAGE), anyInt(),
                anyLong())).thenReturn(bucket);
        // Stubbing the handler call to simulate it synchronously here.
        doReturn(true).when(mService.mHandler).sendMessage(any(Message.class));
        mAppStandbyListener.onAppIdleStateChanged(TEST_CALLING_PACKAGE,
                UserHandle.getUserId(TEST_CALLING_UID), false, bucket, 0);
        final ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);
        verify(mService.mHandler, atLeastOnce()).sendMessage(messageCaptor.capture());
        final Message lastMessage = messageCaptor.getValue();
        assertEquals("Unexpected message send to handler", lastMessage.what,
                APP_STANDBY_BUCKET_CHANGED);
        mService.mHandler.handleMessage(lastMessage);
    }

    @Test
    public void testQuotaDowngrade() throws Exception {
        setQuotasEnabled(true);
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
        sendAndHandleBucketChanged(STANDBY_BUCKET_RARE);
        final int rareQuota = mService.getQuotaForBucketLocked(STANDBY_BUCKET_RARE);
        // The last alarm should now be deferred.
        final long expectedNextTrigger = (firstTrigger + workingQuota - 1 - rareQuota)
                + mAppStandbyWindow + 1;
        assertEquals("Incorrect next alarm trigger", expectedNextTrigger, mTestTimer.getElapsed());
    }

    @Test
    public void testQuotaUpgrade() throws Exception {
        setQuotasEnabled(true);
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
        final long deferredTrigger = firstTrigger + 1 + mAppStandbyWindow;
        assertEquals(deferredTrigger, mTestTimer.getElapsed());

        // Upgrading the bucket now
        sendAndHandleBucketChanged(STANDBY_BUCKET_ACTIVE);
        // The last alarm should now be rescheduled to go as per original expectations
        final long originalTrigger = firstTrigger + frequentQuota;
        assertEquals("Incorrect next alarm trigger", originalTrigger, mTestTimer.getElapsed());
    }

    private void sendAndHandleParoleChanged(boolean parole) {
        // Stubbing the handler call to simulate it synchronously here.
        doReturn(true).when(mService.mHandler).sendMessage(any(Message.class));
        mAppStandbyListener.onParoleStateChanged(parole);
        final ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);
        verify(mService.mHandler, atLeastOnce()).sendMessage(messageCaptor.capture());
        final Message lastMessage = messageCaptor.getValue();
        assertEquals("Unexpected message send to handler", lastMessage.what,
                APP_STANDBY_PAROLE_CHANGED);
        mService.mHandler.handleMessage(lastMessage);
    }

    @Test
    public void testParole() throws Exception {
        setQuotasEnabled(true);
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
        assertEquals(firstTrigger + mAppStandbyWindow + 1, mTestTimer.getElapsed());
        // Paroling now
        sendAndHandleParoleChanged(true);

        // Subsequent alarms should now go off as per original expectations.
        for (int i = 0; i < 5; i++) {
            mNowElapsedTest = mTestTimer.getElapsed();
            assertEquals(firstTrigger + workingQuota + i, mNowElapsedTest);
            mTestTimer.expire();
        }
        // Come out of parole
        sendAndHandleParoleChanged(false);

        // Subsequent alarms should again get deferred
        final long expectedNextTrigger = (firstTrigger + 5) + 1 + mAppStandbyWindow;
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

    @After
    public void tearDown() {
        if (mMockingSession != null) {
            mMockingSession.finishMocking();
        }
    }
}
