/*
 * Copyright (C) 2018 The Android Open Source Project
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

package android.security.keystore.recovery;

import android.Manifest;
import android.annotation.NonNull;
import android.annotation.RequiresPermission;
import android.annotation.SystemApi;
import android.os.RemoteException;
import android.os.ServiceSpecificException;
import android.util.ArrayMap;
import android.util.Log;

import java.security.Key;
import java.security.SecureRandom;
import java.security.UnrecoverableKeyException;
import java.security.cert.CertPath;
import java.security.cert.CertificateException;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Session to recover a {@link KeyChainSnapshot} from the remote trusted hardware, initiated by a
 * recovery agent.
 *
 * @hide
 */
@SystemApi
public class RecoverySession implements AutoCloseable {
    private static final String TAG = "RecoverySession";

    private static final int SESSION_ID_LENGTH_BYTES = 16;

    private final String mSessionId;
    private final RecoveryController mRecoveryController;

    private RecoverySession(@NonNull RecoveryController recoveryController,
            @NonNull String sessionId) {
        mRecoveryController = recoveryController;
        mSessionId = sessionId;
    }

    /**
     * A new session, started by the {@link RecoveryController}.
     */
    @RequiresPermission(android.Manifest.permission.RECOVER_KEYSTORE)
    static @NonNull RecoverySession newInstance(RecoveryController recoveryController) {
        return new RecoverySession(recoveryController, newSessionId());
    }

    /**
     * Returns a new random session ID.
     */
    private static @NonNull String newSessionId() {
        SecureRandom secureRandom = new SecureRandom();
        byte[] sessionId = new byte[SESSION_ID_LENGTH_BYTES];
        secureRandom.nextBytes(sessionId);
        StringBuilder sb = new StringBuilder();
        for (byte b : sessionId) {
            sb.append(Byte.toHexString(b, /*upperCase=*/ false));
        }
        return sb.toString();
    }

    /**
     * @deprecated Use {@link #start(String, CertPath, byte[], byte[], List)} instead.
     */
    @Deprecated
    @RequiresPermission(android.Manifest.permission.RECOVER_KEYSTORE)
    @NonNull public byte[] start(
            @NonNull byte[] verifierPublicKey,
            @NonNull byte[] vaultParams,
            @NonNull byte[] vaultChallenge,
            @NonNull List<KeyChainProtectionParams> secrets)
            throws CertificateException, InternalRecoveryServiceException {
        try {
            byte[] recoveryClaim =
                    mRecoveryController.getBinder().startRecoverySession(
                            mSessionId,
                            verifierPublicKey,
                            vaultParams,
                            vaultChallenge,
                            secrets);
            return recoveryClaim;
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        } catch (ServiceSpecificException e) {
            if (e.errorCode == RecoveryController.ERROR_BAD_CERTIFICATE_FORMAT
                    || e.errorCode == RecoveryController.ERROR_INVALID_CERTIFICATE) {
                throw new CertificateException("Invalid certificate for recovery session", e);
            }
            throw mRecoveryController.wrapUnexpectedServiceSpecificException(e);
        }
    }

    /**
     * @deprecated Use {@link #start(String, CertPath, byte[], byte[], List)} instead.
     */
    @Deprecated
    @RequiresPermission(android.Manifest.permission.RECOVER_KEYSTORE)
    @NonNull public byte[] start(
            @NonNull CertPath verifierCertPath,
            @NonNull byte[] vaultParams,
            @NonNull byte[] vaultChallenge,
            @NonNull List<KeyChainProtectionParams> secrets)
            throws CertificateException, InternalRecoveryServiceException {
        // Wrap the CertPath in a Parcelable so it can be passed via Binder calls.
        RecoveryCertPath recoveryCertPath =
                RecoveryCertPath.createRecoveryCertPath(verifierCertPath);
        try {
            byte[] recoveryClaim =
                    mRecoveryController.getBinder().startRecoverySessionWithCertPath(
                            mSessionId,
                            /*rootCertificateAlias=*/ "",  // Use the default root cert
                            recoveryCertPath,
                            vaultParams,
                            vaultChallenge,
                            secrets);
            return recoveryClaim;
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        } catch (ServiceSpecificException e) {
            if (e.errorCode == RecoveryController.ERROR_BAD_CERTIFICATE_FORMAT
                    || e.errorCode == RecoveryController.ERROR_INVALID_CERTIFICATE) {
                throw new CertificateException("Invalid certificate for recovery session", e);
            }
            throw mRecoveryController.wrapUnexpectedServiceSpecificException(e);
        }
    }

    /**
     * Starts a recovery session and returns a blob with proof of recovery secret possession.
     * The method generates a symmetric key for a session, which trusted remote device can use to
     * return recovery key.
     *
     * @param rootCertificateAlias The alias of the root certificate that is already in the Android
     *     OS. The root certificate will be used for validating {@code verifierCertPath}.
     * @param verifierCertPath The certificate path used to create the recovery blob on the source
     *     device. Keystore will verify the certificate path by using the root of trust.
     * @param vaultParams Must match the parameters in the corresponding field in the recovery blob.
     *     Used to limit number of guesses.
     * @param vaultChallenge Data passed from server for this recovery session and used to prevent
     *     replay attacks.
     * @param secrets Secrets provided by user, the method only uses type and secret fields.
     * @return The binary blob with recovery claim. It is encrypted with verifierPublicKey
     * and contains a proof of user secrets possession, session symmetric
     *     key and parameters necessary to identify the counter with the number of failed recovery
     *     attempts.
     * @throws CertificateException if the {@code verifierCertPath} is invalid.
     * @throws InternalRecoveryServiceException if an unexpected error occurred in the recovery
     *     service.
     */
    @RequiresPermission(android.Manifest.permission.RECOVER_KEYSTORE)
    @NonNull public byte[] start(
            @NonNull String rootCertificateAlias,
            @NonNull CertPath verifierCertPath,
            @NonNull byte[] vaultParams,
            @NonNull byte[] vaultChallenge,
            @NonNull List<KeyChainProtectionParams> secrets)
            throws CertificateException, InternalRecoveryServiceException {
        // Wrap the CertPath in a Parcelable so it can be passed via Binder calls.
        RecoveryCertPath recoveryCertPath =
                RecoveryCertPath.createRecoveryCertPath(verifierCertPath);
        try {
            byte[] recoveryClaim =
                    mRecoveryController.getBinder().startRecoverySessionWithCertPath(
                            mSessionId,
                            rootCertificateAlias,
                            recoveryCertPath,
                            vaultParams,
                            vaultChallenge,
                            secrets);
            return recoveryClaim;
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        } catch (ServiceSpecificException e) {
            if (e.errorCode == RecoveryController.ERROR_BAD_CERTIFICATE_FORMAT
                    || e.errorCode == RecoveryController.ERROR_INVALID_CERTIFICATE) {
                throw new CertificateException("Invalid certificate for recovery session", e);
            }
            throw mRecoveryController.wrapUnexpectedServiceSpecificException(e);
        }
    }

    /**
     * @deprecated Use {@link #recoverKeyChainSnapshot(byte[], List)} instead.
     */
    @Deprecated
    @RequiresPermission(android.Manifest.permission.RECOVER_KEYSTORE)
    public Map<String, byte[]> recoverKeys(
            @NonNull byte[] recoveryKeyBlob,
            @NonNull List<WrappedApplicationKey> applicationKeys)
            throws SessionExpiredException, DecryptionFailedException,
            InternalRecoveryServiceException {
        try {
            return (Map<String, byte[]>) mRecoveryController.getBinder().recoverKeys(
                    mSessionId, recoveryKeyBlob, applicationKeys);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        } catch (ServiceSpecificException e) {
            if (e.errorCode == RecoveryController.ERROR_DECRYPTION_FAILED) {
                throw new DecryptionFailedException(e.getMessage());
            }
            if (e.errorCode == RecoveryController.ERROR_SESSION_EXPIRED) {
                throw new SessionExpiredException(e.getMessage());
            }
            throw mRecoveryController.wrapUnexpectedServiceSpecificException(e);
        }
    }

    /**
     * Imports key chain snapshot recovered from a remote vault.
     *
     * @param recoveryKeyBlob Recovery blob encrypted by symmetric key generated for this session.
     * @param applicationKeys Application keys. Key material can be decrypted using recoveryKeyBlob
     *     and session key generated by {@link #start}.
     * @return {@code Map} from recovered keys aliases to their references.
     * @throws SessionExpiredException if {@code session} has since been closed.
     * @throws DecryptionFailedException if unable to decrypt the snapshot.
     * @throws InternalRecoveryServiceException if an error occurs internal to the recovery service.
     */
    @RequiresPermission(Manifest.permission.RECOVER_KEYSTORE)
    @NonNull public Map<String, Key> recoverKeyChainSnapshot(
            @NonNull byte[] recoveryKeyBlob,
            @NonNull List<WrappedApplicationKey> applicationKeys
    ) throws SessionExpiredException, DecryptionFailedException, InternalRecoveryServiceException {
        try {
            Map<String, String> grantAliases = mRecoveryController
                    .getBinder()
                    .recoverKeyChainSnapshot(mSessionId, recoveryKeyBlob, applicationKeys);
            return getKeysFromGrants(grantAliases);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        } catch (ServiceSpecificException e) {
            if (e.errorCode == RecoveryController.ERROR_DECRYPTION_FAILED) {
                throw new DecryptionFailedException(e.getMessage());
            }
            if (e.errorCode == RecoveryController.ERROR_SESSION_EXPIRED) {
                throw new SessionExpiredException(e.getMessage());
            }
            throw mRecoveryController.wrapUnexpectedServiceSpecificException(e);
        }
    }

    /** Given a map from alias to grant alias, returns a map from alias to a {@link Key} handle. */
    private @NonNull Map<String, Key> getKeysFromGrants(@NonNull Map<String, String> grantAliases)
            throws InternalRecoveryServiceException {
        ArrayMap<String, Key> keysByAlias = new ArrayMap<>(grantAliases.size());
        for (String alias : grantAliases.keySet()) {
            String grantAlias = grantAliases.get(alias);
            Key key;
            try {
                key = mRecoveryController.getKeyFromGrant(grantAlias);
            } catch (UnrecoverableKeyException e) {
                throw new InternalRecoveryServiceException(
                        String.format(
                                Locale.US,
                                "Failed to get key '%s' from grant '%s'",
                                alias,
                                grantAlias), e);
            }
            keysByAlias.put(alias, key);
        }
        return keysByAlias;
    }

    /**
     * An internal session ID, used by the framework to match recovery claims to snapshot responses.
     *
     * @hide
     */
    String getSessionId() {
        return mSessionId;
    }

    /**
     * Deletes all data associated with {@code session}.
     */
    @RequiresPermission(android.Manifest.permission.RECOVER_KEYSTORE)
    @Override
    public void close() {
        try {
            mRecoveryController.getBinder().closeSession(mSessionId);
        } catch (RemoteException | ServiceSpecificException e) {
            Log.e(TAG, "Unexpected error trying to close session", e);
        }
    }
}
