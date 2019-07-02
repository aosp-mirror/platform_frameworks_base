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

import android.annotation.Nullable;
import android.support.annotation.VisibleForTesting;
import android.util.Log;

import androidx.test.InstrumentationRegistry;

import com.android.server.wm.flicker.monitor.ITransitionMonitor;
import com.android.server.wm.flicker.monitor.LayersTraceMonitor;
import com.android.server.wm.flicker.monitor.ScreenRecorder;
import com.android.server.wm.flicker.monitor.WindowAnimationFrameStatsMonitor;
import com.android.server.wm.flicker.monitor.WindowManagerTraceMonitor;

import com.google.common.io.Files;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

/**
 * Builds and runs UI transitions capturing test artifacts.
 *
 * User can compose a transition from simpler steps, specifying setup and teardown steps. During
 * a transition, Layers trace, WindowManager trace, screen recordings and window animation frame
 * stats can be captured.
 *
 * <pre>
 * Transition builder options:
 *  {@link TransitionBuilder#run(Runnable)} run transition under test. Monitors will be started
 *  before the transition and stopped after the transition is completed.
 *  {@link TransitionBuilder#repeat(int)} repeat transitions under test multiple times recording
 *  result for each run.
 *  {@link TransitionBuilder#withTag(String)} specify a string identifier used to prefix logs and
 *  artifacts generated.
 *  {@link TransitionBuilder#runBeforeAll(Runnable)} run setup transitions once before all other
 *  transition are run to set up an initial state on device.
 *  {@link TransitionBuilder#runBefore(Runnable)} run setup transitions before each test transition
 *  run.
 *  {@link TransitionBuilder#runAfter(Runnable)} run teardown transitions after each test
 *  transition.
 *  {@link TransitionBuilder#runAfter(Runnable)} run teardown transitions once after all
 *  other transition  are run.
 *  {@link TransitionBuilder#includeJankyRuns()} disables {@link WindowAnimationFrameStatsMonitor}
 *  to monitor janky frames. If janky frames are detected, then the test run is skipped. This
 *  monitor is enabled by default.
 *  {@link TransitionBuilder#skipLayersTrace()} disables {@link LayersTraceMonitor} used to
 *  capture Layers trace during a transition. This monitor is enabled by default.
 *  {@link TransitionBuilder#skipWindowManagerTrace()} disables {@link WindowManagerTraceMonitor}
 *  used to capture WindowManager trace during a transition. This monitor is enabled by
 *  default.
 *  {@link TransitionBuilder#recordAllRuns()} records the screen contents and saves it to a file.
 *  All the runs including setup and teardown transitions are included in the recording. This
 *  monitor is used for debugging purposes.
 *  {@link TransitionBuilder#recordEachRun()} records the screen contents during test transitions
 *  and saves it to a file for each run. This monitor is used for debugging purposes.
 *
 * Example transition to capture WindowManager and Layers trace when opening a test app:
 * {@code
 * TransitionRunner.newBuilder()
 *      .withTag("OpenTestAppFast")
 *      .runBeforeAll(UiAutomationLib::wakeUp)
 *      .runBeforeAll(UiAutomationLib::UnlockDevice)
 *      .runBeforeAll(UiAutomationLib::openTestApp)
 *      .runBefore(UiAutomationLib::closeTestApp)
 *      .run(UiAutomationLib::openTestApp)
 *      .runAfterAll(UiAutomationLib::closeTestApp)
 *      .repeat(5)
 *      .build()
 *      .run();
 * }
 * </pre>
 */
class TransitionRunner {
    private static final String TAG = "FLICKER";
    private final ScreenRecorder mScreenRecorder;
    private final WindowManagerTraceMonitor mWmTraceMonitor;
    private final LayersTraceMonitor mLayersTraceMonitor;
    private final WindowAnimationFrameStatsMonitor mFrameStatsMonitor;

    private final List<ITransitionMonitor> mAllRunsMonitors;
    private final List<ITransitionMonitor> mPerRunMonitors;
    private final List<Runnable> mBeforeAlls;
    private final List<Runnable> mBefores;
    private final List<Runnable> mTransitions;
    private final List<Runnable> mAfters;
    private final List<Runnable> mAfterAlls;

    private final int mIterations;
    private final String mTestTag;

    @Nullable
    private List<TransitionResult> mResults = null;

    private TransitionRunner(TransitionBuilder builder) {
        mScreenRecorder = builder.mScreenRecorder;
        mWmTraceMonitor = builder.mWmTraceMonitor;
        mLayersTraceMonitor = builder.mLayersTraceMonitor;
        mFrameStatsMonitor = builder.mFrameStatsMonitor;

        mAllRunsMonitors = builder.mAllRunsMonitors;
        mPerRunMonitors = builder.mPerRunMonitors;
        mBeforeAlls = builder.mBeforeAlls;
        mBefores = builder.mBefores;
        mTransitions = builder.mTransitions;
        mAfters = builder.mAfters;
        mAfterAlls = builder.mAfterAlls;

        mIterations = builder.mIterations;
        mTestTag = builder.mTestTag;
    }

    static TransitionBuilder newBuilder() {
        return new TransitionBuilder();
    }

    /**
     * Runs the composed transition and calls monitors at the appropriate stages. If jank monitor
     * is enabled, transitions with jank are skipped.
     *
     * @return itself
     */
    TransitionRunner run() {
        mResults = new ArrayList<>();
        mAllRunsMonitors.forEach(ITransitionMonitor::start);
        mBeforeAlls.forEach(Runnable::run);
        for (int iteration = 0; iteration < mIterations; iteration++) {
            mBefores.forEach(Runnable::run);
            mPerRunMonitors.forEach(ITransitionMonitor::start);
            mTransitions.forEach(Runnable::run);
            mPerRunMonitors.forEach(ITransitionMonitor::stop);
            mAfters.forEach(Runnable::run);
            if (runJankFree() && mFrameStatsMonitor.jankyFramesDetected()) {
                String msg = String.format("Skipping iteration %d/%d for test %s due to jank. %s",
                        iteration, mIterations - 1, mTestTag, mFrameStatsMonitor.toString());
                Log.e(TAG, msg);
                continue;
            }
            mResults.add(saveResult(iteration));
        }
        mAfterAlls.forEach(Runnable::run);
        mAllRunsMonitors.forEach(monitor -> {
            monitor.stop();
            Path path = monitor.save(mTestTag);
            Log.e(TAG, "Video saved to " + path.toString());
        });
        return this;
    }

    /**
     * Returns a list of transition results.
     *
     * @return list of transition results.
     */
    List<TransitionResult> getResults() {
        if (mResults == null) {
            throw new IllegalStateException("Results do not exist!");
        }
        return mResults;
    }

    /**
     * Deletes all transition results that are not marked for saving.
     *
     * @return list of transition results.
     */
    void deleteResults() {
        if (mResults == null) {
            return;
        }
        mResults.stream()
                .filter(TransitionResult::canDelete)
                .forEach(TransitionResult::delete);
        mResults = null;
    }

    /**
     * Saves monitor results to file.
     *
     * @return object containing paths to test artifacts
     */
    private TransitionResult saveResult(int iteration) {
        Path windowTrace = null;
        Path layerTrace = null;
        Path screenCaptureVideo = null;

        if (mPerRunMonitors.contains(mWmTraceMonitor)) {
            windowTrace = mWmTraceMonitor.save(mTestTag, iteration);
        }
        if (mPerRunMonitors.contains(mLayersTraceMonitor)) {
            layerTrace = mLayersTraceMonitor.save(mTestTag, iteration);
        }
        if (mPerRunMonitors.contains(mScreenRecorder)) {
            screenCaptureVideo = mScreenRecorder.save(mTestTag, iteration);
        }
        return new TransitionResult(layerTrace, windowTrace, screenCaptureVideo);
    }

    private boolean runJankFree() {
        return mPerRunMonitors.contains(mFrameStatsMonitor);
    }

    public String getTestTag() {
        return mTestTag;
    }

    /**
     * Stores paths to all test artifacts.
     */
    @VisibleForTesting
    public static class TransitionResult {
        @Nullable
        final Path layersTrace;
        @Nullable
        final Path windowManagerTrace;
        @Nullable
        final Path screenCaptureVideo;
        private boolean flaggedForSaving;

        TransitionResult(@Nullable Path layersTrace, @Nullable Path windowManagerTrace,
                @Nullable Path screenCaptureVideo) {
            this.layersTrace = layersTrace;
            this.windowManagerTrace = windowManagerTrace;
            this.screenCaptureVideo = screenCaptureVideo;
        }

        void flagForSaving() {
            flaggedForSaving = true;
        }

        boolean canDelete() {
            return !flaggedForSaving;
        }

        boolean layersTraceExists() {
            return layersTrace != null && layersTrace.toFile().exists();
        }

        byte[] getLayersTrace() {
            try {
                return Files.toByteArray(this.layersTrace.toFile());
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        Path getLayersTracePath() {
            return layersTrace;
        }

        boolean windowManagerTraceExists() {
            return windowManagerTrace != null && windowManagerTrace.toFile().exists();
        }

        public byte[] getWindowManagerTrace() {
            try {
                return Files.toByteArray(this.windowManagerTrace.toFile());
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        Path getWindowManagerTracePath() {
            return windowManagerTrace;
        }

        boolean screenCaptureVideoExists() {
            return screenCaptureVideo != null && screenCaptureVideo.toFile().exists();
        }

        Path screenCaptureVideoPath() {
            return screenCaptureVideo;
        }

        void delete() {
            if (layersTraceExists()) layersTrace.toFile().delete();
            if (windowManagerTraceExists()) windowManagerTrace.toFile().delete();
            if (screenCaptureVideoExists()) screenCaptureVideo.toFile().delete();
        }
    }

    /**
     * Builds a {@link TransitionRunner} instance.
     */
    static class TransitionBuilder {
        private ScreenRecorder mScreenRecorder;
        private WindowManagerTraceMonitor mWmTraceMonitor;
        private LayersTraceMonitor mLayersTraceMonitor;
        private WindowAnimationFrameStatsMonitor mFrameStatsMonitor;

        private List<ITransitionMonitor> mAllRunsMonitors = new LinkedList<>();
        private List<ITransitionMonitor> mPerRunMonitors = new LinkedList<>();
        private List<Runnable> mBeforeAlls = new LinkedList<>();
        private List<Runnable> mBefores = new LinkedList<>();
        private List<Runnable> mTransitions = new LinkedList<>();
        private List<Runnable> mAfters = new LinkedList<>();
        private List<Runnable> mAfterAlls = new LinkedList<>();

        private boolean mRunJankFree = true;
        private boolean mCaptureWindowManagerTrace = true;
        private boolean mCaptureLayersTrace = true;
        private boolean mRecordEachRun = false;
        private int mIterations = 1;
        private String mTestTag = "";

        private boolean mRecordAllRuns = false;

        TransitionBuilder() {
            mScreenRecorder = new ScreenRecorder();
            mWmTraceMonitor = new WindowManagerTraceMonitor();
            mLayersTraceMonitor = new LayersTraceMonitor();
            mFrameStatsMonitor = new
                    WindowAnimationFrameStatsMonitor(InstrumentationRegistry.getInstrumentation());
        }

        TransitionRunner build() {
            if (mCaptureWindowManagerTrace) {
                mPerRunMonitors.add(mWmTraceMonitor);
            }

            if (mCaptureLayersTrace) {
                mPerRunMonitors.add(mLayersTraceMonitor);
            }

            if (mRunJankFree) {
                mPerRunMonitors.add(mFrameStatsMonitor);
            }

            if (mRecordAllRuns) {
                mAllRunsMonitors.add(mScreenRecorder);
            }

            if (mRecordEachRun) {
                mPerRunMonitors.add(mScreenRecorder);
            }

            return new TransitionRunner(this);
        }

        TransitionBuilder runBeforeAll(Runnable runnable) {
            mBeforeAlls.add(runnable);
            return this;
        }

        TransitionBuilder runBefore(Runnable runnable) {
            mBefores.add(runnable);
            return this;
        }

        TransitionBuilder run(Runnable runnable) {
            mTransitions.add(runnable);
            return this;
        }

        TransitionBuilder runAfter(Runnable runnable) {
            mAfters.add(runnable);
            return this;
        }

        TransitionBuilder runAfterAll(Runnable runnable) {
            mAfterAlls.add(runnable);
            return this;
        }

        TransitionBuilder repeat(int iterations) {
            mIterations = iterations;
            return this;
        }

        TransitionBuilder skipWindowManagerTrace() {
            mCaptureWindowManagerTrace = false;
            return this;
        }

        TransitionBuilder skipLayersTrace() {
            mCaptureLayersTrace = false;
            return this;
        }

        TransitionBuilder includeJankyRuns() {
            mRunJankFree = false;
            return this;
        }

        TransitionBuilder recordEachRun() {
            if (mRecordAllRuns) {
                throw new IllegalArgumentException("Invalid option with recordAllRuns");
            }
            mRecordEachRun = true;
            return this;
        }

        TransitionBuilder recordAllRuns() {
            if (mRecordEachRun) {
                throw new IllegalArgumentException("Invalid option with recordEachRun");
            }
            mRecordAllRuns = true;
            return this;
        }

        TransitionBuilder withTag(String testTag) {
            mTestTag = testTag;
            return this;
        }
    }
}
