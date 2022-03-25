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

package com.android.server.job;

import static android.text.format.DateUtils.DAY_IN_MILLIS;
import static android.text.format.DateUtils.HOUR_IN_MILLIS;
import static android.text.format.DateUtils.MINUTE_IN_MILLIS;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.doNothing;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.mock;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.mockitoSession;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.spyOn;
import static com.android.server.job.JobSchedulerService.ACTIVE_INDEX;
import static com.android.server.job.JobSchedulerService.RARE_INDEX;
import static com.android.server.job.JobSchedulerService.sElapsedRealtimeClock;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;

import android.app.ActivityManager;
import android.app.ActivityManagerInternal;
import android.app.IActivityManager;
import android.app.UiModeManager;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.app.usage.UsageStatsManagerInternal;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.pm.PackageManagerInternal;
import android.content.res.Resources;
import android.net.ConnectivityManager;
import android.net.NetworkPolicyManager;
import android.os.BatteryManagerInternal;
import android.os.Looper;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemClock;

import com.android.server.AppStateTracker;
import com.android.server.AppStateTrackerImpl;
import com.android.server.DeviceIdleInternal;
import com.android.server.LocalServices;
import com.android.server.PowerAllowlistInternal;
import com.android.server.SystemServiceManager;
import com.android.server.job.controllers.JobStatus;
import com.android.server.pm.UserManagerInternal;
import com.android.server.usage.AppStandbyInternal;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoSession;
import org.mockito.quality.Strictness;

import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;

public class JobSchedulerServiceTest {
    private static final String TAG = JobSchedulerServiceTest.class.getSimpleName();

    private JobSchedulerService mService;

    private MockitoSession mMockingSession;
    @Mock
    private ActivityManagerInternal mActivityMangerInternal;
    @Mock
    private Context mContext;

    private class TestJobSchedulerService extends JobSchedulerService {
        TestJobSchedulerService(Context context) {
            super(context);
            mAppStateTracker = mock(AppStateTrackerImpl.class);
        }
    }

    @Before
    public void setUp() {
        mMockingSession = mockitoSession()
                .initMocks(this)
                .strictness(Strictness.LENIENT)
                .mockStatic(LocalServices.class)
                .mockStatic(ServiceManager.class)
                .startMocking();

        // Called in JobSchedulerService constructor.
        when(mContext.getMainLooper()).thenReturn(Looper.getMainLooper());
        doReturn(mActivityMangerInternal)
                .when(() -> LocalServices.getService(ActivityManagerInternal.class));
        doReturn(mock(AppStandbyInternal.class))
                .when(() -> LocalServices.getService(AppStandbyInternal.class));
        doReturn(mock(BatteryManagerInternal.class))
                .when(() -> LocalServices.getService(BatteryManagerInternal.class));
        doReturn(mock(UsageStatsManagerInternal.class))
                .when(() -> LocalServices.getService(UsageStatsManagerInternal.class));
        when(mContext.getString(anyInt())).thenReturn("some_test_string");
        // Called in BackgroundJobsController constructor.
        doReturn(mock(AppStateTrackerImpl.class))
                .when(() -> LocalServices.getService(AppStateTracker.class));
        // Called in ConnectivityController constructor.
        when(mContext.getSystemService(ConnectivityManager.class))
                .thenReturn(mock(ConnectivityManager.class));
        when(mContext.getSystemService(NetworkPolicyManager.class))
                .thenReturn(mock(NetworkPolicyManager.class));
        // Called in DeviceIdleJobsController constructor.
        doReturn(mock(DeviceIdleInternal.class))
                .when(() -> LocalServices.getService(DeviceIdleInternal.class));
        // Used in JobConcurrencyManager.
        doReturn(mock(UserManagerInternal.class))
                .when(() -> LocalServices.getService(UserManagerInternal.class));
        // Used in JobStatus.
        doReturn(mock(PackageManagerInternal.class))
                .when(() -> LocalServices.getService(PackageManagerInternal.class));
        // Called via IdleController constructor.
        when(mContext.getPackageManager()).thenReturn(mock(PackageManager.class));
        when(mContext.getResources()).thenReturn(mock(Resources.class));
        // Called in QuotaController constructor.
        doReturn(mock(PowerAllowlistInternal.class))
                .when(() -> LocalServices.getService(PowerAllowlistInternal.class));
        IActivityManager activityManager = ActivityManager.getService();
        spyOn(activityManager);
        try {
            doNothing().when(activityManager).registerUidObserver(any(), anyInt(), anyInt(), any());
        } catch (RemoteException e) {
            fail("registerUidObserver threw exception: " + e.getMessage());
        }
        // Called by QuotaTracker
        doReturn(mock(SystemServiceManager.class))
                .when(() -> LocalServices.getService(SystemServiceManager.class));

        JobSchedulerService.sSystemClock = Clock.fixed(Clock.systemUTC().instant(), ZoneOffset.UTC);
        JobSchedulerService.sElapsedRealtimeClock =
                Clock.fixed(SystemClock.elapsedRealtimeClock().instant(), ZoneOffset.UTC);
        // Called by DeviceIdlenessTracker
        when(mContext.getSystemService(UiModeManager.class)).thenReturn(mock(UiModeManager.class));

        mService = new TestJobSchedulerService(mContext);
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

    private static JobInfo.Builder createJobInfo() {
        return createJobInfo(351);
    }

    private static JobInfo.Builder createJobInfo(int jobId) {
        return new JobInfo.Builder(jobId, new ComponentName("foo", "bar"));
    }

    private JobStatus createJobStatus(String testTag, JobInfo.Builder jobInfoBuilder) {
        return createJobStatus(testTag, jobInfoBuilder, 1234);
    }

    private JobStatus createJobStatus(String testTag, JobInfo.Builder jobInfoBuilder,
            int callingUid) {
        return JobStatus.createFromJobInfo(
                jobInfoBuilder.build(), callingUid, "com.android.test", 0, testTag);
    }

    @Test
    public void testGetMinJobExecutionGuaranteeMs() {
        JobStatus ejMax = createJobStatus("testGetMinJobExecutionGuaranteeMs",
                createJobInfo(1).setExpedited(true));
        JobStatus ejHigh = createJobStatus("testGetMinJobExecutionGuaranteeMs",
                createJobInfo(2).setExpedited(true).setPriority(JobInfo.PRIORITY_HIGH));
        JobStatus ejMaxDowngraded = createJobStatus("testGetMinJobExecutionGuaranteeMs",
                createJobInfo(3).setExpedited(true));
        JobStatus ejHighDowngraded = createJobStatus("testGetMinJobExecutionGuaranteeMs",
                createJobInfo(4).setExpedited(true).setPriority(JobInfo.PRIORITY_HIGH));
        JobStatus jobHigh = createJobStatus("testGetMinJobExecutionGuaranteeMs",
                createJobInfo(5).setPriority(JobInfo.PRIORITY_HIGH));
        JobStatus jobDef = createJobStatus("testGetMinJobExecutionGuaranteeMs",
                createJobInfo(6));

        spyOn(ejMax);
        spyOn(ejHigh);
        spyOn(ejMaxDowngraded);
        spyOn(ejHighDowngraded);
        spyOn(jobHigh);
        spyOn(jobDef);

        when(ejMax.shouldTreatAsExpeditedJob()).thenReturn(true);
        when(ejHigh.shouldTreatAsExpeditedJob()).thenReturn(true);
        when(ejMaxDowngraded.shouldTreatAsExpeditedJob()).thenReturn(false);
        when(ejHighDowngraded.shouldTreatAsExpeditedJob()).thenReturn(false);
        when(jobHigh.shouldTreatAsExpeditedJob()).thenReturn(false);
        when(jobDef.shouldTreatAsExpeditedJob()).thenReturn(false);

        assertEquals(mService.mConstants.RUNTIME_MIN_EJ_GUARANTEE_MS,
                mService.getMinJobExecutionGuaranteeMs(ejMax));
        assertEquals(mService.mConstants.RUNTIME_MIN_EJ_GUARANTEE_MS,
                mService.getMinJobExecutionGuaranteeMs(ejHigh));
        assertEquals(mService.mConstants.RUNTIME_MIN_HIGH_PRIORITY_GUARANTEE_MS,
                mService.getMinJobExecutionGuaranteeMs(ejMaxDowngraded));
        assertEquals(mService.mConstants.RUNTIME_MIN_HIGH_PRIORITY_GUARANTEE_MS,
                mService.getMinJobExecutionGuaranteeMs(ejHighDowngraded));
        assertEquals(mService.mConstants.RUNTIME_MIN_HIGH_PRIORITY_GUARANTEE_MS,
                mService.getMinJobExecutionGuaranteeMs(jobHigh));
        assertEquals(mService.mConstants.RUNTIME_MIN_GUARANTEE_MS,
                mService.getMinJobExecutionGuaranteeMs(jobDef));
    }

    /**
     * Confirm that {@link JobSchedulerService#getRescheduleJobForPeriodic(JobStatus)} returns a job
     * with the correct delay and deadline constraints if the periodic job is scheduled with the
     * minimum possible period.
     */
    @Test
    public void testGetRescheduleJobForPeriodic_minPeriod() {
        final long now = sElapsedRealtimeClock.millis();
        JobStatus job = createJobStatus("testGetRescheduleJobForPeriodic_insideWindow",
                createJobInfo().setPeriodic(15 * MINUTE_IN_MILLIS));
        final long nextWindowStartTime = now + 15 * MINUTE_IN_MILLIS;
        final long nextWindowEndTime = now + 30 * MINUTE_IN_MILLIS;

        for (int i = 0; i < 25; i++) {
            JobStatus rescheduledJob = mService.getRescheduleJobForPeriodic(job);
            assertEquals(nextWindowStartTime, rescheduledJob.getEarliestRunTime());
            assertEquals(nextWindowEndTime, rescheduledJob.getLatestRunTimeElapsed());
            advanceElapsedClock(30_000); // 30 seconds
        }

        for (int i = 0; i < 5; i++) {
            // Window buffering in last 1/6 of window.
            JobStatus rescheduledJob = mService.getRescheduleJobForPeriodic(job);
            assertEquals(nextWindowStartTime + i * 30_000, rescheduledJob.getEarliestRunTime());
            assertEquals(nextWindowEndTime, rescheduledJob.getLatestRunTimeElapsed());
            advanceElapsedClock(30_000); // 30 seconds
        }
    }

    /**
     * Confirm that {@link JobSchedulerService#getRescheduleJobForPeriodic(JobStatus)} returns a job
     * with the correct delay and deadline constraints if the periodic job is scheduled with a
     * period that's too large.
     */
    @Test
    public void testGetRescheduleJobForPeriodic_largePeriod() {
        final long now = sElapsedRealtimeClock.millis();
        JobStatus job = createJobStatus("testGetRescheduleJobForPeriodic_insideWindow",
                createJobInfo().setPeriodic(2 * 365 * DAY_IN_MILLIS));
        assertEquals(now, job.getEarliestRunTime());
        // Periods are capped at 365 days (1 year).
        assertEquals(now + 365 * DAY_IN_MILLIS, job.getLatestRunTimeElapsed());
        final long nextWindowStartTime = now + 365 * DAY_IN_MILLIS;
        final long nextWindowEndTime = nextWindowStartTime + 365 * DAY_IN_MILLIS;

        JobStatus rescheduledJob = mService.getRescheduleJobForPeriodic(job);
        assertEquals(nextWindowStartTime, rescheduledJob.getEarliestRunTime());
        assertEquals(nextWindowEndTime, rescheduledJob.getLatestRunTimeElapsed());
    }

    /**
     * Confirm that {@link JobSchedulerService#getRescheduleJobForPeriodic(JobStatus)} returns a job
     * with the correct delay and deadline constraints if the periodic job is completed and
     * rescheduled while run in its expected running window.
     */
    @Test
    public void testGetRescheduleJobForPeriodic_insideWindow() {
        final long now = sElapsedRealtimeClock.millis();
        JobStatus job = createJobStatus("testGetRescheduleJobForPeriodic_insideWindow",
                createJobInfo().setPeriodic(HOUR_IN_MILLIS));
        final long nextWindowStartTime = now + HOUR_IN_MILLIS;
        final long nextWindowEndTime = now + 2 * HOUR_IN_MILLIS;

        JobStatus rescheduledJob = mService.getRescheduleJobForPeriodic(job);
        assertEquals(nextWindowStartTime, rescheduledJob.getEarliestRunTime());
        assertEquals(nextWindowEndTime, rescheduledJob.getLatestRunTimeElapsed());

        advanceElapsedClock(10 * MINUTE_IN_MILLIS); // now + 10 minutes

        rescheduledJob = mService.getRescheduleJobForPeriodic(job);
        assertEquals(nextWindowStartTime, rescheduledJob.getEarliestRunTime());
        assertEquals(nextWindowEndTime, rescheduledJob.getLatestRunTimeElapsed());

        advanceElapsedClock(20 * MINUTE_IN_MILLIS); // now + 30 minutes

        rescheduledJob = mService.getRescheduleJobForPeriodic(job);
        assertEquals(nextWindowStartTime, rescheduledJob.getEarliestRunTime());
        assertEquals(nextWindowEndTime, rescheduledJob.getLatestRunTimeElapsed());

        advanceElapsedClock(25 * MINUTE_IN_MILLIS); // now + 55 minutes

        rescheduledJob = mService.getRescheduleJobForPeriodic(job);
        // Shifted because it's close to the end of the window.
        assertEquals(nextWindowStartTime + 5 * MINUTE_IN_MILLIS,
                rescheduledJob.getEarliestRunTime());
        assertEquals(nextWindowEndTime, rescheduledJob.getLatestRunTimeElapsed());

        advanceElapsedClock(4 * MINUTE_IN_MILLIS); // now + 59 minutes

        rescheduledJob = mService.getRescheduleJobForPeriodic(job);
        // Shifted because it's close to the end of the window.
        assertEquals(nextWindowStartTime + 9 * MINUTE_IN_MILLIS,
                rescheduledJob.getEarliestRunTime());
        assertEquals(nextWindowEndTime, rescheduledJob.getLatestRunTimeElapsed());
    }

    /**
     * Confirm that {@link JobSchedulerService#getRescheduleJobForPeriodic(JobStatus)} returns a job
     * with an extra delay and correct deadline constraint if the periodic job is completed near the
     * end of its expected running window.
     */
    @Test
    public void testGetRescheduleJobForPeriodic_closeToEndOfWindow() {
        JobStatus frequentJob = createJobStatus(
                "testGetRescheduleJobForPeriodic_closeToEndOfWindow",
                createJobInfo().setPeriodic(15 * MINUTE_IN_MILLIS));
        long now = sElapsedRealtimeClock.millis();
        long nextWindowStartTime = now + 15 * MINUTE_IN_MILLIS;
        long nextWindowEndTime = now + 30 * MINUTE_IN_MILLIS;

        // At the beginning of the window. Next window should be unaffected.
        JobStatus rescheduledJob = mService.getRescheduleJobForPeriodic(frequentJob);
        assertEquals(nextWindowStartTime, rescheduledJob.getEarliestRunTime());
        assertEquals(nextWindowEndTime, rescheduledJob.getLatestRunTimeElapsed());

        // Halfway through window. Next window should be unaffected.
        advanceElapsedClock((long) (7.5 * MINUTE_IN_MILLIS));
        rescheduledJob = mService.getRescheduleJobForPeriodic(frequentJob);
        assertEquals(nextWindowStartTime, rescheduledJob.getEarliestRunTime());
        assertEquals(nextWindowEndTime, rescheduledJob.getLatestRunTimeElapsed());

        // In last 1/6 of window. Next window start time should be shifted slightly.
        advanceElapsedClock(6 * MINUTE_IN_MILLIS);
        rescheduledJob = mService.getRescheduleJobForPeriodic(frequentJob);
        assertEquals(nextWindowStartTime + MINUTE_IN_MILLIS,
                rescheduledJob.getEarliestRunTime());
        assertEquals(nextWindowEndTime, rescheduledJob.getLatestRunTimeElapsed());

        JobStatus mediumJob = createJobStatus("testGetRescheduleJobForPeriodic_closeToEndOfWindow",
                createJobInfo().setPeriodic(HOUR_IN_MILLIS));
        now = sElapsedRealtimeClock.millis();
        nextWindowStartTime = now + HOUR_IN_MILLIS;
        nextWindowEndTime = now + 2 * HOUR_IN_MILLIS;

        // At the beginning of the window. Next window should be unaffected.
        rescheduledJob = mService.getRescheduleJobForPeriodic(mediumJob);
        assertEquals(nextWindowStartTime, rescheduledJob.getEarliestRunTime());
        assertEquals(nextWindowEndTime, rescheduledJob.getLatestRunTimeElapsed());

        // Halfway through window. Next window should be unaffected.
        advanceElapsedClock(30 * MINUTE_IN_MILLIS);
        rescheduledJob = mService.getRescheduleJobForPeriodic(mediumJob);
        assertEquals(nextWindowStartTime, rescheduledJob.getEarliestRunTime());
        assertEquals(nextWindowEndTime, rescheduledJob.getLatestRunTimeElapsed());

        // At the edge 1/6 of window. Next window should be unaffected.
        advanceElapsedClock(20 * MINUTE_IN_MILLIS);
        rescheduledJob = mService.getRescheduleJobForPeriodic(mediumJob);
        assertEquals(nextWindowStartTime, rescheduledJob.getEarliestRunTime());
        assertEquals(nextWindowEndTime, rescheduledJob.getLatestRunTimeElapsed());

        // In last 1/6 of window. Next window start time should be shifted slightly.
        advanceElapsedClock(6 * MINUTE_IN_MILLIS);
        rescheduledJob = mService.getRescheduleJobForPeriodic(mediumJob);
        assertEquals(nextWindowStartTime + (6 * MINUTE_IN_MILLIS),
                rescheduledJob.getEarliestRunTime());
        assertEquals(nextWindowEndTime, rescheduledJob.getLatestRunTimeElapsed());

        JobStatus longJob = createJobStatus("testGetRescheduleJobForPeriodic_closeToEndOfWindow",
                createJobInfo().setPeriodic(6 * HOUR_IN_MILLIS));
        now = sElapsedRealtimeClock.millis();
        nextWindowStartTime = now + 6 * HOUR_IN_MILLIS;
        nextWindowEndTime = now + 12 * HOUR_IN_MILLIS;

        // At the beginning of the window. Next window should be unaffected.
        rescheduledJob = mService.getRescheduleJobForPeriodic(longJob);
        assertEquals(nextWindowStartTime, rescheduledJob.getEarliestRunTime());
        assertEquals(nextWindowEndTime, rescheduledJob.getLatestRunTimeElapsed());

        // Halfway through window. Next window should be unaffected.
        advanceElapsedClock(3 * HOUR_IN_MILLIS);
        rescheduledJob = mService.getRescheduleJobForPeriodic(longJob);
        assertEquals(nextWindowStartTime, rescheduledJob.getEarliestRunTime());
        assertEquals(nextWindowEndTime, rescheduledJob.getLatestRunTimeElapsed());

        // At the edge 1/6 of window. Next window should be unaffected.
        advanceElapsedClock(2 * HOUR_IN_MILLIS);
        rescheduledJob = mService.getRescheduleJobForPeriodic(longJob);
        assertEquals(nextWindowStartTime, rescheduledJob.getEarliestRunTime());
        assertEquals(nextWindowEndTime, rescheduledJob.getLatestRunTimeElapsed());

        // In last 1/6 of window. Next window should be unaffected since we're over the shift cap.
        advanceElapsedClock(15 * MINUTE_IN_MILLIS);
        rescheduledJob = mService.getRescheduleJobForPeriodic(longJob);
        assertEquals(nextWindowStartTime, rescheduledJob.getEarliestRunTime());
        assertEquals(nextWindowEndTime, rescheduledJob.getLatestRunTimeElapsed());

        // In last 1/6 of window. Next window start time should be shifted slightly.
        advanceElapsedClock(30 * MINUTE_IN_MILLIS);
        rescheduledJob = mService.getRescheduleJobForPeriodic(longJob);
        assertEquals(nextWindowStartTime + (30 * MINUTE_IN_MILLIS),
                rescheduledJob.getEarliestRunTime());
        assertEquals(nextWindowEndTime, rescheduledJob.getLatestRunTimeElapsed());

        // Flex duration close to period duration.
        JobStatus gameyFlex = createJobStatus("testGetRescheduleJobForPeriodic_closeToEndOfWindow",
                createJobInfo().setPeriodic(HOUR_IN_MILLIS, 59 * MINUTE_IN_MILLIS));
        now = sElapsedRealtimeClock.millis();
        nextWindowStartTime = now + HOUR_IN_MILLIS + MINUTE_IN_MILLIS;
        nextWindowEndTime = now + 2 * HOUR_IN_MILLIS;
        advanceElapsedClock(MINUTE_IN_MILLIS);

        // At the beginning of the window. Next window should be unaffected.
        rescheduledJob = mService.getRescheduleJobForPeriodic(gameyFlex);
        assertEquals(nextWindowStartTime, rescheduledJob.getEarliestRunTime());
        assertEquals(nextWindowEndTime, rescheduledJob.getLatestRunTimeElapsed());

        // Halfway through window. Next window should be unaffected.
        advanceElapsedClock(29 * MINUTE_IN_MILLIS);
        rescheduledJob = mService.getRescheduleJobForPeriodic(gameyFlex);
        assertEquals(nextWindowStartTime, rescheduledJob.getEarliestRunTime());
        assertEquals(nextWindowEndTime, rescheduledJob.getLatestRunTimeElapsed());

        // At the edge 1/6 of window. Next window should be unaffected.
        advanceElapsedClock(20 * MINUTE_IN_MILLIS);
        rescheduledJob = mService.getRescheduleJobForPeriodic(gameyFlex);
        assertEquals(nextWindowStartTime, rescheduledJob.getEarliestRunTime());
        assertEquals(nextWindowEndTime, rescheduledJob.getLatestRunTimeElapsed());

        // In last 1/6 of window. Next window start time should be shifted slightly.
        advanceElapsedClock(6 * MINUTE_IN_MILLIS);
        rescheduledJob = mService.getRescheduleJobForPeriodic(gameyFlex);
        assertEquals(nextWindowStartTime + (5 * MINUTE_IN_MILLIS),
                rescheduledJob.getEarliestRunTime());
        assertEquals(nextWindowEndTime, rescheduledJob.getLatestRunTimeElapsed());

        // Very short flex duration compared to period duration.
        JobStatus superFlex = createJobStatus("testGetRescheduleJobForPeriodic_closeToEndOfWindow",
                createJobInfo().setPeriodic(HOUR_IN_MILLIS, 10 * MINUTE_IN_MILLIS));
        now = sElapsedRealtimeClock.millis();
        nextWindowStartTime = now + HOUR_IN_MILLIS + 50 * MINUTE_IN_MILLIS;
        nextWindowEndTime = now + 2 * HOUR_IN_MILLIS;
        advanceElapsedClock(MINUTE_IN_MILLIS);

        // At the beginning of the window. Next window should be unaffected.
        rescheduledJob = mService.getRescheduleJobForPeriodic(superFlex);
        assertEquals(nextWindowStartTime, rescheduledJob.getEarliestRunTime());
        assertEquals(nextWindowEndTime, rescheduledJob.getLatestRunTimeElapsed());

        // Halfway through window. Next window should be unaffected.
        advanceElapsedClock(29 * MINUTE_IN_MILLIS);
        rescheduledJob = mService.getRescheduleJobForPeriodic(superFlex);
        assertEquals(nextWindowStartTime, rescheduledJob.getEarliestRunTime());
        assertEquals(nextWindowEndTime, rescheduledJob.getLatestRunTimeElapsed());

        // At the edge 1/6 of window. Next window should be unaffected.
        advanceElapsedClock(20 * MINUTE_IN_MILLIS);
        rescheduledJob = mService.getRescheduleJobForPeriodic(superFlex);
        assertEquals(nextWindowStartTime, rescheduledJob.getEarliestRunTime());
        assertEquals(nextWindowEndTime, rescheduledJob.getLatestRunTimeElapsed());

        // In last 1/6 of window. Next window should be unaffected since the flex duration pushes
        // the next window start time far enough away.
        advanceElapsedClock(6 * MINUTE_IN_MILLIS);
        rescheduledJob = mService.getRescheduleJobForPeriodic(superFlex);
        assertEquals(nextWindowStartTime, rescheduledJob.getEarliestRunTime());
        assertEquals(nextWindowEndTime, rescheduledJob.getLatestRunTimeElapsed());
    }

    /**
     * Confirm that {@link JobSchedulerService#getRescheduleJobForPeriodic(JobStatus)} returns a job
     * with the correct delay and deadline constraints if the periodic job with a custom flex
     * setting is completed and rescheduled while run in its expected running window.
     */
    @Test
    public void testGetRescheduleJobForPeriodic_insideWindow_flex() {
        JobStatus job = createJobStatus("testGetRescheduleJobForPeriodic_insideWindow_flex",
                createJobInfo().setPeriodic(HOUR_IN_MILLIS, 30 * MINUTE_IN_MILLIS));
        // First window starts 30 minutes from now.
        advanceElapsedClock(30 * MINUTE_IN_MILLIS);
        final long now = sElapsedRealtimeClock.millis();
        final long nextWindowStartTime = now + HOUR_IN_MILLIS;
        final long nextWindowEndTime = now + 90 * MINUTE_IN_MILLIS;

        JobStatus rescheduledJob = mService.getRescheduleJobForPeriodic(job);
        assertEquals(nextWindowStartTime, rescheduledJob.getEarliestRunTime());
        assertEquals(nextWindowEndTime, rescheduledJob.getLatestRunTimeElapsed());

        advanceElapsedClock(10 * MINUTE_IN_MILLIS); // now + 10 minutes

        rescheduledJob = mService.getRescheduleJobForPeriodic(job);
        assertEquals(nextWindowStartTime, rescheduledJob.getEarliestRunTime());
        assertEquals(nextWindowEndTime, rescheduledJob.getLatestRunTimeElapsed());

        advanceElapsedClock(15 * MINUTE_IN_MILLIS); // now + 25 minutes

        rescheduledJob = mService.getRescheduleJobForPeriodic(job);
        assertEquals(nextWindowStartTime, rescheduledJob.getEarliestRunTime());
        assertEquals(nextWindowEndTime, rescheduledJob.getLatestRunTimeElapsed());

        advanceElapsedClock(4 * MINUTE_IN_MILLIS); // now + 29 minutes

        rescheduledJob = mService.getRescheduleJobForPeriodic(job);
        assertEquals(nextWindowStartTime, rescheduledJob.getEarliestRunTime());
        assertEquals(nextWindowEndTime, rescheduledJob.getLatestRunTimeElapsed());
    }

    /**
     * Confirm that {@link JobSchedulerService#getRescheduleJobForPeriodic(JobStatus)} returns a job
     * with the correct delay and deadline constraints if the periodic job failed but then ran
     * successfully and was rescheduled while run in its expected running window.
     */
    @Test
    public void testGetRescheduleJobForPeriodic_insideWindow_failedJob() {
        final long now = sElapsedRealtimeClock.millis();
        final long nextWindowStartTime = now + HOUR_IN_MILLIS;
        final long nextWindowEndTime = now + 2 * HOUR_IN_MILLIS;
        JobStatus job = createJobStatus("testGetRescheduleJobForPeriodic_insideWindow_failedJob",
                createJobInfo().setPeriodic(HOUR_IN_MILLIS));
        JobStatus failedJob = mService.getRescheduleJobForFailureLocked(job);

        JobStatus rescheduledJob = mService.getRescheduleJobForPeriodic(failedJob);
        assertEquals(nextWindowStartTime, rescheduledJob.getEarliestRunTime());
        assertEquals(nextWindowEndTime, rescheduledJob.getLatestRunTimeElapsed());

        advanceElapsedClock(5 * MINUTE_IN_MILLIS); // now + 5 minutes
        failedJob = mService.getRescheduleJobForFailureLocked(job);
        advanceElapsedClock(5 * MINUTE_IN_MILLIS); // now + 10 minutes

        rescheduledJob = mService.getRescheduleJobForPeriodic(failedJob);
        assertEquals(nextWindowStartTime, rescheduledJob.getEarliestRunTime());
        assertEquals(nextWindowEndTime, rescheduledJob.getLatestRunTimeElapsed());

        advanceElapsedClock(35 * MINUTE_IN_MILLIS); // now + 45 minutes
        failedJob = mService.getRescheduleJobForFailureLocked(job);
        advanceElapsedClock(10 * MINUTE_IN_MILLIS); // now + 55 minutes

        rescheduledJob = mService.getRescheduleJobForPeriodic(failedJob);
        // Shifted because it's close to the end of the window.
        assertEquals(nextWindowStartTime + 5 * MINUTE_IN_MILLIS,
                rescheduledJob.getEarliestRunTime());
        assertEquals(nextWindowEndTime, rescheduledJob.getLatestRunTimeElapsed());

        advanceElapsedClock(2 * MINUTE_IN_MILLIS); // now + 57 minutes
        failedJob = mService.getRescheduleJobForFailureLocked(job);
        advanceElapsedClock(2 * MINUTE_IN_MILLIS); // now + 59 minutes

        rescheduledJob = mService.getRescheduleJobForPeriodic(failedJob);
        // Shifted because it's close to the end of the window.
        assertEquals(nextWindowStartTime + 9 * MINUTE_IN_MILLIS,
                rescheduledJob.getEarliestRunTime());
        assertEquals(nextWindowEndTime, rescheduledJob.getLatestRunTimeElapsed());
    }

    /**
     * Confirm that {@link JobSchedulerService#getRescheduleJobForPeriodic(JobStatus)} returns a job
     * with the correct delay and deadline constraints if the periodic job is completed and
     * rescheduled when run after its expected running window.
     */
    @Test
    public void testGetRescheduleJobForPeriodic_outsideWindow() {
        JobStatus job = createJobStatus("testGetRescheduleJobForPeriodic_outsideWindow",
                createJobInfo().setPeriodic(HOUR_IN_MILLIS));
        long now = sElapsedRealtimeClock.millis();
        long nextWindowStartTime = now + HOUR_IN_MILLIS;
        long nextWindowEndTime = now + 2 * HOUR_IN_MILLIS;

        advanceElapsedClock(HOUR_IN_MILLIS + MINUTE_IN_MILLIS);
        // Say the job ran at the very end of its previous window. The intended JSS behavior is to
        // have consistent windows, so the new window should start as soon as the previous window
        // ended and end PERIOD time after the previous window ended.
        JobStatus rescheduledJob = mService.getRescheduleJobForPeriodic(job);
        assertEquals(nextWindowStartTime, rescheduledJob.getEarliestRunTime());
        assertEquals(nextWindowEndTime, rescheduledJob.getLatestRunTimeElapsed());

        advanceElapsedClock(2 * HOUR_IN_MILLIS);
        // Say that the job ran at this point, possibly due to device idle.
        // The next window should be consistent (start and end at the time it would have had the job
        // run normally in previous windows).
        nextWindowStartTime += 2 * HOUR_IN_MILLIS;
        nextWindowEndTime += 2 * HOUR_IN_MILLIS;

        rescheduledJob = mService.getRescheduleJobForPeriodic(job);
        assertEquals(nextWindowStartTime, rescheduledJob.getEarliestRunTime());
        assertEquals(nextWindowEndTime, rescheduledJob.getLatestRunTimeElapsed());
    }

    /**
     * Confirm that {@link JobSchedulerService#getRescheduleJobForPeriodic(JobStatus)} returns a job
     * with the correct delay and deadline constraints if the periodic job with a custom flex
     * setting is completed and rescheduled when run after its expected running window.
     */
    @Test
    public void testGetRescheduleJobForPeriodic_outsideWindow_flex() {
        JobStatus job = createJobStatus("testGetRescheduleJobForPeriodic_outsideWindow_flex",
                createJobInfo().setPeriodic(HOUR_IN_MILLIS, 30 * MINUTE_IN_MILLIS));
        // First window starts 30 minutes from now.
        advanceElapsedClock(30 * MINUTE_IN_MILLIS);
        long now = sElapsedRealtimeClock.millis();
        long nextWindowStartTime = now + HOUR_IN_MILLIS;
        long nextWindowEndTime = now + 90 * MINUTE_IN_MILLIS;

        advanceElapsedClock(31 * MINUTE_IN_MILLIS);
        // Say the job ran at the very end of its previous window. The intended JSS behavior is to
        // have consistent windows, so the new window should start as soon as the previous window
        // ended and end PERIOD time after the previous window ended.
        JobStatus rescheduledJob = mService.getRescheduleJobForPeriodic(job);
        assertEquals(nextWindowStartTime, rescheduledJob.getEarliestRunTime());
        assertEquals(nextWindowEndTime, rescheduledJob.getLatestRunTimeElapsed());

        // 5 minutes before the start of the next window. It's too close to the next window, so the
        // returned job should be for the window after.
        advanceElapsedClock(24 * MINUTE_IN_MILLIS);
        nextWindowStartTime += HOUR_IN_MILLIS;
        nextWindowEndTime += HOUR_IN_MILLIS;
        rescheduledJob = mService.getRescheduleJobForPeriodic(job);
        assertEquals(nextWindowStartTime, rescheduledJob.getEarliestRunTime());
        assertEquals(nextWindowEndTime, rescheduledJob.getLatestRunTimeElapsed());

        advanceElapsedClock(2 * HOUR_IN_MILLIS + 10 * MINUTE_IN_MILLIS);
        // Say that the job ran at this point, possibly due to device idle.
        // The next window should be consistent (start and end at the time it would have had the job
        // run normally in previous windows).
        nextWindowStartTime += 2 * HOUR_IN_MILLIS;
        nextWindowEndTime += 2 * HOUR_IN_MILLIS;

        rescheduledJob = mService.getRescheduleJobForPeriodic(job);
        assertEquals(nextWindowStartTime, rescheduledJob.getEarliestRunTime());
        assertEquals(nextWindowEndTime, rescheduledJob.getLatestRunTimeElapsed());
    }

    /**
     * Confirm that {@link JobSchedulerService#getRescheduleJobForPeriodic(JobStatus)} returns a job
     * with the correct delay and deadline constraints if the periodic job failed but then ran
     * successfully and was rescheduled when run after its expected running window.
     */
    @Test
    public void testGetRescheduleJobForPeriodic_outsideWindow_failedJob() {
        JobStatus job = createJobStatus("testGetRescheduleJobForPeriodic_outsideWindow_failedJob",
                createJobInfo().setPeriodic(HOUR_IN_MILLIS));
        JobStatus failedJob = mService.getRescheduleJobForFailureLocked(job);
        long now = sElapsedRealtimeClock.millis();
        long nextWindowStartTime = now + HOUR_IN_MILLIS;
        long nextWindowEndTime = now + 2 * HOUR_IN_MILLIS;

        advanceElapsedClock(HOUR_IN_MILLIS + MINUTE_IN_MILLIS);
        // Say the job ran at the very end of its previous window. The intended JSS behavior is to
        // have consistent windows, so the new window should start as soon as the previous window
        // ended and end PERIOD time after the previous window ended.
        JobStatus rescheduledJob = mService.getRescheduleJobForPeriodic(failedJob);
        assertEquals(nextWindowStartTime, rescheduledJob.getEarliestRunTime());
        assertEquals(nextWindowEndTime, rescheduledJob.getLatestRunTimeElapsed());

        advanceElapsedClock(2 * HOUR_IN_MILLIS);
        // Say that the job ran at this point, possibly due to device idle.
        // The next window should be consistent (start and end at the time it would have had the job
        // run normally in previous windows).
        nextWindowStartTime += 2 * HOUR_IN_MILLIS;
        nextWindowEndTime += 2 * HOUR_IN_MILLIS;

        rescheduledJob = mService.getRescheduleJobForPeriodic(failedJob);
        assertEquals(nextWindowStartTime, rescheduledJob.getEarliestRunTime());
        assertEquals(nextWindowEndTime, rescheduledJob.getLatestRunTimeElapsed());
    }

    /**
     * Confirm that {@link JobSchedulerService#getRescheduleJobForPeriodic(JobStatus)} returns a job
     * with the correct delay and deadline constraints if the periodic job with a custom flex
     * setting failed but then ran successfully and was rescheduled when run after its expected
     * running window.
     */
    @Test
    public void testGetRescheduleJobForPeriodic_outsideWindow_flex_failedJob() {
        JobStatus job = createJobStatus(
                "testGetRescheduleJobForPeriodic_outsideWindow_flex_failedJob",
                createJobInfo().setPeriodic(HOUR_IN_MILLIS, 30 * MINUTE_IN_MILLIS));
        JobStatus failedJob = mService.getRescheduleJobForFailureLocked(job);
        // First window starts 30 minutes from now.
        advanceElapsedClock(30 * MINUTE_IN_MILLIS);
        long now = sElapsedRealtimeClock.millis();
        long nextWindowStartTime = now + HOUR_IN_MILLIS;
        long nextWindowEndTime = now + 90 * MINUTE_IN_MILLIS;

        advanceElapsedClock(31 * MINUTE_IN_MILLIS);
        // Say the job ran at the very end of its previous window. The intended JSS behavior is to
        // have consistent windows, so the new window should start as soon as the previous window
        // ended and end PERIOD time after the previous window ended.
        JobStatus rescheduledJob = mService.getRescheduleJobForPeriodic(failedJob);
        assertEquals(nextWindowStartTime, rescheduledJob.getEarliestRunTime());
        assertEquals(nextWindowEndTime, rescheduledJob.getLatestRunTimeElapsed());

        // 5 minutes before the start of the next window. It's too close to the next window, so the
        // returned job should be for the window after.
        advanceElapsedClock(24 * MINUTE_IN_MILLIS);
        nextWindowStartTime += HOUR_IN_MILLIS;
        nextWindowEndTime += HOUR_IN_MILLIS;
        rescheduledJob = mService.getRescheduleJobForPeriodic(job);
        assertEquals(nextWindowStartTime, rescheduledJob.getEarliestRunTime());
        assertEquals(nextWindowEndTime, rescheduledJob.getLatestRunTimeElapsed());

        advanceElapsedClock(2 * HOUR_IN_MILLIS);
        // Say that the job ran at this point, possibly due to device idle.
        // The next window should be consistent (start and end at the time it would have had the job
        // run normally in previous windows).
        nextWindowStartTime += 2 * HOUR_IN_MILLIS;
        nextWindowEndTime += 2 * HOUR_IN_MILLIS;

        rescheduledJob = mService.getRescheduleJobForPeriodic(failedJob);
        assertEquals(nextWindowStartTime, rescheduledJob.getEarliestRunTime());
        assertEquals(nextWindowEndTime, rescheduledJob.getLatestRunTimeElapsed());
    }

    /** Tests that rare job batching works as expected. */
    @Test
    public void testRareJobBatching() {
        spyOn(mService);
        doNothing().when(mService).evaluateControllerStatesLocked(any());
        doNothing().when(mService).noteJobsPending(any());
        doReturn(true).when(mService).isReadyToBeExecutedLocked(any(), anyBoolean());
        advanceElapsedClock(24 * HOUR_IN_MILLIS);

        JobSchedulerService.MaybeReadyJobQueueFunctor maybeQueueFunctor =
                mService.new MaybeReadyJobQueueFunctor();
        mService.mConstants.MIN_READY_NON_ACTIVE_JOBS_COUNT = 5;
        mService.mConstants.MAX_NON_ACTIVE_JOB_BATCH_DELAY_MS = HOUR_IN_MILLIS;

        JobStatus job = createJobStatus(
                "testRareJobBatching",
                createJobInfo().setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY));
        job.setStandbyBucket(RARE_INDEX);

        // Not enough RARE jobs to run.
        mService.mPendingJobQueue.clear();
        maybeQueueFunctor.reset();
        for (int i = 0; i < mService.mConstants.MIN_READY_NON_ACTIVE_JOBS_COUNT / 2; ++i) {
            maybeQueueFunctor.accept(job);
            assertEquals(i + 1, maybeQueueFunctor.forceBatchedCount);
            assertEquals(i + 1, maybeQueueFunctor.runnableJobs.size());
            assertEquals(sElapsedRealtimeClock.millis(), job.getFirstForceBatchedTimeElapsed());
        }
        maybeQueueFunctor.postProcessLocked();
        assertEquals(0, mService.mPendingJobQueue.size());

        // Enough RARE jobs to run.
        mService.mPendingJobQueue.clear();
        maybeQueueFunctor.reset();
        for (int i = 0; i < mService.mConstants.MIN_READY_NON_ACTIVE_JOBS_COUNT; ++i) {
            maybeQueueFunctor.accept(job);
            assertEquals(i + 1, maybeQueueFunctor.forceBatchedCount);
            assertEquals(i + 1, maybeQueueFunctor.runnableJobs.size());
            assertEquals(sElapsedRealtimeClock.millis(), job.getFirstForceBatchedTimeElapsed());
        }
        maybeQueueFunctor.postProcessLocked();
        assertEquals(5, mService.mPendingJobQueue.size());

        // Not enough RARE jobs to run, but a non-batched job saves the day.
        mService.mPendingJobQueue.clear();
        maybeQueueFunctor.reset();
        JobStatus activeJob = createJobStatus(
                "testRareJobBatching",
                createJobInfo().setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY));
        activeJob.setStandbyBucket(ACTIVE_INDEX);
        for (int i = 0; i < mService.mConstants.MIN_READY_NON_ACTIVE_JOBS_COUNT / 2; ++i) {
            maybeQueueFunctor.accept(job);
            assertEquals(i + 1, maybeQueueFunctor.forceBatchedCount);
            assertEquals(i + 1, maybeQueueFunctor.runnableJobs.size());
            assertEquals(sElapsedRealtimeClock.millis(), job.getFirstForceBatchedTimeElapsed());
        }
        maybeQueueFunctor.accept(activeJob);
        maybeQueueFunctor.postProcessLocked();
        assertEquals(3, mService.mPendingJobQueue.size());

        // Not enough RARE jobs to run, but an old RARE job saves the day.
        mService.mPendingJobQueue.clear();
        maybeQueueFunctor.reset();
        JobStatus oldRareJob = createJobStatus("testRareJobBatching", createJobInfo());
        oldRareJob.setStandbyBucket(RARE_INDEX);
        final long oldBatchTime = sElapsedRealtimeClock.millis()
                - 2 * mService.mConstants.MAX_NON_ACTIVE_JOB_BATCH_DELAY_MS;
        oldRareJob.setFirstForceBatchedTimeElapsed(oldBatchTime);
        for (int i = 0; i < mService.mConstants.MIN_READY_NON_ACTIVE_JOBS_COUNT / 2; ++i) {
            maybeQueueFunctor.accept(job);
            assertEquals(i + 1, maybeQueueFunctor.forceBatchedCount);
            assertEquals(i + 1, maybeQueueFunctor.runnableJobs.size());
            assertEquals(sElapsedRealtimeClock.millis(), job.getFirstForceBatchedTimeElapsed());
        }
        maybeQueueFunctor.accept(oldRareJob);
        assertEquals(oldBatchTime, oldRareJob.getFirstForceBatchedTimeElapsed());
        maybeQueueFunctor.postProcessLocked();
        assertEquals(3, mService.mPendingJobQueue.size());
    }

    /** Tests that jobs scheduled by the app itself are counted towards scheduling limits. */
    @Test
    public void testScheduleLimiting_RegularSchedule_Blocked() {
        mService.mConstants.ENABLE_API_QUOTAS = true;
        mService.mConstants.API_QUOTA_SCHEDULE_COUNT = 300;
        mService.mConstants.API_QUOTA_SCHEDULE_WINDOW_MS = 300000;
        mService.mConstants.API_QUOTA_SCHEDULE_THROW_EXCEPTION = false;
        mService.mConstants.API_QUOTA_SCHEDULE_RETURN_FAILURE_RESULT = true;
        mService.updateQuotaTracker();

        final JobInfo job = createJobInfo().setPersisted(true).build();
        for (int i = 0; i < 500; ++i) {
            final int expected =
                    i < 300 ? JobScheduler.RESULT_SUCCESS : JobScheduler.RESULT_FAILURE;
            assertEquals("Got unexpected result for schedule #" + (i + 1),
                    expected,
                    mService.scheduleAsPackage(job, null, 10123, null, 0, ""));
        }
    }

    /**
     * Tests that jobs scheduled by the app itself succeed even if the app is above the scheduling
     * limit.
     */
    @Test
    public void testScheduleLimiting_RegularSchedule_Allowed() {
        mService.mConstants.ENABLE_API_QUOTAS = true;
        mService.mConstants.API_QUOTA_SCHEDULE_COUNT = 300;
        mService.mConstants.API_QUOTA_SCHEDULE_WINDOW_MS = 300000;
        mService.mConstants.API_QUOTA_SCHEDULE_THROW_EXCEPTION = false;
        mService.mConstants.API_QUOTA_SCHEDULE_RETURN_FAILURE_RESULT = false;
        mService.updateQuotaTracker();

        final JobInfo job = createJobInfo().setPersisted(true).build();
        for (int i = 0; i < 500; ++i) {
            assertEquals("Got unexpected result for schedule #" + (i + 1),
                    JobScheduler.RESULT_SUCCESS,
                    mService.scheduleAsPackage(job, null, 10123, null, 0, ""));
        }
    }

    /**
     * Tests that jobs scheduled through a proxy (eg. system server) don't count towards scheduling
     * limits.
     */
    @Test
    public void testScheduleLimiting_Proxy() {
        mService.mConstants.ENABLE_API_QUOTAS = true;
        mService.mConstants.API_QUOTA_SCHEDULE_COUNT = 300;
        mService.mConstants.API_QUOTA_SCHEDULE_WINDOW_MS = 300000;
        mService.mConstants.API_QUOTA_SCHEDULE_THROW_EXCEPTION = false;
        mService.mConstants.API_QUOTA_SCHEDULE_RETURN_FAILURE_RESULT = true;
        mService.updateQuotaTracker();

        final JobInfo job = createJobInfo().setPersisted(true).build();
        for (int i = 0; i < 500; ++i) {
            assertEquals("Got unexpected result for schedule #" + (i + 1),
                    JobScheduler.RESULT_SUCCESS,
                    mService.scheduleAsPackage(job, null, 10123, "proxied.package", 0, ""));
        }
    }

    /**
     * Tests that jobs scheduled by an app for itself as if through a proxy are counted towards
     * scheduling limits.
     */
    @Test
    public void testScheduleLimiting_SelfProxy() {
        mService.mConstants.ENABLE_API_QUOTAS = true;
        mService.mConstants.API_QUOTA_SCHEDULE_COUNT = 300;
        mService.mConstants.API_QUOTA_SCHEDULE_WINDOW_MS = 300000;
        mService.mConstants.API_QUOTA_SCHEDULE_THROW_EXCEPTION = false;
        mService.mConstants.API_QUOTA_SCHEDULE_RETURN_FAILURE_RESULT = true;
        mService.updateQuotaTracker();

        final JobInfo job = createJobInfo().setPersisted(true).build();
        for (int i = 0; i < 500; ++i) {
            final int expected =
                    i < 300 ? JobScheduler.RESULT_SUCCESS : JobScheduler.RESULT_FAILURE;
            assertEquals("Got unexpected result for schedule #" + (i + 1),
                    expected,
                    mService.scheduleAsPackage(job, null, 10123, job.getService().getPackageName(),
                            0, ""));
        }
    }
}
