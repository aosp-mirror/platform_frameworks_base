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

package android.hardware.devicestate;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.hardware.devicestate.DeviceStateManager.DeviceStateListener;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ServiceManager;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.annotations.VisibleForTesting.Visibility;

import java.util.ArrayList;
import java.util.concurrent.Executor;

/**
 * Provides communication with the device state system service on behalf of applications.
 *
 * @see DeviceStateManager
 * @hide
 */
@VisibleForTesting(visibility = Visibility.PACKAGE)
public final class DeviceStateManagerGlobal {
    private static DeviceStateManagerGlobal sInstance;

    /**
     * Returns an instance of {@link DeviceStateManagerGlobal}. May return {@code null} if a
     * connection with the device state service couldn't be established.
     */
    @Nullable
    static DeviceStateManagerGlobal getInstance() {
        synchronized (DeviceStateManagerGlobal.class) {
            if (sInstance == null) {
                IBinder b = ServiceManager.getService(Context.DEVICE_STATE_SERVICE);
                if (b != null) {
                    sInstance = new DeviceStateManagerGlobal(IDeviceStateManager
                            .Stub.asInterface(b));
                }
            }
            return sInstance;
        }
    }

    private final Object mLock = new Object();
    @NonNull
    private final IDeviceStateManager mDeviceStateManager;
    @Nullable
    private DeviceStateManagerCallback mCallback;

    @GuardedBy("mLock")
    private final ArrayList<DeviceStateListenerWrapper> mListeners = new ArrayList<>();
    @Nullable
    @GuardedBy("mLock")
    private Integer mLastReceivedState;

    @VisibleForTesting
    public DeviceStateManagerGlobal(@NonNull IDeviceStateManager deviceStateManager) {
        mDeviceStateManager = deviceStateManager;
    }

    /**
     * Registers a listener to receive notifications about changes in device state.
     *
     * @see DeviceStateManager#registerDeviceStateListener(DeviceStateListener, Executor)
     */
    @VisibleForTesting(visibility = Visibility.PACKAGE)
    public void registerDeviceStateListener(@NonNull DeviceStateListener listener,
            @NonNull Executor executor) {
        Integer stateToReport;
        DeviceStateListenerWrapper wrapper;
        synchronized (mLock) {
            registerCallbackIfNeededLocked();

            int index = findListenerLocked(listener);
            if (index != -1) {
                // This listener is already registered.
                return;
            }

            wrapper = new DeviceStateListenerWrapper(listener, executor);
            mListeners.add(wrapper);
            stateToReport = mLastReceivedState;
        }

        if (stateToReport != null) {
            // Notify the listener with the most recent device state from the server. If the state
            // to report is null this is likely the first listener added and we're still waiting
            // from the callback from the server.
            wrapper.notifyDeviceStateChanged(stateToReport);
        }
    }

    /**
     * Unregisters a listener previously registered with
     * {@link #registerDeviceStateListener(DeviceStateListener, Executor)}.
     *
     * @see DeviceStateManager#registerDeviceStateListener(DeviceStateListener, Executor)
     */
    @VisibleForTesting(visibility = Visibility.PACKAGE)
    public void unregisterDeviceStateListener(DeviceStateListener listener) {
        synchronized (mLock) {
            int indexToRemove = findListenerLocked(listener);
            if (indexToRemove != -1) {
                mListeners.remove(indexToRemove);
            }
        }
    }

    private void registerCallbackIfNeededLocked() {
        if (mCallback == null) {
            mCallback = new DeviceStateManagerCallback();
            try {
                mDeviceStateManager.registerCallback(mCallback);
            } catch (RemoteException ex) {
                throw ex.rethrowFromSystemServer();
            }
        }
    }

    private int findListenerLocked(DeviceStateListener listener) {
        for (int i = 0; i < mListeners.size(); i++) {
            if (mListeners.get(i).mDeviceStateListener.equals(listener)) {
                return i;
            }
        }
        return -1;
    }

    private void handleDeviceStateChanged(int newDeviceState) {
        ArrayList<DeviceStateListenerWrapper> listeners;
        synchronized (mLock) {
            mLastReceivedState = newDeviceState;
            listeners = new ArrayList<>(mListeners);
        }

        for (int i = 0; i < listeners.size(); i++) {
            listeners.get(i).notifyDeviceStateChanged(newDeviceState);
        }
    }

    private final class DeviceStateManagerCallback extends IDeviceStateManagerCallback.Stub {
        @Override
        public void onDeviceStateChanged(int deviceState) {
            handleDeviceStateChanged(deviceState);
        }
    }

    private static final class DeviceStateListenerWrapper {
        private final DeviceStateListener mDeviceStateListener;
        private final Executor mExecutor;

        DeviceStateListenerWrapper(DeviceStateListener listener, Executor executor) {
            mDeviceStateListener = listener;
            mExecutor = executor;
        }

        void notifyDeviceStateChanged(int newDeviceState) {
            mExecutor.execute(() -> mDeviceStateListener.onDeviceStateChanged(newDeviceState));
        }
    }
}
