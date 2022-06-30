/*
 * Copyright (C) 2020 The Android Open Source Project
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

package android.security;

import android.annotation.NonNull;
import android.compat.annotation.ChangeId;
import android.compat.annotation.Disabled;
import android.os.Binder;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.ServiceSpecificException;
import android.security.keymaster.KeymasterDefs;
import android.system.keystore2.Domain;
import android.system.keystore2.IKeystoreService;
import android.system.keystore2.KeyDescriptor;
import android.system.keystore2.KeyEntryResponse;
import android.system.keystore2.ResponseCode;
import android.util.Log;

import java.util.Calendar;

/**
 * @hide This should not be made public in its present form because it
 * assumes that private and secret key bytes are available and would
 * preclude the use of hardware crypto.
 */
public class KeyStore2 {
    private static final String TAG = "KeyStore";

    private static final int RECOVERY_GRACE_PERIOD_MS = 50;

    /**
     * Keystore operation creation may fail
     *
     * Keystore used to work under the assumption that the creation of cryptographic operations
     * always succeeds. However, the KeyMint backend has only a limited number of operation slots.
     * In order to keep up the appearance of "infinite" operation slots, the Keystore daemon
     * would prune least recently used operations if there is no available operation slot.
     * As a result, good operations could be terminated prematurely.
     *
     * This opens AndroidKeystore up to denial-of-service and unintended livelock situations.
     * E.g.: if multiple apps wake up at the same time, e.g., due to power management optimizations,
     * and attempt to perform crypto operations, they start terminating each others operations
     * without making any progress.
     *
     * To break out of livelocks and to discourage DoS attempts we have changed the pruning
     * strategy such that it prefers clients that use few operation slots and only briefly.
     * As a result we can, almost, guarantee that single operations that don't linger inactive
     * for more than 5 seconds will conclude unhampered by the pruning strategy. "Almost",
     * because there are operations related to file system encryption that can prune even
     * these operations, but those are extremely rare.
     *
     * As a side effect of this new pruning strategy operation creation can now fail if the
     * client has a lower pruning power than all of the existing operations.
     *
     * Pruning strategy
     *
     * To find a suitable candidate we compute the malus for the caller and each existing
     * operation. The malus is the inverse of the pruning power (caller) or pruning
     * resistance (existing operation). For the caller to be able to prune an operation it must
     * find an operation with a malus higher than its own.
     *
     * For more detail on the pruning strategy consult the implementation at
     * https://android.googlesource.com/platform/system/security/+/refs/heads/master/keystore2/src/operation.rs
     *
     * For older SDK version, KeyStore2 will poll the Keystore daemon for a free operation
     * slot. So to applications, targeting earlier SDK versions, it will still look like cipher and
     * signature object initialization always succeeds, however, it may take longer to get an
     * operation.
     *
     * All SDK version benefit from fairer operation slot scheduling and a better chance to
     * successfully conclude an operation.
     */
    @ChangeId
    @Disabled // See b/180133780
    static final long KEYSTORE_OPERATION_CREATION_MAY_FAIL = 169897160L;

    // Never use mBinder directly, use KeyStore2.getService() instead or better yet
    // handleRemoteExceptionWithRetry which retries connecting to Keystore once in case
    // of a remote exception.
    private IKeystoreService mBinder;


    @FunctionalInterface
    interface CheckedRemoteRequest<R> {
        R execute(IKeystoreService service) throws RemoteException;
    }

    private <R> R handleRemoteExceptionWithRetry(@NonNull CheckedRemoteRequest<R> request)
            throws KeyStoreException {
        IKeystoreService service = getService(false /* retryLookup */);
        boolean firstTry = true;
        while (true) {
            try {
                return request.execute(service);
            } catch (ServiceSpecificException e) {
                throw getKeyStoreException(e.errorCode, e.getMessage());
            } catch (RemoteException e) {
                if (firstTry) {
                    Log.w(TAG, "Looks like we may have lost connection to the Keystore "
                            + "daemon.");
                    Log.w(TAG, "Retrying after giving Keystore "
                            + RECOVERY_GRACE_PERIOD_MS + "ms to recover.");
                    interruptedPreservingSleep(RECOVERY_GRACE_PERIOD_MS);
                    service = getService(true /* retry Lookup */);
                    firstTry = false;
                } else {
                    Log.e(TAG, "Cannot connect to Keystore daemon.", e);
                    throw new KeyStoreException(ResponseCode.SYSTEM_ERROR, "", e.getMessage());
                }
            }
        }
    }

    private static final String KEYSTORE2_SERVICE_NAME =
            "android.system.keystore2.IKeystoreService/default";

    private KeyStore2() {
        mBinder = null;
    }

    public static KeyStore2 getInstance() {
        return new KeyStore2();
    }

    private synchronized IKeystoreService getService(boolean retryLookup) {
        if (mBinder == null || retryLookup) {
            mBinder = IKeystoreService.Stub.asInterface(ServiceManager
                    .getService(KEYSTORE2_SERVICE_NAME));
            Binder.allowBlocking(mBinder.asBinder());
        }
        return mBinder;
    }

    void delete(KeyDescriptor descriptor) throws KeyStoreException {
        handleRemoteExceptionWithRetry((service) -> {
            service.deleteKey(descriptor);
            return 0;
        });
    }

    /**
     * List all entries in the keystore for in the given namespace.
     */
    public KeyDescriptor[] list(int domain, long namespace) throws KeyStoreException {
        return handleRemoteExceptionWithRetry((service) -> service.listEntries(domain, namespace));
    }

    /**
     * Grant string prefix as used by the keystore boringssl engine. Must be kept in sync
     * with system/security/keystore-engine. Note: The prefix here includes the 0x which
     * std::stringstream used in keystore-engine needs to identify the number as hex represented.
     * Here we include it in the prefix, because Long#parseUnsignedLong does not understand it
     * and gets the radix as explicit argument.
     * @hide
     */
    private static final String KEYSTORE_ENGINE_GRANT_ALIAS_PREFIX =
            "ks2_keystore-engine_grant_id:0x";

    /**
     * This function turns a grant identifier into a specific string that is understood by the
     * keystore-engine in system/security/keystore-engine. Is only used by VPN and WI-FI components
     * to allow certain system components like racoon or vendor components like WPA supplicant
     * to use keystore keys with boring ssl.
     *
     * @param grantId the grant id as returned by {@link #grant} in the {@code nspace} filed of
     *                the resulting {@code KeyDescriptor}.
     * @return The grant descriptor string.
     * @hide
     */
    public static String makeKeystoreEngineGrantString(long grantId) {
        return String.format("%s%016X", KEYSTORE_ENGINE_GRANT_ALIAS_PREFIX, grantId);
    }

    /**
     * Convenience function to turn a keystore engine grant string as returned by
     * {@link #makeKeystoreEngineGrantString(long)} back into a grant KeyDescriptor.
     *
     * @param grantString As string returned by {@link #makeKeystoreEngineGrantString(long)}
     * @return The grant key descriptor.
     * @hide
     */
    public static KeyDescriptor keystoreEngineGrantString2KeyDescriptor(String grantString) {
        KeyDescriptor key = new KeyDescriptor();
        key.domain = Domain.GRANT;
        key.nspace = Long.parseUnsignedLong(
                grantString.substring(KEYSTORE_ENGINE_GRANT_ALIAS_PREFIX.length()), 16);
        key.alias = null;
        key.blob = null;
        return key;
    }

    /**
     * Create a grant that allows the grantee identified by {@code granteeUid} to use
     * the key specified by {@code descriptor} withint the restrictions given by
     * {@code accessVectore}.
     * @see IKeystoreService#grant(KeyDescriptor, int, int) for more details.
     * @param descriptor
     * @param granteeUid
     * @param accessVector
     * @return
     * @throws KeyStoreException
     * @hide
     */
    public KeyDescriptor grant(KeyDescriptor descriptor, int granteeUid, int accessVector)
            throws  KeyStoreException {
        return handleRemoteExceptionWithRetry(
                (service) -> service.grant(descriptor, granteeUid, accessVector)
        );
    }

    /**
     * Destroys a grant.
     * @see IKeystoreService#ungrant(KeyDescriptor, int) for more details.
     * @param descriptor
     * @param granteeUid
     * @throws KeyStoreException
     * @hide
     */
    public void ungrant(KeyDescriptor descriptor, int granteeUid)
            throws KeyStoreException {
        handleRemoteExceptionWithRetry((service) -> {
            service.ungrant(descriptor, granteeUid);
            return 0;
        });
    }

    /**
     * Retrieves a key entry from the keystore backend.
     * @see IKeystoreService#getKeyEntry(KeyDescriptor) for more details.
     * @param descriptor
     * @return
     * @throws KeyStoreException
     * @hide
     */
    public KeyEntryResponse getKeyEntry(@NonNull KeyDescriptor descriptor)
            throws KeyStoreException {
        return handleRemoteExceptionWithRetry((service) -> service.getKeyEntry(descriptor));
    }

    /**
     * Get the security level specific keystore interface from the keystore daemon.
     * @see IKeystoreService#getSecurityLevel(int) for more details.
     * @param securityLevel
     * @return
     * @throws KeyStoreException
     * @hide
     */
    public KeyStoreSecurityLevel getSecurityLevel(int securityLevel)
            throws KeyStoreException {
        return handleRemoteExceptionWithRetry((service) ->
            new KeyStoreSecurityLevel(
                    service.getSecurityLevel(securityLevel)
            )
        );
    }

    /**
     * Update the subcomponents of a key entry designated by the key descriptor.
     * @see IKeystoreService#updateSubcomponent(KeyDescriptor, byte[], byte[]) for more details.
     * @param key
     * @param publicCert
     * @param publicCertChain
     * @throws KeyStoreException
     * @hide
     */
    public void updateSubcomponents(@NonNull KeyDescriptor key, byte[] publicCert,
            byte[] publicCertChain) throws KeyStoreException {
        handleRemoteExceptionWithRetry((service) -> {
            service.updateSubcomponent(key, publicCert, publicCertChain);
            return 0;
        });
    }

    /**
     * Delete the key designed by the key descriptor.
     * @see IKeystoreService#deleteKey(KeyDescriptor) for more details.
     * @param descriptor
     * @throws KeyStoreException
     * @hide
     */
    public void deleteKey(@NonNull KeyDescriptor descriptor)
            throws KeyStoreException {
        handleRemoteExceptionWithRetry((service) -> {
            service.deleteKey(descriptor);
            return 0;
        });
    }

    protected static void interruptedPreservingSleep(long millis) {
        boolean wasInterrupted = false;
        Calendar calendar = Calendar.getInstance();
        long target = calendar.getTimeInMillis() + millis;
        while (true) {
            try {
                Thread.sleep(target - calendar.getTimeInMillis());
                break;
            } catch (InterruptedException e) {
                wasInterrupted = true;
            } catch (IllegalArgumentException e) {
                // This means that the argument to sleep was negative.
                // So we are done sleeping.
                break;
            }
        }
        if (wasInterrupted) {
            Thread.currentThread().interrupt();
        }
    }

    static KeyStoreException getKeyStoreException(int errorCode, String serviceErrorMessage) {
        if (errorCode > 0) {
            // KeyStore layer error
            switch (errorCode) {
                case ResponseCode.LOCKED:
                    return new KeyStoreException(errorCode, "User authentication required",
                            serviceErrorMessage);
                case ResponseCode.UNINITIALIZED:
                    return new KeyStoreException(errorCode, "Keystore not initialized",
                            serviceErrorMessage);
                case ResponseCode.SYSTEM_ERROR:
                    return new KeyStoreException(errorCode, "System error", serviceErrorMessage);
                case ResponseCode.PERMISSION_DENIED:
                    return new KeyStoreException(errorCode, "Permission denied",
                            serviceErrorMessage);
                case ResponseCode.KEY_NOT_FOUND:
                    return new KeyStoreException(errorCode, "Key not found", serviceErrorMessage);
                case ResponseCode.VALUE_CORRUPTED:
                    return new KeyStoreException(errorCode, "Key blob corrupted",
                            serviceErrorMessage);
                case ResponseCode.KEY_PERMANENTLY_INVALIDATED:
                    return new KeyStoreException(errorCode, "Key permanently invalidated",
                            serviceErrorMessage);
                case ResponseCode.OUT_OF_KEYS:
                    // Getting a more specific RKP status requires the security level, which we
                    // don't have here. Higher layers of the stack can interpret this exception
                    // and add more flavor.
                    return new KeyStoreException(errorCode, serviceErrorMessage,
                            KeyStoreException.RKP_TEMPORARILY_UNAVAILABLE);
                default:
                    return new KeyStoreException(errorCode, String.valueOf(errorCode),
                            serviceErrorMessage);
            }
        } else {
            // Keymaster layer error
            switch (errorCode) {
                case KeymasterDefs.KM_ERROR_INVALID_AUTHORIZATION_TIMEOUT:
                    // The name of this parameter significantly differs between Keymaster and
                    // framework APIs. Use the framework wording to make life easier for developers.
                    return new KeyStoreException(errorCode,
                            "Invalid user authentication validity duration",
                            serviceErrorMessage);
                default:
                    return new KeyStoreException(errorCode,
                            KeymasterDefs.getErrorMessage(errorCode),
                            serviceErrorMessage);
            }
        }
    }

}
