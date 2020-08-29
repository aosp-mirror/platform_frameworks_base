/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.internal.jank;

import android.annotation.NonNull;
import android.graphics.HardwareRendererObserver;
import android.os.Handler;
import android.os.Trace;
import android.util.Log;
import android.view.FrameMetrics;
import android.view.ThreadedRenderer;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.jank.InteractionJankMonitor.Session;

/**
 * @hide
 */
public class FrameTracker implements HardwareRendererObserver.OnFrameMetricsAvailableListener {
    private static final String TAG = FrameTracker.class.getSimpleName();
    private static final boolean DEBUG = false;
    //TODO (163431584): need also consider other refresh rates.
    private static final long CRITERIA = 1000000000 / 60;
    @VisibleForTesting
    public static final long UNKNOWN_TIMESTAMP = -1;

    @VisibleForTesting
    public long mBeginTime = UNKNOWN_TIMESTAMP;
    @VisibleForTesting
    public long mEndTime = UNKNOWN_TIMESTAMP;
    public boolean mShouldTriggerTrace;
    public HardwareRendererObserver mObserver;
    public ThreadedRendererWrapper mRendererWrapper;
    public FrameMetricsWrapper mMetricsWrapper;

    private Session mSession;

    public FrameTracker(@NonNull Session session,
            @NonNull Handler handler, @NonNull ThreadedRenderer renderer) {
        mSession = session;
        mRendererWrapper = new ThreadedRendererWrapper(renderer);
        mMetricsWrapper = new FrameMetricsWrapper();
        mObserver = new HardwareRendererObserver(this, mMetricsWrapper.getTiming(), handler);
    }

    /**
     * This constructor is only for unit tests.
     * @param session a trace session.
     * @param renderer a test double for ThreadedRenderer
     * @param metrics a test double for FrameMetrics
     */
    @VisibleForTesting
    public FrameTracker(@NonNull Session session, Handler handler,
            @NonNull ThreadedRendererWrapper renderer, @NonNull FrameMetricsWrapper metrics) {
        mSession = session;
        mRendererWrapper = renderer;
        mMetricsWrapper = metrics;
        mObserver = new HardwareRendererObserver(this, mMetricsWrapper.getTiming(), handler);
    }

    /**
     * Begin a trace session of the CUJ.
     */
    public void begin() {
        long timestamp = System.nanoTime();
        if (DEBUG) {
            Log.d(TAG, "begin: time(ns)=" + timestamp + ", begin(ns)=" + mBeginTime
                    + ", end(ns)=" + mEndTime + ", session=" + mSession);
        }
        if (mBeginTime != UNKNOWN_TIMESTAMP && mEndTime == UNKNOWN_TIMESTAMP) {
            // We have an ongoing tracing already, skip subsequent calls.
            return;
        }
        mBeginTime = timestamp;
        mEndTime = UNKNOWN_TIMESTAMP;
        Trace.beginAsyncSection(mSession.getName(), (int) mBeginTime);
        mRendererWrapper.addObserver(mObserver);
    }

    /**
     * End the trace session of the CUJ.
     */
    public void end() {
        long timestamp = System.nanoTime();
        if (DEBUG) {
            Log.d(TAG, "end: time(ns)=" + timestamp + ", begin(ns)=" + mBeginTime
                    + ", end(ns)=" + mEndTime + ", session=" + mSession);
        }
        if (mBeginTime == UNKNOWN_TIMESTAMP || mEndTime != UNKNOWN_TIMESTAMP) {
            // We haven't started a trace yet.
            return;
        }
        mEndTime = timestamp;
        Trace.endAsyncSection(mSession.getName(), (int) mBeginTime);
    }

    /**
     * Check if we had a janky frame according to the metrics.
     * @param metrics frame metrics
     * @return true if it is a janky frame
     */
    @VisibleForTesting
    public boolean isJankyFrame(FrameMetricsWrapper metrics) {
        long totalDurationMs = metrics.getMetric(FrameMetrics.TOTAL_DURATION);
        boolean isFirstFrame = metrics.getMetric(FrameMetrics.FIRST_DRAW_FRAME) == 1;
        boolean isJanky = !isFirstFrame && totalDurationMs - CRITERIA > 0;

        if (DEBUG) {
            StringBuilder sb = new StringBuilder();
            sb.append(isJanky).append(",");
            sb.append(metrics.getMetric(FrameMetrics.FIRST_DRAW_FRAME)).append(",");
            sb.append(metrics.getMetric(FrameMetrics.INPUT_HANDLING_DURATION)).append(",");
            sb.append(metrics.getMetric(FrameMetrics.ANIMATION_DURATION)).append(",");
            sb.append(metrics.getMetric(FrameMetrics.LAYOUT_MEASURE_DURATION)).append(",");
            sb.append(metrics.getMetric(FrameMetrics.DRAW_DURATION)).append(",");
            sb.append(metrics.getMetric(FrameMetrics.SYNC_DURATION)).append(",");
            sb.append(metrics.getMetric(FrameMetrics.COMMAND_ISSUE_DURATION)).append(",");
            sb.append(metrics.getMetric(FrameMetrics.SWAP_BUFFERS_DURATION)).append(",");
            sb.append(totalDurationMs).append(",");
            sb.append(metrics.getMetric(FrameMetrics.INTENDED_VSYNC_TIMESTAMP)).append(",");
            sb.append(metrics.getMetric(FrameMetrics.VSYNC_TIMESTAMP)).append(",");
            Log.v(TAG, "metrics=" + sb.toString());
        }

        return isJanky;
    }

    @Override
    public void onFrameMetricsAvailable(int dropCountSinceLastInvocation) {
        // Since this callback might come a little bit late after the end() call.
        // We should keep tracking the begin / end timestamp.
        // Then compare with vsync timestamp to check if the frame is in the duration of the CUJ.

        if (mBeginTime == UNKNOWN_TIMESTAMP) return; // We haven't started tracing yet.
        long vsyncTimestamp = mMetricsWrapper.getMetric(FrameMetrics.VSYNC_TIMESTAMP);
        if (vsyncTimestamp < mBeginTime) return; // The tracing has been started.

        // If the end time has not been set, we are still in the tracing.
        if (mEndTime != UNKNOWN_TIMESTAMP && vsyncTimestamp > mEndTime) {
            // The tracing has been ended, remove the observer, see if need to trigger perfetto.
            mRendererWrapper.removeObserver(mObserver);
            // Trigger perfetto if necessary.
            if (mShouldTriggerTrace) {
                if (DEBUG) {
                    Log.v(TAG, "Found janky frame, triggering perfetto.");
                }
                triggerPerfetto();
            }
            return;
        }

        // The frame is in the duration of the CUJ, check if it catches the deadline.
        if (isJankyFrame(mMetricsWrapper)) {
            mShouldTriggerTrace = true;
        }
    }

    /**
     * Trigger the prefetto daemon.
     */
    @VisibleForTesting
    public void triggerPerfetto() {
        InteractionJankMonitor.trigger();
    }

    /**
     * A wrapper class that we can spy FrameMetrics (a final class) in unit tests.
     */
    public static class FrameMetricsWrapper {
        private FrameMetrics mFrameMetrics;

        public FrameMetricsWrapper() {
            mFrameMetrics = new FrameMetrics();
        }

        /**
         * Wrapper method.
         * @return timing data of the metrics
         */
        public long[] getTiming() {
            return mFrameMetrics.mTimingData;
        }

        /**
         * Wrapper method.
         * @param index specific index of the timing data
         * @return the timing data of the specified index
         */
        public long getMetric(int index) {
            return mFrameMetrics.getMetric(index);
        }
    }

    /**
     * A wrapper class that we can spy ThreadedRenderer (a final class) in unit tests.
     */
    public static class ThreadedRendererWrapper {
        private ThreadedRenderer mRenderer;

        public ThreadedRendererWrapper(ThreadedRenderer renderer) {
            mRenderer = renderer;
        }

        /**
         * Wrapper method.
         * @param observer observer
         */
        public void addObserver(HardwareRendererObserver observer) {
            mRenderer.addObserver(observer);
        }

        /**
         * Wrapper method.
         * @param observer observer
         */
        public void removeObserver(HardwareRendererObserver observer) {
            mRenderer.removeObserver(observer);
        }
    }
}
