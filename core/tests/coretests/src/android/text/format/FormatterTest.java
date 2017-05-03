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

import static org.junit.Assert.assertEquals;

import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.support.test.InstrumentationRegistry;
import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;
import android.text.format.Formatter.BytesResult;

import java.util.Locale;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

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
        setLocale(Locale.ENGLISH);

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

    private void checkFormatBytes(long bytes, boolean useShort,
            String expectedString, long expectedRounded) {
        BytesResult r = Formatter.formatBytes(mContext.getResources(), bytes,
                Formatter.FLAG_CALCULATE_ROUNDED | (useShort ? Formatter.FLAG_SHORTER : 0));
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
