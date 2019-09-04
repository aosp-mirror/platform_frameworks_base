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

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.privacy.internal.longitudinalreporting.LongitudinalReportingConfig;
import android.privacy.internal.longitudinalreporting.LongitudinalReportingEncoder;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Unit test for the {@link LongitudinalReportingEncoder}.
 *
 * As {@link LongitudinalReportingEncoder} is based on Rappor,
 * most cases are covered by Rappor tests already.
 */
@RunWith(AndroidJUnit4.class)
@SmallTest
public class LongitudinalReportingEncoderTest {

    @Test
    public void testLongitudinalReportingEncoder_config() throws Exception {
        final LongitudinalReportingConfig config = new LongitudinalReportingConfig(
                "Foo",  // encoderId
                0.4,  // probabilityF
                0.25,  // probabilityP
                1);  // probabilityQ
        final LongitudinalReportingEncoder encoder =
                LongitudinalReportingEncoder.createInsecureEncoderForTest(
                        config);
        assertEquals("LongitudinalReporting", encoder.getConfig().getAlgorithm());
        assertEquals(
                "EncoderId: Foo, ProbabilityF: 0.400, ProbabilityP: 0.250, ProbabilityQ: 1.000",
                encoder.getConfig().toString());
    }

    @Test
    public void testLongitudinalReportingEncoder_basicIRRTest() throws Exception {
        // Test if IRR can generate expected result when seed is fixed (insecure encoder)
        final LongitudinalReportingConfig config = new LongitudinalReportingConfig(
                "Foo",  // encoderId
                0.4,  // probabilityF
                0,  // probabilityP
                0);  // probabilityQ
        // Use insecure encoder here to make sure seed is set.
        final LongitudinalReportingEncoder encoder =
                LongitudinalReportingEncoder.createInsecureEncoderForTest(
                        config);
        assertEquals(1, encoder.encodeBoolean(true)[0]);
        assertEquals(1, encoder.encodeBoolean(true)[0]);
        assertEquals(1, encoder.encodeBoolean(true)[0]);
        assertEquals(0, encoder.encodeBoolean(true)[0]);
        assertEquals(1, encoder.encodeBoolean(true)[0]);
        assertEquals(1, encoder.encodeBoolean(true)[0]);
        assertEquals(1, encoder.encodeBoolean(true)[0]);
        assertEquals(1, encoder.encodeBoolean(true)[0]);
        assertEquals(0, encoder.encodeBoolean(true)[0]);
        assertEquals(0, encoder.encodeBoolean(true)[0]);

        assertEquals(0, encoder.encodeBoolean(false)[0]);
        assertEquals(1, encoder.encodeBoolean(false)[0]);
        assertEquals(1, encoder.encodeBoolean(false)[0]);
        assertEquals(0, encoder.encodeBoolean(false)[0]);
        assertEquals(0, encoder.encodeBoolean(false)[0]);
        assertEquals(0, encoder.encodeBoolean(false)[0]);
        assertEquals(0, encoder.encodeBoolean(false)[0]);
        assertEquals(0, encoder.encodeBoolean(false)[0]);
        assertEquals(0, encoder.encodeBoolean(false)[0]);
        assertEquals(0, encoder.encodeBoolean(false)[0]);

        // Test if IRR returns original result when f = 0
        final LongitudinalReportingConfig config2 = new LongitudinalReportingConfig(
                "Foo",  // encoderId
                0,  // probabilityF
                0,  // probabilityP
                0);  // probabilityQ
        final LongitudinalReportingEncoder encoder2
                = LongitudinalReportingEncoder.createEncoder(
                config2, makeTestingUserSecret("secret2"));
        for (int i = 0; i < 10; i++) {
            assertEquals(1, encoder2.encodeBoolean(true)[0]);
        }
        for (int i = 0; i < 10; i++) {
            assertEquals(0, encoder2.encodeBoolean(false)[0]);
        }

        // Test if IRR returns opposite result when f = 1
        final LongitudinalReportingConfig config3 = new LongitudinalReportingConfig(
                "Foo",  // encoderId
                1,  // probabilityF
                0,  // probabilityP
                0);  // probabilityQ
        final LongitudinalReportingEncoder encoder3
                = LongitudinalReportingEncoder.createEncoder(
                config3, makeTestingUserSecret("secret3"));
        for (int i = 0; i < 10; i++) {
            assertEquals(1, encoder3.encodeBoolean(false)[0]);
        }
        for (int i = 0; i < 10; i++) {
            assertEquals(0, encoder3.encodeBoolean(true)[0]);
        }
    }

    @Test
    public void testLongitudinalReportingInsecureEncoder_setSeedCorrectly() throws Exception {
        final int n = 10000;
        final double f = 0.35;
        final double expectedTrueSum = n * f;
        final double valueRange = 5 * Math.sqrt(n * f * (1 - f));
        int trueSum = 0;
        for (int i = 0; i < n; i++) {
            final LongitudinalReportingConfig config = new LongitudinalReportingConfig(
                    "encoder" + i,  // encoderId
                    f,  // probabilityF
                    0,  // probabilityP
                    1);  // probabilityQ
            final LongitudinalReportingEncoder encoder
                    = LongitudinalReportingEncoder.createInsecureEncoderForTest(config);
            boolean encodedFalse = encoder.encodeBoolean(false)[0] > 0;
            if (encodedFalse) {
                trueSum += 1;
            }
        }
        // Total number of true(s) should be around the mean (10000 * 0.35)
        assertThat((double) trueSum).isLessThan(expectedTrueSum + valueRange);
        assertThat((double) trueSum).isAtLeast(expectedTrueSum - valueRange);
    }

    @Test
    public void testLongitudinalReportingEncoder_basicPRRTest() throws Exception {
        // Should always return original value when p = 0
        for (int i = 0; i < 10; i++) {
            for (int j = 0; j < 10; j++) {
                final LongitudinalReportingConfig config1 = new LongitudinalReportingConfig(
                        "Foo" + i,  // encoderId
                        0,  // probabilityF
                        0,  // probabilityP
                        0);  // probabilityQ
                final LongitudinalReportingEncoder encoder1
                        = LongitudinalReportingEncoder.createEncoder(
                        config1, makeTestingUserSecret("encoder" + j));
                assertEquals(0, encoder1.encodeBoolean(false)[0]);
                assertEquals(0, encoder1.encodeBoolean(false)[0]);
                assertEquals(0, encoder1.encodeBoolean(false)[0]);
                assertEquals(0, encoder1.encodeBoolean(false)[0]);
                assertEquals(0, encoder1.encodeBoolean(false)[0]);
                assertEquals(1, encoder1.encodeBoolean(true)[0]);
                assertEquals(1, encoder1.encodeBoolean(true)[0]);
                assertEquals(1, encoder1.encodeBoolean(true)[0]);
                assertEquals(1, encoder1.encodeBoolean(true)[0]);
                assertEquals(1, encoder1.encodeBoolean(true)[0]);
            }
        }

        // Should always return false when p = 1, q = 0
        for (int i = 0; i < 10; i++) {
            for (int j = 0; j < 10; j++) {
                final LongitudinalReportingConfig config2 = new LongitudinalReportingConfig(
                        "Foo" + i,  // encoderId
                        0,  // probabilityF
                        1,  // probabilityP
                        0);  // probabilityQ
                final LongitudinalReportingEncoder encoder2
                        = LongitudinalReportingEncoder.createEncoder(
                        config2, makeTestingUserSecret("encoder" + j));
                assertEquals(0, encoder2.encodeBoolean(false)[0]);
                assertEquals(0, encoder2.encodeBoolean(false)[0]);
                assertEquals(0, encoder2.encodeBoolean(false)[0]);
                assertEquals(0, encoder2.encodeBoolean(false)[0]);
                assertEquals(0, encoder2.encodeBoolean(false)[0]);
                assertEquals(0, encoder2.encodeBoolean(true)[0]);
                assertEquals(0, encoder2.encodeBoolean(true)[0]);
                assertEquals(0, encoder2.encodeBoolean(true)[0]);
                assertEquals(0, encoder2.encodeBoolean(true)[0]);
                assertEquals(0, encoder2.encodeBoolean(true)[0]);
            }
        }

        // Should always return true when p = 1, q = 1
        for (int i = 0; i < 10; i++) {
            for (int j = 0; j < 10; j++) {
                final LongitudinalReportingConfig config3 = new LongitudinalReportingConfig(
                        "Foo" + i,  // encoderId
                        0,  // probabilityF
                        1,  // probabilityP
                        1);  // probabilityQ
                final LongitudinalReportingEncoder encoder3
                        = LongitudinalReportingEncoder.createEncoder(
                        config3, makeTestingUserSecret("encoder" + j));
                assertEquals(1, encoder3.encodeBoolean(false)[0]);
                assertEquals(1, encoder3.encodeBoolean(false)[0]);
                assertEquals(1, encoder3.encodeBoolean(false)[0]);
                assertEquals(1, encoder3.encodeBoolean(false)[0]);
                assertEquals(1, encoder3.encodeBoolean(false)[0]);
                assertEquals(1, encoder3.encodeBoolean(true)[0]);
                assertEquals(1, encoder3.encodeBoolean(true)[0]);
                assertEquals(1, encoder3.encodeBoolean(true)[0]);
                assertEquals(1, encoder3.encodeBoolean(true)[0]);
                assertEquals(1, encoder3.encodeBoolean(true)[0]);
            }
        }

        // PRR should return different value when encoder id is changed
        boolean hasFalseResult1 = false;
        boolean hasTrueResult1 = false;
        for (int i = 0; i < 50; i++) {
            boolean firstResult = false;
            for (int j = 0; j < 10; j++) {
                final LongitudinalReportingConfig config4 = new LongitudinalReportingConfig(
                        "Foo" + i,  // encoderId
                        0,  // probabilityF
                        1,  // probabilityP
                        0.5);  // probabilityQ
                final LongitudinalReportingEncoder encoder4
                        = LongitudinalReportingEncoder.createEncoder(
                        config4, makeTestingUserSecret("encoder4"));
                boolean encodedFalse = encoder4.encodeBoolean(false)[0] > 0;
                boolean encodedTrue = encoder4.encodeBoolean(true)[0] > 0;
                // PRR should always give the same value when all parameters are the same
                assertEquals(encodedTrue, encodedFalse);
                if (j == 0) {
                    firstResult = encodedTrue;
                } else {
                    assertEquals(firstResult, encodedTrue);
                }
                if (encodedTrue) {
                    hasTrueResult1 = true;
                } else {
                    hasFalseResult1 = true;
                }
            }
        }
        // Ensure it has both true and false results when encoder id is different
        assertTrue(hasTrueResult1);
        assertTrue(hasFalseResult1);

        // PRR should give different value when secret is changed
        boolean hasFalseResult2 = false;
        boolean hasTrueResult2 = false;
        for (int i = 0; i < 50; i++) {
            boolean firstResult = false;
            for (int j = 0; j < 10; j++) {
                final LongitudinalReportingConfig config5 = new LongitudinalReportingConfig(
                        "Foo",  // encoderId
                        0,  // probabilityF
                        1,  // probabilityP
                        0.5);  // probabilityQ
                final LongitudinalReportingEncoder encoder5
                        = LongitudinalReportingEncoder.createEncoder(
                        config5, makeTestingUserSecret("encoder" + i));
                boolean encodedFalse = encoder5.encodeBoolean(false)[0] > 0;
                boolean encodedTrue = encoder5.encodeBoolean(true)[0] > 0;
                // PRR should always give the same value when parameters are the same
                assertEquals(encodedTrue, encodedFalse);
                if (j == 0) {
                    firstResult = encodedTrue;
                } else {
                    assertEquals(firstResult, encodedTrue);
                }
                if (encodedTrue) {
                    hasTrueResult2 = true;
                } else {
                    hasFalseResult2 = true;
                }
            }
        }
        // Ensure it has both true and false results when encoder id is different
        assertTrue(hasTrueResult2);
        assertTrue(hasFalseResult2);

        // Confirm if PRR randomizer is working correctly
        final int n1 = 1000;
        final double p1 = 0.8;
        final double expectedTrueSum1 = n1 * p1;
        final double valueRange1 = 5 * Math.sqrt(n1 * p1 * (1 - p1));
        int trueSum1 = 0;
        for (int i = 0; i < n1; i++) {
            final LongitudinalReportingConfig config6 = new LongitudinalReportingConfig(
                    "Foo",  // encoderId
                    0,  // probabilityF
                    p1,  // probabilityP
                    1);  // probabilityQ
            final LongitudinalReportingEncoder encoder6
                    = LongitudinalReportingEncoder.createEncoder(
                    config6, makeTestingUserSecret("encoder" + i));
            boolean encodedFalse = encoder6.encodeBoolean(false)[0] > 0;
            if (encodedFalse) {
                trueSum1 += 1;
            }
        }
        // Total number of true(s) should be around the mean (1000 * 0.8)
        assertThat((double) trueSum1).isLessThan(expectedTrueSum1 + valueRange1);
        assertThat((double) trueSum1).isAtLeast(expectedTrueSum1 - valueRange1);

        // Confirm if PRR randomizer is working correctly
        final int n2 = 1000;
        final double p2 = 0.2;
        final double expectedTrueSum2 = n2 * p2;
        final double valueRange2 = 5 * Math.sqrt(n2 * p2 * (1 - p2));
        int trueSum2 = 0;
        for (int i = 0; i < n2; i++) {
            final LongitudinalReportingConfig config7 = new LongitudinalReportingConfig(
                    "Foo",  // encoderId
                    0,  // probabilityF
                    p2,  // probabilityP
                    1);  // probabilityQ
            final LongitudinalReportingEncoder encoder7
                    = LongitudinalReportingEncoder.createEncoder(
                    config7, makeTestingUserSecret("encoder" + i));
            boolean encodedFalse = encoder7.encodeBoolean(false)[0] > 0;
            if (encodedFalse) {
                trueSum2 += 1;
            }
        }
        // Total number of true(s) should be around the mean (1000 * 0.2)
        assertThat((double) trueSum2).isLessThan(expectedTrueSum2 + valueRange2);
        assertThat((double) trueSum2).isAtLeast(expectedTrueSum2 - valueRange2);
    }

    @Test
    public void testLongitudinalReportingEncoder_basicIRRwithPRRTest() throws Exception {
        // Verify PRR result will run IRR
        boolean hasFalseResult1 = false;
        boolean hasTrueResult1 = false;
        for (int i = 0; i < 50; i++) {
            final LongitudinalReportingConfig config1 = new LongitudinalReportingConfig(
                    "Foo",  // encoderId
                    0.5,  // probabilityF
                    1,  // probabilityP
                    1);  // probabilityQ
            final LongitudinalReportingEncoder encoder1
                    = LongitudinalReportingEncoder.createEncoder(
                    config1, makeTestingUserSecret("encoder1"));
            if (encoder1.encodeBoolean(false)[0] > 0) {
                hasTrueResult1 = true;
            } else {
                hasFalseResult1 = true;
            }
        }
        assertTrue(hasTrueResult1);
        assertTrue(hasFalseResult1);

        // When secret is different, some device should use PRR result, some should use IRR result
        boolean hasFalseResult2 = false;
        boolean hasTrueResult2 = false;
        for (int i = 0; i < 50; i++) {
            final LongitudinalReportingConfig config2 = new LongitudinalReportingConfig(
                    "Foo",  // encoderId
                    1,  // probabilityF
                    0.5,  // probabilityP
                    1);  // probabilityQ
            final LongitudinalReportingEncoder encoder2
                    = LongitudinalReportingEncoder.createEncoder(
                    config2, makeTestingUserSecret("encoder" + i));
            if (encoder2.encodeBoolean(false)[0] > 0) {
                hasTrueResult2 = true;
            } else {
                hasFalseResult2 = true;
            }
        }
        assertTrue(hasTrueResult2);
        assertTrue(hasFalseResult2);
    }

    @Test
    public void testLongTermRandomizedResult() throws Exception {
        // Verify getLongTermRandomizedResult can return expected result when parameters are fixed.
        final boolean[] expectedResult =
                new boolean[]{true, false, true, true, true,
                        false, false, false, true, false,
                        false, false, false, true, true,
                        true, true, false, true, true,
                        true, true, false, true, true};
        for (int i = 0; i < 5; i++) {
            for (int j = 0; j < 5; j++) {
                boolean result = LongitudinalReportingEncoder.getLongTermRandomizedResult(0.5,
                        true, makeTestingUserSecret("secret" + i), "encoder" + j);
                assertEquals(expectedResult[i * 5 + j], result);
            }
        }
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
