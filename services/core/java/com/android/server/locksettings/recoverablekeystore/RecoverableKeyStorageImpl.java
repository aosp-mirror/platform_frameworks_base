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

import android.security.keystore.AndroidKeyStoreProvider;
import android.security.keystore.KeyProtection;

import java.io.IOException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.UnrecoverableEntryException;

import javax.crypto.SecretKey;

/**
 * Implementation of {@link RecoverableKeyStorage} for a specific application.
 *
 * <p>Persists wrapped keys to disk, and loads raw keys into AndroidKeyStore.
 *
 * @hide
 */
public class RecoverableKeyStorageImpl implements RecoverableKeyStorage {
    private final KeyStore mKeyStore;

    /**
     * A new instance, storing recoverable keys for the given {@code userId}.
     *
     * @throws KeyStoreException if unable to load AndroidKeyStore.
     * @throws NoSuchProviderException if AndroidKeyStore is not in this version of Android. Should
     *     never occur.
     *
     * @hide
     */
    public static RecoverableKeyStorageImpl newInstance(int userId) throws KeyStoreException,
            NoSuchProviderException {
        KeyStore keyStore = AndroidKeyStoreProvider.getKeyStoreForUid(userId);
        return new RecoverableKeyStorageImpl(keyStore);
    }

    private RecoverableKeyStorageImpl(KeyStore keyStore) {
        mKeyStore = keyStore;
    }

    /**
     * Writes {@code wrappedKey} to disk, keyed by the application's uid and the {@code alias}.
     *
     * @throws IOException if an error occurred writing to disk.
     *
     * @hide
     */
    @Override
    public void persistToDisk(String alias, WrappedKey wrappedKey) throws IOException {
        // TODO(robertberry) Add implementation.
        throw new UnsupportedOperationException();
    }

    /**
     * Imports {@code key} into the application's AndroidKeyStore, keyed by {@code alias}.
     *
     * @param alias The alias of the key.
     * @param key The key.
     * @param keyProtection Protection params denoting what the key can be used for. (e.g., what
     *                      Cipher modes, whether for encrpyt/decrypt or signing, etc.)
     * @throws KeyStoreException if an error occurred loading the key into the AndroidKeyStore.
     *
     * @hide
     */
    @Override
    public void importIntoAndroidKeyStore(String alias, SecretKey key, KeyProtection keyProtection)
            throws KeyStoreException {
        mKeyStore.setEntry(alias, new KeyStore.SecretKeyEntry(key), keyProtection);
    }

    /**
     * Loads a key handle from the application's AndroidKeyStore.
     *
     * @param alias Alias of the key to load.
     * @return The key handle.
     * @throws KeyStoreException if an error occurred loading the key from AndroidKeyStore.
     *
     * @hide
     */
    @Override
    public SecretKey loadFromAndroidKeyStore(String alias)
            throws KeyStoreException, NoSuchAlgorithmException, UnrecoverableEntryException {
        return ((SecretKey) mKeyStore.getKey(alias, /*password=*/ null));
    }

    /**
     * Removes the entry with the given {@code alias} from the application's AndroidKeyStore.
     *
     * @throws KeyStoreException if an error occurred deleting the key from AndroidKeyStore.
     *
     * @hide
     */
    @Override
    public void removeFromAndroidKeyStore(String alias) throws KeyStoreException {
        mKeyStore.deleteEntry(alias);
    }
}
