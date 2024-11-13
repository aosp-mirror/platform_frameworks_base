/*
 * Copyright (C) 2012 The Android Open Source Project
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

package android.text.format;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.compat.testing.PlatformCompatChangeRule;
import android.icu.text.DateFormatSymbols;
import android.platform.test.annotations.Presubmit;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import libcore.junit.util.compat.CoreCompatChangeRule.DisableCompatChanges;
import libcore.junit.util.compat.CoreCompatChangeRule.EnableCompatChanges;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestRule;
import org.junit.runner.RunWith;

import java.util.Arrays;
import java.util.Locale;

@Presubmit
@SmallTest
@RunWith(AndroidJUnit4.class)
public class DateFormatTest {

    @Rule
    public TestRule compatChangeRule = new PlatformCompatChangeRule();

    @Test
    public void testHasDesignator() {
        assertTrue(DateFormat.hasDesignator("hh:mm:ss", DateFormat.MINUTE));
        assertTrue(DateFormat.hasDesignator("myyyy", DateFormat.MINUTE));
        assertTrue(DateFormat.hasDesignator("mmm", DateFormat.MINUTE));

        assertFalse(DateFormat.hasDesignator("hh:MM:ss", DateFormat.MINUTE));
    }

    @Test
    public void testHasDesignatorEscaped() {
        assertTrue(DateFormat.hasDesignator("hh:mm 'LOL'", DateFormat.MINUTE));

        assertFalse(DateFormat.hasDesignator("hh:mm 'yyyy'", DateFormat.YEAR));
    }

    @Test
    public void testIs24HourLocale() {
        assertFalse(DateFormat.is24HourLocale(Locale.US));
        assertTrue(DateFormat.is24HourLocale(Locale.GERMANY));
    }

    @Test
    public void testgetIcuDateFormatSymbols() {
        DateFormatSymbols dfs = DateFormat.getIcuDateFormatSymbols(Locale.US);
        assertEquals("AM", dfs.getAmPmStrings()[0]);
        assertEquals("PM", dfs.getAmPmStrings()[1]);
        assertEquals("a", dfs.getAmpmNarrowStrings()[0]);
        assertEquals("p", dfs.getAmpmNarrowStrings()[1]);
    }

    @Test
    public void testGetDateFormatOrder() {
        // lv and fa use differing orders depending on whether you're using numeric or
        // textual months.
        Locale lv = new Locale("lv");
        assertEquals("[d, M, y]", Arrays.toString(DateFormat.getDateFormatOrder(
                best(lv, "yyyy-M-dd"))));
        assertEquals("[y, d, M]", Arrays.toString(DateFormat.getDateFormatOrder(
                best(lv, "yyyy-MMM-dd"))));
        assertEquals("[d, M, \u0000]", Arrays.toString(DateFormat.getDateFormatOrder(
                best(lv, "MMM-dd"))));
        Locale fa = new Locale("fa");
        assertEquals("[y, M, d]", Arrays.toString(DateFormat.getDateFormatOrder(
                best(fa, "yyyy-M-dd"))));
        assertEquals("[d, M, y]", Arrays.toString(DateFormat.getDateFormatOrder(
                best(fa, "yyyy-MMM-dd"))));
        assertEquals("[d, M, \u0000]", Arrays.toString(DateFormat.getDateFormatOrder(
                best(fa, "MMM-dd"))));

        // English differs on each side of the Atlantic.
        Locale enUS = Locale.US;
        assertEquals("[M, d, y]", Arrays.toString(DateFormat.getDateFormatOrder(
                best(enUS, "yyyy-M-dd"))));
        assertEquals("[M, d, y]", Arrays.toString(DateFormat.getDateFormatOrder(
                best(enUS, "yyyy-MMM-dd"))));
        assertEquals("[M, d, \u0000]", Arrays.toString(DateFormat.getDateFormatOrder(
                best(enUS, "MMM-dd"))));
        Locale enGB = Locale.UK;
        assertEquals("[d, M, y]", Arrays.toString(DateFormat.getDateFormatOrder(
                best(enGB, "yyyy-M-dd"))));
        assertEquals("[d, M, y]", Arrays.toString(DateFormat.getDateFormatOrder(
                best(enGB, "yyyy-MMM-dd"))));
        assertEquals("[d, M, \u0000]", Arrays.toString(DateFormat.getDateFormatOrder(
                best(enGB, "MMM-dd"))));

        assertEquals("[y, M, d]", Arrays.toString(DateFormat.getDateFormatOrder(
                "yyyy - 'why' '' 'ddd' MMM-dd")));

        try {
            DateFormat.getDateFormatOrder("the quick brown fox jumped over the lazy dog");
            fail();
        } catch (IllegalArgumentException expected) {
        }

        try {
            DateFormat.getDateFormatOrder("'");
            fail();
        } catch (IllegalArgumentException expected) {
        }

        try {
            DateFormat.getDateFormatOrder("yyyy'");
            fail();
        } catch (IllegalArgumentException expected) {
        }

        try {
            DateFormat.getDateFormatOrder("yyyy'MMM");
            fail();
        } catch (IllegalArgumentException expected) {
        }
    }

    private static String best(Locale l, String skeleton) {
        return DateFormat.getBestDateTimePattern(l, skeleton);
    }

    @Test
    @EnableCompatChanges({DateFormat.DISALLOW_DUPLICATE_FIELD_IN_SKELETON})
    public void testGetBestDateTimePattern_disableDuplicateField() {
        assertIllegalArgumentException(Locale.US, "jmma");
        assertIllegalArgumentException(Locale.US, "ahmma");
    }

    @Test
    @DisableCompatChanges({DateFormat.DISALLOW_DUPLICATE_FIELD_IN_SKELETON})
    public void testGetBestDateTimePattern_enableDuplicateField() {
        // en-US uses 12-hour format by default.
        assertEquals("h:mm a", DateFormat.getBestDateTimePattern(Locale.US, "jmma"));
        assertEquals("h:mm a", DateFormat.getBestDateTimePattern(Locale.US, "ahmma"));
    }

    private static void assertIllegalArgumentException(Locale l, String skeleton) {
        try {
            DateFormat.getBestDateTimePattern(l, skeleton);
            fail("getBestDateTimePattern() does not fail with Locale: " + l
                    + " skeleton: " + skeleton);
        } catch (IllegalArgumentException expected) {
            // ignored
        }
    }
}
