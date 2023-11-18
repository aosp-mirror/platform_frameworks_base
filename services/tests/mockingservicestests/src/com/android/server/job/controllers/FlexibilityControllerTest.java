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

import static android.app.job.JobInfo.BIAS_FOREGROUND_SERVICE;
import static android.app.job.JobInfo.BIAS_TOP_APP;
import static android.app.job.JobInfo.NETWORK_TYPE_ANY;
import static android.app.job.JobInfo.NETWORK_TYPE_CELLULAR;
import static android.app.job.JobInfo.NETWORK_TYPE_NONE;
import static android.net.NetworkCapabilities.TRANSPORT_WIFI;
import static android.text.format.DateUtils.HOUR_IN_MILLIS;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.doAnswer;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.mockitoSession;
import static com.android.server.job.controllers.FlexibilityController.FcConfig.DEFAULT_FALLBACK_FLEXIBILITY_DEADLINE_MS;
import static com.android.server.job.controllers.FlexibilityController.FcConfig.DEFAULT_UNSEEN_CONSTRAINT_GRACE_PERIOD_MS;
import static com.android.server.job.controllers.FlexibilityController.FcConfig.KEY_DEADLINE_PROXIMITY_LIMIT;
import static com.android.server.job.controllers.FlexibilityController.FcConfig.KEY_FALLBACK_FLEXIBILITY_DEADLINE;
import static com.android.server.job.controllers.FlexibilityController.FcConfig.KEY_FLEXIBILITY_ENABLED;
import static com.android.server.job.controllers.FlexibilityController.FcConfig.KEY_PERCENTS_TO_DROP_NUM_FLEXIBLE_CONSTRAINTS;
import static com.android.server.job.controllers.FlexibilityController.NUM_FLEXIBLE_CONSTRAINTS;
import static com.android.server.job.controllers.FlexibilityController.SYSTEM_WIDE_FLEXIBLE_CONSTRAINTS;
import static com.android.server.job.controllers.JobStatus.CONSTRAINT_BATTERY_NOT_LOW;
import static com.android.server.job.controllers.JobStatus.CONSTRAINT_CHARGING;
import static com.android.server.job.controllers.JobStatus.CONSTRAINT_CONNECTIVITY;
import static com.android.server.job.controllers.JobStatus.CONSTRAINT_FLEXIBLE;
import static com.android.server.job.controllers.JobStatus.CONSTRAINT_IDLE;
import static com.android.server.job.controllers.JobStatus.MIN_WINDOW_FOR_FLEXIBILITY_MS;
import static com.android.server.job.controllers.JobStatus.NO_LATEST_RUNTIME;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.app.AlarmManager;
import android.app.AppGlobals;
import android.app.job.JobInfo;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.pm.PackageManagerInternal;
import android.net.NetworkRequest;
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
    private static final long FROZEN_TIME = 100L;

    private MockitoSession mMockingSession;
    private FlexibilityController mFlexibilityController;
    private DeviceConfig.Properties.Builder mDeviceConfigPropertiesBuilder;
    private JobStore mJobStore;
    private FlexibilityController.FcConfig mFcConfig;
    private int mSourceUid;

    @Mock
    private AlarmManager mAlarmManager;
    @Mock
    private Context mContext;
    @Mock
    private JobSchedulerService mJobSchedulerService;
    @Mock
    private PrefetchController mPrefetchController;
    @Mock
    private PackageManager mPackageManager;

    @Before
    public void setup() throws Exception {
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
        when(mContext.getPackageManager()).thenReturn(mPackageManager);
        when(mPackageManager.hasSystemFeature(
                PackageManager.FEATURE_AUTOMOTIVE)).thenReturn(false);
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
                Clock.fixed(Instant.ofEpochMilli(FROZEN_TIME), ZoneOffset.UTC);
        JobSchedulerService.sElapsedRealtimeClock =
                Clock.fixed(Instant.ofEpochMilli(FROZEN_TIME), ZoneOffset.UTC);
        // Initialize real objects.
        mFlexibilityController = new FlexibilityController(mJobSchedulerService,
                mPrefetchController);
        mFcConfig = mFlexibilityController.getFcConfig();

        mSourceUid = AppGlobals.getPackageManager().getPackageUid(SOURCE_PACKAGE, 0, 0);

        setDeviceConfigString(KEY_PERCENTS_TO_DROP_NUM_FLEXIBLE_CONSTRAINTS, "50,60,70,80");
        setDeviceConfigLong(KEY_DEADLINE_PROXIMITY_LIMIT, 0L);
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
            mFcConfig.processConstantLocked(mDeviceConfigPropertiesBuilder.build(), key);
            mFlexibilityController.onConstantsUpdatedLocked();
        }
    }

    private void setDeviceConfigLong(String key, Long val) {
        mDeviceConfigPropertiesBuilder.setLong(key, val);
        synchronized (mFlexibilityController.mLock) {
            mFlexibilityController.prepareForUpdatedConstantsLocked();
            mFcConfig.processConstantLocked(mDeviceConfigPropertiesBuilder.build(), key);
            mFlexibilityController.onConstantsUpdatedLocked();
        }
    }

    private void setDeviceConfigString(String key, String val) {
        mDeviceConfigPropertiesBuilder.setString(key, val);
        synchronized (mFlexibilityController.mLock) {
            mFlexibilityController.prepareForUpdatedConstantsLocked();
            mFcConfig.processConstantLocked(mDeviceConfigPropertiesBuilder.build(), key);
            mFlexibilityController.onConstantsUpdatedLocked();
        }
    }

    private static JobInfo.Builder createJob(int id) {
        return new JobInfo.Builder(id, new ComponentName("foo", "bar"));
    }

    private JobStatus createJobStatus(String testTag, JobInfo.Builder job) {
        JobInfo jobInfo = job.build();
        JobStatus js = JobStatus.createFromJobInfo(
                jobInfo, 1000, SOURCE_PACKAGE, SOURCE_USER_ID, "FCTest", testTag);
        js.enqueueTime = FROZEN_TIME;
        return js;
    }

    /**
     * Tests that the there are equally many percents to drop constraints as there are constraints
     */
    @Test
    public void testDefaultVariableValues() {
        assertEquals(NUM_FLEXIBLE_CONSTRAINTS,
                mFlexibilityController.mFcConfig.DEFAULT_PERCENT_TO_DROP_FLEXIBLE_CONSTRAINTS.length
        );
    }

    @Test
    public void testOnConstantsUpdated_DefaultFlexibility() {
        JobStatus js = createJobStatus("testDefaultFlexibilityConfig", createJob(0));
        assertFalse(mFlexibilityController.isFlexibilitySatisfiedLocked(js));
        setDeviceConfigBoolean(KEY_FLEXIBILITY_ENABLED, false);
        assertTrue(mFlexibilityController.isFlexibilitySatisfiedLocked(js));
        setDeviceConfigBoolean(KEY_FLEXIBILITY_ENABLED, true);
        assertFalse(mFlexibilityController.isFlexibilitySatisfiedLocked(js));
    }

    @Test
    public void testOnConstantsUpdated_DeadlineProximity() {
        JobStatus js = createJobStatus("testDeadlineProximityConfig", createJob(0));
        setDeviceConfigLong(KEY_DEADLINE_PROXIMITY_LIMIT, Long.MAX_VALUE);
        mFlexibilityController.mFlexibilityAlarmQueue
                .scheduleDropNumConstraintsAlarm(js, FROZEN_TIME);
        assertEquals(0, js.getNumRequiredFlexibleConstraints());
    }

    @Test
    public void testOnConstantsUpdated_FallbackDeadline() {
        JobStatus js = createJobStatus("testFallbackDeadlineConfig", createJob(0));
        assertEquals(DEFAULT_FALLBACK_FLEXIBILITY_DEADLINE_MS,
                mFlexibilityController.getLifeCycleEndElapsedLocked(js, 0L));
        setDeviceConfigLong(KEY_FALLBACK_FLEXIBILITY_DEADLINE, 100L);
        assertEquals(100L, mFlexibilityController.getLifeCycleEndElapsedLocked(js, 0L));
    }

    @Test
    public void testOnConstantsUpdated_PercentsToDropConstraints() {
        JobInfo.Builder jb = createJob(0)
                .setOverrideDeadline(MIN_WINDOW_FOR_FLEXIBILITY_MS);
        JobStatus js = createJobStatus("testPercentsToDropConstraintsConfig", jb);
        assertEquals(FROZEN_TIME + MIN_WINDOW_FOR_FLEXIBILITY_MS / 10 * 5,
                mFlexibilityController.getNextConstraintDropTimeElapsedLocked(js));
        setDeviceConfigString(KEY_PERCENTS_TO_DROP_NUM_FLEXIBLE_CONSTRAINTS, "10,20,30,40");
        assertArrayEquals(
                mFlexibilityController.mFcConfig.PERCENTS_TO_DROP_NUM_FLEXIBLE_CONSTRAINTS,
                new int[] {10, 20, 30, 40});
        assertEquals(FROZEN_TIME + MIN_WINDOW_FOR_FLEXIBILITY_MS / 10,
                mFlexibilityController.getNextConstraintDropTimeElapsedLocked(js));
        js.adjustNumRequiredFlexibleConstraints(-1);
        assertEquals(FROZEN_TIME + MIN_WINDOW_FOR_FLEXIBILITY_MS / 10 * 2,
                mFlexibilityController.getNextConstraintDropTimeElapsedLocked(js));
        js.adjustNumRequiredFlexibleConstraints(-1);
        assertEquals(FROZEN_TIME + MIN_WINDOW_FOR_FLEXIBILITY_MS / 10 * 3,
                mFlexibilityController.getNextConstraintDropTimeElapsedLocked(js));
    }

    @Test
    public void testOnConstantsUpdated_PercentsToDropConstraintsInvalidValues() {
        JobInfo.Builder jb = createJob(0).setOverrideDeadline(100L);
        JobStatus js = createJobStatus("testPercentsToDropConstraintsConfig", jb);
        js.enqueueTime = 100L;
        assertEquals(150L,
                mFlexibilityController.getNextConstraintDropTimeElapsedLocked(js));
        setDeviceConfigString(KEY_PERCENTS_TO_DROP_NUM_FLEXIBLE_CONSTRAINTS, "10,20a,030,40");
        assertEquals(150L,
                mFlexibilityController.getNextConstraintDropTimeElapsedLocked(js));
        setDeviceConfigString(KEY_PERCENTS_TO_DROP_NUM_FLEXIBLE_CONSTRAINTS, "10,40");
        assertEquals(150L,
                mFlexibilityController.getNextConstraintDropTimeElapsedLocked(js));
        setDeviceConfigString(KEY_PERCENTS_TO_DROP_NUM_FLEXIBLE_CONSTRAINTS, "50,40,10,40");
        assertEquals(150L,
                mFlexibilityController.getNextConstraintDropTimeElapsedLocked(js));
    }

    @Test
    public void testGetNextConstraintDropTimeElapsedLocked() {
        long nextTimeToDropNumConstraints;

        // no delay, deadline
        JobInfo.Builder jb = createJob(0).setOverrideDeadline(MIN_WINDOW_FOR_FLEXIBILITY_MS);
        JobStatus js = createJobStatus("time", jb);

        assertEquals(JobStatus.NO_EARLIEST_RUNTIME, js.getEarliestRunTime());
        assertEquals(MIN_WINDOW_FOR_FLEXIBILITY_MS + FROZEN_TIME, js.getLatestRunTimeElapsed());
        assertEquals(FROZEN_TIME, js.enqueueTime);

        nextTimeToDropNumConstraints = mFlexibilityController
                .getNextConstraintDropTimeElapsedLocked(js);
        assertEquals(FROZEN_TIME + MIN_WINDOW_FOR_FLEXIBILITY_MS / 10 * 5,
                nextTimeToDropNumConstraints);
        js.adjustNumRequiredFlexibleConstraints(-1);
        nextTimeToDropNumConstraints = mFlexibilityController
                .getNextConstraintDropTimeElapsedLocked(js);
        assertEquals(FROZEN_TIME + MIN_WINDOW_FOR_FLEXIBILITY_MS / 10 * 6,
                nextTimeToDropNumConstraints);
        js.adjustNumRequiredFlexibleConstraints(-1);
        nextTimeToDropNumConstraints = mFlexibilityController
                .getNextConstraintDropTimeElapsedLocked(js);
        assertEquals(FROZEN_TIME + MIN_WINDOW_FOR_FLEXIBILITY_MS / 10 * 7,
                nextTimeToDropNumConstraints);

        // delay, no deadline
        jb = createJob(0).setMinimumLatency(800000L);
        js = createJobStatus("time", jb);

        nextTimeToDropNumConstraints = mFlexibilityController
                .getNextConstraintDropTimeElapsedLocked(js);
        assertEquals(130400100, nextTimeToDropNumConstraints);
        js.adjustNumRequiredFlexibleConstraints(-1);
        nextTimeToDropNumConstraints = mFlexibilityController
                .getNextConstraintDropTimeElapsedLocked(js);
        assertEquals(156320100L, nextTimeToDropNumConstraints);
        js.adjustNumRequiredFlexibleConstraints(-1);
        nextTimeToDropNumConstraints = mFlexibilityController
                .getNextConstraintDropTimeElapsedLocked(js);
        assertEquals(182240100L, nextTimeToDropNumConstraints);

        // no delay, no deadline
        jb = createJob(0);
        js = createJobStatus("time", jb);

        nextTimeToDropNumConstraints = mFlexibilityController
                .getNextConstraintDropTimeElapsedLocked(js);
        assertEquals(129600100, nextTimeToDropNumConstraints);
        js.adjustNumRequiredFlexibleConstraints(-1);
        nextTimeToDropNumConstraints = mFlexibilityController
                .getNextConstraintDropTimeElapsedLocked(js);
        assertEquals(155520100L, nextTimeToDropNumConstraints);
        js.adjustNumRequiredFlexibleConstraints(-1);
        nextTimeToDropNumConstraints = mFlexibilityController
                .getNextConstraintDropTimeElapsedLocked(js);
        assertEquals(181440100L, nextTimeToDropNumConstraints);

        // delay, deadline
        jb = createJob(0)
                .setOverrideDeadline(2 * MIN_WINDOW_FOR_FLEXIBILITY_MS)
                .setMinimumLatency(MIN_WINDOW_FOR_FLEXIBILITY_MS);
        js = createJobStatus("time", jb);

        final long windowStart = FROZEN_TIME + MIN_WINDOW_FOR_FLEXIBILITY_MS;
        nextTimeToDropNumConstraints = mFlexibilityController
                .getNextConstraintDropTimeElapsedLocked(js);
        assertEquals(windowStart + MIN_WINDOW_FOR_FLEXIBILITY_MS / 10 * 5,
                nextTimeToDropNumConstraints);
        js.adjustNumRequiredFlexibleConstraints(-1);
        nextTimeToDropNumConstraints = mFlexibilityController
                .getNextConstraintDropTimeElapsedLocked(js);
        assertEquals(windowStart + MIN_WINDOW_FOR_FLEXIBILITY_MS / 10 * 6,
                nextTimeToDropNumConstraints);
        js.adjustNumRequiredFlexibleConstraints(-1);
        nextTimeToDropNumConstraints = mFlexibilityController
                .getNextConstraintDropTimeElapsedLocked(js);
        assertEquals(windowStart + MIN_WINDOW_FOR_FLEXIBILITY_MS / 10 * 7,
                nextTimeToDropNumConstraints);
    }

    @Test
    public void testCurPercent() {
        long deadline = 1000;
        long nowElapsed;
        JobInfo.Builder jb = createJob(0).setOverrideDeadline(deadline);
        JobStatus js = createJobStatus("time", jb);

        assertEquals(FROZEN_TIME, mFlexibilityController.getLifeCycleBeginningElapsedLocked(js));
        assertEquals(deadline + FROZEN_TIME,
                mFlexibilityController.getLifeCycleEndElapsedLocked(js, FROZEN_TIME));
        nowElapsed = 600 + FROZEN_TIME;
        JobSchedulerService.sElapsedRealtimeClock =
                Clock.fixed(Instant.ofEpochMilli(nowElapsed), ZoneOffset.UTC);
        assertEquals(60, mFlexibilityController.getCurPercentOfLifecycleLocked(js, nowElapsed));

        nowElapsed = 1400;
        JobSchedulerService.sElapsedRealtimeClock =
                Clock.fixed(Instant.ofEpochMilli(nowElapsed), ZoneOffset.UTC);
        assertEquals(100, mFlexibilityController.getCurPercentOfLifecycleLocked(js, nowElapsed));

        nowElapsed = 950 + FROZEN_TIME;
        JobSchedulerService.sElapsedRealtimeClock =
                Clock.fixed(Instant.ofEpochMilli(nowElapsed), ZoneOffset.UTC);
        assertEquals(95, mFlexibilityController.getCurPercentOfLifecycleLocked(js, nowElapsed));

        nowElapsed = FROZEN_TIME;
        JobSchedulerService.sElapsedRealtimeClock =
                Clock.fixed(Instant.ofEpochMilli(nowElapsed), ZoneOffset.UTC);
        long delay = 100;
        deadline = 1100;
        jb = createJob(0).setOverrideDeadline(deadline).setMinimumLatency(delay);
        js = createJobStatus("time", jb);

        assertEquals(FROZEN_TIME + delay,
                mFlexibilityController.getLifeCycleBeginningElapsedLocked(js));
        assertEquals(deadline + FROZEN_TIME,
                mFlexibilityController.getLifeCycleEndElapsedLocked(js, FROZEN_TIME + delay));

        nowElapsed = 600 + FROZEN_TIME + delay;
        JobSchedulerService.sElapsedRealtimeClock =
                Clock.fixed(Instant.ofEpochMilli(nowElapsed), ZoneOffset.UTC);

        assertEquals(60, mFlexibilityController.getCurPercentOfLifecycleLocked(js, nowElapsed));

        nowElapsed = 1400;
        JobSchedulerService.sElapsedRealtimeClock =
                Clock.fixed(Instant.ofEpochMilli(nowElapsed), ZoneOffset.UTC);
        assertEquals(100, mFlexibilityController.getCurPercentOfLifecycleLocked(js, nowElapsed));

        nowElapsed = 950 + FROZEN_TIME + delay;
        JobSchedulerService.sElapsedRealtimeClock =
                Clock.fixed(Instant.ofEpochMilli(nowElapsed), ZoneOffset.UTC);
        assertEquals(95, mFlexibilityController.getCurPercentOfLifecycleLocked(js, nowElapsed));
    }

    @Test
    public void testGetLifeCycleBeginningElapsedLocked_Prefetch() {
        // prefetch with lifecycle
        when(mPrefetchController.getLaunchTimeThresholdMs()).thenReturn(700L);
        JobInfo.Builder jb = createJob(0).setPrefetch(true);
        JobStatus js = createJobStatus("time", jb);
        when(mPrefetchController.getNextEstimatedLaunchTimeLocked(js)).thenReturn(900L);
        assertEquals(900L - 700L, mFlexibilityController.getLifeCycleBeginningElapsedLocked(js));
        // prefetch with enqueue
        jb = createJob(0).setPrefetch(true);
        js = createJobStatus("time", jb);
        assertEquals(FROZEN_TIME, mFlexibilityController.getLifeCycleBeginningElapsedLocked(js));
        // prefetch with delay
        jb = createJob(0).setPrefetch(true).setMinimumLatency(200);
        js = createJobStatus("time", jb);
        assertEquals(200 + FROZEN_TIME, js.getEarliestRunTime());
        assertEquals(js.getEarliestRunTime(),
                mFlexibilityController.getLifeCycleBeginningElapsedLocked(js));
        // prefetch without estimate
        mFlexibilityController.mPrefetchLifeCycleStart
                .add(js.getUserId(), js.getSourcePackageName(), 500L);
        when(mPrefetchController.getNextEstimatedLaunchTimeLocked(js)).thenReturn(Long.MAX_VALUE);
        jb = createJob(0).setPrefetch(true);
        js = createJobStatus("time", jb);
        assertEquals(500L, mFlexibilityController.getLifeCycleBeginningElapsedLocked(js));
    }

    @Test
    public void testGetLifeCycleBeginningElapsedLocked_NonPrefetch() {
        // delay
        long delay = 100;
        JobInfo.Builder jb = createJob(0).setMinimumLatency(delay);
        JobStatus js = createJobStatus("time", jb);
        assertEquals(delay + FROZEN_TIME,
                mFlexibilityController.getLifeCycleBeginningElapsedLocked(js));
        // no delay
        jb = createJob(0);
        js = createJobStatus("time", jb);
        assertEquals(FROZEN_TIME,
                mFlexibilityController.getLifeCycleBeginningElapsedLocked(js));
    }

    @Test
    public void testGetLifeCycleEndElapsedLocked_Prefetch() {
        // prefetch no estimate
        JobInfo.Builder jb = createJob(0).setPrefetch(true);
        JobStatus js = createJobStatus("time", jb);
        when(mPrefetchController.getNextEstimatedLaunchTimeLocked(js)).thenReturn(Long.MAX_VALUE);
        assertEquals(Long.MAX_VALUE, mFlexibilityController.getLifeCycleEndElapsedLocked(js, 0));
        // prefetch with estimate
        jb = createJob(0).setPrefetch(true);
        js = createJobStatus("time", jb);
        when(mPrefetchController.getNextEstimatedLaunchTimeLocked(js)).thenReturn(1000L);
        assertEquals(1000L, mFlexibilityController.getLifeCycleEndElapsedLocked(js, 0));
    }

    @Test
    public void testGetLifeCycleEndElapsedLocked_NonPrefetch() {
        // deadline
        JobInfo.Builder jb = createJob(0).setOverrideDeadline(1000L);
        JobStatus js = createJobStatus("time", jb);
        assertEquals(1000L + FROZEN_TIME,
                mFlexibilityController.getLifeCycleEndElapsedLocked(js, 0));
        // no deadline
        jb = createJob(0);
        js = createJobStatus("time", jb);
        assertEquals(100L + DEFAULT_FALLBACK_FLEXIBILITY_DEADLINE_MS,
                mFlexibilityController.getLifeCycleEndElapsedLocked(js, 100L));
    }

    @Test
    public void testGetLifeCycleEndElapsedLocked_Rescheduled() {
        JobInfo.Builder jb = createJob(0).setOverrideDeadline(1000L);
        JobStatus js = createJobStatus("time", jb);
        js = new JobStatus(
                js, FROZEN_TIME, NO_LATEST_RUNTIME, /* numFailures */ 2, /* numSystemStops */ 0,
                0, FROZEN_TIME, FROZEN_TIME);

        assertEquals(mFcConfig.RESCHEDULED_JOB_DEADLINE_MS,
                mFlexibilityController.getLifeCycleEndElapsedLocked(js, 0));

        js = new JobStatus(
                js, FROZEN_TIME, NO_LATEST_RUNTIME, /* numFailures */ 2, /* numSystemStops */ 1,
                0, FROZEN_TIME, FROZEN_TIME);

        assertEquals(2 * mFcConfig.RESCHEDULED_JOB_DEADLINE_MS,
                mFlexibilityController.getLifeCycleEndElapsedLocked(js, 0));

        js = new JobStatus(
                js, FROZEN_TIME, NO_LATEST_RUNTIME, /* numFailures */ 0, /* numSystemStops */ 10,
                0, FROZEN_TIME, FROZEN_TIME);
        assertEquals(mFcConfig.MAX_RESCHEDULED_DEADLINE_MS,
                mFlexibilityController.getLifeCycleEndElapsedLocked(js, 0));
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
                        FlexibilityTracker(NUM_FLEXIBLE_CONSTRAINTS);
        // Plus one for jobs with 0 required constraint.
        assertEquals(NUM_FLEXIBLE_CONSTRAINTS + 1, flexTracker.size());
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
            assertEquals(1, trackedJobs.get(0).size());
            assertEquals(0, trackedJobs.get(1).size());
            assertEquals(0, trackedJobs.get(2).size());
            assertEquals(3, trackedJobs.get(3).size());
            assertEquals(0, trackedJobs.get(4).size());

            flexTracker.adjustJobsRequiredConstraints(jobs[0], -1, FROZEN_TIME);
            assertEquals(1, trackedJobs.get(0).size());
            assertEquals(0, trackedJobs.get(1).size());
            assertEquals(1, trackedJobs.get(2).size());
            assertEquals(2, trackedJobs.get(3).size());
            assertEquals(0, trackedJobs.get(4).size());

            flexTracker.adjustJobsRequiredConstraints(jobs[0], -1, FROZEN_TIME);
            assertEquals(1, trackedJobs.get(0).size());
            assertEquals(1, trackedJobs.get(1).size());
            assertEquals(0, trackedJobs.get(2).size());
            assertEquals(2, trackedJobs.get(3).size());
            assertEquals(0, trackedJobs.get(4).size());

            flexTracker.adjustJobsRequiredConstraints(jobs[0], -1, FROZEN_TIME);
            assertEquals(2, trackedJobs.get(0).size());
            assertEquals(0, trackedJobs.get(1).size());
            assertEquals(0, trackedJobs.get(2).size());
            assertEquals(2, trackedJobs.get(3).size());
            assertEquals(0, trackedJobs.get(4).size());

            flexTracker.remove(jobs[1]);
            assertEquals(2, trackedJobs.get(0).size());
            assertEquals(0, trackedJobs.get(1).size());
            assertEquals(0, trackedJobs.get(2).size());
            assertEquals(1, trackedJobs.get(3).size());
            assertEquals(0, trackedJobs.get(4).size());

            flexTracker.resetJobNumDroppedConstraints(jobs[0], FROZEN_TIME);
            assertEquals(1, trackedJobs.get(0).size());
            assertEquals(0, trackedJobs.get(1).size());
            assertEquals(0, trackedJobs.get(2).size());
            assertEquals(2, trackedJobs.get(3).size());
            assertEquals(0, trackedJobs.get(4).size());

            flexTracker.adjustJobsRequiredConstraints(jobs[0], -2, FROZEN_TIME);
            assertEquals(1, trackedJobs.get(0).size());
            assertEquals(1, trackedJobs.get(1).size());
            assertEquals(0, trackedJobs.get(2).size());
            assertEquals(1, trackedJobs.get(3).size());
            assertEquals(0, trackedJobs.get(4).size());

            final long nowElapsed = ((DEFAULT_FALLBACK_FLEXIBILITY_DEADLINE_MS / 2)
                    + HOUR_IN_MILLIS);
            JobSchedulerService.sElapsedRealtimeClock =
                    Clock.fixed(Instant.ofEpochMilli(nowElapsed), ZoneOffset.UTC);

            flexTracker.resetJobNumDroppedConstraints(jobs[0], nowElapsed);
            assertEquals(1, trackedJobs.get(0).size());
            assertEquals(0, trackedJobs.get(1).size());
            assertEquals(1, trackedJobs.get(2).size());
            assertEquals(1, trackedJobs.get(3).size());
            assertEquals(0, trackedJobs.get(4).size());
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
    public void testExceptions_UserInitiated() {
        JobInfo.Builder jb = createJob(0);
        jb.setUserInitiated(true).setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY);
        JobStatus js = createJobStatus("testExceptions_UserInitiated", jb);
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
    public void testExceptions_NoFlexibleConstraints() {
        JobInfo.Builder jb = createJob(0);
        jb.setRequiresDeviceIdle(true);
        jb.setRequiresCharging(true);
        jb.setRequiresBatteryNotLow(true);
        JobStatus js = createJobStatus("testExceptions_NoFlexibleConstraints", jb);
        assertFalse(js.hasFlexibilityConstraint());
    }

    @Test
    public void testExceptions_RescheduledOnce() {
        JobInfo.Builder jb = createJob(0);
        JobStatus js = createJobStatus("time", jb);
        js = new JobStatus(
                js, FROZEN_TIME, NO_LATEST_RUNTIME, /* numFailures */ 1, /* numSystemStops */ 0,
                0, FROZEN_TIME, FROZEN_TIME);
        assertFalse(js.hasFlexibilityConstraint());
        js = new JobStatus(
                js, FROZEN_TIME, NO_LATEST_RUNTIME, /* numFailures */ 0, /* numSystemStops */ 1,
                0, FROZEN_TIME, FROZEN_TIME);
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
        mJobStore.add(js);

        // Needed because if before and after Uid bias is the same, nothing happens.
        when(mJobSchedulerService.getUidBias(mSourceUid))
                .thenReturn(JobInfo.BIAS_FOREGROUND_SERVICE);

        synchronized (mFlexibilityController.mLock) {
            mFlexibilityController.maybeStartTrackingJobLocked(js, null);
            assertFalse(mFlexibilityController.isFlexibilitySatisfiedLocked(js));

            setUidBias(mSourceUid, JobInfo.BIAS_TOP_APP);

            assertTrue(mFlexibilityController.isFlexibilitySatisfiedLocked(js));
            assertTrue(js.isConstraintSatisfied(CONSTRAINT_FLEXIBLE));

            setUidBias(mSourceUid, JobInfo.BIAS_FOREGROUND_SERVICE);

            assertFalse(mFlexibilityController.isFlexibilitySatisfiedLocked(js));
            assertFalse(js.isConstraintSatisfied(CONSTRAINT_FLEXIBLE));
        }
    }

    @Test
    public void testTransportAffinity() {
        JobStatus jsAny = createJobStatus("testTransportAffinity",
                createJob(0).setRequiredNetworkType(NETWORK_TYPE_ANY));
        JobStatus jsCell = createJobStatus("testTransportAffinity",
                createJob(0).setRequiredNetworkType(NETWORK_TYPE_CELLULAR));
        JobStatus jsWifi = createJobStatus("testTransportAffinity",
                createJob(0).setRequiredNetwork(
                        new NetworkRequest.Builder()
                                .addTransportType(TRANSPORT_WIFI)
                                .build()));
        // Disable the unseen constraint logic.
        mFlexibilityController.setConstraintSatisfied(
                SYSTEM_WIDE_FLEXIBLE_CONSTRAINTS, true, FROZEN_TIME);
        mFlexibilityController.setConstraintSatisfied(
                SYSTEM_WIDE_FLEXIBLE_CONSTRAINTS, false, FROZEN_TIME);
        // Require only a single constraint
        jsAny.adjustNumRequiredFlexibleConstraints(-3);
        jsCell.adjustNumRequiredFlexibleConstraints(-2);
        jsWifi.adjustNumRequiredFlexibleConstraints(-2);
        synchronized (mFlexibilityController.mLock) {
            jsAny.setTransportAffinitiesSatisfied(false);
            jsCell.setTransportAffinitiesSatisfied(false);
            jsWifi.setTransportAffinitiesSatisfied(false);
            mFlexibilityController.setConstraintSatisfied(
                    CONSTRAINT_CONNECTIVITY, false, FROZEN_TIME);
            assertFalse(mFlexibilityController.hasEnoughSatisfiedConstraintsLocked(jsAny));
            assertFalse(mFlexibilityController.hasEnoughSatisfiedConstraintsLocked(jsCell));
            assertFalse(mFlexibilityController.hasEnoughSatisfiedConstraintsLocked(jsWifi));

            // A good network exists, but the network hasn't been assigned to any of the jobs
            jsAny.setTransportAffinitiesSatisfied(false);
            jsCell.setTransportAffinitiesSatisfied(false);
            jsWifi.setTransportAffinitiesSatisfied(false);
            mFlexibilityController.setConstraintSatisfied(
                    CONSTRAINT_CONNECTIVITY, true, FROZEN_TIME);
            assertFalse(mFlexibilityController.hasEnoughSatisfiedConstraintsLocked(jsAny));
            assertFalse(mFlexibilityController.hasEnoughSatisfiedConstraintsLocked(jsCell));
            assertFalse(mFlexibilityController.hasEnoughSatisfiedConstraintsLocked(jsWifi));

            // The good network has been assigned to the relevant jobs
            jsAny.setTransportAffinitiesSatisfied(true);
            jsCell.setTransportAffinitiesSatisfied(false);
            jsWifi.setTransportAffinitiesSatisfied(true);
            assertTrue(mFlexibilityController.hasEnoughSatisfiedConstraintsLocked(jsAny));
            assertFalse(mFlexibilityController.hasEnoughSatisfiedConstraintsLocked(jsCell));
            assertTrue(mFlexibilityController.hasEnoughSatisfiedConstraintsLocked(jsWifi));

            // One job loses access to the network.
            jsAny.setTransportAffinitiesSatisfied(true);
            jsCell.setTransportAffinitiesSatisfied(false);
            jsWifi.setTransportAffinitiesSatisfied(false);
            assertTrue(mFlexibilityController.hasEnoughSatisfiedConstraintsLocked(jsAny));
            assertFalse(mFlexibilityController.hasEnoughSatisfiedConstraintsLocked(jsCell));
            assertFalse(mFlexibilityController.hasEnoughSatisfiedConstraintsLocked(jsWifi));
        }
    }

    @Test
    public void testSetConstraintSatisfied_Constraints() {
        mFlexibilityController.setConstraintSatisfied(CONSTRAINT_IDLE, false, FROZEN_TIME);
        assertFalse(mFlexibilityController.isConstraintSatisfied(CONSTRAINT_IDLE));

        mFlexibilityController.setConstraintSatisfied(CONSTRAINT_IDLE, true, FROZEN_TIME);
        assertTrue(mFlexibilityController.isConstraintSatisfied(CONSTRAINT_IDLE));

        mFlexibilityController.setConstraintSatisfied(CONSTRAINT_IDLE, false, FROZEN_TIME);
        assertFalse(mFlexibilityController.isConstraintSatisfied(CONSTRAINT_IDLE));
    }

    @Test
    public void testSetConstraintSatisfied_Jobs() {
        JobInfo.Builder jb;
        int[] constraintCombinations = {
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
        for (int i = 0; i < constraintCombinations.length; i++) {
            jb = createJob(i);
            constraints = constraintCombinations[i];
            jb.setRequiresDeviceIdle((constraints & CONSTRAINT_IDLE) != 0);
            jb.setRequiresBatteryNotLow((constraints & CONSTRAINT_BATTERY_NOT_LOW) != 0);
            jb.setRequiresCharging((constraints & CONSTRAINT_CHARGING) != 0);
            synchronized (mFlexibilityController.mLock) {
                mFlexibilityController.maybeStartTrackingJobLocked(
                        createJobStatus(String.valueOf(i), jb), null);
            }
        }
        mFlexibilityController.setConstraintSatisfied(CONSTRAINT_CHARGING, false, FROZEN_TIME);
        mFlexibilityController.setConstraintSatisfied(CONSTRAINT_IDLE, false, FROZEN_TIME);
        mFlexibilityController.setConstraintSatisfied(
                CONSTRAINT_BATTERY_NOT_LOW, false, FROZEN_TIME);

        assertEquals(0, mFlexibilityController.mSatisfiedFlexibleConstraints);

        for (int i = 0; i < constraintCombinations.length; i++) {
            constraints = constraintCombinations[i];
            mFlexibilityController.setConstraintSatisfied(CONSTRAINT_CHARGING,
                    (constraints & CONSTRAINT_CHARGING) != 0, FROZEN_TIME);
            mFlexibilityController.setConstraintSatisfied(CONSTRAINT_IDLE,
                    (constraints & CONSTRAINT_IDLE) != 0, FROZEN_TIME);
            mFlexibilityController.setConstraintSatisfied(CONSTRAINT_BATTERY_NOT_LOW,
                    (constraints & CONSTRAINT_BATTERY_NOT_LOW) != 0, FROZEN_TIME);

            assertEquals(constraints, mFlexibilityController.mSatisfiedFlexibleConstraints);
            synchronized (mFlexibilityController.mLock) {
                assertSatisfiedJobsMatchSatisfiedConstraints(
                        mFlexibilityController.mFlexibilityTracker.getArrayList(), constraints);
            }
        }
    }

    @Test
    public void testHasEnoughSatisfiedConstraints_unseenConstraints_soonAfterBoot() {
        // Add connectivity to require 4 constraints
        JobStatus js = createJobStatus("testHasEnoughSatisfiedConstraints",
                createJob(0).setRequiredNetworkType(NETWORK_TYPE_ANY));

        // Too soon after boot
        JobSchedulerService.sElapsedRealtimeClock =
                Clock.fixed(Instant.ofEpochMilli(100 - 1), ZoneOffset.UTC);
        synchronized (mFlexibilityController.mLock) {
            assertFalse(mFlexibilityController.hasEnoughSatisfiedConstraintsLocked(js));
        }
        JobSchedulerService.sElapsedRealtimeClock =
                Clock.fixed(Instant.ofEpochMilli(DEFAULT_UNSEEN_CONSTRAINT_GRACE_PERIOD_MS - 1),
                        ZoneOffset.UTC);
        synchronized (mFlexibilityController.mLock) {
            assertFalse(mFlexibilityController.hasEnoughSatisfiedConstraintsLocked(js));
        }

        // Long after boot

        // No constraints ever seen. Don't bother waiting
        JobSchedulerService.sElapsedRealtimeClock =
                Clock.fixed(Instant.ofEpochMilli(DEFAULT_UNSEEN_CONSTRAINT_GRACE_PERIOD_MS),
                        ZoneOffset.UTC);
        synchronized (mFlexibilityController.mLock) {
            assertTrue(mFlexibilityController.hasEnoughSatisfiedConstraintsLocked(js));
        }
    }

    @Test
    public void testHasEnoughSatisfiedConstraints_unseenConstraints_longAfterBoot() {
        // Add connectivity to require 4 constraints
        JobStatus connJs = createJobStatus("testHasEnoughSatisfiedConstraints",
                createJob(0).setRequiredNetworkType(NETWORK_TYPE_ANY));
        JobStatus nonConnJs = createJobStatus("testHasEnoughSatisfiedConstraints",
                createJob(0).setRequiredNetworkType(NETWORK_TYPE_NONE));

        mFlexibilityController.setConstraintSatisfied(
                CONSTRAINT_BATTERY_NOT_LOW, true,
                2 * DEFAULT_UNSEEN_CONSTRAINT_GRACE_PERIOD_MS / 10);
        mFlexibilityController.setConstraintSatisfied(
                CONSTRAINT_CHARGING, true,
                3 * DEFAULT_UNSEEN_CONSTRAINT_GRACE_PERIOD_MS / 10);
        mFlexibilityController.setConstraintSatisfied(
                CONSTRAINT_IDLE, true,
                4 * DEFAULT_UNSEEN_CONSTRAINT_GRACE_PERIOD_MS / 10);
        mFlexibilityController.setConstraintSatisfied(
                CONSTRAINT_CONNECTIVITY, true,
                5 * DEFAULT_UNSEEN_CONSTRAINT_GRACE_PERIOD_MS / 10);

        // Long after boot
        // All constraints satisfied right now
        JobSchedulerService.sElapsedRealtimeClock =
                Clock.fixed(Instant.ofEpochMilli(DEFAULT_UNSEEN_CONSTRAINT_GRACE_PERIOD_MS),
                        ZoneOffset.UTC);
        synchronized (mFlexibilityController.mLock) {
            assertTrue(mFlexibilityController.hasEnoughSatisfiedConstraintsLocked(connJs));
            assertTrue(mFlexibilityController.hasEnoughSatisfiedConstraintsLocked(nonConnJs));
        }

        // Go down to 2 satisfied
        mFlexibilityController.setConstraintSatisfied(
                CONSTRAINT_CONNECTIVITY, false,
                6 * DEFAULT_UNSEEN_CONSTRAINT_GRACE_PERIOD_MS / 10);
        mFlexibilityController.setConstraintSatisfied(
                CONSTRAINT_IDLE, false,
                7 * DEFAULT_UNSEEN_CONSTRAINT_GRACE_PERIOD_MS / 10);
        // 3 & 4 constraints were seen recently enough, so the job should wait
        synchronized (mFlexibilityController.mLock) {
            assertFalse(mFlexibilityController.hasEnoughSatisfiedConstraintsLocked(connJs));
            assertFalse(mFlexibilityController.hasEnoughSatisfiedConstraintsLocked(nonConnJs));
        }

        // 4 constraints still in the grace period. Wait.
        JobSchedulerService.sElapsedRealtimeClock =
                Clock.fixed(
                        Instant.ofEpochMilli(16 * DEFAULT_UNSEEN_CONSTRAINT_GRACE_PERIOD_MS / 10),
                        ZoneOffset.UTC);
        synchronized (mFlexibilityController.mLock) {
            assertFalse(mFlexibilityController.hasEnoughSatisfiedConstraintsLocked(connJs));
            assertFalse(mFlexibilityController.hasEnoughSatisfiedConstraintsLocked(nonConnJs));
        }

        // 3 constraints still in the grace period. Wait.
        JobSchedulerService.sElapsedRealtimeClock =
                Clock.fixed(
                        Instant.ofEpochMilli(17 * DEFAULT_UNSEEN_CONSTRAINT_GRACE_PERIOD_MS / 10),
                        ZoneOffset.UTC);
        synchronized (mFlexibilityController.mLock) {
            assertFalse(mFlexibilityController.hasEnoughSatisfiedConstraintsLocked(connJs));
            assertFalse(mFlexibilityController.hasEnoughSatisfiedConstraintsLocked(nonConnJs));
        }

        // 3 constraints haven't been seen recently. Don't wait.
        JobSchedulerService.sElapsedRealtimeClock =
                Clock.fixed(
                        Instant.ofEpochMilli(
                                17 * DEFAULT_UNSEEN_CONSTRAINT_GRACE_PERIOD_MS / 10 + 1),
                        ZoneOffset.UTC);
        synchronized (mFlexibilityController.mLock) {
            assertTrue(mFlexibilityController.hasEnoughSatisfiedConstraintsLocked(connJs));
            assertTrue(mFlexibilityController.hasEnoughSatisfiedConstraintsLocked(nonConnJs));
        }

        // Add then remove connectivity. Resets expectation of 3 constraints for connectivity jobs.
        // Connectivity job should wait while the non-connectivity job can run.
        // of getting back to 4 constraints.
        mFlexibilityController.setConstraintSatisfied(
                CONSTRAINT_CONNECTIVITY, true,
                18 * DEFAULT_UNSEEN_CONSTRAINT_GRACE_PERIOD_MS / 10);
        mFlexibilityController.setConstraintSatisfied(
                CONSTRAINT_CONNECTIVITY, false,
                19 * DEFAULT_UNSEEN_CONSTRAINT_GRACE_PERIOD_MS / 10);
        JobSchedulerService.sElapsedRealtimeClock =
                Clock.fixed(
                        Instant.ofEpochMilli(
                                19 * DEFAULT_UNSEEN_CONSTRAINT_GRACE_PERIOD_MS / 10 + 1),
                        ZoneOffset.UTC);
        synchronized (mFlexibilityController.mLock) {
            assertFalse(mFlexibilityController.hasEnoughSatisfiedConstraintsLocked(connJs));
            assertTrue(mFlexibilityController.hasEnoughSatisfiedConstraintsLocked(nonConnJs));
        }
    }

    @Test
    public void testResetJobNumDroppedConstraints() {
        JobInfo.Builder jb = createJob(22);
        JobStatus js = createJobStatus("testResetJobNumDroppedConstraints", jb);
        long nowElapsed = FROZEN_TIME;

        mFlexibilityController.mFlexibilityTracker.add(js);

        assertEquals(3, js.getNumRequiredFlexibleConstraints());
        assertEquals(0, js.getNumDroppedFlexibleConstraints());
        assertEquals(1, mFlexibilityController
                .mFlexibilityTracker.getJobsByNumRequiredConstraints(3).size());

        nowElapsed += DEFAULT_FALLBACK_FLEXIBILITY_DEADLINE_MS / 10 * 5;
        JobSchedulerService.sElapsedRealtimeClock =
                Clock.fixed(Instant.ofEpochMilli(nowElapsed), ZoneOffset.UTC);

        mFlexibilityController.mFlexibilityTracker
                .adjustJobsRequiredConstraints(js, -1, nowElapsed);

        assertEquals(2, js.getNumRequiredFlexibleConstraints());
        assertEquals(1, js.getNumDroppedFlexibleConstraints());
        assertEquals(1, mFlexibilityController
                .mFlexibilityTracker.getJobsByNumRequiredConstraints(2).size());

        mFlexibilityController.mFlexibilityTracker.resetJobNumDroppedConstraints(js, nowElapsed);

        assertEquals(2, js.getNumRequiredFlexibleConstraints());
        assertEquals(1, js.getNumDroppedFlexibleConstraints());
        assertEquals(1, mFlexibilityController
                .mFlexibilityTracker.getJobsByNumRequiredConstraints(2).size());

        nowElapsed = FROZEN_TIME;
        JobSchedulerService.sElapsedRealtimeClock =
                Clock.fixed(Instant.ofEpochMilli(nowElapsed), ZoneOffset.UTC);

        mFlexibilityController.mFlexibilityTracker.resetJobNumDroppedConstraints(js, nowElapsed);

        assertEquals(3, js.getNumRequiredFlexibleConstraints());
        assertEquals(0, js.getNumDroppedFlexibleConstraints());
        assertEquals(1, mFlexibilityController
                .mFlexibilityTracker.getJobsByNumRequiredConstraints(3).size());

        nowElapsed += DEFAULT_FALLBACK_FLEXIBILITY_DEADLINE_MS / 10 * 9;
        JobSchedulerService.sElapsedRealtimeClock =
                Clock.fixed(Instant.ofEpochMilli(nowElapsed), ZoneOffset.UTC);

        mFlexibilityController.mFlexibilityTracker.resetJobNumDroppedConstraints(js, nowElapsed);

        assertEquals(0, js.getNumRequiredFlexibleConstraints());
        assertEquals(3, js.getNumDroppedFlexibleConstraints());

        nowElapsed = FROZEN_TIME + DEFAULT_FALLBACK_FLEXIBILITY_DEADLINE_MS / 10 * 6;
        JobSchedulerService.sElapsedRealtimeClock =
                Clock.fixed(Instant.ofEpochMilli(nowElapsed), ZoneOffset.UTC);

        mFlexibilityController.mFlexibilityTracker.resetJobNumDroppedConstraints(js, nowElapsed);

        assertEquals(1, js.getNumRequiredFlexibleConstraints());
        assertEquals(2, js.getNumDroppedFlexibleConstraints());
        assertEquals(1, mFlexibilityController
                .mFlexibilityTracker.getJobsByNumRequiredConstraints(1).size());
    }

    @Test
    public void testOnPrefetchCacheUpdated() {
        ArraySet<JobStatus> jobs = new ArraySet<JobStatus>();
        JobInfo.Builder jb = createJob(22).setPrefetch(true);
        JobStatus js = createJobStatus("onPrefetchCacheUpdated", jb);
        jobs.add(js);
        when(mPrefetchController.getLaunchTimeThresholdMs()).thenReturn(7 * HOUR_IN_MILLIS);
        when(mPrefetchController.getNextEstimatedLaunchTimeLocked(js)).thenReturn(
                1150L + mFlexibilityController.mConstants.PREFETCH_FORCE_BATCH_RELAX_THRESHOLD_MS);

        mFlexibilityController.maybeStartTrackingJobLocked(js, null);

        final long nowElapsed = 150L;
        JobSchedulerService.sElapsedRealtimeClock =
                Clock.fixed(Instant.ofEpochMilli(nowElapsed), ZoneOffset.UTC);

        mFlexibilityController.mPrefetchChangedListener.onPrefetchCacheUpdated(
                jobs, js.getUserId(), js.getSourcePackageName(), Long.MAX_VALUE,
                1150L + mFlexibilityController.mConstants.PREFETCH_FORCE_BATCH_RELAX_THRESHOLD_MS,
                nowElapsed);

        assertEquals(150L,
                (long) mFlexibilityController.mPrefetchLifeCycleStart
                        .get(js.getSourceUserId(), js.getSourcePackageName()));
        assertEquals(150L, mFlexibilityController.getLifeCycleBeginningElapsedLocked(js));
        assertEquals(1150L,
                mFlexibilityController.getLifeCycleEndElapsedLocked(js, 150L));
        assertEquals(0, mFlexibilityController.getCurPercentOfLifecycleLocked(js, FROZEN_TIME));
        assertEquals(650L, mFlexibilityController
                .getNextConstraintDropTimeElapsedLocked(js));
        assertEquals(3, js.getNumRequiredFlexibleConstraints());
        assertEquals(1, mFlexibilityController
                .mFlexibilityTracker.getJobsByNumRequiredConstraints(3).size());
    }

    /**
     * The beginning of a lifecycle for prefetch jobs includes the cached maximum of the last time
     * the estimated launch time was updated and the last time the app was opened.
     * When the UID bias updates it means the app might have been opened.
     * This tests that the cached value is updated properly.
     */
    @Test
    public void testUidUpdatesLifeCycle() {
        JobInfo.Builder jb = createJob(0).setPrefetch(true);
        JobStatus js = createJobStatus("uidTest", jb);
        mFlexibilityController.maybeStartTrackingJobLocked(js, null);
        mJobStore.add(js);

        final ArraySet<String> pkgs = new ArraySet<>();
        pkgs.add(js.getSourcePackageName());
        when(mJobSchedulerService.getPackagesForUidLocked(mSourceUid)).thenReturn(pkgs);

        setUidBias(mSourceUid, BIAS_TOP_APP);
        setUidBias(mSourceUid, BIAS_FOREGROUND_SERVICE);
        assertEquals(100L, (long) mFlexibilityController.mPrefetchLifeCycleStart
                .getOrDefault(js.getSourceUserId(), js.getSourcePackageName(), 0L));

        JobSchedulerService.sElapsedRealtimeClock =
                Clock.fixed(Instant.ofEpochMilli(50L), ZoneOffset.UTC);

        setUidBias(mSourceUid, BIAS_TOP_APP);
        setUidBias(mSourceUid, BIAS_FOREGROUND_SERVICE);
        assertEquals(100L, (long) mFlexibilityController
                .mPrefetchLifeCycleStart.get(js.getSourceUserId(), js.getSourcePackageName()));

    }

    @Test
    public void testDeviceDisabledFlexibility_Auto() {
        when(mPackageManager.hasSystemFeature(
                PackageManager.FEATURE_AUTOMOTIVE)).thenReturn(true);
        mFlexibilityController =
                new FlexibilityController(mJobSchedulerService, mPrefetchController);
        assertFalse(mFlexibilityController.mFlexibilityEnabled);

        JobStatus js = createJobStatus("testIsAuto", createJob(0));

        mFlexibilityController.maybeStartTrackingJobLocked(js, null);
        assertTrue(js.isConstraintSatisfied(CONSTRAINT_FLEXIBLE));

        setDeviceConfigBoolean(KEY_FLEXIBILITY_ENABLED, true);
        assertFalse(mFlexibilityController.mFlexibilityEnabled);

        ArrayList<ArraySet<JobStatus>> jobs =
                mFlexibilityController.mFlexibilityTracker.getArrayList();
        for (int i = 0; i < jobs.size(); i++) {
            assertEquals(0, jobs.get(i).size());
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
                final int transportAffinitySatisfied = js.canApplyTransportAffinities()
                        && js.areTransportAffinitiesSatisfied() ? 1 : 0;
                assertEquals(js.getNumRequiredFlexibleConstraints()
                                <= numSatisfiedConstraints + transportAffinitySatisfied,
                        js.isConstraintSatisfied(CONSTRAINT_FLEXIBLE));
            }
        }
    }
}
