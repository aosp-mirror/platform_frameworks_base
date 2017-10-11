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

package com.android.layoutlib.bridge.intensive.util.perf;

import com.android.layoutlib.bridge.intensive.util.perf.LongStatsCollector.Stats;

import java.text.DecimalFormat;

/**
 * Result value of a {@link TimedStatement}
 */
public class TimedStatementResult {
    private static final DecimalFormat UNITS_FORMAT = new DecimalFormat("#.##");

    private final int mWarmUpIterations;
    private final int mRuns;
    private final double mCalibrationTimeMs;
    private final Stats mTimeStats;
    private final Stats mMemoryStats;

    TimedStatementResult(int warmUpIterations, int runs,
            double calibrationTimeMs,
            Stats timeStats,
            Stats memoryStats) {
        mWarmUpIterations = warmUpIterations;
        mRuns = runs;
        mCalibrationTimeMs = calibrationTimeMs;
        mTimeStats = timeStats;
        mMemoryStats = memoryStats;
    }

    @Override
    public String toString() {
        return String.format(
                "Warm up %d. Runs %d\n" + "Time:             %s ms (min: %s, max %s)\n" +
                        "Calibration Time: %f ms\n" +
                        "Calibrated Time:  %s units (min: %s, max %s)\n" +
                        "Sampled %d times\n" +
                        "   Memory used:  %d bytes (max %d)\n\n",
                mWarmUpIterations, mRuns,
                mTimeStats.getMedian(), mTimeStats.getMin(), mTimeStats.getMax(),
                mCalibrationTimeMs,
                UNITS_FORMAT.format((mTimeStats.getMedian() / mCalibrationTimeMs) * 100000),
                UNITS_FORMAT.format((mTimeStats.getMin() / mCalibrationTimeMs) * 100000),
                UNITS_FORMAT.format((mTimeStats.getMax() / mCalibrationTimeMs) * 100000),
                mMemoryStats.getSampleCount(),
                (long)mMemoryStats.getMedian() - mMemoryStats.getMin(),
                mMemoryStats.getMax() - mMemoryStats.getMin());
    }
}
