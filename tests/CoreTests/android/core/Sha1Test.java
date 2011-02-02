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

import android.test.suitebuilder.annotation.SmallTest;
import java.security.MessageDigest;
import junit.framework.TestCase;

/**
 * Tests SHA1 message digest algorithm.
 */
public class Sha1Test extends TestCase {
    class TestData {
        private String input;
        private String result;

        public TestData(String i, String r) {
            input = i;
            result = r;
        }
    }

    TestData[] mTestData = new TestData[]{
            new TestData("abc", "a9993e364706816aba3e25717850c26c9cd0d89d"),
            new TestData("abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq",
                    "84983e441c3bd26ebaae4aa1f95129e5e54670f1")
    };

    @SmallTest
    public void testSha1() throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-1");

        int numTests = mTestData.length;
        for (int i = 0; i < numTests; i++) {
            digest.update(mTestData[i].input.getBytes());
            byte[] hash = digest.digest();
            String encodedHash = encodeHex(hash);
            assertEquals(encodedHash, mTestData[i].result);
        }
    }

    private static String encodeHex(byte[] bytes) {
        StringBuffer hex = new StringBuffer(bytes.length * 2);

        for (int i = 0; i < bytes.length; i++) {
            if (((int) bytes[i] & 0xff) < 0x10) {
                hex.append("0");
            }
            hex.append(Integer.toString((int) bytes[i] & 0xff, 16));
        }

        return hex.toString();
    }
}

