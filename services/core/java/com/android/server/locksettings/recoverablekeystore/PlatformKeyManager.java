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

import android.app.KeyguardManager;
import android.content.Context;
import android.security.keystore.AndroidKeyStoreSecretKey;
import android.security.keystore.KeyProperties;
import android.security.keystore.KeyProtection;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.locksettings.recoverablekeystore.storage.RecoverableKeyStoreDb;

import java.io.IOException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.UnrecoverableKeyException;
import java.security.cert.CertificateException;
import java.util.Locale;

import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.security.auth.DestroyFailedException;

/**
 * Manages creating and checking the validity of the platform key.
 *
 * <p>The platform key is used to wrap the material of recoverable keys before persisting them to
 * disk. It is also used to decrypt the same keys on a screen unlock, before re-wrapping them with
 * a recovery key and syncing them with remote storage.
 *
 * <p>Each platform key has two entries in AndroidKeyStore:
 *
 * <ul>
 *     <li>Encrypt entry - this entry enables the root user to at any time encrypt.
 *     <li>Decrypt entry - this entry enables the root user to decrypt only after recent user
 *       authentication, i.e., within 15 seconds after a screen unlock.
 * </ul>
 *
 * <p>Both entries are enabled only for AES/GCM/NoPadding Cipher algorithm.
 *
 * @hide
 */
public class PlatformKeyManager {
    private static final String TAG = "PlatformKeyManager";

    private static final String KEY_ALGORITHM = "AES";
    private static final int KEY_SIZE_BITS = 256;
    private static final String KEY_ALIAS_PREFIX =
            "com.android.server.locksettings.recoverablekeystore/platform/";
    private static final String ENCRYPT_KEY_ALIAS_SUFFIX = "encrypt";
    private static final String DECRYPT_KEY_ALIAS_SUFFIX = "decrypt";
    private static final int USER_AUTHENTICATION_VALIDITY_DURATION_SECONDS = 15;

    private final Context mContext;
    private final KeyStoreProxy mKeyStore;
    private final RecoverableKeyStoreDb mDatabase;
    private final int mUserId;

    private static final String ANDROID_KEY_STORE_PROVIDER = "AndroidKeyStore";

    /**
     * A new instance operating on behalf of {@code userId}, storing its prefs in the location
     * defined by {@code context}.
     *
     * @param context This should be the context of the RecoverableKeyStoreLoader service.
     * @param userId The ID of the user to whose lock screen the platform key must be bound.
     * @throws KeyStoreException if failed to initialize AndroidKeyStore.
     * @throws NoSuchAlgorithmException if AES is unavailable - should never happen.
     * @throws InsecureUserException if the user does not have a lock screen set.
     * @throws SecurityException if the caller does not have permission to write to /data/system.
     *
     * @hide
     */
    public static PlatformKeyManager getInstance(Context context, RecoverableKeyStoreDb database,
            int userId)
            throws KeyStoreException, NoSuchAlgorithmException, InsecureUserException {
        context = context.getApplicationContext();
        PlatformKeyManager keyManager = new PlatformKeyManager(
                userId,
                context,
                new KeyStoreProxyImpl(getAndLoadAndroidKeyStore()),
                database);
        keyManager.init();
        return keyManager;
    }

    @VisibleForTesting
    PlatformKeyManager(
            int userId,
            Context context,
            KeyStoreProxy keyStore,
            RecoverableKeyStoreDb database) {
        mUserId = userId;
        mKeyStore = keyStore;
        mContext = context;
        mDatabase = database;
    }

    /**
     * Returns the current generation ID of the platform key. This increments whenever a platform
     * key has to be replaced. (e.g., because the user has removed and then re-added their lock
     * screen). Returns -1 if no key has been generated yet.
     *
     * @hide
     */
    public int getGenerationId() {
        return mDatabase.getPlatformKeyGenerationId(mUserId);
    }

    /**
     * Returns {@code true} if the platform key is available. A platform key won't be available if
     * the user has not set up a lock screen.
     *
     * @hide
     */
    public boolean isAvailable() {
        return mContext.getSystemService(KeyguardManager.class).isDeviceSecure(mUserId);
    }

    /**
     * Generates a new key and increments the generation ID. Should be invoked if the platform key
     * is corrupted and needs to be rotated.
     *
     * @throws NoSuchAlgorithmException if AES is unavailable - should never happen.
     * @throws KeyStoreException if there is an error in AndroidKeyStore.
     *
     * @hide
     */
    public void regenerate() throws NoSuchAlgorithmException, KeyStoreException {
        int nextId = getGenerationId() + 1;
        generateAndLoadKey(nextId);
        setGenerationId(nextId);
    }

    /**
     * Returns the platform key used for encryption.
     *
     * @throws KeyStoreException if there was an AndroidKeyStore error.
     * @throws UnrecoverableKeyException if the key could not be recovered.
     * @throws NoSuchAlgorithmException if AES is unavailable - should never occur.
     *
     * @hide
     */
    public PlatformEncryptionKey getEncryptKey()
            throws KeyStoreException, UnrecoverableKeyException, NoSuchAlgorithmException {
        int generationId = getGenerationId();
        AndroidKeyStoreSecretKey key = (AndroidKeyStoreSecretKey) mKeyStore.getKey(
                getEncryptAlias(generationId), /*password=*/ null);
        return new PlatformEncryptionKey(generationId, key);
    }

    /**
     * Returns the platform key used for decryption. Only works after a recent screen unlock.
     *
     * @throws KeyStoreException if there was an AndroidKeyStore error.
     * @throws UnrecoverableKeyException if the key could not be recovered.
     * @throws NoSuchAlgorithmException if AES is unavailable - should never occur.
     *
     * @hide
     */
    public PlatformDecryptionKey getDecryptKey()
            throws KeyStoreException, UnrecoverableKeyException, NoSuchAlgorithmException {
        int generationId = getGenerationId();
        AndroidKeyStoreSecretKey key = (AndroidKeyStoreSecretKey) mKeyStore.getKey(
                getDecryptAlias(generationId), /*password=*/ null);
        return new PlatformDecryptionKey(generationId, key);
    }

    /**
     * Initializes the class. If there is no current platform key, and the user has a lock screen
     * set, will create the platform key and set the generation ID.
     *
     * @throws KeyStoreException if there was an error in AndroidKeyStore.
     * @throws NoSuchAlgorithmException if AES is unavailable - should never happen.
     *
     * @hide
     */
    public void init() throws KeyStoreException, NoSuchAlgorithmException, InsecureUserException {
        if (!isAvailable()) {
            throw new InsecureUserException(String.format(
                    Locale.US, "%d does not have a lock screen set.", mUserId));
        }

        int generationId = getGenerationId();
        if (isKeyLoaded(generationId)) {
            Log.i(TAG, String.format(
                    Locale.US, "Platform key generation %d exists already.", generationId));
            return;
        }
        if (generationId == -1) {
            Log.i(TAG, "Generating initial platform ID.");
        } else {
            Log.w(TAG, String.format(Locale.US, "Platform generation ID was %d but no "
                    + "entry was present in AndroidKeyStore. Generating fresh key.", generationId));
        }

        if (generationId == -1) {
            generationId = 1;
        } else {
            // Had to generate a fresh key, bump the generation id
            generationId++;
        }

        generateAndLoadKey(generationId);
        mDatabase.setPlatformKeyGenerationId(mUserId, generationId);
    }

    /**
     * Returns the alias of the encryption key with the specific {@code generationId} in the
     * AndroidKeyStore.
     *
     * <p>These IDs look as follows:
     * {@code com.security.recoverablekeystore/platform/<user id>/<generation id>/encrypt}
     *
     * @param generationId The generation ID.
     * @return The alias.
     */
    private String getEncryptAlias(int generationId) {
        return KEY_ALIAS_PREFIX + mUserId + "/" + generationId + "/" + ENCRYPT_KEY_ALIAS_SUFFIX;
    }

    /**
     * Returns the alias of the decryption key with the specific {@code generationId} in the
     * AndroidKeyStore.
     *
     * <p>These IDs look as follows:
     * {@code com.security.recoverablekeystore/platform/<user id>/<generation id>/decrypt}
     *
     * @param generationId The generation ID.
     * @return The alias.
     */
    private String getDecryptAlias(int generationId) {
        return KEY_ALIAS_PREFIX + mUserId + "/" + generationId + "/" + DECRYPT_KEY_ALIAS_SUFFIX;
    }

    /**
     * Sets the current generation ID to {@code generationId}.
     */
    private void setGenerationId(int generationId) {
        mDatabase.setPlatformKeyGenerationId(mUserId, generationId);
    }

    /**
     * Returns {@code true} if a key has been loaded with the given {@code generationId} into
     * AndroidKeyStore.
     *
     * @throws KeyStoreException if there was an error checking AndroidKeyStore.
     */
    private boolean isKeyLoaded(int generationId) throws KeyStoreException {
        return mKeyStore.containsAlias(getEncryptAlias(generationId))
                && mKeyStore.containsAlias(getDecryptAlias(generationId));
    }

    /**
     * Generates a new 256-bit AES key, and loads it into AndroidKeyStore with the given
     * {@code generationId} determining its aliases.
     *
     * @throws NoSuchAlgorithmException if AES is unavailable. This should never happen, as it is
     *     available since API version 1.
     * @throws KeyStoreException if there was an issue loading the keys into AndroidKeyStore.
     */
    private void generateAndLoadKey(int generationId)
            throws NoSuchAlgorithmException, KeyStoreException {
        String encryptAlias = getEncryptAlias(generationId);
        String decryptAlias = getDecryptAlias(generationId);
        SecretKey secretKey = generateAesKey();

        mKeyStore.setEntry(
                encryptAlias,
                new KeyStore.SecretKeyEntry(secretKey),
                new KeyProtection.Builder(KeyProperties.PURPOSE_ENCRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build());
        mKeyStore.setEntry(
                decryptAlias,
                new KeyStore.SecretKeyEntry(secretKey),
                new KeyProtection.Builder(KeyProperties.PURPOSE_DECRYPT)
                    .setUserAuthenticationRequired(true)
                    .setUserAuthenticationValidityDurationSeconds(
                            USER_AUTHENTICATION_VALIDITY_DURATION_SECONDS)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setBoundToSpecificSecureUserId(mUserId)
                    .build());

        try {
            secretKey.destroy();
        } catch (DestroyFailedException e) {
            Log.w(TAG, "Failed to destroy in-memory platform key.", e);
        }
    }

    /**
     * Generates a new 256-bit AES key, in software.
     *
     * @return The software-generated AES key.
     * @throws NoSuchAlgorithmException if AES key generation is not available. This should never
     *     happen, as AES has been supported since API level 1.
     */
    private static SecretKey generateAesKey() throws NoSuchAlgorithmException {
        KeyGenerator keyGenerator = KeyGenerator.getInstance(KEY_ALGORITHM);
        keyGenerator.init(KEY_SIZE_BITS);
        return keyGenerator.generateKey();
    }

    /**
     * Returns AndroidKeyStore-provided {@link KeyStore}, having already invoked
     * {@link KeyStore#load(KeyStore.LoadStoreParameter)}.
     *
     * @throws KeyStoreException if there was a problem getting or initializing the key store.
     */
    private static KeyStore getAndLoadAndroidKeyStore() throws KeyStoreException {
        KeyStore keyStore = KeyStore.getInstance(ANDROID_KEY_STORE_PROVIDER);
        try {
            keyStore.load(/*param=*/ null);
        } catch (CertificateException | IOException | NoSuchAlgorithmException e) {
            // Should never happen.
            throw new KeyStoreException("Unable to load keystore.", e);
        }
        return keyStore;
    }

    /**
     * @hide
     */
    public interface Factory {
        /**
         * New PlatformKeyManager instance.
         *
         * @hide
         */
        PlatformKeyManager newInstance()
                throws NoSuchAlgorithmException, InsecureUserException, KeyStoreException;
    }
}
