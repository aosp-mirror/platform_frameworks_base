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

package com.android.server.locksettings;

import android.security.keystore.KeyProperties;
import android.security.keystore.KeyProtection;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.UnrecoverableKeyException;
import java.security.cert.CertificateException;
import java.util.Arrays;

import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.KeyGenerator;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public class SyntheticPasswordCrypto {
    private static final int PROFILE_KEY_IV_SIZE = 12;
    private static final int AES_KEY_LENGTH = 32; // 256-bit AES key
    private static final byte[] APPLICATION_ID_PERSONALIZATION = "application-id".getBytes();
    // Time between the user credential is verified with GK and the decryption of synthetic password
    // under the auth-bound key. This should always happen one after the other, but give it 15
    // seconds just to be sure.
    private static final int USER_AUTHENTICATION_VALIDITY = 15;

    private static byte[] decrypt(SecretKey key, byte[] blob)
            throws NoSuchAlgorithmException, NoSuchPaddingException, InvalidKeyException,
            InvalidAlgorithmParameterException, IllegalBlockSizeException, BadPaddingException {
        if (blob == null) {
            return null;
        }
        byte[] iv = Arrays.copyOfRange(blob, 0, PROFILE_KEY_IV_SIZE);
        byte[] ciphertext = Arrays.copyOfRange(blob, PROFILE_KEY_IV_SIZE, blob.length);
        Cipher cipher = Cipher.getInstance(KeyProperties.KEY_ALGORITHM_AES + "/"
                + KeyProperties.BLOCK_MODE_GCM + "/" + KeyProperties.ENCRYPTION_PADDING_NONE);
        cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, iv));
        return cipher.doFinal(ciphertext);
    }

    private static byte[] encrypt(SecretKey key, byte[] blob)
            throws IOException, NoSuchAlgorithmException, NoSuchPaddingException,
            InvalidKeyException, IllegalBlockSizeException, BadPaddingException {
        if (blob == null) {
            return null;
        }
        Cipher cipher = Cipher.getInstance(
                KeyProperties.KEY_ALGORITHM_AES + "/" + KeyProperties.BLOCK_MODE_GCM + "/"
                        + KeyProperties.ENCRYPTION_PADDING_NONE);
        cipher.init(Cipher.ENCRYPT_MODE, key);
        byte[] ciphertext = cipher.doFinal(blob);
        byte[] iv = cipher.getIV();
        if (iv.length != PROFILE_KEY_IV_SIZE) {
            throw new RuntimeException("Invalid iv length: " + iv.length);
        }
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        outputStream.write(iv);
        outputStream.write(ciphertext);
        return outputStream.toByteArray();
    }

    public static byte[] encrypt(byte[] keyBytes, byte[] personalisation, byte[] message) {
        byte[] keyHash = personalisedHash(personalisation, keyBytes);
        SecretKeySpec key = new SecretKeySpec(Arrays.copyOf(keyHash, AES_KEY_LENGTH),
                KeyProperties.KEY_ALGORITHM_AES);
        try {
            return encrypt(key, message);
        } catch (InvalidKeyException | NoSuchAlgorithmException | NoSuchPaddingException
                | IllegalBlockSizeException | BadPaddingException | IOException e) {
            e.printStackTrace();
            return null;
        }
    }

    public static byte[] decrypt(byte[] keyBytes, byte[] personalisation, byte[] ciphertext) {
        byte[] keyHash = personalisedHash(personalisation, keyBytes);
        SecretKeySpec key = new SecretKeySpec(Arrays.copyOf(keyHash, AES_KEY_LENGTH),
                KeyProperties.KEY_ALGORITHM_AES);
        try {
            return decrypt(key, ciphertext);
        } catch (InvalidKeyException | NoSuchAlgorithmException | NoSuchPaddingException
                | IllegalBlockSizeException | BadPaddingException
                | InvalidAlgorithmParameterException e) {
            e.printStackTrace();
            return null;
        }
    }

    public static byte[] decryptBlobV1(String keyAlias, byte[] blob, byte[] applicationId) {
        try {
            KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
            keyStore.load(null);

            SecretKey decryptionKey = (SecretKey) keyStore.getKey(keyAlias, null);
            byte[] intermediate = decrypt(applicationId, APPLICATION_ID_PERSONALIZATION, blob);
            return decrypt(decryptionKey, intermediate);
        } catch (Exception e) {
            e.printStackTrace();
            throw new RuntimeException("Failed to decrypt blob", e);
        }
    }

    public static byte[] decryptBlob(String keyAlias, byte[] blob, byte[] applicationId) {
        try {
            KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
            keyStore.load(null);

            SecretKey decryptionKey = (SecretKey) keyStore.getKey(keyAlias, null);
            byte[] intermediate = decrypt(decryptionKey, blob);
            return decrypt(applicationId, APPLICATION_ID_PERSONALIZATION, intermediate);
        } catch (CertificateException | IOException | BadPaddingException
                | IllegalBlockSizeException
                | KeyStoreException | NoSuchPaddingException | NoSuchAlgorithmException
                | InvalidKeyException | UnrecoverableKeyException
                | InvalidAlgorithmParameterException e) {
            e.printStackTrace();
            throw new RuntimeException("Failed to decrypt blob", e);
        }
    }

    public static byte[] createBlob(String keyAlias, byte[] data, byte[] applicationId, long sid) {
        try {
            KeyGenerator keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES);
            keyGenerator.init(new SecureRandom());
            SecretKey secretKey = keyGenerator.generateKey();
            KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
            keyStore.load(null);
            KeyProtection.Builder builder = new KeyProtection.Builder(KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setCriticalToDeviceEncryption(true);
            if (sid != 0) {
                builder.setUserAuthenticationRequired(true)
                        .setBoundToSpecificSecureUserId(sid)
                        .setUserAuthenticationValidityDurationSeconds(USER_AUTHENTICATION_VALIDITY);
            }

            keyStore.setEntry(keyAlias,
                    new KeyStore.SecretKeyEntry(secretKey),
                    builder.build());
            byte[] intermediate = encrypt(applicationId, APPLICATION_ID_PERSONALIZATION, data);
            return encrypt(secretKey, intermediate);
        } catch (CertificateException | IOException | BadPaddingException
                | IllegalBlockSizeException
                | KeyStoreException | NoSuchPaddingException | NoSuchAlgorithmException
                | InvalidKeyException e) {
            e.printStackTrace();
            throw new RuntimeException("Failed to encrypt blob", e);
        }
    }

    public static void destroyBlobKey(String keyAlias) {
        KeyStore keyStore;
        try {
            keyStore = KeyStore.getInstance("AndroidKeyStore");
            keyStore.load(null);
            keyStore.deleteEntry(keyAlias);
        } catch (KeyStoreException | NoSuchAlgorithmException | CertificateException
                | IOException e) {
            e.printStackTrace();
        }
    }

    protected static byte[] personalisedHash(byte[] personalisation, byte[]... message) {
        try {
            final int PADDING_LENGTH = 128;
            MessageDigest digest = MessageDigest.getInstance("SHA-512");
            if (personalisation.length > PADDING_LENGTH) {
                throw new RuntimeException("Personalisation too long");
            }
            // Personalize the hash
            // Pad it to the block size of the hash function
            personalisation = Arrays.copyOf(personalisation, PADDING_LENGTH);
            digest.update(personalisation);
            for (byte[] data : message) {
                digest.update(data);
            }
            return digest.digest();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("NoSuchAlgorithmException for SHA-512", e);
        }
    }
}
