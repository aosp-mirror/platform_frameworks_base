/*
 * Copyright (C) 2017 The Android Open Source Project
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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;

import com.android.internal.util.HexDump;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Arrays;

/**
 * runtest frameworks-services -c com.android.server.net.watchlist.HarmfulDigestsTests
 */
@RunWith(AndroidJUnit4.class)
@SmallTest
public class HarmfulDigestsTests {

    private static final byte[] TEST_DIGEST_1 = HexDump.hexStringToByteArray("AAAAAA");
    private static final byte[] TEST_DIGEST_2 = HexDump.hexStringToByteArray("BBBBBB");
    private static final byte[] TEST_DIGEST_3 = HexDump.hexStringToByteArray("AAAABB");
    private static final byte[] TEST_DIGEST_4 = HexDump.hexStringToByteArray("BBBBAA");

    @Before
    public void setUp() throws Exception {
    }

    @After
    public void tearDown() throws Exception {
    }

    @Test
    public void testHarmfulDigests_setAndContains() throws Exception {
        HarmfulDigests harmfulDigests = new HarmfulDigests(
                Arrays.asList(new byte[][] {TEST_DIGEST_1}));
        assertTrue(harmfulDigests.contains(TEST_DIGEST_1));
        assertFalse(harmfulDigests.contains(TEST_DIGEST_2));
        assertFalse(harmfulDigests.contains(TEST_DIGEST_3));
        assertFalse(harmfulDigests.contains(TEST_DIGEST_4));
    }
}
