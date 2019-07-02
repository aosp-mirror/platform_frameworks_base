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

import android.annotation.Nullable;
import android.security.keystore.recovery.RecoveryController;
import android.util.Log;
import android.util.Pair;

import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import javax.crypto.Cipher;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * A {@link javax.crypto.SecretKey} wrapped with AES/GCM/NoPadding.
 *
 * @hide
 */
public class WrappedKey {
    private static final String TAG = "WrappedKey";

    private static final String KEY_WRAP_CIPHER_ALGORITHM = "AES/GCM/NoPadding";
    private static final String APPLICATION_KEY_ALGORITHM = "AES";
    private static final int GCM_TAG_LENGTH_BITS = 128;

    private final int mPlatformKeyGenerationId;
    private final int mRecoveryStatus;
    private final byte[] mNonce;
    private final byte[] mKeyMaterial;
    private final byte[] mKeyMetadata;

    /**
     * Returns a wrapped form of {@code key}, using {@code wrappingKey} to encrypt the key material.
     *
     * @throws InvalidKeyException if {@code wrappingKey} cannot be used to encrypt {@code key}, or
     *     if {@code key} does not expose its key material. See
     *     {@link android.security.keystore.AndroidKeyStoreKey} for an example of a key that does
     *     not expose its key material.
     */
    public static WrappedKey fromSecretKey(PlatformEncryptionKey wrappingKey, SecretKey key,
            @Nullable byte[] metadata)
            throws InvalidKeyException, KeyStoreException {
        if (key.getEncoded() == null) {
            throw new InvalidKeyException(
                    "key does not expose encoded material. It cannot be wrapped.");
        }

        Cipher cipher;
        try {
            cipher = Cipher.getInstance(KEY_WRAP_CIPHER_ALGORITHM);
        } catch (NoSuchAlgorithmException | NoSuchPaddingException e) {
            throw new RuntimeException(
                    "Android does not support AES/GCM/NoPadding. This should never happen.");
        }

        cipher.init(Cipher.WRAP_MODE, wrappingKey.getKey());
        byte[] encryptedKeyMaterial;
        try {
            encryptedKeyMaterial = cipher.wrap(key);
        } catch (IllegalBlockSizeException e) {
            Throwable cause = e.getCause();
            if (cause instanceof KeyStoreException) {
                // If AndroidKeyStore encounters any error here, it throws IllegalBlockSizeException
                // with KeyStoreException as the cause. This is due to there being no better option
                // here, as the Cipher#wrap only checked throws InvalidKeyException or
                // IllegalBlockSizeException. If this is the case, we want to propagate it to the
                // caller, so rethrow the cause.
                throw (KeyStoreException) cause;
            } else {
                throw new RuntimeException(
                        "IllegalBlockSizeException should not be thrown by AES/GCM/NoPadding mode.",
                        e);
            }
        }

        return new WrappedKey(
                /*nonce=*/ cipher.getIV(),
                /*keyMaterial=*/ encryptedKeyMaterial,
                /*keyMetadata=*/ metadata,
                /*platformKeyGenerationId=*/ wrappingKey.getGenerationId(),
                RecoveryController.RECOVERY_STATUS_SYNC_IN_PROGRESS);
    }

    /**
     * A new instance with default recovery status.
     *
     * @param nonce The nonce with which the key material was encrypted.
     * @param keyMaterial The encrypted bytes of the key material.
     * @param platformKeyGenerationId The generation ID of the key used to wrap this key.
     *
     * @see RecoveryController#RECOVERY_STATUS_SYNC_IN_PROGRESS
     * @hide
     */
    public WrappedKey(byte[] nonce, byte[] keyMaterial, @Nullable byte[] keyMetadata,
            int platformKeyGenerationId) {
        this(nonce, keyMaterial, keyMetadata, platformKeyGenerationId,
                RecoveryController.RECOVERY_STATUS_SYNC_IN_PROGRESS);
    }

    /**
     * A new instance.
     *
     * @param nonce The nonce with which the key material was encrypted.
     * @param keyMaterial The encrypted bytes of the key material.
     * @param keyMetadata The metadata that will be authenticated (but unencrypted) together with
     *     the key material when the key is uploaded to cloud.
     * @param platformKeyGenerationId The generation ID of the key used to wrap this key.
     * @param recoveryStatus recovery status of the key.
     *
     * @hide
     */
    public WrappedKey(byte[] nonce, byte[] keyMaterial, @Nullable byte[] keyMetadata,
            int platformKeyGenerationId, int recoveryStatus) {
        mNonce = nonce;
        mKeyMaterial = keyMaterial;
        mKeyMetadata = keyMetadata;
        mPlatformKeyGenerationId = platformKeyGenerationId;
        mRecoveryStatus = recoveryStatus;
    }

    /**
     * Returns the nonce with which the key material was encrypted.
     *
     * @hide
     */
    public byte[] getNonce() {
        return mNonce;
    }

    /**
     * Returns the encrypted key material.
     *
     * @hide
     */
    public byte[] getKeyMaterial() {
        return mKeyMaterial;
    }

    /**
     * Returns the key metadata.
     *
     * @hide
     */
    public @Nullable byte[] getKeyMetadata() {
        return mKeyMetadata;
    }

    /**
     * Returns the generation ID of the platform key, with which this key was wrapped.
     *
     * @hide
     */
    public int getPlatformKeyGenerationId() {
        return mPlatformKeyGenerationId;
    }

    /**
     * Returns recovery status of the key.
     *
     * @hide
     */
    public int getRecoveryStatus() {
        return mRecoveryStatus;
    }

    /**
     * Unwraps the {@code wrappedKeys} with the {@code platformKey}.
     *
     * @return The unwrapped keys, indexed by alias.
     * @throws NoSuchAlgorithmException if AES/GCM/NoPadding Cipher or AES key type is unavailable.
     * @throws BadPlatformKeyException if the {@code platformKey} has a different generation ID to
     *     any of the {@code wrappedKeys}.
     *
     * @hide
     */
    public static Map<String, Pair<SecretKey, byte[]>> unwrapKeys(
            PlatformDecryptionKey platformKey,
            Map<String, WrappedKey> wrappedKeys)
            throws NoSuchAlgorithmException, NoSuchPaddingException, BadPlatformKeyException,
            InvalidKeyException, InvalidAlgorithmParameterException {
        HashMap<String, Pair<SecretKey, byte[]>> unwrappedKeys = new HashMap<>();
        Cipher cipher = Cipher.getInstance(KEY_WRAP_CIPHER_ALGORITHM);
        int platformKeyGenerationId = platformKey.getGenerationId();

        for (String alias : wrappedKeys.keySet()) {
            WrappedKey wrappedKey = wrappedKeys.get(alias);
            if (wrappedKey.getPlatformKeyGenerationId() != platformKeyGenerationId) {
                throw new BadPlatformKeyException(String.format(
                        Locale.US,
                        "WrappedKey with alias '%s' was wrapped with platform key %d, not "
                                + "platform key %d",
                        alias,
                        wrappedKey.getPlatformKeyGenerationId(),
                        platformKey.getGenerationId()));
            }

            cipher.init(
                    Cipher.UNWRAP_MODE,
                    platformKey.getKey(),
                    new GCMParameterSpec(GCM_TAG_LENGTH_BITS, wrappedKey.getNonce()));
            SecretKey key;
            try {
                key = (SecretKey) cipher.unwrap(
                        wrappedKey.getKeyMaterial(), APPLICATION_KEY_ALGORITHM, Cipher.SECRET_KEY);
            } catch (InvalidKeyException | NoSuchAlgorithmException e) {
                Log.e(TAG,
                        String.format(
                                Locale.US,
                                "Error unwrapping recoverable key with alias '%s'",
                                alias),
                        e);
                continue;
            }
            unwrappedKeys.put(alias, Pair.create(key, wrappedKey.getKeyMetadata()));
        }

        return unwrappedKeys;
    }
}
