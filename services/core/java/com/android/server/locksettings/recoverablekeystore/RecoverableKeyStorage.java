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

import android.security.keystore.KeyProtection;

import java.io.IOException;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.UnrecoverableEntryException;

import javax.crypto.SecretKey;

/**
 * Stores wrapped keys to disk, so they can be synced on the next screen unlock event.
 *
 * @hide
 */
public interface RecoverableKeyStorage {

    /**
     * Writes {@code wrappedKey} to disk, keyed by the application's uid and the {@code alias}.
     *
     * @throws IOException if an error occurred writing to disk.
     *
     * @hide
     */
    void persistToDisk(String alias, WrappedKey wrappedKey) throws IOException;

    /**
     * Imports {@code key} into AndroidKeyStore, keyed by the application's uid and
     * the {@code alias}.
     *
     * @param alias The alias of the key.
     * @param key The key.
     * @param keyProtection Protection params denoting what the key can be used for. (e.g., what
     *                      Cipher modes, whether for encrpyt/decrypt or signing, etc.)
     * @throws KeyStoreException if an error occurred loading the key into the AndroidKeyStore.
     *
     * @hide
     */
    void importIntoAndroidKeyStore(String alias, SecretKey key, KeyProtection keyProtection) throws
            KeyStoreException;

    /**
     * Loads a key handle from AndroidKeyStore.
     *
     * @param alias Alias of the key to load.
     * @return The key handle.
     * @throws KeyStoreException if an error occurred loading the key from AndroidKeyStore.
     *
     * @hide
     */
    SecretKey loadFromAndroidKeyStore(String alias) throws KeyStoreException,
            NoSuchAlgorithmException,
            UnrecoverableEntryException;
}
