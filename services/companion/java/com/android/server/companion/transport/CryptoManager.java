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

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Slog;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.UnrecoverableEntryException;
import java.security.cert.CertificateException;

import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.KeyGenerator;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.SecretKey;
import javax.crypto.spec.IvParameterSpec;

/**
 * This class can be used to encrypt and decrypt bytes using Android Cryptography
 */
public class CryptoManager {

    private static final String TAG = "CDM_CryptoManager";

    private static final String KEY_STORE_ALIAS = "cdm_secret";
    private static final String ALGORITHM = KeyProperties.KEY_ALGORITHM_AES;
    private static final String BLOCK_MODE = KeyProperties.BLOCK_MODE_CBC;
    private static final String PADDING = KeyProperties.ENCRYPTION_PADDING_PKCS7;
    private static final String TRANSFORMATION = ALGORITHM + "/" + BLOCK_MODE + "/" + PADDING;

    private final KeyStore mKeyStore;

    public CryptoManager() {
        // Initialize KeyStore
        try {
            mKeyStore = KeyStore.getInstance("AndroidKeyStore");
            mKeyStore.load(null);
        } catch (KeyStoreException | IOException | NoSuchAlgorithmException
                 | CertificateException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Output format: | iv length (int) | iv | encrypted bytes length (int) | encrypted bytes |
     */
    public byte[] encrypt(byte[] input) {
        try {
            // Encrypt using Cipher
            Cipher encryptCipher = Cipher.getInstance(TRANSFORMATION);
            encryptCipher.init(Cipher.ENCRYPT_MODE, getKey());
            byte[] encryptedBytes = encryptCipher.doFinal(input);

            // Write to bytes
            ByteBuffer buffer = ByteBuffer.allocate(
                            4 + encryptCipher.getIV().length + 4 + encryptedBytes.length)
                    .putInt(encryptCipher.getIV().length)
                    .put(encryptCipher.getIV())
                    .putInt(encryptedBytes.length)
                    .put(encryptedBytes);
            return buffer.array();
        } catch (NoSuchAlgorithmException | NoSuchPaddingException | InvalidKeyException
                 | IllegalBlockSizeException | BadPaddingException e) {
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
            Cipher decryptCipher = Cipher.getInstance(TRANSFORMATION);
            decryptCipher.init(Cipher.DECRYPT_MODE, getKey(), new IvParameterSpec(iv));
            return decryptCipher.doFinal(encryptedBytes);
        } catch (NoSuchAlgorithmException | NoSuchPaddingException | InvalidKeyException
                 | InvalidAlgorithmParameterException | IllegalBlockSizeException
                 | BadPaddingException e) {
            Slog.e(TAG, e.getMessage());
            return null;
        }
    }

    private SecretKey getKey() {
        try {
            KeyStore.Entry keyEntry = mKeyStore.getEntry(KEY_STORE_ALIAS, null);
            if (keyEntry instanceof KeyStore.SecretKeyEntry
                    && ((KeyStore.SecretKeyEntry) keyEntry).getSecretKey() != null) {
                return ((KeyStore.SecretKeyEntry) keyEntry).getSecretKey();
            } else {
                return createKey();
            }
        } catch (NoSuchAlgorithmException | UnrecoverableEntryException | KeyStoreException e) {
            throw new RuntimeException(e);
        }
    }

    private SecretKey createKey() {
        try {
            KeyGenerator keyGenerator = KeyGenerator.getInstance(ALGORITHM);
            keyGenerator.init(
                    new KeyGenParameterSpec.Builder(KEY_STORE_ALIAS,
                            KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                            .setBlockModes(BLOCK_MODE)
                            .setEncryptionPaddings(PADDING)
                            .setUserAuthenticationRequired(false)
                            .setRandomizedEncryptionRequired(true)
                            .build());
            return keyGenerator.generateKey();
        } catch (NoSuchAlgorithmException | InvalidAlgorithmParameterException e) {
            throw new RuntimeException(e);
        }
    }
}
