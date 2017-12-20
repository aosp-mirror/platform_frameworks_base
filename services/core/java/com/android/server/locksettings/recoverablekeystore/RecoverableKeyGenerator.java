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

import android.security.keystore.KeyProperties;
import android.security.keystore.KeyProtection;
import android.util.Log;

import com.android.server.locksettings.recoverablekeystore.storage.RecoverableKeyStoreDb;

import java.security.InvalidKeyException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.util.Locale;

import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.security.auth.DestroyFailedException;

/**
 * Generates keys and stores them both in AndroidKeyStore and on disk, in wrapped form.
 *
 * <p>Generates 256-bit AES keys, which can be used for encrypt / decrypt with AES/GCM/NoPadding.
 * They are synced to disk wrapped by a platform key. This allows them to be exported to a remote
 * service.
 *
 * @hide
 */
public class RecoverableKeyGenerator {
    private static final String TAG = "RecoverableKeyGenerator";

    private static final int RESULT_CANNOT_INSERT_ROW = -1;
    private static final String KEY_GENERATOR_ALGORITHM = "AES";
    private static final int KEY_SIZE_BITS = 256;

    /**
     * A new {@link RecoverableKeyGenerator} instance.
     *
     * @throws NoSuchAlgorithmException if "AES" key generation or "AES/GCM/NoPadding" cipher is
     *     unavailable. Should never happen.
     *
     * @hide
     */
    public static RecoverableKeyGenerator newInstance(RecoverableKeyStoreDb database)
            throws NoSuchAlgorithmException {
        // NB: This cannot use AndroidKeyStore as the provider, as we need access to the raw key
        // material, so that it can be synced to disk in encrypted form.
        KeyGenerator keyGenerator = KeyGenerator.getInstance(KEY_GENERATOR_ALGORITHM);
        return new RecoverableKeyGenerator(
                keyGenerator, database, new AndroidKeyStoreFactory.Impl());
    }

    private final KeyGenerator mKeyGenerator;
    private final RecoverableKeyStoreDb mDatabase;
    private final AndroidKeyStoreFactory mAndroidKeyStoreFactory;

    private RecoverableKeyGenerator(
            KeyGenerator keyGenerator,
            RecoverableKeyStoreDb recoverableKeyStoreDb,
            AndroidKeyStoreFactory androidKeyStoreFactory) {
        mKeyGenerator = keyGenerator;
        mAndroidKeyStoreFactory = androidKeyStoreFactory;
        mDatabase = recoverableKeyStoreDb;
    }

    /**
     * Generates a 256-bit AES key with the given alias.
     *
     * <p>Stores in the AndroidKeyStore, as well as persisting in wrapped form to disk. It is
     * persisted to disk so that it can be synced remotely, and then recovered on another device.
     * The generated key allows encrypt/decrypt only using AES/GCM/NoPadding.
     *
     * @param platformKey The user's platform key, with which to wrap the generated key.
     * @param uid The uid of the application that will own the key.
     * @param alias The alias by which the key will be known in AndroidKeyStore.
     * @throws RecoverableKeyStorageException if there is some error persisting the key either to
     *     the AndroidKeyStore or the database.
     * @throws KeyStoreException if there is a KeyStore error wrapping the generated key.
     * @throws InvalidKeyException if the platform key cannot be used to wrap keys.
     *
     * @hide
     */
    public void generateAndStoreKey(PlatformEncryptionKey platformKey, int uid, String alias)
            throws RecoverableKeyStorageException, KeyStoreException, InvalidKeyException {
        mKeyGenerator.init(KEY_SIZE_BITS);
        SecretKey key = mKeyGenerator.generateKey();

        KeyStoreProxy keyStore;

        try {
            keyStore = mAndroidKeyStoreFactory.getKeyStoreForUid(uid);
        } catch (NoSuchProviderException e) {
            throw new RecoverableKeyStorageException(
                    "Impossible: AndroidKeyStore provider did not exist", e);
        } catch (KeyStoreException e) {
            throw new RecoverableKeyStorageException(
                    "Could not load AndroidKeyStore for " + uid, e);
        }

        try {
            keyStore.setEntry(
                    alias,
                    new KeyStore.SecretKeyEntry(key),
                    new KeyProtection.Builder(
                            KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                            .build());
        } catch (KeyStoreException e) {
            throw new RecoverableKeyStorageException(
                    "Failed to load (%d, %s) into AndroidKeyStore", e);
        }

        WrappedKey wrappedKey = WrappedKey.fromSecretKey(platformKey, key);
        try {
            // Keep raw key material in memory for minimum possible time.
            key.destroy();
        } catch (DestroyFailedException e) {
            Log.w(TAG, "Could not destroy SecretKey.");
        }
        long result = mDatabase.insertKey(uid, alias, wrappedKey);

        if (result == RESULT_CANNOT_INSERT_ROW) {
            // Attempt to clean up
            try {
                keyStore.deleteEntry(alias);
            } catch (KeyStoreException e) {
                Log.e(TAG, String.format(Locale.US,
                        "Could not delete recoverable key (%d, %s) from "
                                + "AndroidKeyStore after error writing to database.", uid, alias),
                        e);
            }

            throw new RecoverableKeyStorageException(
                    String.format(
                            Locale.US, "Failed writing (%d, %s) to database.", uid, alias));
        }
    }
}
