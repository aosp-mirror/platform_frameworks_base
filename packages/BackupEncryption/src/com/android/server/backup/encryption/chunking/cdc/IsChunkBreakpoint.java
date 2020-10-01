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

import static com.android.internal.util.Preconditions.checkArgument;

import com.android.server.backup.encryption.chunking.cdc.ContentDefinedChunker.BreakpointPredicate;

/**
 * Function to determine whether a 64-bit fingerprint ought to be a chunk breakpoint.
 *
 * <p>This works by checking whether there are at least n leading zeros in the fingerprint. n is
 * calculated to on average cause a breakpoint after a given number of trials (provided in the
 * constructor). This allows us to choose a number of trials that gives a desired average chunk
 * size. This works because the fingerprint is pseudo-randomly distributed.
 */
public class IsChunkBreakpoint implements BreakpointPredicate {
    private final int mLeadingZeros;
    private final long mBitmask;

    /**
     * A new instance that causes a breakpoint after a given number of trials on average.
     *
     * @param averageNumberOfTrialsUntilBreakpoint The number of trials after which on average to
     *     create a new chunk. If this is not a power of 2, some precision is sacrificed (i.e., on
     *     average, breaks will actually happen after the nearest power of 2 to the average number
     *     of trials passed in).
     */
    public IsChunkBreakpoint(long averageNumberOfTrialsUntilBreakpoint) {
        checkArgument(
                averageNumberOfTrialsUntilBreakpoint >= 0,
                "Average number of trials must be non-negative");

        // Want n leading zeros after t trials.
        // P(leading zeros = n) = 1/2^n
        // Expected num trials to get n leading zeros = 1/2^-n
        // t = 1/2^-n
        // n = log2(t)
        mLeadingZeros = (int) Math.round(log2(averageNumberOfTrialsUntilBreakpoint));
        mBitmask = ~(~0L >>> mLeadingZeros);
    }

    /**
     * Returns {@code true} if {@code fingerprint} indicates that there should be a chunk
     * breakpoint.
     */
    @Override
    public boolean isBreakpoint(long fingerprint) {
        return (fingerprint & mBitmask) == 0;
    }

    /** Returns the number of leading zeros in the fingerprint that causes a breakpoint. */
    public int getLeadingZeros() {
        return mLeadingZeros;
    }

    /**
     * Calculates log base 2 of x. Not the most efficient possible implementation, but it's simple,
     * obviously correct, and is only invoked on object construction.
     */
    private static double log2(double x) {
        return Math.log(x) / Math.log(2);
    }
}
