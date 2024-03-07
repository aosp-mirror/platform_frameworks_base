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
import static android.text.format.DateUtils.MINUTE_IN_MILLIS;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.doAnswer;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.doNothing;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.mockitoSession;
import static com.android.server.job.JobSchedulerService.ACTIVE_INDEX;
import static com.android.server.job.JobSchedulerService.EXEMPTED_INDEX;
import static com.android.server.job.JobSchedulerService.sElapsedRealtimeClock;
import static com.android.server.job.controllers.FlexibilityController.FLEXIBLE_CONSTRAINTS;
import static com.android.server.job.controllers.FlexibilityController.FcConfig.DEFAULT_FALLBACK_FLEXIBILITY_DEADLINES;
import static com.android.server.job.controllers.FlexibilityController.FcConfig.DEFAULT_UNSEEN_CONSTRAINT_GRACE_PERIOD_MS;
import static com.android.server.job.controllers.FlexibilityController.FcConfig.KEY_APPLIED_CONSTRAINTS;
import static com.android.server.job.controllers.FlexibilityController.FcConfig.KEY_DEADLINE_PROXIMITY_LIMIT;
import static com.android.server.job.controllers.FlexibilityController.FcConfig.KEY_FALLBACK_FLEXIBILITY_DEADLINE;
import static com.android.server.job.controllers.FlexibilityController.FcConfig.KEY_FALLBACK_FLEXIBILITY_DEADLINES;
import static com.android.server.job.controllers.FlexibilityController.FcConfig.KEY_FALLBACK_FLEXIBILITY_DEADLINE_ADDITIONAL_SCORE_TIME_FACTORS;
import static com.android.server.job.controllers.FlexibilityController.FcConfig.KEY_FALLBACK_FLEXIBILITY_DEADLINE_SCORES;
import static com.android.server.job.controllers.FlexibilityController.FcConfig.KEY_PERCENTS_TO_DROP_FLEXIBLE_CONSTRAINTS;
import static com.android.server.job.controllers.FlexibilityController.SYSTEM_WIDE_FLEXIBLE_CONSTRAINTS;
import static com.android.server.job.controllers.JobStatus.CONSTRAINT_BATTERY_NOT_LOW;
import static com.android.server.job.controllers.JobStatus.CONSTRAINT_CHARGING;
import static com.android.server.job.controllers.JobStatus.CONSTRAINT_CONNECTIVITY;
import static com.android.server.job.controllers.JobStatus.CONSTRAINT_CONTENT_TRIGGER;
import static com.android.server.job.controllers.JobStatus.CONSTRAINT_FLEXIBLE;
import static com.android.server.job.controllers.JobStatus.CONSTRAINT_IDLE;
import static com.android.server.job.controllers.JobStatus.NO_LATEST_RUNTIME;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.annotation.Nullable;
import android.app.AlarmManager;
import android.app.AppGlobals;
import android.app.job.JobInfo;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.IPackageManager;
import android.content.pm.PackageManager;
import android.net.NetworkRequest;
import android.os.Looper;
import android.os.PowerManager;
import android.os.UserHandle;
import android.provider.DeviceConfig;
import android.telephony.TelephonyManager;
import android.telephony.UiccSlotMapping;
import android.util.ArraySet;
import android.util.EmptyArray;
import android.util.SparseArray;

import com.android.server.AppSchedulingModuleThread;
import com.android.server.DeviceIdleInternal;
import com.android.server.LocalServices;
import com.android.server.job.JobSchedulerService;
import com.android.server.job.JobStore;

import libcore.junit.util.compat.CoreCompatChangeRule;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.MockitoSession;
import org.mockito.quality.Strictness;
import org.mockito.stubbing.Answer;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executor;

public class FlexibilityControllerTest {
    private static final String SOURCE_PACKAGE = "com.android.frameworks.mockingservicestests";
    private static final int SOURCE_USER_ID = 0;
    private static final long FROZEN_TIME = 100L;

    private MockitoSession mMockingSession;
    private BroadcastReceiver mBroadcastReceiver;
    private final SparseArray<ArraySet<String>> mCarrierPrivilegedApps = new SparseArray<>();
    private final SparseArray<TelephonyManager.CarrierPrivilegesCallback>
            mCarrierPrivilegedCallbacks = new SparseArray<>();
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
    private DeviceIdleInternal mDeviceIdleInternal;
    @Mock
    private JobSchedulerService mJobSchedulerService;
    @Mock
    private PrefetchController mPrefetchController;
    @Mock
    private TelephonyManager mTelephonyManager;
    @Mock
    private IPackageManager mIPackageManager;
    @Mock
    private PackageManager mPackageManager;

    @Before
    public void setup() throws Exception {
        mMockingSession = mockitoSession()
                .initMocks(this)
                .strictness(Strictness.LENIENT)
                .spyStatic(DeviceConfig.class)
                .mockStatic(AppGlobals.class)
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
        doNothing().when(mAlarmManager).setExact(anyInt(), anyLong(), anyString(), any(), any());
        when(mContext.getPackageManager()).thenReturn(mPackageManager);
        when(mPackageManager.hasSystemFeature(
                PackageManager.FEATURE_AUTOMOTIVE)).thenReturn(false);
        when(mPackageManager.hasSystemFeature(PackageManager.FEATURE_EMBEDDED)).thenReturn(false);
        doReturn(mDeviceIdleInternal)
                .when(() -> LocalServices.getService(DeviceIdleInternal.class));
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
        // Used in FlexibilityController.SpecialAppTracker.
        when(mContext.getSystemService(TelephonyManager.class)).thenReturn(mTelephonyManager);
        when(mPackageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY_SUBSCRIPTION))
                .thenReturn(true);
        //used to get jobs by UID
        mJobStore = JobStore.initAndGetForTesting(mContext, mContext.getFilesDir());
        doReturn(mJobStore).when(mJobSchedulerService).getJobStore();
        // Used in JobStatus.
        doReturn(mIPackageManager).when(AppGlobals::getPackageManager);
        // Freeze the clocks at a moment in time
        JobSchedulerService.sSystemClock =
                Clock.fixed(Instant.ofEpochMilli(FROZEN_TIME), ZoneOffset.UTC);
        JobSchedulerService.sElapsedRealtimeClock =
                Clock.fixed(Instant.ofEpochMilli(FROZEN_TIME), ZoneOffset.UTC);
        // Set empty set of privileged apps.
        setSimSlotMappings(null);
        setPowerWhitelistExceptIdle();
        // Initialize real objects.
        doReturn(Long.MAX_VALUE).when(mPrefetchController).getNextEstimatedLaunchTimeLocked(any());
        ArgumentCaptor<BroadcastReceiver> receiverCaptor =
                ArgumentCaptor.forClass(BroadcastReceiver.class);
        mFlexibilityController = new FlexibilityController(mJobSchedulerService,
                mPrefetchController);
        mFcConfig = mFlexibilityController.getFcConfig();

        mSourceUid = AppGlobals.getPackageManager().getPackageUid(SOURCE_PACKAGE, 0, 0);

        setDeviceConfigString(KEY_PERCENTS_TO_DROP_FLEXIBLE_CONSTRAINTS,
                "500=50|60|70|80"
                        + ",400=50|60|70|80"
                        + ",300=50|60|70|80"
                        + ",200=50|60|70|80"
                        + ",100=50|60|70|80");
        setDeviceConfigLong(KEY_DEADLINE_PROXIMITY_LIMIT, 0L);
        setDeviceConfigInt(KEY_APPLIED_CONSTRAINTS, FLEXIBLE_CONSTRAINTS);
        waitForQuietModuleThread();

        verify(mContext).registerReceiver(receiverCaptor.capture(),
                ArgumentMatchers.argThat(filter ->
                        filter.hasAction(PowerManager.ACTION_POWER_SAVE_WHITELIST_CHANGED)));
        mBroadcastReceiver = receiverCaptor.getValue();
    }

    @After
    public void teardown() {
        if (mMockingSession != null) {
            mMockingSession.finishMocking();
        }
    }

    private void advanceElapsedClock(long incrementMs) {
        JobSchedulerService.sElapsedRealtimeClock = Clock.offset(
                sElapsedRealtimeClock, Duration.ofMillis(incrementMs));
    }

    private void setDeviceConfigInt(String key, int val) {
        mDeviceConfigPropertiesBuilder.setInt(key, val);
        updateDeviceConfig(key);
    }

    private void setDeviceConfigLong(String key, Long val) {
        mDeviceConfigPropertiesBuilder.setLong(key, val);
        updateDeviceConfig(key);
    }

    private void setDeviceConfigString(String key, String val) {
        mDeviceConfigPropertiesBuilder.setString(key, val);
        updateDeviceConfig(key);
    }

    private void updateDeviceConfig(String key) {
        synchronized (mFlexibilityController.mLock) {
            mFlexibilityController.prepareForUpdatedConstantsLocked();
            mFcConfig.processConstantLocked(mDeviceConfigPropertiesBuilder.build(), key);
            mFlexibilityController.onConstantsUpdatedLocked();
        }
    }

    private void waitForQuietModuleThread() {
        assertTrue("Failed to wait for quiet module thread",
                AppSchedulingModuleThread.getHandler().runWithScissors(() -> {}, 10_000L));
    }

    private static JobInfo.Builder createJob(int id) {
        return new JobInfo.Builder(id, new ComponentName("foo", "bar"));
    }

    private JobStatus createJobStatus(String testTag, JobInfo.Builder job) {
        return createJobStatus(testTag, job, SOURCE_PACKAGE);
    }

    private JobStatus createJobStatus(String testTag, JobInfo.Builder job, String sourcePackage) {
        JobInfo jobInfo = job.build();
        JobStatus js = JobStatus.createFromJobInfo(
                jobInfo, 1000, sourcePackage, SOURCE_USER_ID, "FCTest", testTag);
        js.enqueueTime = FROZEN_TIME;
        js.setStandbyBucket(ACTIVE_INDEX);
        if (js.hasFlexibilityConstraint()) {
            js.setNumAppliedFlexibleConstraints(Integer.bitCount(
                    mFlexibilityController.getRelevantAppliedConstraintsLocked(js)));
        }
        return js;
    }

    /**
     * Tests that the there are equally many percents to drop constraints as there are constraints
     */
    @Test
    public void testDefaultVariableValues() {
        SparseArray<int[]> defaultPercentsToDrop =
                FlexibilityController.FcConfig.DEFAULT_PERCENTS_TO_DROP_FLEXIBLE_CONSTRAINTS;
        for (int i = 0; i < defaultPercentsToDrop.size(); ++i) {
            assertEquals(Integer.bitCount(FLEXIBLE_CONSTRAINTS),
                    defaultPercentsToDrop.valueAt(i).length);
        }
    }

    @Test
    public void testAppliedConstraints() {
        setDeviceConfigInt(KEY_APPLIED_CONSTRAINTS, FLEXIBLE_CONSTRAINTS);

        // Add connectivity to require 4 constraints
        JobStatus connJs = createJobStatus("testAppliedConstraints",
                createJob(0).setRequiredNetworkType(NETWORK_TYPE_ANY));
        JobStatus nonConnJs = createJobStatus("testAppliedConstraints",
                createJob(1).setRequiredNetworkType(NETWORK_TYPE_NONE));

        mFlexibilityController.maybeStartTrackingJobLocked(connJs, null);
        mFlexibilityController.maybeStartTrackingJobLocked(nonConnJs, null);

        assertEquals(4, connJs.getNumAppliedFlexibleConstraints());
        assertEquals(3, nonConnJs.getNumAppliedFlexibleConstraints());

        mFlexibilityController.setConstraintSatisfied(
                CONSTRAINT_BATTERY_NOT_LOW, true,
                JobSchedulerService.sElapsedRealtimeClock.millis() - HOUR_IN_MILLIS);
        mFlexibilityController.setConstraintSatisfied(
                CONSTRAINT_CHARGING, false,
                JobSchedulerService.sElapsedRealtimeClock.millis() - HOUR_IN_MILLIS);
        mFlexibilityController.setConstraintSatisfied(
                CONSTRAINT_IDLE, false,
                JobSchedulerService.sElapsedRealtimeClock.millis() - HOUR_IN_MILLIS);
        mFlexibilityController.setConstraintSatisfied(
                CONSTRAINT_CONNECTIVITY, true,
                JobSchedulerService.sElapsedRealtimeClock.millis() - HOUR_IN_MILLIS);
        connJs.setTransportAffinitiesSatisfied(true);

        synchronized (mFlexibilityController.mLock) {
            assertFalse(mFlexibilityController.hasEnoughSatisfiedConstraintsLocked(connJs));
            assertFalse(mFlexibilityController.hasEnoughSatisfiedConstraintsLocked(nonConnJs));
        }

        setDeviceConfigInt(KEY_APPLIED_CONSTRAINTS,
                CONSTRAINT_BATTERY_NOT_LOW | CONSTRAINT_CONNECTIVITY);
        waitForQuietModuleThread();

        // Only battery-not-low (which is satisfied) applies to the non-connectivity job, so it
        // should be able to run.
        assertEquals(2, connJs.getNumAppliedFlexibleConstraints());
        assertEquals(1, nonConnJs.getNumAppliedFlexibleConstraints());
        synchronized (mFlexibilityController.mLock) {
            assertTrue(mFlexibilityController.hasEnoughSatisfiedConstraintsLocked(connJs));
            assertTrue(mFlexibilityController.hasEnoughSatisfiedConstraintsLocked(nonConnJs));
        }

        setDeviceConfigInt(KEY_APPLIED_CONSTRAINTS, CONSTRAINT_BATTERY_NOT_LOW);
        waitForQuietModuleThread();

        assertEquals(1, connJs.getNumAppliedFlexibleConstraints());
        assertEquals(1, nonConnJs.getNumAppliedFlexibleConstraints());
        synchronized (mFlexibilityController.mLock) {
            assertTrue(mFlexibilityController.hasEnoughSatisfiedConstraintsLocked(connJs));
            assertTrue(mFlexibilityController.hasEnoughSatisfiedConstraintsLocked(nonConnJs));
        }

        setDeviceConfigInt(KEY_APPLIED_CONSTRAINTS, CONSTRAINT_CONNECTIVITY);
        waitForQuietModuleThread();

        // No constraints apply to the non-connectivity job, so it should be able to run.
        assertEquals(1, connJs.getNumAppliedFlexibleConstraints());
        assertEquals(0, nonConnJs.getNumAppliedFlexibleConstraints());
        synchronized (mFlexibilityController.mLock) {
            assertTrue(mFlexibilityController.hasEnoughSatisfiedConstraintsLocked(connJs));
            assertTrue(mFlexibilityController.hasEnoughSatisfiedConstraintsLocked(nonConnJs));
        }

        setDeviceConfigInt(KEY_APPLIED_CONSTRAINTS, CONSTRAINT_CHARGING);
        waitForQuietModuleThread();

        assertEquals(1, connJs.getNumAppliedFlexibleConstraints());
        assertEquals(1, nonConnJs.getNumAppliedFlexibleConstraints());
        synchronized (mFlexibilityController.mLock) {
            assertFalse(mFlexibilityController.hasEnoughSatisfiedConstraintsLocked(connJs));
            assertFalse(mFlexibilityController.hasEnoughSatisfiedConstraintsLocked(nonConnJs));
        }

        setDeviceConfigInt(KEY_APPLIED_CONSTRAINTS, 0);
        waitForQuietModuleThread();

        // No constraints apply, so they should be able to run.
        assertEquals(0, connJs.getNumAppliedFlexibleConstraints());
        assertEquals(0, nonConnJs.getNumAppliedFlexibleConstraints());
        synchronized (mFlexibilityController.mLock) {
            assertTrue(mFlexibilityController.hasEnoughSatisfiedConstraintsLocked(connJs));
            assertTrue(mFlexibilityController.hasEnoughSatisfiedConstraintsLocked(nonConnJs));
        }

        // Invalid constraint to apply.
        setDeviceConfigInt(KEY_APPLIED_CONSTRAINTS, CONSTRAINT_CONTENT_TRIGGER);
        waitForQuietModuleThread();

        // No constraints apply, so they should be able to run.
        assertEquals(0, connJs.getNumAppliedFlexibleConstraints());
        assertEquals(0, nonConnJs.getNumAppliedFlexibleConstraints());
        synchronized (mFlexibilityController.mLock) {
            assertTrue(mFlexibilityController.hasEnoughSatisfiedConstraintsLocked(connJs));
            assertTrue(mFlexibilityController.hasEnoughSatisfiedConstraintsLocked(nonConnJs));
        }
    }

    @Test
    public void testOnConstantsUpdated_AppliedConstraints() {
        JobStatus js = createJobStatus("testDefaultFlexibilityConfig", createJob(0));
        setDeviceConfigInt(KEY_APPLIED_CONSTRAINTS, 0);
        assertTrue(mFlexibilityController.isFlexibilitySatisfiedLocked(js));
        setDeviceConfigInt(KEY_APPLIED_CONSTRAINTS, FLEXIBLE_CONSTRAINTS);
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
        final long nowElapsed = sElapsedRealtimeClock.millis();
        assertEquals(DEFAULT_FALLBACK_FLEXIBILITY_DEADLINES.get(JobInfo.PRIORITY_DEFAULT),
                mFlexibilityController.getLifeCycleEndElapsedLocked(js, nowElapsed, 0L));
        setDeviceConfigLong(KEY_FALLBACK_FLEXIBILITY_DEADLINE, 123L);
        setDeviceConfigString(KEY_FALLBACK_FLEXIBILITY_DEADLINES,
                "500=500,400=400,300=300,200=200,100=100");
        assertEquals(300L, mFlexibilityController.getLifeCycleEndElapsedLocked(js, nowElapsed, 0L));
    }

    @Test
    public void testOnConstantsUpdated_PercentsToDropConstraints() {
        final long fallbackDuration = 12 * HOUR_IN_MILLIS;
        JobInfo.Builder jb = createJob(0)
                .setOverrideDeadline(HOUR_IN_MILLIS);
        JobStatus js = createJobStatus("testPercentsToDropConstraintsConfig", jb);
        // Even though the override deadline is 1 hour, the fallback duration is still used.
        assertEquals(FROZEN_TIME + fallbackDuration / 10 * 5,
                mFlexibilityController.getNextConstraintDropTimeElapsedLocked(js));
        setDeviceConfigString(KEY_PERCENTS_TO_DROP_FLEXIBLE_CONSTRAINTS,
                "500=1|2|3|4"
                        + ",400=5|6|7|8"
                        + ",300=10|20|30|40"
                        + ",200=50|51|52|53"
                        + ",100=54|55|56|57");
        assertArrayEquals(
                mFlexibilityController.mFcConfig.PERCENTS_TO_DROP_FLEXIBLE_CONSTRAINTS
                        .get(JobInfo.PRIORITY_MAX),
                new int[]{1, 2, 3, 4});
        assertArrayEquals(
                mFlexibilityController.mFcConfig.PERCENTS_TO_DROP_FLEXIBLE_CONSTRAINTS
                        .get(JobInfo.PRIORITY_HIGH),
                new int[]{5, 6, 7, 8});
        assertArrayEquals(
                mFlexibilityController.mFcConfig.PERCENTS_TO_DROP_FLEXIBLE_CONSTRAINTS
                        .get(JobInfo.PRIORITY_DEFAULT),
                new int[]{10, 20, 30, 40});
        assertArrayEquals(
                mFlexibilityController.mFcConfig.PERCENTS_TO_DROP_FLEXIBLE_CONSTRAINTS
                        .get(JobInfo.PRIORITY_LOW),
                new int[]{50, 51, 52, 53});
        assertArrayEquals(
                mFlexibilityController.mFcConfig.PERCENTS_TO_DROP_FLEXIBLE_CONSTRAINTS
                        .get(JobInfo.PRIORITY_MIN),
                new int[]{54, 55, 56, 57});
        assertEquals(FROZEN_TIME + fallbackDuration / 10,
                mFlexibilityController.getNextConstraintDropTimeElapsedLocked(js));
        js.setNumDroppedFlexibleConstraints(1);
        assertEquals(FROZEN_TIME + fallbackDuration / 10 * 2,
                mFlexibilityController.getNextConstraintDropTimeElapsedLocked(js));
        js.setNumDroppedFlexibleConstraints(2);
        assertEquals(FROZEN_TIME + fallbackDuration / 10 * 3,
                mFlexibilityController.getNextConstraintDropTimeElapsedLocked(js));
    }

    @Test
    public void testOnConstantsUpdated_PercentsToDropConstraintsInvalidValues() {
        // No priority mapping
        setDeviceConfigString(KEY_PERCENTS_TO_DROP_FLEXIBLE_CONSTRAINTS, "10,20,30,40");
        final SparseArray<int[]> defaultPercentsToDrop =
                FlexibilityController.FcConfig.DEFAULT_PERCENTS_TO_DROP_FLEXIBLE_CONSTRAINTS;
        final SparseArray<int[]> percentsToDrop =
                mFlexibilityController.mFcConfig.PERCENTS_TO_DROP_FLEXIBLE_CONSTRAINTS;
        for (int i = 0; i < defaultPercentsToDrop.size(); ++i) {
            assertArrayEquals(defaultPercentsToDrop.valueAt(i), percentsToDrop.valueAt(i));
        }

        // Invalid priority-percentList string
        setDeviceConfigString(KEY_PERCENTS_TO_DROP_FLEXIBLE_CONSTRAINTS,
                "500=10,20a,030,40"
                        + ",400=20|40|60|80"
                        + ",300=25|50|75|80"
                        + ",200=40|50|60|80"
                        + ",100=20|40|60|80");
        for (int i = 0; i < defaultPercentsToDrop.size(); ++i) {
            assertArrayEquals(defaultPercentsToDrop.valueAt(i), percentsToDrop.valueAt(i));
        }

        // Invalid percentList strings
        setDeviceConfigString(KEY_PERCENTS_TO_DROP_FLEXIBLE_CONSTRAINTS,
                "500=10|20a|030|40" // Letters
                        + ",400=10|40" // Not enough
                        + ",300=.|50|_|80" // Characters
                        + ",200=50|40|10|40" // Out of order
                        + ",100=30|60|90|101"); // Over 100
        for (int i = 0; i < defaultPercentsToDrop.size(); ++i) {
            assertArrayEquals(defaultPercentsToDrop.valueAt(i), percentsToDrop.valueAt(i));
        }

        // Only partially invalid
        setDeviceConfigString(KEY_PERCENTS_TO_DROP_FLEXIBLE_CONSTRAINTS,
                "500=10|20a|030|40" // Letters
                        + ",400=10|40" // Not enough
                        + ",300=.|50|_|80" // Characters
                        + ",200=10|20|30|40" // Valid
                        + ",100=20|40|60|80"); // Valid
        assertArrayEquals(defaultPercentsToDrop.get(JobInfo.PRIORITY_MAX),
                percentsToDrop.get(JobInfo.PRIORITY_MAX));
        assertArrayEquals(defaultPercentsToDrop.get(JobInfo.PRIORITY_HIGH),
                percentsToDrop.get(JobInfo.PRIORITY_HIGH));
        assertArrayEquals(defaultPercentsToDrop.get(JobInfo.PRIORITY_DEFAULT),
                percentsToDrop.get(JobInfo.PRIORITY_DEFAULT));
        assertArrayEquals(new int[]{10, 20, 30, 40}, percentsToDrop.get(JobInfo.PRIORITY_LOW));
        assertArrayEquals(new int[]{20, 40, 60, 80}, percentsToDrop.get(JobInfo.PRIORITY_MIN));
    }

    @Test
    public void testGetNextConstraintDropTimeElapsedLocked() {
        final long fallbackDuration = 50 * HOUR_IN_MILLIS;
        setDeviceConfigLong(KEY_FALLBACK_FLEXIBILITY_DEADLINE, 200 * HOUR_IN_MILLIS);
        setDeviceConfigString(KEY_FALLBACK_FLEXIBILITY_DEADLINES,
                "500=" + HOUR_IN_MILLIS
                        + ",400=" + 25 * HOUR_IN_MILLIS
                        + ",300=" + fallbackDuration
                        + ",200=" + 100 * HOUR_IN_MILLIS
                        + ",100=" + 200 * HOUR_IN_MILLIS);

        long nextTimeToDropNumConstraints;

        // no delay, deadline
        JobInfo.Builder jb = createJob(0).setOverrideDeadline(HOUR_IN_MILLIS);
        JobStatus js = createJobStatus("time", jb);

        assertEquals(JobStatus.NO_EARLIEST_RUNTIME, js.getEarliestRunTime());
        assertEquals(HOUR_IN_MILLIS + FROZEN_TIME, js.getLatestRunTimeElapsed());
        assertEquals(FROZEN_TIME, js.enqueueTime);

        nextTimeToDropNumConstraints = mFlexibilityController
                .getNextConstraintDropTimeElapsedLocked(js);
        assertEquals(FROZEN_TIME + fallbackDuration / 10 * 5,
                nextTimeToDropNumConstraints);
        js.setNumDroppedFlexibleConstraints(1);
        nextTimeToDropNumConstraints = mFlexibilityController
                .getNextConstraintDropTimeElapsedLocked(js);
        assertEquals(FROZEN_TIME + fallbackDuration / 10 * 6,
                nextTimeToDropNumConstraints);
        js.setNumDroppedFlexibleConstraints(2);
        nextTimeToDropNumConstraints = mFlexibilityController
                .getNextConstraintDropTimeElapsedLocked(js);
        assertEquals(FROZEN_TIME + fallbackDuration / 10 * 7,
                nextTimeToDropNumConstraints);

        // delay, no deadline
        jb = createJob(0).setMinimumLatency(800000L);
        js = createJobStatus("time", jb);

        nextTimeToDropNumConstraints = mFlexibilityController
                .getNextConstraintDropTimeElapsedLocked(js);
        assertEquals(FROZEN_TIME + 800000L + (50 * HOUR_IN_MILLIS) / 2,
                nextTimeToDropNumConstraints);
        js.setNumDroppedFlexibleConstraints(1);
        nextTimeToDropNumConstraints = mFlexibilityController
                .getNextConstraintDropTimeElapsedLocked(js);
        assertEquals(FROZEN_TIME + 800000L + (50 * HOUR_IN_MILLIS) * 6 / 10,
                nextTimeToDropNumConstraints);
        js.setNumDroppedFlexibleConstraints(2);
        nextTimeToDropNumConstraints = mFlexibilityController
                .getNextConstraintDropTimeElapsedLocked(js);
        assertEquals(FROZEN_TIME + 800000L + (50 * HOUR_IN_MILLIS) * 7 / 10,
                nextTimeToDropNumConstraints);

        // no delay, no deadline
        jb = createJob(0).setPriority(JobInfo.PRIORITY_LOW);
        js = createJobStatus("time", jb);

        nextTimeToDropNumConstraints = mFlexibilityController
                .getNextConstraintDropTimeElapsedLocked(js);
        assertEquals(FROZEN_TIME + (100 * HOUR_IN_MILLIS) / 2, nextTimeToDropNumConstraints);
        js.setNumDroppedFlexibleConstraints(1);
        nextTimeToDropNumConstraints = mFlexibilityController
                .getNextConstraintDropTimeElapsedLocked(js);
        assertEquals(FROZEN_TIME + (100 * HOUR_IN_MILLIS) * 6 / 10, nextTimeToDropNumConstraints);
        js.setNumDroppedFlexibleConstraints(2);
        nextTimeToDropNumConstraints = mFlexibilityController
                .getNextConstraintDropTimeElapsedLocked(js);
        assertEquals(FROZEN_TIME + (100 * HOUR_IN_MILLIS) * 7 / 10, nextTimeToDropNumConstraints);

        // delay, deadline
        jb = createJob(0)
                .setOverrideDeadline(2 * HOUR_IN_MILLIS)
                .setMinimumLatency(HOUR_IN_MILLIS);
        js = createJobStatus("time", jb);

        final long windowStart = FROZEN_TIME + HOUR_IN_MILLIS;
        nextTimeToDropNumConstraints = mFlexibilityController
                .getNextConstraintDropTimeElapsedLocked(js);
        assertEquals(windowStart + fallbackDuration / 10 * 5,
                nextTimeToDropNumConstraints);
        js.setNumDroppedFlexibleConstraints(1);
        nextTimeToDropNumConstraints = mFlexibilityController
                .getNextConstraintDropTimeElapsedLocked(js);
        assertEquals(windowStart + fallbackDuration / 10 * 6,
                nextTimeToDropNumConstraints);
        js.setNumDroppedFlexibleConstraints(2);
        nextTimeToDropNumConstraints = mFlexibilityController
                .getNextConstraintDropTimeElapsedLocked(js);
        assertEquals(windowStart + fallbackDuration / 10 * 7,
                nextTimeToDropNumConstraints);
    }

    @Test
    public void testCurPercent() {
        final long fallbackDuration = 10 * HOUR_IN_MILLIS;
        setDeviceConfigString(KEY_FALLBACK_FLEXIBILITY_DEADLINES, "300=" + fallbackDuration);
        long deadline = 100 * MINUTE_IN_MILLIS;
        long nowElapsed = FROZEN_TIME;
        JobInfo.Builder jb = createJob(0).setOverrideDeadline(deadline);
        JobStatus js = createJobStatus("time", jb);

        assertEquals(FROZEN_TIME, mFlexibilityController.getLifeCycleBeginningElapsedLocked(js));
        assertEquals(FROZEN_TIME + fallbackDuration,
                mFlexibilityController.getLifeCycleEndElapsedLocked(js, nowElapsed, FROZEN_TIME));
        nowElapsed = FROZEN_TIME + 6 * HOUR_IN_MILLIS;
        JobSchedulerService.sElapsedRealtimeClock =
                Clock.fixed(Instant.ofEpochMilli(nowElapsed), ZoneOffset.UTC);
        assertEquals(60, mFlexibilityController.getCurPercentOfLifecycleLocked(js, nowElapsed));

        nowElapsed = FROZEN_TIME + 13 * HOUR_IN_MILLIS;
        JobSchedulerService.sElapsedRealtimeClock =
                Clock.fixed(Instant.ofEpochMilli(nowElapsed), ZoneOffset.UTC);
        assertEquals(100, mFlexibilityController.getCurPercentOfLifecycleLocked(js, nowElapsed));

        nowElapsed = FROZEN_TIME + 9 * HOUR_IN_MILLIS;
        JobSchedulerService.sElapsedRealtimeClock =
                Clock.fixed(Instant.ofEpochMilli(nowElapsed), ZoneOffset.UTC);
        assertEquals(90, mFlexibilityController.getCurPercentOfLifecycleLocked(js, nowElapsed));

        nowElapsed = FROZEN_TIME;
        JobSchedulerService.sElapsedRealtimeClock =
                Clock.fixed(Instant.ofEpochMilli(nowElapsed), ZoneOffset.UTC);
        long delay = HOUR_IN_MILLIS;
        deadline = HOUR_IN_MILLIS + 100 * MINUTE_IN_MILLIS;
        jb = createJob(0).setOverrideDeadline(deadline).setMinimumLatency(delay);
        js = createJobStatus("time", jb);

        assertEquals(FROZEN_TIME + delay,
                mFlexibilityController.getLifeCycleBeginningElapsedLocked(js));
        assertEquals(FROZEN_TIME + delay + fallbackDuration,
                mFlexibilityController.getLifeCycleEndElapsedLocked(js, nowElapsed,
                        FROZEN_TIME + delay));

        nowElapsed = FROZEN_TIME + delay + 6 * HOUR_IN_MILLIS;
        JobSchedulerService.sElapsedRealtimeClock =
                Clock.fixed(Instant.ofEpochMilli(nowElapsed), ZoneOffset.UTC);

        assertEquals(60, mFlexibilityController.getCurPercentOfLifecycleLocked(js, nowElapsed));

        nowElapsed = FROZEN_TIME + 13 * HOUR_IN_MILLIS;
        JobSchedulerService.sElapsedRealtimeClock =
                Clock.fixed(Instant.ofEpochMilli(nowElapsed), ZoneOffset.UTC);
        assertEquals(100, mFlexibilityController.getCurPercentOfLifecycleLocked(js, nowElapsed));

        nowElapsed = FROZEN_TIME + delay + 9 * HOUR_IN_MILLIS;
        JobSchedulerService.sElapsedRealtimeClock =
                Clock.fixed(Instant.ofEpochMilli(nowElapsed), ZoneOffset.UTC);
        assertEquals(90, mFlexibilityController.getCurPercentOfLifecycleLocked(js, nowElapsed));
    }

    @Test
    public void testGetLifeCycleBeginningElapsedLocked_Periodic() {
        // Periodic with lifecycle
        JobInfo.Builder jbBasic = createJob(0).setPeriodic(HOUR_IN_MILLIS);
        JobInfo.Builder jbFlex = createJob(0)
                .setPeriodic(HOUR_IN_MILLIS, 20 * MINUTE_IN_MILLIS);
        JobStatus jsBasic =
                createJobStatus("testGetLifeCycleBeginningElapsedLocked_Periodic", jbBasic);
        JobStatus jsFlex =
                createJobStatus("testGetLifeCycleBeginningElapsedLocked_Periodic", jbFlex);

        final long nowElapsed = JobSchedulerService.sElapsedRealtimeClock.millis();
        // Base case, no start adjustment
        assertEquals(nowElapsed,
                mFlexibilityController.getLifeCycleBeginningElapsedLocked(jsBasic));
        assertEquals(nowElapsed + 40 * MINUTE_IN_MILLIS,
                mFlexibilityController.getLifeCycleBeginningElapsedLocked(jsFlex));

        // Rescheduled with start adjustment
        final long adjustmentMs = 4 * MINUTE_IN_MILLIS;
        jsBasic = new JobStatus(jsBasic,
                // "True" start is nowElapsed + HOUR_IN_MILLIS
                nowElapsed + HOUR_IN_MILLIS + adjustmentMs,
                nowElapsed + 2 * HOUR_IN_MILLIS,
                0 /* numFailures */, 0 /* numSystemStops */,
                JobSchedulerService.sSystemClock.millis() /* lastSuccessfulRunTime */,
                0, 0);
        jsFlex = new JobStatus(jsFlex,
                // "True" start is nowElapsed + 2 * HOUR_IN_MILLIS - 20 * MINUTE_IN_MILLIS
                nowElapsed + 2 * HOUR_IN_MILLIS - 20 * MINUTE_IN_MILLIS + adjustmentMs,
                nowElapsed + 2 * HOUR_IN_MILLIS,
                0 /* numFailures */, 0 /* numSystemStops */,
                JobSchedulerService.sSystemClock.millis() /* lastSuccessfulRunTime */,
                0, 0);

        assertEquals(nowElapsed + HOUR_IN_MILLIS + adjustmentMs / 2,
                mFlexibilityController.getLifeCycleBeginningElapsedLocked(jsBasic));
        assertEquals(nowElapsed + 2 * HOUR_IN_MILLIS - 20 * MINUTE_IN_MILLIS + adjustmentMs / 2,
                mFlexibilityController.getLifeCycleBeginningElapsedLocked(jsFlex));

        // Rescheduled for failure
        jsBasic = new JobStatus(jsBasic,
                nowElapsed + 30 * MINUTE_IN_MILLIS,
                NO_LATEST_RUNTIME,
                1 /* numFailures */, 1 /* numSystemStops */,
                JobSchedulerService.sSystemClock.millis() /* lastSuccessfulRunTime */,
                0, 0);
        jsFlex = new JobStatus(jsFlex,
                nowElapsed + 30 * MINUTE_IN_MILLIS,
                NO_LATEST_RUNTIME,
                1 /* numFailures */, 1 /* numSystemStops */,
                JobSchedulerService.sSystemClock.millis() /* lastSuccessfulRunTime */,
                0, 0);

        assertEquals(nowElapsed + 30 * MINUTE_IN_MILLIS,
                mFlexibilityController.getLifeCycleBeginningElapsedLocked(jsBasic));
        assertEquals(nowElapsed + 30 * MINUTE_IN_MILLIS,
                mFlexibilityController.getLifeCycleBeginningElapsedLocked(jsFlex));
    }

    @Test
    public void testGetLifeCycleBeginningElapsedLocked_Prefetch() {
        // prefetch with lifecycle
        doReturn(700L).when(mPrefetchController).getLaunchTimeThresholdMs();
        JobInfo.Builder jb = createJob(0).setPrefetch(true);
        JobStatus js = createJobStatus("time", jb);
        doReturn(900L).when(mPrefetchController).getNextEstimatedLaunchTimeLocked(js);
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
        doReturn(Long.MAX_VALUE).when(mPrefetchController).getNextEstimatedLaunchTimeLocked(js);
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
        final long nowElapsed = sElapsedRealtimeClock.millis();

        // prefetch no estimate
        JobInfo.Builder jb = createJob(0).setPrefetch(true);
        JobStatus js = createJobStatus("time", jb);
        doReturn(Long.MAX_VALUE).when(mPrefetchController).getNextEstimatedLaunchTimeLocked(js);
        assertEquals(Long.MAX_VALUE,
                mFlexibilityController.getLifeCycleEndElapsedLocked(js, nowElapsed, 0));

        // prefetch with estimate
        jb = createJob(0).setPrefetch(true);
        js = createJobStatus("time", jb);
        doReturn(1000L).when(mPrefetchController).getNextEstimatedLaunchTimeLocked(js);
        assertEquals(1000L,
                mFlexibilityController.getLifeCycleEndElapsedLocked(js, nowElapsed, 0));
    }

    @Test
    public void testGetLifeCycleEndElapsedLocked_NonPrefetch() {
        setDeviceConfigString(KEY_FALLBACK_FLEXIBILITY_DEADLINES,
                "500=" + HOUR_IN_MILLIS
                        + ",400=" + 2 * HOUR_IN_MILLIS
                        + ",300=" + 3 * HOUR_IN_MILLIS
                        + ",200=" + 4 * HOUR_IN_MILLIS
                        + ",100=" + 5 * HOUR_IN_MILLIS);

        final long nowElapsed = sElapsedRealtimeClock.millis();

        // deadline
        JobInfo.Builder jb = createJob(0).setOverrideDeadline(HOUR_IN_MILLIS);
        JobStatus js = createJobStatus("time", jb);
        assertEquals(3 * HOUR_IN_MILLIS + js.enqueueTime,
                mFlexibilityController
                        .getLifeCycleEndElapsedLocked(js, nowElapsed, js.enqueueTime));

        // no deadline
        assertEquals(js.enqueueTime + 2 * HOUR_IN_MILLIS,
                mFlexibilityController.getLifeCycleEndElapsedLocked(
                        createJobStatus("time", createJob(0).setPriority(JobInfo.PRIORITY_HIGH)),
                        nowElapsed, js.enqueueTime));
        assertEquals(js.enqueueTime + 3 * HOUR_IN_MILLIS,
                mFlexibilityController.getLifeCycleEndElapsedLocked(
                        createJobStatus("time", createJob(0).setPriority(JobInfo.PRIORITY_DEFAULT)),
                        nowElapsed, js.enqueueTime));
        assertEquals(js.enqueueTime + 4 * HOUR_IN_MILLIS,
                mFlexibilityController.getLifeCycleEndElapsedLocked(
                        createJobStatus("time", createJob(0).setPriority(JobInfo.PRIORITY_LOW)),
                        nowElapsed, js.enqueueTime));
        assertEquals(js.enqueueTime + 5 * HOUR_IN_MILLIS,
                mFlexibilityController.getLifeCycleEndElapsedLocked(
                        createJobStatus("time", createJob(0).setPriority(JobInfo.PRIORITY_MIN)),
                        nowElapsed, js.enqueueTime));
    }

    @Test
    public void testGetLifeCycleEndElapsedLocked_Rescheduled() {
        final long nowElapsed = sElapsedRealtimeClock.millis();

        JobInfo.Builder jb = createJob(0).setOverrideDeadline(HOUR_IN_MILLIS);
        JobStatus js = createJobStatus("time", jb);
        js = new JobStatus(
                js, FROZEN_TIME, NO_LATEST_RUNTIME, /* numFailures */ 2, /* numSystemStops */ 0,
                0, FROZEN_TIME, FROZEN_TIME);

        assertEquals(mFcConfig.RESCHEDULED_JOB_DEADLINE_MS,
                mFlexibilityController.getLifeCycleEndElapsedLocked(js, nowElapsed, 0));

        js = new JobStatus(
                js, FROZEN_TIME, NO_LATEST_RUNTIME, /* numFailures */ 2, /* numSystemStops */ 1,
                0, FROZEN_TIME, FROZEN_TIME);

        assertEquals(2 * mFcConfig.RESCHEDULED_JOB_DEADLINE_MS,
                mFlexibilityController.getLifeCycleEndElapsedLocked(js, nowElapsed, 0));

        js = new JobStatus(
                js, FROZEN_TIME, NO_LATEST_RUNTIME, /* numFailures */ 0, /* numSystemStops */ 10,
                0, FROZEN_TIME, FROZEN_TIME);
        assertEquals(mFcConfig.MAX_RESCHEDULED_DEADLINE_MS,
                mFlexibilityController.getLifeCycleEndElapsedLocked(js, nowElapsed, 0));
    }

    @Test
    public void testGetLifeCycleEndElapsedLocked_ScoreAddition() {
        setDeviceConfigString(KEY_FALLBACK_FLEXIBILITY_DEADLINES,
                "500=" + HOUR_IN_MILLIS
                        + ",400=" + HOUR_IN_MILLIS
                        + ",300=" + HOUR_IN_MILLIS
                        + ",200=" + HOUR_IN_MILLIS
                        + ",100=" + HOUR_IN_MILLIS);
        setDeviceConfigString(KEY_FALLBACK_FLEXIBILITY_DEADLINE_SCORES,
                "500=5,400=4,300=3,200=2,100=1");
        setDeviceConfigString(KEY_FALLBACK_FLEXIBILITY_DEADLINE_ADDITIONAL_SCORE_TIME_FACTORS,
                "500=" + 5 * MINUTE_IN_MILLIS
                        + ",400=" + 4 * MINUTE_IN_MILLIS
                        + ",300=" + 3 * MINUTE_IN_MILLIS
                        + ",200=" + 2 * MINUTE_IN_MILLIS
                        + ",100=" + 1 * MINUTE_IN_MILLIS);

        final long nowElapsed = sElapsedRealtimeClock.millis();

        JobStatus jsMax = createJobStatus("testScoreCalculation",
                createJob(0).setExpedited(true).setPriority(JobInfo.PRIORITY_MAX));
        JobStatus jsHigh = createJobStatus("testScoreCalculation",
                createJob(0).setPriority(JobInfo.PRIORITY_HIGH));
        JobStatus jsDefault = createJobStatus("testScoreCalculation",
                createJob(0).setPriority(JobInfo.PRIORITY_DEFAULT));
        JobStatus jsLow = createJobStatus("testScoreCalculation",
                createJob(0).setPriority(JobInfo.PRIORITY_LOW));
        JobStatus jsMin = createJobStatus("testScoreCalculation",
                createJob(0).setPriority(JobInfo.PRIORITY_MIN));
        // Make score = 15
        mFlexibilityController.prepareForExecutionLocked(jsMax);
        mFlexibilityController.prepareForExecutionLocked(jsHigh);
        mFlexibilityController.prepareForExecutionLocked(jsDefault);
        mFlexibilityController.prepareForExecutionLocked(jsLow);
        mFlexibilityController.prepareForExecutionLocked(jsMin);

        final long longDeadlineMs = 14 * 24 * HOUR_IN_MILLIS;
        JobInfo.Builder jbWithLongDeadline = createJob(0).setOverrideDeadline(longDeadlineMs);
        JobStatus jsWithLongDeadline = createJobStatus(
                "testGetLifeCycleEndElapsedLocked_ScoreAddition", jbWithLongDeadline);
        JobInfo.Builder jbWithShortDeadline =
                createJob(0).setOverrideDeadline(15 * MINUTE_IN_MILLIS);
        JobStatus jsWithShortDeadline = createJobStatus(
                "testGetLifeCycleEndElapsedLocked_ScoreAddition", jbWithShortDeadline);

        final long earliestMs = 123L;
        assertEquals(earliestMs + HOUR_IN_MILLIS + 5 * 15 * MINUTE_IN_MILLIS,
                mFlexibilityController.getLifeCycleEndElapsedLocked(
                        createJobStatus("testGetLifeCycleEndElapsedLocked_ScoreAddition",
                                createJob(0).setExpedited(true).setPriority(JobInfo.PRIORITY_MAX)),
                        nowElapsed, earliestMs));
        assertEquals(earliestMs + HOUR_IN_MILLIS + 4 * 15 * MINUTE_IN_MILLIS,
                mFlexibilityController.getLifeCycleEndElapsedLocked(
                        createJobStatus("testGetLifeCycleEndElapsedLocked_ScoreAddition",
                                createJob(0).setPriority(JobInfo.PRIORITY_HIGH)),
                        nowElapsed, earliestMs));
        assertEquals(earliestMs + HOUR_IN_MILLIS + 3 * 15 * MINUTE_IN_MILLIS,
                mFlexibilityController.getLifeCycleEndElapsedLocked(
                        createJobStatus("testGetLifeCycleEndElapsedLocked_ScoreAddition",
                                createJob(0).setPriority(JobInfo.PRIORITY_DEFAULT)),
                        nowElapsed, earliestMs));
        assertEquals(earliestMs + HOUR_IN_MILLIS + 3 * 15 * MINUTE_IN_MILLIS,
                mFlexibilityController.getLifeCycleEndElapsedLocked(
                        jsWithShortDeadline, nowElapsed, earliestMs));
        assertEquals(earliestMs + HOUR_IN_MILLIS + 2 * 15 * MINUTE_IN_MILLIS,
                mFlexibilityController.getLifeCycleEndElapsedLocked(
                        createJobStatus("testGetLifeCycleEndElapsedLocked_ScoreAddition",
                                createJob(0).setPriority(JobInfo.PRIORITY_LOW)),
                        nowElapsed, earliestMs));
        assertEquals(earliestMs + HOUR_IN_MILLIS + 1 * 15 * MINUTE_IN_MILLIS,
                mFlexibilityController.getLifeCycleEndElapsedLocked(
                        createJobStatus("testGetLifeCycleEndElapsedLocked_ScoreAddition",
                                createJob(0).setPriority(JobInfo.PRIORITY_MIN)),
                        nowElapsed, earliestMs));
        assertEquals(jsWithLongDeadline.enqueueTime + longDeadlineMs,
                mFlexibilityController.getLifeCycleEndElapsedLocked(
                        jsWithLongDeadline, nowElapsed, earliestMs));
    }

    @Test
    public void testWontStopAlreadyRunningJob() {
        JobStatus js = createJobStatus("testWontStopAlreadyRunningJob", createJob(101));
        // Stop satisfied constraints from causing a false positive.
        js.setNumAppliedFlexibleConstraints(100);
        synchronized (mFlexibilityController.mLock) {
            doReturn(true).when(mJobSchedulerService).isCurrentlyRunningLocked(js);
            assertTrue(mFlexibilityController.isFlexibilitySatisfiedLocked(js));
        }
    }

    @Test
    public void testFlexibilityTracker() {
        setDeviceConfigLong(KEY_FALLBACK_FLEXIBILITY_DEADLINE, 100 * HOUR_IN_MILLIS);
        setDeviceConfigString(KEY_FALLBACK_FLEXIBILITY_DEADLINES,
                "500=" + 100 * HOUR_IN_MILLIS
                        + ",400=" + 100 * HOUR_IN_MILLIS
                        + ",300=" + 100 * HOUR_IN_MILLIS
                        + ",200=" + 100 * HOUR_IN_MILLIS
                        + ",100=" + 100 * HOUR_IN_MILLIS);

        FlexibilityController.FlexibilityTracker flexTracker =
                mFlexibilityController.new FlexibilityTracker(4);
        // Plus one for jobs with 0 required constraint.
        assertEquals(4 + 1, flexTracker.size());
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

            flexTracker.setNumDroppedFlexibleConstraints(jobs[0], 1);
            assertEquals(1, trackedJobs.get(0).size());
            assertEquals(0, trackedJobs.get(1).size());
            assertEquals(1, trackedJobs.get(2).size());
            assertEquals(2, trackedJobs.get(3).size());
            assertEquals(0, trackedJobs.get(4).size());

            flexTracker.setNumDroppedFlexibleConstraints(jobs[0], 2);
            assertEquals(1, trackedJobs.get(0).size());
            assertEquals(1, trackedJobs.get(1).size());
            assertEquals(0, trackedJobs.get(2).size());
            assertEquals(2, trackedJobs.get(3).size());
            assertEquals(0, trackedJobs.get(4).size());

            flexTracker.setNumDroppedFlexibleConstraints(jobs[0], 3);
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

            flexTracker.calculateNumDroppedConstraints(jobs[0], FROZEN_TIME);
            assertEquals(1, trackedJobs.get(0).size());
            assertEquals(0, trackedJobs.get(1).size());
            assertEquals(0, trackedJobs.get(2).size());
            assertEquals(2, trackedJobs.get(3).size());
            assertEquals(0, trackedJobs.get(4).size());

            flexTracker.setNumDroppedFlexibleConstraints(jobs[0], 2);
            assertEquals(1, trackedJobs.get(0).size());
            assertEquals(1, trackedJobs.get(1).size());
            assertEquals(0, trackedJobs.get(2).size());
            assertEquals(1, trackedJobs.get(3).size());
            assertEquals(0, trackedJobs.get(4).size());

            final long nowElapsed = 51 * HOUR_IN_MILLIS;
            JobSchedulerService.sElapsedRealtimeClock =
                    Clock.fixed(Instant.ofEpochMilli(nowElapsed), ZoneOffset.UTC);

            flexTracker.calculateNumDroppedConstraints(jobs[0], nowElapsed);
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
    @CoreCompatChangeRule.DisableCompatChanges({JobInfo.ENFORCE_MINIMUM_TIME_WINDOWS})
    public void testExceptions_ShortWindow() {
        JobInfo.Builder jb = createJob(0);
        jb.setMinimumLatency(1);
        jb.setOverrideDeadline(2);
        JobStatus js = createJobStatus("testExceptions_ShortWindow", jb);
        assertTrue(js.hasFlexibilityConstraint());
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
    public void testAllowlistedAppBypass() {
        mFlexibilityController.onSystemServicesReady();

        JobStatus jsHigh = createJobStatus("testAllowlistedAppBypass",
                createJob(0).setPriority(JobInfo.PRIORITY_HIGH));
        JobStatus jsDefault = createJobStatus("testAllowlistedAppBypass",
                createJob(0).setPriority(JobInfo.PRIORITY_DEFAULT));
        JobStatus jsLow = createJobStatus("testAllowlistedAppBypass",
                createJob(0).setPriority(JobInfo.PRIORITY_LOW));
        JobStatus jsMin = createJobStatus("testAllowlistedAppBypass",
                createJob(0).setPriority(JobInfo.PRIORITY_MIN));
        jsHigh.setStandbyBucket(EXEMPTED_INDEX);
        jsDefault.setStandbyBucket(EXEMPTED_INDEX);
        jsLow.setStandbyBucket(EXEMPTED_INDEX);
        jsMin.setStandbyBucket(EXEMPTED_INDEX);

        setPowerWhitelistExceptIdle();
        synchronized (mFlexibilityController.mLock) {
            assertFalse(mFlexibilityController.isFlexibilitySatisfiedLocked(jsHigh));
            assertFalse(mFlexibilityController.isFlexibilitySatisfiedLocked(jsDefault));
            assertFalse(mFlexibilityController.isFlexibilitySatisfiedLocked(jsLow));
            assertFalse(mFlexibilityController.isFlexibilitySatisfiedLocked(jsMin));
        }

        setPowerWhitelistExceptIdle(SOURCE_PACKAGE);
        synchronized (mFlexibilityController.mLock) {
            assertTrue(mFlexibilityController.isFlexibilitySatisfiedLocked(jsHigh));
            assertTrue(mFlexibilityController.isFlexibilitySatisfiedLocked(jsDefault));
            assertFalse(mFlexibilityController.isFlexibilitySatisfiedLocked(jsLow));
            assertFalse(mFlexibilityController.isFlexibilitySatisfiedLocked(jsMin));
        }
    }

    @Test
    public void testCarrierPrivilegedAppBypass() throws Exception {
        mFlexibilityController.onSystemServicesReady();

        final String carrier1Pkg1 = "com.test.carrier.1.pkg.1";
        final String carrier1Pkg2 = "com.test.carrier.1.pkg.2";
        final String carrier2Pkg = "com.test.carrier.2.pkg";
        final String nonCarrierPkg = "com.test.normal.pkg";

        setPackageUid(carrier1Pkg1, 1);
        setPackageUid(carrier1Pkg2, 11);
        setPackageUid(carrier2Pkg, 2);
        setPackageUid(nonCarrierPkg, 3);

        // Set the second carrier's privileged list before SIM configuration is sent to test
        // initialization.
        setCarrierPrivilegedAppList(2, carrier2Pkg);

        UiccSlotMapping sim1 = mock(UiccSlotMapping.class);
        UiccSlotMapping sim2 = mock(UiccSlotMapping.class);
        doReturn(1).when(sim1).getLogicalSlotIndex();
        doReturn(2).when(sim2).getLogicalSlotIndex();
        setSimSlotMappings(List.of(sim1, sim2));

        JobStatus jsHighC1P1 = createJobStatus("testCarrierPrivilegedAppBypass",
                createJob(0).setPriority(JobInfo.PRIORITY_HIGH), carrier1Pkg1);
        JobStatus jsDefaultC1P1 = createJobStatus("testCarrierPrivilegedAppBypass",
                createJob(0).setPriority(JobInfo.PRIORITY_DEFAULT), carrier1Pkg1);
        JobStatus jsLowC1P1 = createJobStatus("testCarrierPrivilegedAppBypass",
                createJob(0).setPriority(JobInfo.PRIORITY_LOW), carrier1Pkg1);
        JobStatus jsMinC1P1 = createJobStatus("testCarrierPrivilegedAppBypass",
                createJob(0).setPriority(JobInfo.PRIORITY_MIN), carrier1Pkg1);
        JobStatus jsHighC1P2 = createJobStatus("testCarrierPrivilegedAppBypass",
                createJob(0).setPriority(JobInfo.PRIORITY_HIGH), carrier1Pkg2);
        JobStatus jsDefaultC1P2 = createJobStatus("testCarrierPrivilegedAppBypass",
                createJob(0).setPriority(JobInfo.PRIORITY_DEFAULT), carrier1Pkg2);
        JobStatus jsLowC1P2 = createJobStatus("testCarrierPrivilegedAppBypass",
                createJob(0).setPriority(JobInfo.PRIORITY_LOW), carrier1Pkg2);
        JobStatus jsMinC1P2 = createJobStatus("testCarrierPrivilegedAppBypass",
                createJob(0).setPriority(JobInfo.PRIORITY_MIN), carrier1Pkg2);
        JobStatus jsHighC2P = createJobStatus("testCarrierPrivilegedAppBypass",
                createJob(0).setPriority(JobInfo.PRIORITY_HIGH), carrier2Pkg);
        JobStatus jsDefaultC2P = createJobStatus("testCarrierPrivilegedAppBypass",
                createJob(0).setPriority(JobInfo.PRIORITY_DEFAULT), carrier2Pkg);
        JobStatus jsLowC2P = createJobStatus("testCarrierPrivilegedAppBypass",
                createJob(0).setPriority(JobInfo.PRIORITY_LOW), carrier2Pkg);
        JobStatus jsMinC2P = createJobStatus("testCarrierPrivilegedAppBypass",
                createJob(0).setPriority(JobInfo.PRIORITY_MIN), carrier2Pkg);
        JobStatus jsHighNCP = createJobStatus("testCarrierPrivilegedAppBypass",
                createJob(0).setPriority(JobInfo.PRIORITY_HIGH), nonCarrierPkg);
        JobStatus jsDefaultNCP = createJobStatus("testCarrierPrivilegedAppBypass",
                createJob(0).setPriority(JobInfo.PRIORITY_DEFAULT), nonCarrierPkg);
        JobStatus jsLowNCP = createJobStatus("testCarrierPrivilegedAppBypass",
                createJob(0).setPriority(JobInfo.PRIORITY_LOW), nonCarrierPkg);
        JobStatus jsMinNCP = createJobStatus("testCarrierPrivilegedAppBypass",
                createJob(0).setPriority(JobInfo.PRIORITY_MIN), nonCarrierPkg);

        setCarrierPrivilegedAppList(1);
        synchronized (mFlexibilityController.mLock) {
            assertFalse(mFlexibilityController.isFlexibilitySatisfiedLocked(jsHighC1P1));
            assertFalse(mFlexibilityController.isFlexibilitySatisfiedLocked(jsDefaultC1P1));
            assertFalse(mFlexibilityController.isFlexibilitySatisfiedLocked(jsLowC1P1));
            assertFalse(mFlexibilityController.isFlexibilitySatisfiedLocked(jsMinC1P1));
            assertFalse(mFlexibilityController.isFlexibilitySatisfiedLocked(jsHighC1P2));
            assertFalse(mFlexibilityController.isFlexibilitySatisfiedLocked(jsDefaultC1P2));
            assertFalse(mFlexibilityController.isFlexibilitySatisfiedLocked(jsLowC1P2));
            assertFalse(mFlexibilityController.isFlexibilitySatisfiedLocked(jsMinC1P2));
            assertTrue(mFlexibilityController.isFlexibilitySatisfiedLocked(jsHighC2P));
            assertTrue(mFlexibilityController.isFlexibilitySatisfiedLocked(jsDefaultC2P));
            assertFalse(mFlexibilityController.isFlexibilitySatisfiedLocked(jsLowC2P));
            assertFalse(mFlexibilityController.isFlexibilitySatisfiedLocked(jsMinC2P));
            assertFalse(mFlexibilityController.isFlexibilitySatisfiedLocked(jsHighNCP));
            assertFalse(mFlexibilityController.isFlexibilitySatisfiedLocked(jsDefaultNCP));
            assertFalse(mFlexibilityController.isFlexibilitySatisfiedLocked(jsLowNCP));
            assertFalse(mFlexibilityController.isFlexibilitySatisfiedLocked(jsMinNCP));
        }

        // Only mark the first package of carrier 1 as privileged. Only that app's jobs should
        // be exempted.
        setCarrierPrivilegedAppList(1, carrier1Pkg1);
        synchronized (mFlexibilityController.mLock) {
            assertTrue(mFlexibilityController.isFlexibilitySatisfiedLocked(jsHighC1P1));
            assertTrue(mFlexibilityController.isFlexibilitySatisfiedLocked(jsDefaultC1P1));
            assertFalse(mFlexibilityController.isFlexibilitySatisfiedLocked(jsLowC1P1));
            assertFalse(mFlexibilityController.isFlexibilitySatisfiedLocked(jsMinC1P1));
            assertFalse(mFlexibilityController.isFlexibilitySatisfiedLocked(jsHighC1P2));
            assertFalse(mFlexibilityController.isFlexibilitySatisfiedLocked(jsDefaultC1P2));
            assertFalse(mFlexibilityController.isFlexibilitySatisfiedLocked(jsLowC1P2));
            assertFalse(mFlexibilityController.isFlexibilitySatisfiedLocked(jsMinC1P2));
            assertTrue(mFlexibilityController.isFlexibilitySatisfiedLocked(jsHighC2P));
            assertTrue(mFlexibilityController.isFlexibilitySatisfiedLocked(jsDefaultC2P));
            assertFalse(mFlexibilityController.isFlexibilitySatisfiedLocked(jsLowC2P));
            assertFalse(mFlexibilityController.isFlexibilitySatisfiedLocked(jsMinC2P));
            assertFalse(mFlexibilityController.isFlexibilitySatisfiedLocked(jsHighNCP));
            assertFalse(mFlexibilityController.isFlexibilitySatisfiedLocked(jsDefaultNCP));
            assertFalse(mFlexibilityController.isFlexibilitySatisfiedLocked(jsLowNCP));
            assertFalse(mFlexibilityController.isFlexibilitySatisfiedLocked(jsMinNCP));
        }

        // Add the second package of carrier 1. Both apps' jobs should be exempted.
        setCarrierPrivilegedAppList(1, carrier1Pkg1, carrier1Pkg2);
        synchronized (mFlexibilityController.mLock) {
            assertTrue(mFlexibilityController.isFlexibilitySatisfiedLocked(jsHighC1P1));
            assertTrue(mFlexibilityController.isFlexibilitySatisfiedLocked(jsDefaultC1P1));
            assertFalse(mFlexibilityController.isFlexibilitySatisfiedLocked(jsLowC1P1));
            assertFalse(mFlexibilityController.isFlexibilitySatisfiedLocked(jsMinC1P1));
            assertTrue(mFlexibilityController.isFlexibilitySatisfiedLocked(jsHighC1P2));
            assertTrue(mFlexibilityController.isFlexibilitySatisfiedLocked(jsDefaultC1P2));
            assertFalse(mFlexibilityController.isFlexibilitySatisfiedLocked(jsLowC1P2));
            assertFalse(mFlexibilityController.isFlexibilitySatisfiedLocked(jsMinC1P2));
            assertTrue(mFlexibilityController.isFlexibilitySatisfiedLocked(jsHighC2P));
            assertTrue(mFlexibilityController.isFlexibilitySatisfiedLocked(jsDefaultC2P));
            assertFalse(mFlexibilityController.isFlexibilitySatisfiedLocked(jsLowC2P));
            assertFalse(mFlexibilityController.isFlexibilitySatisfiedLocked(jsMinC2P));
            assertFalse(mFlexibilityController.isFlexibilitySatisfiedLocked(jsHighNCP));
            assertFalse(mFlexibilityController.isFlexibilitySatisfiedLocked(jsDefaultNCP));
            assertFalse(mFlexibilityController.isFlexibilitySatisfiedLocked(jsLowNCP));
            assertFalse(mFlexibilityController.isFlexibilitySatisfiedLocked(jsMinNCP));
        }

        // Remove a SIM slot. The relevant app's should no longer have exempted jobs.
        setSimSlotMappings(List.of(sim1));
        synchronized (mFlexibilityController.mLock) {
            assertTrue(mFlexibilityController.isFlexibilitySatisfiedLocked(jsHighC1P1));
            assertTrue(mFlexibilityController.isFlexibilitySatisfiedLocked(jsDefaultC1P1));
            assertFalse(mFlexibilityController.isFlexibilitySatisfiedLocked(jsLowC1P1));
            assertFalse(mFlexibilityController.isFlexibilitySatisfiedLocked(jsMinC1P1));
            assertTrue(mFlexibilityController.isFlexibilitySatisfiedLocked(jsHighC1P2));
            assertTrue(mFlexibilityController.isFlexibilitySatisfiedLocked(jsDefaultC1P2));
            assertFalse(mFlexibilityController.isFlexibilitySatisfiedLocked(jsLowC1P2));
            assertFalse(mFlexibilityController.isFlexibilitySatisfiedLocked(jsMinC1P2));
            assertFalse(mFlexibilityController.isFlexibilitySatisfiedLocked(jsHighC2P));
            assertFalse(mFlexibilityController.isFlexibilitySatisfiedLocked(jsDefaultC2P));
            assertFalse(mFlexibilityController.isFlexibilitySatisfiedLocked(jsLowC2P));
            assertFalse(mFlexibilityController.isFlexibilitySatisfiedLocked(jsMinC2P));
            assertFalse(mFlexibilityController.isFlexibilitySatisfiedLocked(jsHighNCP));
            assertFalse(mFlexibilityController.isFlexibilitySatisfiedLocked(jsDefaultNCP));
            assertFalse(mFlexibilityController.isFlexibilitySatisfiedLocked(jsLowNCP));
            assertFalse(mFlexibilityController.isFlexibilitySatisfiedLocked(jsMinNCP));
        }
    }

    @Test
    public void testForegroundAppBypass() {
        JobStatus jsHigh = createJobStatus("testAllowlistedAppBypass",
                createJob(0).setPriority(JobInfo.PRIORITY_HIGH));
        JobStatus jsDefault = createJobStatus("testAllowlistedAppBypass",
                createJob(0).setPriority(JobInfo.PRIORITY_DEFAULT));
        JobStatus jsLow = createJobStatus("testAllowlistedAppBypass",
                createJob(0).setPriority(JobInfo.PRIORITY_LOW));
        JobStatus jsMin = createJobStatus("testAllowlistedAppBypass",
                createJob(0).setPriority(JobInfo.PRIORITY_MIN));

        doReturn(JobInfo.BIAS_DEFAULT).when(mJobSchedulerService).getUidBias(mSourceUid);
        synchronized (mFlexibilityController.mLock) {
            assertFalse(mFlexibilityController.isFlexibilitySatisfiedLocked(jsHigh));
            assertFalse(mFlexibilityController.isFlexibilitySatisfiedLocked(jsDefault));
            assertFalse(mFlexibilityController.isFlexibilitySatisfiedLocked(jsLow));
            assertFalse(mFlexibilityController.isFlexibilitySatisfiedLocked(jsMin));
        }

        setUidBias(mSourceUid, JobInfo.BIAS_BOUND_FOREGROUND_SERVICE);
        synchronized (mFlexibilityController.mLock) {
            assertTrue(mFlexibilityController.isFlexibilitySatisfiedLocked(jsHigh));
            assertTrue(mFlexibilityController.isFlexibilitySatisfiedLocked(jsDefault));
            assertFalse(mFlexibilityController.isFlexibilitySatisfiedLocked(jsLow));
            assertFalse(mFlexibilityController.isFlexibilitySatisfiedLocked(jsMin));
        }

        setUidBias(mSourceUid, JobInfo.BIAS_FOREGROUND_SERVICE);
        synchronized (mFlexibilityController.mLock) {
            assertTrue(mFlexibilityController.isFlexibilitySatisfiedLocked(jsHigh));
            assertTrue(mFlexibilityController.isFlexibilitySatisfiedLocked(jsDefault));
            assertFalse(mFlexibilityController.isFlexibilitySatisfiedLocked(jsLow));
            assertFalse(mFlexibilityController.isFlexibilitySatisfiedLocked(jsMin));
        }
    }

    @Test
    public void testTopAppBypass() {
        JobInfo.Builder jb = createJob(0).setPriority(JobInfo.PRIORITY_MIN);
        JobStatus js = createJobStatus("testTopAppBypass", jb);
        mJobStore.add(js);

        // Needed because if before and after Uid bias is the same, nothing happens.
        doReturn(JobInfo.BIAS_DEFAULT).when(mJobSchedulerService).getUidBias(mSourceUid);

        synchronized (mFlexibilityController.mLock) {
            mFlexibilityController.maybeStartTrackingJobLocked(js, null);
            assertFalse(mFlexibilityController.isFlexibilitySatisfiedLocked(js));

            setUidBias(mSourceUid, JobInfo.BIAS_TOP_APP);

            assertTrue(mFlexibilityController.isFlexibilitySatisfiedLocked(js));
            assertTrue(js.isConstraintSatisfied(CONSTRAINT_FLEXIBLE));

            setUidBias(mSourceUid, JobInfo.BIAS_SYNC_INITIALIZATION);

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
        jsAny.setNumAppliedFlexibleConstraints(1);
        jsCell.setNumAppliedFlexibleConstraints(1);
        jsWifi.setNumAppliedFlexibleConstraints(1);
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
    public void testCalculateNumDroppedConstraints() {
        setDeviceConfigString(KEY_FALLBACK_FLEXIBILITY_DEADLINES,
                "500=" + HOUR_IN_MILLIS
                        + ",400=" + 5 * HOUR_IN_MILLIS
                        + ",300=" + 8 * HOUR_IN_MILLIS
                        + ",200=" + 10 * HOUR_IN_MILLIS
                        + ",100=" + 20 * HOUR_IN_MILLIS);
        setDeviceConfigString(KEY_PERCENTS_TO_DROP_FLEXIBLE_CONSTRAINTS,
                "500=20|40|60|80"
                        + ",400=20|40|60|80"
                        + ",300=25|50|75|80"
                        + ",200=40|50|60|80"
                        + ",100=20|40|60|80");

        JobStatus jsHigh = createJobStatus("testCalculateNumDroppedConstraints",
                createJob(24).setPriority(JobInfo.PRIORITY_HIGH));
        JobStatus jsDefault = createJobStatus("testCalculateNumDroppedConstraints",
                createJob(23).setPriority(JobInfo.PRIORITY_DEFAULT));
        JobStatus jsLow = createJobStatus("testCalculateNumDroppedConstraints",
                createJob(22).setPriority(JobInfo.PRIORITY_LOW));
        JobStatus jsMin = createJobStatus("testCalculateNumDroppedConstraints",
                createJob(21).setPriority(JobInfo.PRIORITY_MIN));
        final long startElapsed = FROZEN_TIME;
        long nowElapsed = startElapsed;

        mFlexibilityController.mFlexibilityTracker.add(jsHigh);
        mFlexibilityController.mFlexibilityTracker.add(jsDefault);
        mFlexibilityController.mFlexibilityTracker.add(jsLow);
        mFlexibilityController.mFlexibilityTracker.add(jsMin);

        assertEquals(3, jsHigh.getNumRequiredFlexibleConstraints());
        assertEquals(0, jsHigh.getNumDroppedFlexibleConstraints());
        assertEquals(3, jsDefault.getNumRequiredFlexibleConstraints());
        assertEquals(0, jsDefault.getNumDroppedFlexibleConstraints());
        assertEquals(3, jsLow.getNumRequiredFlexibleConstraints());
        assertEquals(0, jsLow.getNumDroppedFlexibleConstraints());
        assertEquals(3, jsMin.getNumRequiredFlexibleConstraints());
        assertEquals(0, jsMin.getNumDroppedFlexibleConstraints());
        assertEquals(4, mFlexibilityController
                .mFlexibilityTracker.getJobsByNumRequiredConstraints(3).size());

        nowElapsed = startElapsed + HOUR_IN_MILLIS;
        JobSchedulerService.sElapsedRealtimeClock =
                Clock.fixed(Instant.ofEpochMilli(nowElapsed), ZoneOffset.UTC);

        mFlexibilityController.mFlexibilityTracker
                .calculateNumDroppedConstraints(jsHigh, nowElapsed);
        mFlexibilityController.mFlexibilityTracker
                .calculateNumDroppedConstraints(jsDefault, nowElapsed);
        mFlexibilityController.mFlexibilityTracker
                .calculateNumDroppedConstraints(jsLow, nowElapsed);
        mFlexibilityController.mFlexibilityTracker
                .calculateNumDroppedConstraints(jsMin, nowElapsed);

        assertEquals(2, jsHigh.getNumRequiredFlexibleConstraints());
        assertEquals(1, jsHigh.getNumDroppedFlexibleConstraints());
        assertEquals(3, jsDefault.getNumRequiredFlexibleConstraints());
        assertEquals(0, jsDefault.getNumDroppedFlexibleConstraints());
        assertEquals(3, jsLow.getNumRequiredFlexibleConstraints());
        assertEquals(0, jsLow.getNumDroppedFlexibleConstraints());
        assertEquals(3, jsMin.getNumRequiredFlexibleConstraints());
        assertEquals(0, jsMin.getNumDroppedFlexibleConstraints());
        assertEquals(3, mFlexibilityController
                .mFlexibilityTracker.getJobsByNumRequiredConstraints(3).size());
        assertEquals(1, mFlexibilityController
                .mFlexibilityTracker.getJobsByNumRequiredConstraints(2).size());

        nowElapsed = startElapsed;
        JobSchedulerService.sElapsedRealtimeClock =
                Clock.fixed(Instant.ofEpochMilli(nowElapsed), ZoneOffset.UTC);

        mFlexibilityController.mFlexibilityTracker
                .calculateNumDroppedConstraints(jsHigh, nowElapsed);
        mFlexibilityController.mFlexibilityTracker
                .calculateNumDroppedConstraints(jsDefault, nowElapsed);
        mFlexibilityController.mFlexibilityTracker
                .calculateNumDroppedConstraints(jsLow, nowElapsed);
        mFlexibilityController.mFlexibilityTracker
                .calculateNumDroppedConstraints(jsMin, nowElapsed);

        assertEquals(3, jsHigh.getNumRequiredFlexibleConstraints());
        assertEquals(0, jsHigh.getNumDroppedFlexibleConstraints());
        assertEquals(3, jsDefault.getNumRequiredFlexibleConstraints());
        assertEquals(0, jsDefault.getNumDroppedFlexibleConstraints());
        assertEquals(3, jsLow.getNumRequiredFlexibleConstraints());
        assertEquals(0, jsLow.getNumDroppedFlexibleConstraints());
        assertEquals(3, jsMin.getNumRequiredFlexibleConstraints());
        assertEquals(0, jsMin.getNumDroppedFlexibleConstraints());
        assertEquals(4, mFlexibilityController
                .mFlexibilityTracker.getJobsByNumRequiredConstraints(3).size());
        assertEquals(0, mFlexibilityController
                .mFlexibilityTracker.getJobsByNumRequiredConstraints(2).size());
        assertEquals(0, mFlexibilityController
                .mFlexibilityTracker.getJobsByNumRequiredConstraints(1).size());
        assertEquals(0, mFlexibilityController
                .mFlexibilityTracker.getJobsByNumRequiredConstraints(0).size());

        nowElapsed = startElapsed + 3 * HOUR_IN_MILLIS;
        JobSchedulerService.sElapsedRealtimeClock =
                Clock.fixed(Instant.ofEpochMilli(nowElapsed), ZoneOffset.UTC);

        mFlexibilityController.mFlexibilityTracker
                .calculateNumDroppedConstraints(jsHigh, nowElapsed);
        mFlexibilityController.mFlexibilityTracker
                .calculateNumDroppedConstraints(jsDefault, nowElapsed);
        mFlexibilityController.mFlexibilityTracker
                .calculateNumDroppedConstraints(jsLow, nowElapsed);
        mFlexibilityController.mFlexibilityTracker
                .calculateNumDroppedConstraints(jsMin, nowElapsed);

        assertEquals(0, jsHigh.getNumRequiredFlexibleConstraints());
        assertEquals(3, jsHigh.getNumDroppedFlexibleConstraints());
        assertEquals(2, jsDefault.getNumRequiredFlexibleConstraints());
        assertEquals(1, jsDefault.getNumDroppedFlexibleConstraints());
        assertEquals(3, jsLow.getNumRequiredFlexibleConstraints());
        assertEquals(0, jsLow.getNumDroppedFlexibleConstraints());
        assertEquals(3, jsMin.getNumRequiredFlexibleConstraints());
        assertEquals(0, jsMin.getNumDroppedFlexibleConstraints());
        assertEquals(2, mFlexibilityController
                .mFlexibilityTracker.getJobsByNumRequiredConstraints(3).size());
        assertEquals(1, mFlexibilityController
                .mFlexibilityTracker.getJobsByNumRequiredConstraints(2).size());
        assertEquals(0, mFlexibilityController
                .mFlexibilityTracker.getJobsByNumRequiredConstraints(1).size());
        assertEquals(1, mFlexibilityController
                .mFlexibilityTracker.getJobsByNumRequiredConstraints(0).size());

        nowElapsed = startElapsed + 4 * HOUR_IN_MILLIS;
        JobSchedulerService.sElapsedRealtimeClock =
                Clock.fixed(Instant.ofEpochMilli(nowElapsed), ZoneOffset.UTC);

        mFlexibilityController.mFlexibilityTracker
                .calculateNumDroppedConstraints(jsHigh, nowElapsed);
        mFlexibilityController.mFlexibilityTracker
                .calculateNumDroppedConstraints(jsDefault, nowElapsed);
        mFlexibilityController.mFlexibilityTracker
                .calculateNumDroppedConstraints(jsLow, nowElapsed);
        mFlexibilityController.mFlexibilityTracker
                .calculateNumDroppedConstraints(jsMin, nowElapsed);

        assertEquals(0, jsHigh.getNumRequiredFlexibleConstraints());
        assertEquals(3, jsHigh.getNumDroppedFlexibleConstraints());
        assertEquals(1, jsDefault.getNumRequiredFlexibleConstraints());
        assertEquals(2, jsDefault.getNumDroppedFlexibleConstraints());
        assertEquals(2, jsLow.getNumRequiredFlexibleConstraints());
        assertEquals(1, jsLow.getNumDroppedFlexibleConstraints());
        assertEquals(2, jsMin.getNumRequiredFlexibleConstraints());
        assertEquals(1, jsMin.getNumDroppedFlexibleConstraints());
        assertEquals(0, mFlexibilityController
                .mFlexibilityTracker.getJobsByNumRequiredConstraints(3).size());
        assertEquals(2, mFlexibilityController
                .mFlexibilityTracker.getJobsByNumRequiredConstraints(2).size());
        assertEquals(1, mFlexibilityController
                .mFlexibilityTracker.getJobsByNumRequiredConstraints(1).size());
        assertEquals(1, mFlexibilityController
                .mFlexibilityTracker.getJobsByNumRequiredConstraints(0).size());
    }

    @Test
    public void testOnPrefetchCacheUpdated() {
        ArraySet<JobStatus> jobs = new ArraySet<JobStatus>();
        JobInfo.Builder jb = createJob(22).setPrefetch(true);
        JobStatus js = createJobStatus("onPrefetchCacheUpdated", jb);
        jobs.add(js);
        doReturn(7 * HOUR_IN_MILLIS).when(mPrefetchController).getLaunchTimeThresholdMs();
        doReturn(1150L + mFlexibilityController.mConstants.PREFETCH_FORCE_BATCH_RELAX_THRESHOLD_MS)
                .when(mPrefetchController).getNextEstimatedLaunchTimeLocked(js);

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
                mFlexibilityController.getLifeCycleEndElapsedLocked(js, nowElapsed, 150L));
        assertEquals(0, mFlexibilityController.getCurPercentOfLifecycleLocked(js, FROZEN_TIME));
        assertEquals(650L, mFlexibilityController
                .getNextConstraintDropTimeElapsedLocked(js));
        assertEquals(3, js.getNumRequiredFlexibleConstraints());
        assertEquals(1, mFlexibilityController
                .mFlexibilityTracker.getJobsByNumRequiredConstraints(3).size());
    }

    @Test
    public void testScoreCalculation() {
        JobStatus jsMax = createJobStatus("testScoreCalculation",
                createJob(0).setExpedited(true).setPriority(JobInfo.PRIORITY_MAX));
        JobStatus jsHigh = createJobStatus("testScoreCalculation",
                createJob(0).setPriority(JobInfo.PRIORITY_HIGH));
        JobStatus jsDefault = createJobStatus("testScoreCalculation",
                createJob(0).setPriority(JobInfo.PRIORITY_DEFAULT));
        JobStatus jsLow = createJobStatus("testScoreCalculation",
                createJob(0).setPriority(JobInfo.PRIORITY_LOW));
        JobStatus jsMin = createJobStatus("testScoreCalculation",
                createJob(0).setPriority(JobInfo.PRIORITY_MIN));

        long nowElapsed = sElapsedRealtimeClock.millis();
        assertEquals(0,
                mFlexibilityController.getScoreLocked(mSourceUid, SOURCE_PACKAGE, nowElapsed));

        mFlexibilityController.prepareForExecutionLocked(jsMax);
        assertEquals(5,
                mFlexibilityController.getScoreLocked(mSourceUid, SOURCE_PACKAGE, nowElapsed));

        advanceElapsedClock(30 * MINUTE_IN_MILLIS);
        nowElapsed += 30 * MINUTE_IN_MILLIS;
        mFlexibilityController.prepareForExecutionLocked(jsMax);
        assertEquals(10,
                mFlexibilityController.getScoreLocked(mSourceUid, SOURCE_PACKAGE, nowElapsed));

        advanceElapsedClock(31 * MINUTE_IN_MILLIS);
        nowElapsed += 31 * MINUTE_IN_MILLIS;
        mFlexibilityController.prepareForExecutionLocked(jsHigh);
        assertEquals(14,
                mFlexibilityController.getScoreLocked(mSourceUid, SOURCE_PACKAGE, nowElapsed));

        advanceElapsedClock(2 * HOUR_IN_MILLIS);
        nowElapsed += 2 * HOUR_IN_MILLIS;
        mFlexibilityController.prepareForExecutionLocked(jsDefault);
        assertEquals(17,
                mFlexibilityController.getScoreLocked(mSourceUid, SOURCE_PACKAGE, nowElapsed));

        advanceElapsedClock(3 * HOUR_IN_MILLIS);
        nowElapsed += 3 * HOUR_IN_MILLIS;
        mFlexibilityController.prepareForExecutionLocked(jsLow);
        assertEquals(19,
                mFlexibilityController.getScoreLocked(mSourceUid, SOURCE_PACKAGE, nowElapsed));

        advanceElapsedClock(3 * HOUR_IN_MILLIS);
        nowElapsed += 3 * HOUR_IN_MILLIS;
        mFlexibilityController.prepareForExecutionLocked(jsMin);
        assertEquals(20,
                mFlexibilityController.getScoreLocked(mSourceUid, SOURCE_PACKAGE, nowElapsed));

        advanceElapsedClock(3 * HOUR_IN_MILLIS);
        nowElapsed += 3 * HOUR_IN_MILLIS;
        mFlexibilityController.prepareForExecutionLocked(jsMax);
        assertEquals(25,
                mFlexibilityController.getScoreLocked(mSourceUid, SOURCE_PACKAGE, nowElapsed));
        mFlexibilityController.unprepareFromExecutionLocked(jsMax);
        assertEquals(20,
                mFlexibilityController.getScoreLocked(mSourceUid, SOURCE_PACKAGE, nowElapsed));

        // 24 hours haven't passed yet. The jobs in the first hour bucket should still be included.
        advanceElapsedClock(12 * HOUR_IN_MILLIS - MINUTE_IN_MILLIS - 1);
        nowElapsed += 12 * HOUR_IN_MILLIS - MINUTE_IN_MILLIS - 1;
        assertEquals(20,
                mFlexibilityController.getScoreLocked(mSourceUid, SOURCE_PACKAGE, nowElapsed));

        // Passed the 24 hour mark. The jobs in the first hour bucket should no longer be included.
        advanceElapsedClock(2);
        nowElapsed += 2;
        assertEquals(10,
                mFlexibilityController.getScoreLocked(mSourceUid, SOURCE_PACKAGE, nowElapsed));

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
        doReturn(pkgs).when(mJobSchedulerService).getPackagesForUidLocked(mSourceUid);

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
    public void testUnsupportedDevice_Auto() {
        runTestUnsupportedDevice(PackageManager.FEATURE_AUTOMOTIVE);
    }

    @Test
    public void testUnsupportedDevice_Embedded() {
        runTestUnsupportedDevice(PackageManager.FEATURE_EMBEDDED);
    }

    private void runTestUnsupportedDevice(String feature) {
        doReturn(true).when(mPackageManager).hasSystemFeature(feature);
        mFlexibilityController =
                new FlexibilityController(mJobSchedulerService, mPrefetchController);
        assertFalse(mFlexibilityController.isEnabled());

        JobStatus js = createJobStatus("testUnsupportedDevice", createJob(0));

        mFlexibilityController.maybeStartTrackingJobLocked(js, null);
        assertTrue(js.isConstraintSatisfied(CONSTRAINT_FLEXIBLE));

        setDeviceConfigInt(KEY_APPLIED_CONSTRAINTS, FLEXIBLE_CONSTRAINTS);
        assertFalse(mFlexibilityController.isEnabled());

        ArrayList<ArraySet<JobStatus>> jobs =
                mFlexibilityController.mFlexibilityTracker.getArrayList();
        for (int i = 0; i < jobs.size(); i++) {
            assertEquals(0, jobs.get(i).size());
        }
    }

    private void setCarrierPrivilegedAppList(int logicalIndex, String... packages) {
        final ArraySet<String> packageSet = packages == null
                ? new ArraySet<>() : new ArraySet<>(packages);
        mCarrierPrivilegedApps.put(logicalIndex, packageSet);

        TelephonyManager.CarrierPrivilegesCallback callback =
                mCarrierPrivilegedCallbacks.get(logicalIndex);
        if (callback != null) {
            callback.onCarrierPrivilegesChanged(packageSet, Collections.emptySet());
            waitForQuietModuleThread();
        }
    }

    private void setPackageUid(final String pkgName, final int uid) throws Exception {
        doReturn(uid).when(mIPackageManager)
                .getPackageUid(eq(pkgName), anyLong(), eq(UserHandle.getUserId(uid)));
    }

    private void setPowerWhitelistExceptIdle(String... packages) {
        doReturn(packages == null ? EmptyArray.STRING : packages)
                .when(mDeviceIdleInternal).getFullPowerWhitelistExceptIdle();
        if (mBroadcastReceiver != null) {
            mBroadcastReceiver.onReceive(mContext,
                    new Intent(PowerManager.ACTION_POWER_SAVE_WHITELIST_CHANGED));
            waitForQuietModuleThread();
        }
    }

    private void setSimSlotMappings(@Nullable Collection<UiccSlotMapping> simSlotMapping) {
        clearInvocations(mTelephonyManager);
        final Collection<UiccSlotMapping> returnedMapping = simSlotMapping == null
                ? Collections.emptyList() : simSlotMapping;
        doReturn(returnedMapping).when(mTelephonyManager).getSimSlotMapping();
        if (mBroadcastReceiver != null) {
            final Intent intent = new Intent(TelephonyManager.ACTION_MULTI_SIM_CONFIG_CHANGED);
            mBroadcastReceiver.onReceive(mContext, intent);
            waitForQuietModuleThread();
        }
        if (returnedMapping.size() > 0) {
            ArgumentCaptor<TelephonyManager.CarrierPrivilegesCallback> callbackCaptor =
                    ArgumentCaptor.forClass(TelephonyManager.CarrierPrivilegesCallback.class);
            ArgumentCaptor<Integer> logicalIndexCaptor = ArgumentCaptor.forClass(Integer.class);

            final int minExpectedNewRegistrations = Math.max(0,
                    returnedMapping.size() - mCarrierPrivilegedCallbacks.size());
            verify(mTelephonyManager, atLeast(minExpectedNewRegistrations))
                    .registerCarrierPrivilegesCallback(
                            logicalIndexCaptor.capture(), any(), callbackCaptor.capture());

            final List<Integer> registeredIndices = logicalIndexCaptor.getAllValues();
            final List<TelephonyManager.CarrierPrivilegesCallback> registeredCallbacks =
                    callbackCaptor.getAllValues();
            for (int i = 0; i < registeredIndices.size(); ++i) {
                final int logicalIndex = registeredIndices.get(i);
                final TelephonyManager.CarrierPrivilegesCallback callback =
                        registeredCallbacks.get(i);

                mCarrierPrivilegedCallbacks.put(logicalIndex, callback);

                // The API contract promises a callback upon registration with the current list.
                final ArraySet<String> cpApps = mCarrierPrivilegedApps.get(logicalIndex);
                callback.onCarrierPrivilegesChanged(
                        cpApps == null ? Collections.emptySet() : cpApps,
                        Collections.emptySet());
            }
            waitForQuietModuleThread();
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
