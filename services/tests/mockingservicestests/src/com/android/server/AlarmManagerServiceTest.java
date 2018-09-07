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
import static com.android.dx.mockito.inline.extended.ExtendedMockito.spy;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.spyOn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.timeout;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.verify;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.when;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;

import android.app.ActivityManager;
import android.app.AlarmManager;
import android.app.IActivityManager;
import android.app.IUidObserver;
import android.app.PendingIntent;
import android.app.usage.UsageStatsManagerInternal;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.os.UserHandle;
import android.util.Log;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Answers;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoSession;

import javax.annotation.concurrent.GuardedBy;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class AlarmManagerServiceTest {
    private static final String TAG = AlarmManagerServiceTest.class.getSimpleName();
    private static final String TEST_CALLING_PACKAGE = "com.android.framework.test-package";
    private static final int SYSTEM_UI_UID = 123456789;
    private static final int TEST_CALLING_UID = 12345;
    private static final long DEFAULT_TIMEOUT = 5_000;

    private AlarmManagerService mService;
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

        synchronized long expire() {
            mExpired = true;
            notify();
            return mElapsed;
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
                .mockStatic(ActivityManager.class, Answers.CALLS_REAL_METHODS)
                .mockStatic(LocalServices.class)
                .mockStatic(Looper.class, Answers.CALLS_REAL_METHODS)
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

        final Context context = InstrumentationRegistry.getTargetContext();
        mInjector = spy(new Injector(context));
        mService = new AlarmManagerService(context, mInjector);
        spyOn(mService);
        doNothing().when(mService).publishBinderService(any(), any());
        mService.onStart();
        mService.onBootPhase(SystemService.PHASE_SYSTEM_SERVICES_READY);
        mService.mConstants.MIN_FUTURITY = 0;

        assertEquals(mService.mSystemUiUid, SYSTEM_UI_UID);
        assertEquals(mService.mClockReceiver, mClockReceiver);
        assertEquals(mService.mWakeLock, mWakeLock);
        verify(mIActivityManager).registerUidObserver(any(IUidObserver.class), anyInt(), anyInt(),
                isNull());
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

    @Test
    public void testSingleAlarmSet() {
        final long triggerTime = mNowElapsedTest + 5000;
        final PendingIntent alarmPi = getNewMockPendingIntent();
        setTestAlarm(ELAPSED_REALTIME_WAKEUP, triggerTime, alarmPi);
        verify(mInjector).setAlarm(ELAPSED_REALTIME_WAKEUP, triggerTime);
        assertEquals(triggerTime, mTestTimer.getElapsed());
    }

    @Test
    public void testSingleAlarmExpiration() throws Exception {
        final long triggerTime = mNowElapsedTest + 5000;
        final PendingIntent alarmPi = getNewMockPendingIntent();
        setTestAlarm(ELAPSED_REALTIME_WAKEUP, triggerTime, alarmPi);

        mNowElapsedTest = mTestTimer.expire();

        final ArgumentCaptor<PendingIntent.OnFinished> onFinishedCaptor =
                ArgumentCaptor.forClass(PendingIntent.OnFinished.class);
        verify(alarmPi, timeout(DEFAULT_TIMEOUT)).send(any(Context.class), eq(0),
                any(Intent.class), onFinishedCaptor.capture(), any(Handler.class), isNull(), any());
        verify(mWakeLock, timeout(DEFAULT_TIMEOUT)).acquire();
        onFinishedCaptor.getValue().onSendFinished(alarmPi, null, 0, null, null);
        verify(mWakeLock, timeout(DEFAULT_TIMEOUT)).release();
    }

    @Test
    public void testMinFuturity() {
        mService.mConstants.MIN_FUTURITY = 10;
        final long triggerTime = mNowElapsedTest + 1;
        final long expectedTriggerTime = mNowElapsedTest + mService.mConstants.MIN_FUTURITY;
        setTestAlarm(ELAPSED_REALTIME_WAKEUP, triggerTime, getNewMockPendingIntent());
        verify(mInjector).setAlarm(ELAPSED_REALTIME_WAKEUP, expectedTriggerTime);
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
        setTestAlarm(ELAPSED_REALTIME_WAKEUP, mNowElapsedTest + 5, getNewMockPendingIntent());
        setTestAlarm(ELAPSED_REALTIME_WAKEUP, mNowElapsedTest + 6, getNewMockPendingIntent());
        assertEquals(mNowElapsedTest + 5, mTestTimer.getElapsed());

        when(mUsageStatsManagerInternal.getAppStandbyBucket(eq(TEST_CALLING_PACKAGE), anyInt(),
                anyLong())).thenReturn(STANDBY_BUCKET_WORKING_SET);
        mNowElapsedTest = mTestTimer.expire();
        verify(mUsageStatsManagerInternal, timeout(DEFAULT_TIMEOUT).atLeastOnce())
                .getAppStandbyBucket(eq(TEST_CALLING_PACKAGE),
                        eq(UserHandle.getUserId(TEST_CALLING_UID)), anyLong());
        final long expectedNextTrigger = mNowElapsedTest
                + mService.getMinDelayForBucketLocked(STANDBY_BUCKET_WORKING_SET);
        assertTrue("Incorrect next alarm trigger. Expected " + expectedNextTrigger + " found: "
                + mTestTimer.getElapsed(), pollingCheck(DEFAULT_TIMEOUT,
                () -> (mTestTimer.getElapsed() == expectedNextTrigger)));
    }

    @Test
    public void testStandbyBucketDelay_frequent() {
        setTestAlarm(ELAPSED_REALTIME_WAKEUP, mNowElapsedTest + 5, getNewMockPendingIntent());
        setTestAlarm(ELAPSED_REALTIME_WAKEUP, mNowElapsedTest + 6, getNewMockPendingIntent());
        assertEquals(mNowElapsedTest + 5, mTestTimer.getElapsed());

        when(mUsageStatsManagerInternal.getAppStandbyBucket(eq(TEST_CALLING_PACKAGE), anyInt(),
                anyLong())).thenReturn(STANDBY_BUCKET_FREQUENT);
        mNowElapsedTest = mTestTimer.expire();
        verify(mUsageStatsManagerInternal, timeout(DEFAULT_TIMEOUT).atLeastOnce())
                .getAppStandbyBucket(eq(TEST_CALLING_PACKAGE),
                        eq(UserHandle.getUserId(TEST_CALLING_UID)), anyLong());
        final long expectedNextTrigger = mNowElapsedTest
                + mService.getMinDelayForBucketLocked(STANDBY_BUCKET_FREQUENT);
        assertTrue("Incorrect next alarm trigger. Expected " + expectedNextTrigger + " found: "
                + mTestTimer.getElapsed(), pollingCheck(DEFAULT_TIMEOUT,
                () -> (mTestTimer.getElapsed() == expectedNextTrigger)));
    }

    @Test
    public void testStandbyBucketDelay_rare() {
        setTestAlarm(ELAPSED_REALTIME_WAKEUP, mNowElapsedTest + 5, getNewMockPendingIntent());
        setTestAlarm(ELAPSED_REALTIME_WAKEUP, mNowElapsedTest + 6, getNewMockPendingIntent());
        assertEquals(mNowElapsedTest + 5, mTestTimer.getElapsed());

        when(mUsageStatsManagerInternal.getAppStandbyBucket(eq(TEST_CALLING_PACKAGE), anyInt(),
                anyLong())).thenReturn(STANDBY_BUCKET_RARE);
        mNowElapsedTest = mTestTimer.expire();
        verify(mUsageStatsManagerInternal, timeout(DEFAULT_TIMEOUT).atLeastOnce())
                .getAppStandbyBucket(eq(TEST_CALLING_PACKAGE),
                        eq(UserHandle.getUserId(TEST_CALLING_UID)), anyLong());
        final long expectedNextTrigger = mNowElapsedTest
                + mService.getMinDelayForBucketLocked(STANDBY_BUCKET_RARE);
        assertTrue("Incorrect next alarm trigger. Expected " + expectedNextTrigger + " found: "
                + mTestTimer.getElapsed(), pollingCheck(DEFAULT_TIMEOUT,
                () -> (mTestTimer.getElapsed() == expectedNextTrigger)));
    }

    @After
    public void tearDown() {
        if (mMockingSession != null) {
            mMockingSession.finishMocking();
        }
    }

    private boolean pollingCheck(long timeout, Condition condition) {
        final long deadline = SystemClock.uptimeMillis() + timeout;
        boolean interrupted = false;
        while (!condition.check() && SystemClock.uptimeMillis() < deadline) {
            try {
                Thread.sleep(500);
            } catch (InterruptedException ie) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
        return condition.check();
    }

    @FunctionalInterface
    interface Condition {
        boolean check();
    }
}
