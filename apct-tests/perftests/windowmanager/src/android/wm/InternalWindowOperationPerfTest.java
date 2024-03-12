/*
 * Copyright (C) 2019 The Android Open Source Project
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

package android.wm;

import static android.perftests.utils.ManualBenchmarkState.StatsReport;

import android.os.SystemClock;
import android.perftests.utils.ManualBenchmarkState;
import android.perftests.utils.ManualBenchmarkState.ManualBenchmarkTest;
import android.perftests.utils.PerfManualStatusReporter;
import android.perftests.utils.TraceMarkParser;
import android.perftests.utils.TraceMarkParser.TraceMarkSlice;
import android.util.Log;

import androidx.test.filters.LargeTest;
import androidx.test.runner.lifecycle.Stage;

import org.junit.Rule;
import org.junit.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;

/** Measure the performance of internal methods in window manager service by trace tag. */
@LargeTest
public class InternalWindowOperationPerfTest extends WindowManagerPerfTestBase
        implements ManualBenchmarkState.CustomizedIterationListener {
    private static final String TAG = InternalWindowOperationPerfTest.class.getSimpleName();

    @Rule
    public final PerfManualStatusReporter mPerfStatusReporter = new PerfManualStatusReporter();

    @Rule
    public final PerfTestActivityRule mActivityRule = new PerfTestActivityRule();

    private final TraceMarkParser mTraceMarkParser = new TraceMarkParser(
            "applyPostLayoutPolicy",
            "applySurfaceChanges",
            "onTransactionReady",
            "applyTransaction",
            "performLayout",
            "performSurfacePlacement",
            "prepareSurfaces",
            "updateInputWindows",
            "WSA#startAnimation",
            "activityIdle",
            "activityPaused",
            "activityStopped",
            "activityDestroyed",
            "finishActivity",
            "startActivityInner");

    private boolean mIsProfiling;
    private boolean mIsTraceStarted;

    @Test
    @ManualBenchmarkTest(
            targetTestDurationNs = 20 * TIME_1_S_IN_NS,
            statsReport = @StatsReport(
                    flags = StatsReport.FLAG_ITERATION | StatsReport.FLAG_MEAN
                            | StatsReport.FLAG_MAX | StatsReport.FLAG_COEFFICIENT_VAR))
    public void testLaunchAndFinishActivity() throws Throwable {
        final ManualBenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        state.setCustomizedIterations(getProfilingIterations(), this);
        long measuredTimeNs = 0;

        while (state.keepRunning(measuredTimeNs)) {
            if (!mIsTraceStarted && !mIsProfiling && !state.isWarmingUp()) {
                startAsyncAtrace("wm");
                mIsTraceStarted = true;
            }
            final long startTime = SystemClock.elapsedRealtimeNanos();
            mActivityRule.launchActivity();
            mActivityRule.finishActivity();
            mActivityRule.waitForIdleSync(Stage.DESTROYED);
            measuredTimeNs = SystemClock.elapsedRealtimeNanos() - startTime;
        }

        if (mIsTraceStarted) {
            stopAsyncAtrace();
        }

        mTraceMarkParser.forAllSlices((key, slices) -> {
            if (slices.size() < 2) {
                Log.w(TAG, "No sufficient samples: " + key);
                return;
            }
            for (TraceMarkSlice slice : slices) {
                state.addExtraResult(key, (long) (slice.getDurationInSeconds() * NANOS_PER_S));
            }
        });

        Log.i(TAG, String.valueOf(mTraceMarkParser));
    }

    private void stopAsyncAtrace() {
        final InputStream inputStream = stopAsyncAtraceWithStream();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream))) {
            String line;
            while ((line = reader.readLine()) != null) {
                mTraceMarkParser.visit(line);
            }
        } catch (IOException e) {
            Log.w(TAG, "Failed to read the result of stopped atrace", e);
        }
    }

    @Override
    public void onStart(int iteration) {
        if (mIsTraceStarted) {
            // Do not capture trace when profiling because the result will be much slower.
            stopAsyncAtrace();
            mIsTraceStarted = false;
        }
        mIsProfiling = true;
        startProfiling(InternalWindowOperationPerfTest.class.getSimpleName()
                + "_MethodTracing_" + iteration + ".trace");
    }

    @Override
    public void onFinished(int iteration) {
        stopProfiling();
        if (iteration >= getProfilingIterations() - 1) {
            mIsProfiling = false;
        }
    }
}
