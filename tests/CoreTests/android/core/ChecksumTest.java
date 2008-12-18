/*
 * Copyright (C) 2008 The Android Open Source Project
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

package android.core;

import junit.framework.TestCase;

import java.util.zip.Adler32;
import java.util.zip.CRC32;
import android.test.suitebuilder.annotation.SmallTest;

/**
 * tests for CRC32 and Adler32 checksum algorithms.
 */
public class ChecksumTest extends TestCase {

    @SmallTest
    public void testChecksum() throws Exception {
        /*
         * Values computed experimentally, using C interfaces.
         */
        adler32Test(mTestString, 0x9de210dbL);
        cRC32Test(mTestString, 0x939f04afL);

        // Test for issue 1016037
        wrongChecksumWithAdler32Test();
    }

    private void adler32Test(byte[] values, long expected) {
        Adler32 adler = new Adler32();

        // try it all at once
        adler.update(values);
        assertEquals(adler.getValue(), expected);

        // try resetting and computing one byte at a time
        adler.reset();
        for (int i = 0; i < values.length; i++) {
            adler.update(values[i]);
        }
        assertEquals(adler.getValue(), expected);
    }

    private void cRC32Test(byte[] values, long expected) {
        CRC32 crc = new CRC32();

        // try it all at once
        crc.update(values);
        assertEquals(crc.getValue(), expected);

        // try resetting and computing one byte at a time
        crc.reset();
        for (int i = 0; i < values.length; i++) {
            crc.update(values[i]);
        }
        assertEquals(crc.getValue(), expected);
    }

    // "The quick brown fox jumped over the lazy dogs\n"
    private static byte[] mTestString = {
            0x54, 0x68, 0x65, 0x20, 0x71, 0x75, 0x69, 0x63,
            0x6b, 0x20, 0x62, 0x72, 0x6f, 0x77, 0x6e, 0x20,
            0x66, 0x6f, 0x78, 0x20, 0x6a, 0x75, 0x6d, 0x70,
            0x65, 0x64, 0x20, 0x6f, 0x76, 0x65, 0x72, 0x20,
            0x74, 0x68, 0x65, 0x20, 0x6c, 0x61, 0x7a, 0x79,
            0x20, 0x64, 0x6f, 0x67, 0x73, 0x2e, 0x0a
    };


    // Test for issue 1016037
    private void wrongChecksumWithAdler32Test() {
        byte[] bytes = {1, 0, 5, 0, 15, 0, 1, 11, 0, 1};
        Adler32 adler = new Adler32();
        adler.update(bytes);
        long arrayChecksum = adler.getValue();
        adler.reset();
        for (int i = 0; i < bytes.length; i++) {
            adler.update(bytes[i]);
        }
        assertEquals("Checksums not equal: expected: " + arrayChecksum +
                " actual: " + adler.getValue(), arrayChecksum, adler.getValue());
    }
}

