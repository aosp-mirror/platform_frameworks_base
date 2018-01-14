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

package android.security.keystore;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.PendingIntent;
import android.content.pm.PackageManager.NameNotFoundException;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.ServiceSpecificException;

import com.android.internal.widget.ILockSettings;

import java.util.List;
import java.util.Map;

/**
 * A wrapper around KeyStore which lets key be exported to trusted hardware on server side and
 * recovered later.
 *
 * @hide
 */
public class RecoveryManager {

    /** Key has been successfully synced. */
    public static final int RECOVERY_STATUS_SYNCED = 0;
    /** Waiting for recovery agent to sync the key. */
    public static final int RECOVERY_STATUS_SYNC_IN_PROGRESS = 1;
    /** Recovery account is not available. */
    public static final int RECOVERY_STATUS_MISSING_ACCOUNT = 2;
    /** Key cannot be synced. */
    public static final int RECOVERY_STATUS_PERMANENT_FAILURE = 3;

    private final ILockSettings mBinder;

    private RecoveryManager(ILockSettings binder) {
        mBinder = binder;
    }

    /**
     * Gets a new instance of the class.
     */
    public static RecoveryManager getInstance() {
        ILockSettings lockSettings =
                ILockSettings.Stub.asInterface(ServiceManager.getService("lock_settings"));
        return new RecoveryManager(lockSettings);
    }

    /**
     * Initializes key recovery service for the calling application. RecoveryManager
     * randomly chooses one of the keys from the list and keeps it to use for future key export
     * operations. Collection of all keys in the list must be signed by the provided {@code
     * rootCertificateAlias}, which must also be present in the list of root certificates
     * preinstalled on the device. The random selection allows RecoveryManager to select
     * which of a set of remote recovery service devices will be used.
     *
     * <p>In addition, RecoveryManager enforces a delay of three months between
     * consecutive initialization attempts, to limit the ability of an attacker to often switch
     * remote recovery devices and significantly increase number of recovery attempts.
     *
     * @param rootCertificateAlias alias of a root certificate preinstalled on the device
     * @param signedPublicKeyList binary blob a list of X509 certificates and signature
     * @throws RecoveryManagerException if signature is invalid, or key rotation was rate
     *     limited.
     * @hide
     */
    public void initRecoveryService(
            @NonNull String rootCertificateAlias, @NonNull byte[] signedPublicKeyList)
            throws RecoveryManagerException {
        try {
            mBinder.initRecoveryService(rootCertificateAlias, signedPublicKeyList);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        } catch (ServiceSpecificException e) {
            throw RecoveryManagerException.fromServiceSpecificException(e);
        }
    }

    /**
     * Returns data necessary to store all recoverable keys for given account. Key material is
     * encrypted with user secret and recovery public key.
     *
     * @param account specific to Recovery agent.
     * @return Data necessary to recover keystore.
     * @hide
     */
    public @NonNull RecoveryData getRecoveryData(@NonNull byte[] account)
            throws RecoveryManagerException {
        try {
            RecoveryData recoveryData = mBinder.getRecoveryData(account);
            return recoveryData;
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        } catch (ServiceSpecificException e) {
            throw RecoveryManagerException.fromServiceSpecificException(e);
        }
    }

    /**
     * Sets a listener which notifies recovery agent that new recovery snapshot is available. {@link
     * #getRecoveryData} can be used to get the snapshot. Note that every recovery agent can have at
     * most one registered listener at any time.
     *
     * @param intent triggered when new snapshot is available. Unregisters listener if the value is
     *     {@code null}.
     * @hide
     */
    public void setSnapshotCreatedPendingIntent(@Nullable PendingIntent intent)
            throws RecoveryManagerException {
        try {
            mBinder.setSnapshotCreatedPendingIntent(intent);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        } catch (ServiceSpecificException e) {
            throw RecoveryManagerException.fromServiceSpecificException(e);
        }
    }

    /**
     * Returns a map from recovery agent accounts to corresponding KeyStore recovery snapshot
     * version. Version zero is used, if no snapshots were created for the account.
     *
     * @return Map from recovery agent accounts to snapshot versions.
     * @see RecoveryData#getSnapshotVersion
     * @hide
     */
    public @NonNull Map<byte[], Integer> getRecoverySnapshotVersions()
            throws RecoveryManagerException {
        try {
            // IPC doesn't support generic Maps.
            @SuppressWarnings("unchecked")
            Map<byte[], Integer> result =
                    (Map<byte[], Integer>) mBinder.getRecoverySnapshotVersions();
            return result;
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        } catch (ServiceSpecificException e) {
            throw RecoveryManagerException.fromServiceSpecificException(e);
        }
    }

    /**
     * Server parameters used to generate new recovery key blobs. This value will be included in
     * {@code RecoveryData.getEncryptedRecoveryKeyBlob()}. The same value must be included
     * in vaultParams {@link #startRecoverySession}
     *
     * @param serverParams included in recovery key blob.
     * @see #getRecoveryData
     * @throws RecoveryManagerException If parameters rotation is rate limited.
     * @hide
     */
    public void setServerParams(byte[] serverParams) throws RecoveryManagerException {
        try {
            mBinder.setServerParams(serverParams);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        } catch (ServiceSpecificException e) {
            throw RecoveryManagerException.fromServiceSpecificException(e);
        }
    }

    /**
     * Updates recovery status for given keys. It is used to notify keystore that key was
     * successfully stored on the server or there were an error. Application can check this value
     * using {@code getRecoveyStatus}.
     *
     * @param packageName Application whose recoverable keys' statuses are to be updated.
     * @param aliases List of application-specific key aliases. If the array is empty, updates the
     *     status for all existing recoverable keys.
     * @param status Status specific to recovery agent.
     */
    public void setRecoveryStatus(
            @NonNull String packageName, @Nullable String[] aliases, int status)
            throws NameNotFoundException, RecoveryManagerException {
        try {
            mBinder.setRecoveryStatus(packageName, aliases, status);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        } catch (ServiceSpecificException e) {
            throw RecoveryManagerException.fromServiceSpecificException(e);
        }
    }

    /**
     * Returns a {@code Map} from Application's KeyStore key aliases to their recovery status.
     * Negative status values are reserved for recovery agent specific codes. List of common codes:
     *
     * <ul>
     *   <li>{@link #RECOVERY_STATUS_SYNCED}
     *   <li>{@link #RECOVERY_STATUS_SYNC_IN_PROGRESS}
     *   <li>{@link #RECOVERY_STATUS_MISSING_ACCOUNT}
     *   <li>{@link #RECOVERY_STATUS_PERMANENT_FAILURE}
     * </ul>
     *
     * @return {@code Map} from KeyStore alias to recovery status.
     * @see #setRecoveryStatus
     * @hide
     */
    public Map<String, Integer> getRecoveryStatus()
            throws RecoveryManagerException {
        try {
            // IPC doesn't support generic Maps.
            @SuppressWarnings("unchecked")
            Map<String, Integer> result =
                    (Map<String, Integer>) mBinder.getRecoveryStatus(/*packageName=*/ null);
            return result;
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        } catch (ServiceSpecificException e) {
            throw RecoveryManagerException.fromServiceSpecificException(e);
        }
    }

    /**
     * Specifies a set of secret types used for end-to-end keystore encryption. Knowing all of them
     * is necessary to recover data.
     *
     * @param secretTypes {@link RecoveryMetadata#TYPE_LOCKSCREEN} or {@link
     *     RecoveryMetadata#TYPE_CUSTOM_PASSWORD}
     */
    public void setRecoverySecretTypes(
            @NonNull @RecoveryMetadata.UserSecretType int[] secretTypes)
            throws RecoveryManagerException {
        try {
            mBinder.setRecoverySecretTypes(secretTypes);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        } catch (ServiceSpecificException e) {
            throw RecoveryManagerException.fromServiceSpecificException(e);
        }
    }

    /**
     * Defines a set of secret types used for end-to-end keystore encryption. Knowing all of them is
     * necessary to generate RecoveryData.
     *
     * @return list of recovery secret types
     * @see RecoveryData
     */
    public @NonNull @RecoveryMetadata.UserSecretType int[] getRecoverySecretTypes()
            throws RecoveryManagerException {
        try {
            return mBinder.getRecoverySecretTypes();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        } catch (ServiceSpecificException e) {
            throw RecoveryManagerException.fromServiceSpecificException(e);
        }
    }

    /**
     * Returns a list of recovery secret types, necessary to create a pending recovery snapshot.
     * When user enters a secret of a pending type {@link #recoverySecretAvailable} should be
     * called.
     *
     * @return list of recovery secret types
     * @hide
     */
    public @NonNull @RecoveryMetadata.UserSecretType int[] getPendingRecoverySecretTypes()
            throws RecoveryManagerException {
        try {
            return mBinder.getPendingRecoverySecretTypes();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        } catch (ServiceSpecificException e) {
            throw RecoveryManagerException.fromServiceSpecificException(e);
        }
    }

    /**
     * Method notifies KeyStore that a user-generated secret is available. This method generates a
     * symmetric session key which a trusted remote device can use to return a recovery key. Caller
     * should use {@link RecoveryMetadata#clearSecret} to override the secret value in
     * memory.
     *
     * @param recoverySecret user generated secret together with parameters necessary to regenerate
     *     it on a new device.
     * @hide
     */
    public void recoverySecretAvailable(@NonNull RecoveryMetadata recoverySecret)
            throws RecoveryManagerException {
        try {
            mBinder.recoverySecretAvailable(recoverySecret);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        } catch (ServiceSpecificException e) {
            throw RecoveryManagerException.fromServiceSpecificException(e);
        }
    }

    /**
     * Initializes recovery session and returns a blob with proof of recovery secrets possession.
     * The method generates symmetric key for a session, which trusted remote device can use to
     * return recovery key.
     *
     * @param sessionId ID for recovery session.
     * @param verifierPublicKey Encoded {@code java.security.cert.X509Certificate} with Public key
     * used to create the recovery blob on the source device.
     * Keystore will verify the certificate using root of trust.
     * @param vaultParams Must match the parameters in the corresponding field in the recovery blob.
     *     Used to limit number of guesses.
     * @param vaultChallenge Data passed from server for this recovery session and used to prevent
     *     replay attacks
     * @param secrets Secrets provided by user, the method only uses type and secret fields.
     * @return Binary blob with recovery claim. It is encrypted with verifierPublicKey and contains
     *     a proof of user secrets, session symmetric key and parameters necessary to identify the
     *     counter with the number of failed recovery attempts.
     */
    public @NonNull byte[] startRecoverySession(
            @NonNull String sessionId,
            @NonNull byte[] verifierPublicKey,
            @NonNull byte[] vaultParams,
            @NonNull byte[] vaultChallenge,
            @NonNull List<RecoveryMetadata> secrets)
            throws RecoveryManagerException {
        try {
            byte[] recoveryClaim =
                    mBinder.startRecoverySession(
                            sessionId,
                            verifierPublicKey,
                            vaultParams,
                            vaultChallenge,
                            secrets);
            return recoveryClaim;
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        } catch (ServiceSpecificException e) {
            throw RecoveryManagerException.fromServiceSpecificException(e);
        }
    }

    /**
     * Imports keys.
     *
     * @param sessionId Id for recovery session, same as in
     *     {@link #startRecoverySession(String, byte[], byte[], byte[], List)}.
     * @param recoveryKeyBlob Recovery blob encrypted by symmetric key generated for this session.
     * @param applicationKeys Application keys. Key material can be decrypted using recoveryKeyBlob
     *     and session. KeyStore only uses package names from the application info in {@link
     *     EntryRecoveryData}. Caller is responsibility to perform certificates check.
     * @return Map from alias to raw key material.
     */
    public Map<String, byte[]> recoverKeys(
            @NonNull String sessionId,
            @NonNull byte[] recoveryKeyBlob,
            @NonNull List<EntryRecoveryData> applicationKeys)
            throws RecoveryManagerException {
        try {
            return (Map<String, byte[]>) mBinder.recoverKeys(
                    sessionId, recoveryKeyBlob, applicationKeys);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        } catch (ServiceSpecificException e) {
            throw RecoveryManagerException.fromServiceSpecificException(e);
        }
    }

    /**
     * Generates a key called {@code alias} and loads it into the recoverable key store. Returns the
     * raw material of the key.
     *
     * @param alias The key alias.
     * @throws RecoveryManagerException if an error occurred generating and storing the
     *     key.
     */
    public byte[] generateAndStoreKey(@NonNull String alias)
            throws RecoveryManagerException {
        try {
            return mBinder.generateAndStoreKey(alias);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        } catch (ServiceSpecificException e) {
            throw RecoveryManagerException.fromServiceSpecificException(e);
        }
    }

    /**
     * Removes a key called {@code alias} from the recoverable key store.
     *
     * @param alias The key alias.
     */
    public void removeKey(@NonNull String alias) throws RecoveryManagerException {
        try {
            mBinder.removeKey(alias);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        } catch (ServiceSpecificException e) {
            throw RecoveryManagerException.fromServiceSpecificException(e);
        }
    }
}
