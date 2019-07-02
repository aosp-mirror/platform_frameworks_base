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

import static java.nio.charset.StandardCharsets.UTF_8;

import android.platform.test.annotations.Presubmit;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

/** Tests for {@link RabinFingerprint64}. */
@RunWith(RobolectricTestRunner.class)
@Presubmit
public class RabinFingerprint64Test {
    private static final int WINDOW_SIZE = 31;
    private static final ImmutableList<String> TEST_STRINGS =
            ImmutableList.of(
                    "ervHTtChYXO6eXivYqThlyyzqkbRaOR",
                    "IxaVunH9ZC3qneWfhj1GkBH4ys9CYqz",
                    "wZRVjlE1p976icCFPX9pibk4PEBvjSH",
                    "pHIVaT8x8If9D6s9croksgNmJpmGYWI");

    private final RabinFingerprint64 mRabinFingerprint64 = new RabinFingerprint64();

    /**
     * No matter where in the input buffer a string occurs, {@link
     * RabinFingerprint64#computeFingerprint64(byte, byte, long)} should return the same
     * fingerprint.
     */
    @Test
    public void computeFingerprint64_forSameWindow_returnsSameFingerprint() {
        long fingerprint1 =
                computeFingerprintAtPosition(getBytes(TEST_STRINGS.get(0)), WINDOW_SIZE - 1);
        long fingerprint2 =
                computeFingerprintAtPosition(
                        getBytes(TEST_STRINGS.get(1), TEST_STRINGS.get(0)), WINDOW_SIZE * 2 - 1);
        long fingerprint3 =
                computeFingerprintAtPosition(
                        getBytes(TEST_STRINGS.get(2), TEST_STRINGS.get(3), TEST_STRINGS.get(0)),
                        WINDOW_SIZE * 3 - 1);
        String stub = "abc";
        long fingerprint4 =
                computeFingerprintAtPosition(
                        getBytes(stub, TEST_STRINGS.get(0)), WINDOW_SIZE + stub.length() - 1);

        // Assert that all fingerprints are exactly the same
        assertThat(ImmutableSet.of(fingerprint1, fingerprint2, fingerprint3, fingerprint4))
                .hasSize(1);
    }

    /** The computed fingerprint should be different for different inputs. */
    @Test
    public void computeFingerprint64_withDifferentInput_returnsDifferentFingerprint() {
        long fingerprint1 = computeFingerprintOf(TEST_STRINGS.get(0));
        long fingerprint2 = computeFingerprintOf(TEST_STRINGS.get(1));
        long fingerprint3 = computeFingerprintOf(TEST_STRINGS.get(2));
        long fingerprint4 = computeFingerprintOf(TEST_STRINGS.get(3));

        assertThat(ImmutableList.of(fingerprint1, fingerprint2, fingerprint3, fingerprint4))
                .containsNoDuplicates();
    }

    /**
     * An input with the same characters in a different order should return a different fingerprint.
     */
    @Test
    public void computeFingerprint64_withSameInputInDifferentOrder_returnsDifferentFingerprint() {
        long fingerprint1 = computeFingerprintOf("abcdefghijklmnopqrstuvwxyz12345");
        long fingerprint2 = computeFingerprintOf("54321zyxwvutsrqponmlkjihgfedcba");
        long fingerprint3 = computeFingerprintOf("4bcdefghijklmnopqrstuvwxyz123a5");
        long fingerprint4 = computeFingerprintOf("bacdefghijklmnopqrstuvwxyz12345");

        assertThat(ImmutableList.of(fingerprint1, fingerprint2, fingerprint3, fingerprint4))
                .containsNoDuplicates();
    }

    /** UTF-8 bytes of all the given strings in order. */
    private byte[] getBytes(String... strings) {
        StringBuilder sb = new StringBuilder();
        for (String s : strings) {
            sb.append(s);
        }
        return sb.toString().getBytes(UTF_8);
    }

    /**
     * The Rabin fingerprint of a window of bytes ending at {@code position} in the {@code bytes}
     * array.
     */
    private long computeFingerprintAtPosition(byte[] bytes, int position) {
        assertThat(position).isAtMost(bytes.length - 1);
        long fingerprint = 0;
        for (int i = 0; i <= position; i++) {
            byte outChar;
            if (i >= WINDOW_SIZE) {
                outChar = bytes[i - WINDOW_SIZE];
            } else {
                outChar = (byte) 0;
            }
            fingerprint =
                    mRabinFingerprint64.computeFingerprint64(
                            /*inChar=*/ bytes[i], outChar, fingerprint);
        }
        return fingerprint;
    }

    private long computeFingerprintOf(String s) {
        assertThat(s.length()).isEqualTo(WINDOW_SIZE);
        return computeFingerprintAtPosition(s.getBytes(UTF_8), WINDOW_SIZE - 1);
    }
}
