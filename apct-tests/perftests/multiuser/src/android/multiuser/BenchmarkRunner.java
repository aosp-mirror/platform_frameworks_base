/*
 * Copyright (C) 2017 The Android Open Source Project
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
package android.multiuser;

import android.os.Bundle;
import android.os.SystemClock;

import java.util.ArrayList;

// Based on //platform/frameworks/base/apct-tests/perftests/utils/BenchmarkState.java
public class BenchmarkRunner {

    private static long COOL_OFF_PERIOD_MS = 2000;

    private static final int NUM_ITERATIONS = 4;

    private static final int NOT_STARTED = 0;  // The benchmark has not started yet.
    private static final int RUNNING = 1;  // The benchmark is running.
    private static final int PAUSED = 2; // The benchmark is paused
    private static final int FINISHED = 3;  // The benchmark has stopped.

    private final BenchmarkResults mResults = new BenchmarkResults();
    private int mState = NOT_STARTED;  // Current benchmark state.
    private int mIteration;

    public long mStartTimeNs;
    public long mPausedDurationNs;
    public long mPausedTimeNs;

    public boolean keepRunning() {
        switch (mState) {
            case NOT_STARTED:
                mState = RUNNING;
                prepareForNextRun();
                return true;
            case RUNNING:
                mIteration++;
                return startNextTestRun();
            case PAUSED:
                throw new IllegalStateException("Benchmarking is in paused state");
            case FINISHED:
                throw new IllegalStateException("Benchmarking is finished");
            default:
                throw new IllegalStateException("BenchmarkRunner is in unknown state");
        }
    }

    private boolean startNextTestRun() {
        mResults.addDuration(System.nanoTime() - mStartTimeNs - mPausedDurationNs);
        if (mIteration == NUM_ITERATIONS) {
            mState = FINISHED;
            return false;
        } else {
            prepareForNextRun();
            return true;
        }
    }

    private void prepareForNextRun() {
        // TODO: Once http://b/63115387 is fixed, look into using "am wait-for-broadcast-idle"
        // command instead of waiting for a fixed amount of time.
        SystemClock.sleep(COOL_OFF_PERIOD_MS);
        mStartTimeNs = System.nanoTime();
        mPausedDurationNs = 0;
    }

    public void pauseTiming() {
        if (mState != RUNNING) {
            throw new IllegalStateException("Unable to pause the runner: not running currently");
        }
        mPausedTimeNs = System.nanoTime();
        mState = PAUSED;
    }

    public void resumeTiming() {
        if (mState != PAUSED) {
            throw new IllegalStateException("Unable to resume the runner: already running");
        }
        mPausedDurationNs += System.nanoTime() - mPausedTimeNs;
        mState = RUNNING;
    }

    public Bundle getStatsToReport() {
        return mResults.getStatsToReport();
    }

    public Bundle getStatsToLog() {
        return mResults.getStatsToLog();
    }

    public ArrayList<Long> getAllDurations() {
        return mResults.getAllDurations();
    }
}