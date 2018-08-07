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

import android.annotation.NonNull;

import com.android.server.locksettings.recoverablekeystore.storage.RecoverableKeyStoreDb;

import java.security.InvalidKeyException;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import android.util.Log;

import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

/**
 * Generates/imports keys and stores them both in AndroidKeyStore and on disk, in wrapped form.
 *
 * <p>Generates/imports 256-bit AES keys, which can be used for encrypt and decrypt with AES-GCM.
 * They are synced to disk wrapped by a platform key. This allows them to be exported to a remote
 * service.
 *
 * @hide
 */
public class RecoverableKeyGenerator {

    private static final String TAG = "PlatformKeyGen";
    private static final int RESULT_CANNOT_INSERT_ROW = -1;
    private static final String SECRET_KEY_ALGORITHM = "AES";

    static final int KEY_SIZE_BITS = 256;

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
        KeyGenerator keyGenerator = KeyGenerator.getInstance(SECRET_KEY_ALGORITHM);
        return new RecoverableKeyGenerator(keyGenerator, database);
    }

    private final KeyGenerator mKeyGenerator;
    private final RecoverableKeyStoreDb mDatabase;

    private RecoverableKeyGenerator(
            KeyGenerator keyGenerator,
            RecoverableKeyStoreDb recoverableKeyStoreDb) {
        mKeyGenerator = keyGenerator;
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
     * @param userId The user ID of the profile to which the calling app belongs.
     * @param uid The uid of the application that will own the key.
     * @param alias The alias by which the key will be known in the recoverable key store.
     * @throws RecoverableKeyStorageException if there is some error persisting the key either to
     *     the database.
     * @throws KeyStoreException if there is a KeyStore error wrapping the generated key.
     * @throws InvalidKeyException if the platform key cannot be used to wrap keys.
     *
     * @hide
     */
    public byte[] generateAndStoreKey(
            PlatformEncryptionKey platformKey, int userId, int uid, String alias)
            throws RecoverableKeyStorageException, KeyStoreException, InvalidKeyException {
        mKeyGenerator.init(KEY_SIZE_BITS);
        SecretKey key = mKeyGenerator.generateKey();

        WrappedKey wrappedKey = WrappedKey.fromSecretKey(platformKey, key);
        long result = mDatabase.insertKey(userId, uid, alias, wrappedKey);

        if (result == RESULT_CANNOT_INSERT_ROW) {
            throw new RecoverableKeyStorageException(
                    String.format(
                            Locale.US, "Failed writing (%d, %s) to database.", uid, alias));
        }

        long updatedRows = mDatabase.setShouldCreateSnapshot(userId, uid, true);
        if (updatedRows < 0) {
            Log.e(TAG, "Failed to set the shoudCreateSnapshot flag in the local DB.");
        }

        return key.getEncoded();
    }

    /**
     * Imports an AES key with the given alias.
     *
     * <p>Stores in the AndroidKeyStore, as well as persisting in wrapped form to disk. It is
     * persisted to disk so that it can be synced remotely, and then recovered on another device.
     * The generated key allows encrypt/decrypt only using AES/GCM/NoPadding.
     *
     * @param platformKey The user's platform key, with which to wrap the generated key.
     * @param userId The user ID of the profile to which the calling app belongs.
     * @param uid The uid of the application that will own the key.
     * @param alias The alias by which the key will be known in the recoverable key store.
     * @param keyBytes The raw bytes of the AES key to be imported.
     * @throws RecoverableKeyStorageException if there is some error persisting the key either to
     *     the database.
     * @throws KeyStoreException if there is a KeyStore error wrapping the generated key.
     * @throws InvalidKeyException if the platform key cannot be used to wrap keys.
     *
     * @hide
     */
    public void importKey(
            @NonNull PlatformEncryptionKey platformKey, int userId, int uid, @NonNull String alias,
            @NonNull byte[] keyBytes)
            throws RecoverableKeyStorageException, KeyStoreException, InvalidKeyException {
        SecretKey key = new SecretKeySpec(keyBytes, SECRET_KEY_ALGORITHM);

        WrappedKey wrappedKey = WrappedKey.fromSecretKey(platformKey, key);
        long result = mDatabase.insertKey(userId, uid, alias, wrappedKey);

        if (result == RESULT_CANNOT_INSERT_ROW) {
            throw new RecoverableKeyStorageException(
                    String.format(
                            Locale.US, "Failed writing (%d, %s) to database.", uid, alias));
        }

        mDatabase.setShouldCreateSnapshot(userId, uid, true);
    }
}
