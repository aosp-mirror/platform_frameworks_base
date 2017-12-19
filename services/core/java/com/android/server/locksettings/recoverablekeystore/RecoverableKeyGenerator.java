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
import android.security.keystore.KeyProperties;
import android.security.keystore.KeyProtection;
import android.util.Log;

import java.io.IOException;
import java.security.InvalidKeyException;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.UnrecoverableEntryException;

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
    private static final String KEY_GENERATOR_ALGORITHM = "AES";
    private static final int KEY_SIZE_BITS = 256;

    /**
     * A new {@link RecoverableKeyGenerator} instance.
     *
     * @param platformKey Secret key used to wrap generated keys before persisting to disk.
     * @param recoverableKeyStorage Class that manages persisting wrapped keys to disk.
     * @throws NoSuchAlgorithmException if "AES" key generation or "AES/GCM/NoPadding" cipher is
     *     unavailable. Should never happen.
     *
     * @hide
     */
    public static RecoverableKeyGenerator newInstance(
            PlatformEncryptionKey platformKey, RecoverableKeyStorage recoverableKeyStorage)
            throws NoSuchAlgorithmException {
        // NB: This cannot use AndroidKeyStore as the provider, as we need access to the raw key
        // material, so that it can be synced to disk in encrypted form.
        KeyGenerator keyGenerator = KeyGenerator.getInstance(KEY_GENERATOR_ALGORITHM);
        return new RecoverableKeyGenerator(keyGenerator, platformKey, recoverableKeyStorage);
    }

    private final KeyGenerator mKeyGenerator;
    private final RecoverableKeyStorage mRecoverableKeyStorage;
    private final PlatformEncryptionKey mPlatformKey;

    private RecoverableKeyGenerator(
            KeyGenerator keyGenerator,
            PlatformEncryptionKey platformKey,
            RecoverableKeyStorage recoverableKeyStorage) {
        mKeyGenerator = keyGenerator;
        mRecoverableKeyStorage = recoverableKeyStorage;
        mPlatformKey = platformKey;
    }

    /**
     * Generates a 256-bit AES key with the given alias.
     *
     * <p>Stores in the AndroidKeyStore, as well as persisting in wrapped form to disk. It is
     * persisted to disk so that it can be synced remotely, and then recovered on another device.
     * The generated key allows encrypt/decrypt only using AES/GCM/NoPadding.
     *
     * <p>The key handle returned to the caller is a reference to the AndroidKeyStore key,
     * meaning that the caller is never able to access the raw, unencrypted key.
     *
     * @param alias The alias by which the key will be known in AndroidKeyStore.
     * @throws InvalidKeyException if the platform key cannot be used to wrap keys.
     * @throws IOException if there was an issue writing the wrapped key to the wrapped key store.
     * @throws UnrecoverableEntryException if could not retrieve key after putting it in
     *     AndroidKeyStore. This should not happen.
     * @return A handle to the AndroidKeyStore key.
     *
     * @hide
     */
    public SecretKey generateAndStoreKey(String alias) throws KeyStoreException,
            InvalidKeyException, IOException, UnrecoverableEntryException {
        mKeyGenerator.init(KEY_SIZE_BITS);
        SecretKey key = mKeyGenerator.generateKey();

        mRecoverableKeyStorage.importIntoAndroidKeyStore(
                alias,
                key,
                new KeyProtection.Builder(
                        KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .build());
        WrappedKey wrappedKey = WrappedKey.fromSecretKey(mPlatformKey, key);

        try {
            // Keep raw key material in memory for minimum possible time.
            key.destroy();
        } catch (DestroyFailedException e) {
            Log.w(TAG, "Could not destroy SecretKey.");
        }

        mRecoverableKeyStorage.persistToDisk(alias, wrappedKey);

        try {
            // Reload from the keystore, so that the caller is only provided with the handle of the
            // key, not the raw key material.
            return mRecoverableKeyStorage.loadFromAndroidKeyStore(alias);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(
                    "Impossible: NoSuchAlgorithmException when attempting to retrieve a key "
                            + "that has only just been stored in AndroidKeyStore.", e);
        }
    }
}
