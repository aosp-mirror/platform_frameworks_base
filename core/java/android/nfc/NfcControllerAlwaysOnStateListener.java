/*
 * Copyright 2021 The Android Open Source Project
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
import android.nfc.NfcAdapter.ControllerAlwaysOnStateCallback;
import android.os.Binder;
import android.os.RemoteException;
import android.util.Log;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executor;

/**
 * @hide
 */
public class NfcControllerAlwaysOnStateListener extends INfcControllerAlwaysOnStateCallback.Stub {
    private static final String TAG = "NfcControllerAlwaysOnStateListener";

    private final INfcAdapter mAdapter;

    private final Map<ControllerAlwaysOnStateCallback, Executor> mCallbackMap = new HashMap<>();

    private boolean mCurrentState = false;
    private boolean mIsRegistered = false;

    public NfcControllerAlwaysOnStateListener(@NonNull INfcAdapter adapter) {
        mAdapter = adapter;
    }

    /**
     * Register a {@link ControllerAlwaysOnStateCallback} with this
     * {@link NfcControllerAlwaysOnStateListener}
     *
     * @param executor an {@link Executor} to execute given callback
     * @param callback user implementation of the {@link ControllerAlwaysOnStateCallback}
     */
    public void register(@NonNull Executor executor,
            @NonNull ControllerAlwaysOnStateCallback callback) {
        synchronized (this) {
            if (mCallbackMap.containsKey(callback)) {
                return;
            }

            mCallbackMap.put(callback, executor);
            if (!mIsRegistered) {
                try {
                    mAdapter.registerControllerAlwaysOnStateCallback(this);
                    mIsRegistered = true;
                } catch (RemoteException e) {
                    Log.w(TAG, "Failed to register ControllerAlwaysOnStateListener");
                }
            }
        }
    }

    /**
     * Unregister the specified {@link ControllerAlwaysOnStateCallback}
     *
     * @param callback user implementation of the {@link ControllerAlwaysOnStateCallback}
     */
    public void unregister(@NonNull ControllerAlwaysOnStateCallback callback) {
        synchronized (this) {
            if (!mCallbackMap.containsKey(callback)) {
                return;
            }

            mCallbackMap.remove(callback);

            if (mCallbackMap.isEmpty() && mIsRegistered) {
                try {
                    mAdapter.unregisterControllerAlwaysOnStateCallback(this);
                } catch (RemoteException e) {
                    Log.w(TAG, "Failed to unregister ControllerAlwaysOnStateListener");
                }
                mIsRegistered = false;
            }
        }
    }

    private void sendCurrentState(@NonNull ControllerAlwaysOnStateCallback callback) {
        synchronized (this) {
            Executor executor = mCallbackMap.get(callback);

            final long identity = Binder.clearCallingIdentity();
            try {
                executor.execute(() -> callback.onStateChanged(
                        mCurrentState));
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }
    }

    @Override
    public void onControllerAlwaysOnStateChanged(boolean isEnabled) {
        synchronized (this) {
            mCurrentState = isEnabled;
            for (ControllerAlwaysOnStateCallback cb : mCallbackMap.keySet()) {
                sendCurrentState(cb);
            }
        }
    }
}

