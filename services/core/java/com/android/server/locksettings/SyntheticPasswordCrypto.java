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

import android.security.AndroidKeyStoreMaintenance;
import android.security.keystore.KeyProperties;
import android.security.keystore.KeyProtection;
import android.security.keystore2.AndroidKeyStoreLoadStoreParameter;
import android.system.keystore2.Domain;
import android.system.keystore2.KeyDescriptor;
import android.text.TextUtils;
import android.util.Slog;

import com.android.internal.util.ArrayUtils;

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
import java.security.spec.InvalidParameterSpecException;
import java.util.Arrays;

import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.KeyGenerator;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

class SyntheticPasswordCrypto {
    private static final String TAG = "SyntheticPasswordCrypto";
    private static final int AES_GCM_KEY_SIZE = 32; // AES-256-GCM
    private static final int AES_GCM_IV_SIZE = 12;
    private static final int AES_GCM_TAG_SIZE = 16;
    private static final byte[] PROTECTOR_SECRET_PERSONALIZATION = "application-id".getBytes();
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
        byte[] iv = Arrays.copyOfRange(blob, 0, AES_GCM_IV_SIZE);
        byte[] ciphertext = Arrays.copyOfRange(blob, AES_GCM_IV_SIZE, blob.length);
        Cipher cipher = Cipher.getInstance(KeyProperties.KEY_ALGORITHM_AES + "/"
                + KeyProperties.BLOCK_MODE_GCM + "/" + KeyProperties.ENCRYPTION_PADDING_NONE);
        cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(AES_GCM_TAG_SIZE * 8, iv));
        return cipher.doFinal(ciphertext);
    }

    private static byte[] encrypt(SecretKey key, byte[] blob)
            throws IOException, NoSuchAlgorithmException, NoSuchPaddingException,
            InvalidKeyException, IllegalBlockSizeException, BadPaddingException,
            InvalidParameterSpecException {
        if (blob == null) {
            return null;
        }
        Cipher cipher = Cipher.getInstance(
                KeyProperties.KEY_ALGORITHM_AES + "/" + KeyProperties.BLOCK_MODE_GCM + "/"
                        + KeyProperties.ENCRYPTION_PADDING_NONE);
        cipher.init(Cipher.ENCRYPT_MODE, key);
        byte[] ciphertext = cipher.doFinal(blob);
        byte[] iv = cipher.getIV();
        if (iv.length != AES_GCM_IV_SIZE) {
            throw new IllegalArgumentException("Invalid iv length: " + iv.length + " bytes");
        }
        final GCMParameterSpec spec = cipher.getParameters().getParameterSpec(
                GCMParameterSpec.class);
        if (spec.getTLen() != AES_GCM_TAG_SIZE * 8) {
            throw new IllegalArgumentException("Invalid tag length: " + spec.getTLen() + " bits");
        }
        return ArrayUtils.concat(iv, ciphertext);
    }

    public static byte[] encrypt(byte[] keyBytes, byte[] personalization, byte[] message) {
        byte[] keyHash = personalizedHash(personalization, keyBytes);
        SecretKeySpec key = new SecretKeySpec(Arrays.copyOf(keyHash, AES_GCM_KEY_SIZE),
                KeyProperties.KEY_ALGORITHM_AES);
        try {
            return encrypt(key, message);
        } catch (InvalidKeyException | NoSuchAlgorithmException | NoSuchPaddingException
                | IllegalBlockSizeException | BadPaddingException | IOException
                | InvalidParameterSpecException e) {
            Slog.e(TAG, "Failed to encrypt", e);
            return null;
        }
    }

    public static byte[] decrypt(byte[] keyBytes, byte[] personalization, byte[] ciphertext) {
        byte[] keyHash = personalizedHash(personalization, keyBytes);
        SecretKeySpec key = new SecretKeySpec(Arrays.copyOf(keyHash, AES_GCM_KEY_SIZE),
                KeyProperties.KEY_ALGORITHM_AES);
        try {
            return decrypt(key, ciphertext);
        } catch (InvalidKeyException | NoSuchAlgorithmException | NoSuchPaddingException
                | IllegalBlockSizeException | BadPaddingException
                | InvalidAlgorithmParameterException e) {
            Slog.e(TAG, "Failed to decrypt", e);
            return null;
        }
    }

    /**
     * Decrypts a legacy SP blob which did the Keystore and software encryption layers in the wrong
     * order.
     */
    public static byte[] decryptBlobV1(String protectorKeyAlias, byte[] blob,
            byte[] protectorSecret) {
        try {
            KeyStore keyStore = getKeyStore();
            SecretKey protectorKey = (SecretKey) keyStore.getKey(protectorKeyAlias, null);
            if (protectorKey == null) {
                throw new IllegalStateException("SP protector key is missing: "
                        + protectorKeyAlias);
            }
            byte[] intermediate = decrypt(protectorSecret, PROTECTOR_SECRET_PERSONALIZATION, blob);
            return decrypt(protectorKey, intermediate);
        } catch (Exception e) {
            Slog.e(TAG, "Failed to decrypt V1 blob", e);
            throw new IllegalStateException("Failed to decrypt blob", e);
        }
    }

    static String androidKeystoreProviderName() {
        return "AndroidKeyStore";
    }

    static int keyNamespace() {
        return KeyProperties.NAMESPACE_LOCKSETTINGS;
    }

    private static KeyStore getKeyStore()
            throws KeyStoreException, CertificateException, NoSuchAlgorithmException, IOException {
        KeyStore keyStore = KeyStore.getInstance(androidKeystoreProviderName());
        keyStore.load(new AndroidKeyStoreLoadStoreParameter(keyNamespace()));
        return keyStore;
    }

    /**
     * Decrypts an SP blob that was created by {@link #createBlob}.
     */
    public static byte[] decryptBlob(String protectorKeyAlias, byte[] blob,
            byte[] protectorSecret) {
        try {
            final KeyStore keyStore = getKeyStore();

            SecretKey protectorKey = (SecretKey) keyStore.getKey(protectorKeyAlias, null);
            if (protectorKey == null) {
                throw new IllegalStateException("SP protector key is missing: "
                        + protectorKeyAlias);
            }
            byte[] intermediate = decrypt(protectorKey, blob);
            return decrypt(protectorSecret, PROTECTOR_SECRET_PERSONALIZATION, intermediate);
        } catch (CertificateException | IOException | BadPaddingException
                | IllegalBlockSizeException
                | KeyStoreException | NoSuchPaddingException | NoSuchAlgorithmException
                | InvalidKeyException | UnrecoverableKeyException
                | InvalidAlgorithmParameterException e) {
            Slog.e(TAG, "Failed to decrypt blob", e);
            throw new IllegalStateException("Failed to decrypt blob", e);
        }
    }

    /**
     * Creates a new SP blob by encrypting the given data.  Two encryption layers are applied: an
     * inner layer using a hash of protectorSecret as the key, and an outer layer using the
     * protector key, which is a Keystore key that is optionally bound to a SID.  This method
     * creates the protector key and stores it under protectorKeyAlias.
     *
     * The reason we use a layer of software encryption, instead of using protectorSecret as the
     * applicationId of the Keystore key, is to work around buggy KeyMint implementations that don't
     * cryptographically bind the applicationId to the key.  The Keystore layer has to be the outer
     * layer, so that LSKF verification is ratelimited by Gatekeeper when Weaver is unavailable.
     */
    public static byte[] createBlob(String protectorKeyAlias, byte[] data, byte[] protectorSecret,
            long sid) {
        try {
            KeyGenerator keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES);
            keyGenerator.init(AES_GCM_KEY_SIZE * 8, new SecureRandom());
            SecretKey protectorKey = keyGenerator.generateKey();
            final KeyStore keyStore = getKeyStore();
            KeyProtection.Builder builder = new KeyProtection.Builder(KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setCriticalToDeviceEncryption(true);
            if (sid != 0) {
                builder.setUserAuthenticationRequired(true)
                        .setBoundToSpecificSecureUserId(sid)
                        .setUserAuthenticationValidityDurationSeconds(USER_AUTHENTICATION_VALIDITY);
            }
            final KeyProtection protNonRollbackResistant = builder.build();
            builder.setRollbackResistant(true);
            final KeyProtection protRollbackResistant = builder.build();
            final KeyStore.SecretKeyEntry entry = new KeyStore.SecretKeyEntry(protectorKey);
            try {
                keyStore.setEntry(protectorKeyAlias, entry, protRollbackResistant);
                Slog.i(TAG, "Using rollback-resistant key");
            } catch (KeyStoreException e) {
                Slog.w(TAG, "Rollback-resistant keys unavailable.  Falling back to "
                        + "non-rollback-resistant key");
                keyStore.setEntry(protectorKeyAlias, entry, protNonRollbackResistant);
            }

            byte[] intermediate = encrypt(protectorSecret, PROTECTOR_SECRET_PERSONALIZATION, data);
            return encrypt(protectorKey, intermediate);
        } catch (CertificateException | IOException | BadPaddingException
                | IllegalBlockSizeException
                | KeyStoreException | NoSuchPaddingException | NoSuchAlgorithmException
                | InvalidKeyException
                | InvalidParameterSpecException e) {
            Slog.e(TAG, "Failed to create blob", e);
            throw new IllegalStateException("Failed to encrypt blob", e);
        }
    }

    public static void destroyProtectorKey(String keyAlias) {
        KeyStore keyStore;
        try {
            keyStore = getKeyStore();
            keyStore.deleteEntry(keyAlias);
            Slog.i(TAG, "Deleted SP protector key " + keyAlias);
        } catch (KeyStoreException | NoSuchAlgorithmException | CertificateException
                | IOException e) {
            Slog.e(TAG, "Failed to delete SP protector key " + keyAlias, e);
        }
    }

    protected static byte[] personalizedHash(byte[] personalization, byte[]... message) {
        try {
            final int PADDING_LENGTH = 128;
            MessageDigest digest = MessageDigest.getInstance("SHA-512");
            if (personalization.length > PADDING_LENGTH) {
                throw new IllegalArgumentException("Personalization too long");
            }
            // Personalize the hash
            // Pad it to the block size of the hash function
            personalization = Arrays.copyOf(personalization, PADDING_LENGTH);
            digest.update(personalization);
            for (byte[] data : message) {
                digest.update(data);
            }
            return digest.digest();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("NoSuchAlgorithmException for SHA-512", e);
        }
    }

    static boolean migrateLockSettingsKey(String alias) {
        final KeyDescriptor legacyKey = new KeyDescriptor();
        legacyKey.domain = Domain.APP;
        legacyKey.nspace = KeyProperties.NAMESPACE_APPLICATION;
        legacyKey.alias = alias;

        final KeyDescriptor newKey = new KeyDescriptor();
        newKey.domain = Domain.SELINUX;
        newKey.nspace = SyntheticPasswordCrypto.keyNamespace();
        newKey.alias = alias;
        Slog.i(TAG, "Migrating key " + alias);
        int err = AndroidKeyStoreMaintenance.migrateKeyNamespace(legacyKey, newKey);
        if (err == 0) {
            return true;
        } else if (err == AndroidKeyStoreMaintenance.KEY_NOT_FOUND) {
            Slog.i(TAG, "Key does not exist");
            // Treat this as a success so we don't migrate again.
            return true;
        } else if (err == AndroidKeyStoreMaintenance.INVALID_ARGUMENT) {
            Slog.i(TAG, "Key already exists");
            // Treat this as a success so we don't migrate again.
            return true;
        } else {
            Slog.e(TAG, TextUtils.formatSimple("Failed to migrate key: %d", err));
            return false;
        }
    }
}
