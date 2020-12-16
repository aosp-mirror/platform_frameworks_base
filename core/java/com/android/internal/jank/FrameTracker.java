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

import com.android.internal.jank.InteractionJankMonitor.Session;
import com.android.internal.util.FrameworkStatsLog;

/**
 * A class that allows the app to get the frame metrics from HardwareRendererObserver.
 * @hide
 */
public class FrameTracker implements HardwareRendererObserver.OnFrameMetricsAvailableListener {
    private static final String TAG = FrameTracker.class.getSimpleName();
    private static final boolean DEBUG = false;
    //TODO (163431584): need also consider other refresh rates.
    private static final long JANK_THRESHOLD_NANOS = 1000000000 / 60;
    private static final long UNKNOWN_TIMESTAMP = -1;
    public static final int NANOS_IN_MILLISECOND = 1_000_000;

    private final HardwareRendererObserver mObserver;
    private final int mTraceThresholdMissedFrames;
    private final int mTraceThresholdFrameTimeMillis;
    private final ThreadedRendererWrapper mRendererWrapper;
    private final FrameMetricsWrapper mMetricsWrapper;

    private long mBeginTime = UNKNOWN_TIMESTAMP;
    private long mEndTime = UNKNOWN_TIMESTAMP;
    private boolean mSessionEnd;
    private boolean mCancelled = false;
    private int mTotalFramesCount = 0;
    private int mMissedFramesCount = 0;
    private int mSfMissedFramesCount = 0;
    private long mMaxFrameTimeNanos = 0;

    private Session mSession;

    public FrameTracker(@NonNull Session session, @NonNull Handler handler,
            @NonNull ThreadedRendererWrapper renderer, @NonNull FrameMetricsWrapper metrics,
            int traceThresholdMissedFrames, int traceThresholdFrameTimeMillis) {
        mSession = session;
        mRendererWrapper = renderer;
        mMetricsWrapper = metrics;
        mObserver = new HardwareRendererObserver(this, mMetricsWrapper.getTiming(), handler);
        mTraceThresholdMissedFrames = traceThresholdMissedFrames;
        mTraceThresholdFrameTimeMillis = traceThresholdFrameTimeMillis;
    }

    /**
     * Begin a trace session of the CUJ.
     */
    public synchronized void begin() {
        long timestamp = System.nanoTime();
        if (DEBUG) {
            Log.d(TAG, "begin: time(ns)=" + timestamp + ", begin(ns)=" + mBeginTime
                    + ", end(ns)=" + mEndTime + ", session=" + mSession.getName());
        }
        mBeginTime = timestamp;
        mEndTime = UNKNOWN_TIMESTAMP;
        Trace.beginAsyncSection(mSession.getName(), (int) mBeginTime);
        mRendererWrapper.addObserver(mObserver);
    }

    /**
     * End the trace session of the CUJ.
     */
    public synchronized void end() {
        long timestamp = System.nanoTime();
        if (DEBUG) {
            Log.d(TAG, "end: time(ns)=" + timestamp + ", begin(ns)=" + mBeginTime
                    + ", end(ns)=" + mEndTime + ", session=" + mSession.getName());
        }
        mEndTime = timestamp;
        Trace.endAsyncSection(mSession.getName(), (int) mBeginTime);
        // We don't remove observer here,
        // will remove it when all the frame metrics in this duration are called back.
        // See onFrameMetricsAvailable for the logic of removing the observer.
    }

    /**
     * Cancel the trace session of the CUJ.
     */
    public synchronized void cancel() {
        if (mBeginTime == UNKNOWN_TIMESTAMP || mEndTime != UNKNOWN_TIMESTAMP) return;
        if (DEBUG) {
            Log.d(TAG, "cancel: time(ns)=" + System.nanoTime() + ", begin(ns)=" + mBeginTime
                    + ", end(ns)=" + mEndTime + ", session=" + mSession.getName());
        }
        Trace.endAsyncSection(mSession.getName(), (int) mBeginTime);
        mRendererWrapper.removeObserver(mObserver);
        mCancelled = true;
    }

    @Override
    public synchronized void onFrameMetricsAvailable(int dropCountSinceLastInvocation) {
        if (mCancelled) {
            return;
        }

        // Since this callback might come a little bit late after the end() call.
        // We should keep tracking the begin / end timestamp.
        // Then compare with vsync timestamp to check if the frame is in the duration of the CUJ.

        long vsyncTimestamp = mMetricsWrapper.getMetric(FrameMetrics.VSYNC_TIMESTAMP);
        // Discard the frame metrics which is not in the trace session.
        if (vsyncTimestamp < mBeginTime) return;

        // We stop getting callback when the vsync is later than the end calls.
        if (mEndTime != UNKNOWN_TIMESTAMP && vsyncTimestamp > mEndTime && !mSessionEnd) {
            mSessionEnd = true;
            // The tracing has been ended, remove the observer, see if need to trigger perfetto.
            mRendererWrapper.removeObserver(mObserver);

            // Log the frame stats as counters to make them easily accessible in traces.
            Trace.traceCounter(Trace.TRACE_TAG_APP, mSession.getName() + "#sfMissedFrames",
                    mSfMissedFramesCount);
            Trace.traceCounter(Trace.TRACE_TAG_APP, mSession.getName() + "#missedFrames",
                    mMissedFramesCount);
            Trace.traceCounter(Trace.TRACE_TAG_APP, mSession.getName() + "#totalFrames",
                    mTotalFramesCount);
            Trace.traceCounter(Trace.TRACE_TAG_APP, mSession.getName() + "#maxFrameTimeMillis",
                    (int) (mMaxFrameTimeNanos / NANOS_IN_MILLISECOND));

            // Trigger perfetto if necessary.
            boolean overMissedFramesThreshold = mTraceThresholdMissedFrames != -1
                    && (mMissedFramesCount + mSfMissedFramesCount) >= mTraceThresholdMissedFrames;
            boolean overFrameTimeThreshold = mTraceThresholdFrameTimeMillis != -1
                    && mMaxFrameTimeNanos >= mTraceThresholdFrameTimeMillis * NANOS_IN_MILLISECOND;
            if (overMissedFramesThreshold || overFrameTimeThreshold) {
                triggerPerfetto();
            }
            if (mSession.logToStatsd()) {
                FrameworkStatsLog.write(
                        FrameworkStatsLog.UI_INTERACTION_FRAME_INFO_REPORTED,
                        mSession.getStatsdInteractionType(),
                        mTotalFramesCount,
                        mMissedFramesCount,
                        mMaxFrameTimeNanos,
                        mSfMissedFramesCount);
            }
            return;
        }

        long totalDurationNanos = mMetricsWrapper.getMetric(FrameMetrics.TOTAL_DURATION);
        boolean isFirstFrame = mMetricsWrapper.getMetric(FrameMetrics.FIRST_DRAW_FRAME) == 1;
        boolean isJankyFrame = !isFirstFrame && totalDurationNanos > JANK_THRESHOLD_NANOS;

        mTotalFramesCount += 1;

        if (!isFirstFrame) {
            mMaxFrameTimeNanos = Math.max(totalDurationNanos, mMaxFrameTimeNanos);
        }

        // TODO(b/171049584): Also update mSfMissedFramesCount once the data is available.
        if (isJankyFrame) {
            mMissedFramesCount += 1;
        }
    }

    /**
     * Trigger the prefetto daemon.
     */
    public void triggerPerfetto() {
        InteractionJankMonitor.getInstance().trigger(mSession);
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
