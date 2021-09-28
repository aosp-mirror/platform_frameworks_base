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
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.time.Instant;

public class Timestamp64Test {

    @Test
    public void testFromComponents() {
        long minNtpEraSeconds = 0;
        long maxNtpEraSeconds = 0xFFFFFFFFL;

        expectIllegalArgumentException(() -> Timestamp64.fromComponents(minNtpEraSeconds - 1, 0));
        expectIllegalArgumentException(() -> Timestamp64.fromComponents(maxNtpEraSeconds + 1, 0));

        assertComponentCreation(minNtpEraSeconds, 0);
        assertComponentCreation(maxNtpEraSeconds, 0);
        assertComponentCreation(maxNtpEraSeconds, Integer.MIN_VALUE);
        assertComponentCreation(maxNtpEraSeconds, Integer.MAX_VALUE);
    }

    private static void assertComponentCreation(long ntpEraSeconds, int fractionBits) {
        Timestamp64 value = Timestamp64.fromComponents(ntpEraSeconds, fractionBits);
        assertEquals(ntpEraSeconds, value.getEraSeconds());
        assertEquals(fractionBits, value.getFractionBits());
    }

    @Test
    public void testEqualsAndHashcode() {
        assertEqualsAndHashcode(0, 0);
        assertEqualsAndHashcode(1, 0);
        assertEqualsAndHashcode(0, 1);
    }

    private static void assertEqualsAndHashcode(int eraSeconds, int fractionBits) {
        Timestamp64 one = Timestamp64.fromComponents(eraSeconds, fractionBits);
        Timestamp64 two = Timestamp64.fromComponents(eraSeconds, fractionBits);
        assertEquals(one, two);
        assertEquals(one.hashCode(), two.hashCode());
    }

    @Test
    public void testStringForm() {
        expectIllegalArgumentException(() -> Timestamp64.fromString(""));
        expectIllegalArgumentException(() -> Timestamp64.fromString("."));
        expectIllegalArgumentException(() -> Timestamp64.fromString("1234567812345678"));
        expectIllegalArgumentException(() -> Timestamp64.fromString("12345678?12345678"));
        expectIllegalArgumentException(() -> Timestamp64.fromString("12345678..12345678"));
        expectIllegalArgumentException(() -> Timestamp64.fromString("1.12345678"));
        expectIllegalArgumentException(() -> Timestamp64.fromString("12.12345678"));
        expectIllegalArgumentException(() -> Timestamp64.fromString("123456.12345678"));
        expectIllegalArgumentException(() -> Timestamp64.fromString("1234567.12345678"));
        expectIllegalArgumentException(() -> Timestamp64.fromString("12345678.1"));
        expectIllegalArgumentException(() -> Timestamp64.fromString("12345678.12"));
        expectIllegalArgumentException(() -> Timestamp64.fromString("12345678.123456"));
        expectIllegalArgumentException(() -> Timestamp64.fromString("12345678.1234567"));
        expectIllegalArgumentException(() -> Timestamp64.fromString("X2345678.12345678"));
        expectIllegalArgumentException(() -> Timestamp64.fromString("12345678.X2345678"));

        assertStringCreation("00000000.00000000", 0, 0);
        assertStringCreation("00000001.00000001", 1, 1);
        assertStringCreation("ffffffff.ffffffff", 0xFFFFFFFFL, 0xFFFFFFFF);
    }

    private static void assertStringCreation(
            String string, long expectedSeconds, int expectedFractionBits) {
        Timestamp64 timestamp64 = Timestamp64.fromString(string);
        assertEquals(string, timestamp64.toString());
        assertEquals(expectedSeconds, timestamp64.getEraSeconds());
        assertEquals(expectedFractionBits, timestamp64.getFractionBits());
    }

    @Test
    public void testStringForm_lenientHexCasing() {
        Timestamp64 mixedCaseValue = Timestamp64.fromString("AaBbCcDd.EeFf1234");
        assertEquals(0xAABBCCDDL, mixedCaseValue.getEraSeconds());
        assertEquals(0xEEFF1234, mixedCaseValue.getFractionBits());
    }

    @Test
    public void testFromInstant_secondsHandling() {
        final int era0 = 0;
        final int eraNeg1 = -1;
        final int eraNeg2 = -2;
        final int era1 = 1;

        assertInstantCreationOnlySeconds(-Timestamp64.OFFSET_1900_TO_1970, 0, era0);
        assertInstantCreationOnlySeconds(
                -Timestamp64.OFFSET_1900_TO_1970 - Timestamp64.SECONDS_IN_ERA, 0, eraNeg1);
        assertInstantCreationOnlySeconds(
                -Timestamp64.OFFSET_1900_TO_1970 + Timestamp64.SECONDS_IN_ERA, 0, era1);

        assertInstantCreationOnlySeconds(
                -Timestamp64.OFFSET_1900_TO_1970 - 1, Timestamp64.MAX_SECONDS_IN_ERA, -1);
        assertInstantCreationOnlySeconds(
                -Timestamp64.OFFSET_1900_TO_1970 - Timestamp64.SECONDS_IN_ERA - 1,
                Timestamp64.MAX_SECONDS_IN_ERA, eraNeg2);
        assertInstantCreationOnlySeconds(
                -Timestamp64.OFFSET_1900_TO_1970 + Timestamp64.SECONDS_IN_ERA - 1,
                Timestamp64.MAX_SECONDS_IN_ERA, era0);

        assertInstantCreationOnlySeconds(-Timestamp64.OFFSET_1900_TO_1970 + 1, 1, era0);
        assertInstantCreationOnlySeconds(
                -Timestamp64.OFFSET_1900_TO_1970 - Timestamp64.SECONDS_IN_ERA + 1, 1, eraNeg1);
        assertInstantCreationOnlySeconds(
                -Timestamp64.OFFSET_1900_TO_1970 + Timestamp64.SECONDS_IN_ERA + 1, 1, era1);

        assertInstantCreationOnlySeconds(0, Timestamp64.OFFSET_1900_TO_1970, era0);
        assertInstantCreationOnlySeconds(
                -Timestamp64.SECONDS_IN_ERA, Timestamp64.OFFSET_1900_TO_1970, eraNeg1);
        assertInstantCreationOnlySeconds(
                Timestamp64.SECONDS_IN_ERA, Timestamp64.OFFSET_1900_TO_1970, era1);

        assertInstantCreationOnlySeconds(1, Timestamp64.OFFSET_1900_TO_1970 + 1, era0);
        assertInstantCreationOnlySeconds(
                -Timestamp64.SECONDS_IN_ERA + 1, Timestamp64.OFFSET_1900_TO_1970 + 1, eraNeg1);
        assertInstantCreationOnlySeconds(
                Timestamp64.SECONDS_IN_ERA + 1, Timestamp64.OFFSET_1900_TO_1970 + 1, era1);

        assertInstantCreationOnlySeconds(-1, Timestamp64.OFFSET_1900_TO_1970 - 1, era0);
        assertInstantCreationOnlySeconds(
                -Timestamp64.SECONDS_IN_ERA - 1, Timestamp64.OFFSET_1900_TO_1970 - 1, eraNeg1);
        assertInstantCreationOnlySeconds(
                Timestamp64.SECONDS_IN_ERA - 1, Timestamp64.OFFSET_1900_TO_1970 - 1, era1);
    }

    private static void assertInstantCreationOnlySeconds(
            long epochSeconds, long expectedNtpEraSeconds, int ntpEra) {
        int nanosOfSecond = 0;
        Instant instant = Instant.ofEpochSecond(epochSeconds, nanosOfSecond);
        Timestamp64 timestamp = Timestamp64.fromInstant(instant);
        assertEquals(expectedNtpEraSeconds, timestamp.getEraSeconds());

        int expectedFractionBits = 0;
        assertEquals(expectedFractionBits, timestamp.getFractionBits());

        // Confirm the Instant can be round-tripped if we know the era. Also assumes the nanos can
        // be stored precisely; 0 can be.
        Instant roundTrip = timestamp.toInstant(ntpEra);
        assertEquals(instant, roundTrip);
    }

    @Test
    public void testFromInstant_fractionHandling() {
        // Try some values we know can be represented exactly.
        assertInstantCreationOnlyFractionExact(0x0, 0);
        assertInstantCreationOnlyFractionExact(0x80000000, 500_000_000L);
        assertInstantCreationOnlyFractionExact(0x40000000, 250_000_000L);

        // Test the limits of precision.
        assertInstantCreationOnlyFractionExact(0x00000006, 1L);
        assertInstantCreationOnlyFractionExact(0x00000005, 1L);
        assertInstantCreationOnlyFractionExact(0x00000004, 0L);
        assertInstantCreationOnlyFractionExact(0x00000002, 0L);
        assertInstantCreationOnlyFractionExact(0x00000001, 0L);

        // Confirm nanosecond storage / precision is within 1ns.
        final boolean exhaustive = false;
        for (int i = 0; i < NANOS_PER_SECOND; i++) {
            Instant instant = Instant.ofEpochSecond(0, i);
            Instant roundTripped = Timestamp64.fromInstant(instant).toInstant(0);
            assertNanosWithTruncationAllowed(i, roundTripped);
            if (!exhaustive) {
                i += 999_999;
            }
        }
    }

    private static void assertInstantCreationOnlyFractionExact(
            int fractionBits, long expectedNanos) {
        Timestamp64 timestamp64 = Timestamp64.fromComponents(0, fractionBits);

        final int ntpEra = 0;
        Instant instant = timestamp64.toInstant(ntpEra);

        assertEquals(expectedNanos, instant.getNano());
    }

    private static void assertNanosWithTruncationAllowed(long expectedNanos, Instant instant) {
        // Allow for < 1ns difference due to truncation.
        long actualNanos = instant.getNano();
        assertTrue("expectedNanos=" + expectedNanos + ",  actualNanos=" + actualNanos,
                actualNanos == expectedNanos || actualNanos == expectedNanos - 1);
    }

    private static void expectIllegalArgumentException(Runnable r) {
        try {
            r.run();
            fail();
        } catch (IllegalArgumentException e) {
            // Expected
        }
    }
}
