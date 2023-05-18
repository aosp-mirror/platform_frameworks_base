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

import android.annotation.Nullable;
import android.os.Bundle;
import android.os.SystemClock;
import android.perftests.utils.ShellHelper;

import java.util.ArrayList;

// Based on //platform/frameworks/base/apct-tests/perftests/utils/BenchmarkState.java
public class BenchmarkRunner {

    private static final long COOL_OFF_PERIOD_MS = 1000;

    private static final int NUM_ITERATIONS = 4;

    private static final int NOT_STARTED = 0;  // The benchmark has not started yet.
    private static final int RUNNING = 1;  // The benchmark is running.
    private static final int PAUSED = 2; // The benchmark is paused
    private static final int FINISHED = 3;  // The benchmark has stopped.

    private final BenchmarkResults mResults = new BenchmarkResults();
    private int mState = NOT_STARTED;  // Current benchmark state.
    private int mIteration = 1;

    public long mStartTimeNs;
    public long mPausedDurationNs;
    public long mPausedTimeNs;

    private Throwable mFirstFailure = null;

    /**
     * Starts a new run. Also responsible for finalising the calculations from the previous run,
     * if there was one; therefore, any previous run must not be {@link #pauseTiming() paused} when
     * this is called.
     */
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
        if (mIteration == NUM_ITERATIONS + 1) {
            mState = FINISHED;
            return false;
        } else {
            prepareForNextRun();
            return true;
        }
    }

    private void prepareForNextRun() {
        SystemClock.sleep(COOL_OFF_PERIOD_MS);
        ShellHelper.runShellCommand("am wait-for-broadcast-idle --flush-broadcast-loopers");
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

    /**
     * Resumes the timing after a previous {@link #pauseTiming()}.
     * First waits for the system to be idle prior to resuming.
     *
     * If this is called at the end of the run (so that no further timing is actually desired before
     * {@link #keepRunning()} is called anyway), use {@link #resumeTimingForNextIteration()} instead
     * to avoid unnecessary waiting.
     */
    public void resumeTiming() {
        ShellHelper.runShellCommand("am wait-for-broadcast-idle --flush-broadcast-loopers");
        resumeTimer();
    }

    /**
     * Resume timing in preparation for a possible next run (rather than to continue timing the
     * current run).
     *
     * It is equivalent to {@link #resumeTiming()} except that it skips steps that
     * are unnecessary at the end of a trial (namely, waiting for the system to idle).
     */
    public void resumeTimingForNextIteration() {
        resumeTimer();
    }

    private void resumeTimer() {
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

    /** Returns which iteration (starting at 1) the Runner is currently on. */
    public int getIteration() {
        return mIteration;
    }

    /**
     * Marks the test run as failed, along with a message of why.
     * Only the first fail message is retained.
     */
    public void markAsFailed(Throwable err) {
        if (mFirstFailure == null) {
            mFirstFailure = err;
        }
    }

    /** Gets the failure message if the test failed; otherwise {@code null}. */
    public @Nullable Throwable getErrorOrNull() {
        if (mFirstFailure != null) {
            return mFirstFailure;
        }
        if (mState != FINISHED) {
            return new AssertionError("BenchmarkRunner state is not FINISHED.");
        }
        return null;
    }
}