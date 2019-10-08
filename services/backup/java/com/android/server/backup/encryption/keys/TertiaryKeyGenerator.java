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

package com.android.server.backup.encryption.keys;

import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;

import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;

/** 256-bit AES key generator. Each app should have its own separate AES key. */
public class TertiaryKeyGenerator {
    private static final int KEY_SIZE_BITS = 256;
    private static final String KEY_ALGORITHM = "AES";

    private final KeyGenerator mKeyGenerator;

    /** New instance generating keys using {@code secureRandom}. */
    public TertiaryKeyGenerator(SecureRandom secureRandom) {
        try {
            mKeyGenerator = KeyGenerator.getInstance(KEY_ALGORITHM);
            mKeyGenerator.init(KEY_SIZE_BITS, secureRandom);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(
                    "Impossible condition: JCE thinks it does not support AES.", e);
        }
    }

    /** Generates a new random AES key. */
    public SecretKey generate() {
        return mKeyGenerator.generateKey();
    }
}
