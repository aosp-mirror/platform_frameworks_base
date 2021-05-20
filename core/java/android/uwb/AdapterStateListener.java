/*
 * Copyright 2020 The Android Open Source Project
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

package android.uwb;

import android.annotation.NonNull;
import android.os.Binder;
import android.os.RemoteException;
import android.util.Log;
import android.uwb.UwbManager.AdapterStateCallback;
import android.uwb.UwbManager.AdapterStateCallback.State;
import android.uwb.UwbManager.AdapterStateCallback.StateChangedReason;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executor;

/**
 * @hide
 */
public class AdapterStateListener extends IUwbAdapterStateCallbacks.Stub {
    private static final String TAG = "Uwb.StateListener";

    private final IUwbAdapter mAdapter;
    private boolean mIsRegistered = false;

    private final Map<AdapterStateCallback, Executor> mCallbackMap = new HashMap<>();

    @StateChangedReason
    private int mAdapterStateChangeReason = AdapterStateCallback.STATE_CHANGED_REASON_ERROR_UNKNOWN;
    @State
    private int mAdapterState = AdapterStateCallback.STATE_DISABLED;

    public AdapterStateListener(@NonNull IUwbAdapter adapter) {
        mAdapter = adapter;
    }

    /**
     * Register an {@link AdapterStateCallback} with this {@link AdapterStateListener}
     *
     * @param executor an {@link Executor} to execute given callback
     * @param callback user implementation of the {@link AdapterStateCallback}
     */
    public void register(@NonNull Executor executor, @NonNull AdapterStateCallback callback) {
        synchronized (this) {
            if (mCallbackMap.containsKey(callback)) {
                return;
            }

            mCallbackMap.put(callback, executor);

            if (!mIsRegistered) {
                try {
                    mAdapter.registerAdapterStateCallbacks(this);
                    mIsRegistered = true;
                } catch (RemoteException e) {
                    Log.w(TAG, "Failed to register adapter state callback");
                    throw e.rethrowFromSystemServer();
                }
            } else {
                sendCurrentState(callback);
            }
        }
    }

    /**
     * Unregister the specified {@link AdapterStateCallback}
     *
     * @param callback user implementation of the {@link AdapterStateCallback}
     */
    public void unregister(@NonNull AdapterStateCallback callback) {
        synchronized (this) {
            if (!mCallbackMap.containsKey(callback)) {
                return;
            }

            mCallbackMap.remove(callback);

            if (mCallbackMap.isEmpty() && mIsRegistered) {
                try {
                    mAdapter.unregisterAdapterStateCallbacks(this);
                } catch (RemoteException e) {
                    Log.w(TAG, "Failed to unregister AdapterStateCallback with service");
                    throw e.rethrowFromSystemServer();
                }
                mIsRegistered = false;
            }
        }
    }

    /**
     * Sets the adapter enabled state
     *
     * @param isEnabled value of new adapter state
     */
    public void setEnabled(boolean isEnabled) {
        synchronized (this) {
            if (!mIsRegistered) {
                return;
            } else {
                try {
                    mAdapter.setEnabled(isEnabled);
                } catch (RemoteException e) {
                    Log.w(TAG, "Failed to set adapter state");
                    throw e.rethrowFromSystemServer();
                }
            }
        }
    }

    /**
     * Gets the adapter enabled state
     *
     * @return integer representing adapter enabled state
     */
    public int getAdapterState() {
        synchronized (this) {
            try {
                return mAdapter.getAdapterState();
            } catch (RemoteException e) {
                Log.w(TAG, "Failed to get adapter state");
                throw e.rethrowFromSystemServer();
            }
        }
    }

    private void sendCurrentState(@NonNull AdapterStateCallback callback) {
        synchronized (this) {
            Executor executor = mCallbackMap.get(callback);

            final long identity = Binder.clearCallingIdentity();
            try {
                executor.execute(() -> callback.onStateChanged(
                        mAdapterState, mAdapterStateChangeReason));
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }
    }

    @Override
    public void onAdapterStateChanged(int state, int reason) {
        synchronized (this) {
            @StateChangedReason int localReason =
                    convertToStateChangedReason(reason);
            @State int localState = convertToState(state);
            mAdapterStateChangeReason = localReason;
            mAdapterState = localState;
            for (AdapterStateCallback cb : mCallbackMap.keySet()) {
                sendCurrentState(cb);
            }
        }
    }

    private static @StateChangedReason int convertToStateChangedReason(
            @StateChangeReason int reason) {
        switch (reason) {
            case StateChangeReason.ALL_SESSIONS_CLOSED:
                return AdapterStateCallback.STATE_CHANGED_REASON_ALL_SESSIONS_CLOSED;

            case StateChangeReason.SESSION_STARTED:
                return AdapterStateCallback.STATE_CHANGED_REASON_SESSION_STARTED;

            case StateChangeReason.SYSTEM_POLICY:
                return AdapterStateCallback.STATE_CHANGED_REASON_SYSTEM_POLICY;

            case StateChangeReason.SYSTEM_BOOT:
                return AdapterStateCallback.STATE_CHANGED_REASON_SYSTEM_BOOT;

            case StateChangeReason.UNKNOWN:
            default:
                return AdapterStateCallback.STATE_CHANGED_REASON_ERROR_UNKNOWN;
        }
    }

    private static @State int convertToState(@AdapterState int state) {
        switch (state) {
            case AdapterState.STATE_ENABLED_INACTIVE:
                return AdapterStateCallback.STATE_ENABLED_INACTIVE;

            case AdapterState.STATE_ENABLED_ACTIVE:
                return AdapterStateCallback.STATE_ENABLED_ACTIVE;

            case AdapterState.STATE_DISABLED:
            default:
                return AdapterStateCallback.STATE_DISABLED;
        }
    }
}
