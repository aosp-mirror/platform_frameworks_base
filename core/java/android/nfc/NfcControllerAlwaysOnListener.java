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
import android.nfc.NfcAdapter.ControllerAlwaysOnListener;
import android.os.Binder;
import android.os.RemoteException;
import android.util.Log;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executor;

/**
 * @hide
 */
public class NfcControllerAlwaysOnListener extends INfcControllerAlwaysOnListener.Stub {
    private static final String TAG = NfcControllerAlwaysOnListener.class.getSimpleName();

    private final INfcAdapter mAdapter;

    private final Map<ControllerAlwaysOnListener, Executor> mListenerMap = new HashMap<>();

    private boolean mCurrentState = false;
    private boolean mIsRegistered = false;

    public NfcControllerAlwaysOnListener(@NonNull INfcAdapter adapter) {
        mAdapter = adapter;
    }

    /**
     * Register a {@link ControllerAlwaysOnListener} with this
     * {@link NfcControllerAlwaysOnListener}
     *
     * @param executor an {@link Executor} to execute given listener
     * @param listener user implementation of the {@link ControllerAlwaysOnListener}
     */
    public void register(@NonNull Executor executor,
            @NonNull ControllerAlwaysOnListener listener) {
        try {
            if (!mAdapter.isControllerAlwaysOnSupported()) {
                return;
            }
        } catch (RemoteException e) {
            Log.w(TAG, "Failed to register");
            return;
        }
        synchronized (this) {
            if (mListenerMap.containsKey(listener)) {
                return;
            }

            mListenerMap.put(listener, executor);
            if (!mIsRegistered) {
                try {
                    mAdapter.registerControllerAlwaysOnListener(this);
                    mIsRegistered = true;
                } catch (RemoteException e) {
                    Log.w(TAG, "Failed to register");
                }
            }
        }
    }

    /**
     * Unregister the specified {@link ControllerAlwaysOnListener}
     *
     * @param listener user implementation of the {@link ControllerAlwaysOnListener}
     */
    public void unregister(@NonNull ControllerAlwaysOnListener listener) {
        try {
            if (!mAdapter.isControllerAlwaysOnSupported()) {
                return;
            }
        } catch (RemoteException e) {
            Log.w(TAG, "Failed to unregister");
            return;
        }
        synchronized (this) {
            if (!mListenerMap.containsKey(listener)) {
                return;
            }

            mListenerMap.remove(listener);

            if (mListenerMap.isEmpty() && mIsRegistered) {
                try {
                    mAdapter.unregisterControllerAlwaysOnListener(this);
                } catch (RemoteException e) {
                    Log.w(TAG, "Failed to unregister");
                }
                mIsRegistered = false;
            }
        }
    }

    private void sendCurrentState(@NonNull ControllerAlwaysOnListener listener) {
        synchronized (this) {
            Executor executor = mListenerMap.get(listener);

            final long identity = Binder.clearCallingIdentity();
            try {
                executor.execute(() -> listener.onControllerAlwaysOnChanged(
                        mCurrentState));
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }
    }

    @Override
    public void onControllerAlwaysOnChanged(boolean isEnabled) {
        synchronized (this) {
            mCurrentState = isEnabled;
            for (ControllerAlwaysOnListener cb : mListenerMap.keySet()) {
                sendCurrentState(cb);
            }
        }
    }
}

