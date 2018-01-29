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

package com.android.server.locksettings.recoverablekeystore.storage;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.security.keystore.recovery.KeyChainProtectionParams;
import android.security.keystore.recovery.KeyChainSnapshot;
import android.security.keystore.recovery.KeyDerivationParams;
import android.security.keystore.recovery.WrappedApplicationKey;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.ArrayUtils;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * This class provides helper methods serialize and deserialize {@link KeyChainSnapshot}.
 *
 * <p> It is necessary since {@link android.os.Parcelable} is not designed for persistent storage.
 *
 * <p> For every list, length is stored before the elements.
 *
 */
public class PersistentKeyChainSnapshot {
    private static final int VERSION = 1;
    private static final int NULL_LIST_LENGTH = -1;

    private DataInputStream mInput;
    private DataOutputStream mOut;
    private ByteArrayOutputStream mOutStream;

    @VisibleForTesting
    PersistentKeyChainSnapshot() {
    }

    @VisibleForTesting
    void initReader(byte[] input) {
        mInput = new DataInputStream(new ByteArrayInputStream(input));
    }

    @VisibleForTesting
    void initWriter() {
        mOutStream = new ByteArrayOutputStream();
        mOut = new DataOutputStream(mOutStream);
    }

    @VisibleForTesting
    byte[] getOutput() {
        return mOutStream.toByteArray();
    }

    /**
     * Converts {@link KeyChainSnapshot} to its binary representation.
     *
     * @param snapshot The snapshot.
     *
     * @throws IOException if serialization failed.
     */
    public static byte[] serialize(@NonNull KeyChainSnapshot snapshot) throws IOException {
        PersistentKeyChainSnapshot writer = new PersistentKeyChainSnapshot();
        writer.initWriter();
        writer.writeInt(VERSION);
        writer.writeKeyChainSnapshot(snapshot);
        return writer.getOutput();
    }

    /**
     * deserializes {@link KeyChainSnapshot}.
     *
     * @input input - byte array produced by {@link serialize} method.
     * @throws IOException if parsing failed.
     */
    public static @NonNull KeyChainSnapshot deserialize(@NonNull byte[] input)
            throws IOException {
        PersistentKeyChainSnapshot reader = new PersistentKeyChainSnapshot();
        reader.initReader(input);
        try {
            int version = reader.readInt();
            if (version != VERSION) {
                throw new IOException("Unsupported version " + version);
            }
            return reader.readKeyChainSnapshot();
        } catch (IOException e) {
            throw new IOException("Malformed KeyChainSnapshot", e);
        }
    }

    /**
     * Must be in sync with {@link KeyChainSnapshot.writeToParcel}
     */
    @VisibleForTesting
    void writeKeyChainSnapshot(KeyChainSnapshot snapshot) throws IOException {
        writeInt(snapshot.getSnapshotVersion());
        writeProtectionParamsList(snapshot.getKeyChainProtectionParams());
        writeBytes(snapshot.getEncryptedRecoveryKeyBlob());
        writeKeysList(snapshot.getWrappedApplicationKeys());

        writeInt(snapshot.getMaxAttempts());
        writeLong(snapshot.getCounterId());
        writeBytes(snapshot.getServerParams());
        writeBytes(snapshot.getTrustedHardwarePublicKey());
    }

    @VisibleForTesting
    KeyChainSnapshot readKeyChainSnapshot() throws IOException {
        int snapshotVersion = readInt();
        List<KeyChainProtectionParams> protectionParams = readProtectionParamsList();
        byte[] encryptedRecoveryKey = readBytes();
        List<WrappedApplicationKey> keysList = readKeysList();

        int maxAttempts = readInt();
        long conterId = readLong();
        byte[] serverParams = readBytes();
        byte[] trustedHardwarePublicKey = readBytes();

        return new KeyChainSnapshot.Builder()
                .setSnapshotVersion(snapshotVersion)
                .setKeyChainProtectionParams(protectionParams)
                .setEncryptedRecoveryKeyBlob(encryptedRecoveryKey)
                .setWrappedApplicationKeys(keysList)
                .setMaxAttempts(maxAttempts)
                .setCounterId(conterId)
                .setServerParams(serverParams)
                .setTrustedHardwarePublicKey(trustedHardwarePublicKey)
                .build();
    }

    @VisibleForTesting
    void writeProtectionParamsList(
            @NonNull List<KeyChainProtectionParams> ProtectionParamsList) throws IOException {
        writeInt(ProtectionParamsList.size());
        for (KeyChainProtectionParams protectionParams : ProtectionParamsList) {
            writeProtectionParams(protectionParams);
        }
    }

    @VisibleForTesting
    List<KeyChainProtectionParams> readProtectionParamsList() throws IOException {
        int length = readInt();
        List<KeyChainProtectionParams> result = new ArrayList<>(length);
        for (int i = 0; i < length; i++) {
            result.add(readProtectionParams());
        }
        return result;
    }

    /**
     * Must be in sync with {@link KeyChainProtectionParams.writeToParcel}
     */
    @VisibleForTesting
    void writeProtectionParams(@NonNull KeyChainProtectionParams protectionParams)
            throws IOException {
        if (!ArrayUtils.isEmpty(protectionParams.getSecret())) {
            // Extra security check.
            throw new RuntimeException("User generated secret should not be stored");
        }
        writeInt(protectionParams.getUserSecretType());
        writeInt(protectionParams.getLockScreenUiFormat());
        writeKeyDerivationParams(protectionParams.getKeyDerivationParams());
        writeBytes(protectionParams.getSecret());
    }

    @VisibleForTesting
    KeyChainProtectionParams readProtectionParams() throws IOException {
        int userSecretType = readInt();
        int lockScreenUiFormat = readInt();
        KeyDerivationParams derivationParams = readKeyDerivationParams();
        byte[] secret = readBytes();
        return new KeyChainProtectionParams.Builder()
                .setUserSecretType(userSecretType)
                .setLockScreenUiFormat(lockScreenUiFormat)
                .setKeyDerivationParams(derivationParams)
                .setSecret(secret)
                .build();
    }

    /**
     * Must be in sync with {@link KeyDerivationParams.writeToParcel}
     */
    @VisibleForTesting
    void writeKeyDerivationParams(@NonNull KeyDerivationParams Params) throws IOException {
        writeInt(Params.getAlgorithm());
        writeBytes(Params.getSalt());
    }

    @VisibleForTesting
    KeyDerivationParams readKeyDerivationParams() throws IOException {
        int algorithm = readInt();
        byte[] salt = readBytes();
        return KeyDerivationParams.createSha256Params(salt);
    }

    @VisibleForTesting
    void writeKeysList(@NonNull List<WrappedApplicationKey> applicationKeys) throws IOException {
        writeInt(applicationKeys.size());
        for (WrappedApplicationKey keyEntry : applicationKeys) {
            writeKeyEntry(keyEntry);
        }
    }

    @VisibleForTesting
    List<WrappedApplicationKey> readKeysList() throws IOException {
        int length = readInt();
        List<WrappedApplicationKey> result = new ArrayList<>(length);
        for (int i = 0; i < length; i++) {
            result.add(readKeyEntry());
        }
        return result;
    }

    /**
     * Must be in sync with {@link WrappedApplicationKey.writeToParcel}
     */
    @VisibleForTesting
    void writeKeyEntry(@NonNull WrappedApplicationKey keyEntry) throws IOException {
        mOut.writeUTF(keyEntry.getAlias());
        writeBytes(keyEntry.getEncryptedKeyMaterial());
        writeBytes(keyEntry.getAccount());
    }

    @VisibleForTesting
    WrappedApplicationKey readKeyEntry() throws IOException {
        String alias = mInput.readUTF();
        byte[] keyMaterial = readBytes();
        byte[] account = readBytes();
        return new WrappedApplicationKey.Builder()
                .setAlias(alias)
                .setEncryptedKeyMaterial(keyMaterial)
                .setAccount(account)
                .build();
    }

    @VisibleForTesting
    void writeInt(int value) throws IOException {
        mOut.writeInt(value);
    }

    @VisibleForTesting
    int readInt() throws IOException {
        return mInput.readInt();
    }

    @VisibleForTesting
    void writeLong(long value) throws IOException {
        mOut.writeLong(value);
    }

    @VisibleForTesting
    long readLong() throws IOException {
        return mInput.readLong();
    }

    @VisibleForTesting
    void writeBytes(@Nullable byte[] value) throws IOException {
        if (value == null) {
            writeInt(NULL_LIST_LENGTH);
            return;
        }
        writeInt(value.length);
        mOut.write(value, 0, value.length);
    }

    /**
     * Reads @code{byte[]} from current position. Converts {@code null} to an empty array.
     */
    @VisibleForTesting
    @NonNull byte[] readBytes() throws IOException {
        int length = readInt();
        if (length == NULL_LIST_LENGTH) {
            return new byte[]{};
        }
        byte[] result = new byte[length];
        mInput.read(result, 0, result.length);
        return result;
    }
}

