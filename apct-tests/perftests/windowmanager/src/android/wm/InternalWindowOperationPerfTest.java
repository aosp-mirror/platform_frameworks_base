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

import android.os.ParcelFileDescriptor;
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
import java.util.concurrent.TimeUnit;

/** Measure the performance of internal methods in window manager service by trace tag. */
@LargeTest
public class InternalWindowOperationPerfTest extends WindowManagerPerfTestBase {
    private static final String TAG = InternalWindowOperationPerfTest.class.getSimpleName();

    @Rule
    public final PerfManualStatusReporter mPerfStatusReporter = new PerfManualStatusReporter();

    @Rule
    public final PerfTestActivityRule mActivityRule = new PerfTestActivityRule();

    private final TraceMarkParser mTraceMarkParser = new TraceMarkParser(
            "applyPostLayoutPolicy",
            "applySurfaceChanges",
            "AppTransitionReady",
            "closeSurfaceTransactiom",
            "openSurfaceTransaction",
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

    @Test
    @ManualBenchmarkTest(
            targetTestDurationNs = 20 * TIME_1_S_IN_NS,
            statsReport = @StatsReport(
                    flags = StatsReport.FLAG_ITERATION | StatsReport.FLAG_MEAN
                            | StatsReport.FLAG_MAX | StatsReport.FLAG_COEFFICIENT_VAR))
    public void testLaunchAndFinishActivity() throws Throwable {
        final ManualBenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        long measuredTimeNs = 0;
        boolean isTraceStarted = false;

        while (state.keepRunning(measuredTimeNs)) {
            if (!isTraceStarted && !state.isWarmingUp()) {
                startAsyncAtrace();
                isTraceStarted = true;
            }
            final long startTime = SystemClock.elapsedRealtimeNanos();
            mActivityRule.launchActivity();
            mActivityRule.finishActivity();
            mActivityRule.waitForIdleSync(Stage.DESTROYED);
            measuredTimeNs = SystemClock.elapsedRealtimeNanos() - startTime;
        }

        stopAsyncAtrace();

        mTraceMarkParser.forAllSlices((key, slices) -> {
            for (TraceMarkSlice slice : slices) {
                state.addExtraResult(key, (long) (slice.getDurationInSeconds() * NANOS_PER_S));
            }
        });

        Log.i(TAG, String.valueOf(mTraceMarkParser));
    }

    private void startAsyncAtrace() throws IOException {
        sUiAutomation.executeShellCommand("atrace -b 32768 --async_start wm");
        // Avoid atrace isn't ready immediately.
        SystemClock.sleep(TimeUnit.NANOSECONDS.toMillis(TIME_1_S_IN_NS));
    }

    private void stopAsyncAtrace() throws IOException {
        final ParcelFileDescriptor pfd = sUiAutomation.executeShellCommand("atrace --async_stop");
        final InputStream inputStream = new ParcelFileDescriptor.AutoCloseInputStream(pfd);
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream))) {
            String line;
            while ((line = reader.readLine()) != null) {
                mTraceMarkParser.visit(line);
            }
        }
    }
}
