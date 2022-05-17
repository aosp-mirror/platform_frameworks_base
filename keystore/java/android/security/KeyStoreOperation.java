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
import android.hardware.security.keymint.KeyParameter;
import android.os.Binder;
import android.os.RemoteException;
import android.os.ServiceSpecificException;
import android.security.keymaster.KeymasterDefs;
import android.system.keystore2.IKeystoreOperation;
import android.system.keystore2.ResponseCode;
import android.util.Log;

/**
 * @hide
 */
public class KeyStoreOperation {
    static final String TAG = "KeyStoreOperation";
    private final IKeystoreOperation mOperation;
    private final Long mChallenge;
    private final KeyParameter[] mParameters;

    public KeyStoreOperation(
            @NonNull IKeystoreOperation operation,
            Long challenge,
            KeyParameter[] parameters
    ) {
        Binder.allowBlocking(operation.asBinder());
        this.mOperation = operation;
        this.mChallenge = challenge;
        this.mParameters = parameters;
    }

    /**
     * Gets the challenge associated with this operation.
     * @return null if the operation does not required authorization. A 64bit operation
     *         challenge otherwise.
     */
    public Long getChallenge() {
        return mChallenge;
    }

    /**
     * Gets the parameters associated with this operation.
     * @return
     */
    public KeyParameter[] getParameters() {
        return mParameters;
    }

    private <R> R handleExceptions(@NonNull CheckedRemoteRequest<R> request)
            throws KeyStoreException {
        try {
            return request.execute();
        } catch (ServiceSpecificException e) {
            switch(e.errorCode) {
                case ResponseCode.OPERATION_BUSY: {
                    throw new IllegalThreadStateException(
                            "Cannot update the same operation concurrently."
                    );
                }
                default:
                    throw KeyStore2.getKeyStoreException(e.errorCode, e.getMessage());
            }
        } catch (RemoteException e) {
            // Log exception and report invalid operation handle.
            // This should prompt the caller drop the reference to this operation and retry.
            Log.e(
                    TAG,
                    "Remote exception while advancing a KeyStoreOperation.",
                    e
            );
            throw new KeyStoreException(KeymasterDefs.KM_ERROR_INVALID_OPERATION_HANDLE, "",
                    e.getMessage());
        }
    }

    /**
     * Updates the Keystore operation represented by this object with more associated data.
     * @see IKeystoreOperation#updateAad(byte[]) for more details.
     * @param input
     * @throws KeyStoreException
     */
    public void updateAad(@NonNull byte[] input) throws KeyStoreException {
        handleExceptions(() -> {
            mOperation.updateAad(input);
            return 0;
        });
    }

    /**
     * Updates the Keystore operation represented by this object.
     * @see IKeystoreOperation#update(byte[]) for more details.
     * @param input
     * @return
     * @throws KeyStoreException
     * @hide
     */
    public byte[] update(@NonNull byte[] input) throws KeyStoreException {
        return handleExceptions(() -> mOperation.update(input));
    }

    /**
     * Finalizes the Keystore operation represented by this object.
     * @see IKeystoreOperation#finish(byte[], byte[]) for more details.
     * @param input
     * @param signature
     * @return
     * @throws KeyStoreException
     * @hide
     */
    public byte[] finish(byte[] input, byte[] signature) throws KeyStoreException {
        return handleExceptions(() -> mOperation.finish(input, signature));
    }

    /**
     * Aborts the Keystore operation represented by this object.
     * @see IKeystoreOperation#abort() for more details.
     * @throws KeyStoreException
     * @hide
     */
    public void abort() throws KeyStoreException {
        handleExceptions(() -> {
            mOperation.abort();
            return 0;
        });
    }
}
