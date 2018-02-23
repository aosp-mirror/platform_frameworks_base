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

import static android.security.keystore.recovery.KeyChainProtectionParams.TYPE_LOCKSCREEN;

import android.annotation.Nullable;
import android.content.Context;
import android.security.keystore.recovery.KeyDerivationParams;
import android.security.keystore.recovery.KeyChainProtectionParams;
import android.security.keystore.recovery.KeyChainSnapshot;
import android.security.keystore.recovery.WrappedApplicationKey;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.ArrayUtils;
import com.android.internal.widget.LockPatternUtils;
import com.android.server.locksettings.recoverablekeystore.storage.RecoverableKeyStoreDb;
import com.android.server.locksettings.recoverablekeystore.storage.RecoverySnapshotStorage;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.KeyStoreException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.UnrecoverableKeyException;
import java.security.cert.CertPath;
import java.util.ArrayList;
import java.util.List;
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
    private static final int TRUSTED_HARDWARE_MAX_ATTEMPTS = 10;

    private final RecoverableKeyStoreDb mRecoverableKeyStoreDb;
    private final int mUserId;
    private final int mCredentialType;
    private final String mCredential;
    private final boolean mCredentialUpdated;
    private final PlatformKeyManager mPlatformKeyManager;
    private final RecoverySnapshotStorage mRecoverySnapshotStorage;
    private final RecoverySnapshotListenersStorage mSnapshotListenersStorage;

    public static KeySyncTask newInstance(
            Context context,
            RecoverableKeyStoreDb recoverableKeyStoreDb,
            RecoverySnapshotStorage snapshotStorage,
            RecoverySnapshotListenersStorage recoverySnapshotListenersStorage,
            int userId,
            int credentialType,
            String credential,
            boolean credentialUpdated
    ) throws NoSuchAlgorithmException, KeyStoreException, InsecureUserException {
        return new KeySyncTask(
                recoverableKeyStoreDb,
                snapshotStorage,
                recoverySnapshotListenersStorage,
                userId,
                credentialType,
                credential,
                credentialUpdated,
                PlatformKeyManager.getInstance(context, recoverableKeyStoreDb));
    }

    /**
     * A new task.
     *
     * @param recoverableKeyStoreDb Database where the keys are stored.
     * @param userId The uid of the user whose profile has been unlocked.
     * @param credentialType The type of credential as defined in {@code LockPatternUtils}
     * @param credential The credential, encoded as a {@link String}.
     * @param credentialUpdated signals weather credentials were updated.
     * @param platformKeyManager platform key manager
     */
    @VisibleForTesting
    KeySyncTask(
            RecoverableKeyStoreDb recoverableKeyStoreDb,
            RecoverySnapshotStorage snapshotStorage,
            RecoverySnapshotListenersStorage recoverySnapshotListenersStorage,
            int userId,
            int credentialType,
            String credential,
            boolean credentialUpdated,
            PlatformKeyManager platformKeyManager) {
        mSnapshotListenersStorage = recoverySnapshotListenersStorage;
        mRecoverableKeyStoreDb = recoverableKeyStoreDb;
        mUserId = userId;
        mCredentialType = credentialType;
        mCredential = credential;
        mCredentialUpdated = credentialUpdated;
        mPlatformKeyManager = platformKeyManager;
        mRecoverySnapshotStorage = snapshotStorage;
    }

    @Override
    public void run() {
        try {
            // Only one task is active If user unlocks phone many times in a short time interval.
            synchronized(KeySyncTask.class) {
                syncKeys();
            }
        } catch (Exception e) {
            Log.e(TAG, "Unexpected exception thrown during KeySyncTask", e);
        }
    }

    private void syncKeys() {
        if (mCredentialType == LockPatternUtils.CREDENTIAL_TYPE_NONE) {
            // Application keys for the user will not be available for sync.
            Log.w(TAG, "Credentials are not set for user " + mUserId);
            int generation = mPlatformKeyManager.getGenerationId(mUserId);
            mPlatformKeyManager.invalidatePlatformKey(mUserId, generation);
            return;
        }

        List<Integer> recoveryAgents = mRecoverableKeyStoreDb.getRecoveryAgents(mUserId);
        for (int uid : recoveryAgents) {
            syncKeysForAgent(uid);
        }
        if (recoveryAgents.isEmpty()) {
            Log.w(TAG, "No recovery agent initialized for user " + mUserId);
        }
    }

    private void syncKeysForAgent(int recoveryAgentUid) {
        boolean recreateCurrentVersion = false;
        if (!shoudCreateSnapshot(recoveryAgentUid)) {
            recreateCurrentVersion =
                    (mRecoverableKeyStoreDb.getSnapshotVersion(mUserId, recoveryAgentUid) != null)
                    && (mRecoverySnapshotStorage.get(recoveryAgentUid) == null);
            if (recreateCurrentVersion) {
                Log.d(TAG, "Recreating most recent snapshot");
            } else {
                Log.d(TAG, "Key sync not needed.");
                return;
            }
        }

        if (!mSnapshotListenersStorage.hasListener(recoveryAgentUid)) {
            Log.w(TAG, "No pending intent registered for recovery agent " + recoveryAgentUid);
            return;
        }

        PublicKey publicKey;
        CertPath certPath = mRecoverableKeyStoreDb.getRecoveryServiceCertPath(mUserId,
                recoveryAgentUid);
        if (certPath != null) {
            Log.d(TAG, "Using the public key in stored CertPath for syncing");
            publicKey = certPath.getCertificates().get(0).getPublicKey();
        } else {
            Log.d(TAG, "Using the stored raw public key for syncing");
            publicKey = mRecoverableKeyStoreDb.getRecoveryServicePublicKey(mUserId,
                    recoveryAgentUid);
        }
        if (publicKey == null) {
            Log.w(TAG, "Not initialized for KeySync: no public key set. Cancelling task.");
            return;
        }

        byte[] vaultHandle = mRecoverableKeyStoreDb.getServerParams(mUserId, recoveryAgentUid);
        if (vaultHandle == null) {
            Log.w(TAG, "No device ID set for user " + mUserId);
            return;
        }

        byte[] salt = generateSalt();
        byte[] localLskfHash = hashCredentials(salt, mCredential);

        Map<String, SecretKey> rawKeys;
        try {
            rawKeys = getKeysToSync(recoveryAgentUid);
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

        Long counterId;
        // counter id is generated exactly once for each credentials value.
        if (mCredentialUpdated) {
            counterId = generateAndStoreCounterId(recoveryAgentUid);
        } else {
            counterId = mRecoverableKeyStoreDb.getCounterId(mUserId, recoveryAgentUid);
            if (counterId == null) {
                counterId = generateAndStoreCounterId(recoveryAgentUid);
            }
        }

        // TODO: make sure the same counter id is used during recovery and remove temporary fix.
        counterId = 1L;

        byte[] vaultParams = KeySyncUtils.packVaultParams(
                publicKey,
                counterId,
                TRUSTED_HARDWARE_MAX_ATTEMPTS,
                vaultHandle);

        byte[] encryptedRecoveryKey;
        try {
            encryptedRecoveryKey = KeySyncUtils.thmEncryptRecoveryKey(
                    publicKey,
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
        KeyChainProtectionParams metadata = new KeyChainProtectionParams.Builder()
                .setUserSecretType(TYPE_LOCKSCREEN)
                .setLockScreenUiFormat(getUiFormat(mCredentialType, mCredential))
                .setKeyDerivationParams(KeyDerivationParams.createSha256Params(salt))
                .setSecret(new byte[0])
                .build();

        ArrayList<KeyChainProtectionParams> metadataList = new ArrayList<>();
        metadataList.add(metadata);

        // If application keys are not updated, snapshot will not be created on next unlock.
        mRecoverableKeyStoreDb.setShouldCreateSnapshot(mUserId, recoveryAgentUid, false);

        mRecoverySnapshotStorage.put(recoveryAgentUid, new KeyChainSnapshot.Builder()
                .setSnapshotVersion(getSnapshotVersion(recoveryAgentUid, recreateCurrentVersion))
                .setMaxAttempts(TRUSTED_HARDWARE_MAX_ATTEMPTS)
                .setCounterId(counterId)
                .setTrustedHardwarePublicKey(SecureBox.encodePublicKey(publicKey))
                .setTrustedHardwareCertPath(certPath)
                .setServerParams(vaultHandle)
                .setKeyChainProtectionParams(metadataList)
                .setWrappedApplicationKeys(createApplicationKeyEntries(encryptedApplicationKeys))
                .setEncryptedRecoveryKeyBlob(encryptedRecoveryKey)
                .build());

        mSnapshotListenersStorage.recoverySnapshotAvailable(recoveryAgentUid);
    }

    @VisibleForTesting
    int getSnapshotVersion(int recoveryAgentUid, boolean recreateCurrentVersion) {
        Long snapshotVersion = mRecoverableKeyStoreDb.getSnapshotVersion(mUserId, recoveryAgentUid);
        if (recreateCurrentVersion) {
            // version shouldn't be null at this moment.
            snapshotVersion = snapshotVersion == null ? 1 : snapshotVersion;
        } else {
            snapshotVersion = snapshotVersion == null ? 1 : snapshotVersion + 1;
        }
        mRecoverableKeyStoreDb.setSnapshotVersion(mUserId, recoveryAgentUid, snapshotVersion);

        return snapshotVersion.intValue();
    }

    private long generateAndStoreCounterId(int recoveryAgentUid) {
        long counter = new SecureRandom().nextLong();
        mRecoverableKeyStoreDb.setCounterId(mUserId, recoveryAgentUid, counter);
        return counter;
    }

    /**
     * Returns all of the recoverable keys for the user.
     */
    private Map<String, SecretKey> getKeysToSync(int recoveryAgentUid)
            throws InsecureUserException, KeyStoreException, UnrecoverableKeyException,
            NoSuchAlgorithmException, NoSuchPaddingException, BadPlatformKeyException,
            InvalidKeyException, InvalidAlgorithmParameterException {
        PlatformDecryptionKey decryptKey = mPlatformKeyManager.getDecryptKey(mUserId);;
        Map<String, WrappedKey> wrappedKeys = mRecoverableKeyStoreDb.getAllKeys(
                mUserId, recoveryAgentUid, decryptKey.getGenerationId());
        return WrappedKey.unwrapKeys(decryptKey, wrappedKeys);
    }

    /**
     * Returns {@code true} if a sync is pending.
     * @param recoveryAgentUid uid of the recovery agent.
     */
    private boolean shoudCreateSnapshot(int recoveryAgentUid) {
        int[] types = mRecoverableKeyStoreDb.getRecoverySecretTypes(mUserId, recoveryAgentUid);
        if (!ArrayUtils.contains(types, KeyChainProtectionParams.TYPE_LOCKSCREEN)) {
            // Only lockscreen type is supported.
            // We will need to pass extra argument to KeySyncTask to support custom pass phrase.
            return false;
        }
        if (mCredentialUpdated) {
            // Sync credential if at least one snapshot was created.
            if (mRecoverableKeyStoreDb.getSnapshotVersion(mUserId, recoveryAgentUid) != null) {
                mRecoverableKeyStoreDb.setShouldCreateSnapshot(mUserId, recoveryAgentUid, true);
                return true;
            }
        }

        return mRecoverableKeyStoreDb.getShouldCreateSnapshot(mUserId, recoveryAgentUid);
    }

    /**
     * The UI best suited to entering the given lock screen. This is synced with the vault so the
     * user can be shown the same UI when recovering the vault on another device.
     *
     * @return The format - either pattern, pin, or password.
     */
    @VisibleForTesting
    @KeyChainProtectionParams.LockScreenUiFormat static int getUiFormat(
            int credentialType, String credential) {
        if (credentialType == LockPatternUtils.CREDENTIAL_TYPE_PATTERN) {
            return KeyChainProtectionParams.UI_FORMAT_PATTERN;
        } else if (isPin(credential)) {
            return KeyChainProtectionParams.UI_FORMAT_PIN;
        } else {
            return KeyChainProtectionParams.UI_FORMAT_PASSWORD;
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
    static boolean isPin(@Nullable String credential) {
        if (credential == null) {
            return false;
        }
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

    private static List<WrappedApplicationKey> createApplicationKeyEntries(
            Map<String, byte[]> encryptedApplicationKeys) {
        ArrayList<WrappedApplicationKey> keyEntries = new ArrayList<>();
        for (String alias : encryptedApplicationKeys.keySet()) {
            keyEntries.add(new WrappedApplicationKey.Builder()
                    .setAlias(alias)
                    .setEncryptedKeyMaterial(encryptedApplicationKeys.get(alias))
                    .build());
        }
        return keyEntries;
    }
}
