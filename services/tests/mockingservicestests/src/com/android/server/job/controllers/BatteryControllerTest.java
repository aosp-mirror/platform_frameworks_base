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
import static com.android.dx.mockito.inline.extended.ExtendedMockito.mock;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.mockitoSession;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.when;
import static com.android.server.job.JobSchedulerService.FREQUENT_INDEX;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.app.AppGlobals;
import android.app.job.JobInfo;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.pm.PackageManagerInternal;
import android.os.BatteryManagerInternal;
import android.os.RemoteException;
import android.util.ArraySet;

import androidx.test.runner.AndroidJUnit4;

import com.android.server.AppSchedulingModuleThread;
import com.android.server.LocalServices;
import com.android.server.job.JobSchedulerService;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoSession;
import org.mockito.quality.Strictness;

@RunWith(AndroidJUnit4.class)
public class BatteryControllerTest {
    private static final int CALLING_UID = 1000;
    private static final String SOURCE_PACKAGE = "com.android.frameworks.mockingservicestests";
    private static final int SOURCE_USER_ID = 0;

    private BatteryController mBatteryController;
    private FlexibilityController mFlexibilityController;
    private JobSchedulerService.Constants mConstants = new JobSchedulerService.Constants();
    private int mSourceUid;

    private MockitoSession mMockingSession;
    @Mock
    private Context mContext;
    @Mock
    private BatteryManagerInternal mBatteryManagerInternal;
    @Mock
    private JobSchedulerService mJobSchedulerService;
    @Mock
    private PackageManagerInternal mPackageManagerInternal;
    @Mock
    private PackageManager mPackageManager;

    @Before
    public void setUp() {
        mMockingSession = mockitoSession()
                .initMocks(this)
                .strictness(Strictness.LENIENT)
                .mockStatic(LocalServices.class)
                .startMocking();

        // Called in StateController constructor.
        when(mJobSchedulerService.getTestableContext()).thenReturn(mContext);
        when(mJobSchedulerService.getLock()).thenReturn(mJobSchedulerService);
        when(mJobSchedulerService.getConstants()).thenReturn(mConstants);
        // Called in BatteryController constructor.
        doReturn(mBatteryManagerInternal)
                .when(() -> LocalServices.getService(BatteryManagerInternal.class));
        // Used in JobStatus.
        doReturn(mPackageManagerInternal)
                .when(() -> LocalServices.getService(PackageManagerInternal.class));

        // Initialize real objects.
        when(mContext.getPackageManager()).thenReturn(mPackageManager);
        when(mPackageManager.hasSystemFeature(
                PackageManager.FEATURE_AUTOMOTIVE)).thenReturn(false);
        mFlexibilityController =
                new FlexibilityController(mJobSchedulerService, mock(PrefetchController.class));
        mBatteryController = new BatteryController(mJobSchedulerService, mFlexibilityController);
        mBatteryController.startTrackingLocked();

        try {
            mSourceUid = AppGlobals.getPackageManager().getPackageUid(SOURCE_PACKAGE, 0, 0);
            // Need to do this since we're using a mock JS and not a real object.
            doReturn(new ArraySet<>(new String[]{SOURCE_PACKAGE}))
                    .when(mJobSchedulerService).getPackagesForUidLocked(mSourceUid);
        } catch (RemoteException e) {
            fail(e.getMessage());
        }
        setPowerConnected(false);
    }

    @After
    public void tearDown() {
        if (mMockingSession != null) {
            mMockingSession.finishMocking();
        }
    }

    private void setBatteryNotLow(boolean notLow) {
        doReturn(notLow).when(mJobSchedulerService).isBatteryNotLow();
        synchronized (mBatteryController.mLock) {
            mBatteryController.onBatteryStateChangedLocked();
        }
        waitForNonDelayedMessagesProcessed();
    }

    private void setCharging() {
        doReturn(true).when(mJobSchedulerService).isBatteryCharging();
        synchronized (mBatteryController.mLock) {
            mBatteryController.onBatteryStateChangedLocked();
        }
        waitForNonDelayedMessagesProcessed();
    }

    private void setDischarging() {
        doReturn(false).when(mJobSchedulerService).isBatteryCharging();
        synchronized (mBatteryController.mLock) {
            mBatteryController.onBatteryStateChangedLocked();
        }
        waitForNonDelayedMessagesProcessed();
    }

    private void setPowerConnected(boolean connected) {
        doReturn(connected).when(mJobSchedulerService).isPowerConnected();
        synchronized (mBatteryController.mLock) {
            mBatteryController.onBatteryStateChangedLocked();
        }
        waitForNonDelayedMessagesProcessed();
    }

    private void setUidBias(int uid, int bias) {
        int prevBias = mJobSchedulerService.getUidBias(uid);
        doReturn(bias).when(mJobSchedulerService).getUidBias(uid);
        synchronized (mBatteryController.mLock) {
            mBatteryController.onUidBiasChangedLocked(uid, prevBias, bias);
        }
    }

    private void trackJobs(JobStatus... jobs) {
        for (JobStatus job : jobs) {
            synchronized (mBatteryController.mLock) {
                mBatteryController.maybeStartTrackingJobLocked(job, null);
            }
        }
    }

    private void waitForNonDelayedMessagesProcessed() {
        AppSchedulingModuleThread.getHandler().runWithScissors(() -> {}, 15_000);
    }

    private JobInfo.Builder createBaseJobInfoBuilder(int jobId) {
        return new JobInfo.Builder(jobId, new ComponentName(mContext, "TestBatteryJobService"));
    }

    private JobInfo.Builder createBaseJobInfoBuilder(int jobId, String pkgName) {
        return new JobInfo.Builder(jobId, new ComponentName(pkgName, "TestBatteryJobService"));
    }

    private JobStatus createJobStatus(String testTag, String packageName, int callingUid,
            JobInfo jobInfo) {
        JobStatus js = JobStatus.createFromJobInfo(
                jobInfo, callingUid, packageName, SOURCE_USER_ID, "BCTest", testTag);
        js.serviceProcessName = "testProcess";
        // Make sure tests aren't passing just because the default bucket is likely ACTIVE.
        js.setStandbyBucket(FREQUENT_INDEX);
        return js;
    }

    @Test
    public void testBatteryNotLow() {
        JobStatus job1 = createJobStatus("testBatteryNotLow", SOURCE_PACKAGE, CALLING_UID,
                createBaseJobInfoBuilder(1).setRequiresBatteryNotLow(true).build());
        JobStatus job2 = createJobStatus("testBatteryNotLow", SOURCE_PACKAGE, CALLING_UID,
                createBaseJobInfoBuilder(2).setRequiresBatteryNotLow(true).build());

        setBatteryNotLow(false);
        trackJobs(job1);
        assertFalse(job1.isConstraintSatisfied(JobStatus.CONSTRAINT_BATTERY_NOT_LOW));

        setBatteryNotLow(true);
        assertTrue(job1.isConstraintSatisfied(JobStatus.CONSTRAINT_BATTERY_NOT_LOW));

        trackJobs(job2);
        assertTrue(job2.isConstraintSatisfied(JobStatus.CONSTRAINT_BATTERY_NOT_LOW));
    }

    @Test
    public void testFlexibilityController_BatteryNotLow() {
        setBatteryNotLow(false);
        assertFalse(
                mFlexibilityController.isConstraintSatisfied(JobStatus.CONSTRAINT_BATTERY_NOT_LOW));
        setBatteryNotLow(true);
        assertTrue(
                mFlexibilityController.isConstraintSatisfied(JobStatus.CONSTRAINT_BATTERY_NOT_LOW));
        setBatteryNotLow(false);
        assertFalse(
                mFlexibilityController.isConstraintSatisfied(JobStatus.CONSTRAINT_BATTERY_NOT_LOW));
    }

    @Test
    public void testFlexibilityController_Charging() {
        setDischarging();
        assertFalse(mFlexibilityController.isConstraintSatisfied(JobStatus.CONSTRAINT_CHARGING));
        setCharging();
        assertTrue(mFlexibilityController.isConstraintSatisfied(JobStatus.CONSTRAINT_CHARGING));
        setDischarging();
        assertFalse(mFlexibilityController.isConstraintSatisfied(JobStatus.CONSTRAINT_CHARGING));
    }

    @Test
    public void testFlexibilityController_Charging_BatterNotLow() {
        setDischarging();
        setBatteryNotLow(false);

        assertFalse(
                mFlexibilityController.isConstraintSatisfied(JobStatus.CONSTRAINT_BATTERY_NOT_LOW));
        assertFalse(mFlexibilityController.isConstraintSatisfied(JobStatus.CONSTRAINT_CHARGING));

        setCharging();

        assertFalse(
                mFlexibilityController.isConstraintSatisfied(JobStatus.CONSTRAINT_BATTERY_NOT_LOW));
        assertTrue(mFlexibilityController.isConstraintSatisfied(JobStatus.CONSTRAINT_CHARGING));

        setBatteryNotLow(true);

        assertTrue(
                mFlexibilityController.isConstraintSatisfied(JobStatus.CONSTRAINT_BATTERY_NOT_LOW));
        assertTrue(mFlexibilityController.isConstraintSatisfied(JobStatus.CONSTRAINT_CHARGING));

        setDischarging();

        assertTrue(
                mFlexibilityController.isConstraintSatisfied(JobStatus.CONSTRAINT_BATTERY_NOT_LOW));
        assertFalse(mFlexibilityController.isConstraintSatisfied(JobStatus.CONSTRAINT_CHARGING));

        setBatteryNotLow(false);

        assertFalse(
                mFlexibilityController.isConstraintSatisfied(JobStatus.CONSTRAINT_BATTERY_NOT_LOW));
        assertFalse(mFlexibilityController.isConstraintSatisfied(JobStatus.CONSTRAINT_CHARGING));
    }

    @Test
    public void testCharging_BatteryNotLow() {
        JobStatus job1 = createJobStatus("testCharging_BatteryNotLow", SOURCE_PACKAGE, CALLING_UID,
                createBaseJobInfoBuilder(1)
                        .setRequiresCharging(true)
                        .setRequiresBatteryNotLow(true).build());
        JobStatus job2 = createJobStatus("testCharging_BatteryNotLow", SOURCE_PACKAGE, CALLING_UID,
                createBaseJobInfoBuilder(2)
                        .setRequiresCharging(true)
                        .setRequiresBatteryNotLow(false).build());

        setBatteryNotLow(true);
        setDischarging();
        trackJobs(job1, job2);
        assertFalse(job1.isConstraintSatisfied(JobStatus.CONSTRAINT_CHARGING));
        assertFalse(job2.isConstraintSatisfied(JobStatus.CONSTRAINT_CHARGING));

        setCharging();
        assertTrue(job1.isConstraintSatisfied(JobStatus.CONSTRAINT_CHARGING));
        assertTrue(job2.isConstraintSatisfied(JobStatus.CONSTRAINT_CHARGING));
    }

    @Test
    public void testTopPowerConnectedExemption() {
        final int uid1 = mSourceUid;
        final int uid2 = mSourceUid + 1;
        final int uid3 = mSourceUid + 2;
        JobStatus jobFg = createJobStatus("testTopPowerConnectedExemption", SOURCE_PACKAGE, uid1,
                createBaseJobInfoBuilder(1).setRequiresCharging(true).build());
        JobStatus jobFgRunner = createJobStatus("testTopPowerConnectedExemption",
                SOURCE_PACKAGE, uid1,
                createBaseJobInfoBuilder(2).setRequiresCharging(true).build());
        JobStatus jobFgLow = createJobStatus("testTopPowerConnectedExemption", SOURCE_PACKAGE, uid1,
                createBaseJobInfoBuilder(3)
                        .setRequiresCharging(true)
                        .setPriority(JobInfo.PRIORITY_LOW)
                        .build());
        JobStatus jobBg = createJobStatus("testTopPowerConnectedExemption",
                "some.background.app", uid2,
                createBaseJobInfoBuilder(4, "some.background.app")
                        .setRequiresCharging(true)
                        .build());
        JobStatus jobLateFg = createJobStatus("testTopPowerConnectedExemption",
                "switch.to.fg", uid3,
                createBaseJobInfoBuilder(5, "switch.to.fg").setRequiresCharging(true).build());
        JobStatus jobLateFgLow = createJobStatus("testTopPowerConnectedExemption",
                "switch.to.fg", uid3,
                createBaseJobInfoBuilder(6, "switch.to.fg")
                        .setRequiresCharging(true)
                        .setPriority(JobInfo.PRIORITY_MIN)
                        .build());

        setBatteryNotLow(false);
        setDischarging();
        setUidBias(uid1, JobInfo.BIAS_TOP_APP);
        setUidBias(uid2, JobInfo.BIAS_DEFAULT);
        setUidBias(uid3, JobInfo.BIAS_DEFAULT);

        // Jobs are scheduled when power isn't connected.
        setPowerConnected(false);
        trackJobs(jobFg, jobFgLow, jobBg, jobLateFg, jobLateFgLow);
        assertFalse(jobFg.isConstraintSatisfied(JobStatus.CONSTRAINT_CHARGING));
        assertFalse(jobFgLow.isConstraintSatisfied(JobStatus.CONSTRAINT_CHARGING));
        assertFalse(jobBg.isConstraintSatisfied(JobStatus.CONSTRAINT_CHARGING));
        assertFalse(jobLateFg.isConstraintSatisfied(JobStatus.CONSTRAINT_CHARGING));
        assertFalse(jobLateFgLow.isConstraintSatisfied(JobStatus.CONSTRAINT_CHARGING));

        // Power is connected. TOP app should be allowed to start job DEFAULT+ jobs.
        setPowerConnected(true);
        assertTrue(jobFg.isConstraintSatisfied(JobStatus.CONSTRAINT_CHARGING));
        assertFalse(jobFgLow.isConstraintSatisfied(JobStatus.CONSTRAINT_CHARGING));
        assertFalse(jobBg.isConstraintSatisfied(JobStatus.CONSTRAINT_CHARGING));
        assertFalse(jobLateFg.isConstraintSatisfied(JobStatus.CONSTRAINT_CHARGING));
        assertFalse(jobLateFgLow.isConstraintSatisfied(JobStatus.CONSTRAINT_CHARGING));

        // Test that newly scheduled job of TOP app is correctly allowed to run.
        trackJobs(jobFgRunner);
        assertTrue(jobFgRunner.isConstraintSatisfied(JobStatus.CONSTRAINT_CHARGING));

        // Switch top app. New TOP app should be allowed to run job and the running job of
        // previously TOP app should be allowed to continue to run.
        synchronized (mBatteryController.mLock) {
            mBatteryController.prepareForExecutionLocked(jobFgRunner);
        }
        setUidBias(uid1, JobInfo.BIAS_DEFAULT);
        setUidBias(uid2, JobInfo.BIAS_DEFAULT);
        setUidBias(uid3, JobInfo.BIAS_TOP_APP);
        assertFalse(jobFg.isConstraintSatisfied(JobStatus.CONSTRAINT_CHARGING));
        assertTrue(jobFgRunner.isConstraintSatisfied(JobStatus.CONSTRAINT_CHARGING));
        assertFalse(jobFgLow.isConstraintSatisfied(JobStatus.CONSTRAINT_CHARGING));
        assertFalse(jobBg.isConstraintSatisfied(JobStatus.CONSTRAINT_CHARGING));
        assertTrue(jobLateFg.isConstraintSatisfied(JobStatus.CONSTRAINT_CHARGING));
        assertFalse(jobLateFgLow.isConstraintSatisfied(JobStatus.CONSTRAINT_CHARGING));

        setPowerConnected(false);
        assertFalse(jobFg.isConstraintSatisfied(JobStatus.CONSTRAINT_CHARGING));
        assertFalse(jobFgRunner.isConstraintSatisfied(JobStatus.CONSTRAINT_CHARGING));
        assertFalse(jobFgLow.isConstraintSatisfied(JobStatus.CONSTRAINT_CHARGING));
        assertFalse(jobBg.isConstraintSatisfied(JobStatus.CONSTRAINT_CHARGING));
        assertFalse(jobLateFg.isConstraintSatisfied(JobStatus.CONSTRAINT_CHARGING));
        assertFalse(jobLateFgLow.isConstraintSatisfied(JobStatus.CONSTRAINT_CHARGING));
    }

    @Test
    public void testControllerOnlyTracksPowerJobs() {
        JobStatus batteryJob = createJobStatus("testControllerOnlyTracksPowerJobs",
                SOURCE_PACKAGE, mSourceUid,
                createBaseJobInfoBuilder(1).setRequiresBatteryNotLow(true).build());
        JobStatus chargingJob = createJobStatus("testControllerOnlyTracksPowerJobs",
                SOURCE_PACKAGE, mSourceUid,
                createBaseJobInfoBuilder(2).setRequiresCharging(true).build());
        JobStatus bothPowerJob = createJobStatus("testControllerOnlyTracksPowerJobs",
                SOURCE_PACKAGE, mSourceUid,
                createBaseJobInfoBuilder(3)
                        .setRequiresCharging(true)
                        .setRequiresBatteryNotLow(true)
                        .build());
        JobStatus unrelatedJob = createJobStatus("testControllerOnlyTracksPowerJobs",
                SOURCE_PACKAGE, mSourceUid, createBaseJobInfoBuilder(4).build());

        // Follow the lifecycle of tracking
        // Start tracking
        trackJobs(batteryJob, chargingJob, bothPowerJob, unrelatedJob);
        final ArraySet<JobStatus> trackedJobs = mBatteryController.getTrackedJobs();
        final ArraySet<JobStatus> topStartedJobs = mBatteryController.getTopStartedJobs();
        assertTrue(trackedJobs.contains(batteryJob));
        assertTrue(trackedJobs.contains(chargingJob));
        assertTrue(trackedJobs.contains(bothPowerJob));
        assertFalse(trackedJobs.contains(unrelatedJob));
        assertFalse(topStartedJobs.contains(batteryJob));
        assertFalse(topStartedJobs.contains(chargingJob));
        assertFalse(topStartedJobs.contains(bothPowerJob));
        assertFalse(topStartedJobs.contains(unrelatedJob));

        // Procstate change shouldn't affect anything
        setUidBias(mSourceUid, JobInfo.BIAS_TOP_APP);
        assertTrue(trackedJobs.contains(batteryJob));
        assertTrue(trackedJobs.contains(chargingJob));
        assertTrue(trackedJobs.contains(bothPowerJob));
        assertFalse(trackedJobs.contains(unrelatedJob));
        assertFalse(topStartedJobs.contains(batteryJob));
        assertFalse(topStartedJobs.contains(chargingJob));
        assertFalse(topStartedJobs.contains(bothPowerJob));
        assertFalse(topStartedJobs.contains(unrelatedJob));

        // Job starts running
        mBatteryController.prepareForExecutionLocked(batteryJob);
        mBatteryController.prepareForExecutionLocked(chargingJob);
        mBatteryController.prepareForExecutionLocked(bothPowerJob);
        mBatteryController.prepareForExecutionLocked(unrelatedJob);
        assertTrue(topStartedJobs.contains(batteryJob));
        assertTrue(topStartedJobs.contains(chargingJob));
        assertTrue(topStartedJobs.contains(bothPowerJob));
        assertFalse(topStartedJobs.contains(unrelatedJob));

        // Job cleanup
        mBatteryController.maybeStopTrackingJobLocked(batteryJob, null);
        mBatteryController.maybeStopTrackingJobLocked(chargingJob, null);
        mBatteryController.maybeStopTrackingJobLocked(bothPowerJob, null);
        mBatteryController.maybeStopTrackingJobLocked(unrelatedJob, null);
        assertFalse(trackedJobs.contains(batteryJob));
        assertFalse(trackedJobs.contains(chargingJob));
        assertFalse(trackedJobs.contains(bothPowerJob));
        assertFalse(trackedJobs.contains(unrelatedJob));
        assertFalse(topStartedJobs.contains(batteryJob));
        assertFalse(topStartedJobs.contains(chargingJob));
        assertFalse(topStartedJobs.contains(bothPowerJob));
        assertFalse(topStartedJobs.contains(unrelatedJob));
    }
}
