/*
 * Copyright (C) 2024 The Android Open Source Project
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

import static android.app.job.Flags.FLAG_HANDLE_ABANDONED_JOBS;
import static android.text.format.DateUtils.MINUTE_IN_MILLIS;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.doNothing;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.mock;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.mockitoSession;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.spyOn;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import android.app.AppGlobals;
import android.app.job.JobParameters;
import android.compat.testing.PlatformCompatChangeRule;
import android.content.Context;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.platform.test.annotations.DisableFlags;
import android.platform.test.annotations.EnableFlags;
import android.platform.test.flag.junit.SetFlagsRule;

import com.android.internal.app.IBatteryStats;
import com.android.server.job.JobServiceContext.JobCallback;
import com.android.server.job.controllers.JobStatus;

import libcore.junit.util.compat.CoreCompatChangeRule.DisableCompatChanges;
import libcore.junit.util.compat.CoreCompatChangeRule.EnableCompatChanges;

import org.junit.After;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestRule;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoSession;
import org.mockito.quality.Strictness;

import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;

public class JobServiceContextTest {
    private static final String SOURCE_PACKAGE = "com.android.frameworks.mockingservicestests";
    private static final String TAG = JobServiceContextTest.class.getSimpleName();
    @ClassRule
    public static final SetFlagsRule.ClassRule mSetFlagsClassRule = new SetFlagsRule.ClassRule();
    @Rule
    public final SetFlagsRule mSetFlagsRule = mSetFlagsClassRule.createSetFlagsRule();
    @Rule
    public TestRule compatChangeRule = new PlatformCompatChangeRule();
    @Mock
    private JobSchedulerService mMockJobSchedulerService;
    @Mock
    private JobConcurrencyManager mMockConcurrencyManager;
    @Mock
    private JobNotificationCoordinator mMockNotificationCoordinator;
    @Mock
    private IBatteryStats.Stub mMockBatteryStats;
    @Mock
    private JobPackageTracker mMockJobPackageTracker;
    @Mock
    private Looper mMockLooper;
    @Mock
    private Context mMockContext;
    @Mock
    private JobStatus mMockJobStatus;
    @Mock
    private JobParameters mMockJobParameters;
    @Mock
    private JobCallback mMockJobCallback;
    private MockitoSession mMockingSession;
    private JobServiceContext mJobServiceContext;
    private Object mLock;
    private int mSourceUid;

    @Before
    public void setUp() throws Exception {
        mMockingSession =
                mockitoSession()
                        .initMocks(this)
                        .strictness(Strictness.LENIENT)
                        .startMocking();
        JobSchedulerService.sElapsedRealtimeClock =
                Clock.fixed(SystemClock.elapsedRealtimeClock().instant(), ZoneOffset.UTC);
        doReturn(mock(PowerManager.class)).when(mMockContext).getSystemService(PowerManager.class);
        doReturn(mMockContext).when(mMockJobSchedulerService).getContext();
        mLock = new Object();
        doReturn(mLock).when(mMockJobSchedulerService).getLock();
        mJobServiceContext =
                new JobServiceContext(
                        mMockJobSchedulerService,
                        mMockConcurrencyManager,
                        mMockNotificationCoordinator,
                        mMockBatteryStats,
                        mMockJobPackageTracker,
                        mMockLooper);
        spyOn(mJobServiceContext);
        mJobServiceContext.setJobParamsLockedForTest(mMockJobParameters);
        mSourceUid = AppGlobals.getPackageManager().getPackageUid(SOURCE_PACKAGE, 0, 0);
    }

    @After
    public void tearDown() throws Exception {
        if (mMockingSession != null) {
            mMockingSession.finishMocking();
        }
    }

    private Clock getAdvancedClock(Clock clock, long incrementMs) {
        return Clock.offset(clock, Duration.ofMillis(incrementMs));
    }

    private void advanceElapsedClock(long incrementMs) {
        JobSchedulerService.sElapsedRealtimeClock =
                getAdvancedClock(JobSchedulerService.sElapsedRealtimeClock, incrementMs);
    }

    /**
     * Test that with the compat change disabled and the flag enabled, abandoned
     * jobs that are timed out are stopped with the correct stop reason and the
     * job is marked as abandoned.
     */
    @Test
    @EnableFlags(FLAG_HANDLE_ABANDONED_JOBS)
    @DisableCompatChanges({JobParameters.OVERRIDE_HANDLE_ABANDONED_JOBS})
    public void testJobServiceContext_TimeoutAbandonedJob_EnableFlagDisableCompat() {
        mJobServiceContext.mVerb = JobServiceContext.VERB_EXECUTING;
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        doNothing().when(mJobServiceContext).sendStopMessageLocked(captor.capture());

        advanceElapsedClock(30 * MINUTE_IN_MILLIS); // 30 minutes
        mJobServiceContext.setPendingStopReasonLockedForTest(JobParameters.STOP_REASON_UNDEFINED);

        mJobServiceContext.setRunningJobLockedForTest(mMockJobStatus);
        doReturn(mSourceUid).when(mMockJobStatus).getSourceUid();
        doReturn(true).when(mMockJobStatus).isAbandoned();
        mJobServiceContext.mVerb = JobServiceContext.VERB_EXECUTING;

        mJobServiceContext.handleOpTimeoutLocked();

        String stopMessage = captor.getValue();
        assertEquals("timeout while executing and maybe abandoned", stopMessage);
        verify(mMockJobParameters)
                .setStopReason(
                        JobParameters.STOP_REASON_TIMEOUT_ABANDONED,
                        JobParameters.INTERNAL_STOP_REASON_TIMEOUT_ABANDONED,
                        "client timed out and maybe abandoned");
    }

    /**
     * Test that with the compat change enabled and the flag enabled, abandoned
     * jobs that are timed out are stopped with the correct stop reason and the
     * job is not marked as abandoned.
     */
    @Test
    @EnableFlags(FLAG_HANDLE_ABANDONED_JOBS)
    @EnableCompatChanges({JobParameters.OVERRIDE_HANDLE_ABANDONED_JOBS})
    public void testJobServiceContext_TimeoutAbandonedJob_EnableFlagEnableCompat() {
        mJobServiceContext.mVerb = JobServiceContext.VERB_EXECUTING;
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        doNothing().when(mJobServiceContext).sendStopMessageLocked(captor.capture());

        advanceElapsedClock(30 * MINUTE_IN_MILLIS); // 30 minutes
        mJobServiceContext.setPendingStopReasonLockedForTest(JobParameters.STOP_REASON_UNDEFINED);

        mJobServiceContext.setRunningJobLockedForTest(mMockJobStatus);
        doReturn(mSourceUid).when(mMockJobStatus).getSourceUid();
        doReturn(true).when(mMockJobStatus).isAbandoned();
        mJobServiceContext.mVerb = JobServiceContext.VERB_EXECUTING;

        mJobServiceContext.handleOpTimeoutLocked();

        String stopMessage = captor.getValue();
        assertEquals("timeout while executing", stopMessage);
        verify(mMockJobParameters)
                .setStopReason(
                        JobParameters.STOP_REASON_TIMEOUT,
                        JobParameters.INTERNAL_STOP_REASON_TIMEOUT,
                        "client timed out");
    }

    /**
     * Test that with the compat change disabled and the flag disabled, abandoned
     * jobs that are timed out are stopped with the correct stop reason and the
     * job is not marked as abandoned.
     */
    @Test
    @DisableFlags(FLAG_HANDLE_ABANDONED_JOBS)
    @DisableCompatChanges({JobParameters.OVERRIDE_HANDLE_ABANDONED_JOBS})
    public void testJobServiceContext_TimeoutAbandonedJob_DisableFlagDisableCompat() {
        mJobServiceContext.mVerb = JobServiceContext.VERB_EXECUTING;
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        doNothing().when(mJobServiceContext).sendStopMessageLocked(captor.capture());

        advanceElapsedClock(30 * MINUTE_IN_MILLIS); // 30 minutes
        mJobServiceContext.setPendingStopReasonLockedForTest(JobParameters.STOP_REASON_UNDEFINED);

        mJobServiceContext.setRunningJobLockedForTest(mMockJobStatus);
        doReturn(true).when(mMockJobStatus).isAbandoned();
        doReturn(mSourceUid).when(mMockJobStatus).getSourceUid();
        mJobServiceContext.mVerb = JobServiceContext.VERB_EXECUTING;

        synchronized (mLock) {
            mJobServiceContext.handleOpTimeoutLocked();
        }

        String stopMessage = captor.getValue();
        assertEquals("timeout while executing", stopMessage);
        verify(mMockJobParameters)
                .setStopReason(
                        JobParameters.STOP_REASON_TIMEOUT,
                        JobParameters.INTERNAL_STOP_REASON_TIMEOUT,
                        "client timed out");
    }

    /**
     * Test that non-abandoned jobs that are timed out are stopped with the correct stop reason
     */
    @Test
    @EnableFlags(FLAG_HANDLE_ABANDONED_JOBS)
    @DisableCompatChanges({JobParameters.OVERRIDE_HANDLE_ABANDONED_JOBS})
    public void testJobServiceContext_TimeoutNoAbandonedJob() {
        mJobServiceContext.mVerb = JobServiceContext.VERB_EXECUTING;
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        synchronized (mLock) {
            doNothing().when(mJobServiceContext).sendStopMessageLocked(captor.capture());
        }
        advanceElapsedClock(30 * MINUTE_IN_MILLIS); // 30 minutes
        mJobServiceContext.setPendingStopReasonLockedForTest(JobParameters.STOP_REASON_UNDEFINED);

        mJobServiceContext.setRunningJobLockedForTest(mMockJobStatus);
        doReturn(false).when(mMockJobStatus).isAbandoned();
        mJobServiceContext.mVerb = JobServiceContext.VERB_EXECUTING;

        mJobServiceContext.handleOpTimeoutLocked();

        String stopMessage = captor.getValue();
        assertEquals("timeout while executing", stopMessage);
        verify(mMockJobParameters)
                .setStopReason(
                        JobParameters.STOP_REASON_TIMEOUT,
                        JobParameters.INTERNAL_STOP_REASON_TIMEOUT,
                        "client timed out");
    }

    /**
     * Test that the JobStatus is marked as abandoned when the JobServiceContext
     * receives a MSG_HANDLE_ABANDONED_JOB message
     */
    @Test
    @EnableFlags(FLAG_HANDLE_ABANDONED_JOBS)
    public void testJobServiceContext_HandleAbandonedJob() {
        final int jobId = 123;
        mJobServiceContext.setRunningJobLockedForTest(mMockJobStatus);
        mJobServiceContext.setRunningCallbackLockedForTest(mMockJobCallback);
        doReturn(jobId).when(mMockJobStatus).getJobId();

        mJobServiceContext.doHandleAbandonedJob(mMockJobCallback, jobId);

        verify(mMockJobStatus).setAbandoned(true);
    }

    /**
     * Test that the JobStatus is not marked as abandoned when the
     * JobServiceContext receives a MSG_HANDLE_ABANDONED_JOB message and the
     * JobServiceContext is not running a job
     */
    @Test
    @EnableFlags(FLAG_HANDLE_ABANDONED_JOBS)
    public void testJobServiceContext_HandleAbandonedJob_notRunningJob() {
        final int jobId = 123;
        mJobServiceContext.setRunningJobLockedForTest(null);
        mJobServiceContext.setRunningCallbackLockedForTest(mMockJobCallback);

        mJobServiceContext.doHandleAbandonedJob(mMockJobCallback, jobId);

        verify(mMockJobStatus, never()).setAbandoned(true);
    }

    /**
     * Test that the JobStatus is not marked as abandoned when the
     * JobServiceContext receives a MSG_HANDLE_ABANDONED_JOB message and the
     * JobServiceContext is running a job with a different jobId
     */
    @Test
    @EnableFlags(FLAG_HANDLE_ABANDONED_JOBS)
    public void testJobServiceContext_HandleAbandonedJob_differentJobId() {
        final int jobId = 123;
        final int differentJobId = 456;
        mJobServiceContext.setRunningJobLockedForTest(mMockJobStatus);
        mJobServiceContext.setRunningCallbackLockedForTest(mMockJobCallback);
        doReturn(differentJobId).when(mMockJobStatus).getJobId();

        mJobServiceContext.doHandleAbandonedJob(mMockJobCallback, jobId);

        verify(mMockJobStatus, never()).setAbandoned(true);
    }

}
