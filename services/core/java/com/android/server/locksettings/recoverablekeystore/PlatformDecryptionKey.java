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
 * Used to unwrap recoverable keys before syncing them with remote storage.
 *
 * <p>This is a private key stored in AndroidKeyStore. Has an associated generation ID, which is
 * stored with wrapped keys, allowing us to ensure the wrapped key has the same version as the
 * platform key.
 *
 * @hide
 */
public class PlatformDecryptionKey {

    private final int mGenerationId;
    private final AndroidKeyStoreSecretKey mKey;

    /**
     * A new instance.
     *
     * @param generationId The generation ID of the platform key.
     * @param key The key handle in AndroidKeyStore.
     *
     * @hide
     */
    public PlatformDecryptionKey(int generationId, AndroidKeyStoreSecretKey key) {
        mGenerationId = generationId;
        mKey = key;
    }

    /**
     * Returns the generation ID.
     *
     * @hide
     */
    public int getGenerationId() {
        return mGenerationId;
    }

    /**
     * Returns the actual key, which can be used to decrypt.
     *
     * @hide
     */
    public AndroidKeyStoreSecretKey getKey() {
        return mKey;
    }
}
