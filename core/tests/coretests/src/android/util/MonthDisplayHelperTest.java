/*
 * Copyright (C) 2007 The Android Open Source Project
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

import androidx.test.filters.SmallTest;

import junit.framework.TestCase;

import java.util.Calendar;

/**
 * Unit tests for {@link MonthDisplayHelper}.
 */
public class MonthDisplayHelperTest extends TestCase {


    @SmallTest
    public void testFirstDayOfMonth() {

        assertEquals("august 2007",
                Calendar.WEDNESDAY,
                new MonthDisplayHelper(2007, Calendar.AUGUST).getFirstDayOfMonth());

        assertEquals("september, 2007",
                Calendar.SATURDAY,
                new MonthDisplayHelper(2007, Calendar.SEPTEMBER).getFirstDayOfMonth());
    }

    @SmallTest
    public void testNumberOfDaysInCurrentMonth() {
        assertEquals(30,
                new MonthDisplayHelper(2007, Calendar.SEPTEMBER).getNumberOfDaysInMonth());
    }

    @SmallTest
    public void testMonthRows() {
        MonthDisplayHelper helper = new MonthDisplayHelper(2007, Calendar.SEPTEMBER);

        assertArraysEqual(new int[]{26, 27, 28, 29, 30, 31, 1},
                helper.getDigitsForRow(0));
        assertArraysEqual(new int[]{2, 3, 4, 5, 6, 7, 8},
                helper.getDigitsForRow(1));
        assertArraysEqual(new int[]{30, 1, 2, 3, 4, 5, 6},
                helper.getDigitsForRow(5));

    }

    @SmallTest
    public void testMonthRowsWeekStartsMonday() {
        MonthDisplayHelper helper = new MonthDisplayHelper(2007,
                Calendar.SEPTEMBER, Calendar.MONDAY);

        assertArraysEqual(new int[]{27, 28, 29, 30, 31, 1, 2},
                helper.getDigitsForRow(0));
        assertArraysEqual(new int[]{3, 4, 5, 6, 7, 8, 9},
                helper.getDigitsForRow(1));
        assertArraysEqual(new int[]{24, 25, 26, 27, 28, 29, 30},
                helper.getDigitsForRow(4));
        assertArraysEqual(new int[]{1, 2, 3, 4, 5, 6, 7},
                helper.getDigitsForRow(5));
    }

    @SmallTest
    public void testMonthRowsWeekStartsSaturday() {
        MonthDisplayHelper helper = new MonthDisplayHelper(2007,
                Calendar.SEPTEMBER, Calendar.SATURDAY);

        assertArraysEqual(new int[]{1, 2, 3, 4, 5, 6, 7},
                helper.getDigitsForRow(0));
        assertArraysEqual(new int[]{8, 9, 10, 11, 12, 13, 14},
                helper.getDigitsForRow(1));
        assertArraysEqual(new int[]{29, 30, 1, 2, 3, 4, 5},
                helper.getDigitsForRow(4));


        helper = new MonthDisplayHelper(2007,
                Calendar.AUGUST, Calendar.SATURDAY);

        assertArraysEqual(new int[]{28, 29, 30, 31, 1, 2, 3},
                helper.getDigitsForRow(0));
        assertArraysEqual(new int[]{4, 5, 6, 7, 8, 9, 10},
                helper.getDigitsForRow(1));
        assertArraysEqual(new int[]{25, 26, 27, 28, 29, 30, 31},
                helper.getDigitsForRow(4));
    }

    @SmallTest
    public void testGetDayAt() {
        MonthDisplayHelper helper = new MonthDisplayHelper(2007,
                Calendar.SEPTEMBER, Calendar.SUNDAY);

        assertEquals(26, helper.getDayAt(0, 0));
        assertEquals(1, helper.getDayAt(0, 6));
        assertEquals(17, helper.getDayAt(3, 1));
        assertEquals(2, helper.getDayAt(5, 2));
    }

    @SmallTest
    public void testPrevMonth() {
        MonthDisplayHelper helper = new MonthDisplayHelper(2007,
                Calendar.SEPTEMBER, Calendar.SUNDAY);

        assertArraysEqual(new int[]{26, 27, 28, 29, 30, 31, 1},
                helper.getDigitsForRow(0));

        helper.previousMonth();

        assertEquals(Calendar.AUGUST, helper.getMonth());
        assertArraysEqual(new int[]{29, 30, 31, 1, 2, 3, 4},
                helper.getDigitsForRow(0));
    }

    @SmallTest
    public void testPrevMonthRollOver() {
        MonthDisplayHelper helper = new MonthDisplayHelper(2007,
                Calendar.JANUARY);

        helper.previousMonth();

        assertEquals(2006, helper.getYear());
        assertEquals(Calendar.DECEMBER, helper.getMonth());
    }

    @SmallTest
    public void testNextMonth() {
        MonthDisplayHelper helper = new MonthDisplayHelper(2007,
                Calendar.AUGUST, Calendar.SUNDAY);

        assertArraysEqual(new int[]{29, 30, 31, 1, 2, 3, 4},
                helper.getDigitsForRow(0));

        helper.nextMonth();

        assertEquals(Calendar.SEPTEMBER, helper.getMonth());
        assertArraysEqual(new int[]{26, 27, 28, 29, 30, 31, 1},
                helper.getDigitsForRow(0));
    }

    @SmallTest
    public void testGetRowOf() {
        MonthDisplayHelper helper = new MonthDisplayHelper(2007,
                Calendar.AUGUST, Calendar.SUNDAY);

        assertEquals(0, helper.getRowOf(2));
        assertEquals(0, helper.getRowOf(4));
        assertEquals(2, helper.getRowOf(12));
        assertEquals(2, helper.getRowOf(18));
        assertEquals(3, helper.getRowOf(19));
    }

    @SmallTest
    public void testGetColumnOf() {
        MonthDisplayHelper helper = new MonthDisplayHelper(2007,
                Calendar.AUGUST, Calendar.SUNDAY);

        assertEquals(3, helper.getColumnOf(1));
        assertEquals(4, helper.getColumnOf(9));
        assertEquals(5, helper.getColumnOf(17));
        assertEquals(6, helper.getColumnOf(25));
        assertEquals(0, helper.getColumnOf(26));
    }

    @SmallTest
    public void testWithinCurrentMonth() {
        MonthDisplayHelper helper = new MonthDisplayHelper(2007,
                Calendar.SEPTEMBER, Calendar.SUNDAY);

        // out of bounds
        assertFalse(helper.isWithinCurrentMonth(-1, 3));
        assertFalse(helper.isWithinCurrentMonth(6, 3));
        assertFalse(helper.isWithinCurrentMonth(2, -1));
        assertFalse(helper.isWithinCurrentMonth(2, 7));

        // last day of previous month
        assertFalse(helper.isWithinCurrentMonth(0, 5));

        // first day of next month
        assertFalse(helper.isWithinCurrentMonth(5, 1));

        // first day in month
        assertTrue(helper.isWithinCurrentMonth(0, 6));

        // last day in month
        assertTrue(helper.isWithinCurrentMonth(5, 0));
    }

    private void assertArraysEqual(int[] expected, int[] actual) {
        assertEquals("array length", expected.length, actual.length);
        for (int i = 0; i < expected.length; i++) {
            assertEquals("index " + i,
                    expected[i], actual[i]);
        }
    }

}
