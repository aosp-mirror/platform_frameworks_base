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

import android.annotation.Nullable;
import android.security.GateKeeper;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.security.keystore.UserNotAuthenticatedException;
import android.util.Slog;
import android.util.SparseArray;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.ArrayUtils;
import com.android.internal.widget.LockscreenCredential;

import java.security.GeneralSecurityException;
import java.security.Key;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.UnrecoverableKeyException;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * An in-memory cache for unified profile passwords.  A "unified profile password" is the random
 * password that the system automatically generates and manages for each profile that uses a unified
 * challenge and where the parent user has a secure lock screen.
 * <p>
 * Each password in this cache is encrypted by a Keystore key that is auth-bound to the parent user.
 * This is very similar to how the password is protected on-disk, but the in-memory cache uses a
 * much longer timeout on the keys: 7 days instead of 30 seconds.  This enables use cases like
 * unpausing work apps without requiring authentication as frequently.
 * <p>
 * Unified profile passwords are cached when they are created, or when they are decrypted as part of
 * the parent user's LSKF verification flow.  They are removed when the profile is deleted or when a
 * separate challenge is explicitly set on the profile.  There is also an ADB command to evict a
 * cached password, "locksettings remove-cache --user X", to assist development and testing.
 */
@VisibleForTesting // public visibility is needed for Mockito
public class UnifiedProfilePasswordCache {

    private static final String TAG = "UnifiedProfilePasswordCache";
    private static final int KEY_LENGTH = 256;
    private static final int CACHE_TIMEOUT_SECONDS = (int) TimeUnit.DAYS.toSeconds(7);

    private final SparseArray<byte[]> mEncryptedPasswords = new SparseArray<>();
    private final KeyStore mKeyStore;

    public UnifiedProfilePasswordCache(KeyStore keyStore) {
        mKeyStore = keyStore;
    }

    /**
     * Encrypt and store the password in the cache. Does NOT overwrite existing password cache
     * if one for the given user already exists.
     *
     * Should only be called on a profile userId.
     */
    public void storePassword(int userId, LockscreenCredential password, long parentSid) {
        if (parentSid == GateKeeper.INVALID_SECURE_USER_ID) return;
        synchronized (mEncryptedPasswords) {
            if (mEncryptedPasswords.contains(userId)) {
                return;
            }
            String keyName = getEncryptionKeyName(userId);
            KeyGenerator generator;
            SecretKey key;
            try {
                generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,
                        mKeyStore.getProvider());
                generator.init(new KeyGenParameterSpec.Builder(
                        keyName, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                        .setKeySize(KEY_LENGTH)
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setNamespace(SyntheticPasswordCrypto.keyNamespace())
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setUserAuthenticationRequired(true)
                        .setBoundToSpecificSecureUserId(parentSid)
                        .setUserAuthenticationValidityDurationSeconds(CACHE_TIMEOUT_SECONDS)
                        .build());
                key = generator.generateKey();
            } catch (GeneralSecurityException e) {
                Slog.e(TAG, "Cannot generate key", e);
                return;
            }

            Cipher cipher;
            try {
                cipher = Cipher.getInstance("AES/GCM/NoPadding");
                cipher.init(Cipher.ENCRYPT_MODE, key);
                byte[] ciphertext = cipher.doFinal(password.getCredential());
                byte[] iv = cipher.getIV();
                byte[] block = ArrayUtils.concat(iv, ciphertext);
                mEncryptedPasswords.put(userId, block);
            } catch (GeneralSecurityException e) {
                Slog.d(TAG, "Cannot encrypt", e);
            }
        }
    }

    /** Attempt to retrieve the password for the given user. Returns {@code null} if it's not in the
     * cache or if decryption fails.
     */
    public @Nullable LockscreenCredential retrievePassword(int userId) {
        synchronized (mEncryptedPasswords) {
            byte[] block = mEncryptedPasswords.get(userId);
            if (block == null) {
                return null;
            }
            Key key;
            try {
                key = mKeyStore.getKey(getEncryptionKeyName(userId), null);
            } catch (UnrecoverableKeyException | KeyStoreException | NoSuchAlgorithmException e) {
                Slog.d(TAG, "Cannot get key", e);
                return null;
            }
            if (key == null) {
                return null;
            }
            byte[] iv = Arrays.copyOf(block, 12);
            byte[] ciphertext = Arrays.copyOfRange(block, 12, block.length);
            byte[] credential;
            try {
                Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, iv));
                credential = cipher.doFinal(ciphertext);
            } catch (UserNotAuthenticatedException e) {
                Slog.i(TAG, "Device not unlocked for more than 7 days");
                return null;
            } catch (GeneralSecurityException e) {
                Slog.d(TAG, "Cannot decrypt", e);
                return null;
            }
            LockscreenCredential result =
                    LockscreenCredential.createUnifiedProfilePassword(credential);
            Arrays.fill(credential, (byte) 0);
            return result;
        }
    }

    /** Remove the given user's password from cache, if one exists. */
    public void removePassword(int userId) {
        synchronized (mEncryptedPasswords) {
            String keyName = getEncryptionKeyName(userId);
            String legacyKeyName = getLegacyEncryptionKeyName(userId);
            try {
                if (mKeyStore.containsAlias(keyName)) {
                    mKeyStore.deleteEntry(keyName);
                }
                if (mKeyStore.containsAlias(legacyKeyName)) {
                    mKeyStore.deleteEntry(legacyKeyName);
                }
            } catch (KeyStoreException e) {
                Slog.d(TAG, "Cannot delete key", e);
            }
            if (mEncryptedPasswords.contains(userId)) {
                Arrays.fill(mEncryptedPasswords.get(userId), (byte) 0);
                mEncryptedPasswords.remove(userId);
            }
        }
    }

    private static String getEncryptionKeyName(int userId) {
        return "com.android.server.locksettings.unified_profile_cache_v2_" + userId;
    }

    /**
     * Returns the legacy keystore key name when setUnlockedDeviceRequired() was set explicitly.
     * Only existed during Android 11 internal testing period.
     */
    private static String getLegacyEncryptionKeyName(int userId) {
        return "com.android.server.locksettings.unified_profile_cache_" + userId;
    }
}
