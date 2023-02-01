/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.server.security.rkp;

import android.os.CancellationSignal;
import android.os.OperationCanceledException;
import android.os.OutcomeReceiver;
import android.security.rkp.IGetKeyCallback;
import android.security.rkp.IRegistration;
import android.security.rkp.IStoreUpgradedKeyCallback;
import android.security.rkp.service.RegistrationProxy;
import android.security.rkp.service.RemotelyProvisionedKey;
import android.util.Log;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

/**
 * Implements android.security.rkp.IRegistration as a thin wrapper around the java code
 * exported by com.android.rkp.
 *
 * @hide
 */
final class RemoteProvisioningRegistration extends IRegistration.Stub {
    static final String TAG = RemoteProvisioningService.TAG;
    private final ConcurrentHashMap<IGetKeyCallback, CancellationSignal> mGetKeyOperations =
            new ConcurrentHashMap<>();
    private final Set<IStoreUpgradedKeyCallback> mStoreUpgradedKeyOperations =
            ConcurrentHashMap.newKeySet();
    private final RegistrationProxy mRegistration;
    private final Executor mExecutor;

    private class GetKeyReceiver implements OutcomeReceiver<RemotelyProvisionedKey, Exception> {
        IGetKeyCallback mCallback;
        GetKeyReceiver(IGetKeyCallback callback) {
            mCallback = callback;
        }

        @Override
        public void onResult(RemotelyProvisionedKey result) {
            mGetKeyOperations.remove(mCallback);
            Log.i(TAG, "Successfully fetched key for client " + mCallback.hashCode());
            android.security.rkp.RemotelyProvisionedKey parcelable =
                    new android.security.rkp.RemotelyProvisionedKey();
            parcelable.keyBlob = result.getKeyBlob();
            parcelable.encodedCertChain = result.getEncodedCertChain();
            wrapCallback(() -> mCallback.onSuccess(parcelable));
        }

        @Override
        public void onError(Exception e) {
            mGetKeyOperations.remove(mCallback);
            if (e instanceof OperationCanceledException) {
                Log.i(TAG, "Operation cancelled for client " + mCallback.hashCode());
                wrapCallback(mCallback::onCancel);
            } else {
                Log.e(TAG, "Error fetching key for client " + mCallback.hashCode(), e);
                wrapCallback(() -> mCallback.onError(e.getMessage()));
            }
        }
    }

    RemoteProvisioningRegistration(RegistrationProxy registration, Executor executor) {
        mRegistration = registration;
        mExecutor = executor;
    }

    @Override
    public void getKey(int keyId, IGetKeyCallback callback) {
        CancellationSignal cancellationSignal = new CancellationSignal();
        if (mGetKeyOperations.putIfAbsent(callback, cancellationSignal) != null) {
            Log.e(TAG, "Client can only request one call at a time " + callback.hashCode());
            throw new IllegalArgumentException(
                    "Callback is already associated with an existing operation: "
                            + callback.hashCode());
        }

        try {
            Log.i(TAG, "Fetching key " + keyId + " for client " + callback.hashCode());
            mRegistration.getKeyAsync(keyId, cancellationSignal, mExecutor,
                    new GetKeyReceiver(callback));
        } catch (Exception e) {
            Log.e(TAG, "getKeyAsync threw an exception for client " + callback.hashCode(), e);
            mGetKeyOperations.remove(callback);
            wrapCallback(() -> callback.onError(e.getMessage()));
        }
    }

    @Override
    public void cancelGetKey(IGetKeyCallback callback) {
        CancellationSignal cancellationSignal = mGetKeyOperations.remove(callback);
        if (cancellationSignal == null) {
            throw new IllegalArgumentException(
                    "Invalid client in cancelGetKey: " + callback.hashCode());
        }

        Log.i(TAG, "Requesting cancellation for client " + callback.hashCode());
        cancellationSignal.cancel();
    }

    @Override
    public void storeUpgradedKeyAsync(byte[] oldKeyBlob, byte[] newKeyBlob,
            IStoreUpgradedKeyCallback callback) {
        if (!mStoreUpgradedKeyOperations.add(callback)) {
            throw new IllegalArgumentException(
                    "Callback is already associated with an existing operation: "
                            + callback.hashCode());
        }

        try {
            mRegistration.storeUpgradedKeyAsync(oldKeyBlob, newKeyBlob, mExecutor,
                    new OutcomeReceiver<>() {
                        @Override
                        public void onResult(Void result) {
                            mStoreUpgradedKeyOperations.remove(callback);
                            wrapCallback(callback::onSuccess);
                        }

                        @Override
                        public void onError(Exception e) {
                            mStoreUpgradedKeyOperations.remove(callback);
                            wrapCallback(() -> callback.onError(e.getMessage()));
                        }
                    });
        } catch (Exception e) {
            Log.e(TAG, "storeUpgradedKeyAsync threw an exception for client "
                    + callback.hashCode(), e);
            mStoreUpgradedKeyOperations.remove(callback);
            wrapCallback(() -> callback.onError(e.getMessage()));
        }
    }

    interface CallbackRunner {
        void run() throws Exception;
    }

    private void wrapCallback(CallbackRunner callback) {
        // Exceptions resulting from notifications to IGetKeyCallback objects can only be logged,
        // since getKey execution is asynchronous, and there's no way for an exception to be
        // properly handled up the stack.
        try {
            callback.run();
        } catch (Exception e) {
            Log.e(TAG, "Error invoking callback on client binder", e);
        }
    }
}
