/*
 * Copyright 2017 The Android Open Source Project
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

package com.android.server.net.watchlist;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.privacy.DifferentialPrivacyEncoder;
import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

/**
 * runtest frameworks-services -c com.android.server.net.watchlist.PrivacyUtilsTests
 */
@RunWith(AndroidJUnit4.class)
@SmallTest
public class PrivacyUtilsTests {

    private static final List<String> TEST_DIGEST_LIST = Arrays.asList(
            "B86F9D37425340B635F43D6BC2506630761ADA71F5E6BBDBCA4651C479F9FB43",
            "E86F9D37425340B635F43D6BC2506630761ADA71F5E6BBDBCA4651C479F9FB45",
            "C86F9D37425340B635F43D6BC2506630761ADA71F5E6BBDBCA4651C479F9FB44",
            "C86F9D37425340B635F43D6BC2506630761ADA71F5E6BBDBCA4651C479F9FB47",
            "C86F9D37425340B635F43D6BC2506630761ADA71F5E6BBDBCA4651C479F9FB48",
            "C86F9D37425340B635F43D6BC2506630761ADA71F5E6BBDBCA4651C479F9FB49");

    private static final WatchlistReportDbHelper.AggregatedResult TEST_AGGREGATED_RESULT1 =
            new WatchlistReportDbHelper.AggregatedResult(new HashSet<>(Arrays.asList(
                    "B86F9D37425340B635F43D6BC2506630761ADA71F5E6BBDBCA4651C479F9FB43",
                    "C86F9D37425340B635F43D6BC2506630761ADA71F5E6BBDBCA4651C479F9FB48",
                    "E86F9D37425340B635F43D6BC2506630761ADA71F5E6BBDBCA4651C479F9FB45")), null,
                    new HashMap<>());

    private static final byte[] TEST_SECRET = new byte[]{
            (byte) 0xD7, (byte) 0x68, (byte) 0x99, (byte) 0x93,
            (byte) 0x94, (byte) 0x13, (byte) 0x53, (byte) 0x54,
            (byte) 0xFE, (byte) 0xD0, (byte) 0x7E, (byte) 0x54,
            (byte) 0xFE, (byte) 0xD0, (byte) 0x7E, (byte) 0x54,
            (byte) 0xD7, (byte) 0x68, (byte) 0x99, (byte) 0x93,
            (byte) 0x94, (byte) 0x13, (byte) 0x53, (byte) 0x54,
            (byte) 0xFE, (byte) 0xD0, (byte) 0x7E, (byte) 0x54,
            (byte) 0xFE, (byte) 0xD0, (byte) 0x7E, (byte) 0x54,
            (byte) 0xD7, (byte) 0x68, (byte) 0x99, (byte) 0x93,
            (byte) 0x94, (byte) 0x13, (byte) 0x53, (byte) 0x54,
            (byte) 0xFE, (byte) 0xD0, (byte) 0x7E, (byte) 0x54,
            (byte) 0xFE, (byte) 0xD0, (byte) 0x7E, (byte) 0x54
    };

    @Test
    public void testPrivacyUtils_encodeReport() throws Exception {
        Map<String, Boolean> result = PrivacyUtils.createDpEncodedReportMap(false, null,
                TEST_DIGEST_LIST, TEST_AGGREGATED_RESULT1);
        assertEquals(6, result.size());
        assertFalse(result.get("C86F9D37425340B635F43D6BC2506630761ADA71F5E6BBDBCA4651C479F9FB48"));
        assertTrue(result.get("C86F9D37425340B635F43D6BC2506630761ADA71F5E6BBDBCA4651C479F9FB49"));
        assertTrue(result.get("C86F9D37425340B635F43D6BC2506630761ADA71F5E6BBDBCA4651C479F9FB47"));
        assertTrue(result.get("E86F9D37425340B635F43D6BC2506630761ADA71F5E6BBDBCA4651C479F9FB45"));
        assertFalse(result.get("C86F9D37425340B635F43D6BC2506630761ADA71F5E6BBDBCA4651C479F9FB44"));
        assertTrue(result.get("B86F9D37425340B635F43D6BC2506630761ADA71F5E6BBDBCA4651C479F9FB43"));
    }

    @Test
    public void testPrivacyUtils_createInsecureDPEncoderForTest() throws Exception {
        DifferentialPrivacyEncoder encoder = PrivacyUtils.createInsecureDPEncoderForTest("foo");
        assertEquals(
                "EncoderId: watchlist_encoder:foo, ProbabilityF: 0.469, ProbabilityP: 0.280, "
                        + "ProbabilityQ: 1.000",
                encoder.getConfig().toString());
        assertTrue(encoder.isInsecureEncoderForTest());
    }

    @Test
    public void testPrivacyUtils_createSecureDPEncoderTest() throws Exception {
        DifferentialPrivacyEncoder encoder = PrivacyUtils.createSecureDPEncoder(TEST_SECRET, "foo");
        assertEquals(
                "EncoderId: watchlist_encoder:foo, ProbabilityF: 0.469, ProbabilityP: 0.280, "
                        + "ProbabilityQ: 1.000",
                encoder.getConfig().toString());
        assertFalse(encoder.isInsecureEncoderForTest());
    }
}
