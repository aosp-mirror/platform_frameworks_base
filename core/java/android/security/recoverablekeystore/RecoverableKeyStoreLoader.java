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
import android.os.ServiceManager;

import com.android.internal.widget.ILockSettings;

import java.util.List;

/**
 * A wrapper around KeyStore which lets key be exported to
 * trusted hardware on server side and recovered later.
 *
 * @hide
 */
public class RecoverableKeyStoreLoader  {

    private final ILockSettings mBinder;

    // Exception codes, should be in sync with {@code KeyStoreException}.
    public static final int SYSTEM_ERROR = 4;

    public static final int UNINITIALIZED_RECOVERY_PUBLIC_KEY = 20;

    // Too many updates to recovery public key or server parameters.
    public static final int RATE_LIMIT_EXCEEDED = 21;

    private RecoverableKeyStoreLoader(ILockSettings binder) {
        mBinder = binder;
    }

    /**
     * @hide
     */
    public static RecoverableKeyStoreLoader getInstance() {
        ILockSettings lockSettings =
                ILockSettings.Stub.asInterface(ServiceManager.getService("lock_settings"));
        return new RecoverableKeyStoreLoader(lockSettings);
    }

    /**
     * @hide
     */
    public static class RecoverableKeyStoreLoaderException extends Exception {
        private final int mErrorCode;

        public RecoverableKeyStoreLoaderException(int errorCode, String message) {
            super(message);
            mErrorCode = errorCode;
        }

        public int getErrorCode() {
            return mErrorCode;
        }
    }

    /**
     * Initializes key recovery service for the calling application. RecoverableKeyStoreLoader
     * randomly chooses one of the keys from the list
     * and keeps it to use for future key export operations. Collection of all keys
     * in the list must be signed by the provided {@code rootCertificateAlias}, which must also be
     * present in the list of root certificates preinstalled on the device. The random selection
     * allows RecoverableKeyStoreLoader to select which of a set of remote recovery service
     * devices will be used.
     *
     * <p>In addition, RecoverableKeyStoreLoader enforces a delay of three months between
     * consecutive initialization attempts, to limit the ability of an attacker to often switch
     * remote recovery devices and significantly increase number of recovery attempts.
     *
     * @param rootCertificateAlias alias of a root certificate preinstalled on the device
     * @param signedPublicKeyList binary blob a list of X509 certificates and signature
     * @throws RecoverableKeyStoreLoaderException if signature is invalid, or key rotation was rate
     * limited.
     * @hide
     */
    public void initRecoveryService(@NonNull String rootCertificateAlias,
            @NonNull byte[] signedPublicKeyList)
            throws RecoverableKeyStoreLoaderException {
        throw new RecoverableKeyStoreLoaderException(SYSTEM_ERROR, "Not implemented");
        // TODO: extend widget/ILockSettings.aidl
        /* try {
            mBinder.initRecoveryService(rootCertificate, publicKeyList);
        } catch (RemoteException  e) {
            throw e.rethrowFromSystemServer();
        } */
    }

    /**
     * Returns data necessary to store all recoverable keys for given account.
     * Key material is encrypted with user secret and recovery public key.
     */
    public KeyStoreRecoveryData getRecoveryData(@NonNull byte[] account)
            throws RecoverableKeyStoreLoaderException {
        throw new RecoverableKeyStoreLoaderException(SYSTEM_ERROR, "Not implemented");
    }

    /**
     * Server parameters used to generate new recovery key blobs. This value will be included in
     * {@code KeyStoreRecoveryData.getEncryptedRecoveryKeyBlob()}.
     * The same value must be included in vaultParams  {@link startRecoverySession}
     *
     * @see #getRecoveryData
     * @throws RecoverableKeyStoreLoaderException If parameters rotation is rate limited.
     */
    public void updateServerParameters(long serverParameters)
            throws RecoverableKeyStoreLoaderException {
        throw new RecoverableKeyStoreLoaderException(SYSTEM_ERROR, "Not implemented");
    }

    /**
     * Updates recovery status for given keys.
     * It is used to notify keystore that key was successfully stored on the server or
     * there were an error. Returned as a part of KeyInfo data structure.
     *
     * @param packageName Application whose recoverable keys' statuses are to be updated.
     * @param aliases List of application-specific key aliases. If the array is empty, updates the
     * status for all existing recoverable keys.
     * @param status Status specific to recovery agent.
     */
    public void setRecoveryStatus(@NonNull String packageName, @Nullable String[] aliases,
            int status) throws NameNotFoundException, RecoverableKeyStoreLoaderException {
        throw new RecoverableKeyStoreLoaderException(SYSTEM_ERROR, "Not implemented");
    }

    /**
     * Specifies a set of secret types used for end-to-end keystore encryption.
     * Knowing all of them is necessary to recover data.
     *
     * @param secretTypes {@link KeyStoreRecoveryMetadata#TYPE_LOCKSCREEN} or
     * {@link KeyStoreRecoveryMetadata#TYPE_CUSTOM_PASSWORD}
     */
    public void setRecoverySecretTypes(@NonNull @KeyStoreRecoveryMetadata.UserSecretType
            int[] secretTypes) throws RecoverableKeyStoreLoaderException {
        throw new RecoverableKeyStoreLoaderException(SYSTEM_ERROR, "Not implemented");
    }

    /**
     * Defines a set of secret types used for end-to-end keystore encryption.
     * Knowing all of them is necessary to generate KeyStoreRecoveryData.
     * @see KeyStoreRecoveryData
     */
    public @NonNull @KeyStoreRecoveryMetadata.UserSecretType int[] getRecoverySecretTypes()
            throws RecoverableKeyStoreLoaderException {
        throw new RecoverableKeyStoreLoaderException(SYSTEM_ERROR, "Not implemented");
    }

    /**
     * Returns a list of recovery secret types, necessary to create a pending recovery snapshot.
     * When user enters a secret of a pending type
     * {@link #recoverySecretAvailable} should be called.
     */
    public @NonNull @KeyStoreRecoveryMetadata.UserSecretType int[] getPendingRecoverySecretTypes()
            throws RecoverableKeyStoreLoaderException {
        throw new RecoverableKeyStoreLoaderException(SYSTEM_ERROR, "Not implemented");
    }

    /**
     * Method notifies KeyStore that a user-generated secret is available.
     * This method generates a symmetric session key which a trusted remote device can use
     * to return a recovery key.
     * Caller should use {@link KeyStoreRecoveryMetadata#clearSecret} to override the secret value
     * in memory.
     *
     * @param recoverySecret user generated secret together with parameters necessary to
     * regenerate it on a new device.
     */
    public void recoverySecretAvailable(@NonNull KeyStoreRecoveryMetadata recoverySecret)
            throws RecoverableKeyStoreLoaderException {
        throw new RecoverableKeyStoreLoaderException(SYSTEM_ERROR, "Not implemented");
    }

    /**
     * Initializes recovery session and returns a blob with proof of recovery secrets possession.
     * The method generates symmetric key for a session, which trusted remote device can use
     * to return recovery key.
     *
     * @param sessionId ID for recovery session.
     * @param verifierPublicKey Certificate with Public key used to create the recovery blob on
     * the source device. Keystore will verify the certificate using root of trust.
     * @param vaultParams Must match the parameters in the corresponding field in the recovery blob.
     * Used to limit number of guesses.
     * @param vaultChallenge Data passed from server for this recovery session and used to prevent
     * replay attacks
     * @param secrets Secrets provided by user, the method only uses type and secret fields.
     * @return Binary blob with recovery claim. It is encrypted with verifierPublicKey and
     * contains a proof of user secrets, session symmetric key and parameters necessary to identify
     * the counter with the number of failed recovery attempts.
     */
    public @NonNull byte[] startRecoverySession(@NonNull String sessionId,
            @NonNull byte[] verifierPublicKey, @NonNull byte[] vaultParams,
            @NonNull byte[] vaultChallenge, @NonNull List<KeyStoreRecoveryMetadata> secrets)
            throws RecoverableKeyStoreLoaderException {
        throw new RecoverableKeyStoreLoaderException(SYSTEM_ERROR, "Not implemented");
    }

    /**
     * Imports keys.
     *
     * @param sessionId Id for recovery session, same as in = {@link startRecoverySession}.
     * @param recoveryKeyBlob Recovery blob encrypted by symmetric key generated for this session.
     * @param applicationKeys Application keys. Key material can be decrypted using recoveryKeyBlob
     * and session. KeyStore only uses package names from the application info in
     * {@link KeyEntryRecoveryData}. Caller is responsibility to perform certificates check.
     */
    public void recoverKeys(@NonNull String sessionId, @NonNull byte[] recoveryKeyBlob,
            @NonNull List<KeyEntryRecoveryData> applicationKeys)
            throws RecoverableKeyStoreLoaderException {
        throw new RecoverableKeyStoreLoaderException(SYSTEM_ERROR, "Not implemented");
    }
}
