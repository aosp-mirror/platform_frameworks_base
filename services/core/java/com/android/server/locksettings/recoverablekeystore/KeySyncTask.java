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

import android.annotation.NonNull;
import android.content.Context;
import android.security.recoverablekeystore.KeyStoreRecoveryMetadata;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.widget.LockPatternUtils;
import com.android.server.locksettings.recoverablekeystore.storage.RecoverableKeyStoreDb;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.InvalidKeyException;
import java.security.KeyStoreException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.UnrecoverableKeyException;
import java.util.Map;

import javax.crypto.KeyGenerator;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.SecretKey;

/**
 * Task to sync application keys to a remote vault service.
 *
 * @hide
 */
public class KeySyncTask implements Runnable {
    private static final String TAG = "KeySyncTask";

    private static final String RECOVERY_KEY_ALGORITHM = "AES";
    private static final int RECOVERY_KEY_SIZE_BITS = 256;
    private static final int SALT_LENGTH_BYTES = 16;
    private static final int LENGTH_PREFIX_BYTES = Integer.BYTES;
    private static final String LOCK_SCREEN_HASH_ALGORITHM = "SHA-256";

    private final RecoverableKeyStoreDb mRecoverableKeyStoreDb;
    private final int mUserId;
    private final RecoverableSnapshotConsumer mSnapshotConsumer;
    private final int mCredentialType;
    private final String mCredential;
    private final PlatformKeyManager.Factory mPlatformKeyManagerFactory;
    private final VaultKeySupplier mVaultKeySupplier;

    public static KeySyncTask newInstance(
            Context context,
            RecoverableKeyStoreDb recoverableKeyStoreDb,
            int userId,
            int credentialType,
            String credential
    ) throws NoSuchAlgorithmException, KeyStoreException, InsecureUserException {
        return new KeySyncTask(
                recoverableKeyStoreDb,
                userId,
                credentialType,
                credential,
                () -> PlatformKeyManager.getInstance(context, recoverableKeyStoreDb, userId),
                (salt, recoveryKey, applicationKeys) -> {
                    // TODO: implement sending intent
                },
                () -> {
                    throw new UnsupportedOperationException("Not implemented vault key.");
                });
    }

    /**
     * A new task.
     *
     * @param recoverableKeyStoreDb Database where the keys are stored.
     * @param userId The uid of the user whose profile has been unlocked.
     * @param credentialType The type of credential - i.e., pattern or password.
     * @param credential The credential, encoded as a {@link String}.
     * @param platformKeyManagerFactory Instantiates a {@link PlatformKeyManager} for the user.
     *     This is a factory to enable unit testing, as otherwise it would be impossible to test
     *     without a screen unlock occurring!
     */
    @VisibleForTesting
    KeySyncTask(
            RecoverableKeyStoreDb recoverableKeyStoreDb,
            int userId,
            int credentialType,
            String credential,
            PlatformKeyManager.Factory platformKeyManagerFactory,
            RecoverableSnapshotConsumer snapshotConsumer,
            VaultKeySupplier vaultKeySupplier) {
        mRecoverableKeyStoreDb = recoverableKeyStoreDb;
        mUserId = userId;
        mCredentialType = credentialType;
        mCredential = credential;
        mPlatformKeyManagerFactory = platformKeyManagerFactory;
        mSnapshotConsumer = snapshotConsumer;
        mVaultKeySupplier = vaultKeySupplier;
    }

    @Override
    public void run() {
        try {
            syncKeys();
        } catch (Exception e) {
            Log.e(TAG, "Unexpected exception thrown during KeySyncTask", e);
        }
    }

    private void syncKeys() {
        if (!isSyncPending()) {
            Log.d(TAG, "Key sync not needed.");
            return;
        }

        byte[] salt = generateSalt();
        byte[] localLskfHash = hashCredentials(salt, mCredential);

        Map<String, SecretKey> rawKeys;
        try {
            rawKeys = getKeysToSync();
        } catch (GeneralSecurityException e) {
            Log.e(TAG, "Failed to load recoverable keys for sync", e);
            return;
        } catch (InsecureUserException e) {
            Log.wtf(TAG, "A screen unlock triggered the key sync flow, so user must have "
                    + "lock screen. This should be impossible.", e);
            return;
        } catch (BadPlatformKeyException e) {
            Log.wtf(TAG, "Loaded keys for same generation ID as platform key, so "
                    + "BadPlatformKeyException should be impossible.", e);
            return;
        }

        SecretKey recoveryKey;
        try {
            recoveryKey = generateRecoveryKey();
        } catch (NoSuchAlgorithmException e) {
            Log.wtf("AES should never be unavailable", e);
            return;
        }

        Map<String, byte[]> encryptedApplicationKeys;
        try {
            encryptedApplicationKeys = KeySyncUtils.encryptKeysWithRecoveryKey(
                    recoveryKey, rawKeys);
        } catch (InvalidKeyException | NoSuchAlgorithmException e) {
            Log.wtf(TAG,
                    "Should be impossible: could not encrypt application keys with random key",
                    e);
            return;
        }

        // TODO: construct vault params and vault metadata
        byte[] vaultParams = {};

        byte[] encryptedRecoveryKey;
        try {
            encryptedRecoveryKey = KeySyncUtils.thmEncryptRecoveryKey(
                    mVaultKeySupplier.get(),
                    localLskfHash,
                    vaultParams,
                    recoveryKey);
        } catch (NoSuchAlgorithmException e) {
            Log.wtf(TAG, "SecureBox encrypt algorithms unavailable", e);
            return;
        } catch (InvalidKeyException e) {
            Log.e(TAG,"Could not encrypt with recovery key", e);
            return;
        }

        mSnapshotConsumer.accept(salt, encryptedRecoveryKey, encryptedApplicationKeys);
    }

    private PublicKey getVaultPublicKey() {
        // TODO: fill this in
        throw new UnsupportedOperationException("TODO: get vault public key.");
    }

    /**
     * Returns all of the recoverable keys for the user.
     */
    private Map<String, SecretKey> getKeysToSync()
            throws InsecureUserException, KeyStoreException, UnrecoverableKeyException,
            NoSuchAlgorithmException, NoSuchPaddingException, BadPlatformKeyException {
        PlatformKeyManager platformKeyManager = mPlatformKeyManagerFactory.newInstance();
        PlatformDecryptionKey decryptKey = platformKeyManager.getDecryptKey();
        Map<String, WrappedKey> wrappedKeys = mRecoverableKeyStoreDb.getAllKeys(
                mUserId, decryptKey.getGenerationId());
        return WrappedKey.unwrapKeys(decryptKey, wrappedKeys);
    }

    /**
     * Returns {@code true} if a sync is pending.
     */
    private boolean isSyncPending() {
        // TODO: implement properly. For now just always syncing if the user has any recoverable
        // keys. We need to keep track of when the store's state actually changes.
        return !mRecoverableKeyStoreDb.getAllKeys(
                mUserId, mRecoverableKeyStoreDb.getPlatformKeyGenerationId(mUserId)).isEmpty();
    }

    /**
     * The UI best suited to entering the given lock screen. This is synced with the vault so the
     * user can be shown the same UI when recovering the vault on another device.
     *
     * @return The format - either pattern, pin, or password.
     */
    @VisibleForTesting
    @KeyStoreRecoveryMetadata.LockScreenUiFormat static int getUiFormat(
            int credentialType, String credential) {
        if (credentialType == LockPatternUtils.CREDENTIAL_TYPE_PATTERN) {
            return KeyStoreRecoveryMetadata.TYPE_PATTERN;
        } else if (isPin(credential)) {
            return KeyStoreRecoveryMetadata.TYPE_PIN;
        } else {
            return KeyStoreRecoveryMetadata.TYPE_PASSWORD;
        }
    }

    /**
     * Generates a salt to include with the lock screen hash.
     *
     * @return The salt.
     */
    private byte[] generateSalt() {
        byte[] salt = new byte[SALT_LENGTH_BYTES];
        new SecureRandom().nextBytes(salt);
        return salt;
    }

    /**
     * Returns {@code true} if {@code credential} looks like a pin.
     */
    @VisibleForTesting
    static boolean isPin(@NonNull String credential) {
        int length = credential.length();
        for (int i = 0; i < length; i++) {
            if (!Character.isDigit(credential.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    /**
     * Hashes {@code credentials} with the given {@code salt}.
     *
     * @return The SHA-256 hash.
     */
    @VisibleForTesting
    static byte[] hashCredentials(byte[] salt, String credentials) {
        byte[] credentialsBytes = credentials.getBytes(StandardCharsets.UTF_8);
        ByteBuffer byteBuffer = ByteBuffer.allocate(
                salt.length + credentialsBytes.length + LENGTH_PREFIX_BYTES * 2);
        byteBuffer.order(ByteOrder.LITTLE_ENDIAN);
        byteBuffer.putInt(salt.length);
        byteBuffer.put(salt);
        byteBuffer.putInt(credentialsBytes.length);
        byteBuffer.put(credentialsBytes);
        byte[] bytes = byteBuffer.array();

        try {
            return MessageDigest.getInstance(LOCK_SCREEN_HASH_ALGORITHM).digest(bytes);
        } catch (NoSuchAlgorithmException e) {
            // Impossible, SHA-256 must be supported on Android.
            throw new RuntimeException(e);
        }
    }

    private static SecretKey generateRecoveryKey() throws NoSuchAlgorithmException {
        KeyGenerator keyGenerator = KeyGenerator.getInstance(RECOVERY_KEY_ALGORITHM);
        keyGenerator.init(RECOVERY_KEY_SIZE_BITS);
        return keyGenerator.generateKey();
    }

    /**
     * TODO: just replace with the Intent call. I'm not sure exactly what this looks like, hence
     * this interface, so I can test in the meantime.
     */
    public interface RecoverableSnapshotConsumer {
        void accept(
                byte[] salt,
                byte[] encryptedRecoveryKey,
                Map<String, byte[]> encryptedApplicationKeys);
    }

    /**
     * TODO: until this is in the database, so we can test.
     */
    public interface VaultKeySupplier {
        PublicKey get();
    }
}
