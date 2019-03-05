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

package com.android.server.wm.flicker.monitor;

import static android.view.FrameStats.UNDEFINED_TIME_NANO;

import android.app.Instrumentation;
import android.util.Log;
import android.view.FrameStats;

/**
 * Monitors {@link android.view.WindowAnimationFrameStats} to detect janky frames.
 *
 * Adapted from {@link androidx.test.jank.internal.WindowAnimationFrameStatsMonitorImpl}
 * using the same threshold to determine jank.
 */
public class WindowAnimationFrameStatsMonitor implements ITransitionMonitor {

    private static final String TAG = "FLICKER";
    // Maximum normalized error in frame duration before the frame is considered janky
    private static final double MAX_ERROR = 0.5f;
    // Maximum normalized frame duration before the frame is considered a pause
    private static final double PAUSE_THRESHOLD = 15.0f;
    private Instrumentation mInstrumentation;
    private FrameStats stats;
    private int numJankyFrames;
    private long mLongestFrameNano = 0L;


    /**
     * Constructs a WindowAnimationFrameStatsMonitor instance.
     */
    public WindowAnimationFrameStatsMonitor(Instrumentation instrumentation) {
        mInstrumentation = instrumentation;
    }

    private void analyze() {
        int frameCount = stats.getFrameCount();
        long refreshPeriodNano = stats.getRefreshPeriodNano();

        // Skip first frame
        for (int i = 2; i < frameCount; i++) {
            // Handle frames that have not been presented.
            if (stats.getFramePresentedTimeNano(i) == UNDEFINED_TIME_NANO) {
                // The animation must not have completed. Warn and break out of the loop.
                Log.w(TAG, "Skipping fenced frame.");
                break;
            }
            long frameDurationNano = stats.getFramePresentedTimeNano(i) -
                    stats.getFramePresentedTimeNano(i - 1);
            double normalized = (double) frameDurationNano / refreshPeriodNano;
            if (normalized < PAUSE_THRESHOLD) {
                if (normalized > 1.0f + MAX_ERROR) {
                    numJankyFrames++;
                }
                mLongestFrameNano = Math.max(mLongestFrameNano, frameDurationNano);
            }
        }
    }

    @Override
    public void start() {
        // Clear out any previous data
        numJankyFrames = 0;
        mLongestFrameNano = 0;
        mInstrumentation.getUiAutomation().clearWindowAnimationFrameStats();
    }

    @Override
    public void stop() {
        stats = mInstrumentation.getUiAutomation().getWindowAnimationFrameStats();
        analyze();
    }

    public boolean jankyFramesDetected() {
        return stats.getFrameCount() > 0 && numJankyFrames > 0;
    }

    @Override
    public String toString() {
        return stats.toString() +
                " RefreshPeriodNano:" + stats.getRefreshPeriodNano() +
                " NumJankyFrames:" + numJankyFrames +
                " LongestFrameNano:" + mLongestFrameNano;
    }
}