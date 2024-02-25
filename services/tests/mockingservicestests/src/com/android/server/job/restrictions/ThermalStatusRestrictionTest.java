/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.server.job.restrictions;

import static android.os.PowerManager.THERMAL_STATUS_CRITICAL;
import static android.os.PowerManager.THERMAL_STATUS_EMERGENCY;
import static android.os.PowerManager.THERMAL_STATUS_LIGHT;
import static android.os.PowerManager.THERMAL_STATUS_MODERATE;
import static android.os.PowerManager.THERMAL_STATUS_NONE;
import static android.os.PowerManager.THERMAL_STATUS_SEVERE;
import static android.os.PowerManager.THERMAL_STATUS_SHUTDOWN;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.inOrder;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.mock;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.mockitoSession;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.when;
import static com.android.server.job.Flags.FLAG_THERMAL_RESTRICTIONS_TO_FGS_JOBS;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

import android.app.job.JobInfo;
import android.content.ComponentName;
import android.content.Context;
import android.os.PowerManager;
import android.platform.test.annotations.RequiresFlagsDisabled;
import android.platform.test.annotations.RequiresFlagsEnabled;
import android.platform.test.flag.junit.CheckFlagsRule;
import android.platform.test.flag.junit.DeviceFlagsValueProvider;
import android.provider.DeviceConfig;
import android.util.DebugUtils;

import androidx.test.runner.AndroidJUnit4;

import com.android.server.LocalServices;
import com.android.server.job.JobSchedulerService;
import com.android.server.job.controllers.JobStatus;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.MockitoSession;
import org.mockito.quality.Strictness;

@RunWith(AndroidJUnit4.class)
public class ThermalStatusRestrictionTest {
    private static final int CALLING_UID = 1000;
    private static final String SOURCE_PACKAGE = "com.android.frameworks.mockingservicestests";
    private static final int SOURCE_USER_ID = 0;

    private ThermalStatusRestriction mThermalStatusRestriction;
    private PowerManager.OnThermalStatusChangedListener mStatusChangedListener;

    private MockitoSession mMockingSession;
    @Mock
    private Context mContext;
    @Mock
    private JobSchedulerService mJobSchedulerService;

    @Rule
    public final CheckFlagsRule mCheckFlagsRule = DeviceFlagsValueProvider.createCheckFlagsRule();

    class JobStatusContainer {
        public final JobStatus jobMinPriority;
        public final JobStatus jobLowPriority;
        public final JobStatus jobLowPriorityRunning;
        public final JobStatus jobLowPriorityRunningLong;
        public final JobStatus jobDefaultPriority;
        public final JobStatus jobHighPriority;
        public final JobStatus jobHighPriorityRunning;
        public final JobStatus jobHighPriorityRunningLong;
        public final JobStatus ejDowngraded;
        public final JobStatus ej;
        public final JobStatus ejRetried;
        public final JobStatus ejRunning;
        public final JobStatus ejRunningLong;
        public final JobStatus ui;
        public final JobStatus uiRetried;
        public final JobStatus uiRunning;
        public final JobStatus uiRunningLong;
        public final JobStatus importantWhileForeground;
        public final JobStatus importantWhileForegroundRunning;
        public final JobStatus importantWhileForegroundRunningLong;
        public final int[] allJobBiases = {
            JobInfo.BIAS_ADJ_ALWAYS_RUNNING,
            JobInfo.BIAS_ADJ_OFTEN_RUNNING,
            JobInfo.BIAS_DEFAULT,
            JobInfo.BIAS_SYNC_EXPEDITED,
            JobInfo.BIAS_SYNC_INITIALIZATION,
            JobInfo.BIAS_BOUND_FOREGROUND_SERVICE,
            JobInfo.BIAS_FOREGROUND_SERVICE,
            JobInfo.BIAS_TOP_APP
        };
        public final int[] biasesBelowFgs = {
            JobInfo.BIAS_ADJ_ALWAYS_RUNNING,
            JobInfo.BIAS_ADJ_OFTEN_RUNNING,
            JobInfo.BIAS_DEFAULT,
            JobInfo.BIAS_SYNC_EXPEDITED,
            JobInfo.BIAS_SYNC_INITIALIZATION,
            JobInfo.BIAS_BOUND_FOREGROUND_SERVICE
        };
        public final int[] thermalStatuses = {
            THERMAL_STATUS_NONE,
            THERMAL_STATUS_LIGHT,
            THERMAL_STATUS_MODERATE,
            THERMAL_STATUS_SEVERE,
            THERMAL_STATUS_CRITICAL,
            THERMAL_STATUS_EMERGENCY,
            THERMAL_STATUS_SHUTDOWN
        };

        JobStatusContainer(String jobName, JobSchedulerService mJobSchedulerService) {
            jobMinPriority =
                    createJobStatus(
                            jobName, createJobBuilder(1).setPriority(JobInfo.PRIORITY_MIN).build());
            jobLowPriority =
                    createJobStatus(
                            jobName, createJobBuilder(2).setPriority(JobInfo.PRIORITY_LOW).build());
            jobLowPriorityRunning =
                    createJobStatus(
                            jobName, createJobBuilder(3).setPriority(JobInfo.PRIORITY_LOW).build());
            jobLowPriorityRunningLong =
                    createJobStatus(
                            jobName, createJobBuilder(9).setPriority(JobInfo.PRIORITY_LOW).build());
            jobDefaultPriority =
                    createJobStatus(
                            jobName,
                            createJobBuilder(4).setPriority(JobInfo.PRIORITY_DEFAULT).build());
            jobHighPriority =
                    createJobStatus(
                            jobName,
                            createJobBuilder(5).setPriority(JobInfo.PRIORITY_HIGH).build());
            jobHighPriorityRunning =
                    createJobStatus(
                            jobName,
                            createJobBuilder(6).setPriority(JobInfo.PRIORITY_HIGH).build());
            jobHighPriorityRunningLong =
                    createJobStatus(
                            jobName,
                            createJobBuilder(10).setPriority(JobInfo.PRIORITY_HIGH).build());
            ejDowngraded = createJobStatus(jobName, createJobBuilder(7).setExpedited(true).build());
            ej = spy(createJobStatus(jobName, createJobBuilder(8).setExpedited(true).build()));
            ejRetried =
                    spy(createJobStatus(jobName, createJobBuilder(11).setExpedited(true).build()));
            ejRunning =
                    spy(createJobStatus(jobName, createJobBuilder(12).setExpedited(true).build()));
            ejRunningLong =
                    spy(createJobStatus(jobName, createJobBuilder(13).setExpedited(true).build()));
            ui = spy(createJobStatus(jobName, createJobBuilder(14).build()));
            uiRetried = spy(createJobStatus(jobName, createJobBuilder(15).build()));
            uiRunning = spy(createJobStatus(jobName, createJobBuilder(16).build()));
            uiRunningLong = spy(createJobStatus(jobName, createJobBuilder(17).build()));
            importantWhileForeground = spy(createJobStatus(jobName, createJobBuilder(18)
                    .setImportantWhileForeground(true)
                    .build()));
            importantWhileForegroundRunning = spy(createJobStatus(jobName, createJobBuilder(20)
                    .setImportantWhileForeground(true)
                     .build()));
            importantWhileForegroundRunningLong = spy(createJobStatus(jobName, createJobBuilder(19)
                     .setImportantWhileForeground(true)
                     .build()));

            when(ej.shouldTreatAsExpeditedJob()).thenReturn(true);
            when(ejRetried.shouldTreatAsExpeditedJob()).thenReturn(true);
            when(ejRunning.shouldTreatAsExpeditedJob()).thenReturn(true);
            when(ejRunningLong.shouldTreatAsExpeditedJob()).thenReturn(true);
            when(ui.shouldTreatAsUserInitiatedJob()).thenReturn(true);
            when(uiRetried.shouldTreatAsUserInitiatedJob()).thenReturn(true);
            when(uiRunning.shouldTreatAsUserInitiatedJob()).thenReturn(true);
            when(uiRunningLong.shouldTreatAsUserInitiatedJob()).thenReturn(true);
            when(ejRetried.getNumPreviousAttempts()).thenReturn(1);
            when(uiRetried.getNumPreviousAttempts()).thenReturn(2);
            when(mJobSchedulerService.isCurrentlyRunningLocked(jobLowPriorityRunning))
                    .thenReturn(true);
            when(mJobSchedulerService.isCurrentlyRunningLocked(jobHighPriorityRunning))
                    .thenReturn(true);
            when(mJobSchedulerService.isCurrentlyRunningLocked(jobLowPriorityRunningLong))
                    .thenReturn(true);
            when(mJobSchedulerService.isCurrentlyRunningLocked(jobHighPriorityRunningLong))
                    .thenReturn(true);
            when(mJobSchedulerService.isCurrentlyRunningLocked(ejRunning)).thenReturn(true);
            when(mJobSchedulerService.isCurrentlyRunningLocked(ejRunningLong)).thenReturn(true);
            when(mJobSchedulerService.isCurrentlyRunningLocked(uiRunning)).thenReturn(true);
            when(mJobSchedulerService.isCurrentlyRunningLocked(uiRunningLong)).thenReturn(true);
            when(mJobSchedulerService.isJobInOvertimeLocked(jobLowPriorityRunningLong))
                    .thenReturn(true);
            when(mJobSchedulerService.isCurrentlyRunningLocked(importantWhileForegroundRunning))
                    .thenReturn(true);
            when(mJobSchedulerService.isJobInOvertimeLocked(jobHighPriorityRunningLong))
                    .thenReturn(true);
            when(mJobSchedulerService.isJobInOvertimeLocked(ejRunningLong)).thenReturn(true);
            when(mJobSchedulerService.isJobInOvertimeLocked(uiRunningLong)).thenReturn(true);
            when(mJobSchedulerService.isCurrentlyRunningLocked(importantWhileForegroundRunningLong))
                    .thenReturn(true);
            when(mJobSchedulerService.isJobInOvertimeLocked(importantWhileForegroundRunningLong))
                    .thenReturn(true);
        }
    }

    private boolean isJobRestricted(JobStatus status, int bias) {
        return mThermalStatusRestriction.isJobRestricted(status, bias);
    }

    private static String debugTag(int bias, @PowerManager.ThermalStatus int status) {
        return "Bias = "
                + JobInfo.getBiasString(bias)
                + " Thermal Status = "
                + DebugUtils.valueToString(PowerManager.class, "THERMAL_STATUS_", status);
    }

    @Before
    public void setUp() {
        mMockingSession = mockitoSession()
                .initMocks(this)
                .strictness(Strictness.LENIENT)
                .spyStatic(DeviceConfig.class)
                .mockStatic(LocalServices.class)
                .startMocking();

        when(mJobSchedulerService.getTestableContext()).thenReturn(mContext);
        when(mJobSchedulerService.getLock()).thenReturn(mJobSchedulerService);

        PowerManager powerManager = mock(PowerManager.class);
        when(mContext.getSystemService(PowerManager.class)).thenReturn(powerManager);
        // Initialize real objects.
        // Capture the listeners.
        ArgumentCaptor<PowerManager.OnThermalStatusChangedListener> listenerCaptor =
                ArgumentCaptor.forClass(PowerManager.OnThermalStatusChangedListener.class);
        mThermalStatusRestriction = new ThermalStatusRestriction(mJobSchedulerService);
        mThermalStatusRestriction.onSystemServicesReady();

        verify(powerManager).addThermalStatusListener(listenerCaptor.capture());
        mStatusChangedListener = listenerCaptor.getValue();
    }

    @After
    public void tearDown() {
        if (mMockingSession != null) {
            mMockingSession.finishMocking();
        }
    }

    @Test
    public void testStatusChanges() {
        InOrder inOrder = inOrder(mJobSchedulerService);

        mStatusChangedListener.onThermalStatusChanged(THERMAL_STATUS_NONE);
        inOrder.verify(mJobSchedulerService, never())
                .onRestrictionStateChanged(any(), anyBoolean());
        assertEquals(THERMAL_STATUS_NONE, mThermalStatusRestriction.getThermalStatus());

        // Moving within LOW and UPPER thresholds
        mStatusChangedListener.onThermalStatusChanged(THERMAL_STATUS_LIGHT);
        inOrder.verify(mJobSchedulerService).onRestrictionStateChanged(any(), eq(true));
        assertEquals(THERMAL_STATUS_LIGHT, mThermalStatusRestriction.getThermalStatus());

        mStatusChangedListener.onThermalStatusChanged(THERMAL_STATUS_MODERATE);
        inOrder.verify(mJobSchedulerService).onRestrictionStateChanged(any(), eq(true));
        assertEquals(THERMAL_STATUS_MODERATE, mThermalStatusRestriction.getThermalStatus());

        mStatusChangedListener.onThermalStatusChanged(THERMAL_STATUS_SEVERE);
        inOrder.verify(mJobSchedulerService).onRestrictionStateChanged(any(), eq(true));
        assertEquals(THERMAL_STATUS_SEVERE, mThermalStatusRestriction.getThermalStatus());

        // Changing outside of range
        mStatusChangedListener.onThermalStatusChanged(THERMAL_STATUS_CRITICAL);
        inOrder.verify(mJobSchedulerService, never())
                .onRestrictionStateChanged(any(), eq(true));
        assertEquals(THERMAL_STATUS_CRITICAL, mThermalStatusRestriction.getThermalStatus());

        mStatusChangedListener.onThermalStatusChanged(THERMAL_STATUS_EMERGENCY);
        inOrder.verify(mJobSchedulerService, never())
                .onRestrictionStateChanged(any(), anyBoolean());
        assertEquals(THERMAL_STATUS_EMERGENCY, mThermalStatusRestriction.getThermalStatus());

        mStatusChangedListener.onThermalStatusChanged(THERMAL_STATUS_SHUTDOWN);
        inOrder.verify(mJobSchedulerService, never())
                .onRestrictionStateChanged(any(), anyBoolean());
        assertEquals(THERMAL_STATUS_SHUTDOWN, mThermalStatusRestriction.getThermalStatus());

        // Cross values we care about
        mStatusChangedListener.onThermalStatusChanged(THERMAL_STATUS_NONE);
        inOrder.verify(mJobSchedulerService).onRestrictionStateChanged(any(), eq(false));
        assertEquals(THERMAL_STATUS_NONE, mThermalStatusRestriction.getThermalStatus());

        mStatusChangedListener.onThermalStatusChanged(THERMAL_STATUS_EMERGENCY);
        inOrder.verify(mJobSchedulerService).onRestrictionStateChanged(any(), eq(true));
        assertEquals(THERMAL_STATUS_EMERGENCY, mThermalStatusRestriction.getThermalStatus());
    }

    /**
     * Test {@link JobSchedulerService#isJobRestricted(JobStatus)} when Thermal is in default state
     */
    @Test
    public void testIsJobRestrictedDefaultStates() {
        mStatusChangedListener.onThermalStatusChanged(THERMAL_STATUS_NONE);
        JobStatusContainer jc = new JobStatusContainer("testIsJobRestricted", mJobSchedulerService);

        for (int jobBias : jc.allJobBiases) {
            assertFalse(isJobRestricted(jc.jobMinPriority, jobBias));
            assertFalse(isJobRestricted(jc.jobLowPriority, jobBias));
            assertFalse(isJobRestricted(jc.jobLowPriorityRunning, jobBias));
            assertFalse(isJobRestricted(jc.jobLowPriorityRunningLong, jobBias));
            assertFalse(isJobRestricted(jc.jobDefaultPriority, jobBias));
            assertFalse(isJobRestricted(jc.jobHighPriority, jobBias));
            assertFalse(isJobRestricted(jc.jobHighPriorityRunning, jobBias));
            assertFalse(isJobRestricted(jc.jobHighPriorityRunningLong, jobBias));
            assertFalse(isJobRestricted(jc.importantWhileForeground, jobBias));
            assertFalse(isJobRestricted(jc.importantWhileForegroundRunning, jobBias));
            assertFalse(isJobRestricted(jc.importantWhileForegroundRunningLong, jobBias));
            assertFalse(isJobRestricted(jc.ej, jobBias));
            assertFalse(isJobRestricted(jc.ejDowngraded, jobBias));
            assertFalse(isJobRestricted(jc.ejRetried, jobBias));
            assertFalse(isJobRestricted(jc.ejRunning, jobBias));
            assertFalse(isJobRestricted(jc.ejRunningLong, jobBias));
            assertFalse(isJobRestricted(jc.ui, jobBias));
            assertFalse(isJobRestricted(jc.uiRetried, jobBias));
            assertFalse(isJobRestricted(jc.uiRunning, jobBias));
            assertFalse(isJobRestricted(jc.uiRunningLong, jobBias));
        }
    }

    /**
     * Test {@link JobSchedulerService#isJobRestricted(JobStatus)} when Job Bias is Top App and all
     * Thermal states.
     */
    @Test
    public void testIsJobRestrictedBiasTopApp() {
        JobStatusContainer jc =
                new JobStatusContainer("testIsJobRestrictedBiasTopApp", mJobSchedulerService);

        int jobBias = JobInfo.BIAS_TOP_APP;
        for (int thermalStatus : jc.thermalStatuses) {
            String msg = "Thermal Status = " + DebugUtils.valueToString(
                    PowerManager.class, "THERMAL_STATUS_", thermalStatus);
            mStatusChangedListener.onThermalStatusChanged(thermalStatus);

            // No restrictions on any jobs
            assertFalse(msg, isJobRestricted(jc.jobMinPriority, jobBias));
            assertFalse(msg, isJobRestricted(jc.jobLowPriority, jobBias));
            assertFalse(msg, isJobRestricted(jc.jobLowPriorityRunning, jobBias));
            assertFalse(msg, isJobRestricted(jc.jobLowPriorityRunningLong, jobBias));
            assertFalse(msg, isJobRestricted(jc.jobDefaultPriority, jobBias));
            assertFalse(msg, isJobRestricted(jc.jobHighPriority, jobBias));
            assertFalse(msg, isJobRestricted(jc.jobHighPriorityRunning, jobBias));
            assertFalse(msg, isJobRestricted(jc.jobHighPriorityRunningLong, jobBias));
            assertFalse(msg, isJobRestricted(jc.importantWhileForeground, jobBias));
            assertFalse(msg, isJobRestricted(jc.importantWhileForegroundRunning, jobBias));
            assertFalse(msg, isJobRestricted(jc.importantWhileForegroundRunningLong, jobBias));
            assertFalse(msg, isJobRestricted(jc.ej, jobBias));
            assertFalse(msg, isJobRestricted(jc.ejDowngraded, jobBias));
            assertFalse(msg, isJobRestricted(jc.ejRetried, jobBias));
            assertFalse(msg, isJobRestricted(jc.ejRunning, jobBias));
            assertFalse(msg, isJobRestricted(jc.ejRunningLong, jobBias));
            assertFalse(msg, isJobRestricted(jc.ui, jobBias));
            assertFalse(msg, isJobRestricted(jc.uiRetried, jobBias));
            assertFalse(msg, isJobRestricted(jc.uiRunning, jobBias));
            assertFalse(msg, isJobRestricted(jc.uiRunningLong, jobBias));
        }
    }

    /**
     * Test {@link JobSchedulerService#isJobRestricted(JobStatus)} when Job Bias is Foreground
     * Service and all Thermal states.
     */
    @Test
    @RequiresFlagsDisabled(FLAG_THERMAL_RESTRICTIONS_TO_FGS_JOBS)
    public void testIsJobRestrictedBiasFgs_flagThermalRestrictionsToFgsJobsDisabled() {
        JobStatusContainer jc =
                new JobStatusContainer("testIsJobRestrictedBiasFgs", mJobSchedulerService);

        int jobBias = JobInfo.BIAS_FOREGROUND_SERVICE;
        for (int thermalStatus : jc.thermalStatuses) {
            String msg = "Thermal Status = " + DebugUtils.valueToString(
                    PowerManager.class, "THERMAL_STATUS_", thermalStatus);
            mStatusChangedListener.onThermalStatusChanged(thermalStatus);
            // No restrictions on any jobs
            assertFalse(msg, isJobRestricted(jc.jobMinPriority, jobBias));
            assertFalse(msg, isJobRestricted(jc.jobLowPriority, jobBias));
            assertFalse(msg, isJobRestricted(jc.jobLowPriorityRunning, jobBias));
            assertFalse(msg, isJobRestricted(jc.jobLowPriorityRunningLong, jobBias));
            assertFalse(msg, isJobRestricted(jc.jobDefaultPriority, jobBias));
            assertFalse(msg, isJobRestricted(jc.jobHighPriority, jobBias));
            assertFalse(msg, isJobRestricted(jc.jobHighPriorityRunning, jobBias));
            assertFalse(msg, isJobRestricted(jc.jobHighPriorityRunningLong, jobBias));
            assertFalse(msg, isJobRestricted(jc.ej, jobBias));
            assertFalse(msg, isJobRestricted(jc.ejDowngraded, jobBias));
            assertFalse(msg, isJobRestricted(jc.ejRetried, jobBias));
            assertFalse(msg, isJobRestricted(jc.ejRunning, jobBias));
            assertFalse(msg, isJobRestricted(jc.ejRunningLong, jobBias));
            assertFalse(msg, isJobRestricted(jc.ui, jobBias));
            assertFalse(msg, isJobRestricted(jc.uiRetried, jobBias));
            assertFalse(msg, isJobRestricted(jc.uiRunning, jobBias));
            assertFalse(msg, isJobRestricted(jc.uiRunningLong, jobBias));
        }
    }

    /**
     * Test {@link JobSchedulerService#isJobRestricted(JobStatus)} when Job Bias is Foreground
     * Service and all Thermal states.
     */
    @Test
    @RequiresFlagsEnabled(FLAG_THERMAL_RESTRICTIONS_TO_FGS_JOBS)
    public void testIsJobRestrictedBiasFgs_flagThermalRestrictionsToFgsJobsEnabled() {
        JobStatusContainer jc =
                new JobStatusContainer("testIsJobRestrictedBiasFgs", mJobSchedulerService);
        int jobBias = JobInfo.BIAS_FOREGROUND_SERVICE;
        for (int thermalStatus : jc.thermalStatuses) {
            String msg = debugTag(jobBias, thermalStatus);
            mStatusChangedListener.onThermalStatusChanged(thermalStatus);
            if (thermalStatus >= THERMAL_STATUS_SEVERE) {
                // Full restrictions on all jobs
                assertTrue(msg, isJobRestricted(jc.jobMinPriority, jobBias));
                assertTrue(msg, isJobRestricted(jc.jobLowPriority, jobBias));
                assertTrue(msg, isJobRestricted(jc.jobLowPriorityRunning, jobBias));
                assertTrue(msg, isJobRestricted(jc.jobLowPriorityRunningLong, jobBias));
                assertTrue(msg, isJobRestricted(jc.jobDefaultPriority, jobBias));
                assertTrue(msg, isJobRestricted(jc.jobHighPriority, jobBias));
                assertTrue(msg, isJobRestricted(jc.jobHighPriorityRunning, jobBias));
                assertTrue(msg, isJobRestricted(jc.jobHighPriorityRunningLong, jobBias));
                assertTrue(msg, isJobRestricted(jc.ej, jobBias));
                assertTrue(msg, isJobRestricted(jc.ejDowngraded, jobBias));
                assertTrue(msg, isJobRestricted(jc.ejRetried, jobBias));
                assertTrue(msg, isJobRestricted(jc.ejRunning, jobBias));
                assertTrue(msg, isJobRestricted(jc.ejRunningLong, jobBias));
                assertTrue(msg, isJobRestricted(jc.ui, jobBias));
                assertTrue(msg, isJobRestricted(jc.uiRetried, jobBias));
                assertTrue(msg, isJobRestricted(jc.uiRunning, jobBias));
                assertTrue(msg, isJobRestricted(jc.uiRunningLong, jobBias));
            } else if (thermalStatus >= THERMAL_STATUS_MODERATE) {
                // No restrictions on user related jobs
                assertFalse(msg, isJobRestricted(jc.ui, jobBias));
                assertFalse(msg, isJobRestricted(jc.uiRetried, jobBias));
                assertFalse(msg, isJobRestricted(jc.uiRunning, jobBias));
                assertFalse(msg, isJobRestricted(jc.uiRunningLong, jobBias));
                // Some restrictions on expedited jobs
                assertFalse(msg, isJobRestricted(jc.ej, jobBias));
                assertTrue(msg, isJobRestricted(jc.ejDowngraded, jobBias));
                assertTrue(msg, isJobRestricted(jc.ejRetried, jobBias));
                assertFalse(msg, isJobRestricted(jc.ejRunning, jobBias));
                assertTrue(msg, isJobRestricted(jc.ejRunningLong, jobBias));
                // Some restrictions on high priority jobs
                assertTrue(msg, isJobRestricted(jc.jobHighPriority, jobBias));
                assertFalse(msg, isJobRestricted(jc.jobHighPriorityRunning, jobBias));
                assertTrue(msg, isJobRestricted(jc.jobHighPriorityRunningLong, jobBias));
                // Some restructions on important while foreground jobs
                assertFalse(isJobRestricted(jc.importantWhileForeground, jobBias));
                assertFalse(isJobRestricted(jc.importantWhileForegroundRunning, jobBias));
                assertTrue(isJobRestricted(jc.importantWhileForegroundRunningLong, jobBias));
                // Full restriction on default priority jobs
                assertTrue(msg, isJobRestricted(jc.jobDefaultPriority, jobBias));
                // Full restriction on low priority jobs
                assertTrue(msg, isJobRestricted(jc.jobLowPriority, jobBias));
                assertTrue(msg, isJobRestricted(jc.jobLowPriorityRunning, jobBias));
                assertTrue(msg, isJobRestricted(jc.jobLowPriorityRunningLong, jobBias));
                // Full restriction on min priority jobs
                assertTrue(msg, isJobRestricted(jc.jobMinPriority, jobBias));
            } else {
                // thermalStatus < THERMAL_STATUS_MODERATE
                // No restrictions on any job type
                assertFalse(msg, isJobRestricted(jc.ui, jobBias));
                assertFalse(msg, isJobRestricted(jc.uiRetried, jobBias));
                assertFalse(msg, isJobRestricted(jc.uiRunning, jobBias));
                assertFalse(msg, isJobRestricted(jc.uiRunningLong, jobBias));
                assertFalse(msg, isJobRestricted(jc.ej, jobBias));
                assertFalse(msg, isJobRestricted(jc.ejDowngraded, jobBias));
                assertFalse(msg, isJobRestricted(jc.ejRetried, jobBias));
                assertFalse(msg, isJobRestricted(jc.ejRunning, jobBias));
                assertFalse(msg, isJobRestricted(jc.ejRunningLong, jobBias));
                assertFalse(msg, isJobRestricted(jc.jobHighPriority, jobBias));
                assertFalse(msg, isJobRestricted(jc.jobHighPriorityRunning, jobBias));
                assertFalse(msg, isJobRestricted(jc.jobHighPriorityRunningLong, jobBias));
                assertFalse(msg, isJobRestricted(jc.importantWhileForeground, jobBias));
                assertFalse(msg, isJobRestricted(jc.importantWhileForegroundRunning, jobBias));
                assertFalse(msg, isJobRestricted(jc.importantWhileForegroundRunningLong, jobBias));
                assertFalse(msg, isJobRestricted(jc.jobDefaultPriority, jobBias));
                assertFalse(msg, isJobRestricted(jc.jobLowPriority, jobBias));
                assertFalse(msg, isJobRestricted(jc.jobLowPriorityRunning, jobBias));
                assertFalse(msg, isJobRestricted(jc.jobLowPriorityRunningLong, jobBias));
                assertFalse(msg, isJobRestricted(jc.jobMinPriority, jobBias));
            }
        }
    }

    /**
     * Test {@link JobSchedulerService#isJobRestricted(JobStatus)} when Job Bias is less than
     * Foreground Service and all Thermal states.
     */
    @Test
    public void testIsJobRestrictedBiasLessThanFgs() {
        JobStatusContainer jc =
                new JobStatusContainer("testIsJobRestrictedBiasLessThanFgs", mJobSchedulerService);

        for (int jobBias : jc.biasesBelowFgs) {
            for (int thermalStatus : jc.thermalStatuses) {
                String msg = debugTag(jobBias, thermalStatus);
                mStatusChangedListener.onThermalStatusChanged(thermalStatus);
                if (thermalStatus >= THERMAL_STATUS_SEVERE) {
                    // Full restrictions on all jobs
                    assertTrue(msg, isJobRestricted(jc.jobMinPriority, jobBias));
                    assertTrue(msg, isJobRestricted(jc.jobLowPriority, jobBias));
                    assertTrue(msg, isJobRestricted(jc.jobLowPriorityRunning, jobBias));
                    assertTrue(msg, isJobRestricted(jc.jobLowPriorityRunningLong, jobBias));
                    assertTrue(msg, isJobRestricted(jc.jobDefaultPriority, jobBias));
                    assertTrue(msg, isJobRestricted(jc.jobHighPriority, jobBias));
                    assertTrue(msg, isJobRestricted(jc.jobHighPriorityRunning, jobBias));
                    assertTrue(msg, isJobRestricted(jc.jobHighPriorityRunningLong, jobBias));
                    assertTrue(msg, isJobRestricted(jc.ej, jobBias));
                    assertTrue(msg, isJobRestricted(jc.ejDowngraded, jobBias));
                    assertTrue(msg, isJobRestricted(jc.ejRetried, jobBias));
                    assertTrue(msg, isJobRestricted(jc.ejRunning, jobBias));
                    assertTrue(msg, isJobRestricted(jc.ejRunningLong, jobBias));
                    assertTrue(msg, isJobRestricted(jc.ui, jobBias));
                    assertTrue(msg, isJobRestricted(jc.uiRetried, jobBias));
                    assertTrue(msg, isJobRestricted(jc.uiRunning, jobBias));
                    assertTrue(msg, isJobRestricted(jc.uiRunningLong, jobBias));
                } else if (thermalStatus >= THERMAL_STATUS_MODERATE) {
                    // No restrictions on user related jobs
                    assertFalse(msg, isJobRestricted(jc.ui, jobBias));
                    assertFalse(msg, isJobRestricted(jc.uiRetried, jobBias));
                    assertFalse(msg, isJobRestricted(jc.uiRunning, jobBias));
                    assertFalse(msg, isJobRestricted(jc.uiRunningLong, jobBias));
                    // Some restrictions on expedited jobs
                    assertFalse(msg, isJobRestricted(jc.ej, jobBias));
                    assertTrue(msg, isJobRestricted(jc.ejDowngraded, jobBias));
                    assertTrue(msg, isJobRestricted(jc.ejRetried, jobBias));
                    assertFalse(msg, isJobRestricted(jc.ejRunning, jobBias));
                    assertTrue(msg, isJobRestricted(jc.ejRunningLong, jobBias));
                    // Some restrictions on high priority jobs
                    assertTrue(msg, isJobRestricted(jc.jobHighPriority, jobBias));
                    assertFalse(msg, isJobRestricted(jc.jobHighPriorityRunning, jobBias));
                    assertTrue(msg, isJobRestricted(jc.jobHighPriorityRunningLong, jobBias));
                    // Full restriction on default priority jobs
                    assertTrue(msg, isJobRestricted(jc.jobDefaultPriority, jobBias));
                    // Full restriction on low priority jobs
                    assertTrue(msg, isJobRestricted(jc.jobLowPriority, jobBias));
                    assertTrue(msg, isJobRestricted(jc.jobLowPriorityRunning, jobBias));
                    assertTrue(msg, isJobRestricted(jc.jobLowPriorityRunningLong, jobBias));
                    // Full restriction on min priority jobs
                    assertTrue(msg, isJobRestricted(jc.jobMinPriority, jobBias));
                } else if (thermalStatus >= THERMAL_STATUS_LIGHT) {
                    // No restrictions on any user related jobs
                    assertFalse(msg, isJobRestricted(jc.ui, jobBias));
                    assertFalse(msg, isJobRestricted(jc.uiRetried, jobBias));
                    assertFalse(msg, isJobRestricted(jc.uiRunning, jobBias));
                    assertFalse(msg, isJobRestricted(jc.uiRunningLong, jobBias));
                    // No restrictions on any expedited jobs
                    assertFalse(msg, isJobRestricted(jc.ej, jobBias));
                    assertFalse(msg, isJobRestricted(jc.ejDowngraded, jobBias));
                    assertFalse(msg, isJobRestricted(jc.ejRetried, jobBias));
                    assertFalse(msg, isJobRestricted(jc.ejRunning, jobBias));
                    assertFalse(msg, isJobRestricted(jc.ejRunningLong, jobBias));
                    // No restrictions on any high priority jobs
                    assertFalse(msg, isJobRestricted(jc.jobHighPriority, jobBias));
                    assertFalse(msg, isJobRestricted(jc.jobHighPriorityRunning, jobBias));
                    assertFalse(msg, isJobRestricted(jc.jobHighPriorityRunningLong, jobBias));
                    // No restrictions on default priority jobs
                    assertFalse(msg, isJobRestricted(jc.jobDefaultPriority, jobBias));
                    // Some restrictions on low priority jobs
                    assertTrue(msg, isJobRestricted(jc.jobLowPriority, jobBias));
                    assertFalse(msg, isJobRestricted(jc.jobLowPriorityRunning, jobBias));
                    assertTrue(msg, isJobRestricted(jc.jobLowPriorityRunningLong, jobBias));
                    // Full restriction on min priority jobs
                    assertTrue(msg, isJobRestricted(jc.jobMinPriority, jobBias));
                } else { // THERMAL_STATUS_NONE
                    // No restrictions on any jobs
                    assertFalse(msg, isJobRestricted(jc.jobMinPriority, jobBias));
                    assertFalse(msg, isJobRestricted(jc.jobLowPriority, jobBias));
                    assertFalse(msg, isJobRestricted(jc.jobLowPriorityRunning, jobBias));
                    assertFalse(msg, isJobRestricted(jc.jobLowPriorityRunningLong, jobBias));
                    assertFalse(msg, isJobRestricted(jc.jobDefaultPriority, jobBias));
                    assertFalse(msg, isJobRestricted(jc.jobHighPriority, jobBias));
                    assertFalse(msg, isJobRestricted(jc.jobHighPriorityRunning, jobBias));
                    assertFalse(msg, isJobRestricted(jc.jobHighPriorityRunningLong, jobBias));
                    assertFalse(msg, isJobRestricted(jc.ej, jobBias));
                    assertFalse(msg, isJobRestricted(jc.ejDowngraded, jobBias));
                    assertFalse(msg, isJobRestricted(jc.ejRetried, jobBias));
                    assertFalse(msg, isJobRestricted(jc.ejRunning, jobBias));
                    assertFalse(msg, isJobRestricted(jc.ejRunningLong, jobBias));
                    assertFalse(msg, isJobRestricted(jc.ui, jobBias));
                    assertFalse(msg, isJobRestricted(jc.uiRetried, jobBias));
                    assertFalse(msg, isJobRestricted(jc.uiRunning, jobBias));
                    assertFalse(msg, isJobRestricted(jc.uiRunningLong, jobBias));
                }
            }
        }
    }

    private JobInfo.Builder createJobBuilder(int jobId) {
        return new JobInfo.Builder(jobId,
                new ComponentName(mContext, "ThermalStatusRestrictionTest"));
    }

    private JobStatus createJobStatus(String testTag, JobInfo jobInfo) {
        return JobStatus.createFromJobInfo(
                jobInfo, CALLING_UID, SOURCE_PACKAGE, SOURCE_USER_ID, "TSRTest", testTag);
    }
}
