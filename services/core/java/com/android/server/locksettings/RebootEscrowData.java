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

import com.android.internal.util.Preconditions;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;

import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.spec.IvParameterSpec;

/**
 * Holds the data necessary to complete a reboot escrow of the Synthetic Password.
 */
class RebootEscrowData {
    /**
     * This is the current version of the escrow data format. This should be incremented if the
     * format on disk is changed.
     */
    private static final int CURRENT_VERSION = 1;

    /** The algorithm used for the encryption of the key blob. */
    private static final String CIPHER_ALGO = "AES/GCM/NoPadding";

    private RebootEscrowData(byte spVersion, byte[] iv, byte[] syntheticPassword, byte[] blob,
            RebootEscrowKey key) {
        mSpVersion = spVersion;
        mIv = iv;
        mSyntheticPassword = syntheticPassword;
        mBlob = blob;
        mKey = key;
    }

    private final byte mSpVersion;
    private final byte[] mIv;
    private final byte[] mSyntheticPassword;
    private final byte[] mBlob;
    private final RebootEscrowKey mKey;

    public byte getSpVersion() {
        return mSpVersion;
    }

    public byte[] getIv() {
        return mIv;
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

    static RebootEscrowData fromEncryptedData(RebootEscrowKey key, byte[] blob)
            throws IOException {
        Preconditions.checkNotNull(key);
        Preconditions.checkNotNull(blob);

        DataInputStream dis = new DataInputStream(new ByteArrayInputStream(blob));
        int version = dis.readInt();
        if (version != CURRENT_VERSION) {
            throw new IOException("Unsupported version " + version);
        }

        byte spVersion = dis.readByte();

        int ivSize = dis.readInt();
        if (ivSize < 0 || ivSize > 32) {
            throw new IOException("IV out of range: " + ivSize);
        }
        byte[] iv = new byte[ivSize];
        dis.readFully(iv);

        int cipherTextSize = dis.readInt();
        if (cipherTextSize < 0) {
            throw new IOException("Invalid cipher text size: " + cipherTextSize);
        }

        byte[] cipherText = new byte[cipherTextSize];
        dis.readFully(cipherText);

        final byte[] syntheticPassword;
        try {
            Cipher c = Cipher.getInstance(CIPHER_ALGO);
            c.init(Cipher.DECRYPT_MODE, key.getKey(), new IvParameterSpec(iv));
            syntheticPassword = c.doFinal(cipherText);
        } catch (NoSuchAlgorithmException | InvalidKeyException | BadPaddingException
                | IllegalBlockSizeException | NoSuchPaddingException
                | InvalidAlgorithmParameterException e) {
            throw new IOException("Could not decrypt ciphertext", e);
        }

        return new RebootEscrowData(spVersion, iv, syntheticPassword, blob, key);
    }

    static RebootEscrowData fromSyntheticPassword(RebootEscrowKey key, byte spVersion,
            byte[] syntheticPassword)
            throws IOException {
        Preconditions.checkNotNull(syntheticPassword);

        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(bos);

        final byte[] cipherText;
        final byte[] iv;
        try {
            Cipher cipher = Cipher.getInstance(CIPHER_ALGO);
            cipher.init(Cipher.ENCRYPT_MODE, key.getKey());
            cipherText = cipher.doFinal(syntheticPassword);
            iv = cipher.getIV();
        } catch (NoSuchAlgorithmException | BadPaddingException | IllegalBlockSizeException
                | NoSuchPaddingException | InvalidKeyException e) {
            throw new IOException("Could not encrypt reboot escrow data", e);
        }

        dos.writeInt(CURRENT_VERSION);
        dos.writeByte(spVersion);
        dos.writeInt(iv.length);
        dos.write(iv);
        dos.writeInt(cipherText.length);
        dos.write(cipherText);

        return new RebootEscrowData(spVersion, iv, syntheticPassword, bos.toByteArray(),
                key);
    }
}
