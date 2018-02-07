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

import static android.security.keystore.RecoveryController.ERROR_BAD_CERTIFICATE_FORMAT;
import static android.security.keystore.RecoveryController.ERROR_DECRYPTION_FAILED;
import static android.security.keystore.RecoveryController.ERROR_INSECURE_USER;
import static android.security.keystore.RecoveryController.ERROR_NO_SNAPSHOT_PENDING;
import static android.security.keystore.RecoveryController.ERROR_SERVICE_INTERNAL_ERROR;
import static android.security.keystore.RecoveryController.ERROR_SESSION_EXPIRED;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.PendingIntent;
import android.content.Context;
import android.Manifest;
import android.os.Binder;
import android.os.RemoteException;
import android.os.ServiceSpecificException;
import android.os.UserHandle;

import android.security.keystore.recovery.KeyChainProtectionParams;
import android.security.keystore.recovery.KeyChainSnapshot;
import android.security.keystore.recovery.RecoveryController;
import android.security.keystore.recovery.WrappedApplicationKey;
import android.security.KeyStore;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.HexDump;
import com.android.server.locksettings.recoverablekeystore.storage.ApplicationKeyStorage;
import com.android.server.locksettings.recoverablekeystore.storage.RecoverableKeyStoreDb;
import com.android.server.locksettings.recoverablekeystore.storage.RecoverySessionStorage;
import com.android.server.locksettings.recoverablekeystore.storage.RecoverySnapshotStorage;

import java.security.InvalidKeyException;
import java.security.KeyFactory;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.security.spec.InvalidKeySpecException;
import java.security.UnrecoverableKeyException;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.crypto.AEADBadTagException;

/**
 * Class with {@link RecoveryController} API implementation and internal methods to interact
 * with {@code LockSettingsService}.
 *
 * @hide
 */
public class RecoverableKeyStoreManager {
    private static final String TAG = "RecoverableKeyStoreMgr";

    private static RecoverableKeyStoreManager mInstance;

    private final Context mContext;
    private final RecoverableKeyStoreDb mDatabase;
    private final RecoverySessionStorage mRecoverySessionStorage;
    private final ExecutorService mExecutorService;
    private final RecoverySnapshotListenersStorage mListenersStorage;
    private final RecoverableKeyGenerator mRecoverableKeyGenerator;
    private final RecoverySnapshotStorage mSnapshotStorage;
    private final PlatformKeyManager mPlatformKeyManager;
    private final KeyStore mKeyStore;
    private final ApplicationKeyStorage mApplicationKeyStorage;

    /**
     * Returns a new or existing instance.
     *
     * @hide
     */
    public static synchronized RecoverableKeyStoreManager
            getInstance(Context context, KeyStore keystore) {
        if (mInstance == null) {
            RecoverableKeyStoreDb db = RecoverableKeyStoreDb.newInstance(context);
            PlatformKeyManager platformKeyManager;
            ApplicationKeyStorage applicationKeyStorage;
            try {
                platformKeyManager = PlatformKeyManager.getInstance(context, db);
                applicationKeyStorage = ApplicationKeyStorage.getInstance(keystore);
            } catch (NoSuchAlgorithmException e) {
                // Impossible: all algorithms must be supported by AOSP
                throw new RuntimeException(e);
            } catch (KeyStoreException e) {
                throw new ServiceSpecificException(ERROR_SERVICE_INTERNAL_ERROR, e.getMessage());
            }

            mInstance = new RecoverableKeyStoreManager(
                    context.getApplicationContext(),
                    keystore,
                    db,
                    new RecoverySessionStorage(),
                    Executors.newSingleThreadExecutor(),
                    new RecoverySnapshotStorage(),
                    new RecoverySnapshotListenersStorage(),
                    platformKeyManager,
                    applicationKeyStorage);
        }
        return mInstance;
    }

    @VisibleForTesting
    RecoverableKeyStoreManager(
            Context context,
            KeyStore keystore,
            RecoverableKeyStoreDb recoverableKeyStoreDb,
            RecoverySessionStorage recoverySessionStorage,
            ExecutorService executorService,
            RecoverySnapshotStorage snapshotStorage,
            RecoverySnapshotListenersStorage listenersStorage,
            PlatformKeyManager platformKeyManager,
            ApplicationKeyStorage applicationKeyStorage) {
        mContext = context;
        mKeyStore = keystore;
        mDatabase = recoverableKeyStoreDb;
        mRecoverySessionStorage = recoverySessionStorage;
        mExecutorService = executorService;
        mListenersStorage = listenersStorage;
        mSnapshotStorage = snapshotStorage;
        mPlatformKeyManager = platformKeyManager;
        mApplicationKeyStorage = applicationKeyStorage;

        try {
            mRecoverableKeyGenerator = RecoverableKeyGenerator.newInstance(mDatabase);
        } catch (NoSuchAlgorithmException e) {
            Log.wtf(TAG, "AES keygen algorithm not available. AOSP must support this.", e);
            throw new ServiceSpecificException(ERROR_SERVICE_INTERNAL_ERROR, e.getMessage());
        }
    }

    public void initRecoveryService(
            @NonNull String rootCertificateAlias, @NonNull byte[] signedPublicKeyList)
            throws RemoteException {
        checkRecoverKeyStorePermission();
        int userId = UserHandle.getCallingUserId();
        int uid = Binder.getCallingUid();
        // TODO: open /system/etc/security/... cert file, and check the signature on the public keys
        PublicKey publicKey;
        try {
            KeyFactory kf = KeyFactory.getInstance("EC");
            // TODO: Randomly choose a key from the list -- right now we just use the whole input
            X509EncodedKeySpec pkSpec = new X509EncodedKeySpec(signedPublicKeyList);
            publicKey = kf.generatePublic(pkSpec);
        } catch (NoSuchAlgorithmException e) {
            Log.wtf(TAG, "EC algorithm not available. AOSP must support this.", e);
            throw new ServiceSpecificException(ERROR_SERVICE_INTERNAL_ERROR, e.getMessage());
        } catch (InvalidKeySpecException e) {
            throw new ServiceSpecificException(
                    ERROR_BAD_CERTIFICATE_FORMAT, "Not a valid X509 certificate.");
        }
        long updatedRows = mDatabase.setRecoveryServicePublicKey(userId, uid, publicKey);
        if (updatedRows > 0) {
            mDatabase.setShouldCreateSnapshot(userId, uid, true);
        }
    }

    /**
     * Gets all data necessary to recover application keys on new device.
     *
     * @return recovery data
     * @hide
     */
    public @NonNull
    KeyChainSnapshot getKeyChainSnapshot()
            throws RemoteException {
        checkRecoverKeyStorePermission();
        int uid = Binder.getCallingUid();
        KeyChainSnapshot snapshot = mSnapshotStorage.get(uid);
        if (snapshot == null) {
            throw new ServiceSpecificException(ERROR_NO_SNAPSHOT_PENDING);
        }
        return snapshot;
    }

    public void setSnapshotCreatedPendingIntent(@Nullable PendingIntent intent)
            throws RemoteException {
        checkRecoverKeyStorePermission();
        int uid = Binder.getCallingUid();
        mListenersStorage.setSnapshotListener(uid, intent);
    }

    /**
     * Gets recovery snapshot versions for all accounts. Note that snapshot may have 0 application
     * keys, but it still needs to be synced, if previous versions were not empty.
     *
     * @return Map from Recovery agent account to snapshot version.
     */
    public @NonNull Map<byte[], Integer> getRecoverySnapshotVersions()
            throws RemoteException {
        checkRecoverKeyStorePermission();
        throw new UnsupportedOperationException();
    }

    public void setServerParams(byte[] serverParams) throws RemoteException {
        checkRecoverKeyStorePermission();
        int userId = UserHandle.getCallingUserId();
        int uid = Binder.getCallingUid();
        long updatedRows = mDatabase.setServerParams(userId, uid, serverParams);
        if (updatedRows > 0) {
            mDatabase.setShouldCreateSnapshot(userId, uid, true);
        }
    }

    /**
     * Updates recovery status for the application given its {@code packageName}.
     *
     * @param packageName which recoverable key statuses will be returned
     * @param aliases - KeyStore aliases or {@code null} for all aliases of the app
     * @param status - new status
     */
    public void setRecoveryStatus(
            @NonNull String packageName, @Nullable String[] aliases, int status)
            throws RemoteException {
        checkRecoverKeyStorePermission();
        int uid = Binder.getCallingUid();
        if (packageName != null) {
            // TODO: get uid for package name, when many apps are supported.
        }
        if (aliases == null) {
            // Get all keys for the app.
            Map<String, Integer> allKeys = mDatabase.getStatusForAllKeys(uid);
            aliases = new String[allKeys.size()];
            allKeys.keySet().toArray(aliases);
        }
        for (String alias: aliases) {
            mDatabase.setRecoveryStatus(uid, alias, status);
        }
    }

    /**
     * Gets recovery status for caller or other application {@code packageName}.
     * @param packageName which recoverable keys statuses will be returned.
     *
     * @return {@code Map} from KeyStore alias to recovery status.
     */
    public @NonNull Map<String, Integer> getRecoveryStatus(@Nullable String packageName)
            throws RemoteException {
        // Any application should be able to check status for its own keys.
        // If caller is a recovery agent it can check statuses for other packages, but
        // only for recoverable keys it manages.
        return mDatabase.getStatusForAllKeys(Binder.getCallingUid());
    }

    /**
     * Sets recovery secrets list used by all recovery agents for given {@code userId}
     *
     * @hide
     */
    public void setRecoverySecretTypes(
            @NonNull @KeyChainProtectionParams.UserSecretType int[] secretTypes)
            throws RemoteException {
        checkRecoverKeyStorePermission();
        int userId = UserHandle.getCallingUserId();
        int uid = Binder.getCallingUid();
        long updatedRows = mDatabase.setRecoverySecretTypes(userId, uid, secretTypes);
        if (updatedRows > 0) {
            mDatabase.setShouldCreateSnapshot(userId, uid, true);
        }
    }

    /**
     * Gets secret types necessary to create Recovery Data.
     *
     * @return secret types
     * @hide
     */
    public @NonNull int[] getRecoverySecretTypes() throws RemoteException {
        checkRecoverKeyStorePermission();
        return mDatabase.getRecoverySecretTypes(UserHandle.getCallingUserId(),
            Binder.getCallingUid());
    }

    /**
     * Gets secret types RecoveryManagers is waiting for to create new Recovery Data.
     *
     * @return secret types
     * @hide
     */
    public @NonNull int[] getPendingRecoverySecretTypes() throws RemoteException {
        checkRecoverKeyStorePermission();
        throw new UnsupportedOperationException();
    }

    public void recoverySecretAvailable(
            @NonNull KeyChainProtectionParams recoverySecret) throws RemoteException {
        int uid = Binder.getCallingUid();
        if (recoverySecret.getLockScreenUiFormat() == KeyChainProtectionParams.TYPE_LOCKSCREEN) {
            throw new SecurityException(
                    "Caller " + uid + " is not allowed to set lock screen secret");
        }
        checkRecoverKeyStorePermission();
        // TODO: add hook from LockSettingsService to set lock screen secret.
        throw new UnsupportedOperationException();
    }

    /**
     * Initializes recovery session.
     *
     * @param sessionId A unique ID to identify the recovery session.
     * @param verifierPublicKey X509-encoded public key.
     * @param vaultParams Additional params associated with vault.
     * @param vaultChallenge Challenge issued by vault service.
     * @param secrets Lock-screen hashes. For now only a single secret is supported.
     * @return Encrypted bytes of recovery claim. This can then be issued to the vault service.
     *
     * @hide
     */
    public @NonNull byte[] startRecoverySession(
            @NonNull String sessionId,
            @NonNull byte[] verifierPublicKey,
            @NonNull byte[] vaultParams,
            @NonNull byte[] vaultChallenge,
            @NonNull List<KeyChainProtectionParams> secrets)
            throws RemoteException {
        checkRecoverKeyStorePermission();
        int uid = Binder.getCallingUid();

        if (secrets.size() != 1) {
            throw new UnsupportedOperationException(
                    "Only a single KeyChainProtectionParams is supported");
        }

        PublicKey publicKey;
        try {
            publicKey = KeySyncUtils.deserializePublicKey(verifierPublicKey);
        } catch (NoSuchAlgorithmException e) {
            // Should never happen
            throw new RuntimeException(e);
        } catch (InvalidKeySpecException e) {
            throw new ServiceSpecificException(ERROR_BAD_CERTIFICATE_FORMAT, "Not a valid X509 key");
        }
        // The raw public key bytes contained in vaultParams must match the ones given in
        // verifierPublicKey; otherwise, the user secret may be decrypted by a key that is not owned
        // by the original recovery service.
        if (!publicKeysMatch(publicKey, vaultParams)) {
            throw new ServiceSpecificException(ERROR_BAD_CERTIFICATE_FORMAT,
                    "The public keys given in verifierPublicKey and vaultParams do not match.");
        }

        byte[] keyClaimant = KeySyncUtils.generateKeyClaimant();
        byte[] kfHash = secrets.get(0).getSecret();
        mRecoverySessionStorage.add(
                uid,
                new RecoverySessionStorage.Entry(sessionId, kfHash, keyClaimant, vaultParams));

        try {
            byte[] thmKfHash = KeySyncUtils.calculateThmKfHash(kfHash);
            return KeySyncUtils.encryptRecoveryClaim(
                    publicKey,
                    vaultParams,
                    vaultChallenge,
                    thmKfHash,
                    keyClaimant);
        } catch (NoSuchAlgorithmException e) {
            Log.wtf(TAG, "SecureBox algorithm missing. AOSP must support this.", e);
            throw new ServiceSpecificException(ERROR_SERVICE_INTERNAL_ERROR, e.getMessage());
        } catch (InvalidKeyException e) {
            throw new ServiceSpecificException(ERROR_BAD_CERTIFICATE_FORMAT, e.getMessage());
        }
    }

    /**
     * Invoked by a recovery agent after a successful recovery claim is sent to the remote vault
     * service.
     *
     * @param sessionId The session ID used to generate the claim. See
     *     {@link #startRecoverySession(String, byte[], byte[], byte[], List)}.
     * @param encryptedRecoveryKey The encrypted recovery key blob returned by the remote vault
     *     service.
     * @param applicationKeys The encrypted key blobs returned by the remote vault service. These
     *     were wrapped with the recovery key.
     * @return Map from alias to raw key material.
     * @throws RemoteException if an error occurred recovering the keys.
     */
    public Map<String, byte[]> recoverKeys(
            @NonNull String sessionId,
            @NonNull byte[] encryptedRecoveryKey,
            @NonNull List<WrappedApplicationKey> applicationKeys)
            throws RemoteException {
        checkRecoverKeyStorePermission();
        int uid = Binder.getCallingUid();
        RecoverySessionStorage.Entry sessionEntry = mRecoverySessionStorage.get(uid, sessionId);
        if (sessionEntry == null) {
            throw new ServiceSpecificException(ERROR_SESSION_EXPIRED,
                    String.format(Locale.US,
                    "Application uid=%d does not have pending session '%s'", uid, sessionId));
        }

        try {
            byte[] recoveryKey = decryptRecoveryKey(sessionEntry, encryptedRecoveryKey);
            return recoverApplicationKeys(recoveryKey, applicationKeys);
        } finally {
            sessionEntry.destroy();
            mRecoverySessionStorage.remove(uid);
        }
    }

    /**
     * Deprecated
     * Generates a key named {@code alias} in the recoverable store for the calling uid. Then
     * returns the raw key material.
     *
     * <p>TODO: Once AndroidKeyStore has added move api, do not return raw bytes.
     *
     * @hide
     */
    public byte[] generateAndStoreKey(@NonNull String alias) throws RemoteException {
        int uid = Binder.getCallingUid();
        int userId = UserHandle.getCallingUserId();

        PlatformEncryptionKey encryptionKey;
        try {
            encryptionKey = mPlatformKeyManager.getEncryptKey(userId);
        } catch (NoSuchAlgorithmException e) {
            // Impossible: all algorithms must be supported by AOSP
            throw new RuntimeException(e);
        } catch (KeyStoreException | UnrecoverableKeyException e) {
            throw new ServiceSpecificException(ERROR_SERVICE_INTERNAL_ERROR, e.getMessage());
        } catch (InsecureUserException e) {
            throw new ServiceSpecificException(ERROR_INSECURE_USER, e.getMessage());
        }

        try {
            return mRecoverableKeyGenerator.generateAndStoreKey(encryptionKey, userId, uid, alias);
        } catch (KeyStoreException | InvalidKeyException | RecoverableKeyStorageException e) {
            throw new ServiceSpecificException(ERROR_SERVICE_INTERNAL_ERROR, e.getMessage());
        }
    }

    /**
     * Destroys the session with the given {@code sessionId}.
     */
    public void closeSession(@NonNull String sessionId) throws RemoteException {
        mRecoverySessionStorage.remove(Binder.getCallingUid(), sessionId);
    }

    public void removeKey(@NonNull String alias) throws RemoteException {
        int uid = Binder.getCallingUid();
        int userId = UserHandle.getCallingUserId();

        boolean wasRemoved = mDatabase.removeKey(uid, alias);
        if (wasRemoved) {
            mDatabase.setShouldCreateSnapshot(userId, uid, true);
            mApplicationKeyStorage.deleteEntry(userId, uid, alias);
        }
    }

    /**
     * Generates a key named {@code alias} in caller's namespace.
     * The key is stored in system service keystore namespace.
     *
     * @return grant alias, which caller can use to access the key.
     */
    public String generateKey(@NonNull String alias, byte[] account) throws RemoteException {
        int uid = Binder.getCallingUid();
        int userId = UserHandle.getCallingUserId();

        PlatformEncryptionKey encryptionKey;
        try {
            encryptionKey = mPlatformKeyManager.getEncryptKey(userId);
        } catch (NoSuchAlgorithmException e) {
            // Impossible: all algorithms must be supported by AOSP
            throw new RuntimeException(e);
        } catch (KeyStoreException | UnrecoverableKeyException e) {
            throw new ServiceSpecificException(ERROR_SERVICE_INTERNAL_ERROR, e.getMessage());
        } catch (InsecureUserException e) {
            throw new ServiceSpecificException(ERROR_INSECURE_USER, e.getMessage());
        }

        try {
            byte[] secretKey =
                    mRecoverableKeyGenerator.generateAndStoreKey(encryptionKey, userId, uid, alias);
            mApplicationKeyStorage.setSymmetricKeyEntry(userId, uid, alias, secretKey);
            String grantAlias = mApplicationKeyStorage.getGrantAlias(userId, uid, alias);
            return grantAlias;
        } catch (KeyStoreException | InvalidKeyException | RecoverableKeyStorageException e) {
            throw new ServiceSpecificException(ERROR_SERVICE_INTERNAL_ERROR, e.getMessage());
        }
    }

    /**
     * Gets a key named {@code alias} in caller's namespace.
     *
     * @return grant alias, which caller can use to access the key.
     */
    public String getKey(@NonNull String alias) throws RemoteException {
        int uid = Binder.getCallingUid();
        int userId = UserHandle.getCallingUserId();
        String grantAlias = mApplicationKeyStorage.getGrantAlias(userId, uid, alias);
        return grantAlias;
    }

    private byte[] decryptRecoveryKey(
            RecoverySessionStorage.Entry sessionEntry, byte[] encryptedClaimResponse)
            throws RemoteException, ServiceSpecificException {
        byte[] locallyEncryptedKey;
        try {
            // TODO: Remove the extraneous logging here
            Log.d(TAG, constructLoggingMessage("sessionEntry.getKeyClaimant()",
                    sessionEntry.getKeyClaimant()));
            Log.d(TAG, constructLoggingMessage("sessionEntry.getVaultParams()",
                    sessionEntry.getVaultParams()));
            Log.d(TAG, constructLoggingMessage("encryptedClaimResponse", encryptedClaimResponse));
            locallyEncryptedKey = KeySyncUtils.decryptRecoveryClaimResponse(
                    sessionEntry.getKeyClaimant(),
                    sessionEntry.getVaultParams(),
                    encryptedClaimResponse);
        } catch (InvalidKeyException e) {
            Log.e(TAG, "Got InvalidKeyException during decrypting recovery claim response", e);
            throw new ServiceSpecificException(ERROR_DECRYPTION_FAILED,
                    "Failed to decrypt recovery key " + e.getMessage());
        } catch (AEADBadTagException e) {
            Log.e(TAG, "Got AEADBadTagException during decrypting recovery claim response", e);
            throw new ServiceSpecificException(ERROR_DECRYPTION_FAILED,
                    "Failed to decrypt recovery key " + e.getMessage());
        } catch (NoSuchAlgorithmException e) {
            // Should never happen: all the algorithms used are required by AOSP implementations
            throw new ServiceSpecificException(ERROR_SERVICE_INTERNAL_ERROR, e.getMessage());
        }

        try {
            // TODO: Remove the extraneous logging here
            Log.d(TAG, constructLoggingMessage("sessionEntry.getLskfHash()",
                    sessionEntry.getLskfHash()));
            Log.d(TAG, constructLoggingMessage("locallyEncryptedKey", locallyEncryptedKey));
            return KeySyncUtils.decryptRecoveryKey(sessionEntry.getLskfHash(), locallyEncryptedKey);
        } catch (InvalidKeyException e) {
            Log.e(TAG, "Got InvalidKeyException during decrypting recovery key", e);
            throw new ServiceSpecificException(ERROR_DECRYPTION_FAILED,
                    "Failed to decrypt recovery key " + e.getMessage());
        } catch (AEADBadTagException e) {
            Log.e(TAG, "Got AEADBadTagException during decrypting recovery key", e);
            throw new ServiceSpecificException(ERROR_DECRYPTION_FAILED,
                    "Failed to decrypt recovery key " + e.getMessage());
        } catch (NoSuchAlgorithmException e) {
            // Should never happen: all the algorithms used are required by AOSP implementations
            throw new ServiceSpecificException(ERROR_SERVICE_INTERNAL_ERROR, e.getMessage());
        }
    }

    private String constructLoggingMessage(String key, byte[] value) {
        if (value == null) {
            return key + " is null";
        } else {
            return key + ": " + HexDump.toHexString(value);
        }
    }

    /**
     * Uses {@code recoveryKey} to decrypt {@code applicationKeys}.
     *
     * @return Map from alias to raw key material.
     * @throws RemoteException if an error occurred decrypting the keys.
     */
    private Map<String, byte[]> recoverApplicationKeys(
            @NonNull byte[] recoveryKey,
            @NonNull List<WrappedApplicationKey> applicationKeys) throws RemoteException {
        HashMap<String, byte[]> keyMaterialByAlias = new HashMap<>();
        for (WrappedApplicationKey applicationKey : applicationKeys) {
            String alias = applicationKey.getAlias();
            byte[] encryptedKeyMaterial = applicationKey.getEncryptedKeyMaterial();

            try {
                // TODO: Remove the extraneous logging here
                Log.d(TAG, constructLoggingMessage("recoveryKey", recoveryKey));
                Log.d(TAG, constructLoggingMessage("encryptedKeyMaterial", encryptedKeyMaterial));
                byte[] keyMaterial =
                        KeySyncUtils.decryptApplicationKey(recoveryKey, encryptedKeyMaterial);
                keyMaterialByAlias.put(alias, keyMaterial);
            } catch (NoSuchAlgorithmException e) {
                Log.wtf(TAG, "Missing SecureBox algorithm. AOSP required to support this.", e);
                throw new ServiceSpecificException(
                        ERROR_SERVICE_INTERNAL_ERROR, e.getMessage());
            } catch (InvalidKeyException e) {
                Log.e(TAG, "Got InvalidKeyException during decrypting application key with alias: "
                        + alias, e);
                throw new ServiceSpecificException(ERROR_DECRYPTION_FAILED,
                        "Failed to recover key with alias '" + alias + "': " + e.getMessage());
            } catch (AEADBadTagException e) {
                Log.e(TAG, "Got AEADBadTagException during decrypting application key with alias: "
                        + alias, e);
                // Ignore the exception to continue to recover the other application keys.
            }
        }
        if (keyMaterialByAlias.isEmpty()) {
            Log.e(TAG, "Failed to recover any of the application keys.");
            throw new ServiceSpecificException(ERROR_DECRYPTION_FAILED,
                    "Failed to recover any of the application keys.");
        }
        return keyMaterialByAlias;
    }

    /**
     * This function can only be used inside LockSettingsService.
     *
     * @param storedHashType from {@code CredentialHash}
     * @param credential - unencrypted String. Password length should be at most 16 symbols {@code
     *     mPasswordMaxLength}
     * @param userId for user who just unlocked the device.
     * @hide
     */
    public void lockScreenSecretAvailable(
            int storedHashType, @NonNull String credential, int userId) {
        // So as not to block the critical path unlocking the phone, defer to another thread.
        try {
            mExecutorService.execute(KeySyncTask.newInstance(
                    mContext,
                    mDatabase,
                    mSnapshotStorage,
                    mListenersStorage,
                    userId,
                    storedHashType,
                    credential,
                    /*credentialUpdated=*/ false));
        } catch (NoSuchAlgorithmException e) {
            Log.wtf(TAG, "Should never happen - algorithm unavailable for KeySync", e);
        } catch (KeyStoreException e) {
            Log.e(TAG, "Key store error encountered during recoverable key sync", e);
        } catch (InsecureUserException e) {
            Log.wtf(TAG, "Impossible - insecure user, but user just entered lock screen", e);
        }
    }

    /**
     * This function can only be used inside LockSettingsService.
     *
     * @param storedHashType from {@code CredentialHash}
     * @param credential - unencrypted String
     * @param userId for the user whose lock screen credentials were changed.
     * @hide
     */
    public void lockScreenSecretChanged(
            int storedHashType,
            @Nullable String credential,
            int userId) {
        // So as not to block the critical path unlocking the phone, defer to another thread.
        try {
            mExecutorService.execute(KeySyncTask.newInstance(
                    mContext,
                    mDatabase,
                    mSnapshotStorage,
                    mListenersStorage,
                    userId,
                    storedHashType,
                    credential,
                    /*credentialUpdated=*/ true));
        } catch (NoSuchAlgorithmException e) {
            Log.wtf(TAG, "Should never happen - algorithm unavailable for KeySync", e);
        } catch (KeyStoreException e) {
            Log.e(TAG, "Key store error encountered during recoverable key sync", e);
        } catch (InsecureUserException e) {
            Log.e(TAG, "InsecureUserException during lock screen secret update", e);
        }
    }

    private void checkRecoverKeyStorePermission() {
        mContext.enforceCallingOrSelfPermission(
                Manifest.permission.RECOVER_KEYSTORE,
                "Caller " + Binder.getCallingUid() + " doesn't have RecoverKeyStore permission.");
    }

    private boolean publicKeysMatch(PublicKey publicKey, byte[] vaultParams) {
        byte[] encodedPublicKey = SecureBox.encodePublicKey(publicKey);
        return Arrays.equals(encodedPublicKey, Arrays.copyOf(vaultParams, encodedPublicKey.length));
    }
}
