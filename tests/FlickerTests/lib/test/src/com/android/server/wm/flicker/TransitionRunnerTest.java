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
 * limitations under the License.
 */

package com.android.server.wm.flicker;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import android.os.Environment;

import com.android.server.wm.flicker.TransitionRunner.TransitionBuilder;
import com.android.server.wm.flicker.TransitionRunner.TransitionResult;
import com.android.server.wm.flicker.monitor.LayersTraceMonitor;
import com.android.server.wm.flicker.monitor.ScreenRecorder;
import com.android.server.wm.flicker.monitor.WindowAnimationFrameStatsMonitor;
import com.android.server.wm.flicker.monitor.WindowManagerTraceMonitor;

import org.junit.Before;
import org.junit.Test;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.io.IOException;
import java.nio.file.Paths;
import java.util.List;

/**
 * Contains {@link TransitionRunner} tests.
 * {@code atest FlickerLibTest:TransitionRunnerTest}
 */
public class TransitionRunnerTest {
    @Mock
    private SimpleUiTransitions mTransitionsMock;
    @Mock
    private ScreenRecorder mScreenRecorderMock;
    @Mock
    private WindowManagerTraceMonitor mWindowManagerTraceMonitorMock;
    @Mock
    private LayersTraceMonitor mLayersTraceMonitorMock;
    @Mock
    private WindowAnimationFrameStatsMonitor mWindowAnimationFrameStatsMonitor;
    @InjectMocks
    private TransitionBuilder mTransitionBuilder;

    @Before
    public void init() {
        MockitoAnnotations.initMocks(this);
    }

    @Test
    public void transitionsRunInOrder() {
        TransitionRunner.newBuilder()
                .runBeforeAll(mTransitionsMock::turnOnDevice)
                .runBefore(mTransitionsMock::openApp)
                .run(mTransitionsMock::performMagic)
                .runAfter(mTransitionsMock::closeApp)
                .runAfterAll(mTransitionsMock::cleanUpTracks)
                .skipLayersTrace()
                .skipWindowManagerTrace()
                .build()
                .run();

        InOrder orderVerifier = inOrder(mTransitionsMock);
        orderVerifier.verify(mTransitionsMock).turnOnDevice();
        orderVerifier.verify(mTransitionsMock).openApp();
        orderVerifier.verify(mTransitionsMock).performMagic();
        orderVerifier.verify(mTransitionsMock).closeApp();
        orderVerifier.verify(mTransitionsMock).cleanUpTracks();
    }

    @Test
    public void canCombineTransitions() {
        TransitionRunner.newBuilder()
                .runBeforeAll(mTransitionsMock::turnOnDevice)
                .runBeforeAll(mTransitionsMock::turnOnDevice)
                .runBefore(mTransitionsMock::openApp)
                .runBefore(mTransitionsMock::openApp)
                .run(mTransitionsMock::performMagic)
                .run(mTransitionsMock::performMagic)
                .runAfter(mTransitionsMock::closeApp)
                .runAfter(mTransitionsMock::closeApp)
                .runAfterAll(mTransitionsMock::cleanUpTracks)
                .runAfterAll(mTransitionsMock::cleanUpTracks)
                .skipLayersTrace()
                .skipWindowManagerTrace()
                .build()
                .run();

        final int wantedNumberOfInvocations = 2;
        verify(mTransitionsMock, times(wantedNumberOfInvocations)).turnOnDevice();
        verify(mTransitionsMock, times(wantedNumberOfInvocations)).openApp();
        verify(mTransitionsMock, times(wantedNumberOfInvocations)).performMagic();
        verify(mTransitionsMock, times(wantedNumberOfInvocations)).closeApp();
        verify(mTransitionsMock, times(wantedNumberOfInvocations)).cleanUpTracks();
    }

    @Test
    public void emptyTransitionPasses() {
        List<TransitionResult> results = TransitionRunner.newBuilder()
                .skipLayersTrace()
                .skipWindowManagerTrace()
                .build()
                .run()
                .getResults();
        assertThat(results).hasSize(1);
        assertThat(results.get(0).layersTraceExists()).isFalse();
        assertThat(results.get(0).windowManagerTraceExists()).isFalse();
        assertThat(results.get(0).screenCaptureVideoExists()).isFalse();
    }

    @Test
    public void canRepeatTransitions() {
        final int wantedNumberOfInvocations = 10;
        TransitionRunner.newBuilder()
                .runBeforeAll(mTransitionsMock::turnOnDevice)
                .runBefore(mTransitionsMock::openApp)
                .run(mTransitionsMock::performMagic)
                .runAfter(mTransitionsMock::closeApp)
                .runAfterAll(mTransitionsMock::cleanUpTracks)
                .repeat(wantedNumberOfInvocations)
                .skipLayersTrace()
                .skipWindowManagerTrace()
                .build()
                .run();
        verify(mTransitionsMock).turnOnDevice();
        verify(mTransitionsMock, times(wantedNumberOfInvocations)).openApp();
        verify(mTransitionsMock, times(wantedNumberOfInvocations)).performMagic();
        verify(mTransitionsMock, times(wantedNumberOfInvocations)).closeApp();
        verify(mTransitionsMock).cleanUpTracks();
    }

    private void emptyTask() {

    }

    @Test
    public void canCaptureWindowManagerTrace() {
        mTransitionBuilder
                .run(this::emptyTask)
                .includeJankyRuns()
                .skipLayersTrace()
                .withTag("mCaptureWmTraceTransitionRunner")
                .build().run();
        InOrder orderVerifier = inOrder(mWindowManagerTraceMonitorMock);
        orderVerifier.verify(mWindowManagerTraceMonitorMock).start();
        orderVerifier.verify(mWindowManagerTraceMonitorMock).stop();
        orderVerifier.verify(mWindowManagerTraceMonitorMock)
                .save("mCaptureWmTraceTransitionRunner", 0);
        verifyNoMoreInteractions(mWindowManagerTraceMonitorMock);
    }

    @Test
    public void canCaptureLayersTrace() {
        mTransitionBuilder
                .run(this::emptyTask)
                .includeJankyRuns()
                .skipWindowManagerTrace()
                .withTag("mCaptureLayersTraceTransitionRunner")
                .build().run();
        InOrder orderVerifier = inOrder(mLayersTraceMonitorMock);
        orderVerifier.verify(mLayersTraceMonitorMock).start();
        orderVerifier.verify(mLayersTraceMonitorMock).stop();
        orderVerifier.verify(mLayersTraceMonitorMock)
                .save("mCaptureLayersTraceTransitionRunner", 0);
        verifyNoMoreInteractions(mLayersTraceMonitorMock);
    }

    @Test
    public void canRecordEachRun() throws IOException {
        mTransitionBuilder
                .run(this::emptyTask)
                .withTag("mRecordEachRun")
                .recordEachRun()
                .includeJankyRuns()
                .skipLayersTrace()
                .skipWindowManagerTrace()
                .repeat(2)
                .build().run();
        InOrder orderVerifier = inOrder(mScreenRecorderMock);
        orderVerifier.verify(mScreenRecorderMock).start();
        orderVerifier.verify(mScreenRecorderMock).stop();
        orderVerifier.verify(mScreenRecorderMock).save("mRecordEachRun", 0);
        orderVerifier.verify(mScreenRecorderMock).start();
        orderVerifier.verify(mScreenRecorderMock).stop();
        orderVerifier.verify(mScreenRecorderMock).save("mRecordEachRun", 1);
        verifyNoMoreInteractions(mScreenRecorderMock);
    }

    @Test
    public void canRecordAllRuns() throws IOException {
        doReturn(Paths.get(Environment.getExternalStorageDirectory().getAbsolutePath(),
                "mRecordAllRuns.mp4")).when(mScreenRecorderMock).save("mRecordAllRuns");
        mTransitionBuilder
                .run(this::emptyTask)
                .recordAllRuns()
                .includeJankyRuns()
                .skipLayersTrace()
                .skipWindowManagerTrace()
                .withTag("mRecordAllRuns")
                .repeat(2)
                .build().run();
        InOrder orderVerifier = inOrder(mScreenRecorderMock);
        orderVerifier.verify(mScreenRecorderMock).start();
        orderVerifier.verify(mScreenRecorderMock).stop();
        orderVerifier.verify(mScreenRecorderMock).save("mRecordAllRuns");
        verifyNoMoreInteractions(mScreenRecorderMock);
    }

    @Test
    public void canSkipJankyRuns() {
        doReturn(false).doReturn(true).doReturn(false)
                .when(mWindowAnimationFrameStatsMonitor).jankyFramesDetected();
        List<TransitionResult> results = mTransitionBuilder
                .run(this::emptyTask)
                .skipLayersTrace()
                .skipWindowManagerTrace()
                .repeat(3)
                .build().run().getResults();
        assertThat(results).hasSize(2);
    }

    public static class SimpleUiTransitions {
        public void turnOnDevice() {
        }

        public void openApp() {
        }

        public void performMagic() {
        }

        public void closeApp() {
        }

        public void cleanUpTracks() {
        }
    }
}
