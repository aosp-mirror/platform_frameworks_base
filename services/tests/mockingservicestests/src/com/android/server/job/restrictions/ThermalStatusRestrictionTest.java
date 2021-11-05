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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

import android.app.job.JobInfo;
import android.content.ComponentName;
import android.content.Context;
import android.os.PowerManager;
import android.provider.DeviceConfig;

import androidx.test.runner.AndroidJUnit4;

import com.android.server.LocalServices;
import com.android.server.job.JobSchedulerService;
import com.android.server.job.controllers.JobStatus;

import org.junit.After;
import org.junit.Before;
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
        inOrder.verify(mJobSchedulerService, never()).onControllerStateChanged(any());
        assertEquals(THERMAL_STATUS_NONE, mThermalStatusRestriction.getThermalStatus());

        // Moving within LOW and UPPER thresholds
        mStatusChangedListener.onThermalStatusChanged(THERMAL_STATUS_LIGHT);
        inOrder.verify(mJobSchedulerService).onControllerStateChanged(any());
        assertEquals(THERMAL_STATUS_LIGHT, mThermalStatusRestriction.getThermalStatus());

        mStatusChangedListener.onThermalStatusChanged(THERMAL_STATUS_MODERATE);
        inOrder.verify(mJobSchedulerService).onControllerStateChanged(any());
        assertEquals(THERMAL_STATUS_MODERATE, mThermalStatusRestriction.getThermalStatus());

        mStatusChangedListener.onThermalStatusChanged(THERMAL_STATUS_SEVERE);
        inOrder.verify(mJobSchedulerService).onControllerStateChanged(any());
        assertEquals(THERMAL_STATUS_SEVERE, mThermalStatusRestriction.getThermalStatus());

        // Changing outside of range
        mStatusChangedListener.onThermalStatusChanged(THERMAL_STATUS_CRITICAL);
        inOrder.verify(mJobSchedulerService, never()).onControllerStateChanged(any());
        assertEquals(THERMAL_STATUS_CRITICAL, mThermalStatusRestriction.getThermalStatus());

        mStatusChangedListener.onThermalStatusChanged(THERMAL_STATUS_EMERGENCY);
        inOrder.verify(mJobSchedulerService, never()).onControllerStateChanged(any());
        assertEquals(THERMAL_STATUS_EMERGENCY, mThermalStatusRestriction.getThermalStatus());

        mStatusChangedListener.onThermalStatusChanged(THERMAL_STATUS_SHUTDOWN);
        inOrder.verify(mJobSchedulerService, never()).onControllerStateChanged(any());
        assertEquals(THERMAL_STATUS_SHUTDOWN, mThermalStatusRestriction.getThermalStatus());

        // Cross values we care about
        mStatusChangedListener.onThermalStatusChanged(THERMAL_STATUS_NONE);
        inOrder.verify(mJobSchedulerService).onControllerStateChanged(any());
        assertEquals(THERMAL_STATUS_NONE, mThermalStatusRestriction.getThermalStatus());

        mStatusChangedListener.onThermalStatusChanged(THERMAL_STATUS_EMERGENCY);
        inOrder.verify(mJobSchedulerService).onControllerStateChanged(any());
        assertEquals(THERMAL_STATUS_EMERGENCY, mThermalStatusRestriction.getThermalStatus());
    }

    @Test
    public void testIsJobRestricted() {
        mStatusChangedListener.onThermalStatusChanged(THERMAL_STATUS_NONE);

        final JobStatus jobMinPriority = createJobStatus("testIsJobRestricted",
                createJobBuilder(1).setPriority(JobInfo.PRIORITY_MIN).build());
        final JobStatus jobLowPriority = createJobStatus("testIsJobRestricted",
                createJobBuilder(2).setPriority(JobInfo.PRIORITY_LOW).build());
        final JobStatus jobLowPriorityRunning = createJobStatus("testIsJobRestricted",
                createJobBuilder(3).setPriority(JobInfo.PRIORITY_LOW).build());
        final JobStatus jobDefaultPriority = createJobStatus("testIsJobRestricted",
                createJobBuilder(4).setPriority(JobInfo.PRIORITY_DEFAULT).build());
        final JobStatus jobHighPriority = createJobStatus("testIsJobRestricted",
                createJobBuilder(5).setPriority(JobInfo.PRIORITY_HIGH).build());
        final JobStatus jobHighPriorityRunning = createJobStatus("testIsJobRestricted",
                createJobBuilder(6).setPriority(JobInfo.PRIORITY_HIGH).build());
        final JobStatus ejDowngraded = createJobStatus("testIsJobRestricted",
                createJobBuilder(7).setExpedited(true).build());
        final JobStatus ej = spy(createJobStatus("testIsJobRestricted",
                createJobBuilder(8).setExpedited(true).build()));
        when(ej.shouldTreatAsExpeditedJob()).thenReturn(true);
        when(mJobSchedulerService.isCurrentlyRunningLocked(jobLowPriorityRunning)).thenReturn(true);
        when(mJobSchedulerService.isCurrentlyRunningLocked(jobHighPriorityRunning))
                .thenReturn(true);

        assertFalse(mThermalStatusRestriction.isJobRestricted(jobMinPriority));
        assertFalse(mThermalStatusRestriction.isJobRestricted(jobLowPriority));
        assertFalse(mThermalStatusRestriction.isJobRestricted(jobLowPriorityRunning));
        assertFalse(mThermalStatusRestriction.isJobRestricted(jobDefaultPriority));
        assertFalse(mThermalStatusRestriction.isJobRestricted(jobHighPriority));
        assertFalse(mThermalStatusRestriction.isJobRestricted(jobHighPriorityRunning));
        assertFalse(mThermalStatusRestriction.isJobRestricted(ej));
        assertFalse(mThermalStatusRestriction.isJobRestricted(ejDowngraded));

        mStatusChangedListener.onThermalStatusChanged(THERMAL_STATUS_LIGHT);

        assertTrue(mThermalStatusRestriction.isJobRestricted(jobMinPriority));
        assertTrue(mThermalStatusRestriction.isJobRestricted(jobLowPriority));
        assertFalse(mThermalStatusRestriction.isJobRestricted(jobLowPriorityRunning));
        assertFalse(mThermalStatusRestriction.isJobRestricted(jobDefaultPriority));
        assertFalse(mThermalStatusRestriction.isJobRestricted(jobHighPriority));
        assertFalse(mThermalStatusRestriction.isJobRestricted(jobHighPriorityRunning));
        assertFalse(mThermalStatusRestriction.isJobRestricted(ejDowngraded));
        assertFalse(mThermalStatusRestriction.isJobRestricted(ej));

        mStatusChangedListener.onThermalStatusChanged(THERMAL_STATUS_MODERATE);

        assertTrue(mThermalStatusRestriction.isJobRestricted(jobMinPriority));
        assertTrue(mThermalStatusRestriction.isJobRestricted(jobLowPriority));
        assertTrue(mThermalStatusRestriction.isJobRestricted(jobLowPriorityRunning));
        assertTrue(mThermalStatusRestriction.isJobRestricted(jobDefaultPriority));
        assertTrue(mThermalStatusRestriction.isJobRestricted(jobHighPriority));
        assertFalse(mThermalStatusRestriction.isJobRestricted(jobHighPriorityRunning));
        assertTrue(mThermalStatusRestriction.isJobRestricted(ejDowngraded));
        assertFalse(mThermalStatusRestriction.isJobRestricted(ej));

        mStatusChangedListener.onThermalStatusChanged(THERMAL_STATUS_SEVERE);

        assertTrue(mThermalStatusRestriction.isJobRestricted(jobMinPriority));
        assertTrue(mThermalStatusRestriction.isJobRestricted(jobLowPriority));
        assertTrue(mThermalStatusRestriction.isJobRestricted(jobLowPriorityRunning));
        assertTrue(mThermalStatusRestriction.isJobRestricted(jobDefaultPriority));
        assertTrue(mThermalStatusRestriction.isJobRestricted(jobHighPriority));
        assertTrue(mThermalStatusRestriction.isJobRestricted(jobHighPriorityRunning));
        assertTrue(mThermalStatusRestriction.isJobRestricted(ejDowngraded));
        assertTrue(mThermalStatusRestriction.isJobRestricted(ej));

        mStatusChangedListener.onThermalStatusChanged(THERMAL_STATUS_CRITICAL);

        assertTrue(mThermalStatusRestriction.isJobRestricted(jobMinPriority));
        assertTrue(mThermalStatusRestriction.isJobRestricted(jobLowPriority));
        assertTrue(mThermalStatusRestriction.isJobRestricted(jobLowPriorityRunning));
        assertTrue(mThermalStatusRestriction.isJobRestricted(jobDefaultPriority));
        assertTrue(mThermalStatusRestriction.isJobRestricted(jobHighPriority));
        assertTrue(mThermalStatusRestriction.isJobRestricted(jobHighPriorityRunning));
        assertTrue(mThermalStatusRestriction.isJobRestricted(ejDowngraded));
        assertTrue(mThermalStatusRestriction.isJobRestricted(ej));
    }

    private JobInfo.Builder createJobBuilder(int jobId) {
        return new JobInfo.Builder(jobId,
                new ComponentName(mContext, "ThermalStatusRestrictionTest"));
    }

    private JobStatus createJobStatus(String testTag, JobInfo jobInfo) {
        return JobStatus.createFromJobInfo(
                jobInfo, CALLING_UID, SOURCE_PACKAGE, SOURCE_USER_ID, testTag);
    }
}
