/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.server.utils.quota;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.doNothing;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.inOrder;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.mockitoSession;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.spyOn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.when;
import static com.android.server.utils.quota.Category.SINGLE_CATEGORY;
import static com.android.server.utils.quota.QuotaTracker.MAX_WINDOW_SIZE_MS;
import static com.android.server.utils.quota.QuotaTracker.MIN_WINDOW_SIZE_MS;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import android.app.AlarmManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.os.UserHandle;
import android.util.LongArrayQueue;

import androidx.test.runner.AndroidJUnit4;

import com.android.server.LocalServices;
import com.android.server.utils.quota.CountQuotaTracker.ExecutionStats;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.MockitoSession;
import org.mockito.quality.Strictness;

/**
 * Tests for {@link CountQuotaTracker}.
 */
@RunWith(AndroidJUnit4.class)
public class CountQuotaTrackerTest {
    private static final long SECOND_IN_MILLIS = 1000L;
    private static final long MINUTE_IN_MILLIS = 60 * SECOND_IN_MILLIS;
    private static final long HOUR_IN_MILLIS = 60 * MINUTE_IN_MILLIS;
    private static final String TAG_CLEANUP = "*CountQuotaTracker.cleanup*";
    private static final String TAG_QUOTA_CHECK = "*QuotaTracker.quota_check*";
    private static final String TEST_PACKAGE = "com.android.frameworks.mockingservicestests";
    private static final String TEST_TAG = "testing";
    private static final int TEST_UID = 10987;
    private static final int TEST_USER_ID = 0;

    /** A {@link Category} to represent the ACTIVE standby bucket. */
    private static final Category ACTIVE_BUCKET_CATEGORY = new Category("ACTIVE");

    /** A {@link Category} to represent the WORKING_SET standby bucket. */
    private static final Category WORKING_SET_BUCKET_CATEGORY = new Category("WORKING_SET");

    /** A {@link Category} to represent the FREQUENT standby bucket. */
    private static final Category FREQUENT_BUCKET_CATEGORY = new Category("FREQUENT");

    /** A {@link Category} to represent the RARE standby bucket. */
    private static final Category RARE_BUCKET_CATEGORY = new Category("RARE");

    private CountQuotaTracker mQuotaTracker;
    private final CategorizerForTest mCategorizer = new CategorizerForTest();
    private final InjectorForTest mInjector = new InjectorForTest();
    private final TestQuotaChangeListener mQuotaChangeListener = new TestQuotaChangeListener();
    private BroadcastReceiver mReceiver;
    private MockitoSession mMockingSession;
    @Mock
    private AlarmManager mAlarmManager;
    @Mock
    private Context mContext;

    static class CategorizerForTest implements Categorizer {
        private Category mCategoryToUse = SINGLE_CATEGORY;

        @Override
        public Category getCategory(int userId,
                String packageName, String tag) {
            return mCategoryToUse;
        }
    }

    private static class InjectorForTest extends QuotaTracker.Injector {
        private long mElapsedTime = SystemClock.elapsedRealtime();

        @Override
        long getElapsedRealtime() {
            return mElapsedTime;
        }

        @Override
        boolean isAlarmManagerReady() {
            return true;
        }
    }

    private static class TestQuotaChangeListener implements QuotaChangeListener {

        @Override
        public void onQuotaStateChanged(int userId, String packageName, String tag) {

        }
    }

    @Before
    public void setUp() {
        mMockingSession = mockitoSession()
                .initMocks(this)
                .strictness(Strictness.LENIENT)
                .mockStatic(LocalServices.class)
                .startMocking();

        when(mContext.getMainLooper()).thenReturn(Looper.getMainLooper());
        when(mContext.getSystemService(AlarmManager.class)).thenReturn(mAlarmManager);

        // Freeze the clocks at 24 hours after this moment in time. Several tests create sessions
        // in the past, and QuotaController sometimes floors values at 0, so if the test time
        // causes sessions with negative timestamps, they will fail.
        advanceElapsedClock(24 * HOUR_IN_MILLIS);

        // Initialize real objects.
        // Capture the listeners.
        ArgumentCaptor<BroadcastReceiver> receiverCaptor =
                ArgumentCaptor.forClass(BroadcastReceiver.class);
        mQuotaTracker = new CountQuotaTracker(mContext, mCategorizer, mInjector);
        mQuotaTracker.setEnabled(true);
        mQuotaTracker.setQuotaFree(false);
        mQuotaTracker.registerQuotaChangeListener(mQuotaChangeListener);
        verify(mContext, atLeastOnce()).registerReceiverAsUser(
                receiverCaptor.capture(), eq(UserHandle.ALL), any(), any(), any());
        mReceiver = receiverCaptor.getValue();
    }

    @After
    public void tearDown() {
        if (mMockingSession != null) {
            mMockingSession.finishMocking();
        }
    }

    /**
     * Returns true if the two {@link LongArrayQueue}s have the same size and the same elements in
     * the same order.
     */
    private static boolean longArrayQueueEquals(LongArrayQueue queue1, LongArrayQueue queue2) {
        if (queue1 == queue2) {
            return true;
        } else if (queue1 == null || queue2 == null) {
            return false;
        }
        if (queue1.size() == queue2.size()) {
            for (int i = 0; i < queue1.size(); ++i) {
                if (queue1.get(i) != queue2.get(i)) {
                    return false;
                }
            }
            return true;
        }
        return false;
    }

    private void advanceElapsedClock(long incrementMs) {
        mInjector.mElapsedTime += incrementMs;
    }

    private void logEvents(int count) {
        logEvents(TEST_USER_ID, TEST_PACKAGE, TEST_TAG, count);
    }

    private void logEvents(int userId, String pkgName, String tag, int count) {
        for (int i = 0; i < count; ++i) {
            mQuotaTracker.noteEvent(userId, pkgName, tag);
        }
    }

    private void logEventAt(long timeElapsed) {
        logEventAt(TEST_USER_ID, TEST_PACKAGE, TEST_TAG, timeElapsed);
    }

    private void logEventAt(int userId, String pkgName, String tag, long timeElapsed) {
        long now = mInjector.getElapsedRealtime();
        mInjector.mElapsedTime = timeElapsed;
        mQuotaTracker.noteEvent(userId, pkgName, tag);
        mInjector.mElapsedTime = now;
    }

    private void logEventsAt(int userId, String pkgName, String tag, long timeElapsed, int count) {
        for (int i = 0; i < count; ++i) {
            logEventAt(userId, pkgName, tag, timeElapsed);
        }
    }

    @Test
    public void testDeleteObsoleteEventsLocked() {
        // Count window size should only apply to event list.
        mQuotaTracker.setCountLimit(SINGLE_CATEGORY, 7, 2 * HOUR_IN_MILLIS);

        final long now = mInjector.getElapsedRealtime();

        logEventAt(now - 6 * HOUR_IN_MILLIS);
        logEventAt(now - 5 * HOUR_IN_MILLIS);
        logEventAt(now - 4 * HOUR_IN_MILLIS);
        logEventAt(now - 3 * HOUR_IN_MILLIS);
        logEventAt(now - 2 * HOUR_IN_MILLIS);
        logEventAt(now - HOUR_IN_MILLIS);
        logEventAt(now - 1);

        LongArrayQueue expectedEvents = new LongArrayQueue();
        expectedEvents.addLast(now - HOUR_IN_MILLIS);
        expectedEvents.addLast(now - 1);

        mQuotaTracker.deleteObsoleteEventsLocked();

        LongArrayQueue remainingEvents = mQuotaTracker.getEvents(TEST_USER_ID, TEST_PACKAGE,
                TEST_TAG);
        assertTrue(longArrayQueueEquals(expectedEvents, remainingEvents));
    }

    @Test
    public void testAppRemoval() {
        final long now = mInjector.getElapsedRealtime();
        logEventAt(TEST_USER_ID, "com.android.test.remove", "tag1", now - (6 * HOUR_IN_MILLIS));
        logEventAt(TEST_USER_ID, "com.android.test.remove", "tag2",
                now - (2 * HOUR_IN_MILLIS + MINUTE_IN_MILLIS));
        logEventAt(TEST_USER_ID, "com.android.test.remove", "tag3", now - (HOUR_IN_MILLIS));
        // Test that another app isn't affected.
        LongArrayQueue expected1 = new LongArrayQueue();
        expected1.addLast(now - 10 * MINUTE_IN_MILLIS);
        LongArrayQueue expected2 = new LongArrayQueue();
        expected2.addLast(now - 70 * MINUTE_IN_MILLIS);
        logEventAt(TEST_USER_ID, "com.android.test.stay", "tag1", now - 10 * MINUTE_IN_MILLIS);
        logEventAt(TEST_USER_ID, "com.android.test.stay", "tag2", now - 70 * MINUTE_IN_MILLIS);

        Intent removal = new Intent(Intent.ACTION_PACKAGE_FULLY_REMOVED,
                Uri.fromParts("package", "com.android.test.remove", null));
        removal.putExtra(Intent.EXTRA_UID, TEST_UID);
        mReceiver.onReceive(mContext, removal);
        assertNull(
                mQuotaTracker.getEvents(TEST_USER_ID, "com.android.test.remove", "tag1"));
        assertNull(
                mQuotaTracker.getEvents(TEST_USER_ID, "com.android.test.remove", "tag2"));
        assertNull(
                mQuotaTracker.getEvents(TEST_USER_ID, "com.android.test.remove", "tag3"));
        assertTrue(longArrayQueueEquals(expected1,
                mQuotaTracker.getEvents(TEST_USER_ID, "com.android.test.stay", "tag1")));
        assertTrue(longArrayQueueEquals(expected2,
                mQuotaTracker.getEvents(TEST_USER_ID, "com.android.test.stay", "tag2")));
    }

    @Test
    public void testUserRemoval() {
        final long now = mInjector.getElapsedRealtime();
        logEventAt(TEST_USER_ID, TEST_PACKAGE, "tag1", now - (6 * HOUR_IN_MILLIS));
        logEventAt(TEST_USER_ID, TEST_PACKAGE, "tag2",
                now - (2 * HOUR_IN_MILLIS + MINUTE_IN_MILLIS));
        logEventAt(TEST_USER_ID, TEST_PACKAGE, "tag3", now - (HOUR_IN_MILLIS));
        // Test that another user isn't affected.
        LongArrayQueue expected = new LongArrayQueue();
        expected.addLast(now - (70 * MINUTE_IN_MILLIS));
        expected.addLast(now - (10 * MINUTE_IN_MILLIS));
        logEventAt(10, TEST_PACKAGE, "tag4", now - (70 * MINUTE_IN_MILLIS));
        logEventAt(10, TEST_PACKAGE, "tag4", now - 10 * MINUTE_IN_MILLIS);

        Intent removal = new Intent(Intent.ACTION_USER_REMOVED);
        removal.putExtra(Intent.EXTRA_USER_HANDLE, TEST_USER_ID);
        mReceiver.onReceive(mContext, removal);
        assertNull(mQuotaTracker.getEvents(TEST_USER_ID, TEST_PACKAGE, "tag1"));
        assertNull(mQuotaTracker.getEvents(TEST_USER_ID, TEST_PACKAGE, "tag2"));
        assertNull(mQuotaTracker.getEvents(TEST_USER_ID, TEST_PACKAGE, "tag3"));
        longArrayQueueEquals(expected, mQuotaTracker.getEvents(10, TEST_PACKAGE, "tag4"));
    }

    @Test
    public void testUpdateExecutionStatsLocked_NoTimer() {
        mQuotaTracker.setCountLimit(SINGLE_CATEGORY, 3, 24 * HOUR_IN_MILLIS);
        final long now = mInjector.getElapsedRealtime();

        // Added in chronological order.
        logEventAt(now - 4 * HOUR_IN_MILLIS);
        logEventAt(now - HOUR_IN_MILLIS);
        logEventAt(now - 5 * MINUTE_IN_MILLIS);
        logEventAt(now - MINUTE_IN_MILLIS);

        // Test an app that hasn't had any activity.
        ExecutionStats expectedStats = new ExecutionStats();
        ExecutionStats inputStats = new ExecutionStats();

        inputStats.windowSizeMs = expectedStats.windowSizeMs = 12 * HOUR_IN_MILLIS;
        inputStats.countLimit = expectedStats.countLimit = 3;
        // Invalid time is now +24 hours since there are no sessions at all for the app.
        expectedStats.expirationTimeElapsed = now + 24 * HOUR_IN_MILLIS;
        mQuotaTracker.updateExecutionStatsLocked(TEST_USER_ID, "com.android.test.not.run", TEST_TAG,
                inputStats);
        assertEquals(expectedStats, inputStats);

        // Now test app that has had activity.

        inputStats.windowSizeMs = expectedStats.windowSizeMs = MINUTE_IN_MILLIS;
        // Invalid time is now since there was an event exactly windowSizeMs ago.
        expectedStats.expirationTimeElapsed = now;
        expectedStats.countInWindow = 1;
        mQuotaTracker.updateExecutionStatsLocked(TEST_USER_ID, TEST_PACKAGE, TEST_TAG, inputStats);
        assertEquals(expectedStats, inputStats);

        inputStats.windowSizeMs = expectedStats.windowSizeMs = 3 * MINUTE_IN_MILLIS;
        expectedStats.expirationTimeElapsed = now + 2 * MINUTE_IN_MILLIS;
        expectedStats.countInWindow = 1;
        mQuotaTracker.updateExecutionStatsLocked(TEST_USER_ID, TEST_PACKAGE, TEST_TAG, inputStats);
        assertEquals(expectedStats, inputStats);

        inputStats.windowSizeMs = expectedStats.windowSizeMs = 4 * MINUTE_IN_MILLIS;
        expectedStats.expirationTimeElapsed = now + 3 * MINUTE_IN_MILLIS;
        expectedStats.countInWindow = 1;
        mQuotaTracker.updateExecutionStatsLocked(TEST_USER_ID, TEST_PACKAGE, TEST_TAG, inputStats);
        assertEquals(expectedStats, inputStats);

        inputStats.windowSizeMs = expectedStats.windowSizeMs = 49 * MINUTE_IN_MILLIS;
        // Invalid time is now +44 minutes since the earliest session in the window is now-5
        // minutes.
        expectedStats.expirationTimeElapsed = now + 44 * MINUTE_IN_MILLIS;
        expectedStats.countInWindow = 2;
        mQuotaTracker.updateExecutionStatsLocked(TEST_USER_ID, TEST_PACKAGE, TEST_TAG, inputStats);
        assertEquals(expectedStats, inputStats);

        inputStats.windowSizeMs = expectedStats.windowSizeMs = 50 * MINUTE_IN_MILLIS;
        expectedStats.expirationTimeElapsed = now + 45 * MINUTE_IN_MILLIS;
        expectedStats.countInWindow = 2;
        mQuotaTracker.updateExecutionStatsLocked(TEST_USER_ID, TEST_PACKAGE, TEST_TAG, inputStats);
        assertEquals(expectedStats, inputStats);

        inputStats.windowSizeMs = expectedStats.windowSizeMs = HOUR_IN_MILLIS;
        // Invalid time is now since the event is at the very edge of the window
        // cutoff time.
        expectedStats.expirationTimeElapsed = now;
        expectedStats.countInWindow = 3;
        // App is at event count limit but the oldest session is at the edge of the window, so
        // in quota time is now.
        expectedStats.inQuotaTimeElapsed = now;
        mQuotaTracker.updateExecutionStatsLocked(TEST_USER_ID, TEST_PACKAGE, TEST_TAG, inputStats);
        assertEquals(expectedStats, inputStats);

        inputStats.windowSizeMs = expectedStats.windowSizeMs = 2 * HOUR_IN_MILLIS;
        expectedStats.expirationTimeElapsed = now + HOUR_IN_MILLIS;
        expectedStats.countInWindow = 3;
        expectedStats.inQuotaTimeElapsed = now + HOUR_IN_MILLIS;
        mQuotaTracker.updateExecutionStatsLocked(TEST_USER_ID, TEST_PACKAGE, TEST_TAG, inputStats);
        assertEquals(expectedStats, inputStats);

        inputStats.windowSizeMs = expectedStats.windowSizeMs = 5 * HOUR_IN_MILLIS;
        expectedStats.expirationTimeElapsed = now + HOUR_IN_MILLIS;
        expectedStats.countInWindow = 4;
        expectedStats.inQuotaTimeElapsed = now + 4 * HOUR_IN_MILLIS;
        mQuotaTracker.updateExecutionStatsLocked(TEST_USER_ID, TEST_PACKAGE, TEST_TAG, inputStats);
        assertEquals(expectedStats, inputStats);

        inputStats.windowSizeMs = expectedStats.windowSizeMs = 6 * HOUR_IN_MILLIS;
        expectedStats.expirationTimeElapsed = now + 2 * HOUR_IN_MILLIS;
        expectedStats.countInWindow = 4;
        expectedStats.inQuotaTimeElapsed = now + 5 * HOUR_IN_MILLIS;
        mQuotaTracker.updateExecutionStatsLocked(TEST_USER_ID, TEST_PACKAGE, TEST_TAG, inputStats);
        assertEquals(expectedStats, inputStats);
    }

    /**
     * Tests that getExecutionStatsLocked returns the correct stats.
     */
    @Test
    public void testGetExecutionStatsLocked_Values() {
        // The handler could cause changes to the cached stats, so prevent it from operating in
        // this test.
        Handler handler = mQuotaTracker.getHandler();
        spyOn(handler);
        doNothing().when(handler).handleMessage(any());

        mQuotaTracker.setCountLimit(RARE_BUCKET_CATEGORY, 3, 24 * HOUR_IN_MILLIS);
        mQuotaTracker.setCountLimit(FREQUENT_BUCKET_CATEGORY, 4, 8 * HOUR_IN_MILLIS);
        mQuotaTracker.setCountLimit(WORKING_SET_BUCKET_CATEGORY, 9, 2 * HOUR_IN_MILLIS);
        mQuotaTracker.setCountLimit(ACTIVE_BUCKET_CATEGORY, 10, 10 * MINUTE_IN_MILLIS);

        final long now = mInjector.getElapsedRealtime();

        logEventAt(now - 23 * HOUR_IN_MILLIS);
        logEventAt(now - 7 * HOUR_IN_MILLIS);
        logEventAt(now - 5 * HOUR_IN_MILLIS);
        logEventAt(now - 2 * HOUR_IN_MILLIS);
        logEventAt(now - 5 * MINUTE_IN_MILLIS);

        ExecutionStats expectedStats = new ExecutionStats();

        // Active
        expectedStats.expirationTimeElapsed = now + 5 * MINUTE_IN_MILLIS;
        expectedStats.windowSizeMs = 10 * MINUTE_IN_MILLIS;
        expectedStats.countLimit = 10;
        expectedStats.countInWindow = 1;
        mCategorizer.mCategoryToUse = ACTIVE_BUCKET_CATEGORY;
        assertEquals(expectedStats,
                mQuotaTracker.getExecutionStatsLocked(TEST_USER_ID, TEST_PACKAGE, TEST_TAG));

        // Working
        expectedStats.expirationTimeElapsed = now;
        expectedStats.windowSizeMs = 2 * HOUR_IN_MILLIS;
        expectedStats.countLimit = 9;
        expectedStats.countInWindow = 2;
        mCategorizer.mCategoryToUse = WORKING_SET_BUCKET_CATEGORY;
        assertEquals(expectedStats,
                mQuotaTracker.getExecutionStatsLocked(TEST_USER_ID, TEST_PACKAGE, TEST_TAG));

        // Frequent
        expectedStats.expirationTimeElapsed = now + HOUR_IN_MILLIS;
        expectedStats.windowSizeMs = 8 * HOUR_IN_MILLIS;
        expectedStats.countLimit = 4;
        expectedStats.countInWindow = 4;
        expectedStats.inQuotaTimeElapsed = now + HOUR_IN_MILLIS;
        mCategorizer.mCategoryToUse = FREQUENT_BUCKET_CATEGORY;
        assertEquals(expectedStats,
                mQuotaTracker.getExecutionStatsLocked(TEST_USER_ID, TEST_PACKAGE, TEST_TAG));

        // Rare
        expectedStats.expirationTimeElapsed = now + HOUR_IN_MILLIS;
        expectedStats.windowSizeMs = 24 * HOUR_IN_MILLIS;
        expectedStats.countLimit = 3;
        expectedStats.countInWindow = 5;
        expectedStats.inQuotaTimeElapsed = now + 19 * HOUR_IN_MILLIS;
        mCategorizer.mCategoryToUse = RARE_BUCKET_CATEGORY;
        assertEquals(expectedStats,
                mQuotaTracker.getExecutionStatsLocked(TEST_USER_ID, TEST_PACKAGE, TEST_TAG));
    }

    /**
     * Tests that getExecutionStatsLocked returns the correct stats soon after device startup.
     */
    @Test
    public void testGetExecutionStatsLocked_Values_BeginningOfTime() {
        // Set time to 3 minutes after boot.
        mInjector.mElapsedTime = 3 * MINUTE_IN_MILLIS;

        logEventAt(30_000);
        logEventAt(MINUTE_IN_MILLIS);
        logEventAt(2 * MINUTE_IN_MILLIS);

        mQuotaTracker.setCountLimit(SINGLE_CATEGORY, 10, 2 * HOUR_IN_MILLIS);

        ExecutionStats expectedStats = new ExecutionStats();

        expectedStats.windowSizeMs = 2 * HOUR_IN_MILLIS;
        expectedStats.countLimit = 10;
        expectedStats.countInWindow = 3;
        expectedStats.expirationTimeElapsed = 2 * HOUR_IN_MILLIS + 30_000;
        assertEquals(expectedStats,
                mQuotaTracker.getExecutionStatsLocked(TEST_USER_ID, TEST_PACKAGE, TEST_TAG));
    }

    @Test
    public void testisWithinQuota_GlobalQuotaFree() {
        mQuotaTracker.setCountLimit(SINGLE_CATEGORY, 0, 2 * HOUR_IN_MILLIS);
        mQuotaTracker.setQuotaFree(true);
        assertTrue(mQuotaTracker.isWithinQuota(TEST_USER_ID, TEST_PACKAGE, null));
        assertTrue(mQuotaTracker.isWithinQuota(TEST_USER_ID, "com.android.random.app", null));
    }

    @Test
    public void testisWithinQuota_UptcQuotaFree() {
        mQuotaTracker.setCountLimit(SINGLE_CATEGORY, 0, 2 * HOUR_IN_MILLIS);
        mQuotaTracker.setQuotaFree(TEST_USER_ID, TEST_PACKAGE, true);
        assertTrue(mQuotaTracker.isWithinQuota(TEST_USER_ID, TEST_PACKAGE, null));
        assertFalse(
                mQuotaTracker.isWithinQuota(TEST_USER_ID, "com.android.random.app", null));
    }

    @Test
    public void testisWithinQuota_UnderCount() {
        mQuotaTracker.setCountLimit(SINGLE_CATEGORY, 10, 2 * HOUR_IN_MILLIS);
        logEvents(5);
        assertTrue(mQuotaTracker.isWithinQuota(TEST_USER_ID, TEST_PACKAGE, TEST_TAG));
    }

    @Test
    public void testisWithinQuota_OverCount() {
        mQuotaTracker.setCountLimit(SINGLE_CATEGORY, 25, HOUR_IN_MILLIS);
        logEvents(TEST_USER_ID, "com.android.test.spam", TEST_TAG, 30);
        assertFalse(mQuotaTracker.isWithinQuota(TEST_USER_ID, "com.android.test.spam", TEST_TAG));
    }

    @Test
    public void testisWithinQuota_EqualsCount() {
        mQuotaTracker.setCountLimit(SINGLE_CATEGORY, 25, HOUR_IN_MILLIS);
        logEvents(25);
        assertFalse(mQuotaTracker.isWithinQuota(TEST_USER_ID, TEST_PACKAGE, TEST_TAG));
    }

    @Test
    public void testisWithinQuota_DifferentCategories() {
        mQuotaTracker.setCountLimit(RARE_BUCKET_CATEGORY, 3, 24 * HOUR_IN_MILLIS);
        mQuotaTracker.setCountLimit(FREQUENT_BUCKET_CATEGORY, 4, 24 * HOUR_IN_MILLIS);
        mQuotaTracker.setCountLimit(WORKING_SET_BUCKET_CATEGORY, 5, 24 * HOUR_IN_MILLIS);
        mQuotaTracker.setCountLimit(ACTIVE_BUCKET_CATEGORY, 6, 24 * HOUR_IN_MILLIS);

        for (int i = 0; i < 7; ++i) {
            logEvents(1);

            mCategorizer.mCategoryToUse = RARE_BUCKET_CATEGORY;
            assertEquals("Rare has incorrect quota status with " + (i + 1) + " events",
                    i < 2,
                    mQuotaTracker.isWithinQuota(TEST_USER_ID, TEST_PACKAGE, TEST_TAG));
            mCategorizer.mCategoryToUse = FREQUENT_BUCKET_CATEGORY;
            assertEquals("Frequent has incorrect quota status with " + (i + 1) + " events",
                    i < 3,
                    mQuotaTracker.isWithinQuota(TEST_USER_ID, TEST_PACKAGE, TEST_TAG));
            mCategorizer.mCategoryToUse = WORKING_SET_BUCKET_CATEGORY;
            assertEquals("Working has incorrect quota status with " + (i + 1) + " events",
                    i < 4,
                    mQuotaTracker.isWithinQuota(TEST_USER_ID, TEST_PACKAGE, TEST_TAG));
            mCategorizer.mCategoryToUse = ACTIVE_BUCKET_CATEGORY;
            assertEquals("Active has incorrect quota status with " + (i + 1) + " events",
                    i < 5,
                    mQuotaTracker.isWithinQuota(TEST_USER_ID, TEST_PACKAGE, TEST_TAG));
        }
    }

    @Test
    public void testMaybeScheduleCleanupAlarmLocked() {
        mQuotaTracker.setCountLimit(SINGLE_CATEGORY, 5, 24 * HOUR_IN_MILLIS);

        // No sessions saved yet.
        mQuotaTracker.maybeScheduleCleanupAlarmLocked();
        verify(mAlarmManager, never()).set(anyInt(), anyLong(), eq(TAG_CLEANUP), any(), any());

        // Test with only one timing session saved.
        final long now = mInjector.getElapsedRealtime();
        logEventAt(TEST_USER_ID, TEST_PACKAGE, TEST_TAG, now - 6 * HOUR_IN_MILLIS);
        mQuotaTracker.maybeScheduleCleanupAlarmLocked();
        verify(mAlarmManager, timeout(1000).times(1))
                .set(anyInt(), eq(now + 18 * HOUR_IN_MILLIS), eq(TAG_CLEANUP), any(), any());

        // Test with new (more recent) timing sessions saved. AlarmManger shouldn't be called again.
        logEventAt(TEST_USER_ID, TEST_PACKAGE, TEST_TAG, now - 3 * HOUR_IN_MILLIS);
        logEventAt(TEST_USER_ID, TEST_PACKAGE, TEST_TAG, now - HOUR_IN_MILLIS);
        mQuotaTracker.maybeScheduleCleanupAlarmLocked();
        verify(mAlarmManager, times(1))
                .set(anyInt(), eq(now + 18 * HOUR_IN_MILLIS), eq(TAG_CLEANUP), any(), any());
    }

    /**
     * Tests that maybeScheduleStartAlarm schedules an alarm for the right time.
     */
    @Test
    public void testMaybeScheduleStartAlarmLocked() {
        // logEvent calls maybeScheduleCleanupAlarmLocked which interferes with these tests
        // because it schedules an alarm too. Prevent it from doing so.
        spyOn(mQuotaTracker);
        doNothing().when(mQuotaTracker).maybeScheduleCleanupAlarmLocked();

        mQuotaTracker.setCountLimit(SINGLE_CATEGORY, 10, 8 * HOUR_IN_MILLIS);

        // No sessions saved yet.
        mQuotaTracker.maybeScheduleStartAlarmLocked(TEST_USER_ID, TEST_PACKAGE, TEST_TAG);
        verify(mAlarmManager, never()).set(anyInt(), anyLong(), eq(TAG_QUOTA_CHECK), any(), any());

        // Test with timing sessions out of window.
        final long now = mInjector.getElapsedRealtime();
        logEventsAt(TEST_USER_ID, TEST_PACKAGE, TEST_TAG, now - 10 * HOUR_IN_MILLIS, 20);
        mQuotaTracker.maybeScheduleStartAlarmLocked(TEST_USER_ID, TEST_PACKAGE, TEST_TAG);
        verify(mAlarmManager, never()).set(anyInt(), anyLong(), eq(TAG_QUOTA_CHECK), any(), any());

        // Test with timing sessions in window but still in quota.
        final long start = now - (6 * HOUR_IN_MILLIS);
        final long expectedAlarmTime = start + 8 * HOUR_IN_MILLIS;
        logEventsAt(TEST_USER_ID, TEST_PACKAGE, TEST_TAG, start, 5);
        mQuotaTracker.maybeScheduleStartAlarmLocked(TEST_USER_ID, TEST_PACKAGE, TEST_TAG);
        verify(mAlarmManager, never()).set(anyInt(), anyLong(), eq(TAG_QUOTA_CHECK), any(), any());

        // Add some more sessions, but still in quota.
        logEventsAt(TEST_USER_ID, TEST_PACKAGE, TEST_TAG, now - 3 * HOUR_IN_MILLIS, 1);
        logEventsAt(TEST_USER_ID, TEST_PACKAGE, TEST_TAG, now - HOUR_IN_MILLIS, 3);
        mQuotaTracker.maybeScheduleStartAlarmLocked(TEST_USER_ID, TEST_PACKAGE, TEST_TAG);
        verify(mAlarmManager, never()).set(anyInt(), anyLong(), eq(TAG_QUOTA_CHECK), any(), any());

        // Test when out of quota.
        logEventsAt(TEST_USER_ID, TEST_PACKAGE, TEST_TAG, now - HOUR_IN_MILLIS, 1);
        mQuotaTracker.maybeScheduleStartAlarmLocked(TEST_USER_ID, TEST_PACKAGE, TEST_TAG);
        verify(mAlarmManager, timeout(1000).times(1))
                .set(anyInt(), eq(expectedAlarmTime), eq(TAG_QUOTA_CHECK), any(), any());

        // Alarm already scheduled, so make sure it's not scheduled again.
        mQuotaTracker.maybeScheduleStartAlarmLocked(TEST_USER_ID, TEST_PACKAGE, TEST_TAG);
        verify(mAlarmManager, times(1))
                .set(anyInt(), eq(expectedAlarmTime), eq(TAG_QUOTA_CHECK), any(), any());
    }

    /** Tests that the start alarm is properly rescheduled if the app's category is changed. */
    @Test
    public void testMaybeScheduleStartAlarmLocked_CategoryChange() {
        // logEvent calls maybeScheduleCleanupAlarmLocked which interferes with these tests
        // because it schedules an alarm too. Prevent it from doing so.
        spyOn(mQuotaTracker);
        doNothing().when(mQuotaTracker).maybeScheduleCleanupAlarmLocked();

        mQuotaTracker.setCountLimit(RARE_BUCKET_CATEGORY, 10, 24 * HOUR_IN_MILLIS);
        mQuotaTracker.setCountLimit(FREQUENT_BUCKET_CATEGORY, 10, 8 * HOUR_IN_MILLIS);
        mQuotaTracker.setCountLimit(WORKING_SET_BUCKET_CATEGORY, 10, 2 * HOUR_IN_MILLIS);
        mQuotaTracker.setCountLimit(ACTIVE_BUCKET_CATEGORY, 10, 10 * MINUTE_IN_MILLIS);

        final long now = mInjector.getElapsedRealtime();

        // Affects rare bucket
        logEventsAt(TEST_USER_ID, TEST_PACKAGE, TEST_TAG, now - 12 * HOUR_IN_MILLIS, 9);
        // Affects frequent and rare buckets
        logEventsAt(TEST_USER_ID, TEST_PACKAGE, TEST_TAG, now - 4 * HOUR_IN_MILLIS, 4);
        // Affects working, frequent, and rare buckets
        final long outOfQuotaTime = now - HOUR_IN_MILLIS;
        logEventsAt(TEST_USER_ID, TEST_PACKAGE, TEST_TAG, outOfQuotaTime, 7);
        // Affects all buckets
        logEventsAt(TEST_USER_ID, TEST_PACKAGE, TEST_TAG, now - 5 * MINUTE_IN_MILLIS, 3);

        InOrder inOrder = inOrder(mAlarmManager);

        // Start in ACTIVE bucket.
        mCategorizer.mCategoryToUse = ACTIVE_BUCKET_CATEGORY;
        mQuotaTracker.maybeScheduleStartAlarmLocked(TEST_USER_ID, TEST_PACKAGE, TEST_TAG);
        inOrder.verify(mAlarmManager, never())
                .set(anyInt(), anyLong(), eq(TAG_QUOTA_CHECK), any(), any());
        inOrder.verify(mAlarmManager, never()).cancel(any(AlarmManager.OnAlarmListener.class));

        // And down from there.
        final long expectedWorkingAlarmTime = outOfQuotaTime + (2 * HOUR_IN_MILLIS);
        mCategorizer.mCategoryToUse = WORKING_SET_BUCKET_CATEGORY;
        mQuotaTracker.maybeScheduleStartAlarmLocked(TEST_USER_ID, TEST_PACKAGE, TEST_TAG);
        inOrder.verify(mAlarmManager, timeout(1000).times(1))
                .set(anyInt(), eq(expectedWorkingAlarmTime), eq(TAG_QUOTA_CHECK), any(), any());

        final long expectedFrequentAlarmTime = outOfQuotaTime + (8 * HOUR_IN_MILLIS);
        mCategorizer.mCategoryToUse = FREQUENT_BUCKET_CATEGORY;
        mQuotaTracker.maybeScheduleStartAlarmLocked(TEST_USER_ID, TEST_PACKAGE, TEST_TAG);
        inOrder.verify(mAlarmManager, timeout(1000).times(1))
                .set(anyInt(), eq(expectedFrequentAlarmTime), eq(TAG_QUOTA_CHECK), any(), any());

        final long expectedRareAlarmTime = outOfQuotaTime + (24 * HOUR_IN_MILLIS);
        mCategorizer.mCategoryToUse = RARE_BUCKET_CATEGORY;
        mQuotaTracker.maybeScheduleStartAlarmLocked(TEST_USER_ID, TEST_PACKAGE, TEST_TAG);
        inOrder.verify(mAlarmManager, timeout(1000).times(1))
                .set(anyInt(), eq(expectedRareAlarmTime), eq(TAG_QUOTA_CHECK), any(), any());

        // And back up again.
        mCategorizer.mCategoryToUse = FREQUENT_BUCKET_CATEGORY;
        mQuotaTracker.maybeScheduleStartAlarmLocked(TEST_USER_ID, TEST_PACKAGE, TEST_TAG);
        inOrder.verify(mAlarmManager, timeout(1000).times(1))
                .set(anyInt(), eq(expectedFrequentAlarmTime), eq(TAG_QUOTA_CHECK), any(), any());

        mCategorizer.mCategoryToUse = WORKING_SET_BUCKET_CATEGORY;
        mQuotaTracker.maybeScheduleStartAlarmLocked(TEST_USER_ID, TEST_PACKAGE, TEST_TAG);
        inOrder.verify(mAlarmManager, timeout(1000).times(1))
                .set(anyInt(), eq(expectedWorkingAlarmTime), eq(TAG_QUOTA_CHECK), any(), any());

        mCategorizer.mCategoryToUse = ACTIVE_BUCKET_CATEGORY;
        mQuotaTracker.maybeScheduleStartAlarmLocked(TEST_USER_ID, TEST_PACKAGE, TEST_TAG);
        inOrder.verify(mAlarmManager, timeout(1000).times(1))
                .cancel(any(AlarmManager.OnAlarmListener.class));
        inOrder.verify(mAlarmManager, timeout(1000).times(0))
                .set(anyInt(), anyLong(), eq(TAG_QUOTA_CHECK), any(), any());
    }

    @Test
    public void testConstantsUpdating_ValidValues() {
        mQuotaTracker.setCountLimit(SINGLE_CATEGORY, 0, 60_000);
        assertEquals(0, mQuotaTracker.getLimit(SINGLE_CATEGORY));
        assertEquals(60_000, mQuotaTracker.getWindowSizeMs(SINGLE_CATEGORY));
    }

    @Test
    public void testConstantsUpdating_InvalidValues() {
        // Test negatives.
        try {
            mQuotaTracker.setCountLimit(SINGLE_CATEGORY, -1, 5000);
            fail("Negative count limit didn't throw an exception");
        } catch (IllegalArgumentException e) {
            // Success
        }
        try {
            mQuotaTracker.setCountLimit(SINGLE_CATEGORY, 1, -1);
            fail("Negative count window size didn't throw an exception");
        } catch (IllegalArgumentException e) {
            // Success
        }

        // Test window sizes too low.
        mQuotaTracker.setCountLimit(SINGLE_CATEGORY, 0, 1);
        assertEquals(MIN_WINDOW_SIZE_MS, mQuotaTracker.getWindowSizeMs(SINGLE_CATEGORY));

        // Test window sizes too high.
        mQuotaTracker.setCountLimit(SINGLE_CATEGORY, 0, 365 * 24 * HOUR_IN_MILLIS);
        assertEquals(MAX_WINDOW_SIZE_MS, mQuotaTracker.getWindowSizeMs(SINGLE_CATEGORY));
    }

    /** Tests that events aren't counted when global quota is free. */
    @Test
    public void testLogEvent_GlobalQuotaFree() {
        mQuotaTracker.setQuotaFree(true);
        mQuotaTracker.setCountLimit(SINGLE_CATEGORY, 10, 2 * HOUR_IN_MILLIS);

        ExecutionStats stats =
                mQuotaTracker.getExecutionStatsLocked(TEST_USER_ID, TEST_PACKAGE, TEST_TAG);
        assertEquals(0, stats.countInWindow);

        for (int i = 0; i < 10; ++i) {
            mQuotaTracker.noteEvent(TEST_USER_ID, TEST_PACKAGE, TEST_TAG);
            advanceElapsedClock(10 * SECOND_IN_MILLIS);

            mQuotaTracker.updateExecutionStatsLocked(TEST_USER_ID, TEST_PACKAGE, TEST_TAG, stats);
            assertEquals(0, stats.countInWindow);
        }
    }

    /**
     * Tests that events are counted when global quota is not free.
     */
    @Test
    public void testLogEvent_GlobalQuotaNotFree() {
        mQuotaTracker.setQuotaFree(false);
        mQuotaTracker.setCountLimit(SINGLE_CATEGORY, 10, 2 * HOUR_IN_MILLIS);

        ExecutionStats stats =
                mQuotaTracker.getExecutionStatsLocked(TEST_USER_ID, TEST_PACKAGE, TEST_TAG);
        assertEquals(0, stats.countInWindow);

        for (int i = 0; i < 10; ++i) {
            mQuotaTracker.noteEvent(TEST_USER_ID, TEST_PACKAGE, TEST_TAG);
            advanceElapsedClock(10 * SECOND_IN_MILLIS);

            mQuotaTracker.updateExecutionStatsLocked(TEST_USER_ID, TEST_PACKAGE, TEST_TAG, stats);
            assertEquals(i + 1, stats.countInWindow);
        }
    }

    /** Tests that events aren't counted when the uptc quota is free. */
    @Test
    public void testLogEvent_UptcQuotaFree() {
        mQuotaTracker.setQuotaFree(TEST_USER_ID, TEST_PACKAGE, true);
        mQuotaTracker.setCountLimit(SINGLE_CATEGORY, 10, 2 * HOUR_IN_MILLIS);

        ExecutionStats stats =
                mQuotaTracker.getExecutionStatsLocked(TEST_USER_ID, TEST_PACKAGE, TEST_TAG);
        assertEquals(0, stats.countInWindow);

        for (int i = 0; i < 10; ++i) {
            mQuotaTracker.noteEvent(TEST_USER_ID, TEST_PACKAGE, TEST_TAG);
            advanceElapsedClock(10 * SECOND_IN_MILLIS);

            mQuotaTracker.updateExecutionStatsLocked(TEST_USER_ID, TEST_PACKAGE, TEST_TAG, stats);
            assertEquals(0, stats.countInWindow);
        }
    }

    /**
     * Tests that events are counted when UPTC quota is not free.
     */
    @Test
    public void testLogEvent_UptcQuotaNotFree() {
        mQuotaTracker.setQuotaFree(TEST_USER_ID, TEST_PACKAGE, false);
        mQuotaTracker.setCountLimit(SINGLE_CATEGORY, 10, 2 * HOUR_IN_MILLIS);

        ExecutionStats stats =
                mQuotaTracker.getExecutionStatsLocked(TEST_USER_ID, TEST_PACKAGE, TEST_TAG);
        assertEquals(0, stats.countInWindow);

        for (int i = 0; i < 10; ++i) {
            mQuotaTracker.noteEvent(TEST_USER_ID, TEST_PACKAGE, TEST_TAG);
            advanceElapsedClock(10 * SECOND_IN_MILLIS);

            mQuotaTracker.updateExecutionStatsLocked(TEST_USER_ID, TEST_PACKAGE, TEST_TAG, stats);
            assertEquals(i + 1, stats.countInWindow);
        }
    }

    /**
     * Tests that QuotaChangeListeners are notified when a UPTC reaches its count quota.
     */
    @Test
    public void testTracking_OutOfQuota() {
        spyOn(mQuotaChangeListener);

        mQuotaTracker.setCountLimit(SINGLE_CATEGORY, 10, 2 * HOUR_IN_MILLIS);
        logEvents(9);

        mQuotaTracker.noteEvent(TEST_USER_ID, TEST_PACKAGE, TEST_TAG);

        // Wait for some extra time to allow for processing.
        verify(mQuotaChangeListener, timeout(3 * SECOND_IN_MILLIS).times(1))
                .onQuotaStateChanged(eq(TEST_USER_ID), eq(TEST_PACKAGE), eq(TEST_TAG));
        assertFalse(mQuotaTracker.isWithinQuota(TEST_USER_ID, TEST_PACKAGE, TEST_TAG));
    }

    /**
     * Tests that QuotaChangeListeners are not incorrectly notified after a UPTC event is logged
     * quota times.
     */
    @Test
    public void testTracking_InQuota() {
        spyOn(mQuotaChangeListener);

        mQuotaTracker.setCountLimit(SINGLE_CATEGORY, 5, MINUTE_IN_MILLIS);

        // Log an event once per minute. This is well below the quota, so listeners should not be
        // notified.
        for (int i = 0; i < 10; i++) {
            advanceElapsedClock(MINUTE_IN_MILLIS);
            mQuotaTracker.noteEvent(TEST_USER_ID, TEST_PACKAGE, TEST_TAG);
        }

        // Wait for some extra time to allow for processing.
        verify(mQuotaChangeListener, timeout(3 * SECOND_IN_MILLIS).times(0))
                .onQuotaStateChanged(eq(TEST_USER_ID), eq(TEST_PACKAGE), eq(TEST_TAG));
        assertTrue(mQuotaTracker.isWithinQuota(TEST_USER_ID, TEST_PACKAGE, TEST_TAG));
    }
}
