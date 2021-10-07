/*
 * Copyright (C) 2020 The Android Open Source Project
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
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;

import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

class AesEncryptionUtil {
    /** The algorithm used for the encryption of the key blob. */
    private static final String CIPHER_ALGO = "AES/GCM/NoPadding";

    private AesEncryptionUtil() {}

    static byte[] decrypt(SecretKey key, DataInputStream cipherStream) throws IOException {
        Objects.requireNonNull(key);
        Objects.requireNonNull(cipherStream);

        int ivSize = cipherStream.readInt();
        if (ivSize < 0 || ivSize > 32) {
            throw new IOException("IV out of range: " + ivSize);
        }
        byte[] iv = new byte[ivSize];
        cipherStream.readFully(iv);

        int rawCipherTextSize = cipherStream.readInt();
        if (rawCipherTextSize < 0) {
            throw new IOException("Invalid cipher text size: " + rawCipherTextSize);
        }

        byte[] rawCipherText = new byte[rawCipherTextSize];
        cipherStream.readFully(rawCipherText);

        final byte[] plainText;
        try {
            Cipher c = Cipher.getInstance(CIPHER_ALGO);
            c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, iv));
            plainText = c.doFinal(rawCipherText);
        } catch (NoSuchAlgorithmException | InvalidKeyException | BadPaddingException
                | IllegalBlockSizeException | NoSuchPaddingException
                | InvalidAlgorithmParameterException e) {
            throw new IOException("Could not decrypt cipher text", e);
        }

        return plainText;
    }

    static byte[] decrypt(SecretKey key, byte[] cipherText) throws IOException {
        Objects.requireNonNull(key);
        Objects.requireNonNull(cipherText);

        DataInputStream cipherStream = new DataInputStream(new ByteArrayInputStream(cipherText));
        return decrypt(key, cipherStream);
    }

    static byte[] encrypt(SecretKey key, byte[] plainText) throws IOException {
        Objects.requireNonNull(key);
        Objects.requireNonNull(plainText);

        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(bos);

        final byte[] cipherText;
        final byte[] iv;
        try {
            Cipher cipher = Cipher.getInstance(CIPHER_ALGO);
            cipher.init(Cipher.ENCRYPT_MODE, key);
            cipherText = cipher.doFinal(plainText);
            iv = cipher.getIV();
        } catch (NoSuchAlgorithmException | BadPaddingException | IllegalBlockSizeException
                | NoSuchPaddingException | InvalidKeyException e) {
            throw new IOException("Could not encrypt input data", e);
        }

        dos.writeInt(iv.length);
        dos.write(iv);
        dos.writeInt(cipherText.length);
        dos.write(cipherText);

        return bos.toByteArray();
    }
}
