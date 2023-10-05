/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.server.companion.transport;

import android.util.Slog;

import java.nio.ByteBuffer;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.SecretKey;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * This class uses Java Cryptography to encrypt and decrypt messages
 */
public class CryptoManager {

    private static final String TAG = "CDM_CryptoManager";
    private static final int SECRET_KEY_LENGTH = 32;
    private static final String ALGORITHM = "AES";
    private static final String TRANSFORMATION = "AES/CBC/PKCS7Padding";

    private final byte[] mPreSharedKey;
    private Cipher mEncryptCipher;
    private Cipher mDecryptCipher;

    private SecretKey mSecretKey;

    public CryptoManager(byte[] preSharedKey) {
        if (preSharedKey == null) {
            mPreSharedKey = Arrays.copyOf(new byte[0], SECRET_KEY_LENGTH);
        } else {
            mPreSharedKey = Arrays.copyOf(preSharedKey, SECRET_KEY_LENGTH);
        }
        mSecretKey = new SecretKeySpec(mPreSharedKey, ALGORITHM);
        try {
            mEncryptCipher = Cipher.getInstance(TRANSFORMATION);
            mEncryptCipher.init(Cipher.ENCRYPT_MODE, mSecretKey);
            mDecryptCipher = Cipher.getInstance(TRANSFORMATION);
        } catch (NoSuchPaddingException | NoSuchAlgorithmException | InvalidKeyException e) {
            Slog.e(TAG, e.getMessage());
        }
    }

    /**
     * Output format: | iv length (int) | iv | encrypted bytes length (int) | encrypted bytes |
     */
    public byte[] encrypt(byte[] input) {
        try {
            if (mEncryptCipher == null) {
                return null;
            }

            byte[] encryptedBytes = mEncryptCipher.doFinal(input);
            ByteBuffer buffer = ByteBuffer.allocate(
                            4 + mEncryptCipher.getIV().length + 4 + encryptedBytes.length)
                    .putInt(mEncryptCipher.getIV().length)
                    .put(mEncryptCipher.getIV())
                    .putInt(encryptedBytes.length)
                    .put(encryptedBytes);
            return buffer.array();
        } catch (IllegalBlockSizeException | BadPaddingException e) {
            Slog.e(TAG, e.getMessage());
            return null;
        }
    }

    /**
     * Input format: | iv length (int) | iv | encrypted bytes length (int) | encrypted bytes |
     */
    public byte[] decrypt(byte[] input) {
        ByteBuffer buffer = ByteBuffer.wrap(input);
        byte[] iv = new byte[buffer.getInt()];
        buffer.get(iv);
        byte[] encryptedBytes = new byte[buffer.getInt()];
        buffer.get(encryptedBytes);
        try {
            mDecryptCipher.init(Cipher.DECRYPT_MODE, getKey(), new IvParameterSpec(iv));
            return mDecryptCipher.doFinal(encryptedBytes);
        } catch (InvalidKeyException | InvalidAlgorithmParameterException
                 | IllegalBlockSizeException | BadPaddingException e) {
            Slog.e(TAG, e.getMessage());
            return null;
        }
    }

    private SecretKey getKey() {
        if (mSecretKey != null) {
            return mSecretKey;
        }
        mSecretKey = new SecretKeySpec(mPreSharedKey, ALGORITHM);
        return mSecretKey;
    }
}
