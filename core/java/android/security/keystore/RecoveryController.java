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
import android.util.Log;

import com.android.internal.widget.ILockSettings;

import java.util.List;
import java.util.Map;

/**
 * @deprecated Use {@link android.security.keystore.recovery.RecoveryController}.
 * @hide
 */
public class RecoveryController {
    private static final String TAG = "RecoveryController";

    /** Key has been successfully synced. */
    public static final int RECOVERY_STATUS_SYNCED = 0;
    /** Waiting for recovery agent to sync the key. */
    public static final int RECOVERY_STATUS_SYNC_IN_PROGRESS = 1;
    /** Recovery account is not available. */
    public static final int RECOVERY_STATUS_MISSING_ACCOUNT = 2;
    /** Key cannot be synced. */
    public static final int RECOVERY_STATUS_PERMANENT_FAILURE = 3;

    /**
     * Failed because no snapshot is yet pending to be synced for the user.
     *
     * @hide
     */
    public static final int ERROR_NO_SNAPSHOT_PENDING = 21;

    /**
     * Failed due to an error internal to the recovery service. This is unexpected and indicates
     * either a problem with the logic in the service, or a problem with a dependency of the
     * service (such as AndroidKeyStore).
     *
     * @hide
     */
    public static final int ERROR_SERVICE_INTERNAL_ERROR = 22;

    /**
     * Failed because the user does not have a lock screen set.
     *
     * @hide
     */
    public static final int ERROR_INSECURE_USER = 23;

    /**
     * Error thrown when attempting to use a recovery session that has since been closed.
     *
     * @hide
     */
    public static final int ERROR_SESSION_EXPIRED = 24;

    /**
     * Failed because the provided certificate was not a valid X509 certificate.
     *
     * @hide
     */
    public static final int ERROR_BAD_CERTIFICATE_FORMAT = 25;

    /**
     * Error thrown if decryption failed. This might be because the tag is wrong, the key is wrong,
     * the data has become corrupted, the data has been tampered with, etc.
     *
     * @hide
     */
    public static final int ERROR_DECRYPTION_FAILED = 26;


    private final ILockSettings mBinder;

    private RecoveryController(ILockSettings binder) {
        mBinder = binder;
    }

    /**
     * Gets a new instance of the class.
     */
    public static RecoveryController getInstance() {
        ILockSettings lockSettings =
                ILockSettings.Stub.asInterface(ServiceManager.getService("lock_settings"));
        return new RecoveryController(lockSettings);
    }

    /**
     * Initializes key recovery service for the calling application. RecoveryController
     * randomly chooses one of the keys from the list and keeps it to use for future key export
     * operations. Collection of all keys in the list must be signed by the provided {@code
     * rootCertificateAlias}, which must also be present in the list of root certificates
     * preinstalled on the device. The random selection allows RecoveryController to select
     * which of a set of remote recovery service devices will be used.
     *
     * <p>In addition, RecoveryController enforces a delay of three months between
     * consecutive initialization attempts, to limit the ability of an attacker to often switch
     * remote recovery devices and significantly increase number of recovery attempts.
     *
     * @param rootCertificateAlias alias of a root certificate preinstalled on the device
     * @param signedPublicKeyList binary blob a list of X509 certificates and signature
     * @throws BadCertificateFormatException if the {@code signedPublicKeyList} is in a bad format.
     * @throws InternalRecoveryServiceException if an unexpected error occurred in the recovery
     *     service.
     */
    public void initRecoveryService(
            @NonNull String rootCertificateAlias, @NonNull byte[] signedPublicKeyList)
            throws BadCertificateFormatException, InternalRecoveryServiceException {
        try {
            mBinder.initRecoveryService(rootCertificateAlias, signedPublicKeyList);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        } catch (ServiceSpecificException e) {
            if (e.errorCode == ERROR_BAD_CERTIFICATE_FORMAT) {
                throw new BadCertificateFormatException(e.getMessage());
            }
            throw wrapUnexpectedServiceSpecificException(e);
        }
    }

    /**
     * Returns data necessary to store all recoverable keys for given account. Key material is
     * encrypted with user secret and recovery public key.
     *
     * @param account specific to Recovery agent.
     * @return Data necessary to recover keystore.
     * @throws InternalRecoveryServiceException if an unexpected error occurred in the recovery
     *     service.
     */
    public @NonNull KeychainSnapshot getRecoveryData(@NonNull byte[] account)
            throws InternalRecoveryServiceException {
        try {
            return BackwardsCompat.toLegacyKeychainSnapshot(mBinder.getKeyChainSnapshot());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        } catch (ServiceSpecificException e) {
            if (e.errorCode == ERROR_NO_SNAPSHOT_PENDING) {
                return null;
            }
            throw wrapUnexpectedServiceSpecificException(e);
        }
    }

    /**
     * Sets a listener which notifies recovery agent that new recovery snapshot is available. {@link
     * #getRecoveryData} can be used to get the snapshot. Note that every recovery agent can have at
     * most one registered listener at any time.
     *
     * @param intent triggered when new snapshot is available. Unregisters listener if the value is
     *     {@code null}.
     * @throws InternalRecoveryServiceException if an unexpected error occurred in the recovery
     *     service.
     */
    public void setSnapshotCreatedPendingIntent(@Nullable PendingIntent intent)
            throws InternalRecoveryServiceException {
        try {
            mBinder.setSnapshotCreatedPendingIntent(intent);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        } catch (ServiceSpecificException e) {
            throw wrapUnexpectedServiceSpecificException(e);
        }
    }

    /**
     * Returns a map from recovery agent accounts to corresponding KeyStore recovery snapshot
     * version. Version zero is used, if no snapshots were created for the account.
     *
     * @return Map from recovery agent accounts to snapshot versions.
     * @see KeychainSnapshot#getSnapshotVersion
     * @throws InternalRecoveryServiceException if an unexpected error occurred in the recovery
     *     service.
     */
    public @NonNull Map<byte[], Integer> getRecoverySnapshotVersions()
            throws InternalRecoveryServiceException {
        try {
            // IPC doesn't support generic Maps.
            @SuppressWarnings("unchecked")
            Map<byte[], Integer> result =
                    (Map<byte[], Integer>) mBinder.getRecoverySnapshotVersions();
            return result;
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        } catch (ServiceSpecificException e) {
            throw wrapUnexpectedServiceSpecificException(e);
        }
    }

    /**
     * Server parameters used to generate new recovery key blobs. This value will be included in
     * {@code KeychainSnapshot.getEncryptedRecoveryKeyBlob()}. The same value must be included
     * in vaultParams {@link #startRecoverySession}
     *
     * @param serverParams included in recovery key blob.
     * @see #getRecoveryData
     * @throws InternalRecoveryServiceException if an unexpected error occurred in the recovery
     *     service.
     */
    public void setServerParams(byte[] serverParams) throws InternalRecoveryServiceException {
        try {
            mBinder.setServerParams(serverParams);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        } catch (ServiceSpecificException e) {
            throw wrapUnexpectedServiceSpecificException(e);
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
     * @throws InternalRecoveryServiceException if an unexpected error occurred in the recovery
     *     service.
     */
    public void setRecoveryStatus(
            @NonNull String packageName, @Nullable String[] aliases, int status)
            throws NameNotFoundException, InternalRecoveryServiceException {
        try {
            for (String alias : aliases) {
                mBinder.setRecoveryStatus(alias, status);
            }
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        } catch (ServiceSpecificException e) {
            throw wrapUnexpectedServiceSpecificException(e);
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
     * @throws InternalRecoveryServiceException if an unexpected error occurred in the recovery
     *     service.
     */
    public Map<String, Integer> getRecoveryStatus() throws InternalRecoveryServiceException {
        try {
            // IPC doesn't support generic Maps.
            @SuppressWarnings("unchecked")
            Map<String, Integer> result =
                    (Map<String, Integer>) mBinder.getRecoveryStatus();
            return result;
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        } catch (ServiceSpecificException e) {
            throw wrapUnexpectedServiceSpecificException(e);
        }
    }

    /**
     * Specifies a set of secret types used for end-to-end keystore encryption. Knowing all of them
     * is necessary to recover data.
     *
     * @param secretTypes {@link KeychainProtectionParams#TYPE_LOCKSCREEN} or {@link
     *     KeychainProtectionParams#TYPE_CUSTOM_PASSWORD}
     * @throws InternalRecoveryServiceException if an unexpected error occurred in the recovery
     *     service.
     */
    public void setRecoverySecretTypes(
            @NonNull @KeychainProtectionParams.UserSecretType int[] secretTypes)
            throws InternalRecoveryServiceException {
        try {
            mBinder.setRecoverySecretTypes(secretTypes);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        } catch (ServiceSpecificException e) {
            throw wrapUnexpectedServiceSpecificException(e);
        }
    }

    /**
     * Defines a set of secret types used for end-to-end keystore encryption. Knowing all of them is
     * necessary to generate KeychainSnapshot.
     *
     * @return list of recovery secret types
     * @see KeychainSnapshot
     * @throws InternalRecoveryServiceException if an unexpected error occurred in the recovery
     *     service.
     */
    public @NonNull @KeychainProtectionParams.UserSecretType int[] getRecoverySecretTypes()
            throws InternalRecoveryServiceException {
        try {
            return mBinder.getRecoverySecretTypes();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        } catch (ServiceSpecificException e) {
            throw wrapUnexpectedServiceSpecificException(e);
        }
    }

    /**
     * Returns a list of recovery secret types, necessary to create a pending recovery snapshot.
     * When user enters a secret of a pending type {@link #recoverySecretAvailable} should be
     * called.
     *
     * @return list of recovery secret types
     * @throws InternalRecoveryServiceException if an unexpected error occurred in the recovery
     *     service.
     */
    @NonNull
    public @KeychainProtectionParams.UserSecretType int[] getPendingRecoverySecretTypes()
            throws InternalRecoveryServiceException {
        try {
            return mBinder.getPendingRecoverySecretTypes();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        } catch (ServiceSpecificException e) {
            throw wrapUnexpectedServiceSpecificException(e);
        }
    }

    /**
     * Initializes recovery session and returns a blob with proof of recovery secrets possession.
     * The method generates symmetric key for a session, which trusted remote device can use to
     * return recovery key.
     *
     * @param verifierPublicKey Encoded {@code java.security.cert.X509Certificate} with Public key
     * used to create the recovery blob on the source device.
     * Keystore will verify the certificate using root of trust.
     * @param vaultParams Must match the parameters in the corresponding field in the recovery blob.
     *     Used to limit number of guesses.
     * @param vaultChallenge Data passed from server for this recovery session and used to prevent
     *     replay attacks
     * @param secrets Secrets provided by user, the method only uses type and secret fields.
     * @return The recovery claim. Claim provides a b binary blob with recovery claim. It is
     *     encrypted with verifierPublicKey and contains a proof of user secrets, session symmetric
     *     key and parameters necessary to identify the counter with the number of failed recovery
     *     attempts.
     * @throws BadCertificateFormatException if the {@code verifierPublicKey} is in an incorrect
     *     format.
     * @throws InternalRecoveryServiceException if an unexpected error occurred in the recovery
     *     service.
     */
    @NonNull public RecoveryClaim startRecoverySession(
            @NonNull byte[] verifierPublicKey,
            @NonNull byte[] vaultParams,
            @NonNull byte[] vaultChallenge,
            @NonNull List<KeychainProtectionParams> secrets)
            throws BadCertificateFormatException, InternalRecoveryServiceException {
        try {
            RecoverySession recoverySession = RecoverySession.newInstance(this);
            byte[] recoveryClaim =
                    mBinder.startRecoverySession(
                            recoverySession.getSessionId(),
                            verifierPublicKey,
                            vaultParams,
                            vaultChallenge,
                            BackwardsCompat.fromLegacyKeychainProtectionParams(secrets));
            return new RecoveryClaim(recoverySession, recoveryClaim);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        } catch (ServiceSpecificException e) {
            if (e.errorCode == ERROR_BAD_CERTIFICATE_FORMAT) {
                throw new BadCertificateFormatException(e.getMessage());
            }
            throw wrapUnexpectedServiceSpecificException(e);
        }
    }

    /**
     * Imports keys.
     *
     * @param session Related recovery session, as originally created by invoking
     *        {@link #startRecoverySession(byte[], byte[], byte[], List)}.
     * @param recoveryKeyBlob Recovery blob encrypted by symmetric key generated for this session.
     * @param applicationKeys Application keys. Key material can be decrypted using recoveryKeyBlob
     *     and session. KeyStore only uses package names from the application info in {@link
     *     WrappedApplicationKey}. Caller is responsibility to perform certificates check.
     * @return Map from alias to raw key material.
     * @throws SessionExpiredException if {@code session} has since been closed.
     * @throws DecryptionFailedException if unable to decrypt the snapshot.
     * @throws InternalRecoveryServiceException if an error occurs internal to the recovery service.
     */
    public Map<String, byte[]> recoverKeys(
            @NonNull RecoverySession session,
            @NonNull byte[] recoveryKeyBlob,
            @NonNull List<WrappedApplicationKey> applicationKeys)
            throws SessionExpiredException, DecryptionFailedException,
            InternalRecoveryServiceException {
        try {
            return (Map<String, byte[]>) mBinder.recoverKeys(
                    session.getSessionId(),
                    recoveryKeyBlob,
                    BackwardsCompat.fromLegacyWrappedApplicationKeys(applicationKeys));
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        } catch (ServiceSpecificException e) {
            if (e.errorCode == ERROR_DECRYPTION_FAILED) {
                throw new DecryptionFailedException(e.getMessage());
            }
            if (e.errorCode == ERROR_SESSION_EXPIRED) {
                throw new SessionExpiredException(e.getMessage());
            }
            throw wrapUnexpectedServiceSpecificException(e);
        }
    }

    /**
     * Deletes all data associated with {@code session}. Should not be invoked directly but via
     * {@link RecoverySession#close()}.
     *
     * @hide
     */
    void closeSession(RecoverySession session) {
        try {
            mBinder.closeSession(session.getSessionId());
        } catch (RemoteException | ServiceSpecificException e) {
            Log.e(TAG, "Unexpected error trying to close session", e);
        }
    }

    /**
     * Generates a key called {@code alias} and loads it into the recoverable key store. Returns the
     * raw material of the key.
     *
     * @param alias The key alias.
     * @throws InternalRecoveryServiceException if an unexpected error occurred in the recovery
     *     service.
     * @throws LockScreenRequiredException if the user has not set a lock screen. This is required
     *     to generate recoverable keys, as the snapshots are encrypted using a key derived from the
     *     lock screen.
     */
    public byte[] generateAndStoreKey(@NonNull String alias)
            throws InternalRecoveryServiceException, LockScreenRequiredException {
        try {
            return mBinder.generateAndStoreKey(alias);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        } catch (ServiceSpecificException e) {
            if (e.errorCode == ERROR_INSECURE_USER) {
                throw new LockScreenRequiredException(e.getMessage());
            }
            throw wrapUnexpectedServiceSpecificException(e);
        }
    }

    /**
     * Removes a key called {@code alias} from the recoverable key store.
     *
     * @param alias The key alias.
     * @throws InternalRecoveryServiceException if an unexpected error occurred in the recovery
     *     service.
     */
    public void removeKey(@NonNull String alias) throws InternalRecoveryServiceException {
        try {
            mBinder.removeKey(alias);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        } catch (ServiceSpecificException e) {
            throw wrapUnexpectedServiceSpecificException(e);
        }
    }

    private InternalRecoveryServiceException wrapUnexpectedServiceSpecificException(
            ServiceSpecificException e) {
        if (e.errorCode == ERROR_SERVICE_INTERNAL_ERROR) {
            return new InternalRecoveryServiceException(e.getMessage());
        }

        // Should never happen. If it does, it's a bug, and we need to update how the method that
        // called this throws its exceptions.
        return new InternalRecoveryServiceException("Unexpected error code for method: "
                + e.errorCode, e);
    }
}
