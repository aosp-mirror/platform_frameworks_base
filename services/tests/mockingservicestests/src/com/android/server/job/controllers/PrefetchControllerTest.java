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

import static android.text.format.DateUtils.HOUR_IN_MILLIS;
import static android.text.format.DateUtils.MINUTE_IN_MILLIS;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.doAnswer;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.inOrder;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.mockitoSession;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.when;
import static com.android.server.job.JobSchedulerService.FREQUENT_INDEX;
import static com.android.server.job.JobSchedulerService.sElapsedRealtimeClock;
import static com.android.server.job.JobSchedulerService.sSystemClock;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import android.app.AlarmManager;
import android.app.job.JobInfo;
import android.app.usage.UsageStatsManagerInternal;
import android.app.usage.UsageStatsManagerInternal.EstimatedLaunchTimeChangedListener;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.provider.DeviceConfig;
import android.util.ArraySet;
import android.util.SparseArray;

import androidx.test.runner.AndroidJUnit4;

import com.android.server.LocalServices;
import com.android.server.job.JobSchedulerInternal;
import com.android.server.job.JobSchedulerService;
import com.android.server.job.controllers.PrefetchController.PcConstants;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.MockitoSession;
import org.mockito.quality.Strictness;
import org.mockito.stubbing.Answer;

import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.concurrent.Executor;

@RunWith(AndroidJUnit4.class)
public class PrefetchControllerTest {
    private static final String SOURCE_PACKAGE = "com.android.frameworks.mockingservicestests";
    private static final int SOURCE_USER_ID = 0;
    private static final int CALLING_UID = 1000;
    private static final long DEFAULT_WAIT_MS = 3000;
    private static final String TAG_PREFETCH = "*job.prefetch*";

    private PrefetchController mPrefetchController;
    private PcConstants mPcConstants;
    private DeviceConfig.Properties.Builder mDeviceConfigPropertiesBuilder;
    private EstimatedLaunchTimeChangedListener mEstimatedLaunchTimeChangedListener;
    private SparseArray<ArraySet<String>> mPackagesForUid = new SparseArray<>();

    private MockitoSession mMockingSession;
    @Mock
    private AlarmManager mAlarmManager;
    @Mock
    private Context mContext;
    @Mock
    private JobSchedulerService mJobSchedulerService;
    @Mock
    private UsageStatsManagerInternal mUsageStatsManagerInternal;

    @Before
    public void setUp() {
        mMockingSession = mockitoSession()
                .initMocks(this)
                .strictness(Strictness.LENIENT)
                .spyStatic(DeviceConfig.class)
                .mockStatic(LocalServices.class)
                .startMocking();

        // Called in StateController constructor.
        when(mJobSchedulerService.getTestableContext()).thenReturn(mContext);
        when(mJobSchedulerService.getLock()).thenReturn(mJobSchedulerService);
        // Called in PrefetchController constructor.
        doReturn(mUsageStatsManagerInternal)
                .when(() -> LocalServices.getService(UsageStatsManagerInternal.class));
        when(mContext.getMainLooper()).thenReturn(Looper.getMainLooper());
        when(mContext.getSystemService(AlarmManager.class)).thenReturn(mAlarmManager);
        // Used in PrefetchController.PcConstants
        doAnswer((Answer<Void>) invocationOnMock -> null)
                .when(() -> DeviceConfig.addOnPropertiesChangedListener(
                        anyString(), any(Executor.class),
                        any(DeviceConfig.OnPropertiesChangedListener.class)));
        mDeviceConfigPropertiesBuilder =
                new DeviceConfig.Properties.Builder(DeviceConfig.NAMESPACE_JOB_SCHEDULER);
        doAnswer(
                (Answer<DeviceConfig.Properties>) invocationOnMock
                        -> mDeviceConfigPropertiesBuilder.build())
                .when(() -> DeviceConfig.getProperties(
                        eq(DeviceConfig.NAMESPACE_JOB_SCHEDULER), ArgumentMatchers.<String>any()));
        // Used in PrefetchController.maybeUpdateConstraintForUid
        when(mJobSchedulerService.getPackagesForUidLocked(anyInt()))
                .thenAnswer(invocationOnMock
                        -> mPackagesForUid.get(invocationOnMock.getArgument(0)));
        // Used in JobStatus.
        doReturn(mock(JobSchedulerInternal.class))
                .when(() -> LocalServices.getService(JobSchedulerInternal.class));

        // Freeze the clocks at 24 hours after this moment in time. Several tests create sessions
        // in the past, and PrefetchController sometimes floors values at 0, so if the test time
        // causes sessions with negative timestamps, they will fail.
        sSystemClock =
                getShiftedClock(Clock.fixed(Clock.systemUTC().instant(), ZoneOffset.UTC),
                        24 * HOUR_IN_MILLIS);
        JobSchedulerService.sUptimeMillisClock = getShiftedClock(
                Clock.fixed(SystemClock.uptimeClock().instant(), ZoneOffset.UTC),
                24 * HOUR_IN_MILLIS);
        JobSchedulerService.sElapsedRealtimeClock = getShiftedClock(
                Clock.fixed(SystemClock.elapsedRealtimeClock().instant(), ZoneOffset.UTC),
                24 * HOUR_IN_MILLIS);

        // Initialize real objects.
        // Capture the listeners.
        ArgumentCaptor<EstimatedLaunchTimeChangedListener> eltListenerCaptor =
                ArgumentCaptor.forClass(EstimatedLaunchTimeChangedListener.class);
        mPrefetchController = new PrefetchController(mJobSchedulerService);
        mPrefetchController.startTrackingLocked();
        mPcConstants = mPrefetchController.getPcConstants();

        setDeviceConfigLong(PcConstants.KEY_LAUNCH_TIME_THRESHOLD_MS, 7 * HOUR_IN_MILLIS);

        setUidBias(Process.myUid(), JobInfo.BIAS_DEFAULT);

        verify(mUsageStatsManagerInternal)
                .registerLaunchTimeChangedListener(eltListenerCaptor.capture());
        mEstimatedLaunchTimeChangedListener = eltListenerCaptor.getValue();
    }

    @After
    public void tearDown() {
        if (mMockingSession != null) {
            mMockingSession.finishMocking();
        }
    }

    private JobInfo createJobInfo(int jobId) {
        return new JobInfo.Builder(jobId,
                new ComponentName(mContext, "TestPrefetchJobService"))
                .setPrefetch(true)
                .build();
    }

    private JobStatus createJobStatus(String testTag, int jobId) {
        return createJobStatus(testTag, SOURCE_PACKAGE, CALLING_UID, createJobInfo(jobId));
    }

    private static JobStatus createJobStatus(String testTag, String packageName, int callingUid,
            JobInfo jobInfo) {
        JobStatus js = JobStatus.createFromJobInfo(
                jobInfo, callingUid, packageName, SOURCE_USER_ID, "PCTest", testTag);
        js.serviceProcessName = "testProcess";
        js.setStandbyBucket(FREQUENT_INDEX);
        // Make sure Doze and background-not-restricted don't affect tests.
        js.setDeviceNotDozingConstraintSatisfied(/* nowElapsed */ sElapsedRealtimeClock.millis(),
                /* state */ true, /* allowlisted */false);
        js.setBackgroundNotRestrictedConstraintSatisfied(
                sElapsedRealtimeClock.millis(), true, false);
        js.setQuotaConstraintSatisfied(sElapsedRealtimeClock.millis(), true);
        js.setTareWealthConstraintSatisfied(sElapsedRealtimeClock.millis(), true);
        js.setExpeditedJobQuotaApproved(sElapsedRealtimeClock.millis(), true);
        js.setExpeditedJobTareApproved(sElapsedRealtimeClock.millis(), true);
        js.setFlexibilityConstraintSatisfied(sElapsedRealtimeClock.millis(), true);
        return js;
    }

    private Clock getShiftedClock(Clock clock, long incrementMs) {
        return Clock.offset(clock, Duration.ofMillis(incrementMs));
    }

    private void setUidBias(int uid, int bias) {
        int prevBias = mJobSchedulerService.getUidBias(uid);
        doReturn(bias).when(mJobSchedulerService).getUidBias(uid);
        synchronized (mPrefetchController.mLock) {
            mPrefetchController.onUidBiasChangedLocked(uid, prevBias, bias);
        }
    }

    private void setDeviceConfigLong(String key, long val) {
        mDeviceConfigPropertiesBuilder.setLong(key, val);
        synchronized (mPrefetchController.mLock) {
            mPrefetchController.prepareForUpdatedConstantsLocked();
            mPcConstants.processConstantLocked(mDeviceConfigPropertiesBuilder.build(), key);
            mPrefetchController.onConstantsUpdatedLocked();
        }
    }

    private void trackJobs(JobStatus... jobs) {
        for (JobStatus job : jobs) {
            ArraySet<String> pkgs = mPackagesForUid.get(job.getSourceUid());
            if (pkgs == null) {
                pkgs = new ArraySet<>();
                mPackagesForUid.put(job.getSourceUid(), pkgs);
            }
            pkgs.add(job.getSourcePackageName());
            synchronized (mPrefetchController.mLock) {
                mPrefetchController.maybeStartTrackingJobLocked(job, null);
            }
        }
    }

    @Test
    public void testConstantsUpdating_ValidValues() {
        setDeviceConfigLong(PcConstants.KEY_LAUNCH_TIME_THRESHOLD_MS, 5 * HOUR_IN_MILLIS);
        setDeviceConfigLong(PcConstants.KEY_LAUNCH_TIME_ALLOWANCE_MS, 5 * MINUTE_IN_MILLIS);

        assertEquals(5 * HOUR_IN_MILLIS, mPrefetchController.getLaunchTimeThresholdMs());
        assertEquals(5 * MINUTE_IN_MILLIS, mPrefetchController.getLaunchTimeAllowanceMs());
    }

    @Test
    public void testConstantsUpdating_InvalidValues() {
        // Test negatives/too low.
        setDeviceConfigLong(PcConstants.KEY_LAUNCH_TIME_THRESHOLD_MS, 4 * MINUTE_IN_MILLIS);
        setDeviceConfigLong(PcConstants.KEY_LAUNCH_TIME_ALLOWANCE_MS, -MINUTE_IN_MILLIS);

        assertEquals(HOUR_IN_MILLIS, mPrefetchController.getLaunchTimeThresholdMs());
        assertEquals(0, mPrefetchController.getLaunchTimeAllowanceMs());

        // Test larger than a day. Controller should cap at one day.
        setDeviceConfigLong(PcConstants.KEY_LAUNCH_TIME_THRESHOLD_MS, 25 * HOUR_IN_MILLIS);
        setDeviceConfigLong(PcConstants.KEY_LAUNCH_TIME_ALLOWANCE_MS, 5 * HOUR_IN_MILLIS);

        assertEquals(24 * HOUR_IN_MILLIS, mPrefetchController.getLaunchTimeThresholdMs());
        assertEquals(2 * HOUR_IN_MILLIS, mPrefetchController.getLaunchTimeAllowanceMs());
    }

    @Test
    public void testConstantsUpdating_ThresholdChangesAlarms() {
        final long launchDelayMs = 11 * HOUR_IN_MILLIS;
        setDeviceConfigLong(PcConstants.KEY_LAUNCH_TIME_THRESHOLD_MS, 7 * HOUR_IN_MILLIS);
        setDeviceConfigLong(PcConstants.KEY_LAUNCH_TIME_ALLOWANCE_MS, 0);
        when(mUsageStatsManagerInternal
                .getEstimatedPackageLaunchTime(SOURCE_PACKAGE, SOURCE_USER_ID))
                .thenReturn(sSystemClock.millis() + launchDelayMs);
        JobStatus jobStatus = createJobStatus("testConstantsUpdating_ThresholdChangesAlarms", 1);
        trackJobs(jobStatus);

        InOrder inOrder = inOrder(mAlarmManager);

        inOrder.verify(mAlarmManager, timeout(DEFAULT_WAIT_MS).times(1))
                .setWindow(
                        anyInt(), eq(sElapsedRealtimeClock.millis() + 4 * HOUR_IN_MILLIS),
                        anyLong(), eq(TAG_PREFETCH), any(), any(Handler.class));

        setDeviceConfigLong(PcConstants.KEY_LAUNCH_TIME_THRESHOLD_MS, 3 * HOUR_IN_MILLIS);
        inOrder.verify(mAlarmManager, timeout(DEFAULT_WAIT_MS).times(1))
                .setWindow(
                        anyInt(), eq(sElapsedRealtimeClock.millis() + 8 * HOUR_IN_MILLIS),
                        anyLong(), eq(TAG_PREFETCH), any(), any(Handler.class));
    }

    @Test
    public void testConstraintNotSatisfiedWhenLaunchLate() {
        setDeviceConfigLong(PcConstants.KEY_LAUNCH_TIME_ALLOWANCE_MS, 0);
        setDeviceConfigLong(PcConstants.KEY_LAUNCH_TIME_THRESHOLD_MS, 7 * HOUR_IN_MILLIS);

        final JobStatus job = createJobStatus("testConstraintNotSatisfiedWhenLaunchLate", 1);
        when(mUsageStatsManagerInternal
                .getEstimatedPackageLaunchTime(SOURCE_PACKAGE, SOURCE_USER_ID))
                .thenReturn(sSystemClock.millis() + 10 * HOUR_IN_MILLIS);
        trackJobs(job);
        verify(mUsageStatsManagerInternal, timeout(DEFAULT_WAIT_MS))
                .getEstimatedPackageLaunchTime(SOURCE_PACKAGE, SOURCE_USER_ID);
        assertFalse(job.isConstraintSatisfied(JobStatus.CONSTRAINT_PREFETCH));
        assertFalse(job.isReady());
    }

    @Test
    public void testConstraintSatisfiedWhenLaunchSoon() {
        setDeviceConfigLong(PcConstants.KEY_LAUNCH_TIME_ALLOWANCE_MS, 0);

        final JobStatus job = createJobStatus("testConstraintSatisfiedWhenLaunchSoon", 2);
        when(mUsageStatsManagerInternal
                .getEstimatedPackageLaunchTime(SOURCE_PACKAGE, SOURCE_USER_ID))
                .thenReturn(sSystemClock.millis() + MINUTE_IN_MILLIS);
        trackJobs(job);
        verify(mUsageStatsManagerInternal, timeout(DEFAULT_WAIT_MS))
                .getEstimatedPackageLaunchTime(SOURCE_PACKAGE, SOURCE_USER_ID);
        verify(mJobSchedulerService, timeout(DEFAULT_WAIT_MS)).onControllerStateChanged(any());
        assertTrue(job.isConstraintSatisfied(JobStatus.CONSTRAINT_PREFETCH));
        assertTrue(job.isReady());
    }

    @Test
    public void testConstraintSatisfiedWhenTop() {
        final JobStatus jobPending = createJobStatus("testConstraintSatisfiedWhenTop", 1);
        final JobStatus jobRunning = createJobStatus("testConstraintSatisfiedWhenTop", 2);
        final int uid = jobPending.getSourceUid();

        when(mJobSchedulerService.isCurrentlyRunningLocked(jobPending)).thenReturn(false);
        when(mJobSchedulerService.isCurrentlyRunningLocked(jobRunning)).thenReturn(true);

        InOrder inOrder = inOrder(mJobSchedulerService);

        when(mUsageStatsManagerInternal
                .getEstimatedPackageLaunchTime(SOURCE_PACKAGE, SOURCE_USER_ID))
                .thenReturn(sSystemClock.millis() + 10 * MINUTE_IN_MILLIS);
        trackJobs(jobPending, jobRunning);
        verify(mUsageStatsManagerInternal, timeout(DEFAULT_WAIT_MS))
                .getEstimatedPackageLaunchTime(SOURCE_PACKAGE, SOURCE_USER_ID);
        inOrder.verify(mJobSchedulerService, timeout(DEFAULT_WAIT_MS))
                .onControllerStateChanged(any());
        assertTrue(jobPending.isConstraintSatisfied(JobStatus.CONSTRAINT_PREFETCH));
        assertTrue(jobPending.isReady());
        assertTrue(jobRunning.isConstraintSatisfied(JobStatus.CONSTRAINT_PREFETCH));
        assertTrue(jobRunning.isReady());
        setUidBias(uid, JobInfo.BIAS_TOP_APP);
        // Processing happens on the handler, so wait until we're sure the change has been processed
        inOrder.verify(mJobSchedulerService, timeout(DEFAULT_WAIT_MS))
                .onControllerStateChanged(any());
        // Already running job should continue but pending job must wait.
        assertFalse(jobPending.isConstraintSatisfied(JobStatus.CONSTRAINT_PREFETCH));
        assertFalse(jobPending.isReady());
        assertTrue(jobRunning.isConstraintSatisfied(JobStatus.CONSTRAINT_PREFETCH));
        assertTrue(jobRunning.isReady());
        setUidBias(uid, JobInfo.BIAS_DEFAULT);
        inOrder.verify(mJobSchedulerService, timeout(DEFAULT_WAIT_MS))
                .onControllerStateChanged(any());
        assertTrue(jobPending.isConstraintSatisfied(JobStatus.CONSTRAINT_PREFETCH));
        assertTrue(jobPending.isReady());
        assertTrue(jobRunning.isConstraintSatisfied(JobStatus.CONSTRAINT_PREFETCH));
        assertTrue(jobRunning.isReady());
    }

    @Test
    public void testConstraintSatisfiedWhenWidget() {
        setDeviceConfigLong(PcConstants.KEY_LAUNCH_TIME_ALLOWANCE_MS, 0);

        final JobStatus jobNonWidget = createJobStatus("testConstraintSatisfiedWhenWidget", 1);
        final JobStatus jobWidget = createJobStatus("testConstraintSatisfiedWhenWidget", 2);

        when(mUsageStatsManagerInternal
                .getEstimatedPackageLaunchTime(SOURCE_PACKAGE, SOURCE_USER_ID))
                .thenReturn(sSystemClock.millis() + 100 * HOUR_IN_MILLIS);

        final AppWidgetManager appWidgetManager = mock(AppWidgetManager.class);
        when(mContext.getSystemService(AppWidgetManager.class)).thenReturn(appWidgetManager);
        mPrefetchController.onSystemServicesReady();

        when(appWidgetManager.isBoundWidgetPackage(SOURCE_PACKAGE, SOURCE_USER_ID))
                .thenReturn(false);
        trackJobs(jobNonWidget);
        verify(mUsageStatsManagerInternal, timeout(DEFAULT_WAIT_MS))
                .getEstimatedPackageLaunchTime(SOURCE_PACKAGE, SOURCE_USER_ID);
        assertFalse(jobNonWidget.isConstraintSatisfied(JobStatus.CONSTRAINT_PREFETCH));
        assertFalse(jobNonWidget.isReady());

        when(appWidgetManager.isBoundWidgetPackage(SOURCE_PACKAGE, SOURCE_USER_ID))
                .thenReturn(true);
        trackJobs(jobWidget);
        assertTrue(jobWidget.isConstraintSatisfied(JobStatus.CONSTRAINT_PREFETCH));
        assertTrue(jobWidget.isReady());
    }

    @Test
    public void testEstimatedLaunchTimeChangedToLate() {
        setDeviceConfigLong(PcConstants.KEY_LAUNCH_TIME_THRESHOLD_MS, 7 * HOUR_IN_MILLIS);
        setDeviceConfigLong(PcConstants.KEY_LAUNCH_TIME_ALLOWANCE_MS, 0);
        when(mUsageStatsManagerInternal
                .getEstimatedPackageLaunchTime(SOURCE_PACKAGE, SOURCE_USER_ID))
                .thenReturn(sSystemClock.millis() + HOUR_IN_MILLIS);

        InOrder inOrder = inOrder(mUsageStatsManagerInternal);

        JobStatus jobStatus = createJobStatus("testEstimatedLaunchTimeChangedToLate", 1);
        trackJobs(jobStatus);
        inOrder.verify(mUsageStatsManagerInternal, timeout(DEFAULT_WAIT_MS))
                .getEstimatedPackageLaunchTime(SOURCE_PACKAGE, SOURCE_USER_ID);
        verify(mJobSchedulerService, timeout(DEFAULT_WAIT_MS)).onControllerStateChanged(any());
        assertTrue(jobStatus.isConstraintSatisfied(JobStatus.CONSTRAINT_PREFETCH));
        assertTrue(jobStatus.isReady());

        mEstimatedLaunchTimeChangedListener.onEstimatedLaunchTimeChanged(SOURCE_USER_ID,
                SOURCE_PACKAGE, sSystemClock.millis() + 10 * HOUR_IN_MILLIS);

        inOrder.verify(mUsageStatsManagerInternal, timeout(DEFAULT_WAIT_MS).times(0))
                .getEstimatedPackageLaunchTime(SOURCE_PACKAGE, SOURCE_USER_ID);
        verify(mAlarmManager, timeout(DEFAULT_WAIT_MS).times(1))
                .setWindow(
                        anyInt(), eq(sElapsedRealtimeClock.millis() + 3 * HOUR_IN_MILLIS),
                        anyLong(), eq(TAG_PREFETCH), any(), any(Handler.class));
        assertFalse(jobStatus.isConstraintSatisfied(JobStatus.CONSTRAINT_PREFETCH));
        assertFalse(jobStatus.isReady());
    }

    @Test
    public void testEstimatedLaunchTimeChangedToSoon() {
        setDeviceConfigLong(PcConstants.KEY_LAUNCH_TIME_THRESHOLD_MS, 7 * HOUR_IN_MILLIS);
        setDeviceConfigLong(PcConstants.KEY_LAUNCH_TIME_ALLOWANCE_MS, 0);
        when(mUsageStatsManagerInternal
                .getEstimatedPackageLaunchTime(SOURCE_PACKAGE, SOURCE_USER_ID))
                .thenReturn(sSystemClock.millis() + 10 * HOUR_IN_MILLIS);

        InOrder inOrder = inOrder(mUsageStatsManagerInternal);

        JobStatus jobStatus = createJobStatus("testEstimatedLaunchTimeChangedToSoon", 1);
        trackJobs(jobStatus);
        inOrder.verify(mUsageStatsManagerInternal, timeout(DEFAULT_WAIT_MS))
                .getEstimatedPackageLaunchTime(SOURCE_PACKAGE, SOURCE_USER_ID);
        assertFalse(jobStatus.isConstraintSatisfied(JobStatus.CONSTRAINT_PREFETCH));
        assertFalse(jobStatus.isReady());

        mEstimatedLaunchTimeChangedListener.onEstimatedLaunchTimeChanged(SOURCE_USER_ID,
                SOURCE_PACKAGE, sSystemClock.millis() + MINUTE_IN_MILLIS);

        inOrder.verify(mUsageStatsManagerInternal, timeout(DEFAULT_WAIT_MS).times(0))
                .getEstimatedPackageLaunchTime(SOURCE_PACKAGE, SOURCE_USER_ID);
        verify(mJobSchedulerService, timeout(DEFAULT_WAIT_MS)).onControllerStateChanged(any());
        assertTrue(jobStatus.isConstraintSatisfied(JobStatus.CONSTRAINT_PREFETCH));
        assertTrue(jobStatus.isReady());
    }

    @Test
    public void testEstimatedLaunchTimeAllowance() {
        setDeviceConfigLong(PcConstants.KEY_LAUNCH_TIME_THRESHOLD_MS, 7 * HOUR_IN_MILLIS);
        setDeviceConfigLong(PcConstants.KEY_LAUNCH_TIME_ALLOWANCE_MS, 15 * MINUTE_IN_MILLIS);
        when(mUsageStatsManagerInternal
                .getEstimatedPackageLaunchTime(SOURCE_PACKAGE, SOURCE_USER_ID))
                .thenReturn(sSystemClock.millis() + 10 * HOUR_IN_MILLIS);

        InOrder inOrder = inOrder(mUsageStatsManagerInternal);

        JobStatus jobStatus = createJobStatus("testEstimatedLaunchTimeAllowance", 1);
        trackJobs(jobStatus);
        inOrder.verify(mUsageStatsManagerInternal, timeout(DEFAULT_WAIT_MS))
                .getEstimatedPackageLaunchTime(SOURCE_PACKAGE, SOURCE_USER_ID);
        // The allowance shouldn't shift the alarm
        verify(mAlarmManager, timeout(DEFAULT_WAIT_MS).times(1))
                .setWindow(
                        anyInt(), eq(sElapsedRealtimeClock.millis() + 3 * HOUR_IN_MILLIS),
                        anyLong(), eq(TAG_PREFETCH), any(), any(Handler.class));
        assertFalse(jobStatus.isConstraintSatisfied(JobStatus.CONSTRAINT_PREFETCH));
        assertFalse(jobStatus.isReady());

        mEstimatedLaunchTimeChangedListener.onEstimatedLaunchTimeChanged(SOURCE_USER_ID,
                SOURCE_PACKAGE, sSystemClock.millis() + HOUR_IN_MILLIS);

        inOrder.verify(mUsageStatsManagerInternal, timeout(DEFAULT_WAIT_MS).times(0))
                .getEstimatedPackageLaunchTime(SOURCE_PACKAGE, SOURCE_USER_ID);
        verify(mJobSchedulerService, timeout(DEFAULT_WAIT_MS)).onControllerStateChanged(any());
        assertTrue(jobStatus.isConstraintSatisfied(JobStatus.CONSTRAINT_PREFETCH));
        assertTrue(jobStatus.isReady());

        sSystemClock = getShiftedClock(sSystemClock, HOUR_IN_MILLIS + MINUTE_IN_MILLIS);
    }

    @Test
    public void testRegisterOnPrefetchChangedListener() {
        when(mUsageStatsManagerInternal
                .getEstimatedPackageLaunchTime(SOURCE_PACKAGE, SOURCE_USER_ID))
                .thenReturn(sSystemClock.millis() + 10 * HOUR_IN_MILLIS);
        // Needs to get wrapped in an array to get accessed by an inner class.
        final boolean[] onPrefetchCacheChangedCalled = new boolean[1];
        final PrefetchController.PrefetchChangedListener prefetchChangedListener =
                new PrefetchController.PrefetchChangedListener() {
                    @Override
                    public void onPrefetchCacheUpdated(ArraySet<JobStatus> jobs,
                            int userId, String pkgName, long prevEstimatedLaunchTime,
                            long newEstimatedLaunchTime, long nowElapsed) {
                        onPrefetchCacheChangedCalled[0] = true;
                    }
                };
        mPrefetchController.registerPrefetchChangedListener(prefetchChangedListener);

        JobStatus jobStatus = createJobStatus("testRegisterOnPrefetchChangedListener", 1);
        trackJobs(jobStatus);

        mEstimatedLaunchTimeChangedListener.onEstimatedLaunchTimeChanged(SOURCE_USER_ID,
                SOURCE_PACKAGE, sSystemClock.millis() + HOUR_IN_MILLIS);
        verify(mJobSchedulerService, timeout(DEFAULT_WAIT_MS)).onControllerStateChanged(any());

        assertTrue(onPrefetchCacheChangedCalled[0]);
    }
}
