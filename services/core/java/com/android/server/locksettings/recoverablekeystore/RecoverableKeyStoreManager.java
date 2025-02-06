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

import static android.security.keystore.recovery.RecoveryController.ERROR_BAD_CERTIFICATE_FORMAT;
import static android.security.keystore.recovery.RecoveryController.ERROR_DECRYPTION_FAILED;
import static android.security.keystore.recovery.RecoveryController.ERROR_DOWNGRADE_CERTIFICATE;
import static android.security.keystore.recovery.RecoveryController.ERROR_INSECURE_USER;
import static android.security.keystore.recovery.RecoveryController.ERROR_INVALID_CERTIFICATE;
import static android.security.keystore.recovery.RecoveryController.ERROR_INVALID_KEY_FORMAT;
import static android.security.keystore.recovery.RecoveryController.ERROR_NO_SNAPSHOT_PENDING;
import static android.security.keystore.recovery.RecoveryController.ERROR_SERVICE_INTERNAL_ERROR;
import static android.security.keystore.recovery.RecoveryController.ERROR_SESSION_EXPIRED;

import android.Manifest;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.KeyguardManager;
import android.app.PendingIntent;
import android.app.RemoteLockscreenValidationResult;
import android.app.RemoteLockscreenValidationSession;
import android.content.Context;
import android.os.Binder;
import android.os.RemoteException;
import android.os.ServiceSpecificException;
import android.os.UserHandle;
import android.security.keystore.recovery.KeyChainProtectionParams;
import android.security.keystore.recovery.KeyChainSnapshot;
import android.security.keystore.recovery.RecoveryCertPath;
import android.security.keystore.recovery.RecoveryController;
import android.security.keystore.recovery.WrappedApplicationKey;
import android.util.ArrayMap;
import android.util.FeatureFlagUtils;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.HexDump;
import com.android.internal.widget.LockPatternUtils;
import com.android.internal.widget.LockPatternView;
import com.android.internal.widget.LockscreenCredential;
import com.android.internal.widget.VerifyCredentialResponse;
import com.android.security.SecureBox;
import com.android.server.locksettings.LockSettingsService;
import com.android.server.locksettings.recoverablekeystore.certificate.CertParsingException;
import com.android.server.locksettings.recoverablekeystore.certificate.CertUtils;
import com.android.server.locksettings.recoverablekeystore.certificate.CertValidationException;
import com.android.server.locksettings.recoverablekeystore.certificate.CertXml;
import com.android.server.locksettings.recoverablekeystore.certificate.SigXml;
import com.android.server.locksettings.recoverablekeystore.storage.ApplicationKeyStorage;
import com.android.server.locksettings.recoverablekeystore.storage.CleanupManager;
import com.android.server.locksettings.recoverablekeystore.storage.RecoverableKeyStoreDb;
import com.android.server.locksettings.recoverablekeystore.storage.RecoverySessionStorage;
import com.android.server.locksettings.recoverablekeystore.storage.RecoverySnapshotStorage;
import com.android.server.locksettings.recoverablekeystore.storage.RemoteLockscreenValidationSessionStorage;
import com.android.server.locksettings.recoverablekeystore.storage.RemoteLockscreenValidationSessionStorage.LockscreenVerificationSession;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.UnrecoverableKeyException;
import java.security.cert.CertPath;
import java.security.cert.CertificateEncodingException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.security.spec.InvalidKeySpecException;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import javax.crypto.AEADBadTagException;

/**
 * Class with {@link RecoveryController} API implementation and internal methods to interact
 * with {@code LockSettingsService}.
 *
 * @hide
 */
public class RecoverableKeyStoreManager {
    private static final String TAG = "RecoverableKeyStoreMgr";
    private static final long SYNC_DELAY_MILLIS = 2000;
    private static final int INVALID_REMOTE_GUESS_LIMIT = 5;

    private static RecoverableKeyStoreManager mInstance;

    private final Context mContext;
    private final RecoverableKeyStoreDb mDatabase;
    private final RecoverySessionStorage mRecoverySessionStorage;
    private final ScheduledExecutorService mExecutorService;
    private final RecoverySnapshotListenersStorage mListenersStorage;
    private final RecoverableKeyGenerator mRecoverableKeyGenerator;
    private final RecoverySnapshotStorage mSnapshotStorage;
    private final PlatformKeyManager mPlatformKeyManager;
    private final ApplicationKeyStorage mApplicationKeyStorage;
    private final TestOnlyInsecureCertificateHelper mTestCertHelper;
    private final CleanupManager mCleanupManager;
    // only set if SETTINGS_ENABLE_LOCKSCREEN_TRANSFER_API is enabled.
    @Nullable private final RemoteLockscreenValidationSessionStorage
            mRemoteLockscreenValidationSessionStorage;

    /**
     * Returns a new or existing instance.
     *
     * @hide
     */
    public static synchronized RecoverableKeyStoreManager
            getInstance(Context context) {
        if (mInstance == null) {
            RecoverableKeyStoreDb db = RecoverableKeyStoreDb.newInstance(context);
            RemoteLockscreenValidationSessionStorage lockscreenCheckSessions;
            if (FeatureFlagUtils.isEnabled(context,
                    FeatureFlagUtils.SETTINGS_ENABLE_LOCKSCREEN_TRANSFER_API)) {
                lockscreenCheckSessions = new RemoteLockscreenValidationSessionStorage();
            } else {
                lockscreenCheckSessions = null;
            }
            PlatformKeyManager platformKeyManager;
            ApplicationKeyStorage applicationKeyStorage;
            try {
                platformKeyManager = PlatformKeyManager.getInstance(context, db);
                applicationKeyStorage = ApplicationKeyStorage.getInstance();
            } catch (NoSuchAlgorithmException e) {
                // Impossible: all algorithms must be supported by AOSP
                throw new RuntimeException(e);
            } catch (KeyStoreException e) {
                throw new ServiceSpecificException(ERROR_SERVICE_INTERNAL_ERROR, e.getMessage());
            }

            RecoverySnapshotStorage snapshotStorage =
                    RecoverySnapshotStorage.newInstance();
            CleanupManager cleanupManager = CleanupManager.getInstance(
                    context.getApplicationContext(),
                    snapshotStorage,
                    db,
                    applicationKeyStorage);
            mInstance = new RecoverableKeyStoreManager(
                    context.getApplicationContext(),
                    db,
                    new RecoverySessionStorage(),
                    Executors.newScheduledThreadPool(1),
                    snapshotStorage,
                    new RecoverySnapshotListenersStorage(),
                    platformKeyManager,
                    applicationKeyStorage,
                    new TestOnlyInsecureCertificateHelper(),
                    cleanupManager,
                    lockscreenCheckSessions);
        }
        return mInstance;
    }

    @VisibleForTesting
    RecoverableKeyStoreManager(
            Context context,
            RecoverableKeyStoreDb recoverableKeyStoreDb,
            RecoverySessionStorage recoverySessionStorage,
            ScheduledExecutorService executorService,
            RecoverySnapshotStorage snapshotStorage,
            RecoverySnapshotListenersStorage listenersStorage,
            PlatformKeyManager platformKeyManager,
            ApplicationKeyStorage applicationKeyStorage,
            TestOnlyInsecureCertificateHelper testOnlyInsecureCertificateHelper,
            CleanupManager cleanupManager,
            RemoteLockscreenValidationSessionStorage remoteLockscreenValidationSessionStorage) {
        mContext = context;
        mDatabase = recoverableKeyStoreDb;
        mRecoverySessionStorage = recoverySessionStorage;
        mExecutorService = executorService;
        mListenersStorage = listenersStorage;
        mSnapshotStorage = snapshotStorage;
        mPlatformKeyManager = platformKeyManager;
        mApplicationKeyStorage = applicationKeyStorage;
        mTestCertHelper = testOnlyInsecureCertificateHelper;
        mCleanupManager = cleanupManager;
        try {
            // Clears data for removed users.
            mCleanupManager.verifyKnownUsers();
        } catch (Exception e) {
            Log.e(TAG, "Failed to verify known users", e);
        }
        try {
            mRecoverableKeyGenerator = RecoverableKeyGenerator.newInstance(mDatabase);
        } catch (NoSuchAlgorithmException e) {
            Log.wtf(TAG, "AES keygen algorithm not available. AOSP must support this.", e);
            throw new ServiceSpecificException(ERROR_SERVICE_INTERNAL_ERROR, e.getMessage());
        }
        mRemoteLockscreenValidationSessionStorage = remoteLockscreenValidationSessionStorage;
    }

    /**
     * Used by {@link #initRecoveryServiceWithSigFile(String, byte[], byte[])}.
     */
    @VisibleForTesting
    void initRecoveryService(
            @NonNull String rootCertificateAlias, @NonNull byte[] recoveryServiceCertFile)
            throws RemoteException {
        checkRecoverKeyStorePermission();
        int userId = UserHandle.getCallingUserId();
        int uid = Binder.getCallingUid();

        rootCertificateAlias
                = mTestCertHelper.getDefaultCertificateAliasIfEmpty(rootCertificateAlias);
        if (!mTestCertHelper.isValidRootCertificateAlias(rootCertificateAlias)) {
            throw new ServiceSpecificException(
                    ERROR_INVALID_CERTIFICATE, "Invalid root certificate alias");
        }
        // Always set active alias to the argument of the last call to initRecoveryService method,
        // even if cert file is incorrect.
        String activeRootAlias = mDatabase.getActiveRootOfTrust(userId, uid);
        if (activeRootAlias == null) {
            Log.d(TAG, "Root of trust for recovery agent + " + uid
                + " is assigned for the first time to " + rootCertificateAlias);
        } else if (!activeRootAlias.equals(rootCertificateAlias)) {
            Log.i(TAG, "Root of trust for recovery agent " + uid + " is changed to "
                    + rootCertificateAlias + " from  " + activeRootAlias);
        }
        long updatedRows = mDatabase.setActiveRootOfTrust(userId, uid, rootCertificateAlias);
        if (updatedRows < 0) {
            throw new ServiceSpecificException(ERROR_SERVICE_INTERNAL_ERROR,
                    "Failed to set the root of trust in the local DB.");
        }

        CertXml certXml;
        try {
            certXml = CertXml.parse(recoveryServiceCertFile);
        } catch (CertParsingException e) {
            Log.d(TAG, "Failed to parse the input as a cert file: " + HexDump.toHexString(
                    recoveryServiceCertFile));
            throw new ServiceSpecificException(ERROR_BAD_CERTIFICATE_FORMAT, e.getMessage());
        }

        // Check serial number
        long newSerial = certXml.getSerial();
        Long oldSerial = mDatabase.getRecoveryServiceCertSerial(userId, uid, rootCertificateAlias);
        if (oldSerial != null && oldSerial >= newSerial
                && !mTestCertHelper.isTestOnlyCertificateAlias(rootCertificateAlias)) {
            if (oldSerial == newSerial) {
                Log.i(TAG, "The cert file serial number is the same, so skip updating.");
            } else {
                Log.e(TAG, "The cert file serial number is older than the one in database.");
                throw new ServiceSpecificException(ERROR_DOWNGRADE_CERTIFICATE,
                        "The cert file serial number is older than the one in database.");
            }
            return;
        }
        Log.i(TAG, "Updating the certificate with the new serial number " + newSerial);

        // Randomly choose and validate an endpoint certificate from the list
        CertPath certPath;
        X509Certificate rootCert =
                mTestCertHelper.getRootCertificate(rootCertificateAlias);
        Date validationDate = mTestCertHelper.getValidationDate(rootCertificateAlias);
        try {
            Log.d(TAG, "Getting and validating a random endpoint certificate");
            certPath = certXml.getRandomEndpointCert(rootCert, validationDate);
        } catch (CertValidationException e) {
            Log.e(TAG, "Invalid endpoint cert", e);
            throw new ServiceSpecificException(ERROR_INVALID_CERTIFICATE, e.getMessage());
        }

        // Save the chosen and validated certificate into database
        try {
            Log.d(TAG, "Saving the randomly chosen endpoint certificate to database");
            long updatedCertPathRows = mDatabase.setRecoveryServiceCertPath(userId, uid,
                    rootCertificateAlias, certPath);
            if (updatedCertPathRows > 0) {
                long updatedCertSerialRows = mDatabase.setRecoveryServiceCertSerial(userId, uid,
                        rootCertificateAlias, newSerial);
                if (updatedCertSerialRows < 0) {
                    // Ideally CertPath and CertSerial should be updated together in single
                    // transaction, but since their mismatch doesn't create many problems
                    // extra complexity is unnecessary.
                    throw new ServiceSpecificException(ERROR_SERVICE_INTERNAL_ERROR,
                        "Failed to set the certificate serial number in the local DB.");
                }
                if (mDatabase.getSnapshotVersion(userId, uid) != null) {
                    mDatabase.setShouldCreateSnapshot(userId, uid, true);
                    Log.i(TAG, "This is a certificate change. Snapshot must be updated");
                } else {
                    Log.i(TAG, "This is a certificate change. Snapshot didn't exist");
                }
                long updatedCounterIdRows =
                        mDatabase.setCounterId(userId, uid, new SecureRandom().nextLong());
                if (updatedCounterIdRows < 0) {
                    Log.e(TAG, "Failed to set the counter id in the local DB.");
                }
            } else if (updatedCertPathRows < 0) {
                throw new ServiceSpecificException(ERROR_SERVICE_INTERNAL_ERROR,
                        "Failed to set the certificate path in the local DB.");
            }
        } catch (CertificateEncodingException e) {
            Log.e(TAG, "Failed to encode CertPath", e);
            throw new ServiceSpecificException(ERROR_BAD_CERTIFICATE_FORMAT, e.getMessage());
        }
    }

    /**
     * Initializes the recovery service with the two files {@code recoveryServiceCertFile} and
     * {@code recoveryServiceSigFile}.
     *
     * @param rootCertificateAlias the alias for the root certificate that is used for validating
     *     the recovery service certificates.
     * @param recoveryServiceCertFile the content of the XML file containing a list of certificates
     *     for the recovery service.
     * @param recoveryServiceSigFile the content of the XML file containing the public-key signature
     *     over the entire content of {@code recoveryServiceCertFile}.
     */
    public void initRecoveryServiceWithSigFile(
            @NonNull String rootCertificateAlias, @NonNull byte[] recoveryServiceCertFile,
            @NonNull byte[] recoveryServiceSigFile)
            throws RemoteException {
        checkRecoverKeyStorePermission();
        rootCertificateAlias =
                mTestCertHelper.getDefaultCertificateAliasIfEmpty(rootCertificateAlias);
        Objects.requireNonNull(recoveryServiceCertFile, "recoveryServiceCertFile is null");
        Objects.requireNonNull(recoveryServiceSigFile, "recoveryServiceSigFile is null");

        SigXml sigXml;
        try {
            sigXml = SigXml.parse(recoveryServiceSigFile);
        } catch (CertParsingException e) {
            Log.d(TAG, "Failed to parse the sig file: " + HexDump.toHexString(
                    recoveryServiceSigFile));
            throw new ServiceSpecificException(ERROR_BAD_CERTIFICATE_FORMAT, e.getMessage());
        }

        X509Certificate rootCert =
                mTestCertHelper.getRootCertificate(rootCertificateAlias);
        Date validationDate = mTestCertHelper.getValidationDate(rootCertificateAlias);
        try {
            sigXml.verifyFileSignature(rootCert, recoveryServiceCertFile, validationDate);
        } catch (CertValidationException e) {
            Log.e(TAG, "The signature over the cert file is invalid."
                    + " Cert: " + HexDump.toHexString(recoveryServiceCertFile)
                    + " Sig: " + HexDump.toHexString(recoveryServiceSigFile));
            throw new ServiceSpecificException(ERROR_INVALID_CERTIFICATE, e.getMessage());
        }

        initRecoveryService(rootCertificateAlias, recoveryServiceCertFile);
    }

    /**
     * Gets all data necessary to recover application keys on new device.
     *
     * @return KeyChain Snapshot.
     * @throws ServiceSpecificException if no snapshot is pending.
     * @hide
     */
    public @NonNull KeyChainSnapshot getKeyChainSnapshot()
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
     * Set the server params for the user's key chain. This is used to uniquely identify a key
     * chain. Along with the counter ID, it is used to uniquely identify an instance of a vault.
     */
    public void setServerParams(@NonNull byte[] serverParams) throws RemoteException {
        checkRecoverKeyStorePermission();
        int userId = UserHandle.getCallingUserId();
        int uid = Binder.getCallingUid();

        byte[] currentServerParams = mDatabase.getServerParams(userId, uid);

        if (Arrays.equals(serverParams, currentServerParams)) {
            Log.v(TAG, "Not updating server params - same as old value.");
            return;
        }

        long updatedRows = mDatabase.setServerParams(userId, uid, serverParams);
        if (updatedRows < 0) {
            throw new ServiceSpecificException(
                    ERROR_SERVICE_INTERNAL_ERROR, "Database failure trying to set server params.");
        }

        if (currentServerParams == null) {
            Log.i(TAG, "Initialized server params.");
            return;
        }

        if (mDatabase.getSnapshotVersion(userId, uid) != null) {
            mDatabase.setShouldCreateSnapshot(userId, uid, true);
            Log.i(TAG, "Updated server params. Snapshot must be updated");
        } else {
            Log.i(TAG, "Updated server params. Snapshot didn't exist");
        }
    }

    /**
     * Sets the recovery status of key with {@code alias} to {@code status}.
     */
    public void setRecoveryStatus(@NonNull String alias, int status) throws RemoteException {
        checkRecoverKeyStorePermission();
        Objects.requireNonNull(alias, "alias is null");
        long updatedRows = mDatabase.setRecoveryStatus(Binder.getCallingUid(), alias, status);
        if (updatedRows < 0) {
            throw new ServiceSpecificException(
                    ERROR_SERVICE_INTERNAL_ERROR,
                    "Failed to set the key recovery status in the local DB.");
        }
    }

    /**
     * Returns recovery statuses for all keys belonging to the calling uid.
     *
     * @return {@link Map} from key alias to recovery status. Recovery status is one of
     *     {@link RecoveryController#RECOVERY_STATUS_SYNCED},
     *     {@link RecoveryController#RECOVERY_STATUS_SYNC_IN_PROGRESS} or
     *     {@link RecoveryController#RECOVERY_STATUS_PERMANENT_FAILURE}.
     */
    public @NonNull Map<String, Integer> getRecoveryStatus() throws RemoteException {
        checkRecoverKeyStorePermission();
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
        Objects.requireNonNull(secretTypes, "secretTypes is null");
        int userId = UserHandle.getCallingUserId();
        int uid = Binder.getCallingUid();

        int[] currentSecretTypes = mDatabase.getRecoverySecretTypes(userId, uid);
        if (Arrays.equals(secretTypes, currentSecretTypes)) {
            Log.v(TAG, "Not updating secret types - same as old value.");
            return;
        }

        long updatedRows = mDatabase.setRecoverySecretTypes(userId, uid, secretTypes);
        if (updatedRows < 0) {
            throw new ServiceSpecificException(ERROR_SERVICE_INTERNAL_ERROR,
                    "Database error trying to set secret types.");
        }

        if (currentSecretTypes.length == 0) {
            Log.i(TAG, "Initialized secret types.");
            return;
        }

        Log.i(TAG, "Updated secret types. Snapshot pending.");
        if (mDatabase.getSnapshotVersion(userId, uid) != null) {
            mDatabase.setShouldCreateSnapshot(userId, uid, true);
            Log.i(TAG, "Updated secret types. Snapshot must be updated");
        } else {
            Log.i(TAG, "Updated secret types. Snapshot didn't exist");
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
     * Initializes recovery session given the X509-encoded public key of the recovery service.
     *
     * @param sessionId A unique ID to identify the recovery session.
     * @param verifierPublicKey X509-encoded public key.
     * @param vaultParams Additional params associated with vault.
     * @param vaultChallenge Challenge issued by vault service.
     * @param secrets Lock-screen hashes. For now only a single secret is supported.
     * @return Encrypted bytes of recovery claim. This can then be issued to the vault service.
     * @deprecated Use {@link #startRecoverySessionWithCertPath(String, String, RecoveryCertPath,
     *         byte[], byte[], List)} instead.
     *
     * @hide
     */
    @VisibleForTesting
    @NonNull byte[] startRecoverySession(
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
        } catch (InvalidKeySpecException e) {
            throw new ServiceSpecificException(ERROR_BAD_CERTIFICATE_FORMAT, e.getMessage());
        }
        // The raw public key bytes contained in vaultParams must match the ones given in
        // verifierPublicKey; otherwise, the user secret may be decrypted by a key that is not owned
        // by the original recovery service.
        if (!publicKeysMatch(publicKey, vaultParams)) {
            throw new ServiceSpecificException(ERROR_INVALID_CERTIFICATE,
                    "The public keys given in verifierPublicKey and vaultParams do not match.");
        }

        byte[] keyClaimant = KeySyncUtils.generateKeyClaimant();
        byte[] kfHash = secrets.get(0).getSecret();
        mRecoverySessionStorage.add(
                uid,
                new RecoverySessionStorage.Entry(sessionId, kfHash, keyClaimant, vaultParams));

        Log.i(TAG, "Received VaultParams for recovery: " + HexDump.toHexString(vaultParams));
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
     * Initializes recovery session given the certificate path of the recovery service.
     *
     * @param sessionId A unique ID to identify the recovery session.
     * @param verifierCertPath The certificate path of the recovery service.
     * @param vaultParams Additional params associated with vault.
     * @param vaultChallenge Challenge issued by vault service.
     * @param secrets Lock-screen hashes. For now only a single secret is supported.
     * @return Encrypted bytes of recovery claim. This can then be issued to the vault service.
     *
     * @hide
     */
    public @NonNull byte[] startRecoverySessionWithCertPath(
            @NonNull String sessionId,
            @NonNull String rootCertificateAlias,
            @NonNull RecoveryCertPath verifierCertPath,
            @NonNull byte[] vaultParams,
            @NonNull byte[] vaultChallenge,
            @NonNull List<KeyChainProtectionParams> secrets)
            throws RemoteException {
        checkRecoverKeyStorePermission();
        rootCertificateAlias =
                mTestCertHelper.getDefaultCertificateAliasIfEmpty(rootCertificateAlias);
        Objects.requireNonNull(sessionId, "invalid session");
        Objects.requireNonNull(verifierCertPath, "verifierCertPath is null");
        Objects.requireNonNull(vaultParams, "vaultParams is null");
        Objects.requireNonNull(vaultChallenge, "vaultChallenge is null");
        Objects.requireNonNull(secrets, "secrets is null");
        CertPath certPath;
        try {
            certPath = verifierCertPath.getCertPath();
        } catch (CertificateException e) {
            throw new ServiceSpecificException(ERROR_BAD_CERTIFICATE_FORMAT, e.getMessage());
        }

        try {
            Date validationDate = mTestCertHelper.getValidationDate(rootCertificateAlias);
            CertUtils.validateCertPath(mTestCertHelper.getRootCertificate(rootCertificateAlias),
                    certPath, validationDate);
        } catch (CertValidationException e) {
            Log.e(TAG, "Failed to validate the given cert path", e);
            throw new ServiceSpecificException(ERROR_INVALID_CERTIFICATE, e.getMessage());
        }

        byte[] verifierPublicKey = certPath.getCertificates().get(0).getPublicKey().getEncoded();
        if (verifierPublicKey == null) {
            Log.e(TAG, "Failed to encode verifierPublicKey");
            throw new ServiceSpecificException(ERROR_BAD_CERTIFICATE_FORMAT,
                    "Failed to encode verifierPublicKey");
        }

        return startRecoverySession(
                sessionId, verifierPublicKey, vaultParams, vaultChallenge, secrets);
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
     * @throws RemoteException if an error occurred recovering the keys.
     */
    public @NonNull Map<String, String> recoverKeyChainSnapshot(
            @NonNull String sessionId,
            @NonNull byte[] encryptedRecoveryKey,
            @NonNull List<WrappedApplicationKey> applicationKeys) throws RemoteException {
        checkRecoverKeyStorePermission();
        int userId = UserHandle.getCallingUserId();
        int uid = Binder.getCallingUid();
        RecoverySessionStorage.Entry sessionEntry = mRecoverySessionStorage.get(uid, sessionId);
        if (sessionEntry == null) {
            throw new ServiceSpecificException(ERROR_SESSION_EXPIRED,
                    String.format(Locale.US,
                            "Application uid=%d does not have pending session '%s'",
                            uid,
                            sessionId));
        }

        try {
            byte[] recoveryKey = decryptRecoveryKey(sessionEntry, encryptedRecoveryKey);
            Map<String, byte[]> keysByAlias = recoverApplicationKeys(recoveryKey,
                    applicationKeys);
            return importKeyMaterials(userId, uid, keysByAlias);
        } catch (KeyStoreException e) {
            throw new ServiceSpecificException(ERROR_SERVICE_INTERNAL_ERROR, e.getMessage());
        } finally {
            sessionEntry.destroy();
            mRecoverySessionStorage.remove(uid);
        }
    }

    /**
     * Imports the key materials, returning a map from alias to grant alias for the calling user.
     *
     * @param userId The calling user ID.
     * @param uid The calling uid.
     * @param keysByAlias The key materials, keyed by alias.
     * @throws KeyStoreException if an error occurs importing the key or getting the grant.
     */
    private @NonNull Map<String, String> importKeyMaterials(
            int userId, int uid, Map<String, byte[]> keysByAlias)
            throws KeyStoreException {
        ArrayMap<String, String> grantAliasesByAlias = new ArrayMap<>(keysByAlias.size());
        for (String alias : keysByAlias.keySet()) {
            mApplicationKeyStorage.setSymmetricKeyEntry(userId, uid, alias, keysByAlias.get(alias));
            String grantAlias = getAlias(userId, uid, alias);
            Log.i(TAG, String.format(Locale.US, "Import %s -> %s", alias, grantAlias));
            grantAliasesByAlias.put(alias, grantAlias);
        }
        return grantAliasesByAlias;
    }

    /**
     * Returns an alias for the key.
     *
     * @param userId The user ID of the calling process.
     * @param uid The uid of the calling process.
     * @param alias The alias of the key.
     * @return The alias in the calling process's keystore.
     */
    private @Nullable String getAlias(int userId, int uid, String alias) {
        return mApplicationKeyStorage.getGrantAlias(userId, uid, alias);
    }

    /**
     * Destroys the session with the given {@code sessionId}.
     */
    public void closeSession(@NonNull String sessionId) throws RemoteException {
        checkRecoverKeyStorePermission();
        Objects.requireNonNull(sessionId, "invalid session");
        mRecoverySessionStorage.remove(Binder.getCallingUid(), sessionId);
    }

    public void removeKey(@NonNull String alias) throws RemoteException {
        checkRecoverKeyStorePermission();
        Objects.requireNonNull(alias, "alias is null");
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
     * @param alias the alias provided by caller as a reference to the key.
     * @return grant alias, which caller can use to access the key.
     * @throws RemoteException if certain internal errors occur.
     *
     * @deprecated Use {@link #generateKeyWithMetadata(String, byte[])} instead.
     */
    @Deprecated
    public String generateKey(@NonNull String alias) throws RemoteException {
        return generateKeyWithMetadata(alias, /*metadata=*/ null);
    }

    /**
     * Generates a key named {@code alias} with the {@code metadata} in caller's namespace.
     * The key is stored in system service keystore namespace.
     *
     * @param alias the alias provided by caller as a reference to the key.
     * @param metadata the optional metadata blob that will authenticated (but unencrypted) together
     *         with the key material when the key is uploaded to cloud.
     * @return grant alias, which caller can use to access the key.
     * @throws RemoteException if certain internal errors occur.
     */
    public String generateKeyWithMetadata(@NonNull String alias, @Nullable byte[] metadata)
            throws RemoteException {
        checkRecoverKeyStorePermission();
        Objects.requireNonNull(alias, "alias is null");
        int uid = Binder.getCallingUid();
        int userId = UserHandle.getCallingUserId();

        PlatformEncryptionKey encryptionKey;
        try {
            encryptionKey = mPlatformKeyManager.getEncryptKey(userId);
        } catch (NoSuchAlgorithmException e) {
            // Impossible: all algorithms must be supported by AOSP
            throw new RuntimeException(e);
        } catch (KeyStoreException | UnrecoverableKeyException | IOException e) {
            throw new ServiceSpecificException(ERROR_SERVICE_INTERNAL_ERROR, e.getMessage());
        } catch (InsecureUserException e) {
            throw new ServiceSpecificException(ERROR_INSECURE_USER, e.getMessage());
        }

        try {
            byte[] secretKey = mRecoverableKeyGenerator.generateAndStoreKey(encryptionKey, userId,
                    uid, alias, metadata);
            mApplicationKeyStorage.setSymmetricKeyEntry(userId, uid, alias, secretKey);
            return getAlias(userId, uid, alias);
        } catch (KeyStoreException | InvalidKeyException | RecoverableKeyStorageException e) {
            throw new ServiceSpecificException(ERROR_SERVICE_INTERNAL_ERROR, e.getMessage());
        }
    }

    /**
     * Imports a 256-bit AES-GCM key named {@code alias}. The key is stored in system service
     * keystore namespace.
     *
     * @param alias the alias provided by caller as a reference to the key.
     * @param keyBytes the raw bytes of the 256-bit AES key.
     * @return grant alias, which caller can use to access the key.
     * @throws RemoteException if the given key is invalid or some internal errors occur.
     *
     * @deprecated Use {{@link #importKeyWithMetadata(String, byte[], byte[])}} instead.
     *
     * @hide
     */
    @Deprecated
    public @Nullable String importKey(@NonNull String alias, @NonNull byte[] keyBytes)
            throws RemoteException {
        return importKeyWithMetadata(alias, keyBytes, /*metadata=*/ null);
    }

    /**
     * Imports a 256-bit AES-GCM key named {@code alias} with the given {@code metadata}. The key is
     * stored in system service keystore namespace.
     *
     * @param alias the alias provided by caller as a reference to the key.
     * @param keyBytes the raw bytes of the 256-bit AES key.
     * @param metadata the metadata to be authenticated (but unencrypted) together with the key.
     * @return grant alias, which caller can use to access the key.
     * @throws RemoteException if the given key is invalid or some internal errors occur.
     *
     * @hide
     */
    public @Nullable String importKeyWithMetadata(@NonNull String alias, @NonNull byte[] keyBytes,
            @Nullable byte[] metadata) throws RemoteException {
        checkRecoverKeyStorePermission();
        Objects.requireNonNull(alias, "alias is null");
        Objects.requireNonNull(keyBytes, "keyBytes is null");
        if (keyBytes.length != RecoverableKeyGenerator.KEY_SIZE_BITS / Byte.SIZE) {
            Log.e(TAG, "The given key for import doesn't have the required length "
                    + RecoverableKeyGenerator.KEY_SIZE_BITS);
            throw new ServiceSpecificException(ERROR_INVALID_KEY_FORMAT,
                    "The given key does not contain " + RecoverableKeyGenerator.KEY_SIZE_BITS
                            + " bits.");
        }

        int uid = Binder.getCallingUid();
        int userId = UserHandle.getCallingUserId();

        PlatformEncryptionKey encryptionKey;
        try {
            encryptionKey = mPlatformKeyManager.getEncryptKey(userId);
        } catch (NoSuchAlgorithmException e) {
            // Impossible: all algorithms must be supported by AOSP
            throw new RuntimeException(e);
        } catch (KeyStoreException | UnrecoverableKeyException | IOException e) {
            throw new ServiceSpecificException(ERROR_SERVICE_INTERNAL_ERROR, e.getMessage());
        } catch (InsecureUserException e) {
            throw new ServiceSpecificException(ERROR_INSECURE_USER, e.getMessage());
        }

        try {
            // Wrap the key by the platform key and store the wrapped key locally
            mRecoverableKeyGenerator.importKey(encryptionKey, userId, uid, alias, keyBytes,
                    metadata);

            // Import the key to Android KeyStore and get grant
            mApplicationKeyStorage.setSymmetricKeyEntry(userId, uid, alias, keyBytes);
            return getAlias(userId, uid, alias);
        } catch (KeyStoreException | InvalidKeyException | RecoverableKeyStorageException e) {
            throw new ServiceSpecificException(ERROR_SERVICE_INTERNAL_ERROR, e.getMessage());
        }
    }

    /**
     * Gets a key named {@code alias} in caller's namespace.
     *
     * @return grant alias, which caller can use to access the key.
     */
    public @Nullable String getKey(@NonNull String alias) throws RemoteException {
        checkRecoverKeyStorePermission();
        Objects.requireNonNull(alias, "alias is null");
        int uid = Binder.getCallingUid();
        int userId = UserHandle.getCallingUserId();
        return getAlias(userId, uid, alias);
    }

    private byte[] decryptRecoveryKey(
            RecoverySessionStorage.Entry sessionEntry, byte[] encryptedClaimResponse)
            throws RemoteException, ServiceSpecificException {
        byte[] locallyEncryptedKey;
        try {
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

    /**
     * Uses {@code recoveryKey} to decrypt {@code applicationKeys}.
     *
     * @return Map from alias to raw key material.
     * @throws RemoteException if an error occurred decrypting the keys.
     */
    private @NonNull Map<String, byte[]> recoverApplicationKeys(@NonNull byte[] recoveryKey,
            @NonNull List<WrappedApplicationKey> applicationKeys) throws RemoteException {
        HashMap<String, byte[]> keyMaterialByAlias = new HashMap<>();
        for (WrappedApplicationKey applicationKey : applicationKeys) {
            String alias = applicationKey.getAlias();
            byte[] encryptedKeyMaterial = applicationKey.getEncryptedKeyMaterial();
            byte[] keyMetadata = applicationKey.getMetadata();

            try {
                byte[] keyMaterial = KeySyncUtils.decryptApplicationKey(recoveryKey,
                        encryptedKeyMaterial, keyMetadata);
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
        if (!applicationKeys.isEmpty() && keyMaterialByAlias.isEmpty()) {
            Log.e(TAG, "Failed to recover any of the application keys.");
            throw new ServiceSpecificException(ERROR_DECRYPTION_FAILED,
                    "Failed to recover any of the application keys.");
        }
        return keyMaterialByAlias;
    }

    /**
     * This function can only be used inside LockSettingsService.
     *
     * @param credentialType the type of credential, as defined in {@code LockPatternUtils}
     * @param credential the credential, encoded as a byte array
     * @param userId the ID of the user to whom the credential belongs
     * @hide
     */
    public void lockScreenSecretAvailable(
            int credentialType, @NonNull byte[] credential, int userId) {
        // So as not to block the critical path unlocking the phone, defer to another thread.
        try {
            mExecutorService.schedule(KeySyncTask.newInstance(
                    mContext,
                    mDatabase,
                    mSnapshotStorage,
                    mListenersStorage,
                    userId,
                    credentialType,
                    credential,
                    /*credentialUpdated=*/ false),
                    SYNC_DELAY_MILLIS,
                    TimeUnit.MILLISECONDS
            );
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
     * @param credentialType the type of the new credential, as defined in {@code LockPatternUtils}
     * @param credential the new credential, encoded as a byte array
     * @param userId the ID of the user whose credential was changed
     * @hide
     */
    public void lockScreenSecretChanged(
            int credentialType,
            @Nullable byte[] credential,
            int userId) {
        // So as not to block the critical path unlocking the phone, defer to another thread.
        try {
            mExecutorService.schedule(KeySyncTask.newInstance(
                    mContext,
                    mDatabase,
                    mSnapshotStorage,
                    mListenersStorage,
                    userId,
                    credentialType,
                    credential,
                    /*credentialUpdated=*/ true),
                    SYNC_DELAY_MILLIS,
                    TimeUnit.MILLISECONDS
            );
        } catch (NoSuchAlgorithmException e) {
            Log.wtf(TAG, "Should never happen - algorithm unavailable for KeySync", e);
        } catch (KeyStoreException e) {
            Log.e(TAG, "Key store error encountered during recoverable key sync", e);
        } catch (InsecureUserException e) {
            Log.e(TAG, "InsecureUserException during lock screen secret update", e);
        }
    }

    /**
     * Starts a session to verify lock screen credentials provided by a remote device.
     */
    public RemoteLockscreenValidationSession startRemoteLockscreenValidation(
            LockSettingsService lockSettingsService) {
        if (mRemoteLockscreenValidationSessionStorage == null) {
            throw new UnsupportedOperationException("Under development");
        }
        checkVerifyRemoteLockscreenPermission();
        int userId = UserHandle.getCallingUserId();
        int savedCredentialType;
        final long token = Binder.clearCallingIdentity();
        try {
            savedCredentialType = lockSettingsService.getCredentialType(userId);
        } finally {
            Binder.restoreCallingIdentity(token);
        }
        int keyguardCredentialsType = lockPatternUtilsToKeyguardType(savedCredentialType);
        LockscreenVerificationSession session =
                mRemoteLockscreenValidationSessionStorage.startSession(userId);
        PublicKey publicKey = session.getKeyPair().getPublic();
        byte[] encodedPublicKey = SecureBox.encodePublicKey(publicKey);
        int badGuesses = mDatabase.getBadRemoteGuessCounter(userId);
        int remainingAttempts = Math.max(INVALID_REMOTE_GUESS_LIMIT - badGuesses, 0);
        // TODO(b/254335492): Schedule task to remove inactive session
        return new RemoteLockscreenValidationSession.Builder()
                .setLockType(keyguardCredentialsType)
                .setRemainingAttempts(remainingAttempts)
                .setSourcePublicKey(encodedPublicKey)
                .build();
    }

    /**
     * Verifies encrypted credentials guess from a remote device.
     */
    public synchronized RemoteLockscreenValidationResult validateRemoteLockscreen(
            @NonNull byte[] encryptedCredential,
            LockSettingsService lockSettingsService) {
        checkVerifyRemoteLockscreenPermission();
        int userId = UserHandle.getCallingUserId();
        LockscreenVerificationSession session =
                mRemoteLockscreenValidationSessionStorage.get(userId);
        int badGuesses = mDatabase.getBadRemoteGuessCounter(userId);
        int remainingAttempts = INVALID_REMOTE_GUESS_LIMIT - badGuesses;
        if (remainingAttempts <= 0) {
            return new RemoteLockscreenValidationResult.Builder()
                .setResultCode(RemoteLockscreenValidationResult.RESULT_NO_REMAINING_ATTEMPTS)
                .build();
        }
        if (session == null) {
            return new RemoteLockscreenValidationResult.Builder()
                .setResultCode(RemoteLockscreenValidationResult.RESULT_SESSION_EXPIRED)
                .build();
        }
        byte[] decryptedCredentials;
        try {
            decryptedCredentials = SecureBox.decrypt(
                session.getKeyPair().getPrivate(),
                /* sharedSecret= */ null,
                LockPatternUtils.ENCRYPTED_REMOTE_CREDENTIALS_HEADER,
                encryptedCredential);
        } catch (NoSuchAlgorithmException e) {
            Log.wtf(TAG, "Missing SecureBox algorithm. AOSP required to support this.", e);
            throw new IllegalStateException(e);
        } catch (InvalidKeyException e) {
            Log.e(TAG, "Got InvalidKeyException during lock screen credentials decryption");
            throw new IllegalStateException(e);
        } catch (AEADBadTagException e) {
            throw new IllegalStateException("Could not decrypt credentials guess", e);
        }
        int savedCredentialType;
        final long token = Binder.clearCallingIdentity();
        try {
            savedCredentialType = lockSettingsService.getCredentialType(userId);
            int keyguardCredentialsType = lockPatternUtilsToKeyguardType(savedCredentialType);
            try (LockscreenCredential credential =
                    createLockscreenCredential(keyguardCredentialsType, decryptedCredentials)) {
                LockPatternUtils.zeroize(decryptedCredentials);
                decryptedCredentials = null;
                VerifyCredentialResponse verifyResponse =
                        lockSettingsService.verifyCredential(credential, userId, 0);
                return handleVerifyCredentialResponse(verifyResponse, userId);
            }
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    private RemoteLockscreenValidationResult handleVerifyCredentialResponse(
            VerifyCredentialResponse response, int userId) {
        if (response.getResponseCode() == VerifyCredentialResponse.RESPONSE_OK) {
            mDatabase.setBadRemoteGuessCounter(userId, 0);
            mRemoteLockscreenValidationSessionStorage.finishSession(userId);
            return new RemoteLockscreenValidationResult.Builder()
                    .setResultCode(RemoteLockscreenValidationResult.RESULT_GUESS_VALID)
                    .build();
        }
        if (response.getResponseCode() == VerifyCredentialResponse.RESPONSE_RETRY) {
            long timeout = (long) response.getTimeout();
            return new RemoteLockscreenValidationResult.Builder()
                    .setResultCode(RemoteLockscreenValidationResult.RESULT_LOCKOUT)
                    .setTimeoutMillis(timeout)
                    .build();
        }
        // Invalid guess
        int badGuesses = mDatabase.getBadRemoteGuessCounter(userId);
        mDatabase.setBadRemoteGuessCounter(userId, badGuesses + 1);
        return new RemoteLockscreenValidationResult.Builder()
                .setResultCode(RemoteLockscreenValidationResult.RESULT_GUESS_INVALID)
                .build();
    }

    private LockscreenCredential createLockscreenCredential(
            int lockType, byte[] password) {
        switch (lockType) {
            case KeyguardManager.PASSWORD:
                CharSequence passwordStr = new String(password, StandardCharsets.UTF_8);
                return LockscreenCredential.createPassword(passwordStr);
            case KeyguardManager.PIN:
                CharSequence pinStr = new String(password);
                return LockscreenCredential.createPin(pinStr);
            case KeyguardManager.PATTERN:
                List<LockPatternView.Cell> pattern =
                        LockPatternUtils.byteArrayToPattern(password);
                return LockscreenCredential.createPattern(pattern);
            default:
                throw new IllegalStateException("Lockscreen is not set");
        }
    }

    private void checkVerifyRemoteLockscreenPermission() {
        mContext.enforceCallingOrSelfPermission(
                Manifest.permission.CHECK_REMOTE_LOCKSCREEN,
                "Caller " + Binder.getCallingUid()
                        + " doesn't have CHECK_REMOTE_LOCKSCREEN permission.");
        int userId = UserHandle.getCallingUserId();
        int uid = Binder.getCallingUid();
        mCleanupManager.registerRecoveryAgent(userId, uid);
    }

    private int lockPatternUtilsToKeyguardType(int credentialsType) {
        switch(credentialsType) {
            case LockPatternUtils.CREDENTIAL_TYPE_NONE:
                throw new IllegalStateException("Screen lock is not set");
            case LockPatternUtils.CREDENTIAL_TYPE_PATTERN:
                return KeyguardManager.PATTERN;
            case LockPatternUtils.CREDENTIAL_TYPE_PIN:
                return KeyguardManager.PIN;
            case LockPatternUtils.CREDENTIAL_TYPE_PASSWORD:
                return KeyguardManager.PASSWORD;
            default:
                throw new IllegalStateException("Screen lock is not set");
        }
    }

    private void checkRecoverKeyStorePermission() {
        mContext.enforceCallingOrSelfPermission(
                Manifest.permission.RECOVER_KEYSTORE,
                "Caller " + Binder.getCallingUid() + " doesn't have RecoverKeyStore permission.");
        int userId = UserHandle.getCallingUserId();
        int uid = Binder.getCallingUid();
        mCleanupManager.registerRecoveryAgent(userId, uid);
    }

    private boolean publicKeysMatch(PublicKey publicKey, byte[] vaultParams) {
        byte[] encodedPublicKey = SecureBox.encodePublicKey(publicKey);
        return Arrays.equals(encodedPublicKey, Arrays.copyOf(vaultParams, encodedPublicKey.length));
    }
}
