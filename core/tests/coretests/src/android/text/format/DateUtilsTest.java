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

import android.content.res.Configuration;
import android.content.res.Resources;
import android.os.LocaleList;
import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;

import java.util.Locale;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class DateUtilsTest {

    private static final LocaleList LOCALE_LIST_US = new LocaleList(Locale.US);
    private LocaleList mOriginalLocales;

    @Before
    public void setup() {
        mOriginalLocales = Resources.getSystem().getConfiguration().getLocales();
        setLocales(LOCALE_LIST_US);
    }

    @After
    public void teardown() {
        setLocales(mOriginalLocales);
    }

    @Test
    public void test_formatDuration_seconds() {
        assertEquals("0 seconds", DateUtils.formatDuration(0));
        assertEquals("0 seconds", DateUtils.formatDuration(1));
        assertEquals("0 seconds", DateUtils.formatDuration(499));
        assertEquals("1 second", DateUtils.formatDuration(500));
        assertEquals("1 second", DateUtils.formatDuration(1000));
        assertEquals("2 seconds", DateUtils.formatDuration(1500));

        assertEquals("0 seconds", DateUtils.formatDuration(0, DateUtils.LENGTH_LONG));
        assertEquals("1 second", DateUtils.formatDuration(1000, DateUtils.LENGTH_LONG));
        assertEquals("2 seconds", DateUtils.formatDuration(1500, DateUtils.LENGTH_LONG));

        assertEquals("0 sec", DateUtils.formatDuration(0, DateUtils.LENGTH_SHORT));
        assertEquals("1 sec", DateUtils.formatDuration(1000, DateUtils.LENGTH_SHORT));
        assertEquals("2 sec", DateUtils.formatDuration(1500, DateUtils.LENGTH_SHORT));

        assertEquals("0s", DateUtils.formatDuration(0, DateUtils.LENGTH_SHORTEST));
        assertEquals("1s", DateUtils.formatDuration(1000, DateUtils.LENGTH_SHORTEST));
        assertEquals("2s", DateUtils.formatDuration(1500, DateUtils.LENGTH_SHORTEST));
    }

    @Test
    public void test_formatDuration_Minutes() {
        assertEquals("59 seconds", DateUtils.formatDuration(59000));
        assertEquals("60 seconds", DateUtils.formatDuration(59500));
        assertEquals("1 minute", DateUtils.formatDuration(60000));
        assertEquals("2 minutes", DateUtils.formatDuration(120000));

        assertEquals("1 minute", DateUtils.formatDuration(60000, DateUtils.LENGTH_LONG));
        assertEquals("2 minutes", DateUtils.formatDuration(120000, DateUtils.LENGTH_LONG));

        assertEquals("1 min", DateUtils.formatDuration(60000, DateUtils.LENGTH_SHORT));
        assertEquals("2 min", DateUtils.formatDuration(120000, DateUtils.LENGTH_SHORT));

        assertEquals("1m", DateUtils.formatDuration(60000, DateUtils.LENGTH_SHORTEST));
        assertEquals("2m", DateUtils.formatDuration(120000, DateUtils.LENGTH_SHORTEST));
    }

    @Test
    public void test_formatDuration_Hours() {
        assertEquals("59 minutes", DateUtils.formatDuration(3540000));
        assertEquals("1 hour", DateUtils.formatDuration(3600000));
        assertEquals("48 hours", DateUtils.formatDuration(172800000));

        assertEquals("1 hour", DateUtils.formatDuration(3600000, DateUtils.LENGTH_LONG));
        assertEquals("48 hours", DateUtils.formatDuration(172800000, DateUtils.LENGTH_LONG));

        assertEquals("1 hr", DateUtils.formatDuration(3600000, DateUtils.LENGTH_SHORT));
        assertEquals("48 hr", DateUtils.formatDuration(172800000, DateUtils.LENGTH_SHORT));

        assertEquals("1h", DateUtils.formatDuration(3600000, DateUtils.LENGTH_SHORTEST));
        assertEquals("48h", DateUtils.formatDuration(172800000, DateUtils.LENGTH_SHORTEST));
    }

    private void setLocales(LocaleList locales) {
        final Resources systemResources = Resources.getSystem();
        final Configuration config = new Configuration(systemResources.getConfiguration());
        config.setLocales(locales);
        // This is not very safe to call, but since DateUtils.formatDuration() is a static method
        // (it gets its format strings from the system resources), we can't pass a modified Context
        // to it.
        systemResources.updateConfiguration(config, null);
    }

}
