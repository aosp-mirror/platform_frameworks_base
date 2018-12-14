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

import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.inOrder;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.mock;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.mockitoSession;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.spyOn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.when;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import android.app.AlarmManager;
import android.app.job.JobInfo;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManagerInternal;
import android.os.SystemClock;

import androidx.test.runner.AndroidJUnit4;

import com.android.server.LocalServices;
import com.android.server.job.JobSchedulerService;
import com.android.server.job.JobSchedulerService.Constants;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.MockitoSession;
import org.mockito.quality.Strictness;

import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;

@RunWith(AndroidJUnit4.class)
public class TimeControllerTest {
    private static final long SECOND_IN_MILLIS = 1000L;
    private static final long MINUTE_IN_MILLIS = 60 * SECOND_IN_MILLIS;
    private static final long HOUR_IN_MILLIS = 60 * MINUTE_IN_MILLIS;
    private static final String TAG_DEADLINE = "*job.deadline*";
    private static final String TAG_DELAY = "*job.delay*";
    private static final String SOURCE_PACKAGE = "com.android.frameworks.mockingservicestests";
    private static final int SOURCE_USER_ID = 0;

    private Constants mConstants;
    private TimeController mTimeController;

    private MockitoSession mMockingSession;
    @Mock
    private AlarmManager mAlarmManager;
    @Mock
    private Context mContext;
    @Mock
    private JobSchedulerService mJobSchedulerService;

    @Before
    public void setUp() {
        mMockingSession = mockitoSession()
                .initMocks(this)
                .strictness(Strictness.LENIENT)
                .mockStatic(LocalServices.class)
                .startMocking();
        // Use default constants for now.
        mConstants = new Constants();

        // Called in StateController constructor.
        when(mJobSchedulerService.getTestableContext()).thenReturn(mContext);
        when(mJobSchedulerService.getLock()).thenReturn(mJobSchedulerService);
        when(mJobSchedulerService.getConstants()).thenReturn(mConstants);
        // Called in TimeController constructor.
        when(mContext.getSystemService(Context.ALARM_SERVICE)).thenReturn(mAlarmManager);
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
        mTimeController = new TimeController(mJobSchedulerService);
        spyOn(mTimeController);
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

    private static JobInfo.Builder createJob() {
        return new JobInfo.Builder(101, new ComponentName("foo", "bar"));
    }

    private JobStatus createJobStatus(String testTag, JobInfo.Builder job) {
        JobInfo jobInfo = job.build();
        return JobStatus.createFromJobInfo(
                jobInfo, 1000, SOURCE_PACKAGE, SOURCE_USER_ID, testTag);
    }

    @Test
    public void testMaybeStartTrackingJobLocked_AlreadySatisfied() {
        JobStatus delaySatisfied = createJobStatus(
                "testMaybeStartTrackingJobLocked_AlreadySatisfied",
                createJob().setMinimumLatency(1));
        JobStatus deadlineSatisfied = createJobStatus(
                "testMaybeStartTrackingJobLocked_AlreadySatisfied",
                createJob().setOverrideDeadline(1));

        advanceElapsedClock(5);

        mTimeController.maybeStartTrackingJobLocked(delaySatisfied, null);
        mTimeController.maybeStartTrackingJobLocked(deadlineSatisfied, null);
        verify(mAlarmManager, never())
                .set(anyInt(), anyLong(), anyLong(), anyLong(), anyString(), any(), any(), any());
    }

    @Test
    public void testMaybeStartTrackingJobLocked_DelayInOrder_NoSkipping() {
        mConstants.TIME_CONTROLLER_SKIP_NOT_READY_JOBS = false;
        mTimeController.onConstantsUpdatedLocked();

        runTestMaybeStartTrackingJobLocked_DelayInOrder();
    }

    @Test
    public void testMaybeStartTrackingJobLocked_DelayInOrder_WithSkipping_AllReady() {
        mConstants.TIME_CONTROLLER_SKIP_NOT_READY_JOBS = true;
        mTimeController.onConstantsUpdatedLocked();

        doReturn(true).when(mTimeController).wouldBeReadyWithConstraintLocked(any(), anyInt());

        runTestMaybeStartTrackingJobLocked_DelayInOrder();
    }

    private void runTestMaybeStartTrackingJobLocked_DelayInOrder() {
        final long now = JobSchedulerService.sElapsedRealtimeClock.millis();

        JobStatus jobLatest = createJobStatus("testMaybeStartTrackingJobLocked_DelayInOrder",
                createJob().setMinimumLatency(HOUR_IN_MILLIS));
        JobStatus jobMiddle = createJobStatus("testMaybeStartTrackingJobLocked_DelayInOrder",
                createJob().setMinimumLatency(30 * MINUTE_IN_MILLIS));
        JobStatus jobEarliest = createJobStatus("testMaybeStartTrackingJobLocked_DelayInOrder",
                createJob().setMinimumLatency(5 * MINUTE_IN_MILLIS));

        InOrder inOrder = inOrder(mAlarmManager);

        mTimeController.maybeStartTrackingJobLocked(jobEarliest, null);
        inOrder.verify(mAlarmManager, times(1))
                .set(anyInt(), eq(now + 5 * MINUTE_IN_MILLIS), anyLong(), anyLong(), eq(TAG_DELAY),
                        any(), any(), any());
        mTimeController.maybeStartTrackingJobLocked(jobMiddle, null);
        inOrder.verify(mAlarmManager, never())
                .set(anyInt(), anyLong(), anyLong(), anyLong(), anyString(), any(), any(), any());
        mTimeController.maybeStartTrackingJobLocked(jobLatest, null);
        inOrder.verify(mAlarmManager, never())
                .set(anyInt(), anyLong(), anyLong(), anyLong(), anyString(), any(), any(), any());
    }

    @Test
    public void testMaybeStartTrackingJobLocked_DelayInOrder_WithSkipping_SomeNotReady() {
        mConstants.TIME_CONTROLLER_SKIP_NOT_READY_JOBS = true;
        mTimeController.onConstantsUpdatedLocked();
        final long now = JobSchedulerService.sElapsedRealtimeClock.millis();

        JobStatus jobLatest = createJobStatus("testMaybeStartTrackingJobLocked_DelayInOrder",
                createJob().setMinimumLatency(HOUR_IN_MILLIS));
        JobStatus jobMiddle = createJobStatus("testMaybeStartTrackingJobLocked_DelayInOrder",
                createJob().setMinimumLatency(30 * MINUTE_IN_MILLIS));
        JobStatus jobEarliest = createJobStatus("testMaybeStartTrackingJobLocked_DelayInOrder",
                createJob().setMinimumLatency(5 * MINUTE_IN_MILLIS));

        doReturn(true).when(mTimeController)
                .wouldBeReadyWithConstraintLocked(eq(jobLatest), anyInt());
        doReturn(false).when(mTimeController)
                .wouldBeReadyWithConstraintLocked(eq(jobMiddle), anyInt());
        doReturn(true).when(mTimeController)
                .wouldBeReadyWithConstraintLocked(eq(jobEarliest), anyInt());

        InOrder inOrder = inOrder(mAlarmManager);

        mTimeController.maybeStartTrackingJobLocked(jobEarliest, null);
        inOrder.verify(mAlarmManager, times(1))
                .set(anyInt(), eq(now + 5 * MINUTE_IN_MILLIS), anyLong(), anyLong(), eq(TAG_DELAY),
                        any(), any(), any());
        mTimeController.maybeStartTrackingJobLocked(jobMiddle, null);
        inOrder.verify(mAlarmManager, never())
                .set(anyInt(), anyLong(), anyLong(), anyLong(), anyString(), any(), any(), any());
        mTimeController.maybeStartTrackingJobLocked(jobLatest, null);
        inOrder.verify(mAlarmManager, never())
                .set(anyInt(), anyLong(), anyLong(), anyLong(), anyString(), any(), any(), any());
    }

    @Test
    public void testMaybeStartTrackingJobLocked_DelayReverseOrder_NoSkipping() {
        mConstants.TIME_CONTROLLER_SKIP_NOT_READY_JOBS = false;
        mTimeController.onConstantsUpdatedLocked();

        runTestMaybeStartTrackingJobLocked_DelayReverseOrder();
    }

    @Test
    public void testMaybeStartTrackingJobLocked_DelayReverseOrder_WithSkipping_AllReady() {
        mConstants.TIME_CONTROLLER_SKIP_NOT_READY_JOBS = true;
        mTimeController.onConstantsUpdatedLocked();

        doReturn(true).when(mTimeController).wouldBeReadyWithConstraintLocked(any(), anyInt());

        runTestMaybeStartTrackingJobLocked_DelayReverseOrder();
    }

    private void runTestMaybeStartTrackingJobLocked_DelayReverseOrder() {
        final long now = JobSchedulerService.sElapsedRealtimeClock.millis();

        JobStatus jobLatest = createJobStatus("testMaybeStartTrackingJobLocked_DelayReverseOrder",
                createJob().setMinimumLatency(HOUR_IN_MILLIS));
        JobStatus jobMiddle = createJobStatus("testMaybeStartTrackingJobLocked_DelayReverseOrder",
                createJob().setMinimumLatency(30 * MINUTE_IN_MILLIS));
        JobStatus jobEarliest = createJobStatus("testMaybeStartTrackingJobLocked_DelayReverseOrder",
                createJob().setMinimumLatency(5 * MINUTE_IN_MILLIS));

        InOrder inOrder = inOrder(mAlarmManager);

        mTimeController.maybeStartTrackingJobLocked(jobLatest, null);
        inOrder.verify(mAlarmManager, times(1))
                .set(anyInt(), eq(now + HOUR_IN_MILLIS), anyLong(), anyLong(), eq(TAG_DELAY), any(),
                        any(), any());
        mTimeController.maybeStartTrackingJobLocked(jobMiddle, null);
        inOrder.verify(mAlarmManager, times(1))
                .set(anyInt(), eq(now + 30 * MINUTE_IN_MILLIS), anyLong(), anyLong(), eq(TAG_DELAY),
                        any(), any(), any());
        mTimeController.maybeStartTrackingJobLocked(jobEarliest, null);
        inOrder.verify(mAlarmManager, times(1))
                .set(anyInt(), eq(now + 5 * MINUTE_IN_MILLIS), anyLong(), anyLong(), eq(TAG_DELAY),
                        any(), any(), any());
    }

    @Test
    public void testMaybeStartTrackingJobLocked_DelayReverseOrder_WithSkipping_SomeNotReady() {
        mConstants.TIME_CONTROLLER_SKIP_NOT_READY_JOBS = true;
        mTimeController.onConstantsUpdatedLocked();
        final long now = JobSchedulerService.sElapsedRealtimeClock.millis();

        JobStatus jobLatest = createJobStatus("testMaybeStartTrackingJobLocked_DelayReverseOrder",
                createJob().setMinimumLatency(HOUR_IN_MILLIS));
        JobStatus jobMiddle = createJobStatus("testMaybeStartTrackingJobLocked_DelayReverseOrder",
                createJob().setMinimumLatency(30 * MINUTE_IN_MILLIS));
        JobStatus jobEarliest = createJobStatus("testMaybeStartTrackingJobLocked_DelayReverseOrder",
                createJob().setMinimumLatency(5 * MINUTE_IN_MILLIS));

        doReturn(true).when(mTimeController)
                .wouldBeReadyWithConstraintLocked(eq(jobLatest), anyInt());
        doReturn(false).when(mTimeController)
                .wouldBeReadyWithConstraintLocked(eq(jobMiddle), anyInt());
        doReturn(true).when(mTimeController)
                .wouldBeReadyWithConstraintLocked(eq(jobEarliest), anyInt());

        InOrder inOrder = inOrder(mAlarmManager);

        mTimeController.maybeStartTrackingJobLocked(jobLatest, null);
        inOrder.verify(mAlarmManager, times(1))
                .set(anyInt(), eq(now + HOUR_IN_MILLIS), anyLong(), anyLong(), eq(TAG_DELAY), any(),
                        any(), any());
        mTimeController.maybeStartTrackingJobLocked(jobMiddle, null);
        // Middle alarm shouldn't be set since it won't be ready.
        inOrder.verify(mAlarmManager, never())
                .set(anyInt(), anyLong(), anyLong(), anyLong(), eq(TAG_DELAY), any(), any(), any());
        mTimeController.maybeStartTrackingJobLocked(jobEarliest, null);
        inOrder.verify(mAlarmManager, times(1))
                .set(anyInt(), eq(now + 5 * MINUTE_IN_MILLIS), anyLong(), anyLong(), eq(TAG_DELAY),
                        any(), any(), any());
    }

    @Test
    public void testMaybeStartTrackingJobLocked_DeadlineInOrder_NoSkipping() {
        mConstants.TIME_CONTROLLER_SKIP_NOT_READY_JOBS = false;
        mTimeController.onConstantsUpdatedLocked();

        runTestMaybeStartTrackingJobLocked_DeadlineInOrder();
    }

    @Test
    public void testMaybeStartTrackingJobLocked_DeadlineInOrder_WithSkipping_AllReady() {
        mConstants.TIME_CONTROLLER_SKIP_NOT_READY_JOBS = true;
        mTimeController.onConstantsUpdatedLocked();

        doReturn(true).when(mTimeController).wouldBeReadyWithConstraintLocked(any(), anyInt());

        runTestMaybeStartTrackingJobLocked_DeadlineInOrder();
    }

    private void runTestMaybeStartTrackingJobLocked_DeadlineInOrder() {
        final long now = JobSchedulerService.sElapsedRealtimeClock.millis();

        JobStatus jobLatest = createJobStatus("testMaybeStartTrackingJobLocked_DeadlineInOrder",
                createJob().setOverrideDeadline(HOUR_IN_MILLIS));
        JobStatus jobMiddle = createJobStatus("testMaybeStartTrackingJobLocked_DeadlineInOrder",
                createJob().setOverrideDeadline(30 * MINUTE_IN_MILLIS));
        JobStatus jobEarliest = createJobStatus("testMaybeStartTrackingJobLocked_DeadlineInOrder",
                createJob().setOverrideDeadline(5 * MINUTE_IN_MILLIS));

        InOrder inOrder = inOrder(mAlarmManager);

        mTimeController.maybeStartTrackingJobLocked(jobEarliest, null);
        inOrder.verify(mAlarmManager, times(1))
                .set(anyInt(), eq(now + 5 * MINUTE_IN_MILLIS), anyLong(), anyLong(),
                        eq(TAG_DEADLINE), any(), any(), any());
        mTimeController.maybeStartTrackingJobLocked(jobMiddle, null);
        inOrder.verify(mAlarmManager, never())
                .set(anyInt(), anyLong(), anyLong(), anyLong(), anyString(), any(), any(), any());
        mTimeController.maybeStartTrackingJobLocked(jobLatest, null);
        inOrder.verify(mAlarmManager, never())
                .set(anyInt(), anyLong(), anyLong(), anyLong(), anyString(), any(), any(), any());
    }

    @Test
    public void testMaybeStartTrackingJobLocked_DeadlineInOrder_WithSkipping_SomeNotReady() {
        mConstants.TIME_CONTROLLER_SKIP_NOT_READY_JOBS = true;
        mTimeController.onConstantsUpdatedLocked();
        final long now = JobSchedulerService.sElapsedRealtimeClock.millis();

        JobStatus jobLatest = createJobStatus("testMaybeStartTrackingJobLocked_DeadlineInOrder",
                createJob().setOverrideDeadline(HOUR_IN_MILLIS));
        JobStatus jobMiddle = createJobStatus("testMaybeStartTrackingJobLocked_DeadlineInOrder",
                createJob().setOverrideDeadline(30 * MINUTE_IN_MILLIS));
        JobStatus jobEarliest = createJobStatus("testMaybeStartTrackingJobLocked_DeadlineInOrder",
                createJob().setOverrideDeadline(5 * MINUTE_IN_MILLIS));

        doReturn(true).when(mTimeController)
                .wouldBeReadyWithConstraintLocked(eq(jobLatest), anyInt());
        doReturn(false).when(mTimeController)
                .wouldBeReadyWithConstraintLocked(eq(jobMiddle), anyInt());
        doReturn(true).when(mTimeController)
                .wouldBeReadyWithConstraintLocked(eq(jobEarliest), anyInt());

        InOrder inOrder = inOrder(mAlarmManager);

        mTimeController.maybeStartTrackingJobLocked(jobEarliest, null);
        inOrder.verify(mAlarmManager, times(1))
                .set(anyInt(), eq(now + 5 * MINUTE_IN_MILLIS), anyLong(), anyLong(),
                        eq(TAG_DEADLINE), any(), any(), any());
        mTimeController.maybeStartTrackingJobLocked(jobMiddle, null);
        inOrder.verify(mAlarmManager, never())
                .set(anyInt(), anyLong(), anyLong(), anyLong(), anyString(), any(), any(), any());
        mTimeController.maybeStartTrackingJobLocked(jobLatest, null);
        inOrder.verify(mAlarmManager, never())
                .set(anyInt(), anyLong(), anyLong(), anyLong(), anyString(), any(), any(), any());
    }

    @Test
    public void testMaybeStartTrackingJobLocked_DeadlineReverseOrder_NoSkipping() {
        mConstants.TIME_CONTROLLER_SKIP_NOT_READY_JOBS = false;
        mTimeController.onConstantsUpdatedLocked();

        runTestMaybeStartTrackingJobLocked_DeadlineReverseOrder();
    }

    @Test
    public void testMaybeStartTrackingJobLocked_DeadlineReverseOrder_WithSkipping_AllReady() {
        mConstants.TIME_CONTROLLER_SKIP_NOT_READY_JOBS = true;
        mTimeController.onConstantsUpdatedLocked();

        doReturn(true).when(mTimeController).wouldBeReadyWithConstraintLocked(any(), anyInt());

        runTestMaybeStartTrackingJobLocked_DeadlineReverseOrder();
    }

    private void runTestMaybeStartTrackingJobLocked_DeadlineReverseOrder() {
        final long now = JobSchedulerService.sElapsedRealtimeClock.millis();

        JobStatus jobLatest = createJobStatus(
                "testMaybeStartTrackingJobLocked_DeadlineReverseOrder",
                createJob().setOverrideDeadline(HOUR_IN_MILLIS));
        JobStatus jobMiddle = createJobStatus(
                "testMaybeStartTrackingJobLocked_DeadlineReverseOrder",
                createJob().setOverrideDeadline(30 * MINUTE_IN_MILLIS));
        JobStatus jobEarliest = createJobStatus(
                "testMaybeStartTrackingJobLocked_DeadlineReverseOrder",
                createJob().setOverrideDeadline(5 * MINUTE_IN_MILLIS));

        InOrder inOrder = inOrder(mAlarmManager);

        mTimeController.maybeStartTrackingJobLocked(jobLatest, null);
        inOrder.verify(mAlarmManager, times(1))
                .set(anyInt(), eq(now + HOUR_IN_MILLIS), anyLong(), anyLong(), eq(TAG_DEADLINE),
                        any(), any(), any());
        mTimeController.maybeStartTrackingJobLocked(jobMiddle, null);
        inOrder.verify(mAlarmManager, times(1))
                .set(anyInt(), eq(now + 30 * MINUTE_IN_MILLIS), anyLong(), anyLong(),
                        eq(TAG_DEADLINE), any(), any(), any());
        mTimeController.maybeStartTrackingJobLocked(jobEarliest, null);
        inOrder.verify(mAlarmManager, times(1))
                .set(anyInt(), eq(now + 5 * MINUTE_IN_MILLIS), anyLong(), anyLong(),
                        eq(TAG_DEADLINE), any(), any(), any());
    }

    @Test
    public void testMaybeStartTrackingJobLocked_DeadlineReverseOrder_WithSkipping_SomeNotReady() {
        mConstants.TIME_CONTROLLER_SKIP_NOT_READY_JOBS = true;
        mTimeController.onConstantsUpdatedLocked();
        final long now = JobSchedulerService.sElapsedRealtimeClock.millis();

        JobStatus jobLatest = createJobStatus(
                "testMaybeStartTrackingJobLocked_DeadlineReverseOrder",
                createJob().setOverrideDeadline(HOUR_IN_MILLIS));
        JobStatus jobMiddle = createJobStatus(
                "testMaybeStartTrackingJobLocked_DeadlineReverseOrder",
                createJob().setOverrideDeadline(30 * MINUTE_IN_MILLIS));
        JobStatus jobEarliest = createJobStatus(
                "testMaybeStartTrackingJobLocked_DeadlineReverseOrder",
                createJob().setOverrideDeadline(5 * MINUTE_IN_MILLIS));

        doReturn(true).when(mTimeController)
                .wouldBeReadyWithConstraintLocked(eq(jobLatest), anyInt());
        doReturn(false).when(mTimeController)
                .wouldBeReadyWithConstraintLocked(eq(jobMiddle), anyInt());
        doReturn(true).when(mTimeController)
                .wouldBeReadyWithConstraintLocked(eq(jobEarliest), anyInt());

        InOrder inOrder = inOrder(mAlarmManager);

        mTimeController.maybeStartTrackingJobLocked(jobLatest, null);
        inOrder.verify(mAlarmManager, times(1))
                .set(anyInt(), eq(now + HOUR_IN_MILLIS), anyLong(), anyLong(), eq(TAG_DEADLINE),
                        any(), any(), any());
        mTimeController.maybeStartTrackingJobLocked(jobMiddle, null);
        // Middle alarm should be skipped since the job wouldn't be ready.
        inOrder.verify(mAlarmManager, never())
                .set(anyInt(), anyLong(), anyLong(), anyLong(), eq(TAG_DEADLINE), any(), any(),
                        any());
        mTimeController.maybeStartTrackingJobLocked(jobEarliest, null);
        inOrder.verify(mAlarmManager, times(1))
                .set(anyInt(), eq(now + 5 * MINUTE_IN_MILLIS), anyLong(), anyLong(),
                        eq(TAG_DEADLINE), any(), any(), any());
    }

    @Test
    public void testJobSkipToggling() {
        final long now = JobSchedulerService.sElapsedRealtimeClock.millis();

        JobStatus jobLatest = createJobStatus(
                "testMaybeStartTrackingJobLocked_DeadlineReverseOrder",
                createJob().setOverrideDeadline(HOUR_IN_MILLIS));
        JobStatus jobEarliest = createJobStatus(
                "testMaybeStartTrackingJobLocked_DeadlineReverseOrder",
                createJob().setOverrideDeadline(5 * MINUTE_IN_MILLIS));

        doReturn(true).when(mTimeController)
                .wouldBeReadyWithConstraintLocked(eq(jobLatest), anyInt());
        doReturn(false).when(mTimeController)
                .wouldBeReadyWithConstraintLocked(eq(jobEarliest), anyInt());

        // Starting off with the skipping off, we should still set an alarm for the earlier job.
        mConstants.TIME_CONTROLLER_SKIP_NOT_READY_JOBS = false;
        mTimeController.onConstantsUpdatedLocked();
        InOrder inOrder = inOrder(mAlarmManager);

        mTimeController.maybeStartTrackingJobLocked(jobEarliest, null);
        mTimeController.maybeStartTrackingJobLocked(jobLatest, null);
        inOrder.verify(mAlarmManager, times(1))
                .set(anyInt(), eq(now + 5 * MINUTE_IN_MILLIS), anyLong(), anyLong(),
                        eq(TAG_DEADLINE), any(), any(), any());

        // Turn it on, use alarm for later job.
        mConstants.TIME_CONTROLLER_SKIP_NOT_READY_JOBS = true;
        mTimeController.onConstantsUpdatedLocked();

        inOrder.verify(mAlarmManager, times(1))
                .set(anyInt(), eq(now + HOUR_IN_MILLIS), anyLong(), anyLong(), eq(TAG_DEADLINE),
                        any(), any(), any());

        // Back off, use alarm for earlier job.
        mConstants.TIME_CONTROLLER_SKIP_NOT_READY_JOBS = false;
        mTimeController.onConstantsUpdatedLocked();

        inOrder.verify(mAlarmManager, times(1))
                .set(anyInt(), eq(now + 5 * MINUTE_IN_MILLIS), anyLong(), anyLong(),
                        eq(TAG_DEADLINE), any(), any(), any());
    }

    @Test
    public void testCheckExpiredDelaysAndResetAlarm_NoSkipping() {
        mConstants.TIME_CONTROLLER_SKIP_NOT_READY_JOBS = false;
        mTimeController.onConstantsUpdatedLocked();

        runTestCheckExpiredDelaysAndResetAlarm();
    }

    @Test
    public void testCheckExpiredDelaysAndResetAlarm_WithSkipping_AllReady() {
        mConstants.TIME_CONTROLLER_SKIP_NOT_READY_JOBS = true;
        mTimeController.onConstantsUpdatedLocked();

        doReturn(true).when(mTimeController).wouldBeReadyWithConstraintLocked(any(), anyInt());

        runTestCheckExpiredDelaysAndResetAlarm();
    }

    private void runTestCheckExpiredDelaysAndResetAlarm() {
        final long now = JobSchedulerService.sElapsedRealtimeClock.millis();

        JobStatus jobLatest = createJobStatus("testCheckExpiredDelaysAndResetAlarm",
                createJob().setMinimumLatency(HOUR_IN_MILLIS));
        JobStatus jobMiddle = createJobStatus("testCheckExpiredDelaysAndResetAlarm",
                createJob().setMinimumLatency(30 * MINUTE_IN_MILLIS));
        JobStatus jobEarliest = createJobStatus("testCheckExpiredDelaysAndResetAlarm",
                createJob().setMinimumLatency(5 * MINUTE_IN_MILLIS));

        InOrder inOrder = inOrder(mAlarmManager);

        mTimeController.maybeStartTrackingJobLocked(jobLatest, null);
        mTimeController.maybeStartTrackingJobLocked(jobMiddle, null);
        mTimeController.maybeStartTrackingJobLocked(jobEarliest, null);
        inOrder.verify(mAlarmManager, times(1))
                .set(anyInt(), eq(now + 5 * MINUTE_IN_MILLIS), anyLong(), anyLong(), eq(TAG_DELAY),
                        any(), any(), any());

        advanceElapsedClock(10 * MINUTE_IN_MILLIS);

        mTimeController.checkExpiredDelaysAndResetAlarm();
        assertTrue(jobEarliest.isConstraintSatisfied(JobStatus.CONSTRAINT_TIMING_DELAY));
        assertFalse(jobMiddle.isConstraintSatisfied(JobStatus.CONSTRAINT_TIMING_DELAY));
        assertFalse(jobLatest.isConstraintSatisfied(JobStatus.CONSTRAINT_TIMING_DELAY));
        inOrder.verify(mAlarmManager, times(1))
                .set(anyInt(), eq(now + 30 * MINUTE_IN_MILLIS), anyLong(), anyLong(), eq(TAG_DELAY),
                        any(), any(), any());

        advanceElapsedClock(30 * MINUTE_IN_MILLIS);

        mTimeController.checkExpiredDelaysAndResetAlarm();
        assertTrue(jobEarliest.isConstraintSatisfied(JobStatus.CONSTRAINT_TIMING_DELAY));
        assertTrue(jobMiddle.isConstraintSatisfied(JobStatus.CONSTRAINT_TIMING_DELAY));
        assertFalse(jobLatest.isConstraintSatisfied(JobStatus.CONSTRAINT_TIMING_DELAY));
        inOrder.verify(mAlarmManager, times(1))
                .set(anyInt(), eq(now + HOUR_IN_MILLIS), anyLong(), anyLong(), eq(TAG_DELAY), any(),
                        any(), any());

        advanceElapsedClock(30 * MINUTE_IN_MILLIS);

        mTimeController.checkExpiredDelaysAndResetAlarm();
        assertTrue(jobEarliest.isConstraintSatisfied(JobStatus.CONSTRAINT_TIMING_DELAY));
        assertTrue(jobMiddle.isConstraintSatisfied(JobStatus.CONSTRAINT_TIMING_DELAY));
        assertTrue(jobLatest.isConstraintSatisfied(JobStatus.CONSTRAINT_TIMING_DELAY));
        inOrder.verify(mAlarmManager, never())
                .set(anyInt(), anyLong(), anyLong(), anyLong(), anyString(), any(), any(), any());
    }

    @Test
    public void testCheckExpiredDelaysAndResetAlarm_WithSkipping_SomeNotReady() {
        mConstants.TIME_CONTROLLER_SKIP_NOT_READY_JOBS = true;
        mTimeController.onConstantsUpdatedLocked();
        final long now = JobSchedulerService.sElapsedRealtimeClock.millis();

        JobStatus jobLatest = createJobStatus("testCheckExpiredDelaysAndResetAlarm",
                createJob().setMinimumLatency(HOUR_IN_MILLIS));
        JobStatus jobMiddle = createJobStatus("testCheckExpiredDelaysAndResetAlarm",
                createJob().setMinimumLatency(30 * MINUTE_IN_MILLIS));
        JobStatus jobEarliest = createJobStatus("testCheckExpiredDelaysAndResetAlarm",
                createJob().setMinimumLatency(5 * MINUTE_IN_MILLIS));

        doReturn(true).when(mTimeController)
                .wouldBeReadyWithConstraintLocked(eq(jobLatest), anyInt());
        doReturn(false).when(mTimeController)
                .wouldBeReadyWithConstraintLocked(eq(jobMiddle), anyInt());
        doReturn(true).when(mTimeController)
                .wouldBeReadyWithConstraintLocked(eq(jobEarliest), anyInt());

        InOrder inOrder = inOrder(mAlarmManager);

        mTimeController.maybeStartTrackingJobLocked(jobLatest, null);
        mTimeController.maybeStartTrackingJobLocked(jobMiddle, null);
        mTimeController.maybeStartTrackingJobLocked(jobEarliest, null);
        inOrder.verify(mAlarmManager, times(1))
                .set(anyInt(), eq(now + 5 * MINUTE_IN_MILLIS), anyLong(), anyLong(), eq(TAG_DELAY),
                        any(), any(), any());

        advanceElapsedClock(10 * MINUTE_IN_MILLIS);

        mTimeController.checkExpiredDelaysAndResetAlarm();
        assertTrue(jobEarliest.isConstraintSatisfied(JobStatus.CONSTRAINT_TIMING_DELAY));
        assertFalse(jobMiddle.isConstraintSatisfied(JobStatus.CONSTRAINT_TIMING_DELAY));
        assertFalse(jobLatest.isConstraintSatisfied(JobStatus.CONSTRAINT_TIMING_DELAY));
        // Middle job wouldn't be ready, so its alarm should be skipped.
        inOrder.verify(mAlarmManager, times(1))
                .set(anyInt(), eq(now + HOUR_IN_MILLIS), anyLong(), anyLong(), eq(TAG_DELAY), any(),
                        any(), any());

        advanceElapsedClock(55 * MINUTE_IN_MILLIS);

        mTimeController.checkExpiredDelaysAndResetAlarm();
        assertTrue(jobEarliest.isConstraintSatisfied(JobStatus.CONSTRAINT_TIMING_DELAY));
        assertTrue(jobMiddle.isConstraintSatisfied(JobStatus.CONSTRAINT_TIMING_DELAY));
        assertTrue(jobLatest.isConstraintSatisfied(JobStatus.CONSTRAINT_TIMING_DELAY));
        inOrder.verify(mAlarmManager, never())
                .set(anyInt(), anyLong(), anyLong(), anyLong(), anyString(), any(), any(), any());
    }

    @Test
    public void testCheckExpiredDeadlinesAndResetAlarm_NoSkipping() {
        mConstants.TIME_CONTROLLER_SKIP_NOT_READY_JOBS = false;
        mTimeController.onConstantsUpdatedLocked();

        runTestCheckExpiredDeadlinesAndResetAlarm();
    }

    @Test
    public void testCheckExpiredDeadlinesAndResetAlarm_WithSkipping_AllReady() {
        mConstants.TIME_CONTROLLER_SKIP_NOT_READY_JOBS = true;
        mTimeController.onConstantsUpdatedLocked();

        doReturn(true).when(mTimeController).wouldBeReadyWithConstraintLocked(any(), anyInt());

        runTestCheckExpiredDeadlinesAndResetAlarm();
    }

    private void runTestCheckExpiredDeadlinesAndResetAlarm() {
        final long now = JobSchedulerService.sElapsedRealtimeClock.millis();

        JobStatus jobLatest = createJobStatus("testCheckExpiredDeadlinesAndResetAlarm",
                createJob().setOverrideDeadline(HOUR_IN_MILLIS));
        JobStatus jobMiddle = createJobStatus("testCheckExpiredDeadlinesAndResetAlarm",
                createJob().setOverrideDeadline(30 * MINUTE_IN_MILLIS));
        JobStatus jobEarliest = createJobStatus("testCheckExpiredDeadlinesAndResetAlarm",
                createJob().setOverrideDeadline(5 * MINUTE_IN_MILLIS));

        InOrder inOrder = inOrder(mAlarmManager);

        mTimeController.maybeStartTrackingJobLocked(jobLatest, null);
        mTimeController.maybeStartTrackingJobLocked(jobMiddle, null);
        mTimeController.maybeStartTrackingJobLocked(jobEarliest, null);
        inOrder.verify(mAlarmManager, times(1))
                .set(anyInt(), eq(now + 5 * MINUTE_IN_MILLIS), anyLong(), anyLong(),
                        eq(TAG_DEADLINE), any(), any(), any());

        advanceElapsedClock(10 * MINUTE_IN_MILLIS);

        mTimeController.checkExpiredDeadlinesAndResetAlarm();
        assertTrue(jobEarliest.isConstraintSatisfied(JobStatus.CONSTRAINT_DEADLINE));
        assertFalse(jobMiddle.isConstraintSatisfied(JobStatus.CONSTRAINT_DEADLINE));
        assertFalse(jobLatest.isConstraintSatisfied(JobStatus.CONSTRAINT_DEADLINE));
        inOrder.verify(mAlarmManager, times(1))
                .set(anyInt(), eq(now + 30 * MINUTE_IN_MILLIS), anyLong(), anyLong(),
                        eq(TAG_DEADLINE), any(), any(), any());

        advanceElapsedClock(30 * MINUTE_IN_MILLIS);

        mTimeController.checkExpiredDeadlinesAndResetAlarm();
        assertTrue(jobEarliest.isConstraintSatisfied(JobStatus.CONSTRAINT_DEADLINE));
        assertTrue(jobMiddle.isConstraintSatisfied(JobStatus.CONSTRAINT_DEADLINE));
        assertFalse(jobLatest.isConstraintSatisfied(JobStatus.CONSTRAINT_DEADLINE));
        inOrder.verify(mAlarmManager, times(1))
                .set(anyInt(), eq(now + HOUR_IN_MILLIS), anyLong(), anyLong(), eq(TAG_DEADLINE),
                        any(), any(), any());

        advanceElapsedClock(30 * MINUTE_IN_MILLIS);

        mTimeController.checkExpiredDeadlinesAndResetAlarm();
        assertTrue(jobEarliest.isConstraintSatisfied(JobStatus.CONSTRAINT_DEADLINE));
        assertTrue(jobMiddle.isConstraintSatisfied(JobStatus.CONSTRAINT_DEADLINE));
        assertTrue(jobLatest.isConstraintSatisfied(JobStatus.CONSTRAINT_DEADLINE));
        inOrder.verify(mAlarmManager, never())
                .set(anyInt(), anyLong(), anyLong(), anyLong(), anyString(), any(), any(), any());
    }

    @Test
    public void testCheckExpiredDeadlinesAndResetAlarm_WithSkipping_SomeNotReady() {
        mConstants.TIME_CONTROLLER_SKIP_NOT_READY_JOBS = true;
        mTimeController.onConstantsUpdatedLocked();
        final long now = JobSchedulerService.sElapsedRealtimeClock.millis();

        JobStatus jobLatest = createJobStatus("testCheckExpiredDeadlinesAndResetAlarm",
                createJob().setOverrideDeadline(HOUR_IN_MILLIS));
        JobStatus jobMiddle = createJobStatus("testCheckExpiredDeadlinesAndResetAlarm",
                createJob().setOverrideDeadline(30 * MINUTE_IN_MILLIS));
        JobStatus jobEarliest = createJobStatus("testCheckExpiredDeadlinesAndResetAlarm",
                createJob().setOverrideDeadline(5 * MINUTE_IN_MILLIS));

        doReturn(true).when(mTimeController)
                .wouldBeReadyWithConstraintLocked(eq(jobLatest), anyInt());
        doReturn(false).when(mTimeController)
                .wouldBeReadyWithConstraintLocked(eq(jobMiddle), anyInt());
        doReturn(true).when(mTimeController)
                .wouldBeReadyWithConstraintLocked(eq(jobEarliest), anyInt());

        InOrder inOrder = inOrder(mAlarmManager);

        mTimeController.maybeStartTrackingJobLocked(jobLatest, null);
        mTimeController.maybeStartTrackingJobLocked(jobMiddle, null);
        mTimeController.maybeStartTrackingJobLocked(jobEarliest, null);
        inOrder.verify(mAlarmManager, times(1))
                .set(anyInt(), eq(now + 5 * MINUTE_IN_MILLIS), anyLong(), anyLong(),
                        eq(TAG_DEADLINE), any(), any(), any());

        advanceElapsedClock(10 * MINUTE_IN_MILLIS);

        mTimeController.checkExpiredDeadlinesAndResetAlarm();
        assertTrue(jobEarliest.isConstraintSatisfied(JobStatus.CONSTRAINT_DEADLINE));
        assertFalse(jobMiddle.isConstraintSatisfied(JobStatus.CONSTRAINT_DEADLINE));
        assertFalse(jobLatest.isConstraintSatisfied(JobStatus.CONSTRAINT_DEADLINE));
        // Middle job wouldn't be ready, so its alarm should be skipped.
        inOrder.verify(mAlarmManager, times(1))
                .set(anyInt(), eq(now + HOUR_IN_MILLIS), anyLong(), anyLong(), eq(TAG_DEADLINE),
                        any(), any(), any());

        advanceElapsedClock(55 * MINUTE_IN_MILLIS);

        mTimeController.checkExpiredDeadlinesAndResetAlarm();
        assertTrue(jobEarliest.isConstraintSatisfied(JobStatus.CONSTRAINT_DEADLINE));
        assertTrue(jobMiddle.isConstraintSatisfied(JobStatus.CONSTRAINT_DEADLINE));
        assertTrue(jobLatest.isConstraintSatisfied(JobStatus.CONSTRAINT_DEADLINE));
        inOrder.verify(mAlarmManager, never())
                .set(anyInt(), anyLong(), anyLong(), anyLong(), anyString(), any(), any(), any());
    }

    @Test
    public void testEvaluateStateLocked_SkippingOff() {
        mConstants.TIME_CONTROLLER_SKIP_NOT_READY_JOBS = false;
        mTimeController.onConstantsUpdatedLocked();
        JobStatus job = createJobStatus("testEvaluateStateLocked_SkippingOff",
                createJob().setOverrideDeadline(HOUR_IN_MILLIS));

        mTimeController.evaluateStateLocked(job);
        verify(mAlarmManager, never())
                .set(anyInt(), anyLong(), anyLong(), anyLong(), anyString(), any(), any(), any());
    }

    @Test
    public void testEvaluateStateLocked_SkippingOn_Delay() {
        mConstants.TIME_CONTROLLER_SKIP_NOT_READY_JOBS = true;
        mTimeController.onConstantsUpdatedLocked();
        final long now = JobSchedulerService.sElapsedRealtimeClock.millis();

        JobStatus jobLatest = createJobStatus("testEvaluateStateLocked_SkippingOn_Delay",
                createJob().setMinimumLatency(HOUR_IN_MILLIS));
        JobStatus jobMiddle = createJobStatus("testEvaluateStateLocked_SkippingOn_Delay",
                createJob().setMinimumLatency(30 * MINUTE_IN_MILLIS));
        JobStatus jobEarliest = createJobStatus("testEvaluateStateLocked_SkippingOn_Delay",
                createJob().setMinimumLatency(5 * MINUTE_IN_MILLIS));

        doReturn(false).when(mTimeController)
                .wouldBeReadyWithConstraintLocked(eq(jobLatest), anyInt());
        doReturn(true).when(mTimeController)
                .wouldBeReadyWithConstraintLocked(eq(jobMiddle), anyInt());
        doReturn(false).when(mTimeController)
                .wouldBeReadyWithConstraintLocked(eq(jobEarliest), anyInt());

        InOrder inOrder = inOrder(mAlarmManager);

        mTimeController.maybeStartTrackingJobLocked(jobLatest, null);
        mTimeController.maybeStartTrackingJobLocked(jobMiddle, null);
        mTimeController.maybeStartTrackingJobLocked(jobEarliest, null);
        inOrder.verify(mAlarmManager, times(1))
                .set(anyInt(), eq(now + 30 * MINUTE_IN_MILLIS), anyLong(), anyLong(), eq(TAG_DELAY),
                        any(), any(), any());

        // Test evaluating something after the current deadline.
        doReturn(true).when(mTimeController)
                .wouldBeReadyWithConstraintLocked(eq(jobLatest), anyInt());
        mTimeController.evaluateStateLocked(jobLatest);
        inOrder.verify(mAlarmManager, never())
                .set(anyInt(), anyLong(), anyLong(), anyLong(), anyString(), any(), any(), any());

        // Test evaluating something before the current deadline.
        doReturn(true).when(mTimeController)
                .wouldBeReadyWithConstraintLocked(eq(jobEarliest), anyInt());
        mTimeController.evaluateStateLocked(jobEarliest);
        inOrder.verify(mAlarmManager, times(1))
                .set(anyInt(), eq(now + 5 * MINUTE_IN_MILLIS), anyLong(), anyLong(), eq(TAG_DELAY),
                        any(), any(), any());
        // Job goes back to not being ready. Middle is still true, so use that alarm.
        doReturn(false).when(mTimeController)
                .wouldBeReadyWithConstraintLocked(eq(jobEarliest), anyInt());
        mTimeController.evaluateStateLocked(jobEarliest);
        inOrder.verify(mAlarmManager, times(1))
                .set(anyInt(), eq(now + 30 * MINUTE_IN_MILLIS), anyLong(), anyLong(),
                        eq(TAG_DELAY), any(), any(), any());
        // Turn middle off. Latest is true, so use that alarm.
        doReturn(false).when(mTimeController)
                .wouldBeReadyWithConstraintLocked(eq(jobMiddle), anyInt());
        mTimeController.evaluateStateLocked(jobMiddle);
        inOrder.verify(mAlarmManager, times(1))
                .set(anyInt(), eq(now + HOUR_IN_MILLIS), anyLong(), anyLong(),
                        eq(TAG_DELAY), any(), any(), any());
    }

    @Test
    public void testEvaluateStateLocked_SkippingOn_Deadline() {
        mConstants.TIME_CONTROLLER_SKIP_NOT_READY_JOBS = true;
        mTimeController.onConstantsUpdatedLocked();
        final long now = JobSchedulerService.sElapsedRealtimeClock.millis();

        JobStatus jobLatest = createJobStatus("testEvaluateStateLocked_SkippingOn_Deadline",
                createJob().setOverrideDeadline(HOUR_IN_MILLIS));
        JobStatus jobMiddle = createJobStatus("testEvaluateStateLocked_SkippingOn_Deadline",
                createJob().setOverrideDeadline(30 * MINUTE_IN_MILLIS));
        JobStatus jobEarliest = createJobStatus("testEvaluateStateLocked_SkippingOn_Deadline",
                createJob().setOverrideDeadline(5 * MINUTE_IN_MILLIS));

        doReturn(false).when(mTimeController)
                .wouldBeReadyWithConstraintLocked(eq(jobLatest), anyInt());
        doReturn(true).when(mTimeController)
                .wouldBeReadyWithConstraintLocked(eq(jobMiddle), anyInt());
        doReturn(false).when(mTimeController)
                .wouldBeReadyWithConstraintLocked(eq(jobEarliest), anyInt());

        InOrder inOrder = inOrder(mAlarmManager);

        mTimeController.maybeStartTrackingJobLocked(jobLatest, null);
        mTimeController.maybeStartTrackingJobLocked(jobMiddle, null);
        mTimeController.maybeStartTrackingJobLocked(jobEarliest, null);
        inOrder.verify(mAlarmManager, times(1))
                .set(anyInt(), eq(now + 30 * MINUTE_IN_MILLIS), anyLong(), anyLong(),
                        eq(TAG_DEADLINE), any(), any(), any());

        // Test evaluating something after the current deadline.
        doReturn(true).when(mTimeController)
                .wouldBeReadyWithConstraintLocked(eq(jobLatest), anyInt());
        mTimeController.evaluateStateLocked(jobLatest);
        inOrder.verify(mAlarmManager, never())
                .set(anyInt(), anyLong(), anyLong(), anyLong(), anyString(), any(), any(), any());

        // Test evaluating something before the current deadline.
        doReturn(true).when(mTimeController)
                .wouldBeReadyWithConstraintLocked(eq(jobEarliest), anyInt());
        mTimeController.evaluateStateLocked(jobEarliest);
        inOrder.verify(mAlarmManager, times(1))
                .set(anyInt(), eq(now + 5 * MINUTE_IN_MILLIS), anyLong(), anyLong(),
                        eq(TAG_DEADLINE), any(), any(), any());
        // Job goes back to not being ready. Middle is still true, so use that alarm.
        doReturn(false).when(mTimeController)
                .wouldBeReadyWithConstraintLocked(eq(jobEarliest), anyInt());
        mTimeController.evaluateStateLocked(jobEarliest);
        inOrder.verify(mAlarmManager, times(1))
                .set(anyInt(), eq(now + 30 * MINUTE_IN_MILLIS), anyLong(), anyLong(),
                        eq(TAG_DEADLINE), any(), any(), any());
        // Turn middle off. Latest is true, so use that alarm.
        doReturn(false).when(mTimeController)
                .wouldBeReadyWithConstraintLocked(eq(jobMiddle), anyInt());
        mTimeController.evaluateStateLocked(jobMiddle);
        inOrder.verify(mAlarmManager, times(1))
                .set(anyInt(), eq(now + HOUR_IN_MILLIS), anyLong(), anyLong(),
                        eq(TAG_DEADLINE), any(), any(), any());
    }
}
