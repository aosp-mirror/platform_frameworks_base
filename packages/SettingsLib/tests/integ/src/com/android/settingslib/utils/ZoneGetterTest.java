/*
 * Copyright (C) 2016 The Android Open Source Project
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
 * limitations under the License
 */
package com.android.settingslib.utils;

import android.content.Context;
import android.support.test.InstrumentationRegistry;
import android.support.test.runner.AndroidJUnit4;
import android.support.test.filters.SmallTest;
import android.text.Spanned;
import android.text.style.TtsSpan;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.*;
import com.android.settingslib.datetime.ZoneGetter;

import static junit.framework.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class ZoneGetterTest {
    private static final String TIME_ZONE_LONDON_ID = "Europe/London";
    private static final String TIME_ZONE_LA_ID = "America/Los_Angeles";
    private static final String TIME_ZONE_ALGIERS_ID = "Africa/Algiers";
    private static final String TIME_ZONE_CEUTA_ID = "Africa/Ceuta";
    private Locale mLocaleEnUs;
    private Calendar mCalendar;

    @Before
    public void setUp() {
        mLocaleEnUs = new Locale("en", "us");
        Locale.setDefault(mLocaleEnUs);
        mCalendar = new GregorianCalendar(2016, 9, 1);
    }

    @Test
    public void getTimeZoneOffsetAndName_setLondon_returnBritishSummerTime() {
        // Check it will ends with 'British Summer Time', not 'London' or sth else
        testTimeZoneOffsetAndNameInner(TIME_ZONE_LONDON_ID, "British Summer Time");
    }

    @Test
    public void getTimeZoneOffsetAndName_setLosAngeles_returnPacificDaylightTime() {
        // Check it will ends with 'Pacific Daylight Time', not 'Los_Angeles'
        testTimeZoneOffsetAndNameInner(TIME_ZONE_LA_ID, "Pacific Daylight Time");
    }

    @Test
    public void getTimeZoneOffsetAndName_setAlgiers_returnCentralEuropeanStandardTime() {
        testTimeZoneOffsetAndNameInner(TIME_ZONE_ALGIERS_ID, "Central European Standard Time");
    }

    @Test
    public void getTimeZoneOffsetAndName_setCeuta_returnCentralEuropeanSummerTime() {
        testTimeZoneOffsetAndNameInner(TIME_ZONE_CEUTA_ID, "Central European Summer Time");
    }

    @Test
    public void getZonesList_checkTypes() {
        final List<Map<String, Object>> zones =
                ZoneGetter.getZonesList(InstrumentationRegistry.getContext());
        for (Map<String, Object> zone : zones) {
            assertTrue(zone.get(ZoneGetter.KEY_DISPLAYNAME) instanceof String);
            assertTrue(zone.get(ZoneGetter.KEY_DISPLAY_LABEL) instanceof CharSequence);
            assertTrue(zone.get(ZoneGetter.KEY_OFFSET) instanceof Integer);
            assertTrue(zone.get(ZoneGetter.KEY_OFFSET_LABEL) instanceof CharSequence);
            assertTrue(zone.get(ZoneGetter.KEY_ID) instanceof String);
            assertTrue(zone.get(ZoneGetter.KEY_GMT) instanceof String);
        }
    }

    @Test
    public void getTimeZoneOffsetAndName_withTtsSpan() {
        final Context context = InstrumentationRegistry.getContext();
        final TimeZone timeZone = TimeZone.getTimeZone(TIME_ZONE_LA_ID);

        CharSequence timeZoneString = ZoneGetter.getTimeZoneOffsetAndName(context, timeZone,
                mCalendar.getTime());
        assertTrue("Time zone string should be spanned", timeZoneString instanceof Spanned);
        assertTrue("Time zone display name should have TTS spans",
                ((Spanned) timeZoneString).getSpans(
                    0, timeZoneString.length(), TtsSpan.class).length > 0);
    }

    private void testTimeZoneOffsetAndNameInner(String timeZoneId, String expectedName) {
        final Context context = InstrumentationRegistry.getContext();
        final TimeZone timeZone = TimeZone.getTimeZone(timeZoneId);

        CharSequence timeZoneString = ZoneGetter.getTimeZoneOffsetAndName(context, timeZone,
                mCalendar.getTime());

        assertTrue(timeZoneString.toString().endsWith(expectedName));
    }

}
