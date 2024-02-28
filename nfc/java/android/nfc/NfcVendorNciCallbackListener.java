/*
 * Copyright (C) 2024 The Android Open Source Project
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
package android.nfc;

import android.annotation.NonNull;
import android.nfc.NfcAdapter.NfcVendorNciCallback;
import android.os.Binder;
import android.os.RemoteException;
import android.util.Log;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executor;

/**
 * @hide
 */
public final class NfcVendorNciCallbackListener extends INfcVendorNciCallback.Stub {
    private static final String TAG = "Nfc.NfcVendorNciCallbacks";
    private final INfcAdapter mAdapter;
    private boolean mIsRegistered = false;
    private final Map<NfcVendorNciCallback, Executor> mCallbackMap = new HashMap<>();

    public NfcVendorNciCallbackListener(@NonNull INfcAdapter adapter) {
        mAdapter = adapter;
    }

    public void register(@NonNull Executor executor, @NonNull NfcVendorNciCallback callback) {
        synchronized (this) {
            if (mCallbackMap.containsKey(callback)) {
                return;
            }
            mCallbackMap.put(callback, executor);
            if (!mIsRegistered) {
                try {
                    mAdapter.registerVendorExtensionCallback(this);
                    mIsRegistered = true;
                } catch (RemoteException e) {
                    Log.w(TAG, "Failed to register adapter state callback");
                    mCallbackMap.remove(callback);
                    throw e.rethrowFromSystemServer();
                }
            }
        }
    }

    public void unregister(@NonNull NfcVendorNciCallback callback) {
        synchronized (this) {
            if (!mCallbackMap.containsKey(callback) || !mIsRegistered) {
                return;
            }
            if (mCallbackMap.size() == 1) {
                try {
                    mAdapter.unregisterVendorExtensionCallback(this);
                    mIsRegistered = false;
                    mCallbackMap.remove(callback);
                } catch (RemoteException e) {
                    Log.w(TAG, "Failed to unregister AdapterStateCallback with service");
                    throw e.rethrowFromSystemServer();
                }
            } else {
                mCallbackMap.remove(callback);
            }
        }
    }

    @Override
    public void onVendorResponseReceived(int gid, int oid, @NonNull byte[] payload)
            throws RemoteException {
        synchronized (this) {
            final long identity = Binder.clearCallingIdentity();
            try {
                for (NfcVendorNciCallback callback : mCallbackMap.keySet()) {
                    Executor executor = mCallbackMap.get(callback);
                    executor.execute(() -> callback.onVendorNciResponse(gid, oid, payload));
                }
            } catch (RuntimeException ex) {
                throw ex;
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }
    }

    @Override
    public void onVendorNotificationReceived(int gid, int oid, @NonNull byte[] payload)
            throws RemoteException {
        synchronized (this) {
            final long identity = Binder.clearCallingIdentity();
            try {
                for (NfcVendorNciCallback callback : mCallbackMap.keySet()) {
                    Executor executor = mCallbackMap.get(callback);
                    executor.execute(() -> callback.onVendorNciNotification(gid, oid, payload));
                }
            } catch (RuntimeException ex) {
                throw ex;
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }
    }
}
