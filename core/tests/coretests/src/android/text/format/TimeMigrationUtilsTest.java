/*
 * Copyright (C) 2019 The Android Open Source Project
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

import android.platform.test.annotations.Presubmit;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Locale;
import java.util.TimeZone;

@Presubmit
@SmallTest
@RunWith(AndroidJUnit4.class)
public class TimeMigrationUtilsTest {

    private static final int ONE_DAY_IN_SECONDS = 24 * 60 * 60;

    private Locale mDefaultLocale;
    private TimeZone mDefaultTimeZone;

    @Before
    public void setUp() {
        mDefaultLocale = Locale.getDefault();
        mDefaultTimeZone = TimeZone.getDefault();
    }

    @After
    public void tearDown() {
        Locale.setDefault(mDefaultLocale);
        TimeZone.setDefault(mDefaultTimeZone);
    }

    @Test
    public void formatMillisWithFixedFormat_fixes2038Issue() {
        Locale.setDefault(Locale.UK);
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));

        // The following cannot be represented properly using Time because they are outside of the
        // supported range.
        long y2038Issue1 = (((long) Integer.MIN_VALUE) - ONE_DAY_IN_SECONDS) * 1000L;
        assertEquals(
                "1901-12-12 20:45:52", TimeMigrationUtils.formatMillisWithFixedFormat(y2038Issue1));
        long y2038Issue2 = (((long) Integer.MAX_VALUE) + ONE_DAY_IN_SECONDS) * 1000L;
        assertEquals(
                "2038-01-20 03:14:07", TimeMigrationUtils.formatMillisWithFixedFormat(y2038Issue2));
    }

    /**
     * Compares TimeMigrationUtils.formatSimpleDateTime() with the code it is replacing.
     */
    @Test
    public void formatMillisAsDateTime_matchesOldBehavior() {
        // A selection of interesting locales.
        Locale[] locales = new Locale[] {
                Locale.US,
                Locale.UK,
                Locale.FRANCE,
                Locale.JAPAN,
                Locale.CHINA,
                // Android supports RTL locales like arabic and arabic with latin numbers.
                Locale.forLanguageTag("ar-AE"),
                Locale.forLanguageTag("ar-AE-u-nu-latn"),
        };
        // A selection of interesting time zones.
        String[] timeZoneIds = new String[] {
                "UTC", "Europe/London", "America/New_York", "America/Los_Angeles", "Asia/Shanghai",
        };
        // Some arbitrary times when the two formatters should agree.
        long[] timesMillis = new long[] {
                System.currentTimeMillis(),
                0,
                // The Time class only works in 32-bit range, the replacement works beyond that. To
                // avoid messing around with offsets and complicating the test, below there are a
                // day after / before the known limits.
                (Integer.MIN_VALUE + ONE_DAY_IN_SECONDS) * 1000L,
                (Integer.MAX_VALUE - ONE_DAY_IN_SECONDS) * 1000L,
        };

        for (Locale locale : locales) {
            Locale.setDefault(locale);
            for (String timeZoneId : timeZoneIds) {
                TimeZone timeZone = TimeZone.getTimeZone(timeZoneId);
                TimeZone.setDefault(timeZone);
                for (long timeMillis : timesMillis) {
                    Time time = new Time();
                    time.set(timeMillis);
                    String oldResult = time.format("%Y-%m-%d %H:%M:%S");
                    String newResult = TimeMigrationUtils.formatMillisWithFixedFormat(timeMillis);
                    assertEquals(
                            "locale=" + locale + ", timeZoneId=" + timeZoneId
                                    + ", timeMillis=" + timeMillis,
                            oldResult, newResult);
                }
            }
        }
    }
}
