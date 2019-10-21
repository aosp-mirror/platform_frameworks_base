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

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.internal.util.HexDump;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Arrays;

/**
 * runtest frameworks-services -c com.android.server.net.watchlist.HarmfulCrcTests
 */
@RunWith(AndroidJUnit4.class)
@SmallTest
public class HarmfulCrcsTests {

    private static final byte[] TEST_DIGEST = HexDump.hexStringToByteArray("AABBCCDD");

    @Before
    public void setUp() throws Exception {
    }

    @After
    public void tearDown() throws Exception {
    }

    @Test
    public void testHarmfulCrcs_setAndContains() throws Exception {
        HarmfulCrcs harmfulCrcs = new HarmfulCrcs(
                Arrays.asList(new byte[][] {TEST_DIGEST}));
        assertTrue(harmfulCrcs.contains(0xaabbccdd));
        assertFalse(harmfulCrcs.contains(0xbbbbbbbb));
        assertFalse(harmfulCrcs.contains(0x01020304));
        assertFalse(harmfulCrcs.contains(0xddccbbaa));
    }
}
