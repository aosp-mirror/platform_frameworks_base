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

package com.android.server.backup.encryption.chunking.cdc;

import static com.google.common.truth.Truth.assertThat;

import static org.testng.Assert.assertThrows;

import android.platform.test.annotations.Presubmit;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.Random;

/** Tests for {@link IsChunkBreakpoint}. */
@RunWith(RobolectricTestRunner.class)
@Presubmit
public class IsChunkBreakpointTest {
    private static final int RANDOM_SEED = 42;
    private static final double TOLERANCE = 0.01;
    private static final int NUMBER_OF_TESTS = 10000;
    private static final int BITS_PER_LONG = 64;

    private Random mRandom;

    /** Make sure that tests are deterministic. */
    @Before
    public void setUp() {
        mRandom = new Random(RANDOM_SEED);
    }

    /**
     * Providing a negative average number of trials should throw an {@link
     * IllegalArgumentException}.
     */
    @Test
    public void create_withNegativeAverageNumberOfTrials_throwsIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class, () -> new IsChunkBreakpoint(-1));
    }

    // Note: the following three tests are compute-intensive, so be cautious adding more.

    /**
     * If the provided average number of trials is zero, a breakpoint should be expected after one
     * trial on average.
     */
    @Test
    public void
            isBreakpoint_withZeroAverageNumberOfTrials_isTrueOnAverageAfterOneTrial() {
        assertExpectedTrials(new IsChunkBreakpoint(0), /*expectedTrials=*/ 1);
    }

    /**
     * If the provided average number of trials is 512, a breakpoint should be expected after 512
     * trials on average.
     */
    @Test
    public void
            isBreakpoint_with512AverageNumberOfTrials_isTrueOnAverageAfter512Trials() {
        assertExpectedTrials(new IsChunkBreakpoint(512), /*expectedTrials=*/ 512);
    }

    /**
     * If the provided average number of trials is 1024, a breakpoint should be expected after 1024
     * trials on average.
     */
    @Test
    public void
            isBreakpoint_with1024AverageNumberOfTrials_isTrueOnAverageAfter1024Trials() {
        assertExpectedTrials(new IsChunkBreakpoint(1024), /*expectedTrials=*/ 1024);
    }

    /** The number of leading zeros should be the logarithm of the average number of trials. */
    @Test
    public void getLeadingZeros_squaredIsAverageNumberOfTrials() {
        for (int i = 0; i < BITS_PER_LONG; i++) {
            long averageNumberOfTrials = (long) Math.pow(2, i);

            int leadingZeros = new IsChunkBreakpoint(averageNumberOfTrials).getLeadingZeros();

            assertThat(leadingZeros).isEqualTo(i);
        }
    }

    private void assertExpectedTrials(IsChunkBreakpoint isChunkBreakpoint, long expectedTrials) {
        long sum = 0;
        for (int i = 0; i < NUMBER_OF_TESTS; i++) {
            sum += numberOfTrialsTillBreakpoint(isChunkBreakpoint);
        }
        long averageTrials = sum / NUMBER_OF_TESTS;
        assertThat((double) Math.abs(averageTrials - expectedTrials))
                .isLessThan(TOLERANCE * expectedTrials);
    }

    private int numberOfTrialsTillBreakpoint(IsChunkBreakpoint isChunkBreakpoint) {
        int trials = 0;

        while (true) {
            trials++;
            if (isChunkBreakpoint.isBreakpoint(mRandom.nextLong())) {
                return trials;
            }
        }
    }
}
