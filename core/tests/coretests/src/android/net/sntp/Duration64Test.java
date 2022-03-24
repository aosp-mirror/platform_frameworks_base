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

import static android.net.sntp.Timestamp64.NANOS_PER_SECOND;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import android.platform.test.annotations.Presubmit;

import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

@Presubmit
@RunWith(AndroidJUnit4.class)
public class Duration64Test {

    @Test
    public void testBetween_rangeChecks() {
        long maxDuration64Seconds = Timestamp64.MAX_SECONDS_IN_ERA / 2;

        Timestamp64 zeroNoFrac = Timestamp64.fromComponents(0, 0);
        assertEquals(Duration64.ZERO, Duration64.between(zeroNoFrac, zeroNoFrac));

        {
            Timestamp64 ceilNoFrac = Timestamp64.fromComponents(maxDuration64Seconds, 0);
            assertEquals(Duration64.ZERO, Duration64.between(ceilNoFrac, ceilNoFrac));

            long expectedNanos = maxDuration64Seconds * NANOS_PER_SECOND;
            assertEquals(Duration.ofNanos(expectedNanos),
                    Duration64.between(zeroNoFrac, ceilNoFrac).toDuration());
            assertEquals(Duration.ofNanos(-expectedNanos),
                    Duration64.between(ceilNoFrac, zeroNoFrac).toDuration());
        }

        {
            // This value is the largest fraction of a second representable. It is 1-(1/2^32)), and
            // so numerically larger than 999_999_999 nanos.
            int fractionBits = 0xFFFF_FFFF;
            Timestamp64 ceilWithFrac = Timestamp64
                    .fromComponents(maxDuration64Seconds, fractionBits);
            assertEquals(Duration64.ZERO, Duration64.between(ceilWithFrac, ceilWithFrac));

            long expectedNanos = maxDuration64Seconds * NANOS_PER_SECOND + 999_999_999;
            assertEquals(
                    Duration.ofNanos(expectedNanos),
                    Duration64.between(zeroNoFrac, ceilWithFrac).toDuration());
            // The -1 nanos demonstrates asymmetry due to the way Duration64 has different
            // precision / range of sub-second fractions.
            assertEquals(
                    Duration.ofNanos(-expectedNanos - 1),
                    Duration64.between(ceilWithFrac, zeroNoFrac).toDuration());
        }
    }

    @Test
    public void testBetween_smallSecondsOnly() {
        long expectedNanos = 5L * NANOS_PER_SECOND;
        assertEquals(Duration.ofNanos(expectedNanos),
                Duration64.between(Timestamp64.fromComponents(5, 0),
                        Timestamp64.fromComponents(10, 0))
                        .toDuration());
        assertEquals(Duration.ofNanos(-expectedNanos),
                Duration64.between(Timestamp64.fromComponents(10, 0),
                        Timestamp64.fromComponents(5, 0))
                        .toDuration());
    }

    @Test
    public void testBetween_smallSecondsAndFraction() {
        // Choose a nanos values we know can be represented exactly with fixed point binary (1/2
        // second, 1/4 second, etc.).
        {
            long expectedNanos = 5L * NANOS_PER_SECOND + 500_000_000L;
            int fractionHalfSecond = 0x8000_0000;
            assertEquals(Duration.ofNanos(expectedNanos),
                    Duration64.between(
                            Timestamp64.fromComponents(5, 0),
                            Timestamp64.fromComponents(10, fractionHalfSecond)).toDuration());
            assertEquals(Duration.ofNanos(-expectedNanos),
                    Duration64.between(
                            Timestamp64.fromComponents(10, fractionHalfSecond),
                            Timestamp64.fromComponents(5, 0)).toDuration());
        }

        {
            long expectedNanos = 5L * NANOS_PER_SECOND + 250_000_000L;
            int fractionHalfSecond = 0x8000_0000;
            int fractionQuarterSecond = 0x4000_0000;

            assertEquals(Duration.ofNanos(expectedNanos),
                    Duration64.between(
                            Timestamp64.fromComponents(5, fractionQuarterSecond),
                            Timestamp64.fromComponents(10, fractionHalfSecond)).toDuration());
            assertEquals(Duration.ofNanos(-expectedNanos),
                    Duration64.between(
                            Timestamp64.fromComponents(10, fractionHalfSecond),
                            Timestamp64.fromComponents(5, fractionQuarterSecond)).toDuration());
        }

    }

    @Test
    public void testBetween_sameEra0() {
        int arbitraryEra0Year = 2021;
        Instant one = utcInstant(arbitraryEra0Year, 1, 1, 0, 0, 0, 500);
        assertNtpEraOfInstant(one, 0);

        checkDuration64Behavior(one, one);

        Instant two = utcInstant(arbitraryEra0Year + 1, 1, 1, 0, 0, 0, 250);
        assertNtpEraOfInstant(two, 0);

        checkDuration64Behavior(one, two);
        checkDuration64Behavior(two, one);
    }

    @Test
    public void testBetween_sameEra1() {
        int arbitraryEra1Year = 2037;
        Instant one = utcInstant(arbitraryEra1Year, 1, 1, 0, 0, 0, 500);
        assertNtpEraOfInstant(one, 1);

        checkDuration64Behavior(one, one);

        Instant two = utcInstant(arbitraryEra1Year + 1, 1, 1, 0, 0, 0, 250);
        assertNtpEraOfInstant(two, 1);

        checkDuration64Behavior(one, two);
        checkDuration64Behavior(two, one);
    }

    /**
     * Tests that two timestamps can originate from times in different eras, and the works
     * calculation still works providing the two times aren't more than 68 years apart (half of the
     * 136 years representable using an unsigned 32-bit seconds representation).
     */
    @Test
    public void testBetween_adjacentEras() {
        int yearsSeparation = 68;

        // This year just needs to be < 68 years before the end of NTP timestamp era 0.
        int arbitraryYearInEra0 = 2021;

        Instant one = utcInstant(arbitraryYearInEra0, 1, 1, 0, 0, 0, 500);
        assertNtpEraOfInstant(one, 0);

        checkDuration64Behavior(one, one);

        Instant two = utcInstant(arbitraryYearInEra0 + yearsSeparation, 1, 1, 0, 0, 0, 250);
        assertNtpEraOfInstant(two, 1);

        checkDuration64Behavior(one, two);
        checkDuration64Behavior(two, one);
    }

    /**
     * This test confirms that duration calculations fail in the expected fashion if two
     * Timestamp64s are more than 2^31 seconds apart.
     *
     * <p>The types / math specified by NTP for timestamps deliberately takes place in 64-bit signed
     * arithmetic for the bits used to represent timestamps (32-bit unsigned integer seconds,
     * 32-bits fixed point for fraction of seconds). Timestamps can therefore represent ~136 years
     * of seconds.
     * When subtracting one timestamp from another, we end up with a signed 32-bit seconds value.
     * This means the max duration representable is ~68 years before numbers will over or underflow.
     * i.e. the client and server are in the same or adjacent NTP eras and the difference in their
     * clocks isn't more than ~68 years. >= ~68 years and things break down.
     */
    @Test
    public void testBetween_tooFarApart() {
        int tooManyYearsSeparation = 68 + 1;

        Instant one = utcInstant(2021, 1, 1, 0, 0, 0, 500);
        assertNtpEraOfInstant(one, 0);
        Instant two = utcInstant(2021 + tooManyYearsSeparation, 1, 1, 0, 0, 0, 250);
        assertNtpEraOfInstant(two, 1);

        checkDuration64OverflowBehavior(one, two);
        checkDuration64OverflowBehavior(two, one);
    }

    private static void checkDuration64Behavior(Instant one, Instant two) {
        // This is the answer if we perform the arithmetic in a lossless fashion.
        Duration expectedDuration = Duration.between(one, two);
        Duration64 expectedDuration64 = Duration64.fromDuration(expectedDuration);

        // Sub-second precision is limited in Timestamp64, so we can lose 1ms.
        assertEqualsOrSlightlyLessThan(
                expectedDuration.toMillis(), expectedDuration64.toDuration().toMillis());

        Timestamp64 one64 = Timestamp64.fromInstant(one);
        Timestamp64 two64 = Timestamp64.fromInstant(two);

        // This is the answer if we perform the arithmetic in a lossy fashion.
        Duration64 actualDuration64 = Duration64.between(one64, two64);
        assertEquals(expectedDuration64.getSeconds(), actualDuration64.getSeconds());
        assertEqualsOrSlightlyLessThan(expectedDuration64.getNanos(), actualDuration64.getNanos());
    }

    private static void checkDuration64OverflowBehavior(Instant one, Instant two) {
        // This is the answer if we perform the arithmetic in a lossless fashion.
        Duration trueDuration = Duration.between(one, two);

        // Confirm the maths is expected to overflow / underflow.
        assertTrue(trueDuration.getSeconds() > Integer.MAX_VALUE / 2
                || trueDuration.getSeconds() < Integer.MIN_VALUE / 2);

        // Now perform the arithmetic as specified for NTP: do subtraction using the 64-bit
        // timestamp.
        Timestamp64 one64 = Timestamp64.fromInstant(one);
        Timestamp64 two64 = Timestamp64.fromInstant(two);

        Duration64 actualDuration64 = Duration64.between(one64, two64);
        assertNotEquals(trueDuration.getSeconds(), actualDuration64.getSeconds());
    }

    /**
     * Asserts the instant provided is in the specified NTP timestamp era. Used to confirm /
     * document values picked for tests have the properties needed.
     */
    private static void assertNtpEraOfInstant(Instant one, int ntpEra) {
        long expectedSeconds = one.getEpochSecond();

        // The conversion to Timestamp64 is lossy (it loses the era). We then supply the expected
        // era. If the era was correct, we will end up with the value we started with (modulo nano
        // precision loss). If the era is wrong, we won't.
        Instant roundtrippedInstant = Timestamp64.fromInstant(one).toInstant(ntpEra);

        long actualSeconds = roundtrippedInstant.getEpochSecond();
        assertEquals(expectedSeconds, actualSeconds);
    }

    /**
     * Used to account for the fact that NTP types used 32-bit fixed point storage, so cannot store
     * all values precisely. The value we get out will always be the value we put in, or one that is
     * one unit smaller (due to truncation).
     */
    private static void assertEqualsOrSlightlyLessThan(long expected, long actual) {
        assertTrue("expected=" + expected + ", actual=" + actual,
                expected == actual || expected == actual - 1);
    }

    private static Instant utcInstant(
            int year, int monthOfYear, int day, int hour, int minute, int second, int nanos) {
        return LocalDateTime.of(year, monthOfYear, day, hour, minute, second, nanos)
                .toInstant(ZoneOffset.UTC);
    }
}
