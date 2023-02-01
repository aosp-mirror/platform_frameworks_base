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
package com.android.internal.os.anr;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.Before;
import org.junit.Test;

/**
 * Tests for {@link AnrLatencyTracekr.java}.
 *
 */
public class AnrLatencyTrackerTests {

    private AnrLatencyTracker mLatencyTracker;

    @Before
    public void setup() {
        mLatencyTracker = spy(new AnrLatencyTracker(0, 50L));
        when(mLatencyTracker.getUptimeMillis())
            .thenReturn(55L)
            .thenReturn(60L)
            .thenReturn(70L)
            .thenReturn(80L)
            .thenReturn(88L)
            .thenReturn(99L)
            .thenReturn(101L)
            .thenReturn(105L)
            .thenReturn(108L)
            .thenReturn(110L)
            .thenReturn(117L)
            .thenReturn(121L)
            .thenReturn(129L)
            .thenReturn(135L)
            .thenReturn(150L)
            .thenReturn(155L)
            .thenReturn(157L)
            .thenReturn(158L)
            .thenReturn(165L)
            .thenReturn(175L)
            .thenReturn(198L)
            .thenReturn(203L)
            .thenReturn(209L);
    }

    @Test
    public void testNormalScenario() {

        mLatencyTracker.appNotRespondingStarted();
        mLatencyTracker.waitingOnAnrRecordLockStarted();
        mLatencyTracker.waitingOnAnrRecordLockEnded();
        mLatencyTracker.anrRecordPlacingOnQueueWithSize(3);
        mLatencyTracker.appNotRespondingEnded();

        mLatencyTracker.anrProcessingStarted();
        mLatencyTracker.updateCpuStatsNowCalled();
        mLatencyTracker.updateCpuStatsNowReturned();
        mLatencyTracker.currentPsiStateCalled();
        mLatencyTracker.currentPsiStateReturned();
        mLatencyTracker.processCpuTrackerMethodsCalled();
        mLatencyTracker.processCpuTrackerMethodsReturned();
        mLatencyTracker.criticalEventLogStarted();
        mLatencyTracker.criticalEventLogEnded();

        mLatencyTracker.waitingOnGlobalLockStarted();
        mLatencyTracker.waitingOnGlobalLockEnded();
        mLatencyTracker.waitingOnPidLockStarted();
        mLatencyTracker.waitingOnPidLockEnded();
        mLatencyTracker.waitingOnAMSLockStarted();
        mLatencyTracker.waitingOnAMSLockEnded();
        mLatencyTracker.waitingOnProcLockStarted();
        mLatencyTracker.waitingOnProcLockEnded();

        mLatencyTracker.dumpStackTracesStarted();
        mLatencyTracker.dumpingFirstPidsStarted();
        mLatencyTracker.dumpingPidStarted(1);
        mLatencyTracker.dumpingPidEnded();
        mLatencyTracker.dumpingFirstPidsEnded();
        mLatencyTracker.dumpingNativePidsStarted();
        mLatencyTracker.dumpingPidStarted(3);
        mLatencyTracker.dumpingPidEnded();
        mLatencyTracker.dumpingNativePidsEnded();
        mLatencyTracker.dumpingExtraPidsStarted();
        mLatencyTracker.dumpingPidStarted(10);
        mLatencyTracker.dumpingPidEnded();
        mLatencyTracker.dumpingPidStarted(11);
        mLatencyTracker.dumpingPidEnded();
        mLatencyTracker.dumpingExtraPidsEnded();
        mLatencyTracker.dumpStackTracesEnded();
        mLatencyTracker.anrProcessingEnded();

        mLatencyTracker.close();

        assertThat(mLatencyTracker.dumpAsCommaSeparatedArrayWithHeader())
            .isEqualTo("DurationsV2: 50,5,25,8,115,2,3,7,8,15,2,7,23,10,3,6\n\n");
        verify(mLatencyTracker, times(1)).pushAtom();
    }

    @Test
    public void testCloseIdempotency() {

        mLatencyTracker.appNotRespondingStarted();
        mLatencyTracker.waitingOnAnrRecordLockStarted();
        mLatencyTracker.waitingOnAnrRecordLockEnded();
        mLatencyTracker.anrRecordPlacingOnQueueWithSize(3);
        mLatencyTracker.appNotRespondingEnded();

        mLatencyTracker.anrProcessingStarted();
        mLatencyTracker.updateCpuStatsNowCalled();
        mLatencyTracker.updateCpuStatsNowReturned();
        mLatencyTracker.currentPsiStateCalled();
        mLatencyTracker.currentPsiStateReturned();
        mLatencyTracker.processCpuTrackerMethodsCalled();
        mLatencyTracker.processCpuTrackerMethodsReturned();
        mLatencyTracker.criticalEventLogStarted();
        mLatencyTracker.criticalEventLogEnded();

        mLatencyTracker.waitingOnGlobalLockStarted();
        mLatencyTracker.waitingOnGlobalLockEnded();
        mLatencyTracker.waitingOnPidLockStarted();
        mLatencyTracker.waitingOnPidLockEnded();
        mLatencyTracker.waitingOnAMSLockStarted();
        mLatencyTracker.waitingOnAMSLockEnded();
        mLatencyTracker.waitingOnProcLockStarted();
        mLatencyTracker.waitingOnProcLockEnded();

        mLatencyTracker.dumpStackTracesStarted();
        mLatencyTracker.dumpingFirstPidsStarted();
        mLatencyTracker.dumpingPidStarted(1);
        mLatencyTracker.dumpingPidEnded();
        mLatencyTracker.dumpingFirstPidsEnded();
        mLatencyTracker.dumpingNativePidsStarted();
        mLatencyTracker.dumpingPidStarted(3);
        mLatencyTracker.dumpingPidEnded();
        mLatencyTracker.dumpingNativePidsEnded();
        mLatencyTracker.dumpingExtraPidsStarted();
        mLatencyTracker.dumpingPidStarted(10);
        mLatencyTracker.dumpingPidEnded();
        mLatencyTracker.dumpingPidStarted(11);
        mLatencyTracker.dumpingPidEnded();
        mLatencyTracker.dumpingExtraPidsEnded();
        mLatencyTracker.dumpStackTracesEnded();
        mLatencyTracker.anrProcessingEnded();

        mLatencyTracker.close();
        mLatencyTracker.close();

        verify(mLatencyTracker, times(1)).pushAtom();
    }

    @Test
    public void testSkipInProcessErrorStateRecordAppNotResponding() {

        mLatencyTracker.appNotRespondingStarted();
        mLatencyTracker.waitingOnAnrRecordLockStarted();
        mLatencyTracker.waitingOnAnrRecordLockEnded();
        mLatencyTracker.anrRecordPlacingOnQueueWithSize(3);
        mLatencyTracker.appNotRespondingEnded();

        mLatencyTracker.anrProcessingStarted();
        mLatencyTracker.updateCpuStatsNowCalled();
        mLatencyTracker.updateCpuStatsNowReturned();
        mLatencyTracker.currentPsiStateCalled();
        mLatencyTracker.currentPsiStateReturned();
        mLatencyTracker.processCpuTrackerMethodsCalled();
        mLatencyTracker.processCpuTrackerMethodsReturned();
        mLatencyTracker.criticalEventLogStarted();
        mLatencyTracker.criticalEventLogEnded();

        mLatencyTracker.waitingOnGlobalLockStarted();
        mLatencyTracker.waitingOnGlobalLockEnded();
        mLatencyTracker.waitingOnPidLockStarted();
        mLatencyTracker.waitingOnPidLockEnded();
        mLatencyTracker.waitingOnAMSLockStarted();
        mLatencyTracker.waitingOnAMSLockEnded();
        mLatencyTracker.waitingOnProcLockStarted();
        mLatencyTracker.waitingOnProcLockEnded();

        mLatencyTracker.anrSkippedProcessErrorStateRecordAppNotResponding();

        mLatencyTracker.anrProcessingEnded();

        mLatencyTracker.close();

        verify(mLatencyTracker, times(0)).pushAtom();
    }

    @Test
    public void testSkipInDumpTraces() {

        mLatencyTracker.appNotRespondingStarted();
        mLatencyTracker.waitingOnAnrRecordLockStarted();
        mLatencyTracker.waitingOnAnrRecordLockEnded();
        mLatencyTracker.anrRecordPlacingOnQueueWithSize(3);
        mLatencyTracker.appNotRespondingEnded();

        mLatencyTracker.anrProcessingStarted();
        mLatencyTracker.updateCpuStatsNowCalled();
        mLatencyTracker.updateCpuStatsNowReturned();
        mLatencyTracker.currentPsiStateCalled();
        mLatencyTracker.currentPsiStateReturned();
        mLatencyTracker.processCpuTrackerMethodsCalled();
        mLatencyTracker.processCpuTrackerMethodsReturned();
        mLatencyTracker.criticalEventLogStarted();
        mLatencyTracker.criticalEventLogEnded();

        mLatencyTracker.waitingOnGlobalLockStarted();
        mLatencyTracker.waitingOnGlobalLockEnded();
        mLatencyTracker.waitingOnPidLockStarted();
        mLatencyTracker.waitingOnPidLockEnded();
        mLatencyTracker.waitingOnAMSLockStarted();
        mLatencyTracker.waitingOnAMSLockEnded();
        mLatencyTracker.waitingOnProcLockStarted();
        mLatencyTracker.waitingOnProcLockEnded();

        mLatencyTracker.dumpStackTracesStarted();
        mLatencyTracker.anrSkippedDumpStackTraces();
        mLatencyTracker.dumpStackTracesEnded();

        mLatencyTracker.anrProcessingEnded();

        mLatencyTracker.close();

        verify(mLatencyTracker, times(0)).pushAtom();
    }

}
