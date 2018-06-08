/*
 * Copyright (C) 2015 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the License);
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an AS IS BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.benchmark.ui.automation;

import android.annotation.TargetApi;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Message;
import android.os.SystemClock;
import android.view.FrameMetrics;
import android.view.Window;

import java.lang.ref.WeakReference;
import java.util.LinkedList;
import java.util.List;

/**
 *
 */
final class CollectorThread extends HandlerThread {
    private FrameStatsCollector mCollector;
    private Window mAttachedWindow;
    private List<FrameMetrics> mFrameTimingStats;
    private long mLastFrameTime;
    private WatchdogHandler mWatchdog;
    private WeakReference<CollectorListener> mListener;

    private volatile boolean mCollecting;


    interface CollectorListener {
        void onCollectorThreadReady();
        void onPostInteraction(List<FrameMetrics> stats);
    }

    private final class WatchdogHandler extends Handler {
        private static final long SCHEDULE_INTERVAL_MILLIS = 20 * Automator.FRAME_PERIOD_MILLIS;

        private static final int MSG_SCHEDULE = 0;

        @Override
        public void handleMessage(Message msg) {
            if (!mCollecting) {
                return;
            }

            long currentTime = SystemClock.uptimeMillis();
            if (mLastFrameTime + SCHEDULE_INTERVAL_MILLIS <= currentTime) {
                // haven't seen a frame in a while, interaction is probably done
                mCollecting = false;
                CollectorListener listener = mListener.get();
                if (listener != null) {
                    listener.onPostInteraction(mFrameTimingStats);
                }
            } else {
                schedule();
            }
        }

        public void schedule() {
            sendMessageDelayed(obtainMessage(MSG_SCHEDULE), SCHEDULE_INTERVAL_MILLIS);
        }

        public void deschedule() {
            removeMessages(MSG_SCHEDULE);
        }
    }

    static boolean tripleBuffered = false;
    static int janks = 0;
    static int total = 0;
    @TargetApi(24)
    private class FrameStatsCollector implements Window.OnFrameMetricsAvailableListener {
        @Override
        public void onFrameMetricsAvailable(Window window, FrameMetrics frameMetrics, int dropCount) {
            if (!mCollecting) {
                return;
            }
            mFrameTimingStats.add(new FrameMetrics(frameMetrics));
            mLastFrameTime = SystemClock.uptimeMillis();
        }
    }

    public CollectorThread(CollectorListener listener) {
        super("FrameStatsCollectorThread");
        mFrameTimingStats = new LinkedList<>();
        mListener = new WeakReference<>(listener);
    }

    @TargetApi(24)
    public void attachToWindow(Window window) {
        if (mAttachedWindow != null) {
            mAttachedWindow.removeOnFrameMetricsAvailableListener(mCollector);
        }

        mAttachedWindow = window;
        window.addOnFrameMetricsAvailableListener(mCollector, new Handler(getLooper()));
    }

    @TargetApi(24)
    public synchronized void detachFromWindow() {
        if (mAttachedWindow != null) {
            mAttachedWindow.removeOnFrameMetricsAvailableListener(mCollector);
        }

        mAttachedWindow = null;
    }

    @TargetApi(24)
    @Override
    protected void onLooperPrepared() {
        super.onLooperPrepared();
        mCollector = new FrameStatsCollector();
        mWatchdog = new WatchdogHandler();

        CollectorListener listener = mListener.get();
        if (listener != null) {
            listener.onCollectorThreadReady();
        }
    }

    public boolean quitCollector() {
        stopCollecting();
        detachFromWindow();
        System.out.println("Jank Percentage: " + (100 * janks/ (double) total) + "%");
        tripleBuffered = false;
        total = 0;
        janks = 0;
        return quit();
    }

    void stopCollecting() {
        if (!mCollecting) {
            return;
        }

        mCollecting = false;
        mWatchdog.deschedule();


    }

    public void markInteractionStart() {
        mLastFrameTime = 0;
        mFrameTimingStats.clear();
        mCollecting = true;

        mWatchdog.schedule();
    }
}
