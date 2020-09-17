/*
 * Copyright (C) 2019 The Android Open Source Project
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

import static com.android.internal.util.Preconditions.checkArgument;

import android.content.Context;
import android.util.ArrayMap;

import com.android.server.backup.encryption.protos.nano.WrappedKeyProto;
import com.android.server.backup.encryption.storage.BackupEncryptionDb;
import com.android.server.backup.encryption.storage.TertiaryKey;
import com.android.server.backup.encryption.storage.TertiaryKeysTable;

import com.google.protobuf.nano.CodedOutputByteBufferNano;

import java.io.IOException;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.Optional;

import javax.crypto.IllegalBlockSizeException;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.SecretKey;

/**
 * Stores backup package keys. Each application package has its own {@link SecretKey}, which is used
 * to encrypt the backup data. These keys are then wrapped by a master backup key, and stored in
 * their wrapped form on disk and on the backup server.
 *
 * <p>For now this code only implements writing to disk. Once the backup server is ready, it will be
 * extended to sync the keys there, also.
 */
public class TertiaryKeyStore {

    private final RecoverableKeyStoreSecondaryKey mSecondaryKey;
    private final BackupEncryptionDb mDatabase;

    /**
     * Creates an instance, using {@code secondaryKey} to wrap tertiary keys, and storing them in
     * the database.
     */
    public static TertiaryKeyStore newInstance(
            Context context, RecoverableKeyStoreSecondaryKey secondaryKey) {
        return new TertiaryKeyStore(secondaryKey, BackupEncryptionDb.newInstance(context));
    }

    private TertiaryKeyStore(
            RecoverableKeyStoreSecondaryKey secondaryKey, BackupEncryptionDb database) {
        mSecondaryKey = secondaryKey;
        mDatabase = database;
    }

    /**
     * Saves the given key.
     *
     * @param applicationName The package name of the application for which this key will be used to
     *     encrypt data. e.g., "com.example.app".
     * @param key The key.
     * @throws InvalidKeyException if the backup key is not capable of wrapping.
     * @throws IOException if there is an issue writing to the database.
     */
    public void save(String applicationName, SecretKey key)
            throws IOException, InvalidKeyException, IllegalBlockSizeException,
                    NoSuchPaddingException, NoSuchAlgorithmException {
        checkApplicationName(applicationName);

        byte[] keyBytes = getEncodedKey(KeyWrapUtils.wrap(mSecondaryKey.getSecretKey(), key));

        long pk;
        try {
            pk =
                    mDatabase
                            .getTertiaryKeysTable()
                            .addKey(
                                    new TertiaryKey(
                                            mSecondaryKey.getAlias(), applicationName, keyBytes));
        } finally {
            mDatabase.close();
        }

        if (pk == -1) {
            throw new IOException("Failed to commit to db");
        }
    }

    /**
     * Tries to load a key for the given application.
     *
     * @param applicationName The package name of the application, e.g. "com.example.app".
     * @return The key if it is exists, {@link Optional#empty()} ()} otherwise.
     * @throws InvalidKeyException if the backup key is not good for unwrapping.
     * @throws IOException if there is a problem loading the key from the database.
     */
    public Optional<SecretKey> load(String applicationName)
            throws IOException, InvalidKeyException, InvalidAlgorithmParameterException,
                    NoSuchAlgorithmException, NoSuchPaddingException {
        checkApplicationName(applicationName);

        Optional<TertiaryKey> keyFromDb;
        try {
            keyFromDb =
                    mDatabase
                            .getTertiaryKeysTable()
                            .getKey(mSecondaryKey.getAlias(), applicationName);
        } finally {
            mDatabase.close();
        }

        if (!keyFromDb.isPresent()) {
            return Optional.empty();
        }

        WrappedKeyProto.WrappedKey wrappedKey =
                WrappedKeyProto.WrappedKey.parseFrom(keyFromDb.get().getWrappedKeyBytes());
        return Optional.of(KeyWrapUtils.unwrap(mSecondaryKey.getSecretKey(), wrappedKey));
    }

    /**
     * Loads keys for all applications.
     *
     * @return All of the keys in a map keyed by package name.
     * @throws IOException if there is an issue loading from the database.
     * @throws InvalidKeyException if the backup key is not an appropriate key for unwrapping.
     */
    public Map<String, SecretKey> getAll()
            throws IOException, InvalidKeyException, InvalidAlgorithmParameterException,
                    NoSuchAlgorithmException, NoSuchPaddingException {
        Map<String, TertiaryKey> tertiaries;
        try {
            tertiaries = mDatabase.getTertiaryKeysTable().getAllKeys(mSecondaryKey.getAlias());
        } finally {
            mDatabase.close();
        }

        Map<String, SecretKey> unwrappedKeys = new ArrayMap<>();
        for (String applicationName : tertiaries.keySet()) {
            WrappedKeyProto.WrappedKey wrappedKey =
                    WrappedKeyProto.WrappedKey.parseFrom(
                            tertiaries.get(applicationName).getWrappedKeyBytes());
            unwrappedKeys.put(
                    applicationName, KeyWrapUtils.unwrap(mSecondaryKey.getSecretKey(), wrappedKey));
        }

        return unwrappedKeys;
    }

    /**
     * Adds all wrapped keys to the database.
     *
     * @throws IOException if an error occurred adding a wrapped key.
     */
    public void putAll(Map<String, WrappedKeyProto.WrappedKey> wrappedKeysByApplicationName)
            throws IOException {
        TertiaryKeysTable tertiaryKeysTable = mDatabase.getTertiaryKeysTable();
        try {

            for (String applicationName : wrappedKeysByApplicationName.keySet()) {
                byte[] keyBytes = getEncodedKey(wrappedKeysByApplicationName.get(applicationName));
                long primaryKey =
                        tertiaryKeysTable.addKey(
                                new TertiaryKey(
                                        mSecondaryKey.getAlias(), applicationName, keyBytes));

                if (primaryKey == -1) {
                    throw new IOException("Failed to commit to db");
                }
            }

        } finally {
            mDatabase.close();
        }
    }

    private static void checkApplicationName(String applicationName) {
        checkArgument(!applicationName.isEmpty(), "applicationName must not be empty string.");
        checkArgument(!applicationName.contains("/"), "applicationName must not contain slash.");
    }

    private byte[] getEncodedKey(WrappedKeyProto.WrappedKey key) throws IOException {
        byte[] buffer = new byte[key.getSerializedSize()];
        CodedOutputByteBufferNano out = CodedOutputByteBufferNano.newInstance(buffer);
        key.writeTo(out);
        return buffer;
    }
}
