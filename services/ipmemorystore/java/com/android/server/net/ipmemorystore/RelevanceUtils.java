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

package com.android.server.net.ipmemorystore;

import com.android.internal.annotations.VisibleForTesting;

/**
 * A class containing the logic around the relevance value for
 * IP Memory Store.
 *
 * @hide
 */
public class RelevanceUtils {
    /**
     * The relevance is a decaying value that gets lower and lower until it
     * reaches 0 after some time passes. It follows an exponential decay law,
     * dropping slowly at first then faster and faster, because a network is
     * likely to be visited again if it was visited not long ago, and the longer
     * it hasn't been visited the more likely it is that it won't be visited
     * again. For example, a network visited on holiday should stay fresh for
     * the duration of the holiday and persist for a while, but after the venue
     * hasn't been visited for a while it should quickly be discarded. What
     * should accelerate forgetting the network is extended periods without
     * visits, so that occasional venues get discarded but regular visits keep
     * the network relevant, even if the visits are infrequent.
     *
     * This function must be stable by iteration, meaning that adjusting the same value
     * for different dates iteratively multiple times should give the same result.
     * Formally, if f is the decay function that associates a relevance x at a date d1
     * to the value at ulterior date d3, then for any date d2 between d1 and d3 :
     * f(x, d3 - d1) = f(f(x, d3 - d2), d2 - d1). Intuitively, this property simply
     * means it should be the same to compute and store back the value after two months,
     * or to do it once after one month, store it back, and do it again after another
     * months has passed.
     * The pair of the relevance and date define the entire curve, so any pair
     * of values on the curve will define the same curve. Setting one of them to a
     * constant, so as not to have to store it, means the other one will always suffice
     * to describe the curve. For example, only storing the date for a known, constant
     * value of the relevance is an efficient way of remembering this information (and
     * to compare relevances together, as f is monotonically decreasing).
     *
     *** Choosing the function :
     * Functions of the kind described above are standard exponential decay functions
     * like the ones that govern atomic decay where the value at any given date can be
     * computed uniformly from the value at a previous date and the time elapsed since
     * that date. It is simple to picture this kind of function as one where after a
     * given period of time called the half-life, the relevance value will have been
     * halved. Decay of this kind is expressed in function of the previous value by
     * functions like
     * f(x, t) = x * F ^ (t / L)
     * ...where x is the value, t is the elapsed time, L is the half-life (or more
     * generally the F-th-life) and F the decay factor (typically 0.5, hence why L is
     * usually called the half-life). The ^ symbol here is used for exponentiation.
     * Or, starting at a given M for t = 0 :
     * f(t) = M * F ^ (t / L)
     *
     * Because a line in the store needs to become irrelevant at some point but
     * this class of functions never go to 0, a minimum cutoff has to be chosen to
     * represent irrelevance. The simpler way of doing this is to simply add this
     * minimum cutoff to the computation before and removing it after.
     * Thus the function becomes :
     * f(x, t) = ((x + K) * F ^ (t / L)) - K
     * ...where K is the minimum cutoff, L the half-life, and F the factor between
     * the original x and x after its half-life. Strictly speaking using the word
     * "half-life" implies that F = 0.5, but the relation works for any value of F.
     *
     * It is easy enough to check that this function satisfies the stability
     * relation that was given above for any value of F, L and K, which become
     * parameters that can be defined at will.
     *
     * relevance
     *  1.0 |
     *      |\
     *      | \
     *      |  \            (this graph rendered with L = 75 days and K = 1/40)
     *  0.75|   ',
     *      |     \
     *      |      '.
     *      |        \.
     *      |          \
     *  0.5 |           '\
     *      |             ''.
     *      |                ''.
     *      |                   ''.
     *  0.25|                      '''..
     *      |                           '''..
     *      |                                ''''....
     *      |                                        '''''..........
     *    0 +-------------------------------------------------------''''''''''----
     *      0       50       100      150     200      250     300      350     400 days
     *
     *** Choosing the parameters
     * The maximum M is an arbitrary parameter that simply scales the curve.
     * The tradeoff for M is pretty simple : if the relevance is going to be an
     * integer, the bigger M is the more precision there is in the relevance.
     * However, values of M that are easy for humans to read are preferable to
     * help debugging, and a suitably low value may be enough to ensure there
     * won't be integer overflows in intermediate computations.
     * A value of 1_000_000 probably is plenty for precision, while still in the
     * low range of what ints can represent.
     *
     * F and L are parameters to be chosen arbitrarily and have an impact on how
     * fast the relevance will be decaying at first, keeping in mind that
     * the 400 days value and the cap stay the same. In simpler words, F and L
     * define the steepness of the curve.
     * To keep things simple (and familiar) F is arbitrarily chosen to be 0.5, and
     * L is set to 200 days visually to achieve the desired effect. Refer to the
     * illustration above to get a feel of how that feels.
     *
     * Moreover, the memory store works on an assumption that the relevance should
     * be capped, and that an entry with capped relevance should decay in 400 days.
     * This is on premises that the networks a device will need to remember the
     * longest should be networks visited about once a year.
     * For this reason, the relevance is at the maximum M 400 days before expiry :
     * f(M, 400 days) = 0
     * From replacing this with the value of the function, K can then be derived
     * from the values of M, F and L :
     * (M + K) * F ^ (t / L) - K = 0
     * K = M * F ^ (400 days / L) / (1 - F ^ (400 days / L))
     * Replacing with actual values this gives :
     * K = 1_000_000 * 0.5 ^ (400 / 200) / (1 - 0.5 ^ (400 / 200))
     *   = 1_000_000 / 3 ≈ 333_333.3
     * This ensures the function has the desired profile, the desired value at
     * cap, and the desired value at expiry.
     *
     *** Useful relations
     * Let's define the expiry time for any given relevance x as the interval of
     * time such as :
     * f(x, expiry) = 0
     * which can be rewritten
     * ((x + K) * F ^ (expiry / L)) = K
     * ...giving an expression of the expiry in function of the relevance x as
     * expiry = L * logF(K / (x + K))
     * Conversely the relevance x can be expressed in function of the expiry as
     * x = K / F ^ (expiry / L) - K
     * These relations are useful in utility functions.
     *
     *** Bumping things up
     * The last issue therefore is to decide how to bump up the relevance. The
     * simple approach is to simply lift up the curve a little bit by a constant
     * normalized amount, delaying the time of expiry. For example increasing
     * the relevance by an amount I gives :
     * x2 = x1 + I
     * x2 and x1 correspond to two different expiry times expiry2 and expiry1,
     * and replacing x1 and x2 in the relation above with their expression in
     * function of the expiry comes :
     * K / F ^ (expiry2 / L) - K = K / F ^ (expiry1 / L) - K + I
     * which resolves to :
     * expiry2 = L * logF(K / (I + K / F ^ (expiry1 / L)))
     *
     * In this implementation, the bump is defined as 1/25th of the cap for
     * the relevance. This means a network will be remembered for the maximum
     * period of 400 days if connected 25 times in succession not accounting
     * for decay. Of course decay actually happens so it will take more than 25
     * connections for any given network to actually reach the cap, but because
     * decay is slow at first, it is a good estimate of how fast cap happens.
     *
     * Specifically, it gives the following four results :
     * - A network that a device connects to once hits irrelevance about 32.7 days after
     *   it was first registered if never connected again.
     * - A network that a device connects to once a day at a fixed hour will hit the cap
     *   on the 27th connection.
     * - A network that a device connects to once a week at a fixed hour will hit the cap
     *   on the 57th connection.
     * - A network that a device connects to every day for 7 straight days then never again
     *   expires 144 days after the last connection.
     * These metrics tend to match pretty well the requirements.
     */

    // TODO : make these constants configurable at runtime. Don't forget to build it so that
    // changes will wipe the database, migrate the values, or otherwise make sure the relevance
    // values are still meaningful.

    // How long, in milliseconds, is a capped relevance valid for, or in other
    // words how many milliseconds after its relevance was set to RELEVANCE_CAP does
    // any given line expire. 400 days.
    @VisibleForTesting
    public static final long CAPPED_RELEVANCE_LIFETIME_MS = 400L * 24 * 60 * 60 * 1000;

    // The constant that represents a normalized 1.0 value for the relevance. In other words,
    // the cap for the relevance. This is referred to as M in the explanation above.
    @VisibleForTesting
    public static final int CAPPED_RELEVANCE = 1_000_000;

    // The decay factor. After a half-life, the relevance will have decayed by this value.
    // This is referred to as F in the explanation above.
    private static final double DECAY_FACTOR = 0.5;

    // The half-life. After this time, the relevance will have decayed by a factor DECAY_FACTOR.
    // This is referred to as L in the explanation above.
    private static final long HALF_LIFE_MS = 200L * 24 * 60 * 60 * 1000;

    // The value of the frame change. This is referred to as K in the explanation above.
    private static final double IRRELEVANCE_FLOOR =
            CAPPED_RELEVANCE * powF((double) CAPPED_RELEVANCE_LIFETIME_MS / HALF_LIFE_MS)
            / (1 - powF((double) CAPPED_RELEVANCE_LIFETIME_MS / HALF_LIFE_MS));

    // How much to bump the relevance by every time a line is written to.
    @VisibleForTesting
    public static final int RELEVANCE_BUMP = CAPPED_RELEVANCE / 25;

    // Java doesn't include a function for the logarithm in an arbitrary base, so implement it
    private static final double LOG_DECAY_FACTOR = Math.log(DECAY_FACTOR);
    private static double logF(final double value) {
        return Math.log(value) / LOG_DECAY_FACTOR;
    }

    // Utility function to get a power of the decay factor, to simplify the code.
    private static double powF(final double value) {
        return Math.pow(DECAY_FACTOR, value);
    }

    /**
     * Compute the value of the relevance now given an expiry date.
     *
     * @param expiry the date at which the column in the database expires.
     * @return the adjusted value of the relevance for this moment in time.
     */
    public static int computeRelevanceForNow(final long expiry) {
        return computeRelevanceForTargetDate(expiry, System.currentTimeMillis());
    }

    /**
     * Compute the value of the relevance at a given date from an expiry date.
     *
     * Because relevance decays with time, a relevance in the past corresponds to
     * a different relevance later.
     *
     * Relevance is always a positive value. 0 means not relevant at all.
     *
     * See the explanation at the top of this file to get the justification for this
     * computation.
     *
     * @param expiry the date at which the column in the database expires.
     * @param target the target date to adjust the relevance to.
     * @return the adjusted value of the relevance for the target moment.
     */
    public static int computeRelevanceForTargetDate(final long expiry, final long target) {
        final long delay = expiry - target;
        if (delay >= CAPPED_RELEVANCE_LIFETIME_MS) return CAPPED_RELEVANCE;
        if (delay <= 0) return 0;
        return (int) (IRRELEVANCE_FLOOR / powF((float) delay / HALF_LIFE_MS) - IRRELEVANCE_FLOOR);
    }

    /**
     * Compute the expiry duration adjusted up for a new fresh write.
     *
     * Every time data is written to the memory store for a given line, the
     * relevance is bumped up by a certain amount, which will boost the priority
     * of this line for computation of group attributes, and delay (possibly
     * indefinitely, if the line is accessed regularly) forgetting the data stored
     * in that line.
     * As opposed to bumpExpiryDate, this function uses a duration from now to expiry.
     *
     * See the explanation at the top of this file for a justification of this computation.
     *
     * @param oldExpiryDuration the old expiry duration in milliseconds from now.
     * @return the expiry duration representing a bumped up relevance value.
     */
    public static long bumpExpiryDuration(final long oldExpiryDuration) {
        // L * logF(K / (I + K / F ^ (expiry1 / L))), as documented above
        final double divisionFactor = powF(((double) oldExpiryDuration) / HALF_LIFE_MS);
        final double oldRelevance = IRRELEVANCE_FLOOR / divisionFactor;
        final long newDuration =
                (long) (HALF_LIFE_MS * logF(IRRELEVANCE_FLOOR / (RELEVANCE_BUMP + oldRelevance)));
        return Math.min(newDuration, CAPPED_RELEVANCE_LIFETIME_MS);
    }

    /**
     * Compute the new expiry date adjusted up for a new fresh write.
     *
     * Every time data is written to the memory store for a given line, the
     * relevance is bumped up by a certain amount, which will boost the priority
     * of this line for computation of group attributes, and delay (possibly
     * indefinitely, if the line is accessed regularly) forgetting the data stored
     * in that line.
     * As opposed to bumpExpiryDuration, this function takes the old timestamp and returns the
     * new timestamp.
     *
     * {@see bumpExpiryDuration}, and keep in mind that the bump depends on when this is called,
     * because the relevance decays exponentially, therefore bumping up a high relevance (for a
     * date far in the future) is less potent than bumping up a low relevance (for a date in
     * a close future).
     *
     * @param oldExpiryDate the old date of expiration.
     * @return the new expiration date after the relevance bump.
     */
    public static long bumpExpiryDate(final long oldExpiryDate) {
        final long now = System.currentTimeMillis();
        final long newDuration = bumpExpiryDuration(oldExpiryDate - now);
        return now + newDuration;
    }
}
