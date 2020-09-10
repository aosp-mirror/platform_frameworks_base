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

import static com.android.internal.util.Preconditions.checkArgument;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;

import javax.crypto.SecretKey;

/**
 * Helper for mixing fingerprint with key material.
 *
 * <p>We do this as otherwise the Rabin fingerprint leaks information about the plaintext. i.e., if
 * two users have the same file, it will be partitioned by Rabin in the same way, allowing us to
 * infer that it is the same as another user's file.
 *
 * <p>By mixing the fingerprint with the user's secret key, the chunking method is different on a
 * per key basis. Each application has its own {@link SecretKey}, so we cannot infer that a file is
 * the same even across multiple applications owned by the same user, never mind across multiple
 * users.
 *
 * <p>Instead of directly mixing the fingerprint with the user's secret, we first securely and
 * deterministically derive a secondary chunking key. As Rabin is not a cryptographically secure
 * hash, it might otherwise leak information about the user's secret. This prevents that from
 * happening.
 */
public class FingerprintMixer {
    public static final int SALT_LENGTH_BYTES = 256 / Byte.SIZE;
    private static final String DERIVED_KEY_NAME = "RabinFingerprint64Mixer";

    private final long mAddend;
    private final long mMultiplicand;

    /**
     * A new instance from a given secret key and salt. Salt must be the same across incremental
     * backups, or a different chunking strategy will be used each time, defeating the dedup.
     *
     * @param secretKey The application-specific secret.
     * @param salt The salt.
     * @throws InvalidKeyException If the encoded form of {@code secretKey} is inaccessible.
     */
    public FingerprintMixer(SecretKey secretKey, byte[] salt) throws InvalidKeyException {
        checkArgument(salt.length == SALT_LENGTH_BYTES, "Requires a 256-bit salt.");
        byte[] keyBytes = secretKey.getEncoded();
        if (keyBytes == null) {
            throw new InvalidKeyException("SecretKey must support encoding for FingerprintMixer.");
        }
        byte[] derivedKey =
                Hkdf.hkdf(keyBytes, salt, DERIVED_KEY_NAME.getBytes(StandardCharsets.UTF_8));
        ByteBuffer buffer = ByteBuffer.wrap(derivedKey);
        mAddend = buffer.getLong();
        // Multiplicand must be odd - otherwise we lose some bits of the Rabin fingerprint when
        // mixing
        mMultiplicand = buffer.getLong() | 1;
    }

    /**
     * Mixes the fingerprint with the derived key material. This is performed by adding part of the
     * derived key and multiplying by another part of the derived key (which is forced to be odd, so
     * that the operation is reversible).
     *
     * @param fingerprint A 64-bit Rabin fingerprint.
     * @return The mixed fingerprint.
     */
    long mix(long fingerprint) {
        return ((fingerprint + mAddend) * mMultiplicand);
    }

    /** The addend part of the derived key. */
    long getAddend() {
        return mAddend;
    }

    /** The multiplicand part of the derived key. */
    long getMultiplicand() {
        return mMultiplicand;
    }
}
