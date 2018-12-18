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

package com.android.server.backup.testing;

import java.security.NoSuchAlgorithmException;
import java.util.Random;

import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;

/** Helpers for crypto code tests. */
public class CryptoTestUtils {
    private static final String KEY_ALGORITHM = "AES";
    private static final int KEY_SIZE_BITS = 256;

    private CryptoTestUtils() {}

    public static SecretKey generateAesKey() throws NoSuchAlgorithmException {
        KeyGenerator keyGenerator = KeyGenerator.getInstance(KEY_ALGORITHM);
        keyGenerator.init(KEY_SIZE_BITS);
        return keyGenerator.generateKey();
    }

    /** Generates a byte array of size {@code n} containing random bytes. */
    public static byte[] generateRandomBytes(int n) {
        byte[] bytes = new byte[n];
        Random random = new Random();
        random.nextBytes(bytes);
        return bytes;
    }
}
