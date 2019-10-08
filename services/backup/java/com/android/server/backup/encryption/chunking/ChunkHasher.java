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
 * limitations under the License
 */

package com.android.server.backup.encryption.chunking;

import com.android.server.backup.encryption.chunk.ChunkHash;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import javax.crypto.Mac;
import javax.crypto.SecretKey;

/** Computes the SHA-256 HMAC of a chunk of bytes. */
public class ChunkHasher {
    private static final String MAC_ALGORITHM = "HmacSHA256";

    private final SecretKey mSecretKey;

    /** Constructs a new hasher which computes the HMAC using the given secret key. */
    public ChunkHasher(SecretKey secretKey) {
        this.mSecretKey = secretKey;
    }

    /** Returns the SHA-256 over the given bytes. */
    public ChunkHash computeHash(byte[] plaintext) throws InvalidKeyException {
        try {
            Mac mac = Mac.getInstance(MAC_ALGORITHM);
            mac.init(mSecretKey);
            return new ChunkHash(mac.doFinal(plaintext));
        } catch (NoSuchAlgorithmException e) {
            // This can not happen - AES/GCM/NoPadding is available as part of the framework.
            throw new AssertionError(e);
        }
    }
}
