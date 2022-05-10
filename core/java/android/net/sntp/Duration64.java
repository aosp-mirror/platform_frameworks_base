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

import java.time.Duration;

/**
 * A type similar to {@link Timestamp64} but used when calculating the difference between two
 * timestamps. As such, it is a signed type, but still uses 64-bits in total and so can only
 * represent half the magnitude of {@link Timestamp64}.
 *
 * <p>See <a href="https://www.eecis.udel.edu/~mills/time.html">4. Time Difference Calculations</a>.
 *
 * @hide
 */
public final class Duration64 {

    public static final Duration64 ZERO = new Duration64(0);
    private final long mBits;

    private Duration64(long bits) {
        this.mBits = bits;
    }

    /**
     * Returns the difference between two 64-bit NTP timestamps as a {@link Duration64}, as
     * described in the NTP spec. The times represented by the timestamps have to be within {@link
     * Timestamp64#MAX_SECONDS_IN_ERA} (~68 years) of each other for the calculation to produce a
     * correct answer.
     */
    public static Duration64 between(Timestamp64 startInclusive, Timestamp64 endExclusive) {
        long oneBits = (startInclusive.getEraSeconds() << 32)
                | (startInclusive.getFractionBits() & 0xFFFF_FFFFL);
        long twoBits = (endExclusive.getEraSeconds() << 32)
                | (endExclusive.getFractionBits() & 0xFFFF_FFFFL);
        long resultBits = twoBits - oneBits;
        return new Duration64(resultBits);
    }

    /**
     * Add two {@link Duration64} instances together. This performs the calculation in {@link
     * Duration} and returns a {@link Duration} to increase the magnitude of accepted arguments,
     * since {@link Duration64} only supports signed 32-bit seconds. The use of {@link Duration}
     * limits precision to nanoseconds.
     */
    public Duration plus(Duration64 other) {
        // From https://www.eecis.udel.edu/~mills/time.html:
        // "The offset and delay calculations require sums and differences of these raw timestamp
        // differences that can span no more than from 34 years in the future to 34 years in the
        // past without overflow. This is a fundamental limitation in 64-bit integer calculations.
        //
        // In the NTPv4 reference implementation, all calculations involving offset and delay values
        // use 64-bit floating double arithmetic, with the exception of raw timestamp subtraction,
        // as mentioned above. The raw timestamp differences are then converted to 64-bit floating
        // double format without loss of precision or chance of overflow in subsequent
        // calculations."
        //
        // Here, we use Duration instead, which provides sufficient range, but loses precision below
        // nanos.
        return this.toDuration().plus(other.toDuration());
    }

    /**
     * Returns a {@link Duration64} equivalent of the supplied duration, if the magnitude can be
     * represented. Because {@link Duration64} uses a fixed point type for sub-second values it
     * cannot represent all nanosecond values precisely and so the conversion can be lossy.
     *
     * @throws IllegalArgumentException if the supplied duration is too big to be represented
     */
    public static Duration64 fromDuration(Duration duration) {
        long seconds = duration.getSeconds();
        if (seconds < Integer.MIN_VALUE || seconds > Integer.MAX_VALUE) {
            throw new IllegalArgumentException();
        }
        long bits = (seconds << 32)
                | (Timestamp64.nanosToFractionBits(duration.getNano()) & 0xFFFF_FFFFL);
        return new Duration64(bits);
    }

    /**
     * Returns a {@link Duration} equivalent of this duration. Because {@link Duration64} uses a
     * fixed point type for sub-second values it can values smaller than nanosecond precision and so
     * the conversion can be lossy.
     */
    public Duration toDuration() {
        int seconds = getSeconds();
        int nanos = getNanos();
        return Duration.ofSeconds(seconds, nanos);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        Duration64 that = (Duration64) o;
        return mBits == that.mBits;
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(mBits);
    }

    @Override
    public String toString() {
        Duration duration = toDuration();
        return Long.toHexString(mBits)
                + "(" + duration.getSeconds() + "s " + duration.getNano() + "ns)";
    }

    /**
     * Returns the <em>signed</em> seconds in this duration.
     */
    public int getSeconds() {
        return (int) (mBits >> 32);
    }

    /**
     * Returns the <em>unsigned</em> nanoseconds in this duration (truncated).
     */
    public int getNanos() {
        return Timestamp64.fractionBitsToNanos((int) (mBits & 0xFFFF_FFFFL));
    }
}
