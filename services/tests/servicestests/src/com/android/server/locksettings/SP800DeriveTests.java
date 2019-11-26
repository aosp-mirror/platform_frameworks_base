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

package com.android.server.locksettings;

import static org.junit.Assert.assertEquals;

import android.platform.test.annotations.Presubmit;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.internal.util.HexDump;

import org.junit.Test;
import org.junit.runner.RunWith;

@SmallTest
@Presubmit
@RunWith(AndroidJUnit4.class)
public class SP800DeriveTests {
    @Test
    public void testFixedInput() throws Exception {
        // CAVP: https://csrc.nist.gov/projects/cryptographic-algorithm-validation-program/key-derivation
        byte[] keyBytes = HexDump.hexStringToByteArray(
            "e204d6d466aad507ffaf6d6dab0a5b26"
            + "152c9e21e764370464e360c8fbc765c6");
        SP800Derive sk = new SP800Derive(keyBytes);
        byte[] fixedInput = HexDump.hexStringToByteArray(
            "7b03b98d9f94b899e591f3ef264b71b1"
            + "93fba7043c7e953cde23bc5384bc1a62"
            + "93580115fae3495fd845dadbd02bd645"
            + "5cf48d0f62b33e62364a3a80");
        byte[] res = sk.fixedInput(fixedInput);
        assertEquals((
                "770dfab6a6a4a4bee0257ff335213f78"
                + "d8287b4fd537d5c1fffa956910e7c779").toUpperCase(), HexDump.toHexString(res));
    }
}
