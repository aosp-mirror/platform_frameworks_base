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
import android.annotation.RequiresPermission;
import android.content.Context;
import android.hardware.devicestate.DeviceStateManager.DeviceStateCallback;
import android.os.Binder;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.ArrayMap;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.annotations.VisibleForTesting.Visibility;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.Executor;

/**
 * Provides communication with the device state system service on behalf of applications.
 *
 * @see DeviceStateManager
 *
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
    private final ArrayList<DeviceStateCallbackWrapper> mCallbacks = new ArrayList<>();
    @GuardedBy("mLock")
    private final ArrayMap<IBinder, DeviceStateRequestWrapper> mRequests = new ArrayMap<>();

    @Nullable
    @GuardedBy("mLock")
    private DeviceStateInfo mLastReceivedInfo;

    @VisibleForTesting
    public DeviceStateManagerGlobal(@NonNull IDeviceStateManager deviceStateManager) {
        mDeviceStateManager = deviceStateManager;
        registerCallbackIfNeededLocked();
    }

    /**
     * Returns the set of supported device states.
     *
     * @see DeviceStateManager#getSupportedStates()
     */
    public int[] getSupportedStates() {
        synchronized (mLock) {
            final DeviceStateInfo currentInfo;
            if (mLastReceivedInfo != null) {
                // If we have mLastReceivedInfo a callback is registered for this instance and it
                // is receiving the most recent info from the server. Use that info here.
                currentInfo = mLastReceivedInfo;
            } else {
                // If mLastReceivedInfo is null there is no registered callback so we manually
                // fetch the current info.
                try {
                    currentInfo = mDeviceStateManager.getDeviceStateInfo();
                } catch (RemoteException ex) {
                    throw ex.rethrowFromSystemServer();
                }
            }

            return Arrays.copyOf(currentInfo.supportedStates, currentInfo.supportedStates.length);
        }
    }

    /**
     * Submits a {@link DeviceStateRequest request} to modify the device state.
     *
     * @see DeviceStateManager#requestState(DeviceStateRequest, Executor,
     * DeviceStateRequest.Callback)
     * @see DeviceStateRequest
     */
    @RequiresPermission(value = android.Manifest.permission.CONTROL_DEVICE_STATE,
            conditional = true)
    public void requestState(@NonNull DeviceStateRequest request,
            @Nullable Executor executor, @Nullable DeviceStateRequest.Callback callback) {
        DeviceStateRequestWrapper requestWrapper = new DeviceStateRequestWrapper(request, callback,
                executor);
        synchronized (mLock) {
            if (findRequestTokenLocked(request) != null) {
                // This request has already been submitted.
                return;
            }
            // Add the request wrapper to the mRequests array before requesting the state as the
            // callback could be triggered immediately if the mDeviceStateManager IBinder is in the
            // same process as this instance.
            IBinder token = new Binder();
            mRequests.put(token, requestWrapper);

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
     * {@link #requestState(DeviceStateRequest, Executor, DeviceStateRequest.Callback)}.
     *
     * @see DeviceStateManager#cancelStateRequest
     */
    @RequiresPermission(value = android.Manifest.permission.CONTROL_DEVICE_STATE,
            conditional = true)
    public void cancelStateRequest() {
        synchronized (mLock) {
            try {
                mDeviceStateManager.cancelStateRequest();
            } catch (RemoteException ex) {
                throw ex.rethrowFromSystemServer();
            }
        }
    }

    /**
     * Submits a {@link DeviceStateRequest request} to modify the base state of the device.
     *
     * @see DeviceStateManager#requestBaseStateOverride(DeviceStateRequest, Executor,
     * DeviceStateRequest.Callback)
     * @see DeviceStateRequest
     */
    @RequiresPermission(android.Manifest.permission.CONTROL_DEVICE_STATE)
    public void requestBaseStateOverride(@NonNull DeviceStateRequest request,
            @Nullable Executor executor, @Nullable DeviceStateRequest.Callback callback) {
        DeviceStateRequestWrapper requestWrapper = new DeviceStateRequestWrapper(request, callback,
                executor);
        synchronized (mLock) {
            if (findRequestTokenLocked(request) != null) {
                // This request has already been submitted.
                return;
            }
            // Add the request wrapper to the mRequests array before requesting the state as the
            // callback could be triggered immediately if the mDeviceStateManager IBinder is in the
            // same process as this instance.
            IBinder token = new Binder();
            mRequests.put(token, requestWrapper);

            try {
                mDeviceStateManager.requestBaseStateOverride(token, request.getState(),
                        request.getFlags());
            } catch (RemoteException ex) {
                mRequests.remove(token);
                throw ex.rethrowFromSystemServer();
            }
        }
    }

    /**
     * Cancels a {@link DeviceStateRequest request} previously submitted with a call to
     * {@link #requestBaseStateOverride(DeviceStateRequest, Executor, DeviceStateRequest.Callback)}.
     *
     * @see DeviceStateManager#cancelBaseStateOverride
     */
    @RequiresPermission(android.Manifest.permission.CONTROL_DEVICE_STATE)
    public void cancelBaseStateOverride() {
        synchronized (mLock) {
            try {
                mDeviceStateManager.cancelBaseStateOverride();
            } catch (RemoteException ex) {
                throw ex.rethrowFromSystemServer();
            }
        }
    }

    /**
     * Registers a callback to receive notifications about changes in device state.
     *
     * @see DeviceStateManager#registerCallback(Executor, DeviceStateCallback)
     */
    @VisibleForTesting(visibility = Visibility.PACKAGE)
    public void registerDeviceStateCallback(@NonNull DeviceStateCallback callback,
            @NonNull Executor executor) {
        synchronized (mLock) {
            int index = findCallbackLocked(callback);
            if (index != -1) {
                // This callback is already registered.
                return;
            }
            // Add the callback wrapper to the mCallbacks array after registering the callback as
            // the callback could be triggered immediately if the mDeviceStateManager IBinder is in
            // the same process as this instance.
            DeviceStateCallbackWrapper wrapper = new DeviceStateCallbackWrapper(callback, executor);
            mCallbacks.add(wrapper);

            if (mLastReceivedInfo != null) {
                // Copy the array to prevent the callback from modifying the internal state.
                final int[] supportedStates = Arrays.copyOf(mLastReceivedInfo.supportedStates,
                        mLastReceivedInfo.supportedStates.length);
                wrapper.notifySupportedStatesChanged(supportedStates);
                wrapper.notifyBaseStateChanged(mLastReceivedInfo.baseState);
                wrapper.notifyStateChanged(mLastReceivedInfo.currentState);
            }
        }
    }

    /**
     * Unregisters a callback previously registered with
     * {@link #registerDeviceStateCallback(DeviceStateCallback, Executor)}}.
     *
     * @see DeviceStateManager#unregisterCallback(DeviceStateCallback)
     */
    @VisibleForTesting(visibility = Visibility.PACKAGE)
    public void unregisterDeviceStateCallback(@NonNull DeviceStateCallback callback) {
        synchronized (mLock) {
            int indexToRemove = findCallbackLocked(callback);
            if (indexToRemove != -1) {
                mCallbacks.remove(indexToRemove);
            }
        }
    }

    private void registerCallbackIfNeededLocked() {
        if (mCallback == null) {
            mCallback = new DeviceStateManagerCallback();
            try {
                mDeviceStateManager.registerCallback(mCallback);
            } catch (RemoteException ex) {
                mCallback = null;
                throw ex.rethrowFromSystemServer();
            }
        }
    }

    private int findCallbackLocked(DeviceStateCallback callback) {
        for (int i = 0; i < mCallbacks.size(); i++) {
            if (mCallbacks.get(i).mDeviceStateCallback.equals(callback)) {
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

    /** Handles a call from the server that the device state info has changed. */
    private void handleDeviceStateInfoChanged(@NonNull DeviceStateInfo info) {
        ArrayList<DeviceStateCallbackWrapper> callbacks;
        DeviceStateInfo oldInfo;
        synchronized (mLock) {
            oldInfo = mLastReceivedInfo;
            mLastReceivedInfo = info;
            callbacks = new ArrayList<>(mCallbacks);
        }

        final int diff = oldInfo == null ? ~0 : info.diff(oldInfo);
        if ((diff & DeviceStateInfo.CHANGED_SUPPORTED_STATES) > 0) {
            for (int i = 0; i < callbacks.size(); i++) {
                // Copy the array to prevent callbacks from modifying the internal state.
                final int[] supportedStates = Arrays.copyOf(info.supportedStates,
                        info.supportedStates.length);
                callbacks.get(i).notifySupportedStatesChanged(supportedStates);
            }
        }
        if ((diff & DeviceStateInfo.CHANGED_BASE_STATE) > 0) {
            for (int i = 0; i < callbacks.size(); i++) {
                callbacks.get(i).notifyBaseStateChanged(info.baseState);
            }
        }
        if ((diff & DeviceStateInfo.CHANGED_CURRENT_STATE) > 0) {
            for (int i = 0; i < callbacks.size(); i++) {
                callbacks.get(i).notifyStateChanged(info.currentState);
            }
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
        public void onDeviceStateInfoChanged(DeviceStateInfo info) {
            handleDeviceStateInfoChanged(info);
        }

        @Override
        public void onRequestActive(IBinder token) {
            handleRequestActive(token);
        }

        @Override
        public void onRequestCanceled(IBinder token) {
            handleRequestCanceled(token);
        }
    }

    private static final class DeviceStateCallbackWrapper {
        @NonNull
        private final DeviceStateCallback mDeviceStateCallback;
        @NonNull
        private final Executor mExecutor;

        DeviceStateCallbackWrapper(@NonNull DeviceStateCallback callback,
                @NonNull Executor executor) {
            mDeviceStateCallback = callback;
            mExecutor = executor;
        }

        void notifySupportedStatesChanged(int[] newSupportedStates) {
            mExecutor.execute(() ->
                    mDeviceStateCallback.onSupportedStatesChanged(newSupportedStates));
        }

        void notifyBaseStateChanged(int newBaseState) {
            mExecutor.execute(() -> mDeviceStateCallback.onBaseStateChanged(newBaseState));
        }

        void notifyStateChanged(int newDeviceState) {
            mExecutor.execute(() -> mDeviceStateCallback.onStateChanged(newDeviceState));
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
            validateRequestWrapperParameters(callback, executor);

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

        void notifyRequestCanceled() {
            if (mCallback == null) {
                return;
            }

            mExecutor.execute(() -> mCallback.onRequestCanceled(mRequest));
        }

        private void validateRequestWrapperParameters(
                @Nullable DeviceStateRequest.Callback callback, @Nullable Executor executor) {
            if (callback == null && executor != null) {
                throw new IllegalArgumentException("Callback must be supplied with executor.");
            } else if (executor == null && callback != null) {
                throw new IllegalArgumentException("Executor must be supplied with callback.");
            }
        }
    }
}
