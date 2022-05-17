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
import android.app.compat.CompatChanges;
import android.hardware.security.keymint.KeyParameter;
import android.os.Binder;
import android.os.RemoteException;
import android.os.ServiceSpecificException;
import android.security.keystore.BackendBusyException;
import android.security.keystore.KeyStoreConnectException;
import android.system.keystore2.AuthenticatorSpec;
import android.system.keystore2.CreateOperationResponse;
import android.system.keystore2.IKeystoreSecurityLevel;
import android.system.keystore2.KeyDescriptor;
import android.system.keystore2.KeyMetadata;
import android.system.keystore2.ResponseCode;
import android.util.Log;

import java.util.Calendar;
import java.util.Collection;

/**
 * This is a shim around the security level specific interface of Keystore 2.0. Services with
 * this interface are instantiated per KeyMint backend, each having there own security level.
 * Thus this object representation of a security level.
 * @hide
 */
public class KeyStoreSecurityLevel {
    private static final String TAG = "KeyStoreSecurityLevel";
    private final IKeystoreSecurityLevel mSecurityLevel;

    public KeyStoreSecurityLevel(IKeystoreSecurityLevel securityLevel) {
        Binder.allowBlocking(securityLevel.asBinder());
        this.mSecurityLevel = securityLevel;
    }

    private <R> R handleExceptions(CheckedRemoteRequest<R> request) throws KeyStoreException {
        try {
            return request.execute();
        } catch (ServiceSpecificException e) {
            throw KeyStore2.getKeyStoreException(e.errorCode, e.getMessage());
        } catch (RemoteException e) {
            // Log exception and report invalid operation handle.
            // This should prompt the caller drop the reference to this operation and retry.
            Log.e(TAG, "Could not connect to Keystore.", e);
            throw new KeyStoreException(ResponseCode.SYSTEM_ERROR, "", e.getMessage());
        }
    }

    /**
     * Creates a new keystore operation.
     * @see IKeystoreSecurityLevel#createOperation(KeyDescriptor, KeyParameter[], boolean) for more
     * details.
     * @param keyDescriptor
     * @param args
     * @return
     * @throws KeyStoreException
     * @hide
     */
    public KeyStoreOperation createOperation(@NonNull KeyDescriptor keyDescriptor,
            Collection<KeyParameter> args) throws KeyStoreException {
        while (true) {
            try {
                CreateOperationResponse createOperationResponse =
                        mSecurityLevel.createOperation(
                                keyDescriptor,
                                args.toArray(new KeyParameter[args.size()]),
                                false /* forced */
                        );
                Long challenge = null;
                if (createOperationResponse.operationChallenge != null) {
                    challenge = createOperationResponse.operationChallenge.challenge;
                }
                KeyParameter[] parameters = null;
                if (createOperationResponse.parameters != null) {
                    parameters = createOperationResponse.parameters.keyParameter;
                }
                return new KeyStoreOperation(
                        createOperationResponse.iOperation,
                        challenge,
                        parameters);
            } catch (ServiceSpecificException e) {
                switch (e.errorCode) {
                    case ResponseCode.BACKEND_BUSY: {
                        long backOffHint = (long) (Math.random() * 80 + 20);
                        if (CompatChanges.isChangeEnabled(
                                KeyStore2.KEYSTORE_OPERATION_CREATION_MAY_FAIL)) {
                            // Starting with Android S we inform the caller about the
                            // backend being busy.
                            throw new BackendBusyException(backOffHint);
                        } else {
                            // Before Android S operation creation must always succeed. So we
                            // just have to retry. We do so with a randomized back-off between
                            // 20 and 100ms.
                            // It is a little awkward that we cannot break out of this loop
                            // by interrupting this thread. But that is the expected behavior.
                            // There is some comfort in the fact that interrupting a thread
                            // also does not unblock a thread waiting for a binder transaction.
                            interruptedPreservingSleep(backOffHint);
                        }
                        break;
                    }
                    default:
                        throw KeyStore2.getKeyStoreException(e.errorCode, e.getMessage());
                }
            } catch (RemoteException e) {
                Log.w(TAG, "Cannot connect to keystore", e);
                throw new KeyStoreConnectException();
            }
        }
    }

    /**
     * Generates a new key in Keystore.
     * @see IKeystoreSecurityLevel#generateKey(KeyDescriptor, KeyDescriptor, KeyParameter[], int,
     * byte[]) for more details.
     * @param descriptor
     * @param attestationKey
     * @param args
     * @param flags
     * @param entropy
     * @return
     * @throws KeyStoreException
     * @hide
     */
    public KeyMetadata generateKey(@NonNull KeyDescriptor descriptor, KeyDescriptor attestationKey,
            Collection<KeyParameter> args, int flags, byte[] entropy)
            throws KeyStoreException {
        return handleExceptions(() -> mSecurityLevel.generateKey(
                descriptor, attestationKey, args.toArray(new KeyParameter[args.size()]),
                flags, entropy));
    }

    /**
     * Imports a key into Keystore.
     * @see IKeystoreSecurityLevel#importKey(KeyDescriptor, KeyDescriptor, KeyParameter[], int,
     * byte[]) for more details.
     * @param descriptor
     * @param attestationKey
     * @param args
     * @param flags
     * @param keyData
     * @return
     * @throws KeyStoreException
     * @hide
     */
    public KeyMetadata importKey(KeyDescriptor descriptor, KeyDescriptor attestationKey,
            Collection<KeyParameter> args, int flags, byte[] keyData)
            throws KeyStoreException {
        return handleExceptions(() -> mSecurityLevel.importKey(descriptor, attestationKey,
                args.toArray(new KeyParameter[args.size()]), flags, keyData));
    }

    /**
     * Imports a wrapped key into Keystore.
     * @see IKeystoreSecurityLevel#importWrappedKey(KeyDescriptor, KeyDescriptor, byte[],
     * KeyParameter[], AuthenticatorSpec[]) for more details.
     * @param wrappedKeyDescriptor
     * @param wrappingKeyDescriptor
     * @param wrappedKey
     * @param maskingKey
     * @param args
     * @param authenticatorSpecs
     * @return
     * @throws KeyStoreException
     * @hide
     */
    public KeyMetadata importWrappedKey(@NonNull KeyDescriptor wrappedKeyDescriptor,
            @NonNull KeyDescriptor wrappingKeyDescriptor,
            @NonNull byte[] wrappedKey, byte[] maskingKey,
            Collection<KeyParameter> args, @NonNull AuthenticatorSpec[] authenticatorSpecs)
            throws KeyStoreException {
        KeyDescriptor keyDescriptor = new KeyDescriptor();
        keyDescriptor.alias = wrappedKeyDescriptor.alias;
        keyDescriptor.nspace = wrappedKeyDescriptor.nspace;
        keyDescriptor.blob = wrappedKey;
        keyDescriptor.domain = wrappedKeyDescriptor.domain;

        return handleExceptions(() -> mSecurityLevel.importWrappedKey(keyDescriptor,
                wrappingKeyDescriptor, maskingKey,
                args.toArray(new KeyParameter[args.size()]), authenticatorSpecs));
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
}
