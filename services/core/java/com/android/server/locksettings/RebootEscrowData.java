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

package com.android.server.locksettings;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Objects;

import javax.crypto.SecretKey;

/**
 * Holds the data necessary to complete a reboot escrow of the Synthetic Password.
 */
class RebootEscrowData {
    /**
     * This is the current version of the escrow data format. This should be incremented if the
     * format on disk is changed.
     */
    private static final int CURRENT_VERSION = 2;

    /**
    * This is the legacy version of the escrow data format for R builds. The escrow data is only
    * encrypted by the escrow key, without additional wrap of another key from keystore.
    */
    private static final int LEGACY_SINGLE_ENCRYPTED_VERSION = 1;

    private RebootEscrowData(byte spVersion, byte[] syntheticPassword, byte[] blob,
            RebootEscrowKey key) {
        mSpVersion = spVersion;
        mSyntheticPassword = syntheticPassword;
        mBlob = blob;
        mKey = key;
    }

    private final byte mSpVersion;
    private final byte[] mSyntheticPassword;
    private final byte[] mBlob;
    private final RebootEscrowKey mKey;

    public byte getSpVersion() {
        return mSpVersion;
    }

    public byte[] getSyntheticPassword() {
        return mSyntheticPassword;
    }

    public byte[] getBlob() {
        return mBlob;
    }

    public RebootEscrowKey getKey() {
        return mKey;
    }

    private static byte[] decryptBlobCurrentVersion(SecretKey kk, RebootEscrowKey ks,
            DataInputStream dis) throws IOException {
        if (kk == null) {
            throw new IOException("Failed to find wrapper key in keystore, cannot decrypt the"
                    + " escrow data");
        }

        // Decrypt the blob with the key from keystore first, then decrypt again with the reboot
        // escrow key.
        byte[] ksEncryptedBlob = AesEncryptionUtil.decrypt(kk, dis);
        return AesEncryptionUtil.decrypt(ks.getKey(), ksEncryptedBlob);
    }

    static RebootEscrowData fromEncryptedData(RebootEscrowKey ks, byte[] blob, SecretKey kk)
            throws IOException {
        Objects.requireNonNull(ks);
        Objects.requireNonNull(blob);

        DataInputStream dis = new DataInputStream(new ByteArrayInputStream(blob));
        int version = dis.readInt();
        byte spVersion = dis.readByte();
        switch (version) {
            case CURRENT_VERSION: {
                byte[] syntheticPassword = decryptBlobCurrentVersion(kk, ks, dis);
                return new RebootEscrowData(spVersion, syntheticPassword, blob, ks);
            }
            case LEGACY_SINGLE_ENCRYPTED_VERSION: {
                // Decrypt the blob with the escrow key directly.
                byte[] syntheticPassword = AesEncryptionUtil.decrypt(ks.getKey(), dis);
                return new RebootEscrowData(spVersion, syntheticPassword, blob, ks);
            }
            default:
                throw new IOException("Unsupported version " + version);
        }
    }

    static RebootEscrowData fromSyntheticPassword(RebootEscrowKey ks, byte spVersion,
            byte[] syntheticPassword, SecretKey kk)
            throws IOException {
        Objects.requireNonNull(syntheticPassword);

        // Encrypt synthetic password with the escrow key first; then encrypt the blob again with
        // the key from keystore.
        byte[] ksEncryptedBlob = AesEncryptionUtil.encrypt(ks.getKey(), syntheticPassword);
        byte[] kkEncryptedBlob = AesEncryptionUtil.encrypt(kk, ksEncryptedBlob);

        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(bos);

        dos.writeInt(CURRENT_VERSION);
        dos.writeByte(spVersion);
        dos.write(kkEncryptedBlob);

        return new RebootEscrowData(spVersion, syntheticPassword, bos.toByteArray(), ks);
    }
}
