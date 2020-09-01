/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.server.locksettings;

import java.io.IOException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;

import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

/**
 * Key used to encrypt and decrypt the {@link RebootEscrowData}.
 */
class RebootEscrowKey {

    /** The secret key will be of this format. */
    private static final String KEY_ALGO = "AES";

    /** The key size used for encrypting the reboot escrow data. */
    private static final int KEY_SIZE_BITS = 256;

    private final SecretKey mKey;

    private RebootEscrowKey(SecretKey key) {
        mKey = key;
    }

    static RebootEscrowKey fromKeyBytes(byte[] keyBytes) {
        return new RebootEscrowKey(new SecretKeySpec(keyBytes, KEY_ALGO));
    }

    static RebootEscrowKey generate() throws IOException {
        final SecretKey secretKey;
        try {
            KeyGenerator keyGenerator = KeyGenerator.getInstance(KEY_ALGO);
            keyGenerator.init(KEY_SIZE_BITS, new SecureRandom());
            secretKey = keyGenerator.generateKey();
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("Could not generate new secret key", e);
        }
        return new RebootEscrowKey(secretKey);
    }

    SecretKey getKey() {
        return mKey;
    }

    byte[] getKeyBytes() {
        return mKey.getEncoded();
    }
}
