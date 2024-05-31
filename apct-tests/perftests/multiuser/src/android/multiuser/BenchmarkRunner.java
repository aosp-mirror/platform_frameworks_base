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
import android.util.Log;

import java.util.ArrayList;

// Based on //platform/frameworks/base/apct-tests/perftests/utils/BenchmarkState.java
public class BenchmarkRunner {
    private static final String TAG = BenchmarkRunner.class.getSimpleName();
    private static final long COOL_OFF_PERIOD_MS = 1000;
    private static final int CPU_IDLE_TIMEOUT_MS = 60 * 1000;
    private static final int CPU_IDLE_THRESHOLD_PERCENTAGE = 90;

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
        waitCoolDownPeriod();
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
        waitCoolDownPeriod();
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

    /** Waits for the broadcast queue and the CPU cores to be idle. */
    public void waitCoolDownPeriod() {
        waitForBroadcastIdle();
        waitForCpuIdle();
    }

    private void waitForBroadcastIdle() {
        Log.d(TAG, "starting to waitForBroadcastIdle");
        final long startedAt = System.currentTimeMillis();
        ShellHelper.runShellCommand("am wait-for-broadcast-idle --flush-broadcast-loopers");
        final long elapsed = System.currentTimeMillis() - startedAt;
        Log.d(TAG, "waitForBroadcastIdle is complete in " + elapsed + " ms");
    }
    private void waitForCpuIdle() {
        Log.d(TAG, "starting to waitForCpuIdle");
        final long startedAt = System.currentTimeMillis();
        while (true) {
            final int idleCpuPercentage = getIdleCpuPercentage();
            final long elapsed = System.currentTimeMillis() - startedAt;
            Log.d(TAG, "waitForCpuIdle " + idleCpuPercentage + "% (" + elapsed + "ms elapsed)");
            if (idleCpuPercentage >= CPU_IDLE_THRESHOLD_PERCENTAGE) {
                Log.d(TAG, "waitForCpuIdle is complete in " + elapsed + " ms");
                return;
            }
            if (elapsed >= CPU_IDLE_TIMEOUT_MS) {
                Log.e(TAG, "Ending waitForCpuIdle because it didn't finish in "
                        + CPU_IDLE_TIMEOUT_MS + " ms");
                return;
            }
            SystemClock.sleep(1000);
        }
    }

    private int getIdleCpuPercentage() {
        String output = ShellHelper.runShellCommand("top -m 1 -n 1");
        String[] tokens = output.split("\\s+");
        float totalCpu = -1;
        float idleCpu = -1;
        for (String token : tokens) {
            if (token.contains("%cpu")) {
                totalCpu = Float.parseFloat(token.split("%")[0]);
            } else if (token.contains("%idle")) {
                idleCpu = Float.parseFloat(token.split("%")[0]);
            }
        }
        if (totalCpu < 0 || idleCpu < 0) {
            Log.e(TAG, "Could not get idle cpu percentage, output=" + output);
            return -1;
        }
        return (int) (100 * idleCpu / totalCpu);
    }
}