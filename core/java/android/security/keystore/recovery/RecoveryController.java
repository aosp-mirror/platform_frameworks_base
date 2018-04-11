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

package android.security.keystore.recovery;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.SystemApi;
import android.app.KeyguardManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.pm.PackageManager.NameNotFoundException;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.ServiceSpecificException;
import android.security.KeyStore;
import android.security.keystore.AndroidKeyStoreProvider;

import com.android.internal.widget.ILockSettings;

import java.security.Key;
import java.security.UnrecoverableKeyException;
import java.security.cert.CertPath;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Backs up cryptographic keys to remote secure hardware, encrypted with the user's lock screen.
 *
 * <p>A system app with the {@code android.permission.RECOVER_KEYSTORE} permission may generate or
 * import recoverable keys using this class. To generate a key, the app must call
 * {@link #generateKey(String)} with the desired alias for the key. This returns an AndroidKeyStore
 * reference to a 256-bit {@link javax.crypto.SecretKey}, which can be used for AES/GCM/NoPadding.
 * In order to get the same key again at a later time, the app can call {@link #getKey(String)} with
 * the same alias. If a key is generated in this way the key's raw material is never directly
 * exposed to the calling app. The system app may also import key material using
 * {@link #importKey(String, byte[])}. The app may only generate and import keys for its own
 * {@code uid}.
 *
 * <p>The same system app must also register a Recovery Agent to manage syncing recoverable keys to
 * remote secure hardware. The Recovery Agent is a service that registers itself with the controller
 * as follows:
 *
 * <ul>
 *     <li>Invokes {@link #initRecoveryService(String, byte[], byte[])}
 *     <ul>
 *         <li>The first argument is the alias of the root certificate used to verify trusted
 *         hardware modules. Each trusted hardware module must have a public key signed with this
 *         root of trust. Roots of trust must be shipped with the framework. The app can list all
 *         valid roots of trust by calling {@link #getRootCertificates()}.
 *         <li>The second argument is the UTF-8 bytes of the XML listing file. It lists the X509
 *         certificates containing the public keys of all available remote trusted hardware modules.
 *         Each of the X509 certificates can be validated against the chosen root of trust.
 *         <li>The third argument is the UTF-8 bytes of the XML signing file. The file contains a
 *         signature of the XML listing file. The signature can be validated against the chosen root
 *         of trust.
 *     </ul>
 *     <p>This will cause the controller to choose a random public key from the list. From then
 *     on the controller will attempt to sync the key chain with the trusted hardware module to whom
 *     that key belongs.
 *     <li>Invokes {@link #setServerParams(byte[])} with a byte string that identifies the device
 *     to a remote server. This server may act as the front-end to the trusted hardware modules. It
 *     is up to the Recovery Agent to decide how best to identify devices, but this could be, e.g.,
 *     based on the <a href="https://developers.google.com/instance-id/">Instance ID</a> of the
 *     system app.
 *     <li>Invokes {@link #setRecoverySecretTypes(int[])} with a list of types of secret used to
 *     secure the recoverable key chain. For now only
 *     {@link KeyChainProtectionParams#TYPE_LOCKSCREEN} is supported.
 *     <li>Invokes {@link #setSnapshotCreatedPendingIntent(PendingIntent)} with a
 *     {@link PendingIntent} that is to be invoked whenever a new snapshot is created. Although the
 *     controller can create snapshots without the Recovery Agent registering this intent, it is a
 *     good idea to register the intent so that the Recovery Agent is able to sync this snapshot to
 *     the trusted hardware module as soon as it is available.
 * </ul>
 *
 * <p>The trusted hardware module's public key MUST be generated on secure hardware with protections
 * equivalent to those described in the
 * <a href="https://developer.android.com/preview/features/security/ckv-whitepaper.html">Google
 * Cloud Key Vault Service whitepaper</a>. The trusted hardware module itself must protect the key
 * chain from brute-forcing using the methods also described in the whitepaper: i.e., it should
 * limit the number of allowed attempts to enter the lock screen. If the number of attempts is
 * exceeded the key material must no longer be recoverable.
 *
 * <p>A recoverable key chain snapshot is considered pending if any of the following conditions
 * are met:
 *
 * <ul>
 *     <li>The system app mutates the key chain. i.e., generates, imports, or removes a key.
 *     <li>The user changes their lock screen.
 * </ul>
 *
 * <p>Whenever the user unlocks their device, if a snapshot is pending, the Recovery Controller
 * generates a new snapshot. It follows these steps to do so:
 *
 * <ul>
 *     <li>Generates a 256-bit AES key using {@link java.security.SecureRandom}. This is the
 *     Recovery Key.
 *     <li>Wraps the key material of all keys in the recoverable key chain with the Recovery Key.
 *     <li>Encrypts the Recovery Key with both the public key of the trusted hardware module and a
 *     symmetric key derived from the user's lock screen.
 * </ul>
 *
 * <p>The controller then writes this snapshot to disk, and uses the {@link PendingIntent} that was
 * set by the Recovery Agent during initialization to inform it that a new snapshot is available.
 * The snapshot only contains keys for that Recovery Agent's {@code uid} - i.e., keys the agent's
 * app itself generated. If multiple Recovery Agents exist on the device, each will be notified of
 * their new snapshots, and each snapshots' keys will be only those belonging to the same
 * {@code uid}.
 *
 * <p>The Recovery Agent retrieves its most recent snapshot by calling
 * {@link #getKeyChainSnapshot()}. It syncs the snapshot to the remote server. The snapshot contains
 * the public key used for encryption, which the server uses to forward the encrypted recovery key
 * to the correct trusted hardware module. The snapshot also contains the server params, which are
 * used to identify this device to the server.
 *
 * <p>The client uses the server params to identify a device whose key chain it wishes to restore.
 * This may be on a different device to the device that originally synced the key chain. The client
 * sends the server params identifying the previous device to the server. The server returns the
 * X509 certificate identifying the trusted hardware module in which the encrypted Recovery Key is
 * stored. It also returns some vault parameters identifying that particular Recovery Key to the
 * trusted hardware module. And it also returns a vault challenge, which is used as part of the
 * vault opening protocol to ensure the recovery claim is fresh. See the whitepaper for more
 * details.
 *
 * <p>The key chain is recovered via a {@link RecoverySession}. A Recovery Agent creates one by
 * invoking {@link #createRecoverySession()}. It then invokes
 * {@link RecoverySession#start(String, CertPath, byte[], byte[], List)} with these arguments:
 *
 * <ul>
 *     <li>The alias of the root of trust used to verify the trusted hardware module.
 *     <li>The X509 certificate of the trusted hardware module.
 *     <li>The vault parameters used to identify the Recovery Key to the trusted hardware module.
 *     <li>The vault challenge, as issued by the trusted hardware module.
 *     <li>A list of secrets, corresponding to the secrets used to protect the key chain. At the
 *     moment this is a single {@link KeyChainProtectionParams} containing the lock screen of the
 *     device whose key chain is to be recovered.
 * </ul>
 *
 * <p>This method returns a byte array containing the Recovery Claim, which can be issued to the
 * remote trusted hardware module. It is encrypted with the trusted hardware module's public key
 * (which has itself been certified with the root of trust). It also contains an ephemeral symmetric
 * key generated for this recovery session, which the remote trusted hardware module uses to encrypt
 * its responses. This is the Session Key.
 *
 * <p>If the lock screen provided is correct, the remote trusted hardware module decrypts one of the
 * layers of lock-screen encryption from the Recovery Key. It then returns this key, encrypted with
 * the Session Key to the Recovery Agent. As the Recovery Agent does not know the Session Key, it
 * must then invoke {@link RecoverySession#recoverKeyChainSnapshot(byte[], List)} with the encrypted
 * Recovery Key and the list of wrapped application keys. The controller then decrypts the layer of
 * encryption provided by the Session Key, and uses the lock screen to decrypt the final layer of
 * encryption. It then uses the Recovery Key to decrypt all of the wrapped application keys, and
 * imports them into its own KeyStore. The Recovery Agent's app may then access these keys by
 * calling {@link #getKey(String)}. Only this app's {@code uid} may access the keys that have been
 * recovered.
 *
 * @hide
 */
@SystemApi
public class RecoveryController {
    private static final String TAG = "RecoveryController";

    /** Key has been successfully synced. */
    public static final int RECOVERY_STATUS_SYNCED = 0;
    /** Waiting for recovery agent to sync the key. */
    public static final int RECOVERY_STATUS_SYNC_IN_PROGRESS = 1;
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
     * Failed because the format of the provided certificate is incorrect, e.g., cannot be decoded
     * properly or misses necessary fields.
     *
     * <p>Note that this is different from {@link #ERROR_INVALID_CERTIFICATE}, which implies the
     * certificate has a correct format but cannot be validated.
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

    /**
     * Error thrown if the format of a given key is invalid. This might be because the key has a
     * wrong length, invalid content, etc.
     *
     * @hide
     */
    public static final int ERROR_INVALID_KEY_FORMAT = 27;

    /**
     * Failed because the provided certificate cannot be validated, e.g., is expired or has invalid
     * signatures.
     *
     * <p>Note that this is different from {@link #ERROR_BAD_CERTIFICATE_FORMAT}, which denotes
     * incorrect certificate formats, e.g., due to wrong encoding or structure.
     *
     * @hide
     */
    public static final int ERROR_INVALID_CERTIFICATE = 28;


    /**
     * Failed because the provided certificate contained serial version which is lower that the
     * version device is already initialized with. It is not possible to downgrade serial version of
     * the provided certificate.
     *
     * @hide
     */
    public static final int ERROR_DOWNGRADE_CERTIFICATE = 29;

    private final ILockSettings mBinder;
    private final KeyStore mKeyStore;

    private RecoveryController(ILockSettings binder, KeyStore keystore) {
        mBinder = binder;
        mKeyStore = keystore;
    }

    /**
     * Internal method used by {@code RecoverySession}.
     *
     * @hide
     */
    ILockSettings getBinder() {
        return mBinder;
    }

    /**
     * Gets a new instance of the class.
     */
    @RequiresPermission(android.Manifest.permission.RECOVER_KEYSTORE)
    @NonNull public static RecoveryController getInstance(@NonNull Context context) {
        ILockSettings lockSettings =
                ILockSettings.Stub.asInterface(ServiceManager.getService("lock_settings"));
        return new RecoveryController(lockSettings, KeyStore.getInstance());
    }

    /**
     * Checks whether the recoverable key store is currently available.
     *
     * <p>If it returns true, the device must currently be using a screen lock that is supported for
     * use with the recoverable key store, i.e. AOSP PIN, pattern or password.
     */
    @RequiresPermission(android.Manifest.permission.RECOVER_KEYSTORE)
    public static boolean isRecoverableKeyStoreEnabled(@NonNull Context context) {
        KeyguardManager keyguardManager = context.getSystemService(KeyguardManager.class);
        return keyguardManager != null && keyguardManager.isDeviceSecure();
    }

    /**
     * @deprecated Use {@link #initRecoveryService(String, byte[], byte[])} instead.
     */
    @Deprecated
    @RequiresPermission(android.Manifest.permission.RECOVER_KEYSTORE)
    public void initRecoveryService(
            @NonNull String rootCertificateAlias, @NonNull byte[] signedPublicKeyList)
            throws CertificateException, InternalRecoveryServiceException {
        try {
            mBinder.initRecoveryService(rootCertificateAlias, signedPublicKeyList);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        } catch (ServiceSpecificException e) {
            if (e.errorCode == ERROR_BAD_CERTIFICATE_FORMAT
                    || e.errorCode == ERROR_INVALID_CERTIFICATE) {
                throw new CertificateException("Invalid certificate for recovery service", e);
            }
            throw wrapUnexpectedServiceSpecificException(e);
        }
    }

    /**
     * Initializes the recovery service for the calling application. The detailed steps should be:
     * <ol>
     *     <li>Parse {@code signatureFile} to get relevant information.
     *     <li>Validate the signer's X509 certificate, contained in {@code signatureFile}, against
     *         the root certificate pre-installed in the OS and chosen by {@code
     *         rootCertificateAlias}.
     *     <li>Verify the public-key signature, contained in {@code signatureFile}, and verify it
     *         against the entire {@code certificateFile}.
     *     <li>Parse {@code certificateFile} to get relevant information.
     *     <li>Check the serial number, contained in {@code certificateFile}, and skip the following
     *         steps if the serial number is not larger than the one previously stored.
     *     <li>Randomly choose a X509 certificate from the endpoint X509 certificates, contained in
     *         {@code certificateFile}, and validate it against the root certificate pre-installed
     *         in the OS and chosen by {@code rootCertificateAlias}.
     *     <li>Store the chosen X509 certificate and the serial in local database for later use.
     * </ol>
     *
     * @param rootCertificateAlias the alias of a root certificate pre-installed in the OS
     * @param certificateFile the binary content of the XML file containing a list of recovery
     *     service X509 certificates, and other metadata including the serial number
     * @param signatureFile the binary content of the XML file containing the public-key signature
     *     of the entire certificate file, and a signer's X509 certificate
     * @throws CertificateException if the given certificate files cannot be parsed or validated
     * @throws InternalRecoveryServiceException if an unexpected error occurred in the recovery
     *     service.
     */
    @RequiresPermission(android.Manifest.permission.RECOVER_KEYSTORE)
    public void initRecoveryService(
            @NonNull String rootCertificateAlias, @NonNull byte[] certificateFile,
            @NonNull byte[] signatureFile)
            throws CertificateException, InternalRecoveryServiceException {
        try {
            mBinder.initRecoveryServiceWithSigFile(
                    rootCertificateAlias, certificateFile, signatureFile);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        } catch (ServiceSpecificException e) {
            if (e.errorCode == ERROR_BAD_CERTIFICATE_FORMAT
                    || e.errorCode == ERROR_INVALID_CERTIFICATE) {
                throw new CertificateException("Invalid certificate for recovery service", e);
            }
            if (e.errorCode == ERROR_DOWNGRADE_CERTIFICATE) {
                throw new CertificateException(
                        "Downgrading certificate serial version isn't supported.", e);
            }
            throw wrapUnexpectedServiceSpecificException(e);
        }
    }

    /**
     * @deprecated Use {@link #getKeyChainSnapshot()}
     */
    @Deprecated
    @RequiresPermission(android.Manifest.permission.RECOVER_KEYSTORE)
    public @Nullable KeyChainSnapshot getRecoveryData() throws InternalRecoveryServiceException {
        return getKeyChainSnapshot();
    }

    /**
     * Returns data necessary to store all recoverable keys. Key material is
     * encrypted with user secret and recovery public key.
     *
     * @return Data necessary to recover keystore or {@code null} if snapshot is not available.
     * @throws InternalRecoveryServiceException if an unexpected error occurred in the recovery
     *     service.
     */
    @RequiresPermission(android.Manifest.permission.RECOVER_KEYSTORE)
    public @Nullable KeyChainSnapshot getKeyChainSnapshot()
            throws InternalRecoveryServiceException {
        try {
            return mBinder.getKeyChainSnapshot();
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
     * #getKeyChainSnapshot} can be used to get the snapshot. Note that every recovery agent can
     * have at most one registered listener at any time.
     *
     * @param intent triggered when new snapshot is available. Unregisters listener if the value is
     *     {@code null}.
     * @throws InternalRecoveryServiceException if an unexpected error occurred in the recovery
     *     service.
     */
    @RequiresPermission(android.Manifest.permission.RECOVER_KEYSTORE)
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
     * Server parameters used to generate new recovery key blobs. This value will be included in
     * {@code KeyChainSnapshot.getEncryptedRecoveryKeyBlob()}. The same value must be included
     * in vaultParams {@link RecoverySession#start(CertPath, byte[], byte[], List)}.
     *
     * @param serverParams included in recovery key blob.
     * @see #getKeyChainSnapshot
     * @throws InternalRecoveryServiceException if an unexpected error occurred in the recovery
     *     service.
     */
    @RequiresPermission(android.Manifest.permission.RECOVER_KEYSTORE)
    public void setServerParams(@NonNull byte[] serverParams)
            throws InternalRecoveryServiceException {
        try {
            mBinder.setServerParams(serverParams);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        } catch (ServiceSpecificException e) {
            throw wrapUnexpectedServiceSpecificException(e);
        }
    }

    /**
     * @deprecated Use {@link #getAliases()}.
     */
    @Deprecated
    @RequiresPermission(android.Manifest.permission.RECOVER_KEYSTORE)
    public List<String> getAliases(@Nullable String packageName)
            throws InternalRecoveryServiceException {
        return getAliases();
    }

    /**
     * Returns a list of aliases of keys belonging to the application.
     */
    @RequiresPermission(android.Manifest.permission.RECOVER_KEYSTORE)
    public @NonNull List<String> getAliases() throws InternalRecoveryServiceException {
        try {
            Map<String, Integer> allStatuses = mBinder.getRecoveryStatus();
            return new ArrayList<>(allStatuses.keySet());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        } catch (ServiceSpecificException e) {
            throw wrapUnexpectedServiceSpecificException(e);
        }
    }

    /**
     * @deprecated Use {@link #setRecoveryStatus(String, int)}
     */
    @Deprecated
    @RequiresPermission(android.Manifest.permission.RECOVER_KEYSTORE)
    public void setRecoveryStatus(
            @NonNull String packageName, String alias, int status)
            throws NameNotFoundException, InternalRecoveryServiceException {
        setRecoveryStatus(alias, status);
    }

    /**
     * Sets the recovery status for given key. It is used to notify the keystore that the key was
     * successfully stored on the server or that there was an error. An application can check this
     * value using {@link #getRecoveryStatus(String, String)}.
     *
     * @param alias The alias of the key whose status to set.
     * @param status The status of the key. One of {@link #RECOVERY_STATUS_SYNCED},
     *     {@link #RECOVERY_STATUS_SYNC_IN_PROGRESS} or {@link #RECOVERY_STATUS_PERMANENT_FAILURE}.
     * @throws InternalRecoveryServiceException if an unexpected error occurred in the recovery
     *     service.
     */
    @RequiresPermission(android.Manifest.permission.RECOVER_KEYSTORE)
    public void setRecoveryStatus(@NonNull String alias, int status)
            throws InternalRecoveryServiceException {
        try {
            mBinder.setRecoveryStatus(alias, status);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        } catch (ServiceSpecificException e) {
            throw wrapUnexpectedServiceSpecificException(e);
        }
    }

    /**
     * @deprecated Use {@link #getRecoveryStatus(String)}.
     */
    @Deprecated
    @RequiresPermission(android.Manifest.permission.RECOVER_KEYSTORE)
    public int getRecoveryStatus(String packageName, String alias)
            throws InternalRecoveryServiceException {
        return getRecoveryStatus(alias);
    }

    /**
     * Returns the recovery status for the key with the given {@code alias}.
     *
     * <ul>
     *   <li>{@link #RECOVERY_STATUS_SYNCED}
     *   <li>{@link #RECOVERY_STATUS_SYNC_IN_PROGRESS}
     *   <li>{@link #RECOVERY_STATUS_PERMANENT_FAILURE}
     * </ul>
     *
     * @see #setRecoveryStatus(String, int)
     * @throws InternalRecoveryServiceException if an unexpected error occurred in the recovery
     *     service.
     */
    @RequiresPermission(android.Manifest.permission.RECOVER_KEYSTORE)
    public int getRecoveryStatus(@NonNull String alias) throws InternalRecoveryServiceException {
        try {
            Map<String, Integer> allStatuses = mBinder.getRecoveryStatus();
            Integer status = allStatuses.get(alias);
            if (status == null) {
                return RecoveryController.RECOVERY_STATUS_PERMANENT_FAILURE;
            } else {
                return status;
            }
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
     * @param secretTypes {@link KeyChainProtectionParams#TYPE_LOCKSCREEN}
     * @throws InternalRecoveryServiceException if an unexpected error occurred in the recovery
     *     service.
     */
    @RequiresPermission(android.Manifest.permission.RECOVER_KEYSTORE)
    public void setRecoverySecretTypes(
            @NonNull @KeyChainProtectionParams.UserSecretType int[] secretTypes)
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
     * necessary to generate KeyChainSnapshot.
     *
     * @return list of recovery secret types
     * @see KeyChainSnapshot
     * @throws InternalRecoveryServiceException if an unexpected error occurred in the recovery
     *     service.
     */
    @RequiresPermission(android.Manifest.permission.RECOVER_KEYSTORE)
    public @NonNull @KeyChainProtectionParams.UserSecretType int[] getRecoverySecretTypes()
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
     * Deprecated.
     * Generates a AES256/GCM/NoPADDING key called {@code alias} and loads it into the recoverable
     * key store. Returns the raw material of the key.
     *
     * @param alias The key alias.
     * @param account The account associated with the key
     * @throws InternalRecoveryServiceException if an unexpected error occurred in the recovery
     *     service.
     * @throws LockScreenRequiredException if the user has not set a lock screen. This is required
     *     to generate recoverable keys, as the snapshots are encrypted using a key derived from the
     *     lock screen.
     */
    @RequiresPermission(android.Manifest.permission.RECOVER_KEYSTORE)
    public byte[] generateAndStoreKey(@NonNull String alias, byte[] account)
            throws InternalRecoveryServiceException, LockScreenRequiredException {
        throw new UnsupportedOperationException("Operation is not supported, use generateKey");
    }

    /**
     * @deprecated Use {@link #generateKey(String)}.
     */
    @Deprecated
    @RequiresPermission(android.Manifest.permission.RECOVER_KEYSTORE)
    public Key generateKey(@NonNull String alias, byte[] account)
            throws InternalRecoveryServiceException, LockScreenRequiredException {
        return generateKey(alias);
    }

    /**
     * Generates a recoverable key with the given {@code alias}.
     *
     * @throws InternalRecoveryServiceException if an unexpected error occurred in the recovery
     *     service.
     * @throws LockScreenRequiredException if the user does not have a lock screen set. A lock
     *     screen is required to generate recoverable keys.
     */
    @RequiresPermission(android.Manifest.permission.RECOVER_KEYSTORE)
    public @NonNull Key generateKey(@NonNull String alias) throws InternalRecoveryServiceException,
            LockScreenRequiredException {
        try {
            String grantAlias = mBinder.generateKey(alias);
            if (grantAlias == null) {
                throw new InternalRecoveryServiceException("null grant alias");
            }
            return getKeyFromGrant(grantAlias);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        } catch (UnrecoverableKeyException e) {
            throw new InternalRecoveryServiceException("Failed to get key from keystore", e);
        } catch (ServiceSpecificException e) {
            if (e.errorCode == ERROR_INSECURE_USER) {
                throw new LockScreenRequiredException(e.getMessage());
            }
            throw wrapUnexpectedServiceSpecificException(e);
        }
    }

    /**
     * Imports a 256-bit recoverable AES key with the given {@code alias} and the raw bytes {@code
     * keyBytes}.
     *
     * @throws InternalRecoveryServiceException if an unexpected error occurred in the recovery
     *     service.
     * @throws LockScreenRequiredException if the user does not have a lock screen set. A lock
     *     screen is required to generate recoverable keys.
     *
     */
    @RequiresPermission(android.Manifest.permission.RECOVER_KEYSTORE)
    public @NonNull Key importKey(@NonNull String alias, @NonNull byte[] keyBytes)
            throws InternalRecoveryServiceException, LockScreenRequiredException {
        try {
            String grantAlias = mBinder.importKey(alias, keyBytes);
            if (grantAlias == null) {
                throw new InternalRecoveryServiceException("Null grant alias");
            }
            return getKeyFromGrant(grantAlias);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        } catch (UnrecoverableKeyException e) {
            throw new InternalRecoveryServiceException("Failed to get key from keystore", e);
        } catch (ServiceSpecificException e) {
            if (e.errorCode == ERROR_INSECURE_USER) {
                throw new LockScreenRequiredException(e.getMessage());
            }
            throw wrapUnexpectedServiceSpecificException(e);
        }
    }

    /**
     * Gets a key called {@code alias} from the recoverable key store.
     *
     * @param alias The key alias.
     * @return The key.
     * @throws InternalRecoveryServiceException if an unexpected error occurred in the recovery
     *     service.
     * @throws UnrecoverableKeyException if key is permanently invalidated or not found.
     */
    @RequiresPermission(android.Manifest.permission.RECOVER_KEYSTORE)
    public @Nullable Key getKey(@NonNull String alias)
            throws InternalRecoveryServiceException, UnrecoverableKeyException {
        try {
            String grantAlias = mBinder.getKey(alias);
            if (grantAlias == null || "".equals(grantAlias)) {
                return null;
            }
            return getKeyFromGrant(grantAlias);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        } catch (ServiceSpecificException e) {
            throw wrapUnexpectedServiceSpecificException(e);
        }
    }

    /**
     * Returns the key with the given {@code grantAlias}.
     */
    @NonNull Key getKeyFromGrant(@NonNull String grantAlias) throws UnrecoverableKeyException {
        return AndroidKeyStoreProvider.loadAndroidKeyStoreKeyFromKeystore(
                mKeyStore,
                grantAlias,
                KeyStore.UID_SELF);
    }

    /**
     * Removes a key called {@code alias} from the recoverable key store.
     *
     * @param alias The key alias.
     * @throws InternalRecoveryServiceException if an unexpected error occurred in the recovery
     *     service.
     */
    @RequiresPermission(android.Manifest.permission.RECOVER_KEYSTORE)
    public void removeKey(@NonNull String alias) throws InternalRecoveryServiceException {
        try {
            mBinder.removeKey(alias);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        } catch (ServiceSpecificException e) {
            throw wrapUnexpectedServiceSpecificException(e);
        }
    }

    /**
     * Returns a new {@link RecoverySession}.
     *
     * <p>A recovery session is required to restore keys from a remote store.
     */
    @RequiresPermission(android.Manifest.permission.RECOVER_KEYSTORE)
    public @NonNull RecoverySession createRecoverySession() {
        return RecoverySession.newInstance(this);
    }

    @RequiresPermission(android.Manifest.permission.RECOVER_KEYSTORE)
    public @NonNull Map<String, X509Certificate> getRootCertificates() {
        return TrustedRootCertificates.getRootCertificates();
    }

    InternalRecoveryServiceException wrapUnexpectedServiceSpecificException(
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
