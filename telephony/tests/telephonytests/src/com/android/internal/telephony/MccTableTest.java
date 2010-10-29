/*
 * Copyright (C) 2006 The Android Open Source Project
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

package com.android.internal.telephony;

import com.android.internal.telephony.MccTable;

import android.test.AndroidTestCase;
import android.test.suitebuilder.annotation.SmallTest;

import android.util.Log;

public class MccTableTest extends AndroidTestCase {
    private final static String LOG_TAG = "GSM";

    @SmallTest
    public void testTimeZone() throws Exception {
        assertEquals(MccTable.defaultTimeZoneForMcc(208), "ECT");
        assertEquals(MccTable.defaultTimeZoneForMcc(232), "Europe/Vienna");
        assertEquals(MccTable.defaultTimeZoneForMcc(655), "Africa/Johannesburg");
        assertEquals(MccTable.defaultTimeZoneForMcc(440), "Asia/Tokyo");
        assertEquals(MccTable.defaultTimeZoneForMcc(441), "Asia/Tokyo");
        assertEquals(MccTable.defaultTimeZoneForMcc(525), "Asia/Singapore");
        assertEquals(MccTable.defaultTimeZoneForMcc(240), null);  // tz not defined, hence default
        assertEquals(MccTable.defaultTimeZoneForMcc(0), null);    // mcc not defined, hence default
        assertEquals(MccTable.defaultTimeZoneForMcc(2000), null); // mcc not defined, hence default
    }

    @SmallTest
    public void testCountryCode() throws Exception {
        assertEquals(MccTable.countryCodeForMcc(270), "lu");
        assertEquals(MccTable.countryCodeForMcc(202), "gr");
        assertEquals(MccTable.countryCodeForMcc(750), "fk");
        assertEquals(MccTable.countryCodeForMcc(646), "mg");
        assertEquals(MccTable.countryCodeForMcc(314), "us");
        assertEquals(MccTable.countryCodeForMcc(300), "");  // mcc not defined, hence default
        assertEquals(MccTable.countryCodeForMcc(0), "");    // mcc not defined, hence default
        assertEquals(MccTable.countryCodeForMcc(2000), ""); // mcc not defined, hence default
    }

    @SmallTest
    public void testLang() throws Exception {
        assertEquals(MccTable.defaultLanguageForMcc(311), "en");
        assertEquals(MccTable.defaultLanguageForMcc(232), "de");
        assertEquals(MccTable.defaultLanguageForMcc(230), "cs");
        assertEquals(MccTable.defaultLanguageForMcc(204), "nl");
        assertEquals(MccTable.defaultLanguageForMcc(274), null);  // lang not defined, hence default
        assertEquals(MccTable.defaultLanguageForMcc(0), null);    // mcc not defined, hence default
        assertEquals(MccTable.defaultLanguageForMcc(2000), null); // mcc not defined, hence default
    }

    @SmallTest
    public void testSmDigits() throws Exception {
        assertEquals(MccTable.smallestDigitsMccForMnc(312), 3);
        assertEquals(MccTable.smallestDigitsMccForMnc(430), 2);
        assertEquals(MccTable.smallestDigitsMccForMnc(365), 3);
        assertEquals(MccTable.smallestDigitsMccForMnc(536), 2);
        assertEquals(MccTable.smallestDigitsMccForMnc(352), 2);  // sd not defined, hence default
        assertEquals(MccTable.smallestDigitsMccForMnc(0), 2);    // mcc not defined, hence default
        assertEquals(MccTable.smallestDigitsMccForMnc(2000), 2); // mcc not defined, hence default
    }
}
