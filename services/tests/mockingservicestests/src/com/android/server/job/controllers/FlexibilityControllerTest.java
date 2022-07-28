/*
 * Copyright (C) 2022 The Android Open Source Project
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

import static android.app.job.JobInfo.NETWORK_TYPE_ANY;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.doAnswer;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.mockitoSession;
import static com.android.server.job.controllers.FlexibilityController.FcConstants.KEY_FLEXIBILITY_ENABLED;
import static com.android.server.job.controllers.JobStatus.CONSTRAINT_BATTERY_NOT_LOW;
import static com.android.server.job.controllers.JobStatus.CONSTRAINT_CHARGING;
import static com.android.server.job.controllers.JobStatus.CONSTRAINT_FLEXIBLE;
import static com.android.server.job.controllers.JobStatus.CONSTRAINT_IDLE;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.app.AlarmManager;
import android.app.job.JobInfo;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManagerInternal;
import android.os.Looper;
import android.provider.DeviceConfig;
import android.util.ArraySet;

import com.android.server.LocalServices;
import com.android.server.job.JobSchedulerService;
import com.android.server.job.JobStore;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.MockitoSession;
import org.mockito.quality.Strictness;
import org.mockito.stubbing.Answer;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.concurrent.Executor;

public class FlexibilityControllerTest {
    private static final String SOURCE_PACKAGE = "com.android.frameworks.mockingservicestests";
    private static final int SOURCE_USER_ID = 0;

    private MockitoSession mMockingSession;
    private FlexibilityController mFlexibilityController;
    private DeviceConfig.Properties.Builder mDeviceConfigPropertiesBuilder;
    private JobStore mJobStore;
    private FlexibilityController.FcConstants mFcConstants;

    @Mock
    private AlarmManager mAlarmManager;
    @Mock
    private Context mContext;
    @Mock
    private JobSchedulerService mJobSchedulerService;

    @Before
    public void setup() {
        mMockingSession = mockitoSession()
                .initMocks(this)
                .strictness(Strictness.LENIENT)
                .spyStatic(DeviceConfig.class)
                .mockStatic(LocalServices.class)
                .startMocking();
        // Called in StateController constructor.
        when(mJobSchedulerService.getTestableContext()).thenReturn(mContext);
        when(mJobSchedulerService.getLock()).thenReturn(mJobSchedulerService);
        when(mJobSchedulerService.getConstants()).thenReturn(
                mock(JobSchedulerService.Constants.class));
        // Called in FlexibilityController constructor.
        when(mContext.getMainLooper()).thenReturn(Looper.getMainLooper());
        when(mContext.getSystemService(Context.ALARM_SERVICE)).thenReturn(mAlarmManager);
        // Used in FlexibilityController.FcConstants.
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
        //used to get jobs by UID
        mJobStore = JobStore.initAndGetForTesting(mContext, mContext.getFilesDir());
        when(mJobSchedulerService.getJobStore()).thenReturn(mJobStore);
        // Used in JobStatus.
        doReturn(mock(PackageManagerInternal.class))
                .when(() -> LocalServices.getService(PackageManagerInternal.class));
        // Freeze the clocks at a moment in time
        JobSchedulerService.sSystemClock =
                Clock.fixed(Instant.ofEpochMilli(100L), ZoneOffset.UTC);
        JobSchedulerService.sElapsedRealtimeClock =
                Clock.fixed(Instant.ofEpochMilli(100L), ZoneOffset.UTC);
        // Initialize real objects.
        mFlexibilityController = new FlexibilityController(mJobSchedulerService);
        mFcConstants = mFlexibilityController.getFcConstants();

        setDeviceConfigBoolean(KEY_FLEXIBILITY_ENABLED, true);
    }

    @After
    public void teardown() {
        if (mMockingSession != null) {
            mMockingSession.finishMocking();
        }
    }

    private void setDeviceConfigBoolean(String key, boolean val) {
        mDeviceConfigPropertiesBuilder.setBoolean(key, val);
        synchronized (mFlexibilityController.mLock) {
            mFlexibilityController.prepareForUpdatedConstantsLocked();
            mFcConstants.processConstantLocked(mDeviceConfigPropertiesBuilder.build(), key);
            mFlexibilityController.onConstantsUpdatedLocked();
        }
    }

    private static JobInfo.Builder createJob(int id) {
        return new JobInfo.Builder(id, new ComponentName("foo", "bar"));
    }

    private JobStatus createJobStatus(String testTag, JobInfo.Builder job) {
        JobInfo jobInfo = job.build();
        return JobStatus.createFromJobInfo(
                jobInfo, 1000, SOURCE_PACKAGE, SOURCE_USER_ID, testTag);
    }

    @Test
    public void testGetNextConstraintDropTimeElapsed() {
        long nextTimeToDropNumConstraints;

        // no delay, deadline
        JobInfo.Builder jb = createJob(0).setOverrideDeadline(1000);
        JobStatus js = createJobStatus("time", jb);
        js.enqueueTime = 100L;

        assertEquals(0, js.getEarliestRunTime());
        assertEquals(1100L, js.getLatestRunTimeElapsed());
        assertEquals(100L, js.enqueueTime);

        nextTimeToDropNumConstraints = mFlexibilityController.getNextConstraintDropTimeElapsed(js);
        assertEquals(600L, nextTimeToDropNumConstraints);
        js.adjustNumRequiredFlexibleConstraints(-1);
        nextTimeToDropNumConstraints = mFlexibilityController.getNextConstraintDropTimeElapsed(js);
        assertEquals(700L, nextTimeToDropNumConstraints);
        js.adjustNumRequiredFlexibleConstraints(-1);
        nextTimeToDropNumConstraints = mFlexibilityController.getNextConstraintDropTimeElapsed(js);
        assertEquals(800L, nextTimeToDropNumConstraints);

        // delay, no deadline
        jb = createJob(0).setMinimumLatency(800000L);
        js = createJobStatus("time", jb);
        js.enqueueTime = 100L;

        nextTimeToDropNumConstraints = mFlexibilityController.getNextConstraintDropTimeElapsed(js);
        assertEquals(130400100, nextTimeToDropNumConstraints);
        js.adjustNumRequiredFlexibleConstraints(-1);
        nextTimeToDropNumConstraints = mFlexibilityController.getNextConstraintDropTimeElapsed(js);
        assertEquals(156320100L, nextTimeToDropNumConstraints);
        js.adjustNumRequiredFlexibleConstraints(-1);
        nextTimeToDropNumConstraints = mFlexibilityController.getNextConstraintDropTimeElapsed(js);
        assertEquals(182240100L, nextTimeToDropNumConstraints);

        // no delay, no deadline
        jb = createJob(0);
        js = createJobStatus("time", jb);
        js.enqueueTime = 100L;

        nextTimeToDropNumConstraints = mFlexibilityController.getNextConstraintDropTimeElapsed(js);
        assertEquals(129600100, nextTimeToDropNumConstraints);
        js.adjustNumRequiredFlexibleConstraints(-1);
        nextTimeToDropNumConstraints = mFlexibilityController.getNextConstraintDropTimeElapsed(js);
        assertEquals(155520100L, nextTimeToDropNumConstraints);
        js.adjustNumRequiredFlexibleConstraints(-1);
        nextTimeToDropNumConstraints = mFlexibilityController.getNextConstraintDropTimeElapsed(js);
        assertEquals(181440100L, nextTimeToDropNumConstraints);

        // delay, deadline
        jb = createJob(0).setOverrideDeadline(1100).setMinimumLatency(100);
        js = createJobStatus("time", jb);
        js.enqueueTime = 100L;

        nextTimeToDropNumConstraints = mFlexibilityController.getNextConstraintDropTimeElapsed(js);
        assertEquals(700L, nextTimeToDropNumConstraints);
        js.adjustNumRequiredFlexibleConstraints(-1);
        nextTimeToDropNumConstraints = mFlexibilityController.getNextConstraintDropTimeElapsed(js);
        assertEquals(800L, nextTimeToDropNumConstraints);
        js.adjustNumRequiredFlexibleConstraints(-1);
        nextTimeToDropNumConstraints = mFlexibilityController.getNextConstraintDropTimeElapsed(js);
        assertEquals(900L, nextTimeToDropNumConstraints);
    }

    @Test
    public void testWontStopJobFromRunning() {
        JobStatus js = createJobStatus("testWontStopJobFromRunning", createJob(101));
        // Stop satisfied constraints from causing a false positive.
        js.adjustNumRequiredFlexibleConstraints(100);
        synchronized (mFlexibilityController.mLock) {
            when(mJobSchedulerService.isCurrentlyRunningLocked(js)).thenReturn(true);
            assertTrue(mFlexibilityController.isFlexibilitySatisfiedLocked(js));
        }
    }

    @Test
    public void testFlexibilityTracker() {
        FlexibilityController.FlexibilityTracker flexTracker =
                mFlexibilityController.new
                        FlexibilityTracker(FlexibilityController.SYSTEM_WIDE_FLEXIBLE_CONSTRAINTS);

        JobStatus[] jobs = new JobStatus[4];
        JobInfo.Builder jb;
        for (int i = 0; i < jobs.length; i++) {
            jb = createJob(i);
            if (i > 0) {
                jb.setRequiresDeviceIdle(true);
            }
            if (i > 1) {
                jb.setRequiresBatteryNotLow(true);
            }
            if (i > 2) {
                jb.setRequiresCharging(true);
            }
            jobs[i] = createJobStatus("", jb);
            flexTracker.add(jobs[i]);
        }

        synchronized (mFlexibilityController.mLock) {
            ArrayList<ArraySet<JobStatus>> trackedJobs = flexTracker.getArrayList();
            assertEquals(0, trackedJobs.get(0).size());
            assertEquals(0, trackedJobs.get(1).size());
            assertEquals(3, trackedJobs.get(2).size());
            assertEquals(0, trackedJobs.get(3).size());

            flexTracker.adjustJobsRequiredConstraints(jobs[0], -1);
            assertEquals(0, trackedJobs.get(0).size());
            assertEquals(1, trackedJobs.get(1).size());
            assertEquals(2, trackedJobs.get(2).size());
            assertEquals(0, trackedJobs.get(3).size());

            flexTracker.adjustJobsRequiredConstraints(jobs[0], -1);
            assertEquals(1, trackedJobs.get(0).size());
            assertEquals(0, trackedJobs.get(1).size());
            assertEquals(2, trackedJobs.get(2).size());
            assertEquals(0, trackedJobs.get(3).size());

            flexTracker.adjustJobsRequiredConstraints(jobs[0], -1);
            assertEquals(0, trackedJobs.get(0).size());
            assertEquals(0, trackedJobs.get(1).size());
            assertEquals(2, trackedJobs.get(2).size());
            assertEquals(0, trackedJobs.get(3).size());

            flexTracker.remove(jobs[1]);
            assertEquals(0, trackedJobs.get(0).size());
            assertEquals(0, trackedJobs.get(1).size());
            assertEquals(1, trackedJobs.get(2).size());
            assertEquals(0, trackedJobs.get(3).size());
        }
    }

    @Test
    public void testExceptions_Expedited() {
        JobInfo.Builder jb = createJob(0);
        jb.setExpedited(true);
        JobStatus js = createJobStatus("testExceptions_Expedited", jb);
        assertFalse(js.hasFlexibilityConstraint());
    }

    @Test
    public void testExceptions_ShortWindow() {
        JobInfo.Builder jb = createJob(0);
        jb.setMinimumLatency(1);
        jb.setOverrideDeadline(2);
        JobStatus js = createJobStatus("Disable Flexible When Job Has Short Window", jb);
        assertFalse(js.hasFlexibilityConstraint());
    }

    @Test
    public void testExceptions_Prefetch() {
        JobInfo.Builder jb = createJob(0);
        jb.setPrefetch(true);
        JobStatus js = createJobStatus("testExceptions_Prefetch", jb);
        assertFalse(js.hasFlexibilityConstraint());
    }

    @Test
    public void testExceptions_NoFlexibleConstraints() {
        JobInfo.Builder jb = createJob(0);
        jb.setRequiresDeviceIdle(true);
        jb.setRequiresCharging(true);
        jb.setRequiresBatteryNotLow(true);
        JobStatus js = createJobStatus("testExceptions_NoFlexibleConstraints", jb);
        assertFalse(js.hasFlexibilityConstraint());
    }

    @Test
    public void testExceptions_None() {
        JobInfo.Builder jb = createJob(0);
        JobStatus js = createJobStatus("testExceptions_None", jb);
        assertTrue(js.hasFlexibilityConstraint());
        assertEquals(3, js.getNumRequiredFlexibleConstraints());
    }

    @Test
    public void testTopAppBypass() {
        JobInfo.Builder jb = createJob(0);
        JobStatus js = createJobStatus("testTopAppBypass", jb);
        js.adjustNumRequiredFlexibleConstraints(100);
        mJobStore.add(js);

        // Needed because if before and after Uid bias is the same, nothing happens.
        when(mJobSchedulerService.getUidBias(js.getUid()))
                .thenReturn(JobInfo.BIAS_FOREGROUND_SERVICE);

        synchronized (mFlexibilityController.mLock) {
            setUidBias(js.getUid(), JobInfo.BIAS_TOP_APP);

            assertTrue(mFlexibilityController.isFlexibilitySatisfiedLocked(js));
            assertTrue(js.isConstraintSatisfied(CONSTRAINT_FLEXIBLE));

            setUidBias(js.getUid(), JobInfo.BIAS_FOREGROUND_SERVICE);

            assertFalse(mFlexibilityController.isFlexibilitySatisfiedLocked(js));
            assertFalse(js.isConstraintSatisfied(CONSTRAINT_FLEXIBLE));
        }
    }

    @Test
    public void testConnectionToUnMeteredNetwork() {
        JobInfo.Builder jb = createJob(0).setRequiredNetworkType(NETWORK_TYPE_ANY);
        JobStatus js = createJobStatus("testTopAppBypass", jb);
        synchronized (mFlexibilityController.mLock) {
            js.setHasAccessToUnmetered(false);
            assertEquals(0, mFlexibilityController.getNumSatisfiedRequiredConstraintsLocked(js));
            js.setHasAccessToUnmetered(true);
            assertEquals(1, mFlexibilityController.getNumSatisfiedRequiredConstraintsLocked(js));
            js.setHasAccessToUnmetered(false);
            assertEquals(0, mFlexibilityController.getNumSatisfiedRequiredConstraintsLocked(js));
        }
    }

    @Test
    public void testSetConstraintSatisfied_Constraints() {
        mFlexibilityController.setConstraintSatisfied(CONSTRAINT_IDLE, false);
        assertFalse(mFlexibilityController.isConstraintSatisfied(CONSTRAINT_IDLE));

        mFlexibilityController.setConstraintSatisfied(CONSTRAINT_IDLE, true);
        assertTrue(mFlexibilityController.isConstraintSatisfied(CONSTRAINT_IDLE));

        mFlexibilityController.setConstraintSatisfied(CONSTRAINT_IDLE, false);
        assertFalse(mFlexibilityController.isConstraintSatisfied(CONSTRAINT_IDLE));
    }

    @Test
    public void testSetConstraintSatisfied_Jobs() {
        JobInfo.Builder jb;
        int[] constraintPermutations = {
                CONSTRAINT_IDLE & CONSTRAINT_CHARGING & CONSTRAINT_BATTERY_NOT_LOW,
                CONSTRAINT_IDLE & CONSTRAINT_BATTERY_NOT_LOW,
                CONSTRAINT_IDLE & CONSTRAINT_CHARGING,
                CONSTRAINT_CHARGING & CONSTRAINT_BATTERY_NOT_LOW,
                CONSTRAINT_IDLE,
                CONSTRAINT_CHARGING,
                CONSTRAINT_BATTERY_NOT_LOW,
                0
        };

        int constraints;
        for (int i = 0; i < constraintPermutations.length; i++) {
            jb = createJob(i);
            constraints = constraintPermutations[i];
            jb.setRequiresDeviceIdle((constraints & CONSTRAINT_IDLE) != 0);
            jb.setRequiresBatteryNotLow((constraints & CONSTRAINT_BATTERY_NOT_LOW) != 0);
            jb.setRequiresCharging((constraints & CONSTRAINT_CHARGING) != 0);
            synchronized (mFlexibilityController.mLock) {
                mFlexibilityController.maybeStartTrackingJobLocked(
                        createJobStatus(String.valueOf(i), jb), null);
            }
        }
        mFlexibilityController.setConstraintSatisfied(CONSTRAINT_CHARGING, false);
        mFlexibilityController.setConstraintSatisfied(CONSTRAINT_IDLE, false);
        mFlexibilityController.setConstraintSatisfied(CONSTRAINT_BATTERY_NOT_LOW, false);

        assertEquals(0, mFlexibilityController.mSatisfiedFlexibleConstraints);

        for (int i = 0; i < constraintPermutations.length; i++) {
            constraints = constraintPermutations[i];
            mFlexibilityController.setConstraintSatisfied(CONSTRAINT_CHARGING,
                    (constraints & CONSTRAINT_CHARGING) != 0);
            mFlexibilityController.setConstraintSatisfied(CONSTRAINT_IDLE,
                    (constraints & CONSTRAINT_IDLE) != 0);
            mFlexibilityController.setConstraintSatisfied(CONSTRAINT_BATTERY_NOT_LOW,
                    (constraints & CONSTRAINT_BATTERY_NOT_LOW) != 0);

            assertEquals(constraints, mFlexibilityController.mSatisfiedFlexibleConstraints);
            synchronized (mFlexibilityController.mLock) {
                assertSatisfiedJobsMatchSatisfiedConstraints(
                        mFlexibilityController.mFlexibilityTracker.getArrayList(), constraints);
            }
        }
    }

    private void setUidBias(int uid, int bias) {
        int prevBias = mJobSchedulerService.getUidBias(uid);
        doReturn(bias).when(mJobSchedulerService).getUidBias(uid);
        synchronized (mFlexibilityController.mLock) {
            mFlexibilityController.onUidBiasChangedLocked(uid, prevBias, bias);
        }
    }

    private void assertSatisfiedJobsMatchSatisfiedConstraints(
            ArrayList<ArraySet<JobStatus>> trackedJobs, int satisfiedConstraints) {
        int numSatisfiedConstraints;
        numSatisfiedConstraints = Integer.bitCount(satisfiedConstraints);
        for (int i = 0; i < trackedJobs.size(); i++) {
            ArraySet<JobStatus> jobs = trackedJobs.get(i);
            for (int j = 0; j < jobs.size(); j++) {
                JobStatus js = jobs.valueAt(j);
                final int isUnMetered = js.getPreferUnmetered()
                        && js.getHasAccessToUnmetered() ? 1 : 0;
                assertEquals(js.getNumRequiredFlexibleConstraints()
                                <= numSatisfiedConstraints + isUnMetered,
                        js.isConstraintSatisfied(CONSTRAINT_FLEXIBLE));
            }
        }
    }
}
