/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.server.wm;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.times;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.verify;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.verifyZeroInteractions;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;

import static java.io.File.createTempFile;
import static java.nio.file.Files.createTempDirectory;

import android.platform.test.annotations.Presubmit;
import android.tools.ScenarioBuilder;
import android.tools.traces.io.ResultWriter;
import android.tools.traces.monitors.PerfettoTraceMonitor;
import android.view.Choreographer;

import androidx.test.filters.SmallTest;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

import perfetto.protos.PerfettoConfig.WindowManagerConfig.LogFrequency;

/**
 * Test class for {@link WindowTracingPerfetto}.
 */
@SmallTest
@Presubmit
public class WindowTracingPerfettoTest {
    private WindowManagerService mWmMock;
    private Choreographer mChoreographer;
    private WindowTracing mWindowTracing;
    private PerfettoTraceMonitor mTraceMonitor;
    private ResultWriter mWriter;

    @Before
    public void setUp() throws Exception {
        mWmMock = Mockito.mock(WindowManagerService.class);
        Mockito.doNothing().when(mWmMock).dumpDebugLocked(Mockito.any(), Mockito.anyInt());

        mChoreographer = Mockito.mock(Choreographer.class);

        mWindowTracing = new WindowTracingPerfetto(mWmMock, mChoreographer,
                new WindowManagerGlobalLock());

        mWriter = new ResultWriter()
            .forScenario(new ScenarioBuilder()
                    .forClass(createTempFile("temp", "").getName()).build())
            .withOutputDir(createTempDirectory("temp").toFile())
            .setRunComplete();
    }

    @After
    public void tearDown() throws Exception {
        stopTracing();
    }

    @Test
    public void isEnabled_returnsFalseByDefault() {
        assertFalse(mWindowTracing.isEnabled());
    }

    @Test
    public void isEnabled_returnsTrueAfterStartThenFalseAfterStop() {
        startTracing(false);
        assertTrue(mWindowTracing.isEnabled());

        stopTracing();
        assertFalse(mWindowTracing.isEnabled());
    }

    @Test
    public void trace_ignoresLogStateCalls_ifTracingIsDisabled() {
        mWindowTracing.logState("where");
        verifyZeroInteractions(mWmMock);
    }

    @Test
    public void trace_writesInitialStateSnapshot_whenTracingStarts() throws Exception {
        startTracing(false);
        verify(mWmMock, times(1)).dumpDebugLocked(any(), eq(WindowTracingLogLevel.ALL));
    }

    @Test
    public void trace_writesStateSnapshot_onLogStateCall() throws Exception {
        startTracing(false);
        mWindowTracing.logState("where");
        verify(mWmMock, times(2)).dumpDebugLocked(any(), eq(WindowTracingLogLevel.ALL));
    }

    @Test
    public void dump_writesOneSingleStateSnapshot() throws Exception {
        startTracing(true);
        mWindowTracing.logState("where");
        verify(mWmMock, times(1)).dumpDebugLocked(any(), eq(WindowTracingLogLevel.ALL));
    }

    private void startTracing(boolean isDump) {
        if (isDump) {
            mTraceMonitor = PerfettoTraceMonitor
                    .newBuilder()
                    .enableWindowManagerDump()
                    .build();
        } else {
            mTraceMonitor = PerfettoTraceMonitor
                    .newBuilder()
                    .enableWindowManagerTrace(LogFrequency.LOG_FREQUENCY_TRANSACTION)
                    .build();
        }
        mTraceMonitor.start();
    }

    private void stopTracing() {
        if (mTraceMonitor == null || !mTraceMonitor.isEnabled()) {
            return;
        }
        mTraceMonitor.stop(mWriter);
    }
}
