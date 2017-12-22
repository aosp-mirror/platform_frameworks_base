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

package com.android.benchmark.results;

import android.annotation.TargetApi;
import android.view.FrameMetrics;

import org.apache.commons.math.stat.descriptive.DescriptiveStatistics;
import org.apache.commons.math.stat.descriptive.SummaryStatistics;

import java.util.ArrayList;
import java.util.List;

/**
 * Utility for storing and analyzing UI benchmark results.
 */
@TargetApi(24)
public class UiBenchmarkResult {
    private static final int BASE_SCORE = 100;
    private static final int ZERO_SCORE_TOTAL_DURATION_MS = 32;
    private static final int JANK_PENALTY_THRESHOLD_MS = 12;
    private static final int ZERO_SCORE_ABOVE_THRESHOLD_MS =
            ZERO_SCORE_TOTAL_DURATION_MS - JANK_PENALTY_THRESHOLD_MS;
    private static final double JANK_PENALTY_PER_MS_ABOVE_THRESHOLD =
            BASE_SCORE / (double)ZERO_SCORE_ABOVE_THRESHOLD_MS;
    private static final int CONSISTENCY_BONUS_MAX = 100;

    private static final int METRIC_WAS_JANKY = -1;

    private static final int[] METRICS = new int[] {
            FrameMetrics.UNKNOWN_DELAY_DURATION,
            FrameMetrics.INPUT_HANDLING_DURATION,
            FrameMetrics.ANIMATION_DURATION,
            FrameMetrics.LAYOUT_MEASURE_DURATION,
            FrameMetrics.DRAW_DURATION,
            FrameMetrics.SYNC_DURATION,
            FrameMetrics.COMMAND_ISSUE_DURATION,
            FrameMetrics.SWAP_BUFFERS_DURATION,
            FrameMetrics.TOTAL_DURATION,
    };
    public static final int FRAME_PERIOD_MS = 16;

    private final DescriptiveStatistics[] mStoredStatistics;

    public UiBenchmarkResult(List<FrameMetrics> instances) {
        mStoredStatistics = new DescriptiveStatistics[METRICS.length];
        insertMetrics(instances);
    }

    public UiBenchmarkResult(double[] values) {
        mStoredStatistics = new DescriptiveStatistics[METRICS.length];
        insertValues(values);
    }

    public void update(List<FrameMetrics> instances) {
        insertMetrics(instances);
    }

    public void update(double[] values) {
        insertValues(values);
    }

    public double getAverage(int id) {
        int pos = getMetricPosition(id);
        return mStoredStatistics[pos].getMean();
    }

    public double getMinimum(int id) {
        int pos = getMetricPosition(id);
        return mStoredStatistics[pos].getMin();
    }

    public double getMaximum(int id) {
        int pos = getMetricPosition(id);
        return mStoredStatistics[pos].getMax();
    }

    public int getMaximumIndex(int id) {
        int pos = getMetricPosition(id);
        double[] storedMetrics = mStoredStatistics[pos].getValues();
        int maxIdx = 0;
        for (int i = 0; i < storedMetrics.length; i++) {
            if (storedMetrics[i] >= storedMetrics[maxIdx]) {
                maxIdx = i;
            }
        }

        return maxIdx;
    }

    public double getMetricAtIndex(int index, int metricId) {
        return mStoredStatistics[getMetricPosition(metricId)].getElement(index);
    }

    public double getPercentile(int id, int percentile) {
        if (percentile > 100) percentile = 100;
        if (percentile < 0) percentile = 0;

        int metricPos = getMetricPosition(id);
        return mStoredStatistics[metricPos].getPercentile(percentile);
    }

    public int getTotalFrameCount() {
        if (mStoredStatistics.length == 0) {
            return 0;
        }

        return (int) mStoredStatistics[0].getN();
    }

    public int getScore() {
        SummaryStatistics badFramesStats = new SummaryStatistics();

        int totalFrameCount = getTotalFrameCount();
        for (int i = 0; i < totalFrameCount; i++) {
            double totalDuration = getMetricAtIndex(i, FrameMetrics.TOTAL_DURATION);
            if (totalDuration >= 12) {
                badFramesStats.addValue(totalDuration);
            }
        }

        int length = getSortedJankFrameIndices().length;
        double jankFrameCount = 100 * length / (double) totalFrameCount;

        System.out.println("Mean: " + badFramesStats.getMean() + " JankP: " + jankFrameCount
                + " StdDev: " + badFramesStats.getStandardDeviation() +
                " Count Bad: " + badFramesStats.getN() + " Count Jank: " + length);

        return (int) Math.round(
                (badFramesStats.getMean()) * jankFrameCount * badFramesStats.getStandardDeviation());
    }

    public int getJankPenalty() {
        double total95th = mStoredStatistics[getMetricPosition(FrameMetrics.TOTAL_DURATION)]
                .getPercentile(95);
        System.out.println("95: " + total95th);
        double aboveThreshold = total95th - JANK_PENALTY_THRESHOLD_MS;
        if (aboveThreshold <= 0) {
            return 0;
        }

        if (aboveThreshold > ZERO_SCORE_ABOVE_THRESHOLD_MS) {
            return BASE_SCORE;
        }

        return (int) Math.ceil(JANK_PENALTY_PER_MS_ABOVE_THRESHOLD * aboveThreshold);
    }

    public int getConsistencyBonus() {
        DescriptiveStatistics totalDurationStats =
                mStoredStatistics[getMetricPosition(FrameMetrics.TOTAL_DURATION)];

        double standardDeviation = totalDurationStats.getStandardDeviation();
        if (standardDeviation == 0) {
            return CONSISTENCY_BONUS_MAX;
        }

        // 1 / CV of the total duration.
        double bonus = totalDurationStats.getMean() / standardDeviation;
        return (int) Math.min(Math.round(bonus), CONSISTENCY_BONUS_MAX);
    }

    public int[] getSortedJankFrameIndices() {
        ArrayList<Integer> jankFrameIndices = new ArrayList<>();
        boolean tripleBuffered = false;
        int totalFrameCount = getTotalFrameCount();
        int totalDurationPos = getMetricPosition(FrameMetrics.TOTAL_DURATION);

        for (int i = 0; i < totalFrameCount; i++) {
            double thisDuration = mStoredStatistics[totalDurationPos].getElement(i);
            if (!tripleBuffered) {
                if (thisDuration > FRAME_PERIOD_MS) {
                    tripleBuffered = true;
                    jankFrameIndices.add(i);
                }
            } else {
                if (thisDuration > 2 * FRAME_PERIOD_MS) {
                    tripleBuffered = false;
                    jankFrameIndices.add(i);
                }
            }
        }

        int[] res = new int[jankFrameIndices.size()];
        int i = 0;
        for (Integer index : jankFrameIndices) {
            res[i++] = index;
        }
        return res;
    }

    private int getMetricPosition(int id) {
        for (int i = 0; i < METRICS.length; i++) {
            if (id == METRICS[i]) {
                return i;
            }
        }

        return -1;
    }

    private void insertMetrics(List<FrameMetrics> instances) {
        for (FrameMetrics frame : instances) {
            for (int i = 0; i < METRICS.length; i++) {
                DescriptiveStatistics stats = mStoredStatistics[i];
                if (stats == null) {
                    stats = new DescriptiveStatistics();
                    mStoredStatistics[i] = stats;
                }

                mStoredStatistics[i].addValue(frame.getMetric(METRICS[i]) / (double) 1000000);
            }
        }
    }

    private void insertValues(double[] values) {
        if (values.length != METRICS.length) {
            throw new IllegalArgumentException("invalid values array");
        }

        for (int i = 0; i < values.length; i++) {
            DescriptiveStatistics stats = mStoredStatistics[i];
            if (stats == null) {
                stats = new DescriptiveStatistics();
                mStoredStatistics[i] = stats;
            }

            mStoredStatistics[i].addValue(values[i]);
        }
    }
 }
