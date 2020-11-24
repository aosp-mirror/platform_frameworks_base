/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.systemui.classifier;

import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.plugins.FalsingManager;
import com.android.systemui.util.time.SystemClock;

import java.util.Collection;
import java.util.concurrent.DelayQueue;
import java.util.concurrent.Delayed;
import java.util.concurrent.TimeUnit;

import javax.inject.Inject;

/**
 * A stateful class for tracking recent {@link FalsingManager} results.
 *
 * Can return a "penalty" based on recent gestures that may make it harder or easier to
 * unlock a phone, as well as a "confidence" relating to how consistent recent falsing results
 * have been.
 */
@SysUISingleton
public class HistoryTracker {
    private static final double HISTORY_DECAY = 0.8f;
    private static final long DECAY_INTERVAL_MS = 100;
    // We expire items once their decay factor is below 0.001.
    private static final double MINIMUM_SCORE = 0.001;
    private static final long TOTAL_DECAY_TIME_MS =
            DECAY_INTERVAL_MS * (long) (Math.log(MINIMUM_SCORE) / Math.log(HISTORY_DECAY));
    private final SystemClock mSystemClock;

    DelayQueue<CombinedResult> mResults = new DelayQueue<>();

    @Inject
    HistoryTracker(SystemClock systemClock) {
        mSystemClock = systemClock;
    }

    /**
     * Returns how much the HistoryClassifier thinks the past events indicate pocket dialing.
     *
     * A result of 0 means that all prior gestures succeeded or there is no data to
     * calculate a score with. Use {@link #falseConfidence()} to differentiate between the
     * two cases.
     *
     * A result of 1 means that all prior gestures were very obviously false. The current gesture
     * might be valid, but it should have a high-bar to be classified as such.
     *
     * See also {@link #falseConfidence()}.
     */
    double falsePenalty() {
        //noinspection StatementWithEmptyBody
        while (mResults.poll() != null) {
            // Empty out the expired results.
        }

        if (mResults.isEmpty()) {
            return 0;
        }

        long nowMs = mSystemClock.uptimeMillis();
        return mResults.stream()
                .map(result -> result.getDecayedScore(nowMs))
                .reduce(0.0, Double::sum) / mResults.size();
    }

    /**
     * Returns how confident the HistoryClassifier is in its own score.
     *
     * A result of 0.0 means that there are no data to make a calculation with. The HistoryTracker's
     * results have nothing to add and should not be considered.
     *
     * A result of 0.5 means that the data are not consistent with each other, sometimes falsing
     * sometimes not.
     *
     * A result of 1 means that there are ample, fresh data to act upon that is all consistent
     * with each other.
     *
     * See als {@link #falsePenalty()}.
     */
    double falseConfidence() {
        //noinspection StatementWithEmptyBody
        while (mResults.poll() != null) {
            // Empty out the expired results.
        }

        // Our confidence is 1 - the population stddev. Smaller stddev == higher confidence.
        if (mResults.isEmpty()) {
            return 0;
        }

        double mean = mResults.stream()
                .map(CombinedResult::getScore)
                .reduce(0.0, Double::sum) / mResults.size();

        double stddev = Math.sqrt(
                mResults.stream()
                        .map(result -> Math.pow(result.getScore() - mean, 2))
                        .reduce(0.0, Double::sum) / mResults.size());

        return 1 - stddev;
    }

    void addResults(Collection<FalsingClassifier.Result> results, long uptimeMillis) {
        double finalScore = 0;
        for (FalsingClassifier.Result result : results) {
            // A confidence of 1 adds either 0 for non-falsed or 1 for falsed.
            // A confidence of 0 adds 0.5.
            finalScore += (result.isFalse() ? .5 : -.5) * result.getConfidence() + 0.5;
        }

        finalScore /= results.size();

        //noinspection StatementWithEmptyBody
        while (mResults.poll() != null) {
            // Empty out the expired results.
        }

        mResults.add(new CombinedResult(uptimeMillis, finalScore));
    }

    /**
     * Represents a falsing score combing all the classifiers together.
     *
     * Can "decay" over time, such that older results contribute less. Once they drop below
     * a certain threshold, the {@link #getDelay(TimeUnit)} method will return <= 0, indicating
     * that this result can be discarded.
     */
    private class CombinedResult implements Delayed {

        private final long mExpiryMs;
        private final double mScore;

        CombinedResult(long uptimeMillis, double score) {
            mExpiryMs = uptimeMillis + TOTAL_DECAY_TIME_MS;
            mScore = score;
        }

        double getDecayedScore(long nowMs) {
            long remainingTimeMs = mExpiryMs - nowMs;
            long decayedTimeMs = TOTAL_DECAY_TIME_MS - remainingTimeMs;
            double timeIntervals = (double) decayedTimeMs / DECAY_INTERVAL_MS;
            return mScore * Math.pow(HISTORY_DECAY, timeIntervals);
        }

        double getScore() {
            return mScore;
        }

        @Override
        public long getDelay(TimeUnit unit) {
            return unit.convert(mExpiryMs - mSystemClock.uptimeMillis(), TimeUnit.MILLISECONDS);
        }

        @Override
        public int compareTo(Delayed o) {
            long ourDelay = getDelay(TimeUnit.MILLISECONDS);
            long otherDelay = o.getDelay(TimeUnit.MILLISECONDS);
            return Long.compare(ourDelay, otherDelay);
        }
    }
}
