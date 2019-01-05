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
import android.security.Scrypt;
import android.security.keystore.recovery.KeyChainProtectionParams;
import android.security.keystore.recovery.KeyChainSnapshot;
import android.security.keystore.recovery.KeyDerivationParams;
import android.security.keystore.recovery.WrappedApplicationKey;
import android.util.Log;
import android.util.Pair;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.ArrayUtils;
import com.android.internal.widget.LockPatternUtils;
import com.android.server.locksettings.recoverablekeystore.storage.RecoverableKeyStoreDb;
import com.android.server.locksettings.recoverablekeystore.storage.RecoverySnapshotStorage;

import java.io.IOException;
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
import java.security.cert.CertificateException;
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

    @VisibleForTesting
    static final int SCRYPT_PARAM_N = 4096;
    @VisibleForTesting
    static final int SCRYPT_PARAM_R = 8;
    @VisibleForTesting
    static final int SCRYPT_PARAM_P = 1;
    @VisibleForTesting
    static final int SCRYPT_PARAM_OUTLEN_BYTES = 32;

    private final RecoverableKeyStoreDb mRecoverableKeyStoreDb;
    private final int mUserId;
    private final int mCredentialType;
    private final String mCredential;
    private final boolean mCredentialUpdated;
    private final PlatformKeyManager mPlatformKeyManager;
    private final RecoverySnapshotStorage mRecoverySnapshotStorage;
    private final RecoverySnapshotListenersStorage mSnapshotListenersStorage;
    private final TestOnlyInsecureCertificateHelper mTestOnlyInsecureCertificateHelper;
    private final Scrypt mScrypt;

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
                PlatformKeyManager.getInstance(context, recoverableKeyStoreDb),
                new TestOnlyInsecureCertificateHelper(),
                new Scrypt());
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
     * @param testOnlyInsecureCertificateHelper utility class used for end-to-end tests
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
            PlatformKeyManager platformKeyManager,
            TestOnlyInsecureCertificateHelper testOnlyInsecureCertificateHelper,
            Scrypt scrypt) {
        mSnapshotListenersStorage = recoverySnapshotListenersStorage;
        mRecoverableKeyStoreDb = recoverableKeyStoreDb;
        mUserId = userId;
        mCredentialType = credentialType;
        mCredential = credential;
        mCredentialUpdated = credentialUpdated;
        mPlatformKeyManager = platformKeyManager;
        mRecoverySnapshotStorage = snapshotStorage;
        mTestOnlyInsecureCertificateHelper = testOnlyInsecureCertificateHelper;
        mScrypt = scrypt;
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
        if (isCustomLockScreen()) {
            Log.w(TAG, "Unsupported credential type " + mCredentialType + "for user " + mUserId);
            mRecoverableKeyStoreDb.invalidateKeysForUserIdOnCustomScreenLock(mUserId);
            return;
        }

        List<Integer> recoveryAgents = mRecoverableKeyStoreDb.getRecoveryAgents(mUserId);
        for (int uid : recoveryAgents) {
            try {
              syncKeysForAgent(uid);
            } catch (IOException e) {
                Log.e(TAG, "IOException during sync for agent " + uid, e);
            }
        }
        if (recoveryAgents.isEmpty()) {
            Log.w(TAG, "No recovery agent initialized for user " + mUserId);
        }
    }

    private boolean isCustomLockScreen() {
        return mCredentialType != LockPatternUtils.CREDENTIAL_TYPE_NONE
            && mCredentialType != LockPatternUtils.CREDENTIAL_TYPE_PATTERN
            && mCredentialType != LockPatternUtils.CREDENTIAL_TYPE_PASSWORD;
    }

    private void syncKeysForAgent(int recoveryAgentUid) throws IOException {
        boolean shouldRecreateCurrentVersion = false;
        if (!shouldCreateSnapshot(recoveryAgentUid)) {
            shouldRecreateCurrentVersion =
                    (mRecoverableKeyStoreDb.getSnapshotVersion(mUserId, recoveryAgentUid) != null)
                    && (mRecoverySnapshotStorage.get(recoveryAgentUid) == null);
            if (shouldRecreateCurrentVersion) {
                Log.d(TAG, "Recreating most recent snapshot");
            } else {
                Log.d(TAG, "Key sync not needed.");
                return;
            }
        }

        String rootCertAlias =
                mRecoverableKeyStoreDb.getActiveRootOfTrust(mUserId, recoveryAgentUid);
        rootCertAlias = mTestOnlyInsecureCertificateHelper
                .getDefaultCertificateAliasIfEmpty(rootCertAlias);

        PublicKey publicKey;
        CertPath certPath = mRecoverableKeyStoreDb.getRecoveryServiceCertPath(mUserId,
                recoveryAgentUid, rootCertAlias);
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

        if (mTestOnlyInsecureCertificateHelper.isTestOnlyCertificateAlias(rootCertAlias)) {
            Log.w(TAG, "Insecure root certificate is used by recovery agent "
                    + recoveryAgentUid);
            if (mTestOnlyInsecureCertificateHelper.doesCredentialSupportInsecureMode(
                    mCredentialType, mCredential)) {
                Log.w(TAG, "Whitelisted credential is used to generate snapshot by "
                        + "recovery agent "+ recoveryAgentUid);
            } else {
                Log.w(TAG, "Non whitelisted credential is used to generate recovery snapshot by "
                        + recoveryAgentUid + " - ignore attempt.");
                return; // User secret will not be used.
            }
        }

        boolean useScryptToHashCredential = shouldUseScryptToHashCredential();
        byte[] salt = generateSalt();
        byte[] localLskfHash;
        if (useScryptToHashCredential) {
            localLskfHash = hashCredentialsByScrypt(salt, mCredential);
        } else {
            localLskfHash = hashCredentialsBySaltedSha256(salt, mCredential);
        }

        Map<String, Pair<SecretKey, byte[]>> rawKeysWithMetadata;
        try {
            rawKeysWithMetadata = getKeysToSync(recoveryAgentUid);
        } catch (GeneralSecurityException e) {
            Log.e(TAG, "Failed to load recoverable keys for sync", e);
            return;
        } catch (InsecureUserException e) {
            Log.e(TAG, "A screen unlock triggered the key sync flow, so user must have "
                    + "lock screen. This should be impossible.", e);
            return;
        } catch (BadPlatformKeyException e) {
            Log.e(TAG, "Loaded keys for same generation ID as platform key, so "
                    + "BadPlatformKeyException should be impossible.", e);
            return;
        } catch (IOException e) {
            Log.e(TAG, "Local database error.", e);
            return;
        }
        // Only include insecure key material for test
        if (mTestOnlyInsecureCertificateHelper.isTestOnlyCertificateAlias(rootCertAlias)) {
            rawKeysWithMetadata =
                    mTestOnlyInsecureCertificateHelper.keepOnlyWhitelistedInsecureKeys(
                            rawKeysWithMetadata);
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
                    recoveryKey, rawKeysWithMetadata);
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

        KeyDerivationParams keyDerivationParams;
        if (useScryptToHashCredential) {
            keyDerivationParams = KeyDerivationParams.createScryptParams(
                    salt, /*memoryDifficulty=*/ SCRYPT_PARAM_N);
        } else {
            keyDerivationParams = KeyDerivationParams.createSha256Params(salt);
        }
        KeyChainProtectionParams keyChainProtectionParams = new KeyChainProtectionParams.Builder()
                .setUserSecretType(TYPE_LOCKSCREEN)
                .setLockScreenUiFormat(getUiFormat(mCredentialType, mCredential))
                .setKeyDerivationParams(keyDerivationParams)
                .setSecret(new byte[0])
                .build();

        ArrayList<KeyChainProtectionParams> metadataList = new ArrayList<>();
        metadataList.add(keyChainProtectionParams);

        KeyChainSnapshot.Builder keyChainSnapshotBuilder = new KeyChainSnapshot.Builder()
                .setSnapshotVersion(
                        getSnapshotVersion(recoveryAgentUid, shouldRecreateCurrentVersion))
                .setMaxAttempts(TRUSTED_HARDWARE_MAX_ATTEMPTS)
                .setCounterId(counterId)
                .setServerParams(vaultHandle)
                .setKeyChainProtectionParams(metadataList)
                .setWrappedApplicationKeys(
                        createApplicationKeyEntries(encryptedApplicationKeys, rawKeysWithMetadata))
                .setEncryptedRecoveryKeyBlob(encryptedRecoveryKey);
        try {
            keyChainSnapshotBuilder.setTrustedHardwareCertPath(certPath);
        } catch(CertificateException e) {
            // Should not happen, as it's just deserialized from bytes stored in the db
            Log.wtf(TAG, "Cannot serialize CertPath when calling setTrustedHardwareCertPath", e);
            return;
        }
        mRecoverySnapshotStorage.put(recoveryAgentUid, keyChainSnapshotBuilder.build());
        mSnapshotListenersStorage.recoverySnapshotAvailable(recoveryAgentUid);

        mRecoverableKeyStoreDb.setShouldCreateSnapshot(mUserId, recoveryAgentUid, false);
    }

    @VisibleForTesting
    int getSnapshotVersion(int recoveryAgentUid, boolean shouldRecreateCurrentVersion)
            throws IOException {
        Long snapshotVersion = mRecoverableKeyStoreDb.getSnapshotVersion(mUserId, recoveryAgentUid);
        if (shouldRecreateCurrentVersion) {
            // version shouldn't be null at this moment.
            snapshotVersion = snapshotVersion == null ? 1 : snapshotVersion;
        } else {
            snapshotVersion = snapshotVersion == null ? 1 : snapshotVersion + 1;
        }

        long updatedRows = mRecoverableKeyStoreDb.setSnapshotVersion(mUserId, recoveryAgentUid,
                snapshotVersion);
        if (updatedRows < 0) {
            Log.e(TAG, "Failed to set the snapshot version in the local DB.");
            throw new IOException("Failed to set the snapshot version in the local DB.");
        }

        return snapshotVersion.intValue();
    }

    private long generateAndStoreCounterId(int recoveryAgentUid) throws IOException {
        long counter = new SecureRandom().nextLong();
        long updatedRows = mRecoverableKeyStoreDb.setCounterId(mUserId, recoveryAgentUid, counter);
        if (updatedRows < 0) {
            Log.e(TAG, "Failed to set the snapshot version in the local DB.");
            throw new IOException("Failed to set counterId in the local DB.");
        }
        return counter;
    }

    /**
     * Returns all of the recoverable keys for the user.
     */
    private Map<String, Pair<SecretKey, byte[]>> getKeysToSync(int recoveryAgentUid)
            throws InsecureUserException, KeyStoreException, UnrecoverableKeyException,
            NoSuchAlgorithmException, NoSuchPaddingException, BadPlatformKeyException,
            InvalidKeyException, InvalidAlgorithmParameterException, IOException {
        PlatformDecryptionKey decryptKey = mPlatformKeyManager.getDecryptKey(mUserId);;
        Map<String, WrappedKey> wrappedKeys = mRecoverableKeyStoreDb.getAllKeys(
                mUserId, recoveryAgentUid, decryptKey.getGenerationId());
        return WrappedKey.unwrapKeys(decryptKey, wrappedKeys);
    }

    /**
     * Returns {@code true} if a sync is pending.
     * @param recoveryAgentUid uid of the recovery agent.
     */
    private boolean shouldCreateSnapshot(int recoveryAgentUid) {
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
    private static byte[] generateSalt() {
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
    static byte[] hashCredentialsBySaltedSha256(byte[] salt, String credentials) {
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

    private byte[] hashCredentialsByScrypt(byte[] salt, String credentials) {
        return mScrypt.scrypt(
                credentials.getBytes(StandardCharsets.UTF_8), salt,
                SCRYPT_PARAM_N, SCRYPT_PARAM_R, SCRYPT_PARAM_P, SCRYPT_PARAM_OUTLEN_BYTES);
    }

    private static SecretKey generateRecoveryKey() throws NoSuchAlgorithmException {
        KeyGenerator keyGenerator = KeyGenerator.getInstance(RECOVERY_KEY_ALGORITHM);
        keyGenerator.init(RECOVERY_KEY_SIZE_BITS);
        return keyGenerator.generateKey();
    }

    private static List<WrappedApplicationKey> createApplicationKeyEntries(
            Map<String, byte[]> encryptedApplicationKeys,
            Map<String, Pair<SecretKey, byte[]>> originalKeysWithMetadata) {
        ArrayList<WrappedApplicationKey> keyEntries = new ArrayList<>();
        for (String alias : encryptedApplicationKeys.keySet()) {
            keyEntries.add(new WrappedApplicationKey.Builder()
                    .setAlias(alias)
                    .setEncryptedKeyMaterial(encryptedApplicationKeys.get(alias))
                    .setMetadata(originalKeysWithMetadata.get(alias).second)
                    .build());
        }
        return keyEntries;
    }

    private boolean shouldUseScryptToHashCredential() {
        return mCredentialType == LockPatternUtils.CREDENTIAL_TYPE_PASSWORD;
    }
}
