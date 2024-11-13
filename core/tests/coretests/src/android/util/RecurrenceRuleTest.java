/*
 * Copyright (C) 2017 The Android Open Source Project
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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.time.Clock;
import java.time.Instant;
import java.time.Period;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Iterator;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class RecurrenceRuleTest {

    static Clock sOriginalClock;

    @Before
    public void setUp() throws Exception {
        sOriginalClock = RecurrenceRule.sClock;
    }

    @After
    public void tearDown() throws Exception {
        RecurrenceRule.sClock = sOriginalClock;
    }

    private void setClock(Instant instant) {
        RecurrenceRule.sClock = Clock.fixed(instant, ZoneId.systemDefault());
    }

    @Test
    public void testSimpleMonth() throws Exception {
        setClock(Instant.parse("2015-11-20T10:15:30.00Z"));
        final RecurrenceRule r = new RecurrenceRule(
                ZonedDateTime.parse("2010-11-14T00:00:00.000Z"),
                null,
                Period.ofMonths(1));

        assertTrue(r.isMonthly());

        final Iterator<Range<ZonedDateTime>> it = r.cycleIterator();
        assertTrue(it.hasNext());
        assertEquals(new Range<>(
                ZonedDateTime.parse("2015-11-14T00:00:00.00Z"),
                ZonedDateTime.parse("2015-12-14T00:00:00.00Z")), it.next());
        assertTrue(it.hasNext());
        assertEquals(new Range<>(
                ZonedDateTime.parse("2015-10-14T00:00:00.00Z"),
                ZonedDateTime.parse("2015-11-14T00:00:00.00Z")), it.next());
    }

    @Test
    public void testSimpleDays() throws Exception {
        setClock(Instant.parse("2015-01-01T10:15:30.00Z"));
        final RecurrenceRule r = new RecurrenceRule(
                ZonedDateTime.parse("2010-11-14T00:11:00.000Z"),
                ZonedDateTime.parse("2010-11-20T00:11:00.000Z"),
                Period.ofDays(3));

        assertFalse(r.isMonthly());

        final Iterator<Range<ZonedDateTime>> it = r.cycleIterator();
        assertTrue(it.hasNext());
        assertEquals(new Range<>(
                ZonedDateTime.parse("2010-11-17T00:11:00.00Z"),
                ZonedDateTime.parse("2010-11-20T00:11:00.00Z")), it.next());
        assertTrue(it.hasNext());
        assertEquals(new Range<>(
                ZonedDateTime.parse("2010-11-14T00:11:00.00Z"),
                ZonedDateTime.parse("2010-11-17T00:11:00.00Z")), it.next());
        assertFalse(it.hasNext());
    }

    @Test
    public void testNotRecurring() throws Exception {
        setClock(Instant.parse("2015-01-01T10:15:30.00Z"));
        final RecurrenceRule r = new RecurrenceRule(
                ZonedDateTime.parse("2010-11-14T00:11:00.000Z"),
                ZonedDateTime.parse("2010-11-20T00:11:00.000Z"),
                null);

        assertFalse(r.isMonthly());

        final Iterator<Range<ZonedDateTime>> it = r.cycleIterator();
        assertTrue(it.hasNext());
        assertEquals(new Range<>(
                ZonedDateTime.parse("2010-11-14T00:11:00.000Z"),
                ZonedDateTime.parse("2010-11-20T00:11:00.000Z")), it.next());
        assertFalse(it.hasNext());
    }

    @Test
    public void testNever() throws Exception {
        setClock(Instant.parse("2015-01-01T10:15:30.00Z"));
        final RecurrenceRule r = RecurrenceRule.buildNever();

        assertFalse(r.isMonthly());

        final Iterator<Range<ZonedDateTime>> it = r.cycleIterator();
        assertFalse(it.hasNext());
    }

    @Test
    public void testSane() throws Exception {
        final RecurrenceRule r = new RecurrenceRule(
                ZonedDateTime.parse("1980-01-31T00:00:00.000Z"),
                ZonedDateTime.parse("2030-01-31T00:00:00.000Z"),
                Period.ofMonths(1));

        final Iterator<Range<ZonedDateTime>> it = r.cycleIterator();
        ZonedDateTime lastStart = null;
        int months = 0;
        while (it.hasNext()) {
            final Range<ZonedDateTime> cycle = it.next();

            // Make sure cycle has reasonable length
            final long length = cycle.getUpper().toEpochSecond() - cycle.getLower().toEpochSecond();
            assertTrue(cycle + " must be more than 4 weeks", length >= 2419200);
            assertTrue(cycle + " must be less than 5 weeks", length <= 3024000);

            // Make sure we have no gaps
            if (lastStart != null) {
                assertEquals(lastStart, cycle.getUpper());
            }
            lastStart = cycle.getLower();
            months++;
        }

        assertEquals(600, months);
    }
}
