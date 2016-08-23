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

package android.perftests.utils;

import android.app.Activity;
import android.app.Instrumentation;
import android.os.Bundle;
import android.util.Log;

import java.util.ArrayList;
import java.util.Collections;

/**
 * Provides a benchmark framework.
 *
 * Example usage:
 * // Executes the code while keepRunning returning true.
 *
 * public void sampleMethod() {
 *     BenchmarkState state = new BenchmarkState();
 *
 *     int[] src = new int[] { 1, 2, 3, 4, 5, 6, 7, 8, 9, 10 };
 *     while (state.keepRunning()) {
 *         int[] dest = new int[src.length];
 *         System.arraycopy(src, 0, dest, 0, src.length);
 *     }
 *     System.out.println(state.summaryLine());
 * }
 */
public class BenchmarkState {
    private static final String TAG = "BenchmarkState";

    private static final int NOT_STARTED = 1;  // The benchmark has not started yet.
    private static final int RUNNING = 2;  // The benchmark is running.
    private static final int RUNNING_PAUSED = 3;  // The benchmark is temporary paused.
    private static final int FINISHED = 4;  // The benchmark has stopped.

    private int mState = NOT_STARTED;  // Current benchmark state.

    private long mNanoPreviousTime = 0;  // Previously captured System.nanoTime().
    private long mNanoFinishTime = 0;  // Finish if System.nanoTime() returns after than this value.
    private long mNanoPausedTime = 0; // The System.nanoTime() when the pauseTiming() is called.
    private long mNanoPausedDuration = 0;  // The duration of paused state in nano sec.
    private long mNanoTimeLimit = 1 * 1000 * 1000 * 1000;  // 1 sec. Default time limit.

    // Statistics. These values will be filled when the benchmark has finished.
    // The computation needs double precision, but long int is fine for final reporting.
    private long mMedian = 0;
    private double mMean = 0.0;
    private double mStandardDeviation = 0.0;

    // Number of iterations needed for calculating the stats.
    private int mMinRepeatTimes = 16;

    // Individual duration in nano seconds.
    private ArrayList<Long> mResults = new ArrayList<>();

    /**
     * Sets the number of iterations needed for calculating the stats. Default is 16.
     */
    public void setMinRepeatTimes(int minRepeatTimes) {
        mMinRepeatTimes = minRepeatTimes;
    }

    /**
     * Calculates statistics.
     */
    private void calculateSatistics() {
        final int size = mResults.size();
        if (size <= 1) {
            throw new IllegalStateException("At least two results are necessary.");
        }

        Collections.sort(mResults);
        mMedian = size % 2 == 0 ? (mResults.get(size / 2) + mResults.get(size / 2 + 1)) / 2 :
                mResults.get(size / 2);

        for (int i = 0; i < size; ++i) {
            mMean += mResults.get(i);
        }
        mMean /= (double) size;

        for (int i = 0; i < size; ++i) {
            final double tmp = mResults.get(i) - mMean;
            mStandardDeviation += tmp * tmp;
        }
        mStandardDeviation = Math.sqrt(mStandardDeviation / (double) (size - 1));
    }

    // Stops the benchmark timer.
    // This method can be called only when the timer is running.
    public void pauseTiming() {
        if (mState == RUNNING_PAUSED) {
            throw new IllegalStateException(
                    "Unable to pause the benchmark. The benchmark has already paused.");
        }
        mNanoPausedTime = System.nanoTime();
        mState = RUNNING_PAUSED;
    }

    // Starts the benchmark timer.
    // This method can be called only when the timer is stopped.
    public void resumeTiming() {
        if (mState == RUNNING) {
            throw new IllegalStateException(
                    "Unable to resume the benchmark. The benchmark is already running.");
        }
        mNanoPausedDuration += System.nanoTime() - mNanoPausedTime;
        mNanoPausedTime = 0;
        mState = RUNNING;
    }

    /**
     * Judges whether the benchmark needs more samples.
     *
     * For the usage, see class comment.
     */
    public boolean keepRunning() {
        switch (mState) {
            case NOT_STARTED:
                mNanoPreviousTime = System.nanoTime();
                mNanoFinishTime = mNanoPreviousTime + mNanoTimeLimit;
                mState = RUNNING;
                return true;
            case RUNNING:
                final long currentTime = System.nanoTime();
                mResults.add(currentTime - mNanoPreviousTime - mNanoPausedDuration);
                mNanoPausedDuration = 0;

                // To calculate statistics, needs two or more samples.
                if (mResults.size() > mMinRepeatTimes && currentTime > mNanoFinishTime) {
                    calculateSatistics();
                    mState = FINISHED;
                    return false;
                }

                mNanoPreviousTime = currentTime;
                return true;
            case RUNNING_PAUSED:
                throw new IllegalStateException(
                        "Benchmark step finished with paused state. " +
                        "Resume the benchmark before finishing each step.");
            case FINISHED:
                throw new IllegalStateException("The benchmark has finished.");
            default:
                throw new IllegalStateException("The benchmark is in unknown state.");
        }
    }

    public long mean() {
        if (mState != FINISHED) {
            throw new IllegalStateException("The benchmark hasn't finished");
        }
        return (long) mMean;
    }

    public long median() {
        if (mState != FINISHED) {
            throw new IllegalStateException("The benchmark hasn't finished");
        }
        return mMedian;
    }

    public long standardDeviation() {
        if (mState != FINISHED) {
            throw new IllegalStateException("The benchmark hasn't finished");
        }
        return (long) mStandardDeviation;
    }

    private String summaryLine() {
        StringBuilder sb = new StringBuilder();
        sb.append("Summary: ");
        sb.append("median=").append(median()).append("ns, ");
        sb.append("mean=").append(mean()).append("ns, ");
        sb.append("sigma=").append(standardDeviation()).append(", ");
        sb.append("iteration=").append(mResults.size()).append(", ");
        // print out the first few iterations' number for double checking.
        int sampleNumber = Math.min(mResults.size(), mMinRepeatTimes);
        for (int i = 0; i < sampleNumber; i++) {
            sb.append("No ").append(i).append(" result is ").append(mResults.get(i)).append(", ");
        }
        return sb.toString();
    }

    public void sendFullStatusReport(Instrumentation instrumentation, String key) {
        Log.i(TAG, key + summaryLine());
        Bundle status = new Bundle();
        status.putLong(key + "_median", median());
        status.putLong(key + "_mean", mean());
        status.putLong(key + "_standardDeviation", standardDeviation());
        instrumentation.sendStatus(Activity.RESULT_OK, status);
    }
}
