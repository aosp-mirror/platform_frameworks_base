/*
 * Copyright (C) 2021 The Android Open Source Project
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
package android.net.sntp;

import android.text.TextUtils;

import com.android.internal.annotations.VisibleForTesting;

import java.time.Instant;
import java.util.Objects;
import java.util.Random;

/**
 * The 64-bit type ("timestamp") that NTP uses to represent a point in time. It only holds the
 * lowest 32-bits of the number of seconds since 1900-01-01 00:00:00. Consequently, to turn an
 * instance into an unambiguous point in time the era number must be known. Era zero runs from
 * 1900-01-01 00:00:00 to a date in 2036.
 *
 * It stores sub-second values using a 32-bit fixed point type, so it can resolve values smaller
 * than a nanosecond, but is imprecise (i.e. it truncates).
 *
 * See also <a href=https://www.eecis.udel.edu/~mills/y2k.html>NTP docs</a>.
 *
 * @hide
 */
public final class Timestamp64 {

    public static final Timestamp64 ZERO = fromComponents(0, 0);
    static final int SUB_MILLIS_BITS_TO_RANDOMIZE = 32 - 10;

    // Number of seconds between Jan 1, 1900 and Jan 1, 1970
    // 70 years plus 17 leap days
    static final long OFFSET_1900_TO_1970 = ((365L * 70L) + 17L) * 24L * 60L * 60L;
    static final long MAX_SECONDS_IN_ERA = 0xFFFF_FFFFL;
    static final long SECONDS_IN_ERA = MAX_SECONDS_IN_ERA + 1;

    static final int NANOS_PER_SECOND = 1_000_000_000;

    /** Creates a {@link Timestamp64} from the seconds and fraction components. */
    public static Timestamp64 fromComponents(long eraSeconds, int fractionBits) {
        return new Timestamp64(eraSeconds, fractionBits);
    }

    /** Creates a {@link Timestamp64} by decoding a string in the form "e4dc720c.4d4fc9eb". */
    public static Timestamp64 fromString(String string) {
        final int requiredLength = 17;
        if (string.length() != requiredLength || string.charAt(8) != '.') {
            throw new IllegalArgumentException(string);
        }
        String eraSecondsString = string.substring(0, 8);
        String fractionString = string.substring(9);
        long eraSeconds = Long.parseLong(eraSecondsString, 16);

        // Use parseLong() because the type is unsigned. Integer.parseInt() will reject 0x70000000
        // or above as being out of range.
        long fractionBitsAsLong = Long.parseLong(fractionString, 16);
        if (fractionBitsAsLong < 0 || fractionBitsAsLong > 0xFFFFFFFFL) {
            throw new IllegalArgumentException("Invalid fractionBits:" + fractionString);
        }
        return new Timestamp64(eraSeconds, (int) fractionBitsAsLong);
    }

    /**
     * Converts an {@link Instant} into a {@link Timestamp64}. This is lossy: Timestamp64 only
     * contains the number of seconds in a given era, but the era is not stored. Also, sub-second
     * values are not stored precisely.
     */
    public static Timestamp64 fromInstant(Instant instant) {
        long ntpEraSeconds = instant.getEpochSecond() + OFFSET_1900_TO_1970;
        if (ntpEraSeconds < 0) {
            ntpEraSeconds = SECONDS_IN_ERA - (-ntpEraSeconds % SECONDS_IN_ERA);
        }
        ntpEraSeconds %= SECONDS_IN_ERA;

        long nanos = instant.getNano();
        int fractionBits = nanosToFractionBits(nanos);

        return new Timestamp64(ntpEraSeconds, fractionBits);
    }

    private final long mEraSeconds;
    private final int mFractionBits;

    private Timestamp64(long eraSeconds, int fractionBits) {
        if (eraSeconds < 0 || eraSeconds > MAX_SECONDS_IN_ERA) {
            throw new IllegalArgumentException(
                    "Invalid parameters. seconds=" + eraSeconds + ", fraction=" + fractionBits);
        }
        this.mEraSeconds = eraSeconds;
        this.mFractionBits = fractionBits;
    }

    /** Returns the number of seconds in the NTP era. */
    public long getEraSeconds() {
        return mEraSeconds;
    }

    /** Returns the fraction of a second as 32-bit, unsigned fixed-point bits. */
    public int getFractionBits() {
        return mFractionBits;
    }

    @Override
    public String toString() {
        return TextUtils.formatSimple("%08x.%08x", mEraSeconds, mFractionBits);
    }

    /** Returns the instant represented by this value in the specified NTP era. */
    public Instant toInstant(int ntpEra) {
        long secondsSinceEpoch = mEraSeconds - OFFSET_1900_TO_1970;
        secondsSinceEpoch += ntpEra * SECONDS_IN_ERA;

        int nanos = fractionBitsToNanos(mFractionBits);
        return Instant.ofEpochSecond(secondsSinceEpoch, nanos);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        Timestamp64 that = (Timestamp64) o;
        return mEraSeconds == that.mEraSeconds && mFractionBits == that.mFractionBits;
    }

    @Override
    public int hashCode() {
        return Objects.hash(mEraSeconds, mFractionBits);
    }

    static int fractionBitsToNanos(int fractionBits) {
        long fractionBitsLong = fractionBits & 0xFFFF_FFFFL;
        return (int) ((fractionBitsLong * NANOS_PER_SECOND) >>> 32);
    }

    static int nanosToFractionBits(long nanos) {
        if (nanos > NANOS_PER_SECOND) {
            throw new IllegalArgumentException();
        }
        return (int) ((nanos << 32) / NANOS_PER_SECOND);
    }

    /**
     * Randomizes the fraction bits that represent sub-millisecond values. i.e. the randomization
     * won't change the number of milliseconds represented after truncation. This is used to
     * implement the part of the NTP spec that calls for clients with millisecond accuracy clocks
     * to send randomized LSB values rather than zeros.
     */
    public Timestamp64 randomizeSubMillis(Random random) {
        int randomizedFractionBits =
                randomizeLowestBits(random, this.mFractionBits, SUB_MILLIS_BITS_TO_RANDOMIZE);
        return new Timestamp64(mEraSeconds, randomizedFractionBits);
    }

    /**
     * Randomizes the specified number of LSBs in {@code value} by using replacement bits from
     * {@code Random.getNextInt()}.
     */
    @VisibleForTesting
    public static int randomizeLowestBits(Random random, int value, int bitsToRandomize) {
        if (bitsToRandomize < 1 || bitsToRandomize >= Integer.SIZE) {
            // There's no point in randomizing all bits or none of the bits.
            throw new IllegalArgumentException(Integer.toString(bitsToRandomize));
        }

        int upperBitMask = 0xFFFF_FFFF << bitsToRandomize;
        int lowerBitMask = ~upperBitMask;

        int randomValue = random.nextInt();
        return (value & upperBitMask) | (randomValue & lowerBitMask);
    }
}
