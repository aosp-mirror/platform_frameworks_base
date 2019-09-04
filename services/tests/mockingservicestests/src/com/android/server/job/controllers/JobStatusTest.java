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
 * limitations under the License
 */

package com.android.server.job.controllers;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.mock;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.mockitoSession;
import static com.android.server.job.controllers.JobStatus.CONSTRAINT_BACKGROUND_NOT_RESTRICTED;
import static com.android.server.job.controllers.JobStatus.CONSTRAINT_BATTERY_NOT_LOW;
import static com.android.server.job.controllers.JobStatus.CONSTRAINT_CHARGING;
import static com.android.server.job.controllers.JobStatus.CONSTRAINT_CONNECTIVITY;
import static com.android.server.job.controllers.JobStatus.CONSTRAINT_CONTENT_TRIGGER;
import static com.android.server.job.controllers.JobStatus.CONSTRAINT_DEADLINE;
import static com.android.server.job.controllers.JobStatus.CONSTRAINT_DEVICE_NOT_DOZING;
import static com.android.server.job.controllers.JobStatus.CONSTRAINT_IDLE;
import static com.android.server.job.controllers.JobStatus.CONSTRAINT_STORAGE_NOT_LOW;
import static com.android.server.job.controllers.JobStatus.CONSTRAINT_TIMING_DELAY;
import static com.android.server.job.controllers.JobStatus.CONSTRAINT_WITHIN_QUOTA;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.job.JobInfo;
import android.app.usage.UsageStatsManagerInternal;
import android.content.ComponentName;
import android.content.pm.PackageManagerInternal;
import android.os.SystemClock;
import android.provider.MediaStore;

import androidx.test.runner.AndroidJUnit4;

import com.android.server.LocalServices;
import com.android.server.job.JobSchedulerInternal;
import com.android.server.job.JobSchedulerService;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockitoSession;
import org.mockito.quality.Strictness;

import java.time.Clock;
import java.time.ZoneOffset;

@RunWith(AndroidJUnit4.class)
public class JobStatusTest {
    private static final double DELTA = 0.00001;

    private MockitoSession mMockingSession;

    @Before
    public void setUp() throws Exception {
        mMockingSession = mockitoSession()
                .initMocks(this)
                .strictness(Strictness.LENIENT)
                .mockStatic(LocalServices.class)
                .startMocking();
        doReturn(mock(JobSchedulerInternal.class))
                .when(() -> LocalServices.getService(JobSchedulerInternal.class));
        doReturn(mock(PackageManagerInternal.class))
                .when(() -> LocalServices.getService(PackageManagerInternal.class));
        doReturn(mock(UsageStatsManagerInternal.class))
                .when(() -> LocalServices.getService(UsageStatsManagerInternal.class));

        // Freeze the clocks at this moment in time
        JobSchedulerService.sSystemClock =
                Clock.fixed(Clock.systemUTC().instant(), ZoneOffset.UTC);
        JobSchedulerService.sUptimeMillisClock =
                Clock.fixed(SystemClock.uptimeMillisClock().instant(), ZoneOffset.UTC);
        JobSchedulerService.sElapsedRealtimeClock =
                Clock.fixed(SystemClock.elapsedRealtimeClock().instant(), ZoneOffset.UTC);
    }

    @After
    public void tearDown() {
        if (mMockingSession != null) {
            mMockingSession.finishMocking();
        }
    }

    @Test
    public void testFraction() throws Exception {
        final long now = JobSchedulerService.sElapsedRealtimeClock.millis();

        assertEquals(1, createJobStatus(0, Long.MAX_VALUE).getFractionRunTime(), DELTA);

        assertEquals(1, createJobStatus(0, now - 1000).getFractionRunTime(), DELTA);
        assertEquals(0, createJobStatus(0, now + 1000).getFractionRunTime(), DELTA);

        assertEquals(1, createJobStatus(now - 1000, Long.MAX_VALUE).getFractionRunTime(), DELTA);
        assertEquals(0, createJobStatus(now + 1000, Long.MAX_VALUE).getFractionRunTime(), DELTA);

        assertEquals(0, createJobStatus(now, now + 2000).getFractionRunTime(), DELTA);
        assertEquals(0.25, createJobStatus(now - 500, now + 1500).getFractionRunTime(), DELTA);
        assertEquals(0.5, createJobStatus(now - 1000, now + 1000).getFractionRunTime(), DELTA);
        assertEquals(0.75, createJobStatus(now - 1500, now + 500).getFractionRunTime(), DELTA);
        assertEquals(1, createJobStatus(now - 2000, now).getFractionRunTime(), DELTA);
    }

    /**
     * Test {@link JobStatus#wouldBeReadyWithConstraint} on explicit constraints that weren't
     * requested.
     */
    @Test
    public void testWouldBeReadyWithConstraint_NonRequestedConstraints() {
        // Job with no explicit constraints.
        final JobStatus job = createJobStatus(
                new JobInfo.Builder(101, new ComponentName("foo", "bar")).build());

        markImplicitConstraintsSatisfied(job, false);
        assertFalse(job.wouldBeReadyWithConstraint(CONSTRAINT_CHARGING));
        assertFalse(job.wouldBeReadyWithConstraint(CONSTRAINT_IDLE));
        assertFalse(job.wouldBeReadyWithConstraint(CONSTRAINT_BATTERY_NOT_LOW));
        assertFalse(job.wouldBeReadyWithConstraint(CONSTRAINT_STORAGE_NOT_LOW));
        assertFalse(job.wouldBeReadyWithConstraint(CONSTRAINT_TIMING_DELAY));
        assertFalse(job.wouldBeReadyWithConstraint(CONSTRAINT_DEADLINE));
        assertFalse(job.wouldBeReadyWithConstraint(CONSTRAINT_CONNECTIVITY));
        assertFalse(job.wouldBeReadyWithConstraint(CONSTRAINT_CONTENT_TRIGGER));

        markImplicitConstraintsSatisfied(job, true);
        assertTrue(job.wouldBeReadyWithConstraint(CONSTRAINT_CHARGING));
        assertTrue(job.wouldBeReadyWithConstraint(CONSTRAINT_IDLE));
        assertTrue(job.wouldBeReadyWithConstraint(CONSTRAINT_BATTERY_NOT_LOW));
        assertTrue(job.wouldBeReadyWithConstraint(CONSTRAINT_STORAGE_NOT_LOW));
        assertTrue(job.wouldBeReadyWithConstraint(CONSTRAINT_TIMING_DELAY));
        assertTrue(job.wouldBeReadyWithConstraint(CONSTRAINT_DEADLINE));
        assertTrue(job.wouldBeReadyWithConstraint(CONSTRAINT_CONNECTIVITY));
        assertTrue(job.wouldBeReadyWithConstraint(CONSTRAINT_CONTENT_TRIGGER));
    }

    @Test
    public void testWouldBeReadyWithConstraint_RequestedCharging() {
        final JobInfo jobInfo =
                new JobInfo.Builder(101, new ComponentName("foo", "bar"))
                        .setRequiresCharging(true)
                        .build();
        final JobStatus job = createJobStatus(jobInfo);

        markImplicitConstraintsSatisfied(job, true);
        job.setChargingConstraintSatisfied(false);
        assertTrue(job.wouldBeReadyWithConstraint(CONSTRAINT_CHARGING));
        job.setChargingConstraintSatisfied(true);
        assertTrue(job.wouldBeReadyWithConstraint(CONSTRAINT_CHARGING));

        markImplicitConstraintsSatisfied(job, false);
        job.setChargingConstraintSatisfied(false);
        assertFalse(job.wouldBeReadyWithConstraint(CONSTRAINT_CHARGING));
        job.setChargingConstraintSatisfied(true);
        assertFalse(job.wouldBeReadyWithConstraint(CONSTRAINT_CHARGING));
    }

    @Test
    public void testWouldBeReadyWithConstraint_RequestedDeviceIdle() {
        final JobInfo jobInfo =
                new JobInfo.Builder(101, new ComponentName("foo", "bar"))
                        .setRequiresDeviceIdle(true)
                        .build();
        final JobStatus job = createJobStatus(jobInfo);

        markImplicitConstraintsSatisfied(job, true);
        job.setIdleConstraintSatisfied(false);
        assertTrue(job.wouldBeReadyWithConstraint(CONSTRAINT_IDLE));
        job.setIdleConstraintSatisfied(true);
        assertTrue(job.wouldBeReadyWithConstraint(CONSTRAINT_IDLE));

        markImplicitConstraintsSatisfied(job, false);
        job.setIdleConstraintSatisfied(false);
        assertFalse(job.wouldBeReadyWithConstraint(CONSTRAINT_IDLE));
        job.setIdleConstraintSatisfied(true);
        assertFalse(job.wouldBeReadyWithConstraint(CONSTRAINT_IDLE));
    }

    @Test
    public void testWouldBeReadyWithConstraint_RequestedBatteryNotLow() {
        final JobInfo jobInfo =
                new JobInfo.Builder(101, new ComponentName("foo", "bar"))
                        .setRequiresBatteryNotLow(true)
                        .build();
        final JobStatus job = createJobStatus(jobInfo);

        markImplicitConstraintsSatisfied(job, true);
        job.setBatteryNotLowConstraintSatisfied(false);
        assertTrue(job.wouldBeReadyWithConstraint(CONSTRAINT_BATTERY_NOT_LOW));
        job.setBatteryNotLowConstraintSatisfied(true);
        assertTrue(job.wouldBeReadyWithConstraint(CONSTRAINT_BATTERY_NOT_LOW));

        markImplicitConstraintsSatisfied(job, false);
        job.setBatteryNotLowConstraintSatisfied(false);
        assertFalse(job.wouldBeReadyWithConstraint(CONSTRAINT_BATTERY_NOT_LOW));
        job.setBatteryNotLowConstraintSatisfied(true);
        assertFalse(job.wouldBeReadyWithConstraint(CONSTRAINT_BATTERY_NOT_LOW));
    }

    @Test
    public void testWouldBeReadyWithConstraint_RequestedStorageNotLow() {
        final JobInfo jobInfo =
                new JobInfo.Builder(101, new ComponentName("foo", "bar"))
                        .setRequiresStorageNotLow(true)
                        .build();
        final JobStatus job = createJobStatus(jobInfo);

        markImplicitConstraintsSatisfied(job, true);
        job.setStorageNotLowConstraintSatisfied(false);
        assertTrue(job.wouldBeReadyWithConstraint(CONSTRAINT_STORAGE_NOT_LOW));
        job.setStorageNotLowConstraintSatisfied(true);
        assertTrue(job.wouldBeReadyWithConstraint(CONSTRAINT_STORAGE_NOT_LOW));

        markImplicitConstraintsSatisfied(job, false);
        job.setStorageNotLowConstraintSatisfied(false);
        assertFalse(job.wouldBeReadyWithConstraint(CONSTRAINT_STORAGE_NOT_LOW));
        job.setStorageNotLowConstraintSatisfied(true);
        assertFalse(job.wouldBeReadyWithConstraint(CONSTRAINT_STORAGE_NOT_LOW));
    }

    @Test
    public void testWouldBeReadyWithConstraint_RequestedTimingDelay() {
        final JobInfo jobInfo =
                new JobInfo.Builder(101, new ComponentName("foo", "bar"))
                        .setMinimumLatency(60_000)
                        .build();
        final JobStatus job = createJobStatus(jobInfo);

        markImplicitConstraintsSatisfied(job, true);
        job.setTimingDelayConstraintSatisfied(false);
        assertTrue(job.wouldBeReadyWithConstraint(CONSTRAINT_TIMING_DELAY));
        job.setTimingDelayConstraintSatisfied(true);
        assertTrue(job.wouldBeReadyWithConstraint(CONSTRAINT_TIMING_DELAY));

        markImplicitConstraintsSatisfied(job, false);
        job.setTimingDelayConstraintSatisfied(false);
        assertFalse(job.wouldBeReadyWithConstraint(CONSTRAINT_TIMING_DELAY));
        job.setTimingDelayConstraintSatisfied(true);
        assertFalse(job.wouldBeReadyWithConstraint(CONSTRAINT_TIMING_DELAY));
    }

    @Test
    public void testWouldBeReadyWithConstraint_RequestedOverrideDeadline() {
        final JobInfo jobInfo =
                new JobInfo.Builder(101, new ComponentName("foo", "bar"))
                        .setOverrideDeadline(300_000)
                        .build();
        final JobStatus job = createJobStatus(jobInfo);

        markImplicitConstraintsSatisfied(job, true);
        job.setDeadlineConstraintSatisfied(false);
        assertTrue(job.wouldBeReadyWithConstraint(CONSTRAINT_DEADLINE));
        job.setDeadlineConstraintSatisfied(true);
        assertTrue(job.wouldBeReadyWithConstraint(CONSTRAINT_DEADLINE));

        markImplicitConstraintsSatisfied(job, false);
        job.setDeadlineConstraintSatisfied(false);
        assertFalse(job.wouldBeReadyWithConstraint(CONSTRAINT_DEADLINE));
        job.setDeadlineConstraintSatisfied(true);
        assertFalse(job.wouldBeReadyWithConstraint(CONSTRAINT_DEADLINE));
    }

    @Test
    public void testWouldBeReadyWithConstraint_RequestedConnectivity() {
        final JobInfo jobInfo =
                new JobInfo.Builder(101, new ComponentName("foo", "bar"))
                        .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                        .build();
        final JobStatus job = createJobStatus(jobInfo);

        markImplicitConstraintsSatisfied(job, true);
        job.setConnectivityConstraintSatisfied(false);
        assertTrue(job.wouldBeReadyWithConstraint(CONSTRAINT_CONNECTIVITY));
        job.setConnectivityConstraintSatisfied(true);
        assertTrue(job.wouldBeReadyWithConstraint(CONSTRAINT_CONNECTIVITY));

        markImplicitConstraintsSatisfied(job, false);
        job.setConnectivityConstraintSatisfied(false);
        assertFalse(job.wouldBeReadyWithConstraint(CONSTRAINT_CONNECTIVITY));
        job.setConnectivityConstraintSatisfied(true);
        assertFalse(job.wouldBeReadyWithConstraint(CONSTRAINT_CONNECTIVITY));
    }

    @Test
    public void testWouldBeReadyWithConstraint_RequestedContentTrigger() {
        final JobInfo jobInfo =
                new JobInfo.Builder(101, new ComponentName("foo", "bar"))
                        .addTriggerContentUri(new JobInfo.TriggerContentUri(
                                MediaStore.Images.Media.INTERNAL_CONTENT_URI,
                                JobInfo.TriggerContentUri.FLAG_NOTIFY_FOR_DESCENDANTS))
                        .build();
        final JobStatus job = createJobStatus(jobInfo);

        markImplicitConstraintsSatisfied(job, true);
        job.setContentTriggerConstraintSatisfied(false);
        assertTrue(job.wouldBeReadyWithConstraint(CONSTRAINT_CONTENT_TRIGGER));
        job.setContentTriggerConstraintSatisfied(true);
        assertTrue(job.wouldBeReadyWithConstraint(CONSTRAINT_CONTENT_TRIGGER));

        markImplicitConstraintsSatisfied(job, false);
        job.setContentTriggerConstraintSatisfied(false);
        assertFalse(job.wouldBeReadyWithConstraint(CONSTRAINT_CONTENT_TRIGGER));
        job.setContentTriggerConstraintSatisfied(true);
        assertFalse(job.wouldBeReadyWithConstraint(CONSTRAINT_CONTENT_TRIGGER));
    }

    @Test
    public void testWouldBeReadyWithConstraint_RequestedMixture_NoDeadline() {
        final JobInfo jobInfo =
                new JobInfo.Builder(101, new ComponentName("foo", "bar"))
                        .setRequiresCharging(true)
                        .setRequiredNetworkType(JobInfo.NETWORK_TYPE_CELLULAR)
                        .addTriggerContentUri(new JobInfo.TriggerContentUri(
                                MediaStore.Images.Media.INTERNAL_CONTENT_URI,
                                JobInfo.TriggerContentUri.FLAG_NOTIFY_FOR_DESCENDANTS))
                        .build();
        final JobStatus job = createJobStatus(jobInfo);

        markImplicitConstraintsSatisfied(job, false);

        job.setChargingConstraintSatisfied(false);
        job.setConnectivityConstraintSatisfied(false);
        job.setContentTriggerConstraintSatisfied(false);
        assertFalse(job.wouldBeReadyWithConstraint(CONSTRAINT_CHARGING));
        assertFalse(job.wouldBeReadyWithConstraint(CONSTRAINT_CONNECTIVITY));
        assertFalse(job.wouldBeReadyWithConstraint(CONSTRAINT_CONTENT_TRIGGER));
        job.setChargingConstraintSatisfied(true);
        job.setConnectivityConstraintSatisfied(true);
        job.setContentTriggerConstraintSatisfied(true);
        // Still false because implicit constraints aren't satisfied.
        assertFalse(job.wouldBeReadyWithConstraint(CONSTRAINT_CHARGING));
        assertFalse(job.wouldBeReadyWithConstraint(CONSTRAINT_CONNECTIVITY));
        assertFalse(job.wouldBeReadyWithConstraint(CONSTRAINT_CONTENT_TRIGGER));

        markImplicitConstraintsSatisfied(job, true);

        job.setChargingConstraintSatisfied(false);
        job.setConnectivityConstraintSatisfied(false);
        job.setContentTriggerConstraintSatisfied(false);
        assertFalse(job.wouldBeReadyWithConstraint(CONSTRAINT_CHARGING));
        assertFalse(job.wouldBeReadyWithConstraint(CONSTRAINT_CONNECTIVITY));
        assertFalse(job.wouldBeReadyWithConstraint(CONSTRAINT_CONTENT_TRIGGER));

        // Turn on constraints one at a time.
        job.setChargingConstraintSatisfied(true);
        job.setConnectivityConstraintSatisfied(false);
        job.setContentTriggerConstraintSatisfied(false);
        assertFalse(job.wouldBeReadyWithConstraint(CONSTRAINT_CHARGING));
        assertFalse(job.wouldBeReadyWithConstraint(CONSTRAINT_CONNECTIVITY));
        assertFalse(job.wouldBeReadyWithConstraint(CONSTRAINT_CONTENT_TRIGGER));

        job.setChargingConstraintSatisfied(false);
        job.setConnectivityConstraintSatisfied(false);
        job.setContentTriggerConstraintSatisfied(true);
        assertFalse(job.wouldBeReadyWithConstraint(CONSTRAINT_CHARGING));
        assertFalse(job.wouldBeReadyWithConstraint(CONSTRAINT_CONNECTIVITY));
        assertFalse(job.wouldBeReadyWithConstraint(CONSTRAINT_CONTENT_TRIGGER));

        job.setChargingConstraintSatisfied(false);
        job.setConnectivityConstraintSatisfied(true);
        job.setContentTriggerConstraintSatisfied(false);
        assertFalse(job.wouldBeReadyWithConstraint(CONSTRAINT_CHARGING));
        assertFalse(job.wouldBeReadyWithConstraint(CONSTRAINT_CONNECTIVITY));
        assertFalse(job.wouldBeReadyWithConstraint(CONSTRAINT_CONTENT_TRIGGER));

        // With two of the 3 constraints satisfied (and implicit constraints also satisfied), only
        // the unsatisfied constraint should return true.
        job.setChargingConstraintSatisfied(true);
        job.setConnectivityConstraintSatisfied(false);
        job.setContentTriggerConstraintSatisfied(true);
        assertFalse(job.wouldBeReadyWithConstraint(CONSTRAINT_CHARGING));
        assertTrue(job.wouldBeReadyWithConstraint(CONSTRAINT_CONNECTIVITY));
        assertFalse(job.wouldBeReadyWithConstraint(CONSTRAINT_CONTENT_TRIGGER));

        job.setChargingConstraintSatisfied(true);
        job.setConnectivityConstraintSatisfied(true);
        job.setContentTriggerConstraintSatisfied(false);
        assertFalse(job.wouldBeReadyWithConstraint(CONSTRAINT_CHARGING));
        assertFalse(job.wouldBeReadyWithConstraint(CONSTRAINT_CONNECTIVITY));
        assertTrue(job.wouldBeReadyWithConstraint(CONSTRAINT_CONTENT_TRIGGER));

        job.setChargingConstraintSatisfied(false);
        job.setConnectivityConstraintSatisfied(true);
        job.setContentTriggerConstraintSatisfied(true);
        assertTrue(job.wouldBeReadyWithConstraint(CONSTRAINT_CHARGING));
        assertFalse(job.wouldBeReadyWithConstraint(CONSTRAINT_CONNECTIVITY));
        assertFalse(job.wouldBeReadyWithConstraint(CONSTRAINT_CONTENT_TRIGGER));

        job.setChargingConstraintSatisfied(true);
        job.setConnectivityConstraintSatisfied(true);
        job.setContentTriggerConstraintSatisfied(true);
        assertTrue(job.wouldBeReadyWithConstraint(CONSTRAINT_CHARGING));
        assertTrue(job.wouldBeReadyWithConstraint(CONSTRAINT_CONNECTIVITY));
        assertTrue(job.wouldBeReadyWithConstraint(CONSTRAINT_CONTENT_TRIGGER));
    }

    @Test
    public void testWouldBeReadyWithConstraint_RequestedMixture_WithDeadline() {
        final JobInfo jobInfo =
                new JobInfo.Builder(101, new ComponentName("foo", "bar"))
                        .setRequiresCharging(true)
                        .setOverrideDeadline(300_000)
                        .addTriggerContentUri(new JobInfo.TriggerContentUri(
                                MediaStore.Images.Media.INTERNAL_CONTENT_URI,
                                JobInfo.TriggerContentUri.FLAG_NOTIFY_FOR_DESCENDANTS))
                        .build();
        final JobStatus job = createJobStatus(jobInfo);

        markImplicitConstraintsSatisfied(job, false);

        job.setChargingConstraintSatisfied(false);
        job.setContentTriggerConstraintSatisfied(false);
        job.setDeadlineConstraintSatisfied(false);
        assertFalse(job.wouldBeReadyWithConstraint(CONSTRAINT_CHARGING));
        assertFalse(job.wouldBeReadyWithConstraint(CONSTRAINT_CONTENT_TRIGGER));
        assertFalse(job.wouldBeReadyWithConstraint(CONSTRAINT_DEADLINE));
        job.setChargingConstraintSatisfied(true);
        job.setContentTriggerConstraintSatisfied(true);
        job.setDeadlineConstraintSatisfied(true);
        // Still false because implicit constraints aren't satisfied.
        assertFalse(job.wouldBeReadyWithConstraint(CONSTRAINT_CHARGING));
        assertFalse(job.wouldBeReadyWithConstraint(CONSTRAINT_CONTENT_TRIGGER));
        assertFalse(job.wouldBeReadyWithConstraint(CONSTRAINT_DEADLINE));

        markImplicitConstraintsSatisfied(job, true);

        job.setChargingConstraintSatisfied(false);
        job.setContentTriggerConstraintSatisfied(false);
        job.setDeadlineConstraintSatisfied(false);
        assertFalse(job.wouldBeReadyWithConstraint(CONSTRAINT_CHARGING));
        assertFalse(job.wouldBeReadyWithConstraint(CONSTRAINT_CONTENT_TRIGGER));
        // Once implicit constraint are satisfied, deadline constraint should always return true.
        assertTrue(job.wouldBeReadyWithConstraint(CONSTRAINT_DEADLINE));

        // Turn on constraints one at a time.
        job.setChargingConstraintSatisfied(true);
        job.setContentTriggerConstraintSatisfied(false);
        job.setDeadlineConstraintSatisfied(false);
        assertFalse(job.wouldBeReadyWithConstraint(CONSTRAINT_CHARGING));
        // Deadline should force isReady to be true, but isn't needed for the job to be
        // considered ready.
        assertTrue(job.wouldBeReadyWithConstraint(CONSTRAINT_CONTENT_TRIGGER));
        // Once implicit constraint are satisfied, deadline constraint should always return true.
        assertTrue(job.wouldBeReadyWithConstraint(CONSTRAINT_DEADLINE));

        job.setChargingConstraintSatisfied(false);
        job.setContentTriggerConstraintSatisfied(true);
        job.setDeadlineConstraintSatisfied(false);
        assertTrue(job.wouldBeReadyWithConstraint(CONSTRAINT_CHARGING));
        assertFalse(job.wouldBeReadyWithConstraint(CONSTRAINT_CONTENT_TRIGGER));
        // Once implicit constraint are satisfied, deadline constraint should always return true.
        assertTrue(job.wouldBeReadyWithConstraint(CONSTRAINT_DEADLINE));

        job.setChargingConstraintSatisfied(false);
        job.setContentTriggerConstraintSatisfied(false);
        job.setDeadlineConstraintSatisfied(true);
        // Since the deadline constraint is satisfied, none of the other explicit constraints are
        // needed.
        assertTrue(job.wouldBeReadyWithConstraint(CONSTRAINT_CHARGING));
        assertTrue(job.wouldBeReadyWithConstraint(CONSTRAINT_CONTENT_TRIGGER));
        // Once implicit constraint are satisfied, deadline constraint should always return true.
        assertTrue(job.wouldBeReadyWithConstraint(CONSTRAINT_DEADLINE));

        // With two of the 3 constraints satisfied (and implicit constraints also satisfied), only
        // the unsatisfied constraint should return true.
        job.setChargingConstraintSatisfied(true);
        job.setContentTriggerConstraintSatisfied(true);
        job.setDeadlineConstraintSatisfied(false);
        assertTrue(job.wouldBeReadyWithConstraint(CONSTRAINT_CHARGING));
        assertTrue(job.wouldBeReadyWithConstraint(CONSTRAINT_CONTENT_TRIGGER));
        // Once implicit constraint are satisfied, deadline constraint should always return true.
        assertTrue(job.wouldBeReadyWithConstraint(CONSTRAINT_DEADLINE));

        job.setChargingConstraintSatisfied(true);
        job.setContentTriggerConstraintSatisfied(false);
        job.setDeadlineConstraintSatisfied(true);
        assertTrue(job.wouldBeReadyWithConstraint(CONSTRAINT_CHARGING));
        assertTrue(job.wouldBeReadyWithConstraint(CONSTRAINT_CONTENT_TRIGGER));
        // Once implicit constraint are satisfied, deadline constraint should always return true.
        assertTrue(job.wouldBeReadyWithConstraint(CONSTRAINT_DEADLINE));

        job.setChargingConstraintSatisfied(false);
        job.setContentTriggerConstraintSatisfied(true);
        job.setDeadlineConstraintSatisfied(true);
        assertTrue(job.wouldBeReadyWithConstraint(CONSTRAINT_CHARGING));
        assertTrue(job.wouldBeReadyWithConstraint(CONSTRAINT_CONTENT_TRIGGER));
        // Once implicit constraint are satisfied, deadline constraint should always return true.
        assertTrue(job.wouldBeReadyWithConstraint(CONSTRAINT_DEADLINE));

        job.setChargingConstraintSatisfied(true);
        job.setContentTriggerConstraintSatisfied(true);
        job.setDeadlineConstraintSatisfied(true);
        assertTrue(job.wouldBeReadyWithConstraint(CONSTRAINT_CHARGING));
        assertTrue(job.wouldBeReadyWithConstraint(CONSTRAINT_CONTENT_TRIGGER));
        // Once implicit constraint are satisfied, deadline constraint should always return true.
        assertTrue(job.wouldBeReadyWithConstraint(CONSTRAINT_DEADLINE));
    }

    @Test
    public void testWouldBeReadyWithConstraint_ImplicitDeviceNotDozing() {
        // Job with no explicit constraints.
        final JobStatus job = createJobStatus(
                new JobInfo.Builder(101, new ComponentName("foo", "bar")).build());

        markImplicitConstraintsSatisfied(job, false);
        job.setDeviceNotDozingConstraintSatisfied(false, false);
        assertFalse(job.wouldBeReadyWithConstraint(CONSTRAINT_DEVICE_NOT_DOZING));
        job.setDeviceNotDozingConstraintSatisfied(true, false);
        assertFalse(job.wouldBeReadyWithConstraint(CONSTRAINT_DEVICE_NOT_DOZING));

        markImplicitConstraintsSatisfied(job, true);
        job.setDeviceNotDozingConstraintSatisfied(false, false);
        assertTrue(job.wouldBeReadyWithConstraint(CONSTRAINT_DEVICE_NOT_DOZING));
        job.setDeviceNotDozingConstraintSatisfied(true, false);
        assertTrue(job.wouldBeReadyWithConstraint(CONSTRAINT_DEVICE_NOT_DOZING));
    }

    @Test
    public void testWouldBeReadyWithConstraint_ImplicitQuota() {
        // Job with no explicit constraints.
        final JobStatus job = createJobStatus(
                new JobInfo.Builder(101, new ComponentName("foo", "bar")).build());

        markImplicitConstraintsSatisfied(job, false);
        job.setQuotaConstraintSatisfied(false);
        assertFalse(job.wouldBeReadyWithConstraint(CONSTRAINT_WITHIN_QUOTA));
        job.setQuotaConstraintSatisfied(true);
        assertFalse(job.wouldBeReadyWithConstraint(CONSTRAINT_WITHIN_QUOTA));

        markImplicitConstraintsSatisfied(job, true);
        job.setQuotaConstraintSatisfied(false);
        assertTrue(job.wouldBeReadyWithConstraint(CONSTRAINT_WITHIN_QUOTA));
        job.setQuotaConstraintSatisfied(true);
        assertTrue(job.wouldBeReadyWithConstraint(CONSTRAINT_WITHIN_QUOTA));
    }

    @Test
    public void testWouldBeReadyWithConstraint_ImplicitBackgroundNotRestricted() {
        // Job with no explicit constraints.
        final JobStatus job = createJobStatus(
                new JobInfo.Builder(101, new ComponentName("foo", "bar")).build());

        markImplicitConstraintsSatisfied(job, false);
        job.setBackgroundNotRestrictedConstraintSatisfied(false);
        assertFalse(job.wouldBeReadyWithConstraint(CONSTRAINT_BACKGROUND_NOT_RESTRICTED));
        job.setBackgroundNotRestrictedConstraintSatisfied(true);
        assertFalse(job.wouldBeReadyWithConstraint(CONSTRAINT_BACKGROUND_NOT_RESTRICTED));

        markImplicitConstraintsSatisfied(job, true);
        job.setBackgroundNotRestrictedConstraintSatisfied(false);
        assertTrue(job.wouldBeReadyWithConstraint(CONSTRAINT_BACKGROUND_NOT_RESTRICTED));
        job.setBackgroundNotRestrictedConstraintSatisfied(true);
        assertTrue(job.wouldBeReadyWithConstraint(CONSTRAINT_BACKGROUND_NOT_RESTRICTED));
    }

    private void markImplicitConstraintsSatisfied(JobStatus job, boolean isSatisfied) {
        job.setQuotaConstraintSatisfied(isSatisfied);
        job.setDeviceNotDozingConstraintSatisfied(isSatisfied, false);
        job.setBackgroundNotRestrictedConstraintSatisfied(isSatisfied);
    }

    private static JobStatus createJobStatus(long earliestRunTimeElapsedMillis,
            long latestRunTimeElapsedMillis) {
        final JobInfo job = new JobInfo.Builder(101, new ComponentName("foo", "bar"))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).build();
        return new JobStatus(job, 0, null, -1, 0, 0, null, earliestRunTimeElapsedMillis,
                latestRunTimeElapsedMillis, 0, 0, null, 0);
    }

    private static JobStatus createJobStatus(JobInfo job) {
        return JobStatus.createFromJobInfo(job, 0, null, -1, "JobStatusTest");
    }
}
