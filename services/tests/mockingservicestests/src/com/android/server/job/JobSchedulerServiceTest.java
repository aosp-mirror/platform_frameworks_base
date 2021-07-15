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
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
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
import android.util.Log;
import android.util.SparseBooleanArray;
import android.util.SparseLongArray;

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
import java.util.Random;

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

        @Override
        public boolean isChainedAttributionEnabled() {
            return false;
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
        doReturn(mock(UsageStatsManagerInternal.class))
                .when(() -> LocalServices.getService(UsageStatsManagerInternal.class));
        when(mContext.getString(anyInt())).thenReturn("some_test_string");
        // Called in BackgroundJobsController constructor.
        doReturn(mock(AppStateTrackerImpl.class))
                .when(() -> LocalServices.getService(AppStateTracker.class));
        // Called in BatteryController constructor.
        doReturn(mock(BatteryManagerInternal.class))
                .when(() -> LocalServices.getService(BatteryManagerInternal.class));
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
        doReturn(true).when(mService).isReadyToBeExecutedLocked(any());
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
        mService.mPendingJobs.clear();
        maybeQueueFunctor.reset();
        for (int i = 0; i < mService.mConstants.MIN_READY_NON_ACTIVE_JOBS_COUNT / 2; ++i) {
            maybeQueueFunctor.accept(job);
            assertEquals(i + 1, maybeQueueFunctor.forceBatchedCount);
            assertEquals(i + 1, maybeQueueFunctor.runnableJobs.size());
            assertEquals(sElapsedRealtimeClock.millis(), job.getFirstForceBatchedTimeElapsed());
        }
        maybeQueueFunctor.postProcessLocked();
        assertEquals(0, mService.mPendingJobs.size());

        // Enough RARE jobs to run.
        mService.mPendingJobs.clear();
        maybeQueueFunctor.reset();
        for (int i = 0; i < mService.mConstants.MIN_READY_NON_ACTIVE_JOBS_COUNT; ++i) {
            maybeQueueFunctor.accept(job);
            assertEquals(i + 1, maybeQueueFunctor.forceBatchedCount);
            assertEquals(i + 1, maybeQueueFunctor.runnableJobs.size());
            assertEquals(sElapsedRealtimeClock.millis(), job.getFirstForceBatchedTimeElapsed());
        }
        maybeQueueFunctor.postProcessLocked();
        assertEquals(5, mService.mPendingJobs.size());

        // Not enough RARE jobs to run, but a non-batched job saves the day.
        mService.mPendingJobs.clear();
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
        assertEquals(3, mService.mPendingJobs.size());

        // Not enough RARE jobs to run, but an old RARE job saves the day.
        mService.mPendingJobs.clear();
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
        assertEquals(3, mService.mPendingJobs.size());
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

    @Test
    public void testPendingJobSorting() {
        // First letter in job variable name indicate regular (r) or expedited (e).
        // Capital letters in job variable name indicate the app/UID.
        // Numbers in job variable name indicate the enqueue time.
        // Expected sort order:
        //   eA7 > rA1 > eB6 > rB2 > eC3 > rD4 > eE5 > eF9 > rF8 > eC11 > rC10 > rG12 > rG13 > eE14
        // Intentions:
        //   * A jobs let us test skipping both regular and expedited jobs of other apps
        //   * B jobs let us test skipping only regular job of another app without going too far
        //   * C jobs test that regular jobs don't skip over other app's jobs and that EJs only
        //     skip up to level of the earliest regular job
        //   * E jobs test that expedited jobs don't skip the line when the app has no regular jobs
        //   * F jobs test correct expedited/regular ordering doesn't push jobs too high in list
        //   * G jobs test correct ordering for regular jobs
        JobStatus rA1 = createJobStatus("testPendingJobSorting", createJobInfo(1), 1);
        JobStatus rB2 = createJobStatus("testPendingJobSorting", createJobInfo(2), 2);
        JobStatus eC3 = createJobStatus("testPendingJobSorting",
                createJobInfo(3).setExpedited(true), 3);
        JobStatus rD4 = createJobStatus("testPendingJobSorting", createJobInfo(4), 4);
        JobStatus eE5 = createJobStatus("testPendingJobSorting",
                createJobInfo(5).setExpedited(true), 5);
        JobStatus eB6 = createJobStatus("testPendingJobSorting",
                createJobInfo(6).setExpedited(true), 2);
        JobStatus eA7 = createJobStatus("testPendingJobSorting",
                createJobInfo(7).setExpedited(true), 1);
        JobStatus rF8 = createJobStatus("testPendingJobSorting", createJobInfo(8), 6);
        JobStatus eF9 = createJobStatus("testPendingJobSorting",
                createJobInfo(9).setExpedited(true), 6);
        JobStatus rC10 = createJobStatus("testPendingJobSorting", createJobInfo(10), 3);
        JobStatus eC11 = createJobStatus("testPendingJobSorting",
                createJobInfo(11).setExpedited(true), 3);
        JobStatus rG12 = createJobStatus("testPendingJobSorting", createJobInfo(12), 7);
        JobStatus rG13 = createJobStatus("testPendingJobSorting", createJobInfo(13), 7);
        JobStatus eE14 = createJobStatus("testPendingJobSorting",
                createJobInfo(14).setExpedited(true), 5);

        rA1.enqueueTime = 1;
        rB2.enqueueTime = 2;
        eC3.enqueueTime = 3;
        rD4.enqueueTime = 4;
        eE5.enqueueTime = 5;
        eB6.enqueueTime = 6;
        eA7.enqueueTime = 7;
        rF8.enqueueTime = 8;
        eF9.enqueueTime = 9;
        rC10.enqueueTime = 10;
        eC11.enqueueTime = 11;
        rG12.enqueueTime = 12;
        rG13.enqueueTime = 13;
        eE14.enqueueTime = 14;

        mService.mPendingJobs.clear();
        // Add in random order so sorting is apparent.
        mService.mPendingJobs.add(eC3);
        mService.mPendingJobs.add(eE5);
        mService.mPendingJobs.add(rA1);
        mService.mPendingJobs.add(rG13);
        mService.mPendingJobs.add(rD4);
        mService.mPendingJobs.add(eA7);
        mService.mPendingJobs.add(rG12);
        mService.mPendingJobs.add(rF8);
        mService.mPendingJobs.add(eB6);
        mService.mPendingJobs.add(eE14);
        mService.mPendingJobs.add(eF9);
        mService.mPendingJobs.add(rB2);
        mService.mPendingJobs.add(rC10);
        mService.mPendingJobs.add(eC11);

        mService.mPendingJobComparator.refreshLocked();
        mService.mPendingJobs.sort(mService.mPendingJobComparator);

        final JobStatus[] expectedOrder = new JobStatus[]{
                eA7, rA1, eB6, rB2, eC3, rD4, eE5, eF9, rF8, eC11, rC10, rG12, rG13, eE14};
        for (int i = 0; i < expectedOrder.length; ++i) {
            assertEquals("List wasn't correctly sorted @ index " + i,
                    expectedOrder[i].getJobId(), mService.mPendingJobs.get(i).getJobId());
        }
    }

    private void checkPendingJobInvariants() {
        long regJobEnqueueTime = 0;
        final SparseBooleanArray regJobSeen = new SparseBooleanArray();
        final SparseLongArray ejEnqueueTimes = new SparseLongArray();

        for (int i = 0; i < mService.mPendingJobs.size(); ++i) {
            final JobStatus job = mService.mPendingJobs.get(i);
            final int uid = job.getSourceUid();

            if (!job.isRequestedExpeditedJob()) {
                // Invariant #1: Regular jobs are sorted by enqueue time.
                assertTrue("Regular job with earlier enqueue time sorted after a later time: "
                                + regJobEnqueueTime + " vs " + job.enqueueTime,
                        regJobEnqueueTime <= job.enqueueTime);
                regJobEnqueueTime = job.enqueueTime;
                regJobSeen.put(uid, true);
            } else {
                // Invariant #2: EJs should be before regular jobs for an individual app
                if (regJobSeen.get(uid)) {
                    fail("UID " + uid + " had an EJ ordered after a regular job");
                }
                final long ejEnqueueTime = ejEnqueueTimes.get(uid, 0);
                // Invariant #3: EJs for an individual app should be sorted by enqueue time.
                assertTrue("EJ with earlier enqueue time sorted after a later time: "
                                + ejEnqueueTime + " vs " + job.enqueueTime,
                        ejEnqueueTime <= job.enqueueTime);
                ejEnqueueTimes.put(uid, job.enqueueTime);
            }
        }
    }

    private static String sortedJobToString(JobStatus job) {
        return "testJob " + job.getSourceUid() + "/" + job.getJobId() + "/"
                + job.isRequestedExpeditedJob() + "@" + job.enqueueTime;
    }

    @Test
    public void testPendingJobSorting_Random() {
        Random random = new Random(1); // Always use the same series of pseudo random values.

        mService.mPendingJobs.clear();

        for (int i = 0; i < 2500; ++i) {
            JobStatus job = createJobStatus("testPendingJobSorting_Random",
                    createJobInfo(i).setExpedited(random.nextBoolean()), random.nextInt(250));
            job.enqueueTime = Math.abs(random.nextInt(1_000_000));
            mService.mPendingJobs.add(job);

            mService.mPendingJobComparator.refreshLocked();
            try {
                mService.mPendingJobs.sort(mService.mPendingJobComparator);
            } catch (Exception e) {
                for (JobStatus toDump : mService.mPendingJobs) {
                    Log.i(TAG, sortedJobToString(toDump));
                }
                throw e;
            }
            checkPendingJobInvariants();
        }
    }

    private int sign(int i) {
        if (i > 0) {
            return 1;
        }
        if (i < 0) {
            return -1;
        }
        return 0;
    }

    @Test
    public void testPendingJobSortingTransitivity() {
        Random random = new Random(1); // Always use the same series of pseudo random values.

        mService.mPendingJobs.clear();

        for (int i = 0; i < 250; ++i) {
            JobStatus job = createJobStatus("testPendingJobSortingTransitivity",
                    createJobInfo(i).setExpedited(random.nextBoolean()), random.nextInt(50));
            job.enqueueTime = Math.abs(random.nextInt(1_000_000));
            job.overrideState = random.nextInt(4);
            mService.mPendingJobs.add(job);
        }

        mService.mPendingJobComparator.refreshLocked();

        for (int i = 0; i < mService.mPendingJobs.size(); ++i) {
            final JobStatus job1 = mService.mPendingJobs.get(i);

            for (int j = 0; j < mService.mPendingJobs.size(); ++j) {
                final JobStatus job2 = mService.mPendingJobs.get(j);
                final int sign12 = sign(mService.mPendingJobComparator.compare(job1, job2));
                final int sign21 = sign(mService.mPendingJobComparator.compare(job2, job1));
                if (sign12 != -sign21) {
                    final String job1String = sortedJobToString(job1);
                    final String job2String = sortedJobToString(job2);
                    fail("compare(" + job1String + ", " + job2String + ") != "
                            + "-compare(" + job2String + ", " + job1String + ")");
                }

                for (int k = 0; k < mService.mPendingJobs.size(); ++k) {
                    final JobStatus job3 = mService.mPendingJobs.get(k);
                    final int sign23 = sign(mService.mPendingJobComparator.compare(job2, job3));
                    final int sign13 = sign(mService.mPendingJobComparator.compare(job1, job3));

                    // Confirm 1 < 2 < 3 or 1 > 2 > 3 or 1 == 2 == 3
                    if ((sign12 == sign23 && sign12 != sign13)
                            // Confirm that if 1 == 2, then (1 < 3 AND 2 < 3) OR (1 > 3 && 2 > 3)
                            || (sign12 == 0 && sign13 != sign23)) {
                        final String job1String = sortedJobToString(job1);
                        final String job2String = sortedJobToString(job2);
                        final String job3String = sortedJobToString(job3);
                        fail("Transitivity fail"
                                + ": compare(" + job1String + ", " + job2String + ")=" + sign12
                                + ", compare(" + job2String + ", " + job3String + ")=" + sign23
                                + ", compare(" + job1String + ", " + job3String + ")=" + sign13);
                    }
                }
            }
        }
    }
}
