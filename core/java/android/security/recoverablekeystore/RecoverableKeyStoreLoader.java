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
import android.content.pm.PackageManager.NameNotFoundException;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.ServiceSpecificException;
import android.os.UserHandle;
import android.security.KeyStore;
import android.util.AndroidException;

import com.android.internal.widget.ILockSettings;

import java.util.List;

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
    public static final int UNINITIALIZED_RECOVERY_PUBLIC_KEY = 20;
    // Too many updates to recovery public key or server parameters.
    public static final int RATE_LIMIT_EXCEEDED = 21;

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
                case UNINITIALIZED_RECOVERY_PUBLIC_KEY:
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
            mBinder.initRecoveryService(
                    rootCertificateAlias, signedPublicKeyList, UserHandle.getCallingUserId());
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
    public KeyStoreRecoveryData getRecoveryData(@NonNull byte[] account)
            throws RecoverableKeyStoreLoaderException {
        try {
            KeyStoreRecoveryData recoveryData =
                    mBinder.getRecoveryData(account, UserHandle.getCallingUserId());
            return recoveryData;
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        } catch (ServiceSpecificException e) {
            throw RecoverableKeyStoreLoaderException.fromServiceSpecificException(e);
        }
    }

    /**
     * Server parameters used to generate new recovery key blobs. This value will be included in
     * {@code KeyStoreRecoveryData.getEncryptedRecoveryKeyBlob()}. The same value must be included
     * in vaultParams {@link startRecoverySession}
     *
     * @param serverParameters included in recovery key blob.
     * @see #getRecoveryData
     * @throws RecoverableKeyStoreLoaderException If parameters rotation is rate limited.
     * @hide
     */
    public void setServerParameters(long serverParameters)
            throws RecoverableKeyStoreLoaderException {
        try {
            mBinder.setServerParameters(serverParameters, UserHandle.getCallingUserId());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        } catch (ServiceSpecificException e) {
            throw RecoverableKeyStoreLoaderException.fromServiceSpecificException(e);
        }
    }

    /**
     * Updates recovery status for given keys. It is used to notify keystore that key was
     * successfully stored on the server or there were an error. Returned as a part of KeyInfo data
     * structure.
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
            mBinder.setRecoveryStatus(packageName, aliases, status, UserHandle.getCallingUserId());
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
            mBinder.setRecoverySecretTypes(secretTypes, UserHandle.getCallingUserId());
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
            return mBinder.getRecoverySecretTypes(UserHandle.getCallingUserId());
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
            return mBinder.getPendingRecoverySecretTypes(UserHandle.getCallingUserId());
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
            mBinder.recoverySecretAvailable(recoverySecret, UserHandle.getCallingUserId());
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
                            secrets,
                            UserHandle.getCallingUserId());
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
     * @param sessionId Id for recovery session, same as in = {@link startRecoverySession}.
     * @param recoveryKeyBlob Recovery blob encrypted by symmetric key generated for this session.
     * @param applicationKeys Application keys. Key material can be decrypted using recoveryKeyBlob
     *     and session. KeyStore only uses package names from the application info in {@link
     *     KeyEntryRecoveryData}. Caller is responsibility to perform certificates check.
     */
    public void recoverKeys(
            @NonNull String sessionId,
            @NonNull byte[] recoveryKeyBlob,
            @NonNull List<KeyEntryRecoveryData> applicationKeys)
            throws RecoverableKeyStoreLoaderException {
        try {
            mBinder.recoverKeys(
                    sessionId, recoveryKeyBlob, applicationKeys, UserHandle.getCallingUserId());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        } catch (ServiceSpecificException e) {
            throw RecoverableKeyStoreLoaderException.fromServiceSpecificException(e);
        }
    }
}
