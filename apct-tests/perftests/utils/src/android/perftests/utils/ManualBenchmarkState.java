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

package android.perftests.utils;

import android.app.Activity;
import android.app.Instrumentation;
import android.os.Bundle;
import android.util.Log;

import java.util.ArrayList;
import java.util.concurrent.TimeUnit;

/**
 * Provides a benchmark framework.
 *
 * This differs from BenchmarkState in that rather than the class measuring the the elapsed time,
 * the test passes in the elapsed time.
 *
 * Example usage:
 *
 * public void sampleMethod() {
 *     ManualBenchmarkState state = new ManualBenchmarkState();
 *
 *     int[] src = new int[] { 1, 2, 3, 4, 5, 6, 7, 8, 9, 10 };
 *     long elapsedTime = 0;
 *     while (state.keepRunning(elapsedTime)) {
 *         long startTime = System.nanoTime();
 *         int[] dest = new int[src.length];
 *         System.arraycopy(src, 0, dest, 0, src.length);
 *         elapsedTime = System.nanoTime() - startTime;
 *     }
 *     System.out.println(state.summaryLine());
 * }
 *
 * Or use the PerfManualStatusReporter TestRule.
 *
 * Make sure that the overhead of checking the clock does not noticeably affect the results.
 */
public final class ManualBenchmarkState {
    private static final String TAG = ManualBenchmarkState.class.getSimpleName();

    // TODO: Tune these values.
    // warm-up for duration
    private static final long WARMUP_DURATION_NS = TimeUnit.SECONDS.toNanos(5);
    // minimum iterations to warm-up for
    private static final int WARMUP_MIN_ITERATIONS = 8;

    // target testing for duration
    private static final long TARGET_TEST_DURATION_NS = TimeUnit.SECONDS.toNanos(16);
    private static final int MAX_TEST_ITERATIONS = 1000000;
    private static final int MIN_TEST_ITERATIONS = 10;

    private static final int NOT_STARTED = 0;  // The benchmark has not started yet.
    private static final int WARMUP = 1; // The benchmark is warming up.
    private static final int RUNNING = 2;  // The benchmark is running.
    private static final int FINISHED = 3;  // The benchmark has stopped.

    private int mState = NOT_STARTED;  // Current benchmark state.

    private long mWarmupStartTime = 0;
    private int mWarmupIterations = 0;

    private int mMaxIterations = 0;

    // Individual duration in nano seconds.
    private ArrayList<Long> mResults = new ArrayList<>();

    // Statistics. These values will be filled when the benchmark has finished.
    // The computation needs double precision, but long int is fine for final reporting.
    private Stats mStats;

    private void beginBenchmark(long warmupDuration, int iterations) {
        mMaxIterations = (int) (TARGET_TEST_DURATION_NS / (warmupDuration / iterations));
        mMaxIterations = Math.min(MAX_TEST_ITERATIONS,
                Math.max(mMaxIterations, MIN_TEST_ITERATIONS));
        mState = RUNNING;
    }

    /**
     * Judges whether the benchmark needs more samples.
     *
     * For the usage, see class comment.
     */
    public boolean keepRunning(long duration) {
        if (duration < 0) {
            throw new RuntimeException("duration is negative: " + duration);
        }
        switch (mState) {
            case NOT_STARTED:
                mState = WARMUP;
                mWarmupStartTime = System.nanoTime();
                return true;
            case WARMUP: {
                final long timeSinceStartingWarmup = System.nanoTime() - mWarmupStartTime;
                ++mWarmupIterations;
                if (mWarmupIterations >= WARMUP_MIN_ITERATIONS
                        && timeSinceStartingWarmup >= WARMUP_DURATION_NS) {
                    beginBenchmark(timeSinceStartingWarmup, mWarmupIterations);
                }
                return true;
            }
            case RUNNING: {
                mResults.add(duration);
                final boolean keepRunning = mResults.size() < mMaxIterations;
                if (!keepRunning) {
                    mStats = new Stats(mResults);
                    mState = FINISHED;
                }
                return keepRunning;
            }
            case FINISHED:
                throw new IllegalStateException("The benchmark has finished.");
            default:
                throw new IllegalStateException("The benchmark is in an unknown state.");
        }
    }

    private String summaryLine() {
        final StringBuilder sb = new StringBuilder();
        sb.append("Summary: ");
        sb.append("median=").append(mStats.getMedian()).append("ns, ");
        sb.append("mean=").append(mStats.getMean()).append("ns, ");
        sb.append("min=").append(mStats.getMin()).append("ns, ");
        sb.append("max=").append(mStats.getMax()).append("ns, ");
        sb.append("sigma=").append(mStats.getStandardDeviation()).append(", ");
        sb.append("iteration=").append(mResults.size()).append(", ");
        sb.append("values=").append(mResults.toString());
        return sb.toString();
    }

    public void sendFullStatusReport(Instrumentation instrumentation, String key) {
        if (mState != FINISHED) {
            throw new IllegalStateException("The benchmark hasn't finished");
        }
        Log.i(TAG, key + summaryLine());
        final Bundle status = new Bundle();
        status.putLong(key + "_median", mStats.getMedian());
        status.putLong(key + "_mean", (long) mStats.getMean());
        status.putLong(key + "_percentile90", mStats.getPercentile90());
        status.putLong(key + "_percentile95", mStats.getPercentile95());
        status.putLong(key + "_stddev", (long) mStats.getStandardDeviation());
        instrumentation.sendStatus(Activity.RESULT_OK, status);
    }
}

