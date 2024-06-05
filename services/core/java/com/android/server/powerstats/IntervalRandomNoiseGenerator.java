/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.server.powerstats;

import com.android.internal.annotations.VisibleForTesting;

import org.apache.commons.math.MathException;
import org.apache.commons.math.distribution.AbstractContinuousDistribution;
import org.apache.commons.math.distribution.BetaDistributionImpl;

import java.util.Arrays;

/**
 * Adds random noise to provided value, keeping it within the limits of a specified range.
 * @hide
 */
public class IntervalRandomNoiseGenerator {
    private static final int DISTRIBUTION_SAMPLE_SIZE = 17;

    private final AbstractContinuousDistribution mDistribution;
    private final double[] mSamples = new double[DISTRIBUTION_SAMPLE_SIZE];

    private static final double UNINITIALIZED = -1;

    /**
     * Higher alpha makes the distribution more asymmetrical, tightening it
     * closer to the high bound.  A value of alpha should be &gt; 1 to ensure
     * that the samples closer to 1 appear more frequently t those closer
     * to 0.
     */
    IntervalRandomNoiseGenerator(double alpha) {
        if (alpha <= 1) {
            throw new IllegalArgumentException("alpha should be > 1");
        }
        mDistribution = new BetaDistributionImpl(alpha, 1 /* beta */);
        refresh();
    }

    @VisibleForTesting
    void reseed(long seed) {
        mDistribution.reseedRandomGenerator(seed);
    }

    /**
     * Returns a random value between the specified bounds, statistically closer to the
     * highProbabilityBound.
     *
     * The same value is returned for a given stickyKey until {@link #refresh()} is called.
     */
    long addNoise(long lowProbabilityBound, long highProbabilityBound, int stickyKey) {
        double sample = mSamples[stickyKey % DISTRIBUTION_SAMPLE_SIZE];
        if (sample < 0) {   // UNINITIALIZED
            try {
                sample = mDistribution.sample();
            } catch (MathException e) {
                throw new IllegalStateException(e);
            }
            mSamples[stickyKey % DISTRIBUTION_SAMPLE_SIZE] = sample;
        }
        return lowProbabilityBound + (long) ((highProbabilityBound - lowProbabilityBound) * sample);
    }

    /**
     * Resets the cache of random samples.
     */
    void refresh() {
        Arrays.fill(mSamples, UNINITIALIZED);
    }
}
