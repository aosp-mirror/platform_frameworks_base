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

/** Helper to calculate a 64-bit Rabin fingerprint over a 31-byte window. */
public class RabinFingerprint64 {
    private static final long DEFAULT_IRREDUCIBLE_POLYNOMIAL_64 = 0x000000000000001BL;
    private static final int POLYNOMIAL_DEGREE = 64;
    private static final int SLIDING_WINDOW_SIZE_BYTES = 31;

    private final long mPoly64;
    // Auxiliary tables to speed up the computation of Rabin fingerprints.
    private final long[] mTableFP64 = new long[256];
    private final long[] mTableOutByte = new long[256];

    /**
     * Constructs a new instance over the given irreducible 64-degree polynomial. It is up to the
     * caller to determine that the polynomial is irreducible. If it is not the fingerprinting will
     * not behave as expected.
     *
     * @param poly64 The polynomial.
     */
    public RabinFingerprint64(long poly64) {
        mPoly64 = poly64;
    }

    /** Constructs a new instance using {@code x^64 + x^4 + x + 1} as the irreducible polynomial. */
    public RabinFingerprint64() {
        this(DEFAULT_IRREDUCIBLE_POLYNOMIAL_64);
        computeFingerprintTables64();
        computeFingerprintTables64Windowed();
    }

    /**
     * Computes the fingerprint for the new sliding window given the fingerprint of the previous
     * sliding window, the byte sliding in, and the byte sliding out.
     *
     * @param inChar The new char coming into the sliding window.
     * @param outChar The left most char sliding out of the window.
     * @param fingerPrint Fingerprint for previous window.
     * @return New fingerprint for the new sliding window.
     */
    public long computeFingerprint64(byte inChar, byte outChar, long fingerPrint) {
        return (fingerPrint << 8)
                ^ (inChar & 0xFF)
                ^ mTableFP64[(int) (fingerPrint >>> 56)]
                ^ mTableOutByte[outChar & 0xFF];
    }

    /** Compute auxiliary tables to speed up the fingerprint computation. */
    private void computeFingerprintTables64() {
        long[] degreesRes64 = new long[POLYNOMIAL_DEGREE];
        degreesRes64[0] = mPoly64;
        for (int i = 1; i < POLYNOMIAL_DEGREE; i++) {
            if ((degreesRes64[i - 1] & (1L << 63)) == 0) {
                degreesRes64[i] = degreesRes64[i - 1] << 1;
            } else {
                degreesRes64[i] = (degreesRes64[i - 1] << 1) ^ mPoly64;
            }
        }
        for (int i = 0; i < 256; i++) {
            int currIndex = i;
            for (int j = 0; (currIndex > 0) && (j < 8); j++) {
                if ((currIndex & 0x1) == 1) {
                    mTableFP64[i] ^= degreesRes64[j];
                }
                currIndex >>>= 1;
            }
        }
    }

    /**
     * Compute auxiliary table {@code mTableOutByte} to facilitate the computing of fingerprints for
     * sliding windows. This table is to take care of the effect on the fingerprint when the
     * leftmost byte in the window slides out.
     */
    private void computeFingerprintTables64Windowed() {
        // Auxiliary array degsRes64[8] defined by: <code>degsRes64[i] = x^(8 *
        // SLIDING_WINDOW_SIZE_BYTES + i) mod this.mPoly64.</code>
        long[] degsRes64 = new long[8];
        degsRes64[0] = mPoly64;
        for (int i = 65; i < 8 * (SLIDING_WINDOW_SIZE_BYTES + 1); i++) {
            if ((degsRes64[(i - 1) % 8] & (1L << 63)) == 0) {
                degsRes64[i % 8] = degsRes64[(i - 1) % 8] << 1;
            } else {
                degsRes64[i % 8] = (degsRes64[(i - 1) % 8] << 1) ^ mPoly64;
            }
        }
        for (int i = 0; i < 256; i++) {
            int currIndex = i;
            for (int j = 0; (currIndex > 0) && (j < 8); j++) {
                if ((currIndex & 0x1) == 1) {
                    mTableOutByte[i] ^= degsRes64[j];
                }
                currIndex >>>= 1;
            }
        }
    }
}
