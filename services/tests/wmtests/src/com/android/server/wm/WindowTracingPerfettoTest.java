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

package com.android.server.wm;

import static android.tools.traces.Utils.busyWaitForDataSourceRegistration;

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
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockito.Mockito;

import perfetto.protos.PerfettoConfig.WindowManagerConfig.LogFrequency;

import java.io.IOException;

/**
 * Test class for {@link WindowTracingPerfetto}.
 */
@SmallTest
@Presubmit
public class WindowTracingPerfettoTest {
    private static final String TEST_DATA_SOURCE_NAME = "android.windowmanager.test";

    private static WindowManagerService sWmMock;
    private static Choreographer sChoreographer;
    private static WindowTracing sWindowTracing;

    private PerfettoTraceMonitor mTraceMonitor;

    @BeforeClass
    public static void setUpOnce() throws Exception {
        sWmMock = Mockito.mock(WindowManagerService.class);
        Mockito.doNothing().when(sWmMock).dumpDebugLocked(Mockito.any(), Mockito.anyInt());
        sChoreographer = Mockito.mock(Choreographer.class);
        sWindowTracing = new WindowTracingPerfetto(sWmMock, sChoreographer,
                new WindowManagerGlobalLock(), TEST_DATA_SOURCE_NAME);
        busyWaitForDataSourceRegistration(TEST_DATA_SOURCE_NAME);
    }

    @Before
    public void setUp() throws IOException {
        Mockito.clearInvocations(sWmMock);
    }

    @After
    public void tearDown() throws IOException {
        stopTracing();
    }

    @Test
    public void isEnabled_returnsFalseByDefault() {
        assertFalse(sWindowTracing.isEnabled());
    }

    @Test
    public void isEnabled_returnsTrueAfterStartThenFalseAfterStop() throws IOException {
        startTracing(false);
        assertTrue(sWindowTracing.isEnabled());

        stopTracing();
        assertFalse(sWindowTracing.isEnabled());
    }

    @Test
    public void trace_ignoresLogStateCalls_ifTracingIsDisabled() {
        sWindowTracing.logState("where");
        verifyZeroInteractions(sWmMock);
    }

    @Test
    public void trace_writesInitialStateSnapshot_whenTracingStarts() {
        startTracing(false);
        verify(sWmMock, times(1)).dumpDebugLocked(any(), eq(WindowTracingLogLevel.ALL));
    }

    @Test
    public void trace_writesStateSnapshot_onLogStateCall() {
        startTracing(false);
        sWindowTracing.logState("where");
        verify(sWmMock, times(2)).dumpDebugLocked(any(), eq(WindowTracingLogLevel.ALL));
    }

    @Test
    public void dump_writesOneSingleStateSnapshot() {
        startTracing(true);
        sWindowTracing.logState("where");
        verify(sWmMock, times(1)).dumpDebugLocked(any(), eq(WindowTracingLogLevel.ALL));
    }

    private void startTracing(boolean isDump) {
        if (isDump) {
            mTraceMonitor = PerfettoTraceMonitor
                    .newBuilder()
                    .enableWindowManagerDump(TEST_DATA_SOURCE_NAME)
                    .build();
        } else {
            mTraceMonitor = PerfettoTraceMonitor
                    .newBuilder()
                    .enableWindowManagerTrace(LogFrequency.LOG_FREQUENCY_TRANSACTION,
                            TEST_DATA_SOURCE_NAME)
                    .build();
        }
        mTraceMonitor.start();
    }

    private void stopTracing() throws IOException {
        if (mTraceMonitor == null || !mTraceMonitor.isEnabled()) {
            return;
        }

        ResultWriter writer = new ResultWriter()
                .forScenario(new ScenarioBuilder()
                        .forClass(createTempFile("temp", "").getName()).build())
                .withOutputDir(createTempDirectory("temp").toFile())
                .setRunComplete();

        mTraceMonitor.stop(writer);
    }
}
