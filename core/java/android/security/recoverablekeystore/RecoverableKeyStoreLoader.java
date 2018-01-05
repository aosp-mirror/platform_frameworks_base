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

package android.security.recoverablekeystore;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.PendingIntent;
import android.content.pm.PackageManager.NameNotFoundException;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.ServiceSpecificException;
import android.security.KeyStore;
import android.util.AndroidException;

import com.android.internal.widget.ILockSettings;

import java.util.List;
import java.util.Map;

/**
 * A wrapper around KeyStore which lets key be exported to trusted hardware on server side and
 * recovered later.
 *
 * @hide
 */
public class RecoverableKeyStoreLoader {

    public static final String PERMISSION_RECOVER_KEYSTORE = "android.permission.RECOVER_KEYSTORE";

    public static final int NO_ERROR = KeyStore.NO_ERROR;
    public static final int SYSTEM_ERROR = KeyStore.SYSTEM_ERROR;
    public static final int ERROR_UNINITIALIZED_RECOVERY_PUBLIC_KEY = 20;
    public static final int ERROR_NO_SNAPSHOT_PENDING = 21;
    public static final int ERROR_KEYSTORE_INTERNAL_ERROR = 22;
    public static final int ERROR_INSECURE_USER = 24;

    /**
     * Rate limit is enforced to prevent using too many trusted remote devices, since each device
     * can have its own number of user secret guesses allowed.
     *
     * @hide
     */
    public static final int RATE_LIMIT_EXCEEDED = 21;

    /** Key has been successfully synced. */
    public static final int RECOVERY_STATUS_SYNCED = 0;
    /** Waiting for recovery agent to sync the key. */
    public static final int RECOVERY_STATUS_SYNC_IN_PROGRESS = 1;
    /** Recovery account is not available. */
    public static final int RECOVERY_STATUS_MISSING_ACCOUNT = 2;
    /** Key cannot be synced. */
    public static final int RECOVERY_STATUS_PERMANENT_FAILURE = 3;

    private final ILockSettings mBinder;

    private RecoverableKeyStoreLoader(ILockSettings binder) {
        mBinder = binder;
    }

    /** @hide */
    public static RecoverableKeyStoreLoader getInstance() {
        ILockSettings lockSettings =
                ILockSettings.Stub.asInterface(ServiceManager.getService("lock_settings"));
        return new RecoverableKeyStoreLoader(lockSettings);
    }

    /**
     * Exceptions returned by {@link RecoverableKeyStoreLoader}.
     *
     * @hide
     */
    public static class RecoverableKeyStoreLoaderException extends AndroidException {
        private int mErrorCode;

        /**
         * Creates new {@link #RecoverableKeyStoreLoaderException} instance from the error code.
         *
         * @param errorCode
         * @hide
         */
        public static RecoverableKeyStoreLoaderException fromErrorCode(int errorCode) {
            return new RecoverableKeyStoreLoaderException(
                    errorCode, getMessageFromErrorCode(errorCode));
        }

        /**
         * Creates new {@link #RecoverableKeyStoreLoaderException} from {@link
         * ServiceSpecificException}.
         *
         * @param e exception thrown on service side.
         * @hide
         */
        static RecoverableKeyStoreLoaderException fromServiceSpecificException(
                ServiceSpecificException e) throws RecoverableKeyStoreLoaderException {
            throw RecoverableKeyStoreLoaderException.fromErrorCode(e.errorCode);
        }

        private RecoverableKeyStoreLoaderException(int errorCode, String message) {
            super(message);
        }

        /** Returns errorCode. */
        public int getErrorCode() {
            return mErrorCode;
        }

        /** @hide */
        private static String getMessageFromErrorCode(int errorCode) {
            switch (errorCode) {
                case NO_ERROR:
                    return "OK";
                case SYSTEM_ERROR:
                    return "System error";
                case ERROR_UNINITIALIZED_RECOVERY_PUBLIC_KEY:
                    return "Recovery service is not initialized";
                case RATE_LIMIT_EXCEEDED:
                    return "Rate limit exceeded";
                default:
                    return String.valueOf("Unknown error code " + errorCode);
            }
        }
    }

    /**
     * Initializes key recovery service for the calling application. RecoverableKeyStoreLoader
     * randomly chooses one of the keys from the list and keeps it to use for future key export
     * operations. Collection of all keys in the list must be signed by the provided {@code
     * rootCertificateAlias}, which must also be present in the list of root certificates
     * preinstalled on the device. The random selection allows RecoverableKeyStoreLoader to select
     * which of a set of remote recovery service devices will be used.
     *
     * <p>In addition, RecoverableKeyStoreLoader enforces a delay of three months between
     * consecutive initialization attempts, to limit the ability of an attacker to often switch
     * remote recovery devices and significantly increase number of recovery attempts.
     *
     * @param rootCertificateAlias alias of a root certificate preinstalled on the device
     * @param signedPublicKeyList binary blob a list of X509 certificates and signature
     * @throws RecoverableKeyStoreLoaderException if signature is invalid, or key rotation was rate
     *     limited.
     * @hide
     */
    public void initRecoveryService(
            @NonNull String rootCertificateAlias, @NonNull byte[] signedPublicKeyList)
            throws RecoverableKeyStoreLoaderException {
        try {
            mBinder.initRecoveryService(rootCertificateAlias, signedPublicKeyList);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        } catch (ServiceSpecificException e) {
            throw RecoverableKeyStoreLoaderException.fromServiceSpecificException(e);
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
    public @NonNull KeyStoreRecoveryData getRecoveryData(@NonNull byte[] account)
            throws RecoverableKeyStoreLoaderException {
        try {
            KeyStoreRecoveryData recoveryData = mBinder.getRecoveryData(account);
            return recoveryData;
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        } catch (ServiceSpecificException e) {
            throw RecoverableKeyStoreLoaderException.fromServiceSpecificException(e);
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
            throws RecoverableKeyStoreLoaderException {
        try {
            mBinder.setSnapshotCreatedPendingIntent(intent);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        } catch (ServiceSpecificException e) {
            throw RecoverableKeyStoreLoaderException.fromServiceSpecificException(e);
        }
    }

    /**
     * Returns a map from recovery agent accounts to corresponding KeyStore recovery snapshot
     * version. Version zero is used, if no snapshots were created for the account.
     *
     * @return Map from recovery agent accounts to snapshot versions.
     * @see KeyStoreRecoveryData#getSnapshotVersion
     * @hide
     */
    public @NonNull Map<byte[], Integer> getRecoverySnapshotVersions()
            throws RecoverableKeyStoreLoaderException {
        try {
            // IPC doesn't support generic Maps.
            @SuppressWarnings("unchecked")
            Map<byte[], Integer> result =
                    (Map<byte[], Integer>) mBinder.getRecoverySnapshotVersions();
            return result;
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        } catch (ServiceSpecificException e) {
            throw RecoverableKeyStoreLoaderException.fromServiceSpecificException(e);
        }
    }

    /**
     * Server parameters used to generate new recovery key blobs. This value will be included in
     * {@code KeyStoreRecoveryData.getEncryptedRecoveryKeyBlob()}. The same value must be included
     * in vaultParams {@link #startRecoverySession}
     *
     * @param serverParameters included in recovery key blob.
     * @see #getRecoveryData
     * @throws RecoverableKeyStoreLoaderException If parameters rotation is rate limited.
     * @hide
     */
    public void setServerParameters(long serverParameters)
            throws RecoverableKeyStoreLoaderException {
        try {
            mBinder.setServerParameters(serverParameters);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        } catch (ServiceSpecificException e) {
            throw RecoverableKeyStoreLoaderException.fromServiceSpecificException(e);
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
            throws NameNotFoundException, RecoverableKeyStoreLoaderException {
        try {
            mBinder.setRecoveryStatus(packageName, aliases, status);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        } catch (ServiceSpecificException e) {
            throw RecoverableKeyStoreLoaderException.fromServiceSpecificException(e);
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
     * @param packageName Application whose recoverable keys' statuses are to be retrieved. if
     *     {@code null} caller's package will be used.
     * @return {@code Map} from KeyStore alias to recovery status.
     * @see #setRecoveryStatus
     * @hide
     */
    public Map<String, Integer> getRecoveryStatus(@Nullable String packageName)
            throws RecoverableKeyStoreLoaderException {
        try {
            // IPC doesn't support generic Maps.
            @SuppressWarnings("unchecked")
            Map<String, Integer> result =
                    (Map<String, Integer>)
                            mBinder.getRecoveryStatus(packageName);
            return result;
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        } catch (ServiceSpecificException e) {
            throw RecoverableKeyStoreLoaderException.fromServiceSpecificException(e);
        }
    }

    /**
     * Specifies a set of secret types used for end-to-end keystore encryption. Knowing all of them
     * is necessary to recover data.
     *
     * @param secretTypes {@link KeyStoreRecoveryMetadata#TYPE_LOCKSCREEN} or {@link
     *     KeyStoreRecoveryMetadata#TYPE_CUSTOM_PASSWORD}
     */
    public void setRecoverySecretTypes(
            @NonNull @KeyStoreRecoveryMetadata.UserSecretType int[] secretTypes)
            throws RecoverableKeyStoreLoaderException {
        try {
            mBinder.setRecoverySecretTypes(secretTypes);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        } catch (ServiceSpecificException e) {
            throw RecoverableKeyStoreLoaderException.fromServiceSpecificException(e);
        }
    }

    /**
     * Defines a set of secret types used for end-to-end keystore encryption. Knowing all of them is
     * necessary to generate KeyStoreRecoveryData.
     *
     * @return list of recovery secret types
     * @see KeyStoreRecoveryData
     */
    public @NonNull @KeyStoreRecoveryMetadata.UserSecretType int[] getRecoverySecretTypes()
            throws RecoverableKeyStoreLoaderException {
        try {
            return mBinder.getRecoverySecretTypes();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        } catch (ServiceSpecificException e) {
            throw RecoverableKeyStoreLoaderException.fromServiceSpecificException(e);
        }
    }

    /**
     * Returns a list of recovery secret types, necessary to create a pending recovery snapshot.
     * When user enters a secret of a pending type {@link #recoverySecretAvailable} should be
     * called.
     *
     * @return list of recovery secret types
     */
    public @NonNull @KeyStoreRecoveryMetadata.UserSecretType int[] getPendingRecoverySecretTypes()
            throws RecoverableKeyStoreLoaderException {
        try {
            return mBinder.getPendingRecoverySecretTypes();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        } catch (ServiceSpecificException e) {
            throw RecoverableKeyStoreLoaderException.fromServiceSpecificException(e);
        }
    }

    /**
     * Method notifies KeyStore that a user-generated secret is available. This method generates a
     * symmetric session key which a trusted remote device can use to return a recovery key. Caller
     * should use {@link KeyStoreRecoveryMetadata#clearSecret} to override the secret value in
     * memory.
     *
     * @param recoverySecret user generated secret together with parameters necessary to regenerate
     *     it on a new device.
     */
    public void recoverySecretAvailable(@NonNull KeyStoreRecoveryMetadata recoverySecret)
            throws RecoverableKeyStoreLoaderException {
        try {
            mBinder.recoverySecretAvailable(recoverySecret);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        } catch (ServiceSpecificException e) {
            throw RecoverableKeyStoreLoaderException.fromServiceSpecificException(e);
        }
    }

    /**
     * Initializes recovery session and returns a blob with proof of recovery secrets possession.
     * The method generates symmetric key for a session, which trusted remote device can use to
     * return recovery key.
     *
     * @param sessionId ID for recovery session.
     * @param verifierPublicKey Certificate with Public key used to create the recovery blob on the
     *     source device. Keystore will verify the certificate using root of trust.
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
            @NonNull List<KeyStoreRecoveryMetadata> secrets)
            throws RecoverableKeyStoreLoaderException {
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
            throw RecoverableKeyStoreLoaderException.fromServiceSpecificException(e);
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
     *     KeyEntryRecoveryData}. Caller is responsibility to perform certificates check.
     * @return Map from alias to raw key material.
     */
    public Map<String, byte[]> recoverKeys(
            @NonNull String sessionId,
            @NonNull byte[] recoveryKeyBlob,
            @NonNull List<KeyEntryRecoveryData> applicationKeys)
            throws RecoverableKeyStoreLoaderException {
        try {
            return (Map<String, byte[]>) mBinder.recoverKeys(
                    sessionId, recoveryKeyBlob, applicationKeys);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        } catch (ServiceSpecificException e) {
            throw RecoverableKeyStoreLoaderException.fromServiceSpecificException(e);
        }
    }

    /**
     * Generates a key called {@code alias} and loads it into the recoverable key store. Returns the
     * raw material of the key.
     *
     * @throws RecoverableKeyStoreLoaderException if an error occurred generating and storing the
     *     key.
     */
    public byte[] generateAndStoreKey(String alias) throws RecoverableKeyStoreLoaderException {
        try {
            return mBinder.generateAndStoreKey(alias);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        } catch (ServiceSpecificException e) {
            throw RecoverableKeyStoreLoaderException.fromServiceSpecificException(e);
        }
    }
}
