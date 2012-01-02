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

package com.android.internal.telephony;

import android.test.AndroidTestCase;
import android.test.suitebuilder.annotation.SmallTest;

import static com.android.internal.telephony.SmsUsageMonitor.CATEGORY_FREE_SHORT_CODE;
import static com.android.internal.telephony.SmsUsageMonitor.CATEGORY_NOT_SHORT_CODE;
import static com.android.internal.telephony.SmsUsageMonitor.CATEGORY_POSSIBLE_PREMIUM_SHORT_CODE;
import static com.android.internal.telephony.SmsUsageMonitor.CATEGORY_PREMIUM_SHORT_CODE;
import static com.android.internal.telephony.SmsUsageMonitor.CATEGORY_STANDARD_SHORT_CODE;

/**
 * Test cases for SMS short code pattern matching in SmsUsageMonitor.
 */
public class SmsUsageMonitorShortCodeTest extends AndroidTestCase {

    private static final class ShortCodeTest {
        final String countryIso;
        final String address;
        final int category;

        ShortCodeTest(String countryIso, String destAddress, int category) {
            this.countryIso = countryIso;
            this.address = destAddress;
            this.category = category;
        }
    }

    /**
     * List of short code test cases.
     */
    private static final ShortCodeTest[] sShortCodeTests = new ShortCodeTest[] {
            new ShortCodeTest("al", "112", CATEGORY_NOT_SHORT_CODE),
            new ShortCodeTest("al", "4321", CATEGORY_NOT_SHORT_CODE),
            new ShortCodeTest("al", "54321", CATEGORY_POSSIBLE_PREMIUM_SHORT_CODE),
            new ShortCodeTest("al", "15191", CATEGORY_PREMIUM_SHORT_CODE),
            new ShortCodeTest("al", "55500", CATEGORY_PREMIUM_SHORT_CODE),
            new ShortCodeTest("al", "55600", CATEGORY_PREMIUM_SHORT_CODE),
            new ShortCodeTest("al", "654321", CATEGORY_NOT_SHORT_CODE),

            new ShortCodeTest("am", "112", CATEGORY_NOT_SHORT_CODE),
            new ShortCodeTest("am", "101", CATEGORY_FREE_SHORT_CODE),
            new ShortCodeTest("am", "102", CATEGORY_FREE_SHORT_CODE),
            new ShortCodeTest("am", "103", CATEGORY_FREE_SHORT_CODE),
            new ShortCodeTest("am", "222", CATEGORY_POSSIBLE_PREMIUM_SHORT_CODE),
            new ShortCodeTest("am", "1111", CATEGORY_POSSIBLE_PREMIUM_SHORT_CODE),
            new ShortCodeTest("am", "9999", CATEGORY_POSSIBLE_PREMIUM_SHORT_CODE),
            new ShortCodeTest("am", "1121", CATEGORY_PREMIUM_SHORT_CODE),
            new ShortCodeTest("am", "1141", CATEGORY_PREMIUM_SHORT_CODE),
            new ShortCodeTest("am", "1161", CATEGORY_PREMIUM_SHORT_CODE),
            new ShortCodeTest("am", "3024", CATEGORY_PREMIUM_SHORT_CODE),

            new ShortCodeTest("at", "112", CATEGORY_NOT_SHORT_CODE),
            new ShortCodeTest("at", "116117", CATEGORY_FREE_SHORT_CODE),
            new ShortCodeTest("at", "0901234", CATEGORY_PREMIUM_SHORT_CODE),
            new ShortCodeTest("at", "0900666266", CATEGORY_PREMIUM_SHORT_CODE),

            new ShortCodeTest("au", "112", CATEGORY_NOT_SHORT_CODE),
            new ShortCodeTest("au", "180000", CATEGORY_NOT_SHORT_CODE),
            new ShortCodeTest("au", "190000", CATEGORY_POSSIBLE_PREMIUM_SHORT_CODE),
            new ShortCodeTest("au", "1900000", CATEGORY_NOT_SHORT_CODE),
            new ShortCodeTest("au", "19000000", CATEGORY_POSSIBLE_PREMIUM_SHORT_CODE),
            new ShortCodeTest("au", "19998882", CATEGORY_PREMIUM_SHORT_CODE),

            new ShortCodeTest("az", "112", CATEGORY_NOT_SHORT_CODE),
            new ShortCodeTest("az", "1234", CATEGORY_POSSIBLE_PREMIUM_SHORT_CODE),
            new ShortCodeTest("az", "12345", CATEGORY_POSSIBLE_PREMIUM_SHORT_CODE),
            new ShortCodeTest("az", "87744", CATEGORY_PREMIUM_SHORT_CODE),
            new ShortCodeTest("az", "3301", CATEGORY_PREMIUM_SHORT_CODE),
            new ShortCodeTest("az", "3302", CATEGORY_PREMIUM_SHORT_CODE),
            new ShortCodeTest("az", "9012", CATEGORY_PREMIUM_SHORT_CODE),
            new ShortCodeTest("az", "9014", CATEGORY_PREMIUM_SHORT_CODE),
            new ShortCodeTest("az", "9394", CATEGORY_PREMIUM_SHORT_CODE),
            new ShortCodeTest("az", "87744", CATEGORY_PREMIUM_SHORT_CODE),
            new ShortCodeTest("az", "93101", CATEGORY_PREMIUM_SHORT_CODE),
            new ShortCodeTest("az", "123456", CATEGORY_NOT_SHORT_CODE),

            new ShortCodeTest("be", "112", CATEGORY_NOT_SHORT_CODE),
            new ShortCodeTest("be", "116117", CATEGORY_FREE_SHORT_CODE),
            new ShortCodeTest("be", "567890", CATEGORY_NOT_SHORT_CODE),
            new ShortCodeTest("be", "8000", CATEGORY_FREE_SHORT_CODE),
            new ShortCodeTest("be", "6566", CATEGORY_PREMIUM_SHORT_CODE),
            new ShortCodeTest("be", "7777", CATEGORY_PREMIUM_SHORT_CODE),

            new ShortCodeTest("bg", "112", CATEGORY_NOT_SHORT_CODE),
            new ShortCodeTest("bg", "116117", CATEGORY_FREE_SHORT_CODE),
            new ShortCodeTest("bg", "1234", CATEGORY_POSSIBLE_PREMIUM_SHORT_CODE),
            new ShortCodeTest("bg", "12345", CATEGORY_POSSIBLE_PREMIUM_SHORT_CODE),
            new ShortCodeTest("bg", "1816", CATEGORY_PREMIUM_SHORT_CODE),
            new ShortCodeTest("bg", "1915", CATEGORY_PREMIUM_SHORT_CODE),
            new ShortCodeTest("bg", "1916", CATEGORY_PREMIUM_SHORT_CODE),
            new ShortCodeTest("bg", "1935", CATEGORY_PREMIUM_SHORT_CODE),
            new ShortCodeTest("bg", "18423", CATEGORY_PREMIUM_SHORT_CODE),

            new ShortCodeTest("by", "112", CATEGORY_NOT_SHORT_CODE),
            new ShortCodeTest("by", "1234", CATEGORY_POSSIBLE_PREMIUM_SHORT_CODE),
            new ShortCodeTest("by", "3336", CATEGORY_PREMIUM_SHORT_CODE),
            new ShortCodeTest("by", "5013", CATEGORY_PREMIUM_SHORT_CODE),
            new ShortCodeTest("by", "5014", CATEGORY_PREMIUM_SHORT_CODE),
            new ShortCodeTest("by", "7781", CATEGORY_PREMIUM_SHORT_CODE),

            new ShortCodeTest("ca", "911", CATEGORY_NOT_SHORT_CODE),
            new ShortCodeTest("ca", "+18005551234", CATEGORY_NOT_SHORT_CODE),
            new ShortCodeTest("ca", "8005551234", CATEGORY_NOT_SHORT_CODE),
            new ShortCodeTest("ca", "20000", CATEGORY_POSSIBLE_PREMIUM_SHORT_CODE),
            new ShortCodeTest("ca", "200000", CATEGORY_POSSIBLE_PREMIUM_SHORT_CODE),
            new ShortCodeTest("ca", "2000000", CATEGORY_NOT_SHORT_CODE),
            new ShortCodeTest("ca", "60999", CATEGORY_PREMIUM_SHORT_CODE),
            new ShortCodeTest("ca", "88188", CATEGORY_PREMIUM_SHORT_CODE),

            new ShortCodeTest("ch", "112", CATEGORY_NOT_SHORT_CODE),
            new ShortCodeTest("ch", "123", CATEGORY_NOT_SHORT_CODE),
            new ShortCodeTest("ch", "234", CATEGORY_POSSIBLE_PREMIUM_SHORT_CODE),
            new ShortCodeTest("ch", "3456", CATEGORY_POSSIBLE_PREMIUM_SHORT_CODE),
            new ShortCodeTest("ch", "98765", CATEGORY_POSSIBLE_PREMIUM_SHORT_CODE),
            new ShortCodeTest("ch", "543", CATEGORY_PREMIUM_SHORT_CODE),
            new ShortCodeTest("ch", "83111", CATEGORY_PREMIUM_SHORT_CODE),
            new ShortCodeTest("ch", "234567", CATEGORY_NOT_SHORT_CODE),
            new ShortCodeTest("ch", "87654321", CATEGORY_NOT_SHORT_CODE),

            new ShortCodeTest("cn", "120", CATEGORY_NOT_SHORT_CODE),
            new ShortCodeTest("cn", "1062503000", CATEGORY_NOT_SHORT_CODE),
            new ShortCodeTest("cn", "1065123456", CATEGORY_FREE_SHORT_CODE),
            new ShortCodeTest("cn", "1066335588", CATEGORY_PREMIUM_SHORT_CODE),

            new ShortCodeTest("cy", "112", CATEGORY_NOT_SHORT_CODE),
            new ShortCodeTest("cy", "116117", CATEGORY_FREE_SHORT_CODE),
            new ShortCodeTest("cy", "4321", CATEGORY_POSSIBLE_PREMIUM_SHORT_CODE),
            new ShortCodeTest("cy", "54321", CATEGORY_POSSIBLE_PREMIUM_SHORT_CODE),
            new ShortCodeTest("cy", "654321", CATEGORY_POSSIBLE_PREMIUM_SHORT_CODE),
            new ShortCodeTest("cy", "7510", CATEGORY_PREMIUM_SHORT_CODE),
            new ShortCodeTest("cy", "987654321", CATEGORY_NOT_SHORT_CODE),

            new ShortCodeTest("cz", "112", CATEGORY_NOT_SHORT_CODE),
            new ShortCodeTest("cz", "116117", CATEGORY_FREE_SHORT_CODE),
            new ShortCodeTest("cz", "9090150", CATEGORY_PREMIUM_SHORT_CODE),
            new ShortCodeTest("cz", "90901599", CATEGORY_PREMIUM_SHORT_CODE),
            new ShortCodeTest("cz", "987654321", CATEGORY_NOT_SHORT_CODE),

            new ShortCodeTest("de", "112", CATEGORY_NOT_SHORT_CODE),
            new ShortCodeTest("de", "116117", CATEGORY_FREE_SHORT_CODE),
            new ShortCodeTest("de", "1234", CATEGORY_POSSIBLE_PREMIUM_SHORT_CODE),
            new ShortCodeTest("de", "12345", CATEGORY_POSSIBLE_PREMIUM_SHORT_CODE),
            new ShortCodeTest("de", "8888", CATEGORY_PREMIUM_SHORT_CODE),
            new ShortCodeTest("de", "11111", CATEGORY_PREMIUM_SHORT_CODE),
            new ShortCodeTest("de", "11886", CATEGORY_PREMIUM_SHORT_CODE),
            new ShortCodeTest("de", "22022", CATEGORY_PREMIUM_SHORT_CODE),
            new ShortCodeTest("de", "23300", CATEGORY_PREMIUM_SHORT_CODE),
            new ShortCodeTest("de", "3434", CATEGORY_PREMIUM_SHORT_CODE),
            new ShortCodeTest("de", "34567", CATEGORY_PREMIUM_SHORT_CODE),
            new ShortCodeTest("de", "41414", CATEGORY_PREMIUM_SHORT_CODE),
            new ShortCodeTest("de", "55655", CATEGORY_PREMIUM_SHORT_CODE),
            new ShortCodeTest("de", "66766", CATEGORY_PREMIUM_SHORT_CODE),
            new ShortCodeTest("de", "66777", CATEGORY_PREMIUM_SHORT_CODE),
            new ShortCodeTest("de", "77677", CATEGORY_PREMIUM_SHORT_CODE),
            new ShortCodeTest("de", "80888", CATEGORY_PREMIUM_SHORT_CODE),
            new ShortCodeTest("de", "1232286", CATEGORY_PREMIUM_SHORT_CODE),
            new ShortCodeTest("de", "987654321", CATEGORY_NOT_SHORT_CODE),

            new ShortCodeTest("dk", "112", CATEGORY_NOT_SHORT_CODE),
            new ShortCodeTest("dk", "116117", CATEGORY_FREE_SHORT_CODE),
            new ShortCodeTest("dk", "1259", CATEGORY_PREMIUM_SHORT_CODE),
            new ShortCodeTest("dk", "16123", CATEGORY_POSSIBLE_PREMIUM_SHORT_CODE),
            new ShortCodeTest("dk", "987654321", CATEGORY_NOT_SHORT_CODE),

            new ShortCodeTest("ee", "112", CATEGORY_NOT_SHORT_CODE),
            new ShortCodeTest("ee", "116117", CATEGORY_FREE_SHORT_CODE),
            new ShortCodeTest("ee", "123", CATEGORY_POSSIBLE_PREMIUM_SHORT_CODE),
            new ShortCodeTest("ee", "1259", CATEGORY_POSSIBLE_PREMIUM_SHORT_CODE),
            new ShortCodeTest("ee", "15330", CATEGORY_PREMIUM_SHORT_CODE),
            new ShortCodeTest("ee", "17999", CATEGORY_POSSIBLE_PREMIUM_SHORT_CODE),
            new ShortCodeTest("ee", "17010", CATEGORY_PREMIUM_SHORT_CODE),
            new ShortCodeTest("ee", "17013", CATEGORY_PREMIUM_SHORT_CODE),
            new ShortCodeTest("ee", "9034567", CATEGORY_PREMIUM_SHORT_CODE),
            new ShortCodeTest("ee", "34567890", CATEGORY_NOT_SHORT_CODE),

            new ShortCodeTest("es", "112", CATEGORY_NOT_SHORT_CODE),
            new ShortCodeTest("es", "116117", CATEGORY_FREE_SHORT_CODE),
            new ShortCodeTest("es", "25165", CATEGORY_PREMIUM_SHORT_CODE),
            new ShortCodeTest("es", "27333", CATEGORY_PREMIUM_SHORT_CODE),
            new ShortCodeTest("es", "995399", CATEGORY_PREMIUM_SHORT_CODE),
            new ShortCodeTest("es", "87654321", CATEGORY_NOT_SHORT_CODE),

            new ShortCodeTest("fi", "112", CATEGORY_NOT_SHORT_CODE),
            new ShortCodeTest("fi", "116117", CATEGORY_FREE_SHORT_CODE),
            new ShortCodeTest("fi", "12345", CATEGORY_POSSIBLE_PREMIUM_SHORT_CODE),
            new ShortCodeTest("fi", "123456", CATEGORY_POSSIBLE_PREMIUM_SHORT_CODE),
            new ShortCodeTest("fi", "17159", CATEGORY_PREMIUM_SHORT_CODE),
            new ShortCodeTest("fi", "17163", CATEGORY_PREMIUM_SHORT_CODE),
            new ShortCodeTest("fi", "0600123", CATEGORY_PREMIUM_SHORT_CODE),
            new ShortCodeTest("fi", "070012345", CATEGORY_PREMIUM_SHORT_CODE),
            new ShortCodeTest("fi", "987654321", CATEGORY_NOT_SHORT_CODE),

            new ShortCodeTest("fr", "112", CATEGORY_NOT_SHORT_CODE),
            new ShortCodeTest("fr", "116117", CATEGORY_FREE_SHORT_CODE),
            new ShortCodeTest("fr", "34567", CATEGORY_FREE_SHORT_CODE),
            new ShortCodeTest("fr", "45678", CATEGORY_PREMIUM_SHORT_CODE),
            new ShortCodeTest("fr", "81185", CATEGORY_PREMIUM_SHORT_CODE),
            new ShortCodeTest("fr", "87654321", CATEGORY_NOT_SHORT_CODE),

            new ShortCodeTest("gb", "112", CATEGORY_NOT_SHORT_CODE),
            new ShortCodeTest("gb", "999", CATEGORY_NOT_SHORT_CODE),
            new ShortCodeTest("gb", "116117", CATEGORY_FREE_SHORT_CODE),
            new ShortCodeTest("gb", "4567", CATEGORY_POSSIBLE_PREMIUM_SHORT_CODE),
            new ShortCodeTest("gb", "45678", CATEGORY_POSSIBLE_PREMIUM_SHORT_CODE),
            new ShortCodeTest("gb", "56789", CATEGORY_PREMIUM_SHORT_CODE),
            new ShortCodeTest("gb", "79067", CATEGORY_PREMIUM_SHORT_CODE),
            new ShortCodeTest("gb", "80079", CATEGORY_PREMIUM_SHORT_CODE),
            new ShortCodeTest("gb", "654321", CATEGORY_POSSIBLE_PREMIUM_SHORT_CODE),
            new ShortCodeTest("gb", "7654321", CATEGORY_NOT_SHORT_CODE),

            new ShortCodeTest("ge", "112", CATEGORY_NOT_SHORT_CODE),
            new ShortCodeTest("ge", "8765", CATEGORY_POSSIBLE_PREMIUM_SHORT_CODE),
            new ShortCodeTest("ge", "2345", CATEGORY_POSSIBLE_PREMIUM_SHORT_CODE),
            new ShortCodeTest("ge", "8012", CATEGORY_PREMIUM_SHORT_CODE),
            new ShortCodeTest("ge", "8013", CATEGORY_PREMIUM_SHORT_CODE),
            new ShortCodeTest("ge", "8014", CATEGORY_PREMIUM_SHORT_CODE),
            new ShortCodeTest("ge", "8889", CATEGORY_PREMIUM_SHORT_CODE),

            new ShortCodeTest("gr", "112", CATEGORY_NOT_SHORT_CODE),
            new ShortCodeTest("gr", "116117", CATEGORY_FREE_SHORT_CODE),
            new ShortCodeTest("gr", "54321", CATEGORY_PREMIUM_SHORT_CODE),
            new ShortCodeTest("gr", "19567", CATEGORY_PREMIUM_SHORT_CODE),
            new ShortCodeTest("gr", "19678", CATEGORY_POSSIBLE_PREMIUM_SHORT_CODE),
            new ShortCodeTest("gr", "87654321", CATEGORY_NOT_SHORT_CODE),

            new ShortCodeTest("hu", "112", CATEGORY_NOT_SHORT_CODE),
            new ShortCodeTest("hu", "116117", CATEGORY_FREE_SHORT_CODE),
            new ShortCodeTest("hu", "012", CATEGORY_NOT_SHORT_CODE),
            new ShortCodeTest("hu", "0123", CATEGORY_POSSIBLE_PREMIUM_SHORT_CODE),
            new ShortCodeTest("hu", "1234", CATEGORY_POSSIBLE_PREMIUM_SHORT_CODE),
            new ShortCodeTest("hu", "1784", CATEGORY_PREMIUM_SHORT_CODE),
            new ShortCodeTest("hu", "2345", CATEGORY_NOT_SHORT_CODE),
            new ShortCodeTest("hu", "01234", CATEGORY_NOT_SHORT_CODE),
            new ShortCodeTest("hu", "012345678", CATEGORY_NOT_SHORT_CODE),
            new ShortCodeTest("hu", "0123456789", CATEGORY_POSSIBLE_PREMIUM_SHORT_CODE),
            new ShortCodeTest("hu", "1234567890", CATEGORY_POSSIBLE_PREMIUM_SHORT_CODE),
            new ShortCodeTest("hu", "0691227910", CATEGORY_PREMIUM_SHORT_CODE),
            new ShortCodeTest("hu", "2345678901", CATEGORY_NOT_SHORT_CODE),
            new ShortCodeTest("hu", "01234567890", CATEGORY_NOT_SHORT_CODE),

            new ShortCodeTest("ie", "112", CATEGORY_NOT_SHORT_CODE),
            new ShortCodeTest("ie", "116117", CATEGORY_FREE_SHORT_CODE),
            new ShortCodeTest("ie", "50123", CATEGORY_FREE_SHORT_CODE),
            new ShortCodeTest("ie", "51234", CATEGORY_STANDARD_SHORT_CODE),
            new ShortCodeTest("ie", "52345", CATEGORY_STANDARD_SHORT_CODE),
            new ShortCodeTest("ie", "57890", CATEGORY_PREMIUM_SHORT_CODE),
            new ShortCodeTest("ie", "67890", CATEGORY_POSSIBLE_PREMIUM_SHORT_CODE),
            new ShortCodeTest("ie", "87654321", CATEGORY_NOT_SHORT_CODE),

            new ShortCodeTest("il", "112", CATEGORY_NOT_SHORT_CODE),
            new ShortCodeTest("il", "5432", CATEGORY_POSSIBLE_PREMIUM_SHORT_CODE),
            new ShortCodeTest("il", "4422", CATEGORY_PREMIUM_SHORT_CODE),
            new ShortCodeTest("il", "4545", CATEGORY_PREMIUM_SHORT_CODE),
            new ShortCodeTest("il", "98765", CATEGORY_NOT_SHORT_CODE),

            new ShortCodeTest("it", "112", CATEGORY_NOT_SHORT_CODE),
            new ShortCodeTest("it", "116117", CATEGORY_FREE_SHORT_CODE),
            new ShortCodeTest("it", "4567", CATEGORY_NOT_SHORT_CODE),
            new ShortCodeTest("it", "48000", CATEGORY_PREMIUM_SHORT_CODE),
            new ShortCodeTest("it", "45678", CATEGORY_PREMIUM_SHORT_CODE),
            new ShortCodeTest("it", "56789", CATEGORY_POSSIBLE_PREMIUM_SHORT_CODE),
            new ShortCodeTest("it", "456789", CATEGORY_NOT_SHORT_CODE),

            new ShortCodeTest("kg", "112", CATEGORY_NOT_SHORT_CODE),
            new ShortCodeTest("kg", "5432", CATEGORY_POSSIBLE_PREMIUM_SHORT_CODE),
            new ShortCodeTest("kg", "4152", CATEGORY_PREMIUM_SHORT_CODE),
            new ShortCodeTest("kg", "4157", CATEGORY_PREMIUM_SHORT_CODE),
            new ShortCodeTest("kg", "4449", CATEGORY_PREMIUM_SHORT_CODE),
            new ShortCodeTest("kg", "98765", CATEGORY_NOT_SHORT_CODE),

            new ShortCodeTest("kz", "112", CATEGORY_NOT_SHORT_CODE),
            new ShortCodeTest("kz", "5432", CATEGORY_POSSIBLE_PREMIUM_SHORT_CODE),
            new ShortCodeTest("kz", "9194", CATEGORY_PREMIUM_SHORT_CODE),
            new ShortCodeTest("kz", "7790", CATEGORY_PREMIUM_SHORT_CODE),
            new ShortCodeTest("kz", "98765", CATEGORY_NOT_SHORT_CODE),

            new ShortCodeTest("lt", "112", CATEGORY_NOT_SHORT_CODE),
            new ShortCodeTest("lt", "116117", CATEGORY_FREE_SHORT_CODE),
            new ShortCodeTest("lt", "123", CATEGORY_POSSIBLE_PREMIUM_SHORT_CODE),
            new ShortCodeTest("lt", "1234", CATEGORY_POSSIBLE_PREMIUM_SHORT_CODE),
            new ShortCodeTest("lt", "1381", CATEGORY_PREMIUM_SHORT_CODE),
            new ShortCodeTest("lt", "1394", CATEGORY_PREMIUM_SHORT_CODE),
            new ShortCodeTest("lt", "1645", CATEGORY_PREMIUM_SHORT_CODE),
            new ShortCodeTest("lt", "12345", CATEGORY_POSSIBLE_PREMIUM_SHORT_CODE),
            new ShortCodeTest("lt", "123456", CATEGORY_NOT_SHORT_CODE),

            new ShortCodeTest("lu", "112", CATEGORY_NOT_SHORT_CODE),
            new ShortCodeTest("lu", "116117", CATEGORY_FREE_SHORT_CODE),
            new ShortCodeTest("lu", "1234", CATEGORY_NOT_SHORT_CODE),
            new ShortCodeTest("lu", "12345", CATEGORY_NOT_SHORT_CODE),
            new ShortCodeTest("lu", "64747", CATEGORY_PREMIUM_SHORT_CODE),
            new ShortCodeTest("lu", "678901", CATEGORY_NOT_SHORT_CODE),

            new ShortCodeTest("lv", "112", CATEGORY_NOT_SHORT_CODE),
            new ShortCodeTest("lv", "116117", CATEGORY_FREE_SHORT_CODE),
            new ShortCodeTest("lv", "5432", CATEGORY_POSSIBLE_PREMIUM_SHORT_CODE),
            new ShortCodeTest("lv", "1819", CATEGORY_PREMIUM_SHORT_CODE),
            new ShortCodeTest("lv", "1863", CATEGORY_PREMIUM_SHORT_CODE),
            new ShortCodeTest("lv", "1874", CATEGORY_PREMIUM_SHORT_CODE),
            new ShortCodeTest("lv", "98765", CATEGORY_NOT_SHORT_CODE),

            new ShortCodeTest("mx", "112", CATEGORY_NOT_SHORT_CODE),
            new ShortCodeTest("mx", "2345", CATEGORY_POSSIBLE_PREMIUM_SHORT_CODE),
            new ShortCodeTest("mx", "7766", CATEGORY_PREMIUM_SHORT_CODE),
            new ShortCodeTest("mx", "23456", CATEGORY_POSSIBLE_PREMIUM_SHORT_CODE),
            new ShortCodeTest("mx", "53035", CATEGORY_PREMIUM_SHORT_CODE),

            new ShortCodeTest("my", "112", CATEGORY_NOT_SHORT_CODE),
            new ShortCodeTest("my", "1234", CATEGORY_NOT_SHORT_CODE),
            new ShortCodeTest("my", "23456", CATEGORY_POSSIBLE_PREMIUM_SHORT_CODE),
            new ShortCodeTest("my", "32298", CATEGORY_PREMIUM_SHORT_CODE),
            new ShortCodeTest("my", "33776", CATEGORY_PREMIUM_SHORT_CODE),
            new ShortCodeTest("my", "345678", CATEGORY_NOT_SHORT_CODE),

            new ShortCodeTest("nl", "112", CATEGORY_NOT_SHORT_CODE),
            new ShortCodeTest("nl", "116117", CATEGORY_FREE_SHORT_CODE),
            new ShortCodeTest("nl", "1234", CATEGORY_POSSIBLE_PREMIUM_SHORT_CODE),
            new ShortCodeTest("nl", "4466", CATEGORY_PREMIUM_SHORT_CODE),
            new ShortCodeTest("nl", "5040", CATEGORY_PREMIUM_SHORT_CODE),
            new ShortCodeTest("nl", "23456", CATEGORY_NOT_SHORT_CODE),

            new ShortCodeTest("no", "112", CATEGORY_NOT_SHORT_CODE),
            new ShortCodeTest("no", "1234", CATEGORY_POSSIBLE_PREMIUM_SHORT_CODE),
            new ShortCodeTest("no", "2201", CATEGORY_PREMIUM_SHORT_CODE),
            new ShortCodeTest("no", "2226", CATEGORY_PREMIUM_SHORT_CODE),
            new ShortCodeTest("no", "2227", CATEGORY_PREMIUM_SHORT_CODE),
            new ShortCodeTest("no", "23456", CATEGORY_POSSIBLE_PREMIUM_SHORT_CODE),
            new ShortCodeTest("no", "234567", CATEGORY_NOT_SHORT_CODE),

            new ShortCodeTest("nz", "112", CATEGORY_NOT_SHORT_CODE),
            new ShortCodeTest("nz", "123", CATEGORY_POSSIBLE_PREMIUM_SHORT_CODE),
            new ShortCodeTest("nz", "2345", CATEGORY_POSSIBLE_PREMIUM_SHORT_CODE),
            new ShortCodeTest("nz", "3903", CATEGORY_PREMIUM_SHORT_CODE),
            new ShortCodeTest("nz", "8995", CATEGORY_PREMIUM_SHORT_CODE),
            new ShortCodeTest("nz", "23456", CATEGORY_NOT_SHORT_CODE),

            new ShortCodeTest("pl", "112", CATEGORY_NOT_SHORT_CODE),
            new ShortCodeTest("pl", "116117", CATEGORY_FREE_SHORT_CODE),
            new ShortCodeTest("pl", "7890", CATEGORY_POSSIBLE_PREMIUM_SHORT_CODE),
            new ShortCodeTest("pl", "34567", CATEGORY_POSSIBLE_PREMIUM_SHORT_CODE),
            new ShortCodeTest("pl", "7910", CATEGORY_PREMIUM_SHORT_CODE),
            new ShortCodeTest("pl", "74240", CATEGORY_PREMIUM_SHORT_CODE),
            new ShortCodeTest("pl", "79866", CATEGORY_PREMIUM_SHORT_CODE),
            new ShortCodeTest("pl", "92525", CATEGORY_PREMIUM_SHORT_CODE),
            new ShortCodeTest("pl", "87654321", CATEGORY_NOT_SHORT_CODE),

            new ShortCodeTest("pt", "112", CATEGORY_NOT_SHORT_CODE),
            new ShortCodeTest("pt", "116117", CATEGORY_FREE_SHORT_CODE),
            new ShortCodeTest("pt", "61000", CATEGORY_PREMIUM_SHORT_CODE),
            new ShortCodeTest("pt", "62345", CATEGORY_PREMIUM_SHORT_CODE),
            new ShortCodeTest("pt", "68304", CATEGORY_PREMIUM_SHORT_CODE),
            new ShortCodeTest("pt", "69876", CATEGORY_PREMIUM_SHORT_CODE),
            new ShortCodeTest("pt", "87654321", CATEGORY_NOT_SHORT_CODE),

            new ShortCodeTest("ro", "112", CATEGORY_NOT_SHORT_CODE),
            new ShortCodeTest("ro", "116117", CATEGORY_FREE_SHORT_CODE),
            new ShortCodeTest("ro", "1234", CATEGORY_POSSIBLE_PREMIUM_SHORT_CODE),
            new ShortCodeTest("ro", "1263", CATEGORY_PREMIUM_SHORT_CODE),
            new ShortCodeTest("ro", "1288", CATEGORY_PREMIUM_SHORT_CODE),
            new ShortCodeTest("ro", "1314", CATEGORY_PREMIUM_SHORT_CODE),
            new ShortCodeTest("ro", "1380", CATEGORY_PREMIUM_SHORT_CODE),
            new ShortCodeTest("ro", "7890", CATEGORY_POSSIBLE_PREMIUM_SHORT_CODE),
            new ShortCodeTest("ro", "12345", CATEGORY_NOT_SHORT_CODE),

            new ShortCodeTest("ru", "112", CATEGORY_NOT_SHORT_CODE),
            new ShortCodeTest("ru", "5432", CATEGORY_POSSIBLE_PREMIUM_SHORT_CODE),
            new ShortCodeTest("ru", "1161", CATEGORY_PREMIUM_SHORT_CODE),
            new ShortCodeTest("ru", "2097", CATEGORY_PREMIUM_SHORT_CODE),
            new ShortCodeTest("ru", "3933", CATEGORY_PREMIUM_SHORT_CODE),
            new ShortCodeTest("ru", "7781", CATEGORY_PREMIUM_SHORT_CODE),
            new ShortCodeTest("ru", "98765", CATEGORY_NOT_SHORT_CODE),

            new ShortCodeTest("se", "112", CATEGORY_NOT_SHORT_CODE),
            new ShortCodeTest("se", "116117", CATEGORY_FREE_SHORT_CODE),
            new ShortCodeTest("se", "1234", CATEGORY_NOT_SHORT_CODE),
            new ShortCodeTest("se", "72345", CATEGORY_PREMIUM_SHORT_CODE),
            new ShortCodeTest("se", "72999", CATEGORY_PREMIUM_SHORT_CODE),
            new ShortCodeTest("se", "123456", CATEGORY_NOT_SHORT_CODE),
            new ShortCodeTest("se", "87654321", CATEGORY_NOT_SHORT_CODE),

            new ShortCodeTest("sg", "112", CATEGORY_NOT_SHORT_CODE),
            new ShortCodeTest("sg", "1234", CATEGORY_NOT_SHORT_CODE),
            new ShortCodeTest("sg", "70000", CATEGORY_POSSIBLE_PREMIUM_SHORT_CODE),
            new ShortCodeTest("sg", "79999", CATEGORY_POSSIBLE_PREMIUM_SHORT_CODE),
            new ShortCodeTest("sg", "73800", CATEGORY_PREMIUM_SHORT_CODE),
            new ShortCodeTest("sg", "74688", CATEGORY_STANDARD_SHORT_CODE),
            new ShortCodeTest("sg", "987654", CATEGORY_NOT_SHORT_CODE),

            new ShortCodeTest("si", "112", CATEGORY_NOT_SHORT_CODE),
            new ShortCodeTest("si", "116117", CATEGORY_FREE_SHORT_CODE),
            new ShortCodeTest("si", "1234", CATEGORY_POSSIBLE_PREMIUM_SHORT_CODE),
            new ShortCodeTest("si", "3838", CATEGORY_PREMIUM_SHORT_CODE),
            new ShortCodeTest("si", "72999", CATEGORY_NOT_SHORT_CODE),

            new ShortCodeTest("sk", "112", CATEGORY_NOT_SHORT_CODE),
            new ShortCodeTest("sk", "116117", CATEGORY_FREE_SHORT_CODE),
            new ShortCodeTest("sk", "1234", CATEGORY_PREMIUM_SHORT_CODE),
            new ShortCodeTest("sk", "6674", CATEGORY_PREMIUM_SHORT_CODE),
            new ShortCodeTest("sk", "7604", CATEGORY_PREMIUM_SHORT_CODE),
            new ShortCodeTest("sk", "72999", CATEGORY_NOT_SHORT_CODE),

            new ShortCodeTest("tj", "112", CATEGORY_NOT_SHORT_CODE),
            new ShortCodeTest("tj", "5432", CATEGORY_POSSIBLE_PREMIUM_SHORT_CODE),
            new ShortCodeTest("tj", "1161", CATEGORY_PREMIUM_SHORT_CODE),
            new ShortCodeTest("tj", "1171", CATEGORY_PREMIUM_SHORT_CODE),
            new ShortCodeTest("tj", "4161", CATEGORY_PREMIUM_SHORT_CODE),
            new ShortCodeTest("tj", "4449", CATEGORY_PREMIUM_SHORT_CODE),
            new ShortCodeTest("tj", "98765", CATEGORY_NOT_SHORT_CODE),

            new ShortCodeTest("ua", "112", CATEGORY_NOT_SHORT_CODE),
            new ShortCodeTest("ua", "5432", CATEGORY_POSSIBLE_PREMIUM_SHORT_CODE),
            new ShortCodeTest("ua", "4448", CATEGORY_PREMIUM_SHORT_CODE),
            new ShortCodeTest("ua", "7094", CATEGORY_PREMIUM_SHORT_CODE),
            new ShortCodeTest("ua", "7540", CATEGORY_PREMIUM_SHORT_CODE),
            new ShortCodeTest("ua", "98765", CATEGORY_NOT_SHORT_CODE),

            new ShortCodeTest("us", "911", CATEGORY_NOT_SHORT_CODE),
            new ShortCodeTest("us", "+18005551234", CATEGORY_NOT_SHORT_CODE),
            new ShortCodeTest("us", "8005551234", CATEGORY_NOT_SHORT_CODE),
            new ShortCodeTest("us", "20000", CATEGORY_POSSIBLE_PREMIUM_SHORT_CODE),
            new ShortCodeTest("us", "200000", CATEGORY_POSSIBLE_PREMIUM_SHORT_CODE),
            new ShortCodeTest("us", "2000000", CATEGORY_NOT_SHORT_CODE),
            new ShortCodeTest("us", "20433", CATEGORY_PREMIUM_SHORT_CODE),
            new ShortCodeTest("us", "21472", CATEGORY_PREMIUM_SHORT_CODE),
            new ShortCodeTest("us", "23333", CATEGORY_PREMIUM_SHORT_CODE),
            new ShortCodeTest("us", "99807", CATEGORY_PREMIUM_SHORT_CODE),

            // generic rules for other countries: 5 digits or less considered potential short code
            new ShortCodeTest("zz", "2000000", CATEGORY_NOT_SHORT_CODE),
            new ShortCodeTest("zz", "54321", CATEGORY_POSSIBLE_PREMIUM_SHORT_CODE),
            new ShortCodeTest("zz", "4321", CATEGORY_POSSIBLE_PREMIUM_SHORT_CODE),
            new ShortCodeTest("zz", "321", CATEGORY_POSSIBLE_PREMIUM_SHORT_CODE),
            new ShortCodeTest("zz", "112", CATEGORY_NOT_SHORT_CODE),
            new ShortCodeTest(null, "2000000", CATEGORY_NOT_SHORT_CODE),
            new ShortCodeTest(null, "54321", CATEGORY_POSSIBLE_PREMIUM_SHORT_CODE),
            new ShortCodeTest(null, "4321", CATEGORY_POSSIBLE_PREMIUM_SHORT_CODE),
            new ShortCodeTest(null, "321", CATEGORY_POSSIBLE_PREMIUM_SHORT_CODE),
            new ShortCodeTest(null, "112", CATEGORY_NOT_SHORT_CODE),
    };

    @SmallTest
    public void testSmsUsageMonitor() {
        SmsUsageMonitor monitor = new SmsUsageMonitor(getContext());
        for (ShortCodeTest test : sShortCodeTests) {
            assertEquals("country: " + test.countryIso + " number: " + test.address,
                    test.category, monitor.checkDestination(test.address, test.countryIso));
        }
    }
}
