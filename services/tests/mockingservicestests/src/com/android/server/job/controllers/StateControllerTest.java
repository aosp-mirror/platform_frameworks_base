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
import static com.android.dx.mockito.inline.extended.ExtendedMockito.spy;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.when;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;

import android.app.AlarmManager;
import android.app.job.JobInfo;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManagerInternal;
import android.os.SystemClock;
import android.util.proto.ProtoOutputStream;

import androidx.test.runner.AndroidJUnit4;

import com.android.internal.util.IndentingPrintWriter;
import com.android.server.LocalServices;
import com.android.server.job.JobSchedulerService;
import com.android.server.job.JobSchedulerService.Constants;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoSession;
import org.mockito.quality.Strictness;

import java.time.Clock;
import java.time.ZoneOffset;
import java.util.function.Predicate;

@RunWith(AndroidJUnit4.class)
public class StateControllerTest {
    private static final long SECOND_IN_MILLIS = 1000L;
    private static final long MINUTE_IN_MILLIS = 60 * SECOND_IN_MILLIS;
    private static final long HOUR_IN_MILLIS = 60 * MINUTE_IN_MILLIS;
    private static final int CALLING_UID = 1000;
    private static final String SOURCE_PACKAGE = "com.android.frameworks.mockingservicestests";
    private static final int SOURCE_USER_ID = 0;

    private Constants mConstants;
    private StateController mStateController;

    private MockitoSession mMockingSession;
    @Mock
    private AlarmManager mAlarmManager;
    @Mock
    private Context mContext;
    @Mock
    private JobSchedulerService mJobSchedulerService;

    private class TestStateController extends StateController {
        TestStateController(JobSchedulerService service) {
            super(service);
        }

        public void maybeStartTrackingJobLocked(JobStatus jobStatus, JobStatus lastJob) {
        }

        public void maybeStopTrackingJobLocked(JobStatus jobStatus, JobStatus incomingJob,
                boolean forUpdate) {
        }

        public void dumpControllerStateLocked(IndentingPrintWriter pw,
                Predicate<JobStatus> predicate) {
        }

        public void dumpControllerStateLocked(ProtoOutputStream proto, long fieldId,
                Predicate<JobStatus> predicate) {
        }
    }

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
        // Called in QuotaController constructor.
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
        mStateController = new TestStateController(mJobSchedulerService);
    }

    @After
    public void tearDown() {
        if (mMockingSession != null) {
            mMockingSession.finishMocking();
        }
    }

    private JobStatus createJobStatus(String testTag, int jobId) {
        JobInfo jobInfo = new JobInfo.Builder(jobId,
                new ComponentName(mContext, "TestQuotaJobService"))
                .setMinimumLatency(Math.abs(jobId) + 1)
                .build();
        return JobStatus.createFromJobInfo(
                jobInfo, CALLING_UID, SOURCE_PACKAGE, SOURCE_USER_ID, testTag);
    }

    @Test
    public void testWouldBeReadyWithConstraintLocked() {
        JobStatus job = spy(createJobStatus("testWouldBeReadyWithConstraintLocked", 1));

        when(job.wouldBeReadyWithConstraint(anyInt())).thenReturn(false);
        assertFalse(mStateController.wouldBeReadyWithConstraintLocked(job, 1));

        when(job.wouldBeReadyWithConstraint(anyInt())).thenReturn(true);
        when(mJobSchedulerService.areComponentsInPlaceLocked(job)).thenReturn(false);
        assertFalse(mStateController.wouldBeReadyWithConstraintLocked(job, 1));

        when(job.wouldBeReadyWithConstraint(anyInt())).thenReturn(true);
        when(mJobSchedulerService.areComponentsInPlaceLocked(job)).thenReturn(true);
        assertTrue(mStateController.wouldBeReadyWithConstraintLocked(job, 1));
    }
}
