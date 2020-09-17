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
import android.content.pm.UserInfo;
import android.os.UserHandle;
import android.os.UserManager;
import android.security.keystore.AndroidKeyStoreSpi;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.security.keystore.UserNotAuthenticatedException;
import android.util.Slog;
import android.util.SparseArray;

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
 * Caches *unified* work challenge for user 0's managed profiles. Only user 0's profile is supported
 * at the moment because the cached credential is encrypted using a keystore key auth-bound to
 * user 0: this is to match how unified work challenge is similarly auth-bound to its parent user's
 * lockscreen credential normally. It's possible to extend this class to support managed profiles
 * for secondary users, that will require generating auth-bound keys to their corresponding parent
 * user though (which {@link KeyGenParameterSpec} does not support right now).
 *
 * <p> The cache is filled whenever the managed profile's unified challenge is created or derived
 * (as part of the parent user's credential verification flow). It's removed when the profile is
 * deleted or a (separate) lockscreen credential is explicitly set on the profile. There is also
 * an ADB command to evict the cache "cmd lock_settings remove-cache --user X", to assist
 * development and testing.

 * <p> The encrypted credential is stored in-memory only so the cache does not persist across
 * reboots.
 */
public class ManagedProfilePasswordCache {

    private static final String TAG = "ManagedProfilePasswordCache";
    private static final int KEY_LENGTH = 256;
    private static final int CACHE_TIMEOUT_SECONDS = (int) TimeUnit.DAYS.toSeconds(7);

    private final SparseArray<byte[]> mEncryptedPasswords = new SparseArray<>();
    private final KeyStore mKeyStore;
    private final UserManager mUserManager;

    public ManagedProfilePasswordCache(KeyStore keyStore, UserManager userManager) {
        mKeyStore = keyStore;
        mUserManager = userManager;
    }

    /**
     * Encrypt and store the password in the cache. Does NOT overwrite existing password cache
     * if one for the given user already exists.
     */
    public void storePassword(int userId, LockscreenCredential password) {
        synchronized (mEncryptedPasswords) {
            if (mEncryptedPasswords.contains(userId)) {
                return;
            }
            UserInfo parent = mUserManager.getProfileParent(userId);
            if (parent == null || parent.id != UserHandle.USER_SYSTEM) {
                // Since the cached password is encrypted using a keystore key auth-bound to user 0,
                // only support caching password for user 0's profile.
                return;
            }
            String keyName = getEncryptionKeyName(userId);
            KeyGenerator generator;
            SecretKey key;
            try {
                generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,
                        AndroidKeyStoreSpi.NAME);
                generator.init(new KeyGenParameterSpec.Builder(
                        keyName, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                        .setKeySize(KEY_LENGTH)
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        // Generate auth-bound key to user 0 (since we the caller is user 0)
                        .setUserAuthenticationRequired(true)
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
                byte[] block = Arrays.copyOf(iv, ciphertext.length + iv.length);
                System.arraycopy(ciphertext, 0, block, iv.length, ciphertext.length);
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
            LockscreenCredential result = LockscreenCredential.createManagedPassword(credential);
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
