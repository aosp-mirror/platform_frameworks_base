/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.server.backup.encryption.chunking.cdc;

import static com.google.common.truth.Truth.assertThat;

import static org.testng.Assert.assertThrows;

import android.platform.test.annotations.Presubmit;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.security.InvalidKeyException;
import java.security.Key;
import java.util.HashSet;
import java.util.Random;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

/** Tests for {@link FingerprintMixer}. */
@RunWith(RobolectricTestRunner.class)
@Presubmit
public class FingerprintMixerTest {
    private static final String KEY_ALGORITHM = "AES";
    private static final int SEED = 42;
    private static final int SALT_LENGTH_BYTES = 256 / 8;
    private static final int KEY_SIZE_BITS = 256;

    private Random mSeededRandom;
    private FingerprintMixer mFingerprintMixer;

    /** Set up a {@link FingerprintMixer} with deterministic key and salt generation. */
    @Before
    public void setUp() throws Exception {
        // Seed so that the tests are deterministic.
        mSeededRandom = new Random(SEED);
        mFingerprintMixer = new FingerprintMixer(randomKey(), randomSalt());
    }

    /**
     * Construcing a {@link FingerprintMixer} with a salt that is too small should throw an {@link
     * IllegalArgumentException}.
     */
    @Test
    public void create_withIncorrectSaltSize_throwsIllegalArgumentException() {
        byte[] tooSmallSalt = new byte[SALT_LENGTH_BYTES - 1];

        assertThrows(
                IllegalArgumentException.class,
                () -> new FingerprintMixer(randomKey(), tooSmallSalt));
    }

    /**
     * Constructing a {@link FingerprintMixer} with a secret key that can't be encoded should throw
     * an {@link InvalidKeyException}.
     */
    @Test
    public void create_withUnencodableSecretKey_throwsInvalidKeyException() {
        byte[] keyBytes = new byte[KEY_SIZE_BITS / 8];
        UnencodableSecretKeySpec keySpec =
                new UnencodableSecretKeySpec(keyBytes, 0, keyBytes.length, KEY_ALGORITHM);

        assertThrows(InvalidKeyException.class, () -> new FingerprintMixer(keySpec, randomSalt()));
    }

    /**
     * {@link FingerprintMixer#getAddend()} should not return the same addend for two different
     * keys.
     */
    @Test
    public void getAddend_withDifferentKey_returnsDifferentResult() throws Exception {
        int iterations = 100_000;
        HashSet<Long> returnedAddends = new HashSet<>();
        byte[] salt = randomSalt();

        for (int i = 0; i < iterations; i++) {
            FingerprintMixer fingerprintMixer = new FingerprintMixer(randomKey(), salt);
            long addend = fingerprintMixer.getAddend();
            returnedAddends.add(addend);
        }

        assertThat(returnedAddends).containsNoDuplicates();
    }

    /**
     * {@link FingerprintMixer#getMultiplicand()} should not return the same multiplicand for two
     * different keys.
     */
    @Test
    public void getMultiplicand_withDifferentKey_returnsDifferentResult() throws Exception {
        int iterations = 100_000;
        HashSet<Long> returnedMultiplicands = new HashSet<>();
        byte[] salt = randomSalt();

        for (int i = 0; i < iterations; i++) {
            FingerprintMixer fingerprintMixer = new FingerprintMixer(randomKey(), salt);
            long multiplicand = fingerprintMixer.getMultiplicand();
            returnedMultiplicands.add(multiplicand);
        }

        assertThat(returnedMultiplicands).containsNoDuplicates();
    }

    /** The multiplicant returned by {@link FingerprintMixer} should always be odd. */
    @Test
    public void getMultiplicand_isOdd() throws Exception {
        int iterations = 100_000;

        for (int i = 0; i < iterations; i++) {
            FingerprintMixer fingerprintMixer = new FingerprintMixer(randomKey(), randomSalt());

            long multiplicand = fingerprintMixer.getMultiplicand();

            assertThat(isOdd(multiplicand)).isTrue();
        }
    }

    /** {@link FingerprintMixer#mix(long)} should have a random distribution. */
    @Test
    public void mix_randomlyDistributesBits() throws Exception {
        int iterations = 100_000;
        float tolerance = 0.1f;
        int[] totals = new int[64];

        for (int i = 0; i < iterations; i++) {
            long n = mFingerprintMixer.mix(mSeededRandom.nextLong());
            for (int j = 0; j < 64; j++) {
                int bit = (int) (n >> j & 1);
                totals[j] += bit;
            }
        }

        for (int i = 0; i < 64; i++) {
            float mean = ((float) totals[i]) / iterations;
            float diff = Math.abs(mean - 0.5f);
            assertThat(diff).isLessThan(tolerance);
        }
    }

    /**
     * {@link FingerprintMixer#mix(long)} should always produce a number that's different from the
     * input.
     */
    @Test
    public void mix_doesNotProduceSameNumberAsInput() {
        int iterations = 100_000;

        for (int i = 0; i < iterations; i++) {
            assertThat(mFingerprintMixer.mix(i)).isNotEqualTo(i);
        }
    }

    private byte[] randomSalt() {
        byte[] salt = new byte[SALT_LENGTH_BYTES];
        mSeededRandom.nextBytes(salt);
        return salt;
    }

    /**
     * Not a secure way of generating keys. We want to deterministically generate the same keys for
     * each test run, though, to ensure the test is deterministic.
     */
    private SecretKey randomKey() {
        byte[] keyBytes = new byte[KEY_SIZE_BITS / 8];
        mSeededRandom.nextBytes(keyBytes);
        return new SecretKeySpec(keyBytes, 0, keyBytes.length, KEY_ALGORITHM);
    }

    private static boolean isOdd(long n) {
        return Math.abs(n % 2) == 1;
    }

    /**
     * Subclass of {@link SecretKeySpec} that does not provide an encoded version. As per its
     * contract in {@link Key}, that means {@code getEncoded()} always returns null.
     */
    private class UnencodableSecretKeySpec extends SecretKeySpec {
        UnencodableSecretKeySpec(byte[] key, int offset, int len, String algorithm) {
            super(key, offset, len, algorithm);
        }

        @Override
        public byte[] getEncoded() {
            return null;
        }
    }
}
