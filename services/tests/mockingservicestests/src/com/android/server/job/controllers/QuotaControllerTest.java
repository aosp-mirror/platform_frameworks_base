/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.server.job.controllers;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.doNothing;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.inOrder;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.mock;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.mockitoSession;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.spyOn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.when;
import static com.android.server.job.JobSchedulerService.ACTIVE_INDEX;
import static com.android.server.job.JobSchedulerService.FREQUENT_INDEX;
import static com.android.server.job.JobSchedulerService.RARE_INDEX;
import static com.android.server.job.JobSchedulerService.WORKING_INDEX;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import android.app.AlarmManager;
import android.app.job.JobInfo;
import android.app.usage.UsageStatsManager;
import android.app.usage.UsageStatsManagerInternal;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManagerInternal;
import android.os.BatteryManager;
import android.os.BatteryManagerInternal;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import androidx.test.runner.AndroidJUnit4;

import com.android.server.LocalServices;
import com.android.server.job.JobSchedulerService;
import com.android.server.job.JobSchedulerService.Constants;
import com.android.server.job.controllers.QuotaController.TimingSession;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.MockitoSession;
import org.mockito.quality.Strictness;

import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

@RunWith(AndroidJUnit4.class)
public class QuotaControllerTest {
    private static final long SECOND_IN_MILLIS = 1000L;
    private static final long MINUTE_IN_MILLIS = 60 * SECOND_IN_MILLIS;
    private static final long HOUR_IN_MILLIS = 60 * MINUTE_IN_MILLIS;
    private static final String TAG_CLEANUP = "*job.cleanup*";
    private static final String TAG_QUOTA_CHECK = "*job.quota_check*";
    private static final long IN_QUOTA_BUFFER_MILLIS = 30 * SECOND_IN_MILLIS;
    private static final int CALLING_UID = 1000;
    private static final String SOURCE_PACKAGE = "com.android.frameworks.mockingservicestests";
    private static final int SOURCE_USER_ID = 0;

    private BroadcastReceiver mChargingReceiver;
    private Constants mConstants;
    private QuotaController mQuotaController;

    private MockitoSession mMockingSession;
    @Mock
    private AlarmManager mAlarmManager;
    @Mock
    private Context mContext;
    @Mock
    private JobSchedulerService mJobSchedulerService;
    @Mock
    private UsageStatsManagerInternal mUsageStatsManager;

    @Before
    public void setUp() {
        mMockingSession = mockitoSession()
                .initMocks(this)
                .strictness(Strictness.LENIENT)
                .mockStatic(LocalServices.class)
                .startMocking();
        // Make sure constants turn on QuotaController.
        mConstants = new Constants();
        mConstants.USE_HEARTBEATS = false;

        // Called in StateController constructor.
        when(mJobSchedulerService.getTestableContext()).thenReturn(mContext);
        when(mJobSchedulerService.getLock()).thenReturn(mJobSchedulerService);
        when(mJobSchedulerService.getConstants()).thenReturn(mConstants);
        // Called in QuotaController constructor.
        when(mContext.getMainLooper()).thenReturn(Looper.getMainLooper());
        when(mContext.getSystemService(Context.ALARM_SERVICE)).thenReturn(mAlarmManager);
        doReturn(mock(BatteryManagerInternal.class))
                .when(() -> LocalServices.getService(BatteryManagerInternal.class));
        doReturn(mUsageStatsManager)
                .when(() -> LocalServices.getService(UsageStatsManagerInternal.class));
        // Used in JobStatus.
        doReturn(mock(PackageManagerInternal.class))
                .when(() -> LocalServices.getService(PackageManagerInternal.class));

        // Freeze the clocks at this moment in time
        JobSchedulerService.sSystemClock =
                Clock.fixed(Clock.systemUTC().instant(), ZoneOffset.UTC);
        JobSchedulerService.sUptimeMillisClock =
                Clock.fixed(SystemClock.uptimeMillisClock().instant(), ZoneOffset.UTC);
        JobSchedulerService.sElapsedRealtimeClock =
                Clock.fixed(SystemClock.elapsedRealtimeClock().instant(), ZoneOffset.UTC);

        // Initialize real objects.
        // Capture the listeners.
        ArgumentCaptor<BroadcastReceiver> receiverCaptor =
                ArgumentCaptor.forClass(BroadcastReceiver.class);
        mQuotaController = new QuotaController(mJobSchedulerService);

        verify(mContext).registerReceiver(receiverCaptor.capture(), any());
        mChargingReceiver = receiverCaptor.getValue();
    }

    @After
    public void tearDown() {
        if (mMockingSession != null) {
            mMockingSession.finishMocking();
        }
    }

    private Clock getAdvancedClock(Clock clock, long incrementMs) {
        return Clock.offset(clock, Duration.ofMillis(incrementMs));
    }

    private void advanceElapsedClock(long incrementMs) {
        JobSchedulerService.sElapsedRealtimeClock = getAdvancedClock(
                JobSchedulerService.sElapsedRealtimeClock, incrementMs);
    }

    private void setCharging() {
        Intent intent = new Intent(BatteryManager.ACTION_CHARGING);
        mChargingReceiver.onReceive(mContext, intent);
    }

    private void setDischarging() {
        Intent intent = new Intent(BatteryManager.ACTION_DISCHARGING);
        mChargingReceiver.onReceive(mContext, intent);
    }

    private void setStandbyBucket(int bucketIndex) {
        int bucket;
        switch (bucketIndex) {
            case ACTIVE_INDEX:
                bucket = UsageStatsManager.STANDBY_BUCKET_ACTIVE;
                break;
            case WORKING_INDEX:
                bucket = UsageStatsManager.STANDBY_BUCKET_WORKING_SET;
                break;
            case FREQUENT_INDEX:
                bucket = UsageStatsManager.STANDBY_BUCKET_FREQUENT;
                break;
            case RARE_INDEX:
                bucket = UsageStatsManager.STANDBY_BUCKET_RARE;
                break;
            default:
                bucket = UsageStatsManager.STANDBY_BUCKET_NEVER;
        }
        when(mUsageStatsManager.getAppStandbyBucket(eq(SOURCE_PACKAGE), eq(SOURCE_USER_ID),
                anyLong())).thenReturn(bucket);
    }

    private void setStandbyBucket(int bucketIndex, JobStatus job) {
        setStandbyBucket(bucketIndex);
        job.setStandbyBucket(bucketIndex);
    }

    private JobStatus createJobStatus(String testTag, int jobId) {
        JobInfo jobInfo = new JobInfo.Builder(jobId,
                new ComponentName(mContext, "TestQuotaJobService"))
                .setMinimumLatency(Math.abs(jobId) + 1)
                .build();
        return JobStatus.createFromJobInfo(
                jobInfo, CALLING_UID, SOURCE_PACKAGE, SOURCE_USER_ID, testTag);
    }

    private TimingSession createTimingSession(long start, long duration, int count) {
        return new TimingSession(start, start + duration, count);
    }

    @Test
    public void testSaveTimingSession() {
        assertNull(mQuotaController.getTimingSessions(0, "com.android.test"));

        List<TimingSession> expected = new ArrayList<>();
        TimingSession one = new TimingSession(1, 10, 1);
        TimingSession two = new TimingSession(11, 20, 2);
        TimingSession thr = new TimingSession(21, 30, 3);

        mQuotaController.saveTimingSession(0, "com.android.test", one);
        expected.add(one);
        assertEquals(expected, mQuotaController.getTimingSessions(0, "com.android.test"));

        mQuotaController.saveTimingSession(0, "com.android.test", two);
        expected.add(two);
        assertEquals(expected, mQuotaController.getTimingSessions(0, "com.android.test"));

        mQuotaController.saveTimingSession(0, "com.android.test", thr);
        expected.add(thr);
        assertEquals(expected, mQuotaController.getTimingSessions(0, "com.android.test"));
    }

    @Test
    public void testDeleteObsoleteSessionsLocked() {
        final long now = JobSchedulerService.sElapsedRealtimeClock.millis();
        TimingSession one = createTimingSession(
                now - 10 * MINUTE_IN_MILLIS, 9 * MINUTE_IN_MILLIS, 3);
        TimingSession two = createTimingSession(
                now - (70 * MINUTE_IN_MILLIS), 9 * MINUTE_IN_MILLIS, 1);
        TimingSession thr = createTimingSession(
                now - (3 * HOUR_IN_MILLIS + 10 * MINUTE_IN_MILLIS), 9 * MINUTE_IN_MILLIS, 1);
        // Overlaps 24 hour boundary.
        TimingSession fou = createTimingSession(
                now - (24 * HOUR_IN_MILLIS + 2 * MINUTE_IN_MILLIS), 7 * MINUTE_IN_MILLIS, 1);
        // Way past the 24 hour boundary.
        TimingSession fiv = createTimingSession(
                now - (25 * HOUR_IN_MILLIS), 5 * MINUTE_IN_MILLIS, 4);
        List<TimingSession> expected = new ArrayList<>();
        // Added in correct (chronological) order.
        expected.add(fou);
        expected.add(thr);
        expected.add(two);
        expected.add(one);
        mQuotaController.saveTimingSession(0, "com.android.test", fiv);
        mQuotaController.saveTimingSession(0, "com.android.test", fou);
        mQuotaController.saveTimingSession(0, "com.android.test", thr);
        mQuotaController.saveTimingSession(0, "com.android.test", two);
        mQuotaController.saveTimingSession(0, "com.android.test", one);

        mQuotaController.deleteObsoleteSessionsLocked();

        assertEquals(expected, mQuotaController.getTimingSessions(0, "com.android.test"));
    }

    @Test
    public void testGetTrailingExecutionTimeLocked_NoTimer() {
        final long now = JobSchedulerService.sElapsedRealtimeClock.millis();
        // Added in chronological order.
        mQuotaController.saveTimingSession(0, "com.android.test",
                createTimingSession(now - (6 * HOUR_IN_MILLIS), 10 * MINUTE_IN_MILLIS, 5));
        mQuotaController.saveTimingSession(0, "com.android.test",
                createTimingSession(
                        now - (2 * HOUR_IN_MILLIS + MINUTE_IN_MILLIS), 6 * MINUTE_IN_MILLIS, 5));
        mQuotaController.saveTimingSession(0, "com.android.test",
                createTimingSession(now - (HOUR_IN_MILLIS), MINUTE_IN_MILLIS, 1));
        mQuotaController.saveTimingSession(0, "com.android.test",
                createTimingSession(
                        now - (HOUR_IN_MILLIS - 10 * MINUTE_IN_MILLIS), MINUTE_IN_MILLIS, 1));
        mQuotaController.saveTimingSession(0, "com.android.test",
                createTimingSession(now - 5 * MINUTE_IN_MILLIS, 4 * MINUTE_IN_MILLIS, 3));

        assertEquals(0, mQuotaController.getTrailingExecutionTimeLocked(0, "com.android.test",
                MINUTE_IN_MILLIS));
        assertEquals(2 * MINUTE_IN_MILLIS,
                mQuotaController.getTrailingExecutionTimeLocked(0, "com.android.test",
                        3 * MINUTE_IN_MILLIS));
        assertEquals(4 * MINUTE_IN_MILLIS,
                mQuotaController.getTrailingExecutionTimeLocked(0, "com.android.test",
                        5 * MINUTE_IN_MILLIS));
        assertEquals(4 * MINUTE_IN_MILLIS,
                mQuotaController.getTrailingExecutionTimeLocked(0, "com.android.test",
                        49 * MINUTE_IN_MILLIS));
        assertEquals(5 * MINUTE_IN_MILLIS,
                mQuotaController.getTrailingExecutionTimeLocked(0, "com.android.test",
                        50 * MINUTE_IN_MILLIS));
        assertEquals(6 * MINUTE_IN_MILLIS,
                mQuotaController.getTrailingExecutionTimeLocked(0, "com.android.test",
                        HOUR_IN_MILLIS));
        assertEquals(11 * MINUTE_IN_MILLIS,
                mQuotaController.getTrailingExecutionTimeLocked(0, "com.android.test",
                        2 * HOUR_IN_MILLIS));
        assertEquals(12 * MINUTE_IN_MILLIS,
                mQuotaController.getTrailingExecutionTimeLocked(0, "com.android.test",
                        3 * HOUR_IN_MILLIS));
        assertEquals(22 * MINUTE_IN_MILLIS,
                mQuotaController.getTrailingExecutionTimeLocked(0, "com.android.test",
                        6 * HOUR_IN_MILLIS));
    }

    @Test
    public void testMaybeScheduleCleanupAlarmLocked() {
        // No sessions saved yet.
        mQuotaController.maybeScheduleCleanupAlarmLocked();
        verify(mAlarmManager, never()).set(anyInt(), anyLong(), eq(TAG_CLEANUP), any(), any());

        // Test with only one timing session saved.
        final long now = JobSchedulerService.sElapsedRealtimeClock.millis();
        final long end = now - (6 * HOUR_IN_MILLIS - 5 * MINUTE_IN_MILLIS);
        mQuotaController.saveTimingSession(0, "com.android.test",
                new TimingSession(now - 6 * HOUR_IN_MILLIS, end, 1));
        mQuotaController.maybeScheduleCleanupAlarmLocked();
        verify(mAlarmManager, times(1))
                .set(anyInt(), eq(end + 24 * HOUR_IN_MILLIS), eq(TAG_CLEANUP), any(), any());

        // Test with new (more recent) timing sessions saved. AlarmManger shouldn't be called again.
        mQuotaController.saveTimingSession(0, "com.android.test",
                createTimingSession(now - 3 * HOUR_IN_MILLIS, MINUTE_IN_MILLIS, 1));
        mQuotaController.saveTimingSession(0, "com.android.test",
                createTimingSession(now - HOUR_IN_MILLIS, 3 * MINUTE_IN_MILLIS, 1));
        mQuotaController.maybeScheduleCleanupAlarmLocked();
        verify(mAlarmManager, times(1))
                .set(anyInt(), eq(end + 24 * HOUR_IN_MILLIS), eq(TAG_CLEANUP), any(), any());
    }

    @Test
    public void testMaybeScheduleStartAlarmLocked_WorkingSet() {
        // saveTimingSession calls maybeScheduleCleanupAlarmLocked which interferes with these tests
        // because it schedules an alarm too. Prevent it from doing so.
        spyOn(mQuotaController);
        doNothing().when(mQuotaController).maybeScheduleCleanupAlarmLocked();

        // Working set window size is 2 hours.
        final int standbyBucket = WORKING_INDEX;

        // No sessions saved yet.
        mQuotaController.maybeScheduleStartAlarmLocked(0, "com.android.test", standbyBucket);
        verify(mAlarmManager, never()).set(anyInt(), anyLong(), eq(TAG_QUOTA_CHECK), any(), any());

        // Test with timing sessions out of window.
        final long now = JobSchedulerService.sElapsedRealtimeClock.millis();
        mQuotaController.saveTimingSession(0, "com.android.test",
                createTimingSession(now - 10 * HOUR_IN_MILLIS, 5 * MINUTE_IN_MILLIS, 1));
        mQuotaController.maybeScheduleStartAlarmLocked(0, "com.android.test", standbyBucket);
        verify(mAlarmManager, never()).set(anyInt(), anyLong(), eq(TAG_QUOTA_CHECK), any(), any());

        // Test with timing sessions in window but still in quota.
        final long end = now - (2 * HOUR_IN_MILLIS - 5 * MINUTE_IN_MILLIS);
        // Counting backwards, the quota will come back one minute before the end.
        final long expectedAlarmTime =
                end - MINUTE_IN_MILLIS + 2 * HOUR_IN_MILLIS + IN_QUOTA_BUFFER_MILLIS;
        mQuotaController.saveTimingSession(0, "com.android.test",
                new TimingSession(now - 2 * HOUR_IN_MILLIS, end, 1));
        mQuotaController.maybeScheduleStartAlarmLocked(0, "com.android.test", standbyBucket);
        verify(mAlarmManager, never()).set(anyInt(), anyLong(), eq(TAG_QUOTA_CHECK), any(), any());

        // Add some more sessions, but still in quota.
        mQuotaController.saveTimingSession(0, "com.android.test",
                createTimingSession(now - HOUR_IN_MILLIS, MINUTE_IN_MILLIS, 1));
        mQuotaController.saveTimingSession(0, "com.android.test",
                createTimingSession(now - (50 * MINUTE_IN_MILLIS), 3 * MINUTE_IN_MILLIS, 1));
        mQuotaController.maybeScheduleStartAlarmLocked(0, "com.android.test", standbyBucket);
        verify(mAlarmManager, never()).set(anyInt(), anyLong(), eq(TAG_QUOTA_CHECK), any(), any());

        // Test when out of quota.
        mQuotaController.saveTimingSession(0, "com.android.test",
                createTimingSession(now - 30 * MINUTE_IN_MILLIS, 5 * MINUTE_IN_MILLIS, 1));
        mQuotaController.maybeScheduleStartAlarmLocked(0, "com.android.test", standbyBucket);
        verify(mAlarmManager, times(1))
                .set(anyInt(), eq(expectedAlarmTime), eq(TAG_QUOTA_CHECK), any(), any());

        // Alarm already scheduled, so make sure it's not scheduled again.
        mQuotaController.maybeScheduleStartAlarmLocked(0, "com.android.test", standbyBucket);
        verify(mAlarmManager, times(1))
                .set(anyInt(), eq(expectedAlarmTime), eq(TAG_QUOTA_CHECK), any(), any());
    }

    @Test
    public void testMaybeScheduleStartAlarmLocked_Frequent() {
        // saveTimingSession calls maybeScheduleCleanupAlarmLocked which interferes with these tests
        // because it schedules an alarm too. Prevent it from doing so.
        spyOn(mQuotaController);
        doNothing().when(mQuotaController).maybeScheduleCleanupAlarmLocked();

        // Frequent window size is 8 hours.
        final int standbyBucket = FREQUENT_INDEX;

        // No sessions saved yet.
        mQuotaController.maybeScheduleStartAlarmLocked(0, "com.android.test", standbyBucket);
        verify(mAlarmManager, never()).set(anyInt(), anyLong(), eq(TAG_QUOTA_CHECK), any(), any());

        // Test with timing sessions out of window.
        final long now = JobSchedulerService.sElapsedRealtimeClock.millis();
        mQuotaController.saveTimingSession(0, "com.android.test",
                createTimingSession(now - 10 * HOUR_IN_MILLIS, 5 * MINUTE_IN_MILLIS, 1));
        mQuotaController.maybeScheduleStartAlarmLocked(0, "com.android.test", standbyBucket);
        verify(mAlarmManager, never()).set(anyInt(), anyLong(), eq(TAG_QUOTA_CHECK), any(), any());

        // Test with timing sessions in window but still in quota.
        final long start = now - (6 * HOUR_IN_MILLIS);
        final long expectedAlarmTime = start + 8 * HOUR_IN_MILLIS + IN_QUOTA_BUFFER_MILLIS;
        mQuotaController.saveTimingSession(0, "com.android.test",
                createTimingSession(start, 5 * MINUTE_IN_MILLIS, 1));
        mQuotaController.maybeScheduleStartAlarmLocked(0, "com.android.test", standbyBucket);
        verify(mAlarmManager, never()).set(anyInt(), anyLong(), eq(TAG_QUOTA_CHECK), any(), any());

        // Add some more sessions, but still in quota.
        mQuotaController.saveTimingSession(0, "com.android.test",
                createTimingSession(now - 3 * HOUR_IN_MILLIS, MINUTE_IN_MILLIS, 1));
        mQuotaController.saveTimingSession(0, "com.android.test",
                createTimingSession(now - HOUR_IN_MILLIS, 3 * MINUTE_IN_MILLIS, 1));
        mQuotaController.maybeScheduleStartAlarmLocked(0, "com.android.test", standbyBucket);
        verify(mAlarmManager, never()).set(anyInt(), anyLong(), eq(TAG_QUOTA_CHECK), any(), any());

        // Test when out of quota.
        mQuotaController.saveTimingSession(0, "com.android.test",
                createTimingSession(now - HOUR_IN_MILLIS, MINUTE_IN_MILLIS, 1));
        mQuotaController.maybeScheduleStartAlarmLocked(0, "com.android.test", standbyBucket);
        verify(mAlarmManager, times(1))
                .set(anyInt(), eq(expectedAlarmTime), eq(TAG_QUOTA_CHECK), any(), any());

        // Alarm already scheduled, so make sure it's not scheduled again.
        mQuotaController.maybeScheduleStartAlarmLocked(0, "com.android.test", standbyBucket);
        verify(mAlarmManager, times(1))
                .set(anyInt(), eq(expectedAlarmTime), eq(TAG_QUOTA_CHECK), any(), any());
    }

    @Test
    public void testMaybeScheduleStartAlarmLocked_Rare() {
        // saveTimingSession calls maybeScheduleCleanupAlarmLocked which interferes with these tests
        // because it schedules an alarm too. Prevent it from doing so.
        spyOn(mQuotaController);
        doNothing().when(mQuotaController).maybeScheduleCleanupAlarmLocked();

        // Rare window size is 24 hours.
        final int standbyBucket = RARE_INDEX;

        // No sessions saved yet.
        mQuotaController.maybeScheduleStartAlarmLocked(0, "com.android.test", standbyBucket);
        verify(mAlarmManager, never()).set(anyInt(), anyLong(), eq(TAG_QUOTA_CHECK), any(), any());

        // Test with timing sessions out of window.
        final long now = JobSchedulerService.sElapsedRealtimeClock.millis();
        mQuotaController.saveTimingSession(0, "com.android.test",
                createTimingSession(now - 25 * HOUR_IN_MILLIS, 5 * MINUTE_IN_MILLIS, 1));
        mQuotaController.maybeScheduleStartAlarmLocked(0, "com.android.test", standbyBucket);
        verify(mAlarmManager, never()).set(anyInt(), anyLong(), eq(TAG_QUOTA_CHECK), any(), any());

        // Test with timing sessions in window but still in quota.
        final long start = now - (6 * HOUR_IN_MILLIS);
        // Counting backwards, the first minute in the session is over the allowed time, so it
        // needs to be excluded.
        final long expectedAlarmTime =
                start + MINUTE_IN_MILLIS + 24 * HOUR_IN_MILLIS + IN_QUOTA_BUFFER_MILLIS;
        mQuotaController.saveTimingSession(0, "com.android.test",
                createTimingSession(start, 5 * MINUTE_IN_MILLIS, 1));
        mQuotaController.maybeScheduleStartAlarmLocked(0, "com.android.test", standbyBucket);
        verify(mAlarmManager, never()).set(anyInt(), anyLong(), eq(TAG_QUOTA_CHECK), any(), any());

        // Add some more sessions, but still in quota.
        mQuotaController.saveTimingSession(0, "com.android.test",
                createTimingSession(now - 3 * HOUR_IN_MILLIS, MINUTE_IN_MILLIS, 1));
        mQuotaController.saveTimingSession(0, "com.android.test",
                createTimingSession(now - HOUR_IN_MILLIS, 3 * MINUTE_IN_MILLIS, 1));
        mQuotaController.maybeScheduleStartAlarmLocked(0, "com.android.test", standbyBucket);
        verify(mAlarmManager, never()).set(anyInt(), anyLong(), eq(TAG_QUOTA_CHECK), any(), any());

        // Test when out of quota.
        mQuotaController.saveTimingSession(0, "com.android.test",
                createTimingSession(now - HOUR_IN_MILLIS, 2 * MINUTE_IN_MILLIS, 1));
        mQuotaController.maybeScheduleStartAlarmLocked(0, "com.android.test", standbyBucket);
        verify(mAlarmManager, times(1))
                .set(anyInt(), eq(expectedAlarmTime), eq(TAG_QUOTA_CHECK), any(), any());

        // Alarm already scheduled, so make sure it's not scheduled again.
        mQuotaController.maybeScheduleStartAlarmLocked(0, "com.android.test", standbyBucket);
        verify(mAlarmManager, times(1))
                .set(anyInt(), eq(expectedAlarmTime), eq(TAG_QUOTA_CHECK), any(), any());
    }

    /** Tests that the start alarm is properly rescheduled if the app's bucket is changed. */
    @Test
    public void testMaybeScheduleStartAlarmLocked_BucketChange() {
        // saveTimingSession calls maybeScheduleCleanupAlarmLocked which interferes with these tests
        // because it schedules an alarm too. Prevent it from doing so.
        spyOn(mQuotaController);
        doNothing().when(mQuotaController).maybeScheduleCleanupAlarmLocked();

        final long now = JobSchedulerService.sElapsedRealtimeClock.millis();

        // Affects rare bucket
        mQuotaController.saveTimingSession(0, "com.android.test",
                createTimingSession(now - 12 * HOUR_IN_MILLIS, 9 * MINUTE_IN_MILLIS, 3));
        // Affects frequent and rare buckets
        mQuotaController.saveTimingSession(0, "com.android.test",
                createTimingSession(now - 4 * HOUR_IN_MILLIS, 4 * MINUTE_IN_MILLIS, 3));
        // Affects working, frequent, and rare buckets
        final long outOfQuotaTime = now - HOUR_IN_MILLIS;
        mQuotaController.saveTimingSession(0, "com.android.test",
                createTimingSession(outOfQuotaTime, 7 * MINUTE_IN_MILLIS, 10));
        // Affects all buckets
        mQuotaController.saveTimingSession(0, "com.android.test",
                createTimingSession(now - 5 * MINUTE_IN_MILLIS, 3 * MINUTE_IN_MILLIS, 3));

        InOrder inOrder = inOrder(mAlarmManager);

        // Start in ACTIVE bucket.
        mQuotaController.maybeScheduleStartAlarmLocked(0, "com.android.test", ACTIVE_INDEX);
        inOrder.verify(mAlarmManager, never()).set(anyInt(), anyLong(), eq(TAG_QUOTA_CHECK), any(),
                any());
        inOrder.verify(mAlarmManager, never()).cancel(any(AlarmManager.OnAlarmListener.class));

        // And down from there.
        final long expectedWorkingAlarmTime =
                outOfQuotaTime + (2 * HOUR_IN_MILLIS) + IN_QUOTA_BUFFER_MILLIS;
        mQuotaController.maybeScheduleStartAlarmLocked(0, "com.android.test", WORKING_INDEX);
        inOrder.verify(mAlarmManager, times(1))
                .set(anyInt(), eq(expectedWorkingAlarmTime), eq(TAG_QUOTA_CHECK), any(), any());

        final long expectedFrequentAlarmTime =
                outOfQuotaTime + (8 * HOUR_IN_MILLIS) + IN_QUOTA_BUFFER_MILLIS;
        mQuotaController.maybeScheduleStartAlarmLocked(0, "com.android.test", FREQUENT_INDEX);
        inOrder.verify(mAlarmManager, times(1))
                .set(anyInt(), eq(expectedFrequentAlarmTime), eq(TAG_QUOTA_CHECK), any(), any());

        final long expectedRareAlarmTime =
                outOfQuotaTime + (24 * HOUR_IN_MILLIS) + IN_QUOTA_BUFFER_MILLIS;
        mQuotaController.maybeScheduleStartAlarmLocked(0, "com.android.test", RARE_INDEX);
        inOrder.verify(mAlarmManager, times(1))
                .set(anyInt(), eq(expectedRareAlarmTime), eq(TAG_QUOTA_CHECK), any(), any());

        // And back up again.
        mQuotaController.maybeScheduleStartAlarmLocked(0, "com.android.test", FREQUENT_INDEX);
        inOrder.verify(mAlarmManager, times(1))
                .set(anyInt(), eq(expectedFrequentAlarmTime), eq(TAG_QUOTA_CHECK), any(), any());

        mQuotaController.maybeScheduleStartAlarmLocked(0, "com.android.test", WORKING_INDEX);
        inOrder.verify(mAlarmManager, times(1))
                .set(anyInt(), eq(expectedWorkingAlarmTime), eq(TAG_QUOTA_CHECK), any(), any());

        mQuotaController.maybeScheduleStartAlarmLocked(0, "com.android.test", ACTIVE_INDEX);
        inOrder.verify(mAlarmManager, never()).set(anyInt(), anyLong(), eq(TAG_QUOTA_CHECK), any(),
                any());
        inOrder.verify(mAlarmManager, times(1)).cancel(any(AlarmManager.OnAlarmListener.class));
    }

    /** Tests that QuotaController doesn't throttle if throttling is turned off. */
    @Test
    public void testThrottleToggling() throws Exception {
        setDischarging();
        mQuotaController.saveTimingSession(SOURCE_USER_ID, SOURCE_PACKAGE,
                createTimingSession(
                        JobSchedulerService.sElapsedRealtimeClock.millis() - HOUR_IN_MILLIS,
                        10 * MINUTE_IN_MILLIS, 4));
        JobStatus jobStatus = createJobStatus("testThrottleToggling", 1);
        setStandbyBucket(WORKING_INDEX, jobStatus); // 2 hour window
        mQuotaController.maybeStartTrackingJobLocked(jobStatus, null);
        assertFalse(jobStatus.isConstraintSatisfied(JobStatus.CONSTRAINT_WITHIN_QUOTA));

        mConstants.USE_HEARTBEATS = true;
        mQuotaController.onConstantsUpdatedLocked();
        Thread.sleep(SECOND_IN_MILLIS); // Job updates are done in the background.
        assertTrue(jobStatus.isConstraintSatisfied(JobStatus.CONSTRAINT_WITHIN_QUOTA));

        mConstants.USE_HEARTBEATS = false;
        mQuotaController.onConstantsUpdatedLocked();
        Thread.sleep(SECOND_IN_MILLIS); // Job updates are done in the background.
        assertFalse(jobStatus.isConstraintSatisfied(JobStatus.CONSTRAINT_WITHIN_QUOTA));
    }

    @Test
    public void testConstantsUpdating_ValidValues() {
        mConstants.QUOTA_CONTROLLER_ALLOWED_TIME_PER_PERIOD_MS = 5 * MINUTE_IN_MILLIS;
        mConstants.QUOTA_CONTROLLER_IN_QUOTA_BUFFER_MS = 2 * MINUTE_IN_MILLIS;
        mConstants.QUOTA_CONTROLLER_WINDOW_SIZE_ACTIVE_MS = 15 * MINUTE_IN_MILLIS;
        mConstants.QUOTA_CONTROLLER_WINDOW_SIZE_WORKING_MS = 30 * MINUTE_IN_MILLIS;
        mConstants.QUOTA_CONTROLLER_WINDOW_SIZE_FREQUENT_MS = 45 * MINUTE_IN_MILLIS;
        mConstants.QUOTA_CONTROLLER_WINDOW_SIZE_RARE_MS = 60 * MINUTE_IN_MILLIS;

        mQuotaController.onConstantsUpdatedLocked();

        assertEquals(5 * MINUTE_IN_MILLIS, mQuotaController.getAllowedTimePerPeriodMs());
        assertEquals(2 * MINUTE_IN_MILLIS, mQuotaController.getInQuotaBufferMs());
        assertEquals(15 * MINUTE_IN_MILLIS, mQuotaController.getBucketWindowSizes()[ACTIVE_INDEX]);
        assertEquals(30 * MINUTE_IN_MILLIS, mQuotaController.getBucketWindowSizes()[WORKING_INDEX]);
        assertEquals(45 * MINUTE_IN_MILLIS,
                mQuotaController.getBucketWindowSizes()[FREQUENT_INDEX]);
        assertEquals(60 * MINUTE_IN_MILLIS, mQuotaController.getBucketWindowSizes()[RARE_INDEX]);
    }

    @Test
    public void testConstantsUpdating_InvalidValues() {
        // Test negatives
        mConstants.QUOTA_CONTROLLER_ALLOWED_TIME_PER_PERIOD_MS = -MINUTE_IN_MILLIS;
        mConstants.QUOTA_CONTROLLER_IN_QUOTA_BUFFER_MS = -MINUTE_IN_MILLIS;
        mConstants.QUOTA_CONTROLLER_WINDOW_SIZE_ACTIVE_MS = -MINUTE_IN_MILLIS;
        mConstants.QUOTA_CONTROLLER_WINDOW_SIZE_WORKING_MS = -MINUTE_IN_MILLIS;
        mConstants.QUOTA_CONTROLLER_WINDOW_SIZE_FREQUENT_MS = -MINUTE_IN_MILLIS;
        mConstants.QUOTA_CONTROLLER_WINDOW_SIZE_RARE_MS = -MINUTE_IN_MILLIS;

        mQuotaController.onConstantsUpdatedLocked();

        assertEquals(MINUTE_IN_MILLIS, mQuotaController.getAllowedTimePerPeriodMs());
        assertEquals(0, mQuotaController.getInQuotaBufferMs());
        assertEquals(MINUTE_IN_MILLIS, mQuotaController.getBucketWindowSizes()[ACTIVE_INDEX]);
        assertEquals(MINUTE_IN_MILLIS, mQuotaController.getBucketWindowSizes()[WORKING_INDEX]);
        assertEquals(MINUTE_IN_MILLIS, mQuotaController.getBucketWindowSizes()[FREQUENT_INDEX]);
        assertEquals(MINUTE_IN_MILLIS, mQuotaController.getBucketWindowSizes()[RARE_INDEX]);

        // Test larger than a day. Controller should cap at one day.
        mConstants.QUOTA_CONTROLLER_ALLOWED_TIME_PER_PERIOD_MS = 25 * HOUR_IN_MILLIS;
        mConstants.QUOTA_CONTROLLER_IN_QUOTA_BUFFER_MS = 25 * HOUR_IN_MILLIS;
        mConstants.QUOTA_CONTROLLER_WINDOW_SIZE_ACTIVE_MS = 25 * HOUR_IN_MILLIS;
        mConstants.QUOTA_CONTROLLER_WINDOW_SIZE_WORKING_MS = 25 * HOUR_IN_MILLIS;
        mConstants.QUOTA_CONTROLLER_WINDOW_SIZE_FREQUENT_MS = 25 * HOUR_IN_MILLIS;
        mConstants.QUOTA_CONTROLLER_WINDOW_SIZE_RARE_MS = 25 * HOUR_IN_MILLIS;

        mQuotaController.onConstantsUpdatedLocked();

        assertEquals(24 * HOUR_IN_MILLIS, mQuotaController.getAllowedTimePerPeriodMs());
        assertEquals(5 * MINUTE_IN_MILLIS, mQuotaController.getInQuotaBufferMs());
        assertEquals(24 * HOUR_IN_MILLIS, mQuotaController.getBucketWindowSizes()[ACTIVE_INDEX]);
        assertEquals(24 * HOUR_IN_MILLIS, mQuotaController.getBucketWindowSizes()[WORKING_INDEX]);
        assertEquals(24 * HOUR_IN_MILLIS, mQuotaController.getBucketWindowSizes()[FREQUENT_INDEX]);
        assertEquals(24 * HOUR_IN_MILLIS, mQuotaController.getBucketWindowSizes()[RARE_INDEX]);
    }

    /** Tests that TimingSessions aren't saved when the device is charging. */
    @Test
    public void testTimerTracking_Charging() {
        setCharging();

        JobStatus jobStatus = createJobStatus("testTimerTracking_Charging", 1);
        mQuotaController.maybeStartTrackingJobLocked(jobStatus, null);

        assertNull(mQuotaController.getTimingSessions(SOURCE_USER_ID, SOURCE_PACKAGE));

        mQuotaController.prepareForExecutionLocked(jobStatus);
        advanceElapsedClock(5 * SECOND_IN_MILLIS);
        mQuotaController.maybeStopTrackingJobLocked(jobStatus, null, false);
        assertNull(mQuotaController.getTimingSessions(SOURCE_USER_ID, SOURCE_PACKAGE));
    }

    /** Tests that TimingSessions are saved properly when the device is discharging. */
    @Test
    public void testTimerTracking_Discharging() {
        setDischarging();

        JobStatus jobStatus = createJobStatus("testTimerTracking_Discharging", 1);
        mQuotaController.maybeStartTrackingJobLocked(jobStatus, null);

        assertNull(mQuotaController.getTimingSessions(SOURCE_USER_ID, SOURCE_PACKAGE));

        List<TimingSession> expected = new ArrayList<>();

        long start = JobSchedulerService.sElapsedRealtimeClock.millis();
        mQuotaController.prepareForExecutionLocked(jobStatus);
        advanceElapsedClock(5 * SECOND_IN_MILLIS);
        mQuotaController.maybeStopTrackingJobLocked(jobStatus, null, false);
        expected.add(createTimingSession(start, 5 * SECOND_IN_MILLIS, 1));
        assertEquals(expected, mQuotaController.getTimingSessions(SOURCE_USER_ID, SOURCE_PACKAGE));

        // Test overlapping jobs.
        JobStatus jobStatus2 = createJobStatus("testTimerTracking_Discharging", 2);
        mQuotaController.maybeStartTrackingJobLocked(jobStatus2, null);

        JobStatus jobStatus3 = createJobStatus("testTimerTracking_Discharging", 3);
        mQuotaController.maybeStartTrackingJobLocked(jobStatus3, null);

        advanceElapsedClock(SECOND_IN_MILLIS);

        start = JobSchedulerService.sElapsedRealtimeClock.millis();
        mQuotaController.maybeStartTrackingJobLocked(jobStatus, null);
        mQuotaController.prepareForExecutionLocked(jobStatus);
        advanceElapsedClock(10 * SECOND_IN_MILLIS);
        mQuotaController.prepareForExecutionLocked(jobStatus2);
        advanceElapsedClock(10 * SECOND_IN_MILLIS);
        mQuotaController.maybeStopTrackingJobLocked(jobStatus, null, false);
        advanceElapsedClock(10 * SECOND_IN_MILLIS);
        mQuotaController.prepareForExecutionLocked(jobStatus3);
        advanceElapsedClock(20 * SECOND_IN_MILLIS);
        mQuotaController.maybeStopTrackingJobLocked(jobStatus3, null, false);
        advanceElapsedClock(10 * SECOND_IN_MILLIS);
        mQuotaController.maybeStopTrackingJobLocked(jobStatus2, null, false);
        expected.add(createTimingSession(start, MINUTE_IN_MILLIS, 3));
        assertEquals(expected, mQuotaController.getTimingSessions(SOURCE_USER_ID, SOURCE_PACKAGE));
    }

    /**
     * Tests that TimingSessions are saved properly when the device alternates between
     * charging and discharging.
     */
    @Test
    public void testTimerTracking_ChargingAndDischarging() {
        JobStatus jobStatus = createJobStatus("testTimerTracking_ChargingAndDischarging", 1);
        mQuotaController.maybeStartTrackingJobLocked(jobStatus, null);
        JobStatus jobStatus2 = createJobStatus("testTimerTracking_ChargingAndDischarging", 2);
        mQuotaController.maybeStartTrackingJobLocked(jobStatus2, null);
        JobStatus jobStatus3 = createJobStatus("testTimerTracking_ChargingAndDischarging", 3);
        mQuotaController.maybeStartTrackingJobLocked(jobStatus3, null);
        assertNull(mQuotaController.getTimingSessions(SOURCE_USER_ID, SOURCE_PACKAGE));
        List<TimingSession> expected = new ArrayList<>();

        // A job starting while charging. Only the portion that runs during the discharging period
        // should be counted.
        setCharging();

        mQuotaController.prepareForExecutionLocked(jobStatus);
        advanceElapsedClock(10 * SECOND_IN_MILLIS);
        setDischarging();
        long start = JobSchedulerService.sElapsedRealtimeClock.millis();
        advanceElapsedClock(10 * SECOND_IN_MILLIS);
        mQuotaController.maybeStopTrackingJobLocked(jobStatus, jobStatus, true);
        expected.add(createTimingSession(start, 10 * SECOND_IN_MILLIS, 1));
        assertEquals(expected, mQuotaController.getTimingSessions(SOURCE_USER_ID, SOURCE_PACKAGE));

        advanceElapsedClock(SECOND_IN_MILLIS);

        // One job starts while discharging, spans a charging session, and ends after the charging
        // session. Only the portions during the discharging periods should be counted. This should
        // result in two TimingSessions. A second job starts while discharging and ends within the
        // charging session. Only the portion during the first discharging portion should be
        // counted. A third job starts and ends within the charging session. The third job
        // shouldn't be included in either job count.
        setDischarging();
        start = JobSchedulerService.sElapsedRealtimeClock.millis();
        mQuotaController.maybeStartTrackingJobLocked(jobStatus, null);
        mQuotaController.prepareForExecutionLocked(jobStatus);
        advanceElapsedClock(10 * SECOND_IN_MILLIS);
        mQuotaController.prepareForExecutionLocked(jobStatus2);
        advanceElapsedClock(10 * SECOND_IN_MILLIS);
        setCharging();
        expected.add(createTimingSession(start, 20 * SECOND_IN_MILLIS, 2));
        mQuotaController.prepareForExecutionLocked(jobStatus3);
        advanceElapsedClock(10 * SECOND_IN_MILLIS);
        mQuotaController.maybeStopTrackingJobLocked(jobStatus3, null, false);
        advanceElapsedClock(10 * SECOND_IN_MILLIS);
        mQuotaController.maybeStopTrackingJobLocked(jobStatus2, null, false);
        advanceElapsedClock(10 * SECOND_IN_MILLIS);
        setDischarging();
        start = JobSchedulerService.sElapsedRealtimeClock.millis();
        advanceElapsedClock(20 * SECOND_IN_MILLIS);
        mQuotaController.maybeStopTrackingJobLocked(jobStatus, null, false);
        expected.add(createTimingSession(start, 20 * SECOND_IN_MILLIS, 1));
        assertEquals(expected, mQuotaController.getTimingSessions(SOURCE_USER_ID, SOURCE_PACKAGE));

        // A job starting while discharging and ending while charging. Only the portion that runs
        // during the discharging period should be counted.
        setDischarging();
        start = JobSchedulerService.sElapsedRealtimeClock.millis();
        mQuotaController.maybeStartTrackingJobLocked(jobStatus2, null);
        mQuotaController.prepareForExecutionLocked(jobStatus2);
        advanceElapsedClock(10 * SECOND_IN_MILLIS);
        expected.add(createTimingSession(start, 10 * SECOND_IN_MILLIS, 1));
        setCharging();
        advanceElapsedClock(10 * SECOND_IN_MILLIS);
        mQuotaController.maybeStopTrackingJobLocked(jobStatus2, null, false);
        assertEquals(expected, mQuotaController.getTimingSessions(SOURCE_USER_ID, SOURCE_PACKAGE));
    }

    /** Tests that TimingSessions are saved properly when all the jobs are background jobs. */
    @Test
    public void testTimerTracking_AllBackground() {
        setDischarging();

        JobStatus jobStatus = createJobStatus("testTimerTracking_AllBackground", 1);
        mQuotaController.maybeStartTrackingJobLocked(jobStatus, null);

        assertNull(mQuotaController.getTimingSessions(SOURCE_USER_ID, SOURCE_PACKAGE));

        List<TimingSession> expected = new ArrayList<>();

        // Test single job.
        long start = JobSchedulerService.sElapsedRealtimeClock.millis();
        mQuotaController.prepareForExecutionLocked(jobStatus);
        advanceElapsedClock(5 * SECOND_IN_MILLIS);
        mQuotaController.maybeStopTrackingJobLocked(jobStatus, null, false);
        expected.add(createTimingSession(start, 5 * SECOND_IN_MILLIS, 1));
        assertEquals(expected, mQuotaController.getTimingSessions(SOURCE_USER_ID, SOURCE_PACKAGE));

        // Test overlapping jobs.
        JobStatus jobStatus2 = createJobStatus("testTimerTracking_AllBackground", 2);
        mQuotaController.maybeStartTrackingJobLocked(jobStatus2, null);

        JobStatus jobStatus3 = createJobStatus("testTimerTracking_AllBackground", 3);
        mQuotaController.maybeStartTrackingJobLocked(jobStatus3, null);

        advanceElapsedClock(SECOND_IN_MILLIS);

        start = JobSchedulerService.sElapsedRealtimeClock.millis();
        mQuotaController.maybeStartTrackingJobLocked(jobStatus, null);
        mQuotaController.prepareForExecutionLocked(jobStatus);
        advanceElapsedClock(10 * SECOND_IN_MILLIS);
        mQuotaController.prepareForExecutionLocked(jobStatus2);
        advanceElapsedClock(10 * SECOND_IN_MILLIS);
        mQuotaController.maybeStopTrackingJobLocked(jobStatus, null, false);
        advanceElapsedClock(10 * SECOND_IN_MILLIS);
        mQuotaController.prepareForExecutionLocked(jobStatus3);
        advanceElapsedClock(20 * SECOND_IN_MILLIS);
        mQuotaController.maybeStopTrackingJobLocked(jobStatus3, null, false);
        advanceElapsedClock(10 * SECOND_IN_MILLIS);
        mQuotaController.maybeStopTrackingJobLocked(jobStatus2, null, false);
        expected.add(createTimingSession(start, MINUTE_IN_MILLIS, 3));
        assertEquals(expected, mQuotaController.getTimingSessions(SOURCE_USER_ID, SOURCE_PACKAGE));
    }

    /** Tests that Timers don't count foreground jobs. */
    @Test
    public void testTimerTracking_AllForeground() {
        setDischarging();

        JobStatus jobStatus = createJobStatus("testTimerTracking_AllForeground", 1);
        jobStatus.uidActive = true;
        mQuotaController.maybeStartTrackingJobLocked(jobStatus, null);

        assertNull(mQuotaController.getTimingSessions(SOURCE_USER_ID, SOURCE_PACKAGE));

        mQuotaController.prepareForExecutionLocked(jobStatus);
        advanceElapsedClock(5 * SECOND_IN_MILLIS);
        mQuotaController.maybeStopTrackingJobLocked(jobStatus, null, false);
        assertNull(mQuotaController.getTimingSessions(SOURCE_USER_ID, SOURCE_PACKAGE));
    }

    /**
     * Tests that Timers properly track overlapping foreground and background jobs.
     */
    @Test
    public void testTimerTracking_ForegroundAndBackground() {
        setDischarging();

        JobStatus jobBg1 = createJobStatus("testTimerTracking_ForegroundAndBackground", 1);
        JobStatus jobBg2 = createJobStatus("testTimerTracking_ForegroundAndBackground", 2);
        JobStatus jobFg3 = createJobStatus("testTimerTracking_ForegroundAndBackground", 3);
        jobFg3.uidActive = true;
        mQuotaController.maybeStartTrackingJobLocked(jobBg1, null);
        mQuotaController.maybeStartTrackingJobLocked(jobBg2, null);
        mQuotaController.maybeStartTrackingJobLocked(jobFg3, null);
        assertNull(mQuotaController.getTimingSessions(SOURCE_USER_ID, SOURCE_PACKAGE));
        List<TimingSession> expected = new ArrayList<>();

        // UID starts out inactive.
        long start = JobSchedulerService.sElapsedRealtimeClock.millis();
        mQuotaController.prepareForExecutionLocked(jobBg1);
        advanceElapsedClock(10 * SECOND_IN_MILLIS);
        mQuotaController.maybeStopTrackingJobLocked(jobBg1, jobBg1, true);
        expected.add(createTimingSession(start, 10 * SECOND_IN_MILLIS, 1));
        assertEquals(expected, mQuotaController.getTimingSessions(SOURCE_USER_ID, SOURCE_PACKAGE));

        advanceElapsedClock(SECOND_IN_MILLIS);

        // Bg job starts while inactive, spans an entire active session, and ends after the
        // active session.
        // Fg job starts after the bg job and ends before the bg job.
        // Entire bg job duration should be counted since it started before active session. However,
        // count should only be 1 since Timer shouldn't count fg jobs.
        start = JobSchedulerService.sElapsedRealtimeClock.millis();
        mQuotaController.maybeStartTrackingJobLocked(jobBg2, null);
        mQuotaController.prepareForExecutionLocked(jobBg2);
        advanceElapsedClock(10 * SECOND_IN_MILLIS);
        mQuotaController.prepareForExecutionLocked(jobFg3);
        advanceElapsedClock(10 * SECOND_IN_MILLIS);
        mQuotaController.maybeStopTrackingJobLocked(jobFg3, null, false);
        advanceElapsedClock(10 * SECOND_IN_MILLIS);
        mQuotaController.maybeStopTrackingJobLocked(jobBg2, null, false);
        expected.add(createTimingSession(start, 30 * SECOND_IN_MILLIS, 1));
        assertEquals(expected, mQuotaController.getTimingSessions(SOURCE_USER_ID, SOURCE_PACKAGE));

        advanceElapsedClock(SECOND_IN_MILLIS);

        // Bg job 1 starts, then fg job starts. Bg job 1 job ends. Shortly after, uid goes
        // "inactive" and then bg job 2 starts. Then fg job ends.
        // This should result in two TimingSessions with a count of one each.
        start = JobSchedulerService.sElapsedRealtimeClock.millis();
        mQuotaController.maybeStartTrackingJobLocked(jobBg1, null);
        mQuotaController.maybeStartTrackingJobLocked(jobBg2, null);
        mQuotaController.maybeStartTrackingJobLocked(jobFg3, null);
        mQuotaController.prepareForExecutionLocked(jobBg1);
        advanceElapsedClock(10 * SECOND_IN_MILLIS);
        mQuotaController.prepareForExecutionLocked(jobFg3);
        advanceElapsedClock(10 * SECOND_IN_MILLIS);
        mQuotaController.maybeStopTrackingJobLocked(jobBg1, jobBg1, true);
        expected.add(createTimingSession(start, 20 * SECOND_IN_MILLIS, 1));
        advanceElapsedClock(10 * SECOND_IN_MILLIS); // UID "inactive" now
        start = JobSchedulerService.sElapsedRealtimeClock.millis();
        mQuotaController.prepareForExecutionLocked(jobBg2);
        advanceElapsedClock(10 * SECOND_IN_MILLIS);
        mQuotaController.maybeStopTrackingJobLocked(jobFg3, null, false);
        advanceElapsedClock(10 * SECOND_IN_MILLIS);
        mQuotaController.maybeStopTrackingJobLocked(jobBg2, null, false);
        expected.add(createTimingSession(start, 20 * SECOND_IN_MILLIS, 1));
        assertEquals(expected, mQuotaController.getTimingSessions(SOURCE_USER_ID, SOURCE_PACKAGE));
    }

    /**
     * Tests that a job is properly updated and JobSchedulerService is notified when a job reaches
     * its quota.
     */
    @Test
    public void testTracking_OutOfQuota() {
        JobStatus jobStatus = createJobStatus("testTracking_OutOfQuota", 1);
        mQuotaController.maybeStartTrackingJobLocked(jobStatus, null);
        setStandbyBucket(WORKING_INDEX, jobStatus); // 2 hour window
        // Now the package only has two seconds to run.
        final long remainingTimeMs = 2 * SECOND_IN_MILLIS;
        mQuotaController.saveTimingSession(SOURCE_USER_ID, SOURCE_PACKAGE,
                createTimingSession(
                        JobSchedulerService.sElapsedRealtimeClock.millis() - HOUR_IN_MILLIS,
                        10 * MINUTE_IN_MILLIS - remainingTimeMs, 1));

        // Start the job.
        mQuotaController.prepareForExecutionLocked(jobStatus);
        advanceElapsedClock(remainingTimeMs);

        // Wait for some extra time to allow for job processing.
        verify(mJobSchedulerService,
                timeout(remainingTimeMs + 2 * SECOND_IN_MILLIS).times(1))
                .onControllerStateChanged();
        assertFalse(jobStatus.isConstraintSatisfied(JobStatus.CONSTRAINT_WITHIN_QUOTA));
    }

    /**
     * Tests that a job is properly handled when it's at the edge of its quota and the old quota is
     * being phased out.
     */
    @Test
    public void testTracking_RollingQuota() {
        JobStatus jobStatus = createJobStatus("testTracking_OutOfQuota", 1);
        mQuotaController.maybeStartTrackingJobLocked(jobStatus, null);
        setStandbyBucket(WORKING_INDEX, jobStatus); // 2 hour window
        Handler handler = mQuotaController.getHandler();
        spyOn(handler);

        long now = JobSchedulerService.sElapsedRealtimeClock.millis();
        final long remainingTimeMs = SECOND_IN_MILLIS;
        // The package only has one second to run, but this session is at the edge of the rolling
        // window, so as the package "reaches its quota" it will have more to keep running.
        mQuotaController.saveTimingSession(SOURCE_USER_ID, SOURCE_PACKAGE,
                createTimingSession(now - 2 * HOUR_IN_MILLIS,
                        10 * MINUTE_IN_MILLIS - remainingTimeMs, 1));

        assertEquals(remainingTimeMs, mQuotaController.getRemainingExecutionTimeLocked(jobStatus));
        // Start the job.
        mQuotaController.prepareForExecutionLocked(jobStatus);
        advanceElapsedClock(remainingTimeMs);

        // Wait for some extra time to allow for job processing.
        verify(mJobSchedulerService,
                timeout(remainingTimeMs + 2 * SECOND_IN_MILLIS).times(0))
                .onControllerStateChanged();
        assertTrue(jobStatus.isConstraintSatisfied(JobStatus.CONSTRAINT_WITHIN_QUOTA));
        // The job used up the remaining quota, but in that time, the same amount of time in the
        // old TimingSession also fell out of the quota window, so it should still have the same
        // amount of remaining time left its quota.
        assertEquals(remainingTimeMs,
                mQuotaController.getRemainingExecutionTimeLocked(SOURCE_USER_ID, SOURCE_PACKAGE));
        verify(handler, atLeast(1)).sendMessageDelayed(any(), eq(remainingTimeMs));
    }
}
