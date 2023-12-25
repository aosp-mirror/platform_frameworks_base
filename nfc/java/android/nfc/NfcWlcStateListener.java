/*
 * Copyright 2023 The Android Open Source Project
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
import android.nfc.NfcAdapter.WlcStateListener;
import android.os.Binder;
import android.os.RemoteException;
import android.util.Log;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executor;

/**
 * @hide
 */
public class NfcWlcStateListener extends INfcWlcStateListener.Stub {
    private static final String TAG = NfcWlcStateListener.class.getSimpleName();

    private final INfcAdapter mAdapter;

    private final Map<WlcStateListener, Executor> mListenerMap = new HashMap<>();

    private WlcLDeviceInfo mCurrentState = null;
    private boolean mIsRegistered = false;

    public NfcWlcStateListener(@NonNull INfcAdapter adapter) {
        mAdapter = adapter;
    }

    /**
     * Register a {@link WlcStateListener} with this
     * {@link WlcStateListener}
     *
     * @param executor an {@link Executor} to execute given listener
     * @param listener user implementation of the {@link WlcStateListener}
     */
    public void register(@NonNull Executor executor, @NonNull WlcStateListener listener) {
        synchronized (this) {
            if (mListenerMap.containsKey(listener)) {
                return;
            }

            mListenerMap.put(listener, executor);

            if (!mIsRegistered) {
                try {
                    mAdapter.registerWlcStateListener(this);
                    mIsRegistered = true;
                } catch (RemoteException e) {
                    Log.w(TAG, "Failed to register");
                }
            }
        }
    }

    /**
     * Unregister the specified {@link WlcStateListener}
     *
     * @param listener user implementation of the {@link WlcStateListener}
     */
    public void unregister(@NonNull WlcStateListener listener) {
        synchronized (this) {
            if (!mListenerMap.containsKey(listener)) {
                return;
            }

            mListenerMap.remove(listener);

            if (mListenerMap.isEmpty() && mIsRegistered) {
                try {
                    mAdapter.unregisterWlcStateListener(this);
                } catch (RemoteException e) {
                    Log.w(TAG, "Failed to unregister");
                }
                mIsRegistered = false;
            }
        }
    }

    private void sendCurrentState(@NonNull WlcStateListener listener) {
        synchronized (this) {
            Executor executor = mListenerMap.get(listener);
            final long identity = Binder.clearCallingIdentity();
            try {
                if (Flags.enableNfcCharging()) {
                    executor.execute(() -> listener.onWlcStateChanged(
                            mCurrentState));
                }
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }
    }

    @Override
    public void onWlcStateChanged(@NonNull WlcLDeviceInfo wlcLDeviceInfo) {
        synchronized (this) {
            mCurrentState = wlcLDeviceInfo;

            for (WlcStateListener cb : mListenerMap.keySet()) {
                sendCurrentState(cb);
            }
        }
    }
}

