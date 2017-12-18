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

package com.android.server.locksettings.recoverablekeystore;

import android.security.keystore.AndroidKeyStoreSecretKey;

/**
 * Private key stored in AndroidKeyStore. Used to wrap recoverable keys before writing them to disk.
 *
 * <p>Identified by a generation ID, which increments whenever a new platform key is generated. A
 * new key must be generated whenever the user disables their lock screen, as the decryption key is
 * tied to lock-screen authentication.
 *
 * <p>One current platform key exists per profile on the device. (As each must be tied to a
 * different user's lock screen.)
 *
 * @hide
 */
public class PlatformEncryptionKey {

    private final int mGenerationId;
    private final AndroidKeyStoreSecretKey mKey;

    /**
     * A new instance.
     *
     * @param generationId The generation ID of the key.
     * @param key The secret key handle. Can be used to encrypt WITHOUT requiring screen unlock.
     */
    public PlatformEncryptionKey(int generationId, AndroidKeyStoreSecretKey key) {
        mGenerationId = generationId;
        mKey = key;
    }

    /**
     * Returns the generation ID of the key.
     */
    public int getGenerationId() {
        return mGenerationId;
    }

    /**
     * Returns the actual key, which can only be used to encrypt.
     */
    public AndroidKeyStoreSecretKey getKey() {
        return mKey;
    }
}
