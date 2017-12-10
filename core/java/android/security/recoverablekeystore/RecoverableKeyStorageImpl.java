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

package android.security.recoverablekeystore;

import android.security.keystore.KeyProtection;

import java.io.IOException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.UnrecoverableEntryException;
import java.security.cert.CertificateException;

import javax.crypto.SecretKey;

/**
 * Implementation of {@link RecoverableKeyStorage}.
 *
 * <p>Persists wrapped keys to disk, and loads raw keys into AndroidKeyStore.
 *
 * @hide
 */
public class RecoverableKeyStorageImpl implements RecoverableKeyStorage {
    private static final String ANDROID_KEY_STORE_PROVIDER = "AndroidKeyStore";

    private final KeyStore mKeyStore;

    /**
     * A new instance.
     *
     * @throws KeyStoreException if unable to load AndroidKeyStore.
     *
     * @hide
     */
    public static RecoverableKeyStorageImpl newInstance() throws KeyStoreException {
        KeyStore keyStore = KeyStore.getInstance(ANDROID_KEY_STORE_PROVIDER);
        try {
            keyStore.load(/*param=*/ null);
        } catch (CertificateException | IOException | NoSuchAlgorithmException e) {
            // Should never happen.
            throw new KeyStoreException("Unable to load keystore.", e);
        }
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
     * Imports {@code key} into AndroidKeyStore, keyed by the application's uid and the
     * {@code alias}.
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
     * Loads a key handle from AndroidKeyStore.
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
        return ((KeyStore.SecretKeyEntry) mKeyStore.getEntry(alias, /*protParam=*/ null))
                .getSecretKey();
    }
}
