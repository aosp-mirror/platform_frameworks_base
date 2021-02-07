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
import android.os.Binder;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.ArrayMap;

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
    @GuardedBy("mLock")
    private final ArrayMap<IBinder, DeviceStateRequestWrapper> mRequests = new ArrayMap<>();

    @Nullable
    @GuardedBy("mLock")
    private Integer mLastReceivedState;

    @VisibleForTesting
    public DeviceStateManagerGlobal(@NonNull IDeviceStateManager deviceStateManager) {
        mDeviceStateManager = deviceStateManager;
    }

    /**
     * Returns the set of supported device states.
     *
     * @see DeviceStateManager#getSupportedStates()
     */
    public int[] getSupportedStates() {
        try {
            return mDeviceStateManager.getSupportedDeviceStates();
        } catch (RemoteException ex) {
            throw ex.rethrowFromSystemServer();
        }
    }

    /**
     * Submits a {@link DeviceStateRequest request} to modify the device state.
     *
     * @see DeviceStateManager#requestState(DeviceStateRequest,
     * Executor, DeviceStateRequest.Callback)
     * @see DeviceStateRequest
     */
    public void requestState(@NonNull DeviceStateRequest request,
            @Nullable DeviceStateRequest.Callback callback, @Nullable Executor executor) {
        if (callback == null && executor != null) {
            throw new IllegalArgumentException("Callback must be supplied with executor.");
        } else if (executor == null && callback != null) {
            throw new IllegalArgumentException("Executor must be supplied with callback.");
        }

        synchronized (mLock) {
            registerCallbackIfNeededLocked();

            if (findRequestTokenLocked(request) != null) {
                // This request has already been submitted.
                return;
            }

            // Add the request wrapper to the mRequests array before requesting the state as the
            // callback could be triggered immediately if the mDeviceStateManager IBinder is in the
            // same process as this instance.
            IBinder token = new Binder();
            mRequests.put(token, new DeviceStateRequestWrapper(request, callback, executor));

            try {
                mDeviceStateManager.requestState(token, request.getState(), request.getFlags());
            } catch (RemoteException ex) {
                mRequests.remove(token);
                throw ex.rethrowFromSystemServer();
            }
        }
    }

    /**
     * Cancels a {@link DeviceStateRequest request} previously submitted with a call to
     * {@link #requestState(DeviceStateRequest, DeviceStateRequest.Callback, Executor)}.
     *
     * @see DeviceStateManager#cancelRequest(DeviceStateRequest)
     */
    public void cancelRequest(@NonNull DeviceStateRequest request) {
        synchronized (mLock) {
            registerCallbackIfNeededLocked();

            final IBinder token = findRequestTokenLocked(request);
            if (token == null) {
                // This request has not been submitted.
                return;
            }

            try {
                mDeviceStateManager.cancelRequest(token);
            } catch (RemoteException ex) {
                throw ex.rethrowFromSystemServer();
            }
        }
    }

    /**
     * Registers a listener to receive notifications about changes in device state.
     *
     * @see DeviceStateManager#addDeviceStateListener(Executor, DeviceStateListener)
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
     * @see DeviceStateManager#addDeviceStateListener(Executor, DeviceStateListener)
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

    @Nullable
    private IBinder findRequestTokenLocked(@NonNull DeviceStateRequest request) {
        for (int i = 0; i < mRequests.size(); i++) {
            if (mRequests.valueAt(i).mRequest.equals(request)) {
                return mRequests.keyAt(i);
            }
        }
        return null;
    }

    /** Handles a call from the server that the device state has changed. */
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

    /**
     * Handles a call from the server that a request for the supplied {@code token} has become
     * active.
     */
    private void handleRequestActive(IBinder token) {
        DeviceStateRequestWrapper request;
        synchronized (mLock) {
            request = mRequests.get(token);
        }
        if (request != null) {
            request.notifyRequestActive();
        }
    }

    /**
     * Handles a call from the server that a request for the supplied {@code token} has become
     * suspended.
     */
    private void handleRequestSuspended(IBinder token) {
        DeviceStateRequestWrapper request;
        synchronized (mLock) {
            request = mRequests.get(token);
        }
        if (request != null) {
            request.notifyRequestSuspended();
        }
    }

    /**
     * Handles a call from the server that a request for the supplied {@code token} has become
     * canceled.
     */
    private void handleRequestCanceled(IBinder token) {
        DeviceStateRequestWrapper request;
        synchronized (mLock) {
            request = mRequests.remove(token);
        }
        if (request != null) {
            request.notifyRequestCanceled();
        }
    }

    private final class DeviceStateManagerCallback extends IDeviceStateManagerCallback.Stub {
        @Override
        public void onDeviceStateChanged(int deviceState) {
            handleDeviceStateChanged(deviceState);
        }

        @Override
        public void onRequestActive(IBinder token) {
            handleRequestActive(token);
        }

        @Override
        public void onRequestSuspended(IBinder token) {
            handleRequestSuspended(token);
        }

        @Override
        public void onRequestCanceled(IBinder token) {
            handleRequestCanceled(token);
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

    private static final class DeviceStateRequestWrapper {
        private final DeviceStateRequest mRequest;
        @Nullable
        private final DeviceStateRequest.Callback mCallback;
        @Nullable
        private final Executor mExecutor;

        DeviceStateRequestWrapper(@NonNull DeviceStateRequest request,
                @Nullable DeviceStateRequest.Callback callback, @Nullable Executor executor) {
            mRequest = request;
            mCallback = callback;
            mExecutor = executor;
        }

        void notifyRequestActive() {
            if (mCallback == null) {
                return;
            }

            mExecutor.execute(() -> mCallback.onRequestActivated(mRequest));
        }

        void notifyRequestSuspended() {
            if (mCallback == null) {
                return;
            }

            mExecutor.execute(() -> mCallback.onRequestSuspended(mRequest));
        }

        void notifyRequestCanceled() {
            if (mCallback == null) {
                return;
            }

            mExecutor.execute(() -> mCallback.onRequestSuspended(mRequest));
        }
    }
}
