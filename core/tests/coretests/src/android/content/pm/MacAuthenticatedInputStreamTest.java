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

package android.content.pm;

import android.support.test.filters.LargeTest;
import android.test.AndroidTestCase;

import java.io.ByteArrayInputStream;
import java.util.Arrays;

import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import libcore.io.Streams;

@LargeTest
public class MacAuthenticatedInputStreamTest extends AndroidTestCase {

    private static final SecretKey HMAC_KEY_1 = new SecretKeySpec("test_key_1".getBytes(), "HMAC");

    private static final byte[] TEST_STRING_1 = "Hello, World!".getBytes();

    /**
     * Generated with:
     *
     * echo -n 'Hello, World!' | openssl dgst -hmac 'test_key_1' -binary -sha1 | recode ..//x1 |
     *   sed 's/0x/(byte) 0x/g'
     */
    private static final byte[] TEST_STRING_1_MAC = {
            (byte) 0x29, (byte) 0xB1, (byte) 0x87, (byte) 0x6B, (byte) 0xFE, (byte) 0x83,
            (byte) 0x96, (byte) 0x51, (byte) 0x61, (byte) 0x02, (byte) 0xAF, (byte) 0x7B,
            (byte) 0xBA, (byte) 0x05, (byte) 0xE6, (byte) 0xA4, (byte) 0xAB, (byte) 0x36,
            (byte) 0x18, (byte) 0x02
    };

    /**
     * Same as TEST_STRING_1_MAC but with the first byte as 0x28 instead of
     * 0x29.
     */
    private static final byte[] TEST_STRING_1_MAC_BROKEN = {
            (byte) 0x28, (byte) 0xB1, (byte) 0x87, (byte) 0x6B, (byte) 0xFE, (byte) 0x83,
            (byte) 0x96, (byte) 0x51, (byte) 0x61, (byte) 0x02, (byte) 0xAF, (byte) 0x7B,
            (byte) 0xBA, (byte) 0x05, (byte) 0xE6, (byte) 0xA4, (byte) 0xAB, (byte) 0x36,
            (byte) 0x18, (byte) 0x02
    };

    private ByteArrayInputStream mTestStream1;

    @Override
    protected void setUp() throws Exception {
        super.setUp();

        mTestStream1 = new ByteArrayInputStream(TEST_STRING_1);
    }

    public void testString1Authenticate_Success() throws Exception {
        Mac mac = Mac.getInstance("HMAC-SHA1");
        mac.init(HMAC_KEY_1);

        MacAuthenticatedInputStream is = new MacAuthenticatedInputStream(mTestStream1, mac);

        assertTrue(Arrays.equals(TEST_STRING_1, Streams.readFully(is)));

        assertTrue(is.isTagEqual(TEST_STRING_1_MAC));
    }

    public void testString1Authenticate_WrongTag_Failure() throws Exception {
        Mac mac = Mac.getInstance("HMAC-SHA1");
        mac.init(HMAC_KEY_1);

        MacAuthenticatedInputStream is = new MacAuthenticatedInputStream(mTestStream1, mac);

        assertTrue(Arrays.equals(TEST_STRING_1, Streams.readFully(is)));

        assertFalse(is.isTagEqual(TEST_STRING_1_MAC_BROKEN));
    }

    public void testString1Authenticate_NullTag_Failure() throws Exception {
        Mac mac = Mac.getInstance("HMAC-SHA1");
        mac.init(HMAC_KEY_1);

        MacAuthenticatedInputStream is = new MacAuthenticatedInputStream(mTestStream1, mac);

        assertTrue(Arrays.equals(TEST_STRING_1, Streams.readFully(is)));

        assertFalse(is.isTagEqual(null));
    }

    public void testString1Authenticate_ReadSingleByte_Success() throws Exception {
        Mac mac = Mac.getInstance("HMAC-SHA1");
        mac.init(HMAC_KEY_1);

        MacAuthenticatedInputStream is = new MacAuthenticatedInputStream(mTestStream1, mac);

        int numRead = 0;
        while (is.read() != -1) {
            numRead++;

            if (numRead > TEST_STRING_1.length) {
                fail("read too many bytes");
            }
        }
        assertEquals(TEST_STRING_1.length, numRead);

        assertTrue(is.isTagEqual(TEST_STRING_1_MAC));
    }
}
