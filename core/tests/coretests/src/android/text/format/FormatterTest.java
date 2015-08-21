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

import android.content.res.Configuration;
import android.content.res.Resources;
import android.test.AndroidTestCase;
import android.test.suitebuilder.annotation.SmallTest;
import android.text.format.Formatter.BytesResult;

import java.util.Locale;

public class FormatterTest extends AndroidTestCase {

    private Locale mOriginalLocale;

    @Override
    protected void setUp() throws Exception {
        super.setUp();

        mOriginalLocale = mContext.getResources().getConfiguration().locale;
    }

    @Override
    protected void tearDown() throws Exception {
        if (mOriginalLocale != null) {
            setLocale(mOriginalLocale);
        }
        super.tearDown();
    }

    private void setLocale(Locale locale) {
        Resources res = getContext().getResources();
        Configuration config = res.getConfiguration();
        config.locale = locale;
        res.updateConfiguration(config, res.getDisplayMetrics());

        Locale.setDefault(locale);
    }

    @SmallTest
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

        checkFormatBytes(812, true, "812", 812);
        checkFormatBytes(812, false, "812", 812);

        checkFormatBytes(912, true, "0.89", 911);
        checkFormatBytes(912, false, "0.89", 911);

        checkFormatBytes(9123, true, "8.9", 9113);
        checkFormatBytes(9123, false, "8.91", 9123);

        checkFormatBytes(9123000, true, "8.7", 9122611);
        checkFormatBytes(9123000, false, "8.70", 9122611);

        checkFormatBytes(-1, true, "-1", -1);
        checkFormatBytes(-1, false, "-1", -1);

        checkFormatBytes(-912, true, "-0.89", -911);
        checkFormatBytes(-912, false, "-0.89", -911);

        // Missing FLAG_CALCULATE_ROUNDED case.
        BytesResult r = Formatter.formatBytes(getContext().getResources(), 1, 0);
        assertEquals("1", r.value);
        assertEquals(0, r.roundedBytes); // Didn't pass FLAG_CALCULATE_ROUNDED

        // Make sure it works on different locales.
        setLocale(new Locale("es", "ES"));
        checkFormatBytes(9123000, false, "8,70", 9122611);
    }

    private void checkFormatBytes(long bytes, boolean useShort,
            String expectedString, long expectedRounded) {
        BytesResult r = Formatter.formatBytes(getContext().getResources(), bytes,
                Formatter.FLAG_CALCULATE_ROUNDED | (useShort ? Formatter.FLAG_SHORTER : 0));
        assertEquals(expectedString, r.value);
        assertEquals(expectedRounded, r.roundedBytes);
    }
}
