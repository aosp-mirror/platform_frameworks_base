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

import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.mockitoSession;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.app.AlarmManager;
import android.app.job.JobInfo;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManagerInternal;
import android.os.Looper;
import android.util.ArraySet;

import com.android.server.LocalServices;
import com.android.server.job.JobSchedulerService;
import com.android.server.job.JobStore;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoSession;
import org.mockito.quality.Strictness;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;

public class FlexibilityControllerTest {
    private static final String SOURCE_PACKAGE = "com.android.frameworks.mockingservicestests";
    private static final int SOURCE_USER_ID = 0;

    private MockitoSession mMockingSession;
    private FlexibilityController mFlexibilityController;
    @Mock
    private AlarmManager mAlarmManager;
    @Mock
    private Context mContext;
    @Mock
    private JobSchedulerService mJobSchedulerService;
    private JobStore mJobStore;

    @Before
    public void setup() {
        mMockingSession = mockitoSession()
                .initMocks(this)
                .strictness(Strictness.LENIENT)
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
    }

    @After
    public void teardown() {
        if (mMockingSession != null) {
            mMockingSession.finishMocking();
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
                        FlexibilityTracker(FlexibilityController.FLEXIBLE_CONSTRAINTS);

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

        ArrayList<ArraySet<JobStatus>> trackedJobs = flexTracker.getArrayList();
        assertEquals(1, trackedJobs.get(0).size());
        assertEquals(1, trackedJobs.get(1).size());
        assertEquals(1, trackedJobs.get(2).size());
        assertEquals(0, trackedJobs.get(3).size());

        flexTracker.adjustJobsRequiredConstraints(jobs[0], -1);
        assertEquals(1, trackedJobs.get(0).size());
        assertEquals(2, trackedJobs.get(1).size());
        assertEquals(0, trackedJobs.get(2).size());
        assertEquals(0, trackedJobs.get(3).size());

        flexTracker.adjustJobsRequiredConstraints(jobs[0], -1);
        assertEquals(2, trackedJobs.get(0).size());
        assertEquals(1, trackedJobs.get(1).size());
        assertEquals(0, trackedJobs.get(2).size());
        assertEquals(0, trackedJobs.get(3).size());

        flexTracker.adjustJobsRequiredConstraints(jobs[0], -1);
        assertEquals(1, trackedJobs.get(0).size());
        assertEquals(1, trackedJobs.get(1).size());
        assertEquals(0, trackedJobs.get(2).size());
        assertEquals(0, trackedJobs.get(3).size());

        flexTracker.remove(jobs[1]);
        assertEquals(1, trackedJobs.get(0).size());
        assertEquals(0, trackedJobs.get(1).size());
        assertEquals(0, trackedJobs.get(2).size());
        assertEquals(0, trackedJobs.get(3).size());
    }
}
