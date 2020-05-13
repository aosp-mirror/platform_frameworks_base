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

import java.util.ArrayList;
import java.util.Collections;
import java.util.concurrent.TimeUnit;

public class BenchmarkResults {
    /** If the test fails, output this value as a signal of the failure. */
    public static final long DECLARED_VALUE_IF_ERROR_MS = -10;

    private final ArrayList<Long> mResults = new ArrayList<>();

    public void addDuration(long duration) {
        mResults.add(TimeUnit.NANOSECONDS.toMillis(duration));
    }

    public Bundle getStatsToReport() {
        final Bundle stats = new Bundle();
        stats.putDouble("Mean (ms)", mean());
        return stats;
    }

    public Bundle getStatsToLog() {
        final Bundle stats = new Bundle();
        stats.putDouble("Mean (ms)", mean());
        stats.putDouble("Median (ms)", median());
        stats.putDouble("Sigma (ms)", standardDeviation());
        return stats;
    }

    /**
     * Same as {@link #getStatsToReport()} but for failure,
     * using {@link #DECLARED_VALUE_IF_ERROR_MS}.
     */
    public static Bundle getFailedStatsToReport() {
        final Bundle stats = new Bundle();
        stats.putDouble("Mean (ms)", DECLARED_VALUE_IF_ERROR_MS);
        return stats;
    }

    /**
     * Same as {@link #getStatsToLog()} but for failure,
     * using {@link #DECLARED_VALUE_IF_ERROR_MS}.
     */
    public static Bundle getFailedStatsToLog() {
        final Bundle stats = new Bundle();
        stats.putDouble("Mean (ms)", DECLARED_VALUE_IF_ERROR_MS);
        stats.putDouble("Median (ms)", DECLARED_VALUE_IF_ERROR_MS);
        stats.putDouble("Sigma (ms)", DECLARED_VALUE_IF_ERROR_MS);
        return stats;
    }

    public ArrayList<Long> getAllDurations() {
        return mResults;
    }

    private double mean() {
        final int size = mResults.size();
        long sum = 0;
        for (int i = 0; i < size; ++i) {
            sum += mResults.get(i);
        }
        return (double) sum / size;
    }

    private double median() {
        final int size = mResults.size();
        if (size == 0) {
            return 0f;
        }

        final ArrayList<Long> resultsCopy = new ArrayList<>(mResults);
        Collections.sort(resultsCopy);
        final int idx = size / 2;
        return size % 2 == 0
                ? (double) (resultsCopy.get(idx) + resultsCopy.get(idx - 1)) / 2
                : resultsCopy.get(idx);
    }

    private double standardDeviation() {
        final int size = mResults.size();
        if (size == 0) {
            return 0f;
        }
        final double mean = mean();
        double sd = 0;
        for (int i = 0; i < size; ++i) {
            double diff = mResults.get(i) - mean;
            sd += diff * diff;
        }
        return Math.sqrt(sd / size);
    }
}
