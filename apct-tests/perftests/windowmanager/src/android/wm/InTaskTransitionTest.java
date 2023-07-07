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

package android.wm;

import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Looper;
import android.os.RemoteCallback;
import android.os.RemoteException;
import android.os.SystemClock;
import android.perftests.utils.ManualBenchmarkState;
import android.perftests.utils.PerfManualStatusReporter;
import android.perftests.utils.PerfTestActivity;
import android.view.WindowManagerGlobal;

import org.junit.Rule;
import org.junit.Test;

/** Measure the performance of warm launch activity in the same task. */
public class InTaskTransitionTest extends WindowManagerPerfTestBase
        implements RemoteCallback.OnResultListener {

    private static final long TIMEOUT_MS = 5000;

    @Rule
    public final PerfManualStatusReporter mPerfStatusReporter = new PerfManualStatusReporter();

    private final TransitionMetricsReader mMetricsReader = new TransitionMetricsReader();

    @Test
    @ManualBenchmarkState.ManualBenchmarkTest(
            targetTestDurationNs = 20 * TIME_1_S_IN_NS,
            statsReport = @ManualBenchmarkState.StatsReport(
                    flags = ManualBenchmarkState.StatsReport.FLAG_ITERATION
                            | ManualBenchmarkState.StatsReport.FLAG_MEAN
                            | ManualBenchmarkState.StatsReport.FLAG_MAX))
    public void testStartActivityInSameTask() {
        final Context context = getInstrumentation().getContext();
        final Activity activity = getInstrumentation().startActivitySync(
                new Intent(context, PerfTestActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        final Intent next = new Intent(context, TestActivity.class);
        next.putExtra(TestActivity.CALLBACK, new RemoteCallback(this));

        final ManualBenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        long measuredTimeNs = 0;

        boolean readerStarted = false;
        while (state.keepRunning(measuredTimeNs)) {
            if (!readerStarted && !state.isWarmingUp()) {
                mMetricsReader.setCheckpoint();
                readerStarted = true;
            }
            final long startTime = SystemClock.elapsedRealtimeNanos();
            activity.startActivity(next);
            synchronized (mMetricsReader) {
                try {
                    mMetricsReader.wait(TIMEOUT_MS);
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            }
            measuredTimeNs = SystemClock.elapsedRealtimeNanos() - startTime;
        }

        for (TransitionMetricsReader.TransitionMetrics metrics : mMetricsReader.getMetrics()) {
            if (metrics.mTransitionDelayMs > 0) {
                state.addExtraResult("transitionDelayMs", metrics.mTransitionDelayMs);
            }
            if (metrics.mWindowsDrawnDelayMs > 0) {
                state.addExtraResult("windowsDrawnDelayMs", metrics.mWindowsDrawnDelayMs);
            }
        }
    }

    @Override
    public void onResult(Bundle result) {
        // The test activity is destroyed.
        synchronized (mMetricsReader) {
            mMetricsReader.notifyAll();
        }
    }

    /** The test activity runs on a different process to trigger metrics logs. */
    public static class TestActivity extends Activity implements Runnable {
        static final String CALLBACK = "callback";

        private RemoteCallback mCallback;

        @Override
        protected void onCreate(Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);
            mCallback = getIntent().getParcelableExtra(CALLBACK, RemoteCallback.class);
            if (mCallback != null) {
                Looper.myLooper().getQueue().addIdleHandler(() -> {
                    new Thread(this).start();
                    return false;
                });
            }
        }

        @Override
        public void run() {
            // Wait until transition animation is finished and then finish self.
            try {
                WindowManagerGlobal.getWindowManagerService()
                        .syncInputTransactions(true /* waitForAnimations */);
            } catch (RemoteException e) {
                e.rethrowFromSystemServer();
            }
            finish();
        }

        @Override
        protected void onDestroy() {
            super.onDestroy();
            if (mCallback != null) {
                getMainThreadHandler().post(() -> mCallback.sendResult(null));
            }
        }
    }
}
