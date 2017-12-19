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
import android.annotation.Nullable;
import android.app.PendingIntent;
import android.content.Context;
import android.os.Binder;
import android.os.RemoteException;
import android.os.ServiceSpecificException;
import android.os.UserHandle;

import android.security.recoverablekeystore.KeyEntryRecoveryData;
import android.security.recoverablekeystore.KeyStoreRecoveryData;
import android.security.recoverablekeystore.KeyStoreRecoveryMetadata;
import android.security.recoverablekeystore.RecoverableKeyStoreLoader;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.widget.LockPatternUtils;
import com.android.server.locksettings.recoverablekeystore.storage.RecoverableKeyStoreDb;
import com.android.server.locksettings.recoverablekeystore.storage.RecoverySessionStorage;

import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.security.spec.InvalidKeySpecException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Class with {@link RecoverableKeyStoreLoader} API implementation and internal methods to interact
 * with {@code LockSettingsService}.
 *
 * @hide
 */
public class RecoverableKeyStoreManager {
    private static final String TAG = "RecoverableKeyStoreManager";
    private static RecoverableKeyStoreManager mInstance;

    private final Context mContext;
    private final RecoverableKeyStoreDb mDatabase;
    private final RecoverySessionStorage mRecoverySessionStorage;

    /**
     * Returns a new or existing instance.
     *
     * @hide
     */
    public static synchronized RecoverableKeyStoreManager getInstance(Context mContext) {
        if (mInstance == null) {
            RecoverableKeyStoreDb db = RecoverableKeyStoreDb.newInstance(mContext);
            mInstance = new RecoverableKeyStoreManager(
                    mContext.getApplicationContext(),
                    db,
                    new RecoverySessionStorage());
        }
        return mInstance;
    }

    @VisibleForTesting
    RecoverableKeyStoreManager(
            Context context,
            RecoverableKeyStoreDb recoverableKeyStoreDb,
            RecoverySessionStorage recoverySessionStorage) {
        mContext = context;
        mDatabase = recoverableKeyStoreDb;
        mRecoverySessionStorage = recoverySessionStorage;
    }

    public int initRecoveryService(
            @NonNull String rootCertificateAlias, @NonNull byte[] signedPublicKeyList, int userId)
            throws RemoteException {
        checkRecoverKeyStorePermission();
        // TODO open /system/etc/security/... cert file
        throw new UnsupportedOperationException();
    }

    /**
     * Gets all data necessary to recover application keys on new device.
     *
     * @return recovery data
     * @hide
     */
    public @NonNull KeyStoreRecoveryData getRecoveryData(@NonNull byte[] account, int userId)
            throws RemoteException {
        checkRecoverKeyStorePermission();
        final int callingUid = Binder.getCallingUid(); // Recovery agent uid.
        final int callingUserId = UserHandle.getCallingUserId();
        final long callingIdentiy = Binder.clearCallingIdentity();
        try {
            // TODO: Return the latest snapshot for the calling recovery agent.
        } finally {
            Binder.restoreCallingIdentity(callingIdentiy);
        }

        // KeyStoreRecoveryData without application keys and empty recovery blob.
        KeyStoreRecoveryData recoveryData =
                new KeyStoreRecoveryData(
                        /*snapshotVersion=*/ 1,
                        new ArrayList<KeyStoreRecoveryMetadata>(),
                        new ArrayList<KeyEntryRecoveryData>(),
                        /*encryptedRecoveryKeyBlob=*/ new byte[] {});
        throw new ServiceSpecificException(
                RecoverableKeyStoreLoader.UNINITIALIZED_RECOVERY_PUBLIC_KEY);
    }

    public void setSnapshotCreatedPendingIntent(@Nullable PendingIntent intent, int userId)
            throws RemoteException {
        checkRecoverKeyStorePermission();
        throw new UnsupportedOperationException();
    }

    /**
     * Gets recovery snapshot versions for all accounts. Note that snapshot may have 0 application
     * keys, but it still needs to be synced, if previous versions were not empty.
     *
     * @return Map from Recovery agent account to snapshot version.
     */
    public @NonNull Map<byte[], Integer> getRecoverySnapshotVersions(int userId)
            throws RemoteException {
        checkRecoverKeyStorePermission();
        throw new UnsupportedOperationException();
    }

    public void setServerParameters(long serverParameters, int userId) throws RemoteException {
        checkRecoverKeyStorePermission();
        throw new UnsupportedOperationException();
    }

    public void setRecoveryStatus(
            @NonNull String packageName, @Nullable String[] aliases, int status, int userId)
            throws RemoteException {
        checkRecoverKeyStorePermission();
        throw new UnsupportedOperationException();
    }

    /**
     * Gets recovery status for keys {@code packageName}.
     *
     * @param packageName which recoverable keys statuses will be returned
     * @return Map from KeyStore alias to recovery status
     */
    public @NonNull Map<String, Integer> getRecoveryStatus(@Nullable String packageName, int userId)
            throws RemoteException {
        // Any application should be able to check status for its own keys.
        // If caller is a recovery agent it can check statuses for other packages, but
        // only for recoverable keys it manages.
        checkRecoverKeyStorePermission();
        throw new UnsupportedOperationException();
    }

    /**
     * Sets recovery secrets list used by all recovery agents for given {@code userId}
     *
     * @hide
     */
    public void setRecoverySecretTypes(
            @NonNull @KeyStoreRecoveryMetadata.UserSecretType int[] secretTypes, int userId)
            throws RemoteException {
        checkRecoverKeyStorePermission();
        throw new UnsupportedOperationException();
    }

    /**
     * Gets secret types necessary to create Recovery Data.
     *
     * @return secret types
     * @hide
     */
    public @NonNull int[] getRecoverySecretTypes(int userId) throws RemoteException {
        checkRecoverKeyStorePermission();
        throw new UnsupportedOperationException();
    }

    /**
     * Gets secret types RecoverableKeyStoreLoaders is waiting for to create new Recovery Data.
     *
     * @return secret types
     * @hide
     */
    public @NonNull int[] getPendingRecoverySecretTypes(int userId) throws RemoteException {
        checkRecoverKeyStorePermission();
        throw new UnsupportedOperationException();
    }

    public void recoverySecretAvailable(
            @NonNull KeyStoreRecoveryMetadata recoverySecret, int userId) throws RemoteException {
        final int callingUid = Binder.getCallingUid(); // Recovery agent uid.
        if (recoverySecret.getLockScreenUiFormat() == KeyStoreRecoveryMetadata.TYPE_LOCKSCREEN) {
            throw new SecurityException(
                    "Caller " + callingUid + "is not allowed to set lock screen secret");
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
     * @param secrets Lock-screen hashes. Should have a single element. TODO: why is this a list?
     * @return Encrypted bytes of recovery claim. This can then be issued to the vault service.
     *
     * @hide
     */
    public @NonNull byte[] startRecoverySession(
            @NonNull String sessionId,
            @NonNull byte[] verifierPublicKey,
            @NonNull byte[] vaultParams,
            @NonNull byte[] vaultChallenge,
            @NonNull List<KeyStoreRecoveryMetadata> secrets,
            int userId)
            throws RemoteException {
        checkRecoverKeyStorePermission();

        if (secrets.size() != 1) {
            // TODO: support multiple secrets
            throw new RemoteException("Only a single KeyStoreRecoveryMetadata is supported");
        }

        byte[] keyClaimant = KeySyncUtils.generateKeyClaimant();
        byte[] kfHash = secrets.get(0).getSecret();
        mRecoverySessionStorage.add(
                userId, new RecoverySessionStorage.Entry(sessionId, kfHash, keyClaimant));

        try {
            byte[] thmKfHash = KeySyncUtils.calculateThmKfHash(kfHash);
            PublicKey publicKey = KeySyncUtils.deserializePublicKey(verifierPublicKey);
            return KeySyncUtils.encryptRecoveryClaim(
                    publicKey,
                    vaultParams,
                    vaultChallenge,
                    thmKfHash,
                    keyClaimant);
        } catch (NoSuchAlgorithmException e) {
            // Should never happen: all the algorithms used are required by AOSP implementations.
            throw new RemoteException(
                    "Missing required algorithm",
                    e,
                    /*enableSuppression=*/ true,
                    /*writeableStackTrace=*/ true);
        } catch (InvalidKeySpecException | InvalidKeyException e) {
            throw new RemoteException(
                    "Not a valid X509 key",
                    e,
                    /*enableSuppression=*/ true,
                    /*writeableStackTrace=*/ true);
        }
    }

    public void recoverKeys(
            @NonNull String sessionId,
            @NonNull byte[] recoveryKeyBlob,
            @NonNull List<KeyEntryRecoveryData> applicationKeys,
            int userId)
            throws RemoteException {
        checkRecoverKeyStorePermission();
        throw new UnsupportedOperationException();
    }

    /**
     * This function can only be used inside LockSettingsService.
     *
     * @param storedHashType from {@Code CredentialHash}
     * @param credential - unencrypted String. Password length should be at most 16 symbols {@code
     *     mPasswordMaxLength}
     * @param userId for user who just unlocked the device.
     * @hide
     */
    public void lockScreenSecretAvailable(
            int storedHashType, @NonNull String credential, int userId) {
        // Notify RecoverableKeystoreLoader about unlock
        @KeyStoreRecoveryMetadata.LockScreenUiFormat int uiFormat;
        if (storedHashType == LockPatternUtils.CREDENTIAL_TYPE_PATTERN) {
            uiFormat = KeyStoreRecoveryMetadata.TYPE_PATTERN;
        } else if (isPin(credential)) {
            uiFormat = KeyStoreRecoveryMetadata.TYPE_PIN;
        } else {
            uiFormat = KeyStoreRecoveryMetadata.TYPE_PASSWORD;
        }
        // TODO: check getPendingRecoverySecretTypes.
        // TODO: compute SHA256 or Argon2id depending on secret type.
    }

    /** This function can only be used inside LockSettingsService. */
    public void lockScreenSecretChanged(
            @KeyStoreRecoveryMetadata.LockScreenUiFormat int type,
            @Nullable String credential,
            int userId) {
        throw new UnsupportedOperationException();
    }

    @VisibleForTesting
    boolean isPin(@NonNull String credential) {
        for (int i = 0; i < credential.length(); i++) {
            char c = credential.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
    }

    private void checkRecoverKeyStorePermission() {
        mContext.enforceCallingOrSelfPermission(
                RecoverableKeyStoreLoader.PERMISSION_RECOVER_KEYSTORE,
                "Caller " + Binder.getCallingUid() + " doesn't have RecoverKeyStore permission.");
    }
}
