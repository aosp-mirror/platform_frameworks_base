/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.server.integrity;

import static com.android.server.integrity.IntegrityUtils.getBytesFromHexDigest;
import static com.android.server.integrity.IntegrityUtils.getHexDigest;
import static com.android.server.testutils.TestUtils.assertExpectException;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

/** Unit test for {@link com.android.server.integrity.IntegrityUtils} */
@RunWith(AndroidJUnit4.class)
public class IntegrityUtilsTest {

    private static final String HEX_DIGEST = "1234567890ABCDEF";
    private static final byte[] BYTES =
            new byte[] {0x12, 0x34, 0x56, 0x78, (byte) 0x90, (byte) 0xAB, (byte) 0xCD, (byte) 0xEF};

    @Test
    public void testGetBytesFromHexDigest() {
        assertArrayEquals(BYTES, getBytesFromHexDigest(HEX_DIGEST));
    }

    @Test
    public void testGetHexDigest() {
        assertEquals(HEX_DIGEST, getHexDigest(BYTES));
    }

    @Test
    public void testInvalidHexDigest() {
        assertExpectException(
                IllegalArgumentException.class,
                "must have even length",
                () -> getBytesFromHexDigest("ABC"));

        assertExpectException(
                IllegalArgumentException.class,
                "Invalid hex char",
                () -> getBytesFromHexDigest("GH"));
    }
}
