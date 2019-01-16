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
 * Unit tests for {@link DayOfMonthCursor}.
 */
public class DayOfMonthCursorTest extends TestCase {

    @SmallTest
    public void testMonthRows() {
        DayOfMonthCursor mc = new DayOfMonthCursor(2007,
                Calendar.SEPTEMBER, 11, Calendar.SUNDAY);

        assertArraysEqual(new int[]{26, 27, 28, 29, 30, 31, 1},
                mc.getDigitsForRow(0));
        assertArraysEqual(new int[]{2, 3, 4, 5, 6, 7, 8},
                mc.getDigitsForRow(1));
        assertArraysEqual(new int[]{30, 1, 2, 3, 4, 5, 6},
                mc.getDigitsForRow(5));
    }

    @SmallTest
    public void testMoveLeft() {
        DayOfMonthCursor mc = new DayOfMonthCursor(2007,
                Calendar.SEPTEMBER, 3, Calendar.SUNDAY);

        assertEquals(Calendar.SEPTEMBER, mc.getMonth());
        assertEquals(3, mc.getSelectedDayOfMonth());
        assertEquals(1, mc.getSelectedRow());
        assertEquals(1, mc.getSelectedColumn());

        // move left, still same row
        assertFalse(mc.left());
        assertEquals(2, mc.getSelectedDayOfMonth());
        assertEquals(1, mc.getSelectedRow());
        assertEquals(0, mc.getSelectedColumn());

        // wrap over to previous column, same month
        assertFalse(mc.left());
        assertEquals(1, mc.getSelectedDayOfMonth());
        assertEquals(0, mc.getSelectedRow());
        assertEquals(6, mc.getSelectedColumn());

        // wrap to previous month
        assertTrue(mc.left());
        assertEquals(Calendar.AUGUST, mc.getMonth());
        assertEquals(31, mc.getSelectedDayOfMonth());
        assertEquals(4, mc.getSelectedRow());
        assertEquals(5, mc.getSelectedColumn());
    }

    @SmallTest
    public void testMoveRight() {
        DayOfMonthCursor mc = new DayOfMonthCursor(2007,
                Calendar.SEPTEMBER, 28, Calendar.SUNDAY);

        assertEquals(Calendar.SEPTEMBER, mc.getMonth());
        assertEquals(28, mc.getSelectedDayOfMonth());
        assertEquals(4, mc.getSelectedRow());
        assertEquals(5, mc.getSelectedColumn());

        // same row
        assertFalse(mc.right());
        assertEquals(29, mc.getSelectedDayOfMonth());
        assertEquals(4, mc.getSelectedRow());
        assertEquals(6, mc.getSelectedColumn());

        // wrap to next column, same month
        assertFalse(mc.right());
        assertEquals(30, mc.getSelectedDayOfMonth());
        assertEquals(5, mc.getSelectedRow());
        assertEquals(0, mc.getSelectedColumn());

        // next month
        assertTrue(mc.right());
        assertEquals(Calendar.OCTOBER, mc.getMonth());
        assertEquals(1, mc.getSelectedDayOfMonth());
        assertEquals(0, mc.getSelectedRow());
        assertEquals(1, mc.getSelectedColumn());
    }

    @SmallTest
    public void testMoveUp() {
        DayOfMonthCursor mc = new DayOfMonthCursor(2007,
                Calendar.SEPTEMBER, 13, Calendar.SUNDAY);

        assertEquals(Calendar.SEPTEMBER, mc.getMonth());
        assertEquals(13, mc.getSelectedDayOfMonth());
        assertEquals(2, mc.getSelectedRow());
        assertEquals(4, mc.getSelectedColumn());

        // up, same month
        assertFalse(mc.up());
        assertEquals(6, mc.getSelectedDayOfMonth());
        assertEquals(1, mc.getSelectedRow());
        assertEquals(4, mc.getSelectedColumn());

        // up, flips back
        assertTrue(mc.up());
        assertEquals(Calendar.AUGUST, mc.getMonth());
        assertEquals(30, mc.getSelectedDayOfMonth());
        assertEquals(4, mc.getSelectedRow());
        assertEquals(4, mc.getSelectedColumn());
    }

    @SmallTest
    public void testMoveDown() {
        DayOfMonthCursor mc = new DayOfMonthCursor(2007,
                Calendar.SEPTEMBER, 23, Calendar.SUNDAY);

        assertEquals(Calendar.SEPTEMBER, mc.getMonth());
        assertEquals(23, mc.getSelectedDayOfMonth());
        assertEquals(4, mc.getSelectedRow());
        assertEquals(0, mc.getSelectedColumn());

        // down, same month
        assertFalse(mc.down());
        assertEquals(30, mc.getSelectedDayOfMonth());
        assertEquals(5, mc.getSelectedRow());
        assertEquals(0, mc.getSelectedColumn());

        // down, next month
        assertTrue(mc.down());
        assertEquals(Calendar.OCTOBER, mc.getMonth());
        assertEquals(7, mc.getSelectedDayOfMonth());
        assertEquals(1, mc.getSelectedRow());
        assertEquals(0, mc.getSelectedColumn());
    }

    private void assertArraysEqual(int[] expected, int[] actual) {
        assertEquals("array length", expected.length, actual.length);
        for (int i = 0; i < expected.length; i++) {
            assertEquals("index " + i,
                    expected[i], actual[i]);
        }
    }
}
