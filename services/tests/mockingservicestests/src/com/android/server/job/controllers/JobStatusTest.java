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
import static com.android.server.job.JobSchedulerService.ACTIVE_INDEX;
import static com.android.server.job.JobSchedulerService.FREQUENT_INDEX;
import static com.android.server.job.JobSchedulerService.NEVER_INDEX;
import static com.android.server.job.JobSchedulerService.RARE_INDEX;
import static com.android.server.job.JobSchedulerService.RESTRICTED_INDEX;
import static com.android.server.job.JobSchedulerService.WORKING_INDEX;
import static com.android.server.job.JobSchedulerService.sElapsedRealtimeClock;
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
import static org.mockito.Mockito.when;

import android.app.job.JobInfo;
import android.app.usage.UsageStatsManagerInternal;
import android.content.ComponentName;
import android.content.pm.PackageManagerInternal;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.util.SparseIntArray;

import androidx.test.runner.AndroidJUnit4;

import com.android.server.LocalServices;
import com.android.server.job.JobSchedulerInternal;
import com.android.server.job.JobSchedulerService;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoSession;
import org.mockito.quality.Strictness;

import java.time.Clock;
import java.time.ZoneOffset;

@RunWith(AndroidJUnit4.class)
public class JobStatusTest {
    private static final double DELTA = 0.00001;
    private static final String TEST_PACKAGE = "job.test.package";
    private static final ComponentName TEST_JOB_COMPONENT = new ComponentName(TEST_PACKAGE, "test");

    private static final Uri IMAGES_MEDIA_URI = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
    private static final Uri VIDEO_MEDIA_URI = MediaStore.Video.Media.EXTERNAL_CONTENT_URI;

    @Mock
    private JobSchedulerInternal mJobSchedulerInternal;
    private MockitoSession mMockingSession;

    @Before
    public void setUp() throws Exception {
        mMockingSession = mockitoSession()
                .initMocks(this)
                .strictness(Strictness.LENIENT)
                .mockStatic(LocalServices.class)
                .startMocking();
        doReturn(mJobSchedulerInternal)
                .when(() -> LocalServices.getService(JobSchedulerInternal.class));
        doReturn(mock(PackageManagerInternal.class))
                .when(() -> LocalServices.getService(PackageManagerInternal.class));
        doReturn(mock(UsageStatsManagerInternal.class))
                .when(() -> LocalServices.getService(UsageStatsManagerInternal.class));

        // Freeze the clocks at this moment in time
        JobSchedulerService.sSystemClock =
                Clock.fixed(Clock.systemUTC().instant(), ZoneOffset.UTC);
        JobSchedulerService.sUptimeMillisClock =
                Clock.fixed(SystemClock.uptimeClock().instant(), ZoneOffset.UTC);
        sElapsedRealtimeClock =
                Clock.fixed(SystemClock.elapsedRealtimeClock().instant(), ZoneOffset.UTC);
    }

    @After
    public void tearDown() {
        if (mMockingSession != null) {
            mMockingSession.finishMocking();
        }
    }

    private static void assertEffectiveBucketForMediaExemption(JobStatus jobStatus,
            boolean exemptionGranted) {
        final SparseIntArray effectiveBucket = new SparseIntArray();
        effectiveBucket.put(ACTIVE_INDEX, ACTIVE_INDEX);
        effectiveBucket.put(WORKING_INDEX, WORKING_INDEX);
        effectiveBucket.put(FREQUENT_INDEX, exemptionGranted ? WORKING_INDEX : FREQUENT_INDEX);
        effectiveBucket.put(RARE_INDEX, exemptionGranted ? WORKING_INDEX : RARE_INDEX);
        effectiveBucket.put(NEVER_INDEX, NEVER_INDEX);
        effectiveBucket.put(RESTRICTED_INDEX, RESTRICTED_INDEX);
        for (int i = 0; i < effectiveBucket.size(); i++) {
            jobStatus.setStandbyBucket(effectiveBucket.keyAt(i));
            assertEquals(effectiveBucket.valueAt(i), jobStatus.getEffectiveStandbyBucket());
        }
    }

    @Test
    public void testMediaBackupExemption_lateConstraint() {
        final JobInfo triggerContentJob = new JobInfo.Builder(42, TEST_JOB_COMPONENT)
                .addTriggerContentUri(new JobInfo.TriggerContentUri(IMAGES_MEDIA_URI, 0))
                .setOverrideDeadline(12)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .build();
        when(mJobSchedulerInternal.getMediaBackupPackage()).thenReturn(TEST_PACKAGE);
        assertEffectiveBucketForMediaExemption(createJobStatus(triggerContentJob), false);
    }

    @Test
    public void testMediaBackupExemption_noConnectivityConstraint() {
        final JobInfo triggerContentJob = new JobInfo.Builder(42, TEST_JOB_COMPONENT)
                .addTriggerContentUri(new JobInfo.TriggerContentUri(IMAGES_MEDIA_URI, 0))
                .build();
        when(mJobSchedulerInternal.getMediaBackupPackage()).thenReturn(TEST_PACKAGE);
        assertEffectiveBucketForMediaExemption(createJobStatus(triggerContentJob), false);
    }

    @Test
    public void testMediaBackupExemption_noContentTriggerConstraint() {
        final JobInfo networkJob = new JobInfo.Builder(42, TEST_JOB_COMPONENT)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .build();
        when(mJobSchedulerInternal.getMediaBackupPackage()).thenReturn(TEST_PACKAGE);
        assertEffectiveBucketForMediaExemption(createJobStatus(networkJob), false);
    }

    @Test
    public void testMediaBackupExemption_wrongSourcePackage() {
        final JobInfo networkContentJob = new JobInfo.Builder(42, TEST_JOB_COMPONENT)
                .addTriggerContentUri(new JobInfo.TriggerContentUri(IMAGES_MEDIA_URI, 0))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .build();
        when(mJobSchedulerInternal.getMediaBackupPackage()).thenReturn("not.test.package");
        assertEffectiveBucketForMediaExemption(createJobStatus(networkContentJob), false);
    }

    @Test
    public void testMediaBackupExemption_nonEligibleUri() {
        final Uri nonEligibleUri = MediaStore.AUTHORITY_URI.buildUpon()
                .appendPath("any_path").build();
        final JobInfo networkContentJob = new JobInfo.Builder(42, TEST_JOB_COMPONENT)
                .addTriggerContentUri(new JobInfo.TriggerContentUri(IMAGES_MEDIA_URI, 0))
                .addTriggerContentUri(new JobInfo.TriggerContentUri(nonEligibleUri, 0))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .build();
        when(mJobSchedulerInternal.getMediaBackupPackage()).thenReturn(TEST_PACKAGE);
        assertEffectiveBucketForMediaExemption(createJobStatus(networkContentJob), false);
    }

    @Test
    public void testMediaBackupExemptionGranted() {
        when(mJobSchedulerInternal.getMediaBackupPackage()).thenReturn(TEST_PACKAGE);
        final JobInfo imageUriJob = new JobInfo.Builder(42, TEST_JOB_COMPONENT)
                .addTriggerContentUri(new JobInfo.TriggerContentUri(IMAGES_MEDIA_URI, 0))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .build();
        assertEffectiveBucketForMediaExemption(createJobStatus(imageUriJob), true);

        final JobInfo videoUriJob = new JobInfo.Builder(42, TEST_JOB_COMPONENT)
                .addTriggerContentUri(new JobInfo.TriggerContentUri(VIDEO_MEDIA_URI, 0))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .build();
        assertEffectiveBucketForMediaExemption(createJobStatus(videoUriJob), true);

        final JobInfo bothUriJob = new JobInfo.Builder(42, TEST_JOB_COMPONENT)
                .addTriggerContentUri(new JobInfo.TriggerContentUri(IMAGES_MEDIA_URI, 0))
                .addTriggerContentUri(new JobInfo.TriggerContentUri(VIDEO_MEDIA_URI, 0))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .build();
        assertEffectiveBucketForMediaExemption(createJobStatus(bothUriJob), true);
    }

    @Test
    public void testFraction() throws Exception {
        final long now = sElapsedRealtimeClock.millis();

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
        job.setChargingConstraintSatisfied(sElapsedRealtimeClock.millis(), false);
        assertTrue(job.wouldBeReadyWithConstraint(CONSTRAINT_CHARGING));
        job.setChargingConstraintSatisfied(sElapsedRealtimeClock.millis(), true);
        assertTrue(job.wouldBeReadyWithConstraint(CONSTRAINT_CHARGING));

        markImplicitConstraintsSatisfied(job, false);
        job.setChargingConstraintSatisfied(sElapsedRealtimeClock.millis(), false);
        assertFalse(job.wouldBeReadyWithConstraint(CONSTRAINT_CHARGING));
        job.setChargingConstraintSatisfied(sElapsedRealtimeClock.millis(), true);
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
        job.setIdleConstraintSatisfied(sElapsedRealtimeClock.millis(), false);
        assertTrue(job.wouldBeReadyWithConstraint(CONSTRAINT_IDLE));
        job.setIdleConstraintSatisfied(sElapsedRealtimeClock.millis(), true);
        assertTrue(job.wouldBeReadyWithConstraint(CONSTRAINT_IDLE));

        markImplicitConstraintsSatisfied(job, false);
        job.setIdleConstraintSatisfied(sElapsedRealtimeClock.millis(), false);
        assertFalse(job.wouldBeReadyWithConstraint(CONSTRAINT_IDLE));
        job.setIdleConstraintSatisfied(sElapsedRealtimeClock.millis(), true);
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
        job.setBatteryNotLowConstraintSatisfied(sElapsedRealtimeClock.millis(), false);
        assertTrue(job.wouldBeReadyWithConstraint(CONSTRAINT_BATTERY_NOT_LOW));
        job.setBatteryNotLowConstraintSatisfied(sElapsedRealtimeClock.millis(), true);
        assertTrue(job.wouldBeReadyWithConstraint(CONSTRAINT_BATTERY_NOT_LOW));

        markImplicitConstraintsSatisfied(job, false);
        job.setBatteryNotLowConstraintSatisfied(sElapsedRealtimeClock.millis(), false);
        assertFalse(job.wouldBeReadyWithConstraint(CONSTRAINT_BATTERY_NOT_LOW));
        job.setBatteryNotLowConstraintSatisfied(sElapsedRealtimeClock.millis(), true);
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
        job.setStorageNotLowConstraintSatisfied(sElapsedRealtimeClock.millis(), false);
        assertTrue(job.wouldBeReadyWithConstraint(CONSTRAINT_STORAGE_NOT_LOW));
        job.setStorageNotLowConstraintSatisfied(sElapsedRealtimeClock.millis(), true);
        assertTrue(job.wouldBeReadyWithConstraint(CONSTRAINT_STORAGE_NOT_LOW));

        markImplicitConstraintsSatisfied(job, false);
        job.setStorageNotLowConstraintSatisfied(sElapsedRealtimeClock.millis(), false);
        assertFalse(job.wouldBeReadyWithConstraint(CONSTRAINT_STORAGE_NOT_LOW));
        job.setStorageNotLowConstraintSatisfied(sElapsedRealtimeClock.millis(), true);
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
        job.setTimingDelayConstraintSatisfied(sElapsedRealtimeClock.millis(), false);
        assertTrue(job.wouldBeReadyWithConstraint(CONSTRAINT_TIMING_DELAY));
        job.setTimingDelayConstraintSatisfied(sElapsedRealtimeClock.millis(), true);
        assertTrue(job.wouldBeReadyWithConstraint(CONSTRAINT_TIMING_DELAY));

        markImplicitConstraintsSatisfied(job, false);
        job.setTimingDelayConstraintSatisfied(sElapsedRealtimeClock.millis(), false);
        assertFalse(job.wouldBeReadyWithConstraint(CONSTRAINT_TIMING_DELAY));
        job.setTimingDelayConstraintSatisfied(sElapsedRealtimeClock.millis(), true);
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
        job.setDeadlineConstraintSatisfied(sElapsedRealtimeClock.millis(), false);
        assertTrue(job.wouldBeReadyWithConstraint(CONSTRAINT_DEADLINE));
        job.setDeadlineConstraintSatisfied(sElapsedRealtimeClock.millis(), true);
        assertTrue(job.wouldBeReadyWithConstraint(CONSTRAINT_DEADLINE));

        markImplicitConstraintsSatisfied(job, false);
        job.setDeadlineConstraintSatisfied(sElapsedRealtimeClock.millis(), false);
        assertFalse(job.wouldBeReadyWithConstraint(CONSTRAINT_DEADLINE));
        job.setDeadlineConstraintSatisfied(sElapsedRealtimeClock.millis(), true);
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
        job.setConnectivityConstraintSatisfied(sElapsedRealtimeClock.millis(), false);
        assertTrue(job.wouldBeReadyWithConstraint(CONSTRAINT_CONNECTIVITY));
        job.setConnectivityConstraintSatisfied(sElapsedRealtimeClock.millis(), true);
        assertTrue(job.wouldBeReadyWithConstraint(CONSTRAINT_CONNECTIVITY));

        markImplicitConstraintsSatisfied(job, false);
        job.setConnectivityConstraintSatisfied(sElapsedRealtimeClock.millis(), false);
        assertFalse(job.wouldBeReadyWithConstraint(CONSTRAINT_CONNECTIVITY));
        job.setConnectivityConstraintSatisfied(sElapsedRealtimeClock.millis(), true);
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
        job.setContentTriggerConstraintSatisfied(sElapsedRealtimeClock.millis(), false);
        assertTrue(job.wouldBeReadyWithConstraint(CONSTRAINT_CONTENT_TRIGGER));
        job.setContentTriggerConstraintSatisfied(sElapsedRealtimeClock.millis(), true);
        assertTrue(job.wouldBeReadyWithConstraint(CONSTRAINT_CONTENT_TRIGGER));

        markImplicitConstraintsSatisfied(job, false);
        job.setContentTriggerConstraintSatisfied(sElapsedRealtimeClock.millis(), false);
        assertFalse(job.wouldBeReadyWithConstraint(CONSTRAINT_CONTENT_TRIGGER));
        job.setContentTriggerConstraintSatisfied(sElapsedRealtimeClock.millis(), true);
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

        job.setChargingConstraintSatisfied(sElapsedRealtimeClock.millis(), false);
        job.setConnectivityConstraintSatisfied(sElapsedRealtimeClock.millis(), false);
        job.setContentTriggerConstraintSatisfied(sElapsedRealtimeClock.millis(), false);
        assertFalse(job.wouldBeReadyWithConstraint(CONSTRAINT_CHARGING));
        assertFalse(job.wouldBeReadyWithConstraint(CONSTRAINT_CONNECTIVITY));
        assertFalse(job.wouldBeReadyWithConstraint(CONSTRAINT_CONTENT_TRIGGER));
        job.setChargingConstraintSatisfied(sElapsedRealtimeClock.millis(), true);
        job.setConnectivityConstraintSatisfied(sElapsedRealtimeClock.millis(), true);
        job.setContentTriggerConstraintSatisfied(sElapsedRealtimeClock.millis(), true);
        // Still false because implicit constraints aren't satisfied.
        assertFalse(job.wouldBeReadyWithConstraint(CONSTRAINT_CHARGING));
        assertFalse(job.wouldBeReadyWithConstraint(CONSTRAINT_CONNECTIVITY));
        assertFalse(job.wouldBeReadyWithConstraint(CONSTRAINT_CONTENT_TRIGGER));

        markImplicitConstraintsSatisfied(job, true);

        job.setChargingConstraintSatisfied(sElapsedRealtimeClock.millis(), false);
        job.setConnectivityConstraintSatisfied(sElapsedRealtimeClock.millis(), false);
        job.setContentTriggerConstraintSatisfied(sElapsedRealtimeClock.millis(), false);
        assertFalse(job.wouldBeReadyWithConstraint(CONSTRAINT_CHARGING));
        assertFalse(job.wouldBeReadyWithConstraint(CONSTRAINT_CONNECTIVITY));
        assertFalse(job.wouldBeReadyWithConstraint(CONSTRAINT_CONTENT_TRIGGER));

        // Turn on constraints one at a time.
        job.setChargingConstraintSatisfied(sElapsedRealtimeClock.millis(), true);
        job.setConnectivityConstraintSatisfied(sElapsedRealtimeClock.millis(), false);
        job.setContentTriggerConstraintSatisfied(sElapsedRealtimeClock.millis(), false);
        assertFalse(job.wouldBeReadyWithConstraint(CONSTRAINT_CHARGING));
        assertFalse(job.wouldBeReadyWithConstraint(CONSTRAINT_CONNECTIVITY));
        assertFalse(job.wouldBeReadyWithConstraint(CONSTRAINT_CONTENT_TRIGGER));

        job.setChargingConstraintSatisfied(sElapsedRealtimeClock.millis(), false);
        job.setConnectivityConstraintSatisfied(sElapsedRealtimeClock.millis(), false);
        job.setContentTriggerConstraintSatisfied(sElapsedRealtimeClock.millis(), true);
        assertFalse(job.wouldBeReadyWithConstraint(CONSTRAINT_CHARGING));
        assertFalse(job.wouldBeReadyWithConstraint(CONSTRAINT_CONNECTIVITY));
        assertFalse(job.wouldBeReadyWithConstraint(CONSTRAINT_CONTENT_TRIGGER));

        job.setChargingConstraintSatisfied(sElapsedRealtimeClock.millis(), false);
        job.setConnectivityConstraintSatisfied(sElapsedRealtimeClock.millis(), true);
        job.setContentTriggerConstraintSatisfied(sElapsedRealtimeClock.millis(), false);
        assertFalse(job.wouldBeReadyWithConstraint(CONSTRAINT_CHARGING));
        assertFalse(job.wouldBeReadyWithConstraint(CONSTRAINT_CONNECTIVITY));
        assertFalse(job.wouldBeReadyWithConstraint(CONSTRAINT_CONTENT_TRIGGER));

        // With two of the 3 constraints satisfied (and implicit constraints also satisfied), only
        // the unsatisfied constraint should return true.
        job.setChargingConstraintSatisfied(sElapsedRealtimeClock.millis(), true);
        job.setConnectivityConstraintSatisfied(sElapsedRealtimeClock.millis(), false);
        job.setContentTriggerConstraintSatisfied(sElapsedRealtimeClock.millis(), true);
        assertFalse(job.wouldBeReadyWithConstraint(CONSTRAINT_CHARGING));
        assertTrue(job.wouldBeReadyWithConstraint(CONSTRAINT_CONNECTIVITY));
        assertFalse(job.wouldBeReadyWithConstraint(CONSTRAINT_CONTENT_TRIGGER));

        job.setChargingConstraintSatisfied(sElapsedRealtimeClock.millis(), true);
        job.setConnectivityConstraintSatisfied(sElapsedRealtimeClock.millis(), true);
        job.setContentTriggerConstraintSatisfied(sElapsedRealtimeClock.millis(), false);
        assertFalse(job.wouldBeReadyWithConstraint(CONSTRAINT_CHARGING));
        assertFalse(job.wouldBeReadyWithConstraint(CONSTRAINT_CONNECTIVITY));
        assertTrue(job.wouldBeReadyWithConstraint(CONSTRAINT_CONTENT_TRIGGER));

        job.setChargingConstraintSatisfied(sElapsedRealtimeClock.millis(), false);
        job.setConnectivityConstraintSatisfied(sElapsedRealtimeClock.millis(), true);
        job.setContentTriggerConstraintSatisfied(sElapsedRealtimeClock.millis(), true);
        assertTrue(job.wouldBeReadyWithConstraint(CONSTRAINT_CHARGING));
        assertFalse(job.wouldBeReadyWithConstraint(CONSTRAINT_CONNECTIVITY));
        assertFalse(job.wouldBeReadyWithConstraint(CONSTRAINT_CONTENT_TRIGGER));

        job.setChargingConstraintSatisfied(sElapsedRealtimeClock.millis(), true);
        job.setConnectivityConstraintSatisfied(sElapsedRealtimeClock.millis(), true);
        job.setContentTriggerConstraintSatisfied(sElapsedRealtimeClock.millis(), true);
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

        job.setChargingConstraintSatisfied(sElapsedRealtimeClock.millis(), false);
        job.setContentTriggerConstraintSatisfied(sElapsedRealtimeClock.millis(), false);
        job.setDeadlineConstraintSatisfied(sElapsedRealtimeClock.millis(), false);
        assertFalse(job.wouldBeReadyWithConstraint(CONSTRAINT_CHARGING));
        assertFalse(job.wouldBeReadyWithConstraint(CONSTRAINT_CONTENT_TRIGGER));
        assertFalse(job.wouldBeReadyWithConstraint(CONSTRAINT_DEADLINE));
        job.setChargingConstraintSatisfied(sElapsedRealtimeClock.millis(), true);
        job.setContentTriggerConstraintSatisfied(sElapsedRealtimeClock.millis(), true);
        job.setDeadlineConstraintSatisfied(sElapsedRealtimeClock.millis(), true);
        // Still false because implicit constraints aren't satisfied.
        assertFalse(job.wouldBeReadyWithConstraint(CONSTRAINT_CHARGING));
        assertFalse(job.wouldBeReadyWithConstraint(CONSTRAINT_CONTENT_TRIGGER));
        assertFalse(job.wouldBeReadyWithConstraint(CONSTRAINT_DEADLINE));

        markImplicitConstraintsSatisfied(job, true);

        job.setChargingConstraintSatisfied(sElapsedRealtimeClock.millis(), false);
        job.setContentTriggerConstraintSatisfied(sElapsedRealtimeClock.millis(), false);
        job.setDeadlineConstraintSatisfied(sElapsedRealtimeClock.millis(), false);
        assertFalse(job.wouldBeReadyWithConstraint(CONSTRAINT_CHARGING));
        assertFalse(job.wouldBeReadyWithConstraint(CONSTRAINT_CONTENT_TRIGGER));
        // Once implicit constraint are satisfied, deadline constraint should always return true.
        assertTrue(job.wouldBeReadyWithConstraint(CONSTRAINT_DEADLINE));

        // Turn on constraints one at a time.
        job.setChargingConstraintSatisfied(sElapsedRealtimeClock.millis(), true);
        job.setContentTriggerConstraintSatisfied(sElapsedRealtimeClock.millis(), false);
        job.setDeadlineConstraintSatisfied(sElapsedRealtimeClock.millis(), false);
        assertFalse(job.wouldBeReadyWithConstraint(CONSTRAINT_CHARGING));
        // Deadline should force isReady to be true, but isn't needed for the job to be
        // considered ready.
        assertTrue(job.wouldBeReadyWithConstraint(CONSTRAINT_CONTENT_TRIGGER));
        // Once implicit constraint are satisfied, deadline constraint should always return true.
        assertTrue(job.wouldBeReadyWithConstraint(CONSTRAINT_DEADLINE));

        job.setChargingConstraintSatisfied(sElapsedRealtimeClock.millis(), false);
        job.setContentTriggerConstraintSatisfied(sElapsedRealtimeClock.millis(), true);
        job.setDeadlineConstraintSatisfied(sElapsedRealtimeClock.millis(), false);
        assertTrue(job.wouldBeReadyWithConstraint(CONSTRAINT_CHARGING));
        assertFalse(job.wouldBeReadyWithConstraint(CONSTRAINT_CONTENT_TRIGGER));
        // Once implicit constraint are satisfied, deadline constraint should always return true.
        assertTrue(job.wouldBeReadyWithConstraint(CONSTRAINT_DEADLINE));

        job.setChargingConstraintSatisfied(sElapsedRealtimeClock.millis(), false);
        job.setContentTriggerConstraintSatisfied(sElapsedRealtimeClock.millis(), false);
        job.setDeadlineConstraintSatisfied(sElapsedRealtimeClock.millis(), true);
        // Since the deadline constraint is satisfied, none of the other explicit constraints are
        // needed.
        assertTrue(job.wouldBeReadyWithConstraint(CONSTRAINT_CHARGING));
        assertTrue(job.wouldBeReadyWithConstraint(CONSTRAINT_CONTENT_TRIGGER));
        // Once implicit constraint are satisfied, deadline constraint should always return true.
        assertTrue(job.wouldBeReadyWithConstraint(CONSTRAINT_DEADLINE));

        // With two of the 3 constraints satisfied (and implicit constraints also satisfied), only
        // the unsatisfied constraint should return true.
        job.setChargingConstraintSatisfied(sElapsedRealtimeClock.millis(), true);
        job.setContentTriggerConstraintSatisfied(sElapsedRealtimeClock.millis(), true);
        job.setDeadlineConstraintSatisfied(sElapsedRealtimeClock.millis(), false);
        assertTrue(job.wouldBeReadyWithConstraint(CONSTRAINT_CHARGING));
        assertTrue(job.wouldBeReadyWithConstraint(CONSTRAINT_CONTENT_TRIGGER));
        // Once implicit constraint are satisfied, deadline constraint should always return true.
        assertTrue(job.wouldBeReadyWithConstraint(CONSTRAINT_DEADLINE));

        job.setChargingConstraintSatisfied(sElapsedRealtimeClock.millis(), true);
        job.setContentTriggerConstraintSatisfied(sElapsedRealtimeClock.millis(), false);
        job.setDeadlineConstraintSatisfied(sElapsedRealtimeClock.millis(), true);
        assertTrue(job.wouldBeReadyWithConstraint(CONSTRAINT_CHARGING));
        assertTrue(job.wouldBeReadyWithConstraint(CONSTRAINT_CONTENT_TRIGGER));
        // Once implicit constraint are satisfied, deadline constraint should always return true.
        assertTrue(job.wouldBeReadyWithConstraint(CONSTRAINT_DEADLINE));

        job.setChargingConstraintSatisfied(sElapsedRealtimeClock.millis(), false);
        job.setContentTriggerConstraintSatisfied(sElapsedRealtimeClock.millis(), true);
        job.setDeadlineConstraintSatisfied(sElapsedRealtimeClock.millis(), true);
        assertTrue(job.wouldBeReadyWithConstraint(CONSTRAINT_CHARGING));
        assertTrue(job.wouldBeReadyWithConstraint(CONSTRAINT_CONTENT_TRIGGER));
        // Once implicit constraint are satisfied, deadline constraint should always return true.
        assertTrue(job.wouldBeReadyWithConstraint(CONSTRAINT_DEADLINE));

        job.setChargingConstraintSatisfied(sElapsedRealtimeClock.millis(), true);
        job.setContentTriggerConstraintSatisfied(sElapsedRealtimeClock.millis(), true);
        job.setDeadlineConstraintSatisfied(sElapsedRealtimeClock.millis(), true);
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
        job.setDeviceNotDozingConstraintSatisfied(sElapsedRealtimeClock.millis(), false, false);
        assertFalse(job.wouldBeReadyWithConstraint(CONSTRAINT_DEVICE_NOT_DOZING));
        job.setDeviceNotDozingConstraintSatisfied(sElapsedRealtimeClock.millis(), true, false);
        assertFalse(job.wouldBeReadyWithConstraint(CONSTRAINT_DEVICE_NOT_DOZING));

        markImplicitConstraintsSatisfied(job, true);
        job.setDeviceNotDozingConstraintSatisfied(sElapsedRealtimeClock.millis(), false, false);
        assertTrue(job.wouldBeReadyWithConstraint(CONSTRAINT_DEVICE_NOT_DOZING));
        job.setDeviceNotDozingConstraintSatisfied(sElapsedRealtimeClock.millis(), true, false);
        assertTrue(job.wouldBeReadyWithConstraint(CONSTRAINT_DEVICE_NOT_DOZING));
    }

    @Test
    public void testWouldBeReadyWithConstraint_ImplicitQuota() {
        // Job with no explicit constraints.
        final JobStatus job = createJobStatus(
                new JobInfo.Builder(101, new ComponentName("foo", "bar")).build());

        markImplicitConstraintsSatisfied(job, false);
        job.setQuotaConstraintSatisfied(sElapsedRealtimeClock.millis(), false);
        assertFalse(job.wouldBeReadyWithConstraint(CONSTRAINT_WITHIN_QUOTA));
        job.setQuotaConstraintSatisfied(sElapsedRealtimeClock.millis(), true);
        assertFalse(job.wouldBeReadyWithConstraint(CONSTRAINT_WITHIN_QUOTA));

        markImplicitConstraintsSatisfied(job, true);
        job.setQuotaConstraintSatisfied(sElapsedRealtimeClock.millis(), false);
        assertTrue(job.wouldBeReadyWithConstraint(CONSTRAINT_WITHIN_QUOTA));
        job.setQuotaConstraintSatisfied(sElapsedRealtimeClock.millis(), true);
        assertTrue(job.wouldBeReadyWithConstraint(CONSTRAINT_WITHIN_QUOTA));
    }

    @Test
    public void testWouldBeReadyWithConstraint_ImplicitBackgroundNotRestricted() {
        // Job with no explicit constraints.
        final JobStatus job = createJobStatus(
                new JobInfo.Builder(101, new ComponentName("foo", "bar")).build());

        markImplicitConstraintsSatisfied(job, false);
        job.setBackgroundNotRestrictedConstraintSatisfied(
                sElapsedRealtimeClock.millis(), false, false);
        assertFalse(job.wouldBeReadyWithConstraint(CONSTRAINT_BACKGROUND_NOT_RESTRICTED));
        job.setBackgroundNotRestrictedConstraintSatisfied(
                sElapsedRealtimeClock.millis(), true, false);
        assertFalse(job.wouldBeReadyWithConstraint(CONSTRAINT_BACKGROUND_NOT_RESTRICTED));

        markImplicitConstraintsSatisfied(job, true);
        job.setBackgroundNotRestrictedConstraintSatisfied(
                sElapsedRealtimeClock.millis(), false, false);
        assertTrue(job.wouldBeReadyWithConstraint(CONSTRAINT_BACKGROUND_NOT_RESTRICTED));
        job.setBackgroundNotRestrictedConstraintSatisfied(
                sElapsedRealtimeClock.millis(), true, false);
        assertTrue(job.wouldBeReadyWithConstraint(CONSTRAINT_BACKGROUND_NOT_RESTRICTED));
    }

    private void markImplicitConstraintsSatisfied(JobStatus job, boolean isSatisfied) {
        job.setQuotaConstraintSatisfied(sElapsedRealtimeClock.millis(), isSatisfied);
        job.setTareWealthConstraintSatisfied(sElapsedRealtimeClock.millis(), isSatisfied);
        job.setDeviceNotDozingConstraintSatisfied(
                sElapsedRealtimeClock.millis(), isSatisfied, false);
        job.setBackgroundNotRestrictedConstraintSatisfied(
                sElapsedRealtimeClock.millis(), isSatisfied, false);
    }

    private static JobStatus createJobStatus(long earliestRunTimeElapsedMillis,
            long latestRunTimeElapsedMillis) {
        final JobInfo job = new JobInfo.Builder(101, new ComponentName("foo", "bar"))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).build();
        return new JobStatus(job, 0, null, -1, 0, null, earliestRunTimeElapsedMillis,
                latestRunTimeElapsedMillis, 0, 0, null, 0, 0);
    }

    private static JobStatus createJobStatus(JobInfo job) {
        JobStatus jobStatus = JobStatus.createFromJobInfo(job, 0, null, -1, "JobStatusTest");
        jobStatus.serviceInfo = mock(ServiceInfo.class);
        return jobStatus;
    }
}
