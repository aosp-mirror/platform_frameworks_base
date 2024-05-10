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

package android.util;

import static org.junit.Assert.assertEquals;

import android.platform.test.annotations.IgnoreUnderRavenwood;
import android.platform.test.ravenwood.RavenwoodRule;

import androidx.test.runner.AndroidJUnit4;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.function.Consumer;

@RunWith(AndroidJUnit4.class)
public class TimeUtilsTest {
    @Rule
    public final RavenwoodRule mRavenwood = new RavenwoodRule();

    public static final long SECOND_IN_MILLIS = 1000;
    public static final long MINUTE_IN_MILLIS = SECOND_IN_MILLIS * 60;
    public static final long HOUR_IN_MILLIS = MINUTE_IN_MILLIS * 60;
    public static final long DAY_IN_MILLIS = HOUR_IN_MILLIS * 24;
    public static final long WEEK_IN_MILLIS = DAY_IN_MILLIS * 7;

    @Test
    public void testFormatTime() {
        assertEquals("1672556400000 (now)",
                TimeUtils.formatTime(1672556400000L, 1672556400000L));
        assertEquals("1672556400000 (in 10 ms)",
                TimeUtils.formatTime(1672556400000L, 1672556400000L - 10));
        assertEquals("1672556400000 (10 ms ago)",
                TimeUtils.formatTime(1672556400000L, 1672556400000L + 10));

        // Uses formatter above, so we just care that it doesn't crash
        TimeUtils.formatRealtime(1672556400000L);
        TimeUtils.formatUptime(1672556400000L);
    }

    @Test
    public void testFormatDuration_Zero() {
        assertEquals("0", TimeUtils.formatDuration(0));
    }

    @Test
    public void testFormatDuration_Negative() {
        assertEquals("-10ms", TimeUtils.formatDuration(-10));
    }

    @Test
    public void testFormatDuration() {
        long accum = 900;
        assertEquals("+900ms", TimeUtils.formatDuration(accum));

        accum += 59 * SECOND_IN_MILLIS;
        assertEquals("+59s900ms", TimeUtils.formatDuration(accum));

        accum += 59 * MINUTE_IN_MILLIS;
        assertEquals("+59m59s900ms", TimeUtils.formatDuration(accum));

        accum += 23 * HOUR_IN_MILLIS;
        assertEquals("+23h59m59s900ms", TimeUtils.formatDuration(accum));

        accum += 6 * DAY_IN_MILLIS;
        assertEquals("+6d23h59m59s900ms", TimeUtils.formatDuration(accum));
    }

    @Test
    @IgnoreUnderRavenwood(reason = "Flaky test, b/315872700")
    public void testDumpTime() {
        assertEquals("2023-01-01 00:00:00.000", runWithPrintWriter((pw) -> {
            TimeUtils.dumpTime(pw, 1672556400000L);
        }));
        assertEquals("2023-01-01 00:00:00.000 (now)", runWithPrintWriter((pw) -> {
            TimeUtils.dumpTimeWithDelta(pw, 1672556400000L, 1672556400000L);
        }));
        assertEquals("2023-01-01 00:00:00.000 (-10ms)", runWithPrintWriter((pw) -> {
            TimeUtils.dumpTimeWithDelta(pw, 1672556400000L, 1672556400000L + 10);
        }));
    }

    @Test
    @IgnoreUnderRavenwood(reason = "Flaky test, b/315872700")
    public void testFormatForLogging() {
        assertEquals("unknown", TimeUtils.formatForLogging(0));
        assertEquals("unknown", TimeUtils.formatForLogging(-1));
        assertEquals("unknown", TimeUtils.formatForLogging(Long.MIN_VALUE));
        assertEquals("2023-01-01 00:00:00", TimeUtils.formatForLogging(1672556400000L));
    }

    @Test
    @IgnoreUnderRavenwood(reason = "Flaky test, b/315872700")
    public void testLogTimeOfDay() {
        assertEquals("01-01 00:00:00.000", TimeUtils.logTimeOfDay(1672556400000L));
    }

    public static String runWithPrintWriter(Consumer<PrintWriter> consumer) {
        final StringWriter sw = new StringWriter();
        consumer.accept(new PrintWriter(sw));
        return sw.toString();
    }

    public static String runWithStringBuilder(Consumer<StringBuilder> consumer) {
        final StringBuilder sb = new StringBuilder();
        consumer.accept(sb);
        return sb.toString();
    }
}
