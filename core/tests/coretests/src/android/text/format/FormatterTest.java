/*
 * Copyright (C) 2015 The Android Open Source Project
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

import static android.text.format.Formatter.FLAG_IEC_UNITS;
import static android.text.format.Formatter.FLAG_SI_UNITS;

import static org.junit.Assert.assertEquals;

import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.platform.test.annotations.Presubmit;
import android.support.test.InstrumentationRegistry;
import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;
import android.text.format.Formatter.BytesResult;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Locale;

@Presubmit
@SmallTest
@RunWith(AndroidJUnit4.class)
public class FormatterTest {
    private Locale mOriginalLocale;
    private Context mContext;

    @Before
    public void setup() {
        mContext = InstrumentationRegistry.getContext();
        mOriginalLocale = mContext.getResources()
            .getConfiguration().locale;
    }

    @After
    public void tearDown() {
        if (mOriginalLocale != null) {
            setLocale(mOriginalLocale);
        }
    }

    @Test
    public void testFormatBytes() {
        setLocale(Locale.US);

        checkFormatBytes(0, true, "0", 0);
        checkFormatBytes(0, false, "0", 0);

        checkFormatBytes(1, true, "1", 1);
        checkFormatBytes(1, false, "1", 1);

        checkFormatBytes(12, true, "12", 12);
        checkFormatBytes(12, false, "12", 12);

        checkFormatBytes(123, true, "123", 123);
        checkFormatBytes(123, false, "123", 123);

        checkFormatBytes(900, true, "900", 900);
        checkFormatBytes(900, false, "900", 900);

        checkFormatBytes(901, true, "0.90", 900);
        checkFormatBytes(901, false, "0.90", 900);

        checkFormatBytes(912, true, "0.91", 910);
        checkFormatBytes(912, false, "0.91", 910);

        checkFormatBytes(9123, true, "9.1", 9100);
        checkFormatBytes(9123, false, "9.12", 9120);

        checkFormatBytes(9123456, true, "9.1", 9100000);
        checkFormatBytes(9123456, false, "9.12", 9120000);

        checkFormatBytes(-1, true, "-1", -1);
        checkFormatBytes(-1, false, "-1", -1);

        checkFormatBytes(-914, true, "-0.91", -910);
        checkFormatBytes(-914, false, "-0.91", -910);

        // Missing FLAG_CALCULATE_ROUNDED case.
        BytesResult r = Formatter.formatBytes(mContext.getResources(), 1, 0);
        assertEquals("1", r.value);
        assertEquals(0, r.roundedBytes); // Didn't pass FLAG_CALCULATE_ROUNDED

        // Make sure it works on different locales.
        setLocale(new Locale("es", "ES"));
        checkFormatBytes(9123000, false, "9,12", 9120000);
    }

    @Test
    public void testFormatBytesSi() {
        setLocale(Locale.US);

        checkFormatBytes(1_000, FLAG_SI_UNITS, "1.00", 1_000);
        checkFormatBytes(1_024, FLAG_SI_UNITS, "1.02", 1_020);
        checkFormatBytes(1_500, FLAG_SI_UNITS, "1.50", 1_500);
        checkFormatBytes(12_582_912L, FLAG_SI_UNITS, "12.58", 12_580_000L);
    }

    @Test
    public void testFormatBytesIec() {
        setLocale(Locale.US);

        checkFormatBytes(1_000, FLAG_IEC_UNITS, "0.98", 1_003);
        checkFormatBytes(1_024, FLAG_IEC_UNITS, "1.00", 1_024);
        checkFormatBytes(1_500, FLAG_IEC_UNITS, "1.46", 1_495);
        checkFormatBytes(12_500_000L, FLAG_IEC_UNITS, "11.92", 12_499_025L);
        checkFormatBytes(12_582_912L, FLAG_IEC_UNITS, "12.00", 12_582_912L);
    }

    private static final long SECOND = 1000;
    private static final long MINUTE = 60 * SECOND;
    private static final long HOUR = 60 * MINUTE;
    private static final long DAY = 24 * HOUR;

    @Test
    public void testFormatShortElapsedTime() {
        setLocale(Locale.US);
        assertEquals("3 days", Formatter.formatShortElapsedTime(mContext, 2 * DAY + 12 * HOUR));
        assertEquals("2 days", Formatter.formatShortElapsedTime(mContext, 2 * DAY + 11 * HOUR));
        assertEquals("2 days", Formatter.formatShortElapsedTime(mContext, 2 * DAY));
        assertEquals("1 day, 23 hr",
                Formatter.formatShortElapsedTime(mContext, 1 * DAY + 23 * HOUR + 59 * MINUTE));
        assertEquals("1 day",
                Formatter.formatShortElapsedTime(mContext, 1 * DAY + 59 * MINUTE));
        assertEquals("1 day", Formatter.formatShortElapsedTime(mContext, 1 * DAY));
        assertEquals("24 hr", Formatter.formatShortElapsedTime(mContext, 23 * HOUR + 30 * MINUTE));
        assertEquals("3 hr", Formatter.formatShortElapsedTime(mContext, 2 * HOUR + 30 * MINUTE));
        assertEquals("2 hr", Formatter.formatShortElapsedTime(mContext, 2 * HOUR));
        assertEquals("1 hr", Formatter.formatShortElapsedTime(mContext, 1 * HOUR));
        assertEquals("60 min",
                Formatter.formatShortElapsedTime(mContext, 59 * MINUTE + 30 * SECOND));
        assertEquals("59 min",
                Formatter.formatShortElapsedTime(mContext, 59 * MINUTE));
        assertEquals("3 min", Formatter.formatShortElapsedTime(mContext, 2 * MINUTE + 30 * SECOND));
        assertEquals("2 min", Formatter.formatShortElapsedTime(mContext, 2 * MINUTE));
        assertEquals("1 min, 59 sec",
                Formatter.formatShortElapsedTime(mContext, 1 * MINUTE + 59 * SECOND + 999));
        assertEquals("1 min", Formatter.formatShortElapsedTime(mContext, 1 * MINUTE));
        assertEquals("59 sec", Formatter.formatShortElapsedTime(mContext, 59 * SECOND + 999));
        assertEquals("1 sec", Formatter.formatShortElapsedTime(mContext, 1 * SECOND));
        assertEquals("0 sec", Formatter.formatShortElapsedTime(mContext, 1));
        assertEquals("0 sec", Formatter.formatShortElapsedTime(mContext, 0));

        // Make sure it works on different locales.
        setLocale(Locale.FRANCE);
        assertEquals("2 j", Formatter.formatShortElapsedTime(mContext, 2 * DAY));
    }

    @Test
    public void testFormatShortElapsedTimeRoundingUpToMinutes() {
        setLocale(Locale.US);
        assertEquals("3 days", Formatter.formatShortElapsedTimeRoundingUpToMinutes(
                mContext, 2 * DAY + 12 * HOUR));
        assertEquals("2 days", Formatter.formatShortElapsedTimeRoundingUpToMinutes(
                mContext, 2 * DAY + 11 * HOUR));
        assertEquals("2 days", Formatter.formatShortElapsedTimeRoundingUpToMinutes(
                mContext, 2 * DAY));
        assertEquals("1 day, 23 hr", Formatter.formatShortElapsedTimeRoundingUpToMinutes(
                mContext, 1 * DAY + 23 * HOUR + 59 * MINUTE));
        assertEquals("1 day", Formatter.formatShortElapsedTimeRoundingUpToMinutes(
                mContext, 1 * DAY + 59 * MINUTE));
        assertEquals("1 day", Formatter.formatShortElapsedTimeRoundingUpToMinutes(
                mContext, 1 * DAY));
        assertEquals("24 hr", Formatter.formatShortElapsedTimeRoundingUpToMinutes(
                mContext, 23 * HOUR + 30 * MINUTE));
        assertEquals("3 hr", Formatter.formatShortElapsedTimeRoundingUpToMinutes(
                mContext, 2 * HOUR + 30 * MINUTE));
        assertEquals("2 hr", Formatter.formatShortElapsedTimeRoundingUpToMinutes(
                mContext, 2 * HOUR));
        assertEquals("1 hr", Formatter.formatShortElapsedTimeRoundingUpToMinutes(
                mContext, 1 * HOUR));
        assertEquals("1 hr", Formatter.formatShortElapsedTimeRoundingUpToMinutes(
                mContext, 59 * MINUTE + 30 * SECOND));
        assertEquals("59 min", Formatter.formatShortElapsedTimeRoundingUpToMinutes(
                mContext, 59 * MINUTE));
        assertEquals("3 min", Formatter.formatShortElapsedTimeRoundingUpToMinutes(
                mContext, 2 * MINUTE + 30 * SECOND));
        assertEquals("2 min", Formatter.formatShortElapsedTimeRoundingUpToMinutes(
                mContext, 2 * MINUTE));
        assertEquals("2 min", Formatter.formatShortElapsedTimeRoundingUpToMinutes(
                mContext, 1 * MINUTE + 59 * SECOND + 999));
        assertEquals("1 min", Formatter.formatShortElapsedTimeRoundingUpToMinutes(
                mContext, 1 * MINUTE));
        assertEquals("1 min", Formatter.formatShortElapsedTimeRoundingUpToMinutes(
                mContext, 59 * SECOND + 999));
        assertEquals("1 min", Formatter.formatShortElapsedTimeRoundingUpToMinutes(
                mContext, 1 * SECOND));
        assertEquals("1 min", Formatter.formatShortElapsedTimeRoundingUpToMinutes(
                mContext, 1));
        assertEquals("0 min", Formatter.formatShortElapsedTimeRoundingUpToMinutes(
                mContext, 0));

        // Make sure it works on different locales.
        setLocale(new Locale("ru", "RU"));
        assertEquals("1 мин.", Formatter.formatShortElapsedTimeRoundingUpToMinutes(
                mContext, 1 * SECOND));
    }

    private void checkFormatBytes(long bytes, boolean useShort,
            String expectedString, long expectedRounded) {
        checkFormatBytes(bytes, (useShort ? Formatter.FLAG_SHORTER : 0),
                expectedString, expectedRounded);
    }

    private void checkFormatBytes(long bytes, int flags,
            String expectedString, long expectedRounded) {
        BytesResult r = Formatter.formatBytes(mContext.getResources(), bytes,
                Formatter.FLAG_CALCULATE_ROUNDED | flags);
        assertEquals(expectedString, r.value);
        assertEquals(expectedRounded, r.roundedBytes);
    }

    private void setLocale(Locale locale) {
        Resources res = mContext.getResources();
        Configuration config = res.getConfiguration();
        config.locale = locale;
        res.updateConfiguration(config, res.getDisplayMetrics());

        Locale.setDefault(locale);
    }
}
