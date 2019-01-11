/*
 * Copyright (C) 2016 The Android Open Source Project
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

package android.net.util;

import android.os.SystemClock;


/**
 * @hide
 */
public class Stopwatch {
    private long mStartTimeMs;
    private long mStopTimeMs;

    public boolean isStarted() {
        return (mStartTimeMs > 0);
    }

    public boolean isStopped() {
        return (mStopTimeMs > 0);
    }

    public boolean isRunning() {
        return (isStarted() && !isStopped());
    }

    /**
     * Start the Stopwatch.
     */
    public Stopwatch start() {
        if (!isStarted()) {
            mStartTimeMs = SystemClock.elapsedRealtime();
        }
        return this;
    }

    /**
     * Stop the Stopwatch.
     * @return the total time recorded, in milliseconds, or 0 if not started.
     */
    public long stop() {
        if (isRunning()) {
            mStopTimeMs = SystemClock.elapsedRealtime();
        }
        // Return either the delta after having stopped, or 0.
        return (mStopTimeMs - mStartTimeMs);
    }

    /**
     * Return the total time recorded to date, in milliseconds.
     * If the Stopwatch is not running, returns the same value as stop(),
     * i.e. either the total time recorded before stopping or 0.
     */
    public long lap() {
        if (isRunning()) {
            return (SystemClock.elapsedRealtime() - mStartTimeMs);
        } else {
            return stop();
        }
    }

    /**
     * Reset the Stopwatch. It will be stopped when this method returns.
     */
    public void reset() {
        mStartTimeMs = 0;
        mStopTimeMs = 0;
    }
}
