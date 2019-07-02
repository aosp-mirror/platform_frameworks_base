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

package android.privacy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.privacy.internal.rappor.RapporConfig;
import android.privacy.internal.rappor.RapporEncoder;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Unit test for the {@link RapporEncoder}.
 * Most of the tests are done in external/rappor/client/javatest/ already.
 * Tests here are just make sure the {@link RapporEncoder} wrap Rappor correctly.
 */
@RunWith(AndroidJUnit4.class)
@SmallTest
public class RapporEncoderTest {

    @Test
    public void testRapporEncoder_config() throws Exception {
        final RapporConfig config = new RapporConfig(
                "Foo",  // encoderId
                8,  // numBits,
                13.0 / 128.0,  // probabilityF
                0.25,  // probabilityP
                0.75,  // probabilityQ
                1,  // numCohorts
                2);  // numBloomHashes)
        final RapporEncoder encoder = RapporEncoder.createEncoder(config,
                makeTestingUserSecret("encoder1"));
        assertEquals("Rappor", encoder.getConfig().getAlgorithm());
        assertEquals("EncoderId: Foo, NumBits: 8, ProbabilityF: 0.102, "
                + "ProbabilityP: 0.250, ProbabilityQ: 0.750, NumCohorts: 1, "
                + "NumBloomHashes: 2", encoder.getConfig().toString());
    }

    @Test
    public void testRapporEncoder_basicIRRTest() throws Exception {
        final RapporConfig config = new RapporConfig(
                "Foo", // encoderId
                12, // numBits,
                0, // probabilityF
                0, // probabilityP
                1, // probabilityQ
                1, // numCohorts (so must be cohort 0)
                2);  // numBloomHashes
        // Use insecure encoder here as we want to get the exact output.
        final RapporEncoder encoder = RapporEncoder.createInsecureEncoderForTest(config);
        assertEquals(768, toLong(encoder.encodeString("Testing")));
    }

    @Test
    public void testRapporEncoder_IRRWithPRR() throws Exception {
        int numBits = 8;
        final long inputValue = 254L;
        final long expectedPrrValue = 126L;
        final long expectedPrrAndIrrValue = 79L;

        final RapporConfig config1 = new RapporConfig(
                "Foo2", // encoderId
                numBits, // numBits,
                0.25, // probabilityF
                0, // probabilityP
                1, // probabilityQ
                1, // numCohorts
                2); // numBloomHashes
        // Use insecure encoder here as we want to get the exact output.
        final RapporEncoder encoder1 = RapporEncoder.createInsecureEncoderForTest(config1);
        // Verify that PRR is working as expected.
        assertEquals(expectedPrrValue, toLong(encoder1.encodeBits(toBytes(inputValue))));
        assertTrue(encoder1.isInsecureEncoderForTest());

        // Verify that IRR is working as expected.
        final RapporConfig config2 = new RapporConfig(
                "Foo2", // encoderId
                numBits, // numBits,
                0, // probabilityF
                0.3, // probabilityP
                0.7, // probabilityQ
                1, // numCohorts
                2); // numBloomHashes
        // Use insecure encoder here as we want to get the exact output.
        final RapporEncoder encoder2 = RapporEncoder.createInsecureEncoderForTest(config2);
        assertEquals(expectedPrrAndIrrValue,
                toLong(encoder2.encodeBits(toBytes(expectedPrrValue))));

        // Test that end-to-end is the result of PRR + IRR.
        final RapporConfig config3 = new RapporConfig(
                "Foo2", // encoderId
                numBits, // numBits,
                0.25, // probabilityF
                0.3, // probabilityP
                0.7, // probabilityQ
                1, // numCohorts
                2); // numBloomHashes
        final RapporEncoder encoder3 = RapporEncoder.createInsecureEncoderForTest(config3);
        // Verify that PRR is working as expected.
        assertEquals(expectedPrrAndIrrValue, toLong(encoder3.encodeBits(toBytes(inputValue))));
    }

    @Test
    public void testRapporEncoder_ensureSecureEncoderIsSecure() throws Exception {
        int numBits = 8;
        final long inputValue = 254L;
        final long prrValue = 250L;
        final long prrAndIrrValue = 184L;

        final RapporConfig config1 = new RapporConfig(
                "Foo", // encoderId
                numBits, // numBits,
                0.25, // probabilityF
                0, // probabilityP
                1, // probabilityQ
                1, // numCohorts
                2); // numBloomHashes
        final RapporEncoder encoder1 = RapporEncoder.createEncoder(config1,
                makeTestingUserSecret("secret1"));
        // Verify that PRR is working as expected, not affected by random seed.
        assertEquals(prrValue, toLong(encoder1.encodeBits(toBytes(inputValue))));
        assertFalse(encoder1.isInsecureEncoderForTest());

        boolean hasDifferentResult2 = false;
        for (int i = 0; i < 5; i++) {
            final RapporConfig config2 = new RapporConfig(
                    "Foo", // encoderId
                    numBits, // numBits,
                    0, // probabilityF
                    0.3, // probabilityP
                    0.7, // probabilityQ
                    1, // numCohorts
                    2); // numBloomHashes
            final RapporEncoder encoder2 = RapporEncoder.createEncoder(config2,
                    makeTestingUserSecret("secret1"));
            hasDifferentResult2 |= (prrAndIrrValue != toLong(
                    encoder2.encodeBits(toBytes(prrValue))));
        }
        // Ensure it's not getting same result as it has random seed while encoder id and secret
        // is the same.
        assertTrue(hasDifferentResult2);

        boolean hasDifferentResults3 = false;
        for (int i = 0; i < 5; i++) {
            final RapporConfig config3 = new RapporConfig(
                    "Foo", // encoderId
                    numBits, // numBits,
                    0.25, // probabilityF
                    0.3, // probabilityP
                    0.7, // probabilityQ
                    1, // numCohorts
                    2); // numBloomHashes
            final RapporEncoder encoder3 = RapporEncoder.createEncoder(config3,
                    makeTestingUserSecret("secret1"));
            hasDifferentResults3 |= (prrAndIrrValue != toLong(
                    encoder3.encodeBits(toBytes(inputValue))));
        }
        // Ensure it's not getting same result as it has random seed while encoder id and secret
        // is the same.
        assertTrue(hasDifferentResults3);
    }

    private static byte[] toBytes(long value) {
        return ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(value).array();
    }

    private static long toLong(byte[] bytes) {
        ByteBuffer buffer = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).put(bytes);
        buffer.rewind();
        return buffer.getLong();
    }

    private static byte[] makeTestingUserSecret(String testingSecret) throws Exception {
        // We generate the fake user secret by concatenating three copies of the
        // 16 byte MD5 hash of the testingSecret string encoded in UTF 8.
        MessageDigest md5 = MessageDigest.getInstance("MD5");
        byte[] digest = md5.digest(testingSecret.getBytes(StandardCharsets.UTF_8));
        assertEquals(16, digest.length);
        return ByteBuffer.allocate(48).put(digest).put(digest).put(digest).array();
    }
}
