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

import static android.view.SurfaceControl.JankData.BUFFER_STUFFING;
import static android.view.SurfaceControl.JankData.DISPLAY_HAL;
import static android.view.SurfaceControl.JankData.JANK_APP_DEADLINE_MISSED;
import static android.view.SurfaceControl.JankData.JANK_NONE;
import static android.view.SurfaceControl.JankData.JANK_SURFACEFLINGER_DEADLINE_MISSED;
import static android.view.SurfaceControl.JankData.JANK_SURFACEFLINGER_GPU_DEADLINE_MISSED;
import static android.view.SurfaceControl.JankData.PREDICTION_ERROR;
import static android.view.SurfaceControl.JankData.SURFACE_FLINGER_SCHEDULING;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.graphics.HardwareRendererObserver;
import android.os.Handler;
import android.os.Trace;
import android.util.Log;
import android.util.SparseArray;
import android.view.Choreographer;
import android.view.FrameMetrics;
import android.view.SurfaceControl;
import android.view.SurfaceControl.JankData.JankType;
import android.view.ThreadedRenderer;
import android.view.ViewRootImpl;

import com.android.internal.jank.InteractionJankMonitor.Session;
import com.android.internal.util.FrameworkStatsLog;

/**
 * A class that allows the app to get the frame metrics from HardwareRendererObserver.
 * @hide
 */
public class FrameTracker extends SurfaceControl.OnJankDataListener
        implements HardwareRendererObserver.OnFrameMetricsAvailableListener {
    private static final String TAG = "FrameTracker";
    private static final boolean DEBUG = false;

    private static final long INVALID_ID = -1;
    public static final int NANOS_IN_MILLISECOND = 1_000_000;

    private final HardwareRendererObserver mObserver;
    private SurfaceControl mSurfaceControl;
    private final int mTraceThresholdMissedFrames;
    private final int mTraceThresholdFrameTimeMillis;
    private final ThreadedRendererWrapper mRendererWrapper;
    private final FrameMetricsWrapper mMetricsWrapper;
    private final SparseArray<JankInfo> mJankInfos = new SparseArray<>();
    private final Session mSession;
    private final ViewRootWrapper mViewRoot;
    private final SurfaceControlWrapper mSurfaceControlWrapper;
    private final ViewRootImpl.SurfaceChangedCallback mSurfaceChangedCallback;
    private final Handler mHandler;
    private final ChoreographerWrapper mChoreographer;

    private long mBeginVsyncId = INVALID_ID;
    private long mEndVsyncId = INVALID_ID;
    private boolean mMetricsFinalized;
    private boolean mCancelled = false;

    private static class JankInfo {
        long frameVsyncId;
        long totalDurationNanos;
        boolean isFirstFrame;
        boolean hwuiCallbackFired;
        boolean surfaceControlCallbackFired;
        @JankType int jankType;

        static JankInfo createFromHwuiCallback(long frameVsyncId, long totalDurationNanos,
                boolean isFirstFrame) {
            return new JankInfo(frameVsyncId, true, false, JANK_NONE, totalDurationNanos,
                    isFirstFrame);
        }

        static JankInfo createFromSurfaceControlCallback(long frameVsyncId,
                @JankType int jankType) {
            return new JankInfo(frameVsyncId, false, true, jankType, 0, false /* isFirstFrame */);
        }

        private JankInfo(long frameVsyncId, boolean hwuiCallbackFired,
                boolean surfaceControlCallbackFired, @JankType int jankType,
                long totalDurationNanos, boolean isFirstFrame) {
            this.frameVsyncId = frameVsyncId;
            this.hwuiCallbackFired = hwuiCallbackFired;
            this.surfaceControlCallbackFired = surfaceControlCallbackFired;
            this.totalDurationNanos = totalDurationNanos;
            this.jankType = jankType;
            this.isFirstFrame = isFirstFrame;
        }
    }

    public FrameTracker(@NonNull Session session, @NonNull Handler handler,
            @NonNull ThreadedRendererWrapper renderer, @NonNull ViewRootWrapper viewRootWrapper,
            @NonNull SurfaceControlWrapper surfaceControlWrapper,
            @NonNull ChoreographerWrapper choreographer,
            @NonNull FrameMetricsWrapper metrics, int traceThresholdMissedFrames,
            int traceThresholdFrameTimeMillis) {
        mSession = session;
        mRendererWrapper = renderer;
        mMetricsWrapper = metrics;
        mViewRoot = viewRootWrapper;
        mChoreographer = choreographer;
        mSurfaceControlWrapper = surfaceControlWrapper;
        mHandler = handler;
        mObserver = new HardwareRendererObserver(this, mMetricsWrapper.getTiming(), handler);
        mTraceThresholdMissedFrames = traceThresholdMissedFrames;
        mTraceThresholdFrameTimeMillis = traceThresholdFrameTimeMillis;

        // If the surface isn't valid yet, wait until it's created.
        if (viewRootWrapper.getSurfaceControl().isValid()) {
            mSurfaceControl = viewRootWrapper.getSurfaceControl();
        }
        mSurfaceChangedCallback = new ViewRootImpl.SurfaceChangedCallback() {
            @Override
            public void surfaceCreated(SurfaceControl.Transaction t) {
                synchronized (FrameTracker.this) {
                    if (mSurfaceControl == null) {
                        mSurfaceControl = viewRootWrapper.getSurfaceControl();
                        if (mBeginVsyncId != INVALID_ID) {
                            mSurfaceControlWrapper.addJankStatsListener(
                                    FrameTracker.this, mSurfaceControl);
                        }
                    }
                }
            }

            @Override
            public void surfaceReplaced(SurfaceControl.Transaction t) {
            }

            @Override
            public void surfaceDestroyed() {

                // Wait a while to give the system a chance for the remaining frames to arrive, then
                // force finish the session.
                mHandler.postDelayed(() -> {
                    synchronized (FrameTracker.this) {
                        if (!mMetricsFinalized) {
                            finish(mJankInfos.size() - 1);
                        }
                    }
                }, 50);
            }
        };
        viewRootWrapper.addSurfaceChangedCallback(mSurfaceChangedCallback);
    }

    /**
     * Begin a trace session of the CUJ.
     */
    public synchronized void begin() {
        mBeginVsyncId = mChoreographer.getVsyncId() + 1;
        Trace.beginAsyncSection(mSession.getName(), (int) mBeginVsyncId);
        mRendererWrapper.addObserver(mObserver);
        if (mSurfaceControl != null) {
            mSurfaceControlWrapper.addJankStatsListener(this, mSurfaceControl);
        }
    }

    /**
     * End the trace session of the CUJ.
     */
    public synchronized void end() {
        mEndVsyncId = mChoreographer.getVsyncId();
        Trace.endAsyncSection(mSession.getName(), (int) mBeginVsyncId);
        if (mEndVsyncId == mBeginVsyncId) {
            cancel();
        }
        // We don't remove observer here,
        // will remove it when all the frame metrics in this duration are called back.
        // See onFrameMetricsAvailable for the logic of removing the observer.
    }

    /**
     * Cancel the trace session of the CUJ.
     */
    public synchronized void cancel() {
        if (mBeginVsyncId == INVALID_ID || mEndVsyncId != INVALID_ID) return;
        Trace.endAsyncSection(mSession.getName(), (int) mBeginVsyncId);
        mCancelled = true;
        removeObservers();
    }

    @Override
    public synchronized void onJankDataAvailable(SurfaceControl.JankData[] jankData) {
        if (mCancelled) {
            return;
        }

        for (SurfaceControl.JankData jankStat : jankData) {
            if (!isInRange(jankStat.frameVsyncId)) {
                continue;
            }
            JankInfo info = findJankInfo(jankStat.frameVsyncId);
            if (info != null) {
                info.surfaceControlCallbackFired = true;
                info.jankType = jankStat.jankType;
            } else {
                mJankInfos.put((int) jankStat.frameVsyncId,
                        JankInfo.createFromSurfaceControlCallback(
                                jankStat.frameVsyncId, jankStat.jankType));
            }
        }
        processJankInfos();
    }

    private @Nullable JankInfo findJankInfo(long frameVsyncId) {
        return mJankInfos.get((int) frameVsyncId);
    }

    private boolean isInRange(long vsyncId) {

        // It's possible that we may miss a callback for the frame with vsyncId == mEndVsyncId.
        // Because of that, we collect all frames even if they happen after the end so we eventually
        // have a frame after the end with both callbacks present.
        return vsyncId >= mBeginVsyncId;
    }

    @Override
    public synchronized void onFrameMetricsAvailable(int dropCountSinceLastInvocation) {
        if (mCancelled) {
            return;
        }

        // Since this callback might come a little bit late after the end() call.
        // We should keep tracking the begin / end timestamp.
        // Then compare with vsync timestamp to check if the frame is in the duration of the CUJ.
        long totalDurationNanos = mMetricsWrapper.getMetric(FrameMetrics.TOTAL_DURATION);
        boolean isFirstFrame = mMetricsWrapper.getMetric(FrameMetrics.FIRST_DRAW_FRAME) == 1;
        long frameVsyncId = mMetricsWrapper.getTiming()[FrameMetrics.Index.FRAME_TIMELINE_VSYNC_ID];

        if (!isInRange(frameVsyncId)) {
            return;
        }
        JankInfo info = findJankInfo(frameVsyncId);
        if (info != null) {
            info.hwuiCallbackFired = true;
            info.totalDurationNanos = totalDurationNanos;
            info.isFirstFrame = isFirstFrame;
        } else {
            mJankInfos.put((int) frameVsyncId, JankInfo.createFromHwuiCallback(
                    frameVsyncId, totalDurationNanos, isFirstFrame));
        }
        processJankInfos();
    }

    /**
     * Finds the first index in {@link #mJankInfos} which happened on or after {@link #mEndVsyncId},
     * or -1 if the session hasn't ended yet.
     */
    private int getIndexOnOrAfterEnd() {
        if (mEndVsyncId == INVALID_ID || mMetricsFinalized) {
            return -1;
        }
        JankInfo last = mJankInfos.size() == 0 ? null : mJankInfos.valueAt(mJankInfos.size() - 1);
        if (last == null) {
            return -1;
        }
        if (last.frameVsyncId < mEndVsyncId) {
            return -1;
        }

        int lastIndex = -1;
        for (int i = mJankInfos.size() - 1; i >= 0; i--) {
            JankInfo info = mJankInfos.valueAt(i);
            if (info.frameVsyncId >= mEndVsyncId) {
                if (info.hwuiCallbackFired && info.surfaceControlCallbackFired) {
                    lastIndex = i;
                }
            } else {
                break;
            }
        }
        return lastIndex;
    }

    private void processJankInfos() {
        int indexOnOrAfterEnd = getIndexOnOrAfterEnd();
        if (indexOnOrAfterEnd == -1) {
            return;
        }
        finish(indexOnOrAfterEnd);
    }

    private void finish(int indexOnOrAfterEnd) {

        mMetricsFinalized = true;

        // The tracing has been ended, remove the observer, see if need to trigger perfetto.
        removeObservers();

        int totalFramesCount = 0;
        long maxFrameTimeNanos = 0;
        int missedAppFramesCount = 0;
        int missedSfFramesCounts = 0;

        for (int i = 0; i <= indexOnOrAfterEnd; i++) {
            JankInfo info = mJankInfos.valueAt(i);
            if (info.isFirstFrame) {
                continue;
            }
            if (info.surfaceControlCallbackFired) {
                totalFramesCount++;
                if ((info.jankType & PREDICTION_ERROR) != 0
                        || ((info.jankType & JANK_APP_DEADLINE_MISSED) != 0)) {
                    Log.w(TAG, "Missed App frame:" + info.jankType);
                    missedAppFramesCount++;
                }
                if ((info.jankType & DISPLAY_HAL) != 0
                        || (info.jankType & JANK_SURFACEFLINGER_DEADLINE_MISSED) != 0
                        || (info.jankType & JANK_SURFACEFLINGER_GPU_DEADLINE_MISSED) != 0
                        || (info.jankType & SURFACE_FLINGER_SCHEDULING) != 0) {
                    Log.w(TAG, "Missed SF frame:" + info.jankType);
                    missedSfFramesCounts++;
                }
                // TODO (b/174755489): Early latch currently gets fired way too often, so we have
                // to ignore it for now.
                if (!info.hwuiCallbackFired) {
                    Log.w(TAG, "Missing HWUI jank callback for vsyncId: " + info.frameVsyncId);
                }
            }
            if (info.hwuiCallbackFired) {
                maxFrameTimeNanos = Math.max(info.totalDurationNanos, maxFrameTimeNanos);
                if (!info.surfaceControlCallbackFired) {
                    Log.w(TAG, "Missing SF jank callback for vsyncId: " + info.frameVsyncId);
                }
            }
        }

        // Log the frame stats as counters to make them easily accessible in traces.
        Trace.traceCounter(Trace.TRACE_TAG_APP, mSession.getName() + "#missedAppFrames",
                missedAppFramesCount);
        Trace.traceCounter(Trace.TRACE_TAG_APP, mSession.getName() + "#missedSfFrames",
                missedSfFramesCounts);
        Trace.traceCounter(Trace.TRACE_TAG_APP, mSession.getName() + "#totalFrames",
                totalFramesCount);
        Trace.traceCounter(Trace.TRACE_TAG_APP, mSession.getName() + "#maxFrameTimeMillis",
                (int) (maxFrameTimeNanos / NANOS_IN_MILLISECOND));

        // Trigger perfetto if necessary.
        boolean overMissedFramesThreshold = mTraceThresholdMissedFrames != -1
                && missedAppFramesCount + missedSfFramesCounts >= mTraceThresholdMissedFrames;
        boolean overFrameTimeThreshold = mTraceThresholdFrameTimeMillis != -1
                && maxFrameTimeNanos >= mTraceThresholdFrameTimeMillis * NANOS_IN_MILLISECOND;
        if (overMissedFramesThreshold || overFrameTimeThreshold) {
            triggerPerfetto();
        }
        if (mSession.logToStatsd()) {
            FrameworkStatsLog.write(
                    FrameworkStatsLog.UI_INTERACTION_FRAME_INFO_REPORTED,
                    mSession.getStatsdInteractionType(),
                    totalFramesCount,
                    missedAppFramesCount + missedSfFramesCounts,
                    maxFrameTimeNanos,
                    missedSfFramesCounts);
        }
        if (DEBUG) {
            Log.i(TAG, "FrameTracker: CUJ=" + mSession.getName()
                    + " totalFrames=" + totalFramesCount
                    + " missedAppFrames=" + missedAppFramesCount
                    + " missedSfFrames=" + missedSfFramesCounts
                    + " maxFrameTimeMillis=" + maxFrameTimeNanos / NANOS_IN_MILLISECOND);
        }
    }

    private void removeObservers() {
        mRendererWrapper.removeObserver(mObserver);
        mSurfaceControlWrapper.removeJankStatsListener(this);
        if (mSurfaceChangedCallback != null) {
            mViewRoot.removeSurfaceChangedCallback(mSurfaceChangedCallback);
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
        private final FrameMetrics mFrameMetrics;

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
        private final ThreadedRenderer mRenderer;

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

    public static class ViewRootWrapper {
        private final ViewRootImpl mViewRoot;

        public ViewRootWrapper(ViewRootImpl viewRoot) {
            mViewRoot = viewRoot;
        }

        public void addSurfaceChangedCallback(ViewRootImpl.SurfaceChangedCallback callback) {
            mViewRoot.addSurfaceChangedCallback(callback);
        }

        public void removeSurfaceChangedCallback(ViewRootImpl.SurfaceChangedCallback callback) {
            mViewRoot.removeSurfaceChangedCallback(callback);
        }

        public SurfaceControl getSurfaceControl() {
            return mViewRoot.getSurfaceControl();
        }
    }

    public static class SurfaceControlWrapper {

        public void addJankStatsListener(SurfaceControl.OnJankDataListener listener,
                SurfaceControl surfaceControl) {
            SurfaceControl.addJankDataListener(listener, surfaceControl);
        }

        public void removeJankStatsListener(SurfaceControl.OnJankDataListener listener) {
            SurfaceControl.removeJankDataListener(listener);
        }
    }

    public static class ChoreographerWrapper {

        private final Choreographer mChoreographer;

        public ChoreographerWrapper(Choreographer choreographer) {
            mChoreographer = choreographer;
        }

        public long getVsyncId() {
            return mChoreographer.getVsyncId();
        }
    }
}
