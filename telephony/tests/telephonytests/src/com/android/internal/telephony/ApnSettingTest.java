/*
 * Copyright (C) 2010 The Android Open Source Project
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

import junit.framework.TestCase;

import android.test.suitebuilder.annotation.SmallTest;

public class ApnSettingTest extends TestCase {

    public static final String[] TYPES = {"default", "*"};

    public static void assertApnSettingEqual(ApnSetting a1, ApnSetting a2) {
        assertEquals(a1.carrier,  a2.carrier);
        assertEquals(a1.apn,      a2.apn);
        assertEquals(a1.proxy,    a2.proxy);
        assertEquals(a1.port,     a2.port);
        assertEquals(a1.mmsc,     a2.mmsc);
        assertEquals(a1.mmsProxy, a2.mmsProxy);
        assertEquals(a1.mmsPort,  a2.mmsPort);
        assertEquals(a1.user,     a2.user);
        assertEquals(a1.password, a2.password);
        assertEquals(a1.authType, a2.authType);
        assertEquals(a1.id,       a2.id);
        assertEquals(a1.numeric,  a2.numeric);
        assertEquals(a1.protocol, a2.protocol);
        assertEquals(a1.roamingProtocol, a2.roamingProtocol);
        assertEquals(a1.types.length, a2.types.length);
        int i;
        for (i = 0; i < a1.types.length; i++) {
            assertEquals(a1.types[i], a2.types[i]);
        }
        assertEquals(a1.carrierEnabled, a2.carrierEnabled);
        assertEquals(a1.bearer, a2.bearer);
    }

    @SmallTest
    public void testFromString() throws Exception {
        String[] dunTypes = {"DUN"};
        String[] mmsTypes = {"mms", "*"};

        ApnSetting expected_apn;
        String testString;

        // A real-world v1 example string.
        testString = "Vodafone IT,web.omnitel.it,,,,,,,,,222,10,,DUN";
        expected_apn =  new ApnSetting(
                -1, "22210", "Vodafone IT", "web.omnitel.it", "", "",
                "", "", "", "", "", 0, dunTypes, "IP", "IP",true,0);
        assertApnSettingEqual(expected_apn, ApnSetting.fromString(testString));

        // A v2 string.
        testString = "[ApnSettingV2] Name,apn,,,,,,,,,123,45,,mms|*,IPV6,IP,true,14";
        expected_apn =  new ApnSetting(
                -1, "12345", "Name", "apn", "", "",
                "", "", "", "", "", 0, mmsTypes, "IPV6", "IP",true,14);
        assertApnSettingEqual(expected_apn, ApnSetting.fromString(testString));

        // A v2 string with spaces.
        testString = "[ApnSettingV2] Name,apn, ,,,,,,,,123,45,,mms|*,IPV4V6, IP,true,14";
        expected_apn =  new ApnSetting(
                -1, "12345", "Name", "apn", "", "",
                "", "", "", "", "", 0, mmsTypes, "IPV4V6", "IP",true,14);
        assertApnSettingEqual(expected_apn, ApnSetting.fromString(testString));

        // Return null if insufficient fields given.
        testString = "[ApnSettingV2] Name,apn,,,,,,,,,123, 45,,mms|*";
        assertEquals(null, ApnSetting.fromString(testString));

        testString = "Name,apn,,,,,,,,,123, 45,";
        assertEquals(null, ApnSetting.fromString(testString));

        // Parse (incorrect) V2 format without the tag as V1.
        testString = "Name,apn,,,,,,,,,123, 45,,mms|*,IPV6,true,14";
        String[] incorrectTypes = {"mms|*", "IPV6"};
        expected_apn =  new ApnSetting(
                -1, "12345", "Name", "apn", "", "",
                "", "", "", "", "", 0, incorrectTypes, "IP", "IP",true,14);
        assertApnSettingEqual(expected_apn, ApnSetting.fromString(testString));
    }


    @SmallTest
    public void testToString() throws Exception {
        String[] types = {"default", "*"};
        ApnSetting apn =  new ApnSetting(
                99, "12345", "Name", "apn", "proxy", "port",
                "mmsc", "mmsproxy", "mmsport", "user", "password", 0,
                types, "IPV4V6", "IP", true, 14);
        String expected = "[ApnSettingV2] Name, 99, 12345, apn, proxy, " +
                "mmsc, mmsproxy, mmsport, port, 0, default | *, " +
                "IPV4V6, IP, true, 14";
        assertEquals(expected, apn.toString());
    }
}
