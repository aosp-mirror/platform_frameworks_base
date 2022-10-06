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

package androidx.window.extensions.area;

import static android.hardware.devicestate.DeviceStateManager.INVALID_DEVICE_STATE;

import android.app.Activity;
import android.content.Context;
import android.hardware.devicestate.DeviceStateManager;
import android.hardware.devicestate.DeviceStateRequest;
import android.util.ArraySet;

import androidx.annotation.NonNull;

import com.android.internal.R;
import com.android.internal.annotations.GuardedBy;

import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * Reference implementation of androidx.window.extensions.area OEM interface for use with
 * WindowManager Jetpack.
 *
 * This component currently supports Rear Display mode with the ability to add and remove
 * status listeners for this mode.
 *
 * The public methods in this class are thread-safe.
 **/
public class WindowAreaComponentImpl implements WindowAreaComponent,
        DeviceStateManager.DeviceStateCallback {

    private final Object mLock = new Object();

    private final DeviceStateManager mDeviceStateManager;
    private final Executor mExecutor;

    @GuardedBy("mLock")
    private final ArraySet<Consumer<Integer>> mRearDisplayStatusListeners = new ArraySet<>();
    private final int mRearDisplayState;
    @WindowAreaSessionState
    private int mRearDisplaySessionStatus = WindowAreaComponent.SESSION_STATE_INACTIVE;

    @GuardedBy("mLock")
    private int mCurrentDeviceState = INVALID_DEVICE_STATE;
    @GuardedBy("mLock")
    private int mCurrentDeviceBaseState = INVALID_DEVICE_STATE;
    @GuardedBy("mLock")
    private DeviceStateRequest mDeviceStateRequest;

    public WindowAreaComponentImpl(@NonNull Context context) {
        mDeviceStateManager = context.getSystemService(DeviceStateManager.class);
        mExecutor = context.getMainExecutor();

        // TODO(b/236022708) Move rear display state to device state config file
        mRearDisplayState = context.getResources().getInteger(
                R.integer.config_deviceStateRearDisplay);

        mDeviceStateManager.registerCallback(mExecutor, this);
    }

    /**
     * Adds a listener interested in receiving updates on the RearDisplayStatus
     * of the device. Because this is being called from the OEM provided
     * extensions, we will post the result of the listener on the executor
     * provided by the developer at the initial call site.
     *
     * Depending on the initial state of the device, we will return either
     * {@link WindowAreaComponent#STATUS_AVAILABLE} or
     * {@link WindowAreaComponent#STATUS_UNAVAILABLE} if the feature is supported or not in that
     * state respectively. When the rear display feature is triggered, we update the status to be
     * {@link WindowAreaComponent#STATUS_UNAVAILABLE}. TODO(b/240727590) Prefix with AREA_
     *
     * TODO(b/239833099) Add a STATUS_ACTIVE option to let apps know if a feature is currently
     * enabled.
     *
     * @param consumer {@link Consumer} interested in receiving updates to the status of
     * rear display mode.
     */
    public void addRearDisplayStatusListener(
            @NonNull Consumer<@WindowAreaStatus Integer> consumer) {
        synchronized (mLock) {
            mRearDisplayStatusListeners.add(consumer);

            // If current device state is still invalid, we haven't gotten our initial value yet
            if (mCurrentDeviceState == INVALID_DEVICE_STATE) {
                return;
            }
            consumer.accept(getCurrentStatus());
        }
    }

    /**
     * Removes a listener no longer interested in receiving updates.
     * @param consumer no longer interested in receiving updates to RearDisplayStatus
     */
    public void removeRearDisplayStatusListener(
            @NonNull Consumer<@WindowAreaStatus Integer> consumer) {
        synchronized (mLock) {
            mRearDisplayStatusListeners.remove(consumer);
        }
    }

    /**
     * Creates and starts a rear display session and provides updates to the
     * callback provided. Because this is being called from the OEM provided
     * extensions, we will post the result of the listener on the executor
     * provided by the developer at the initial call site.
     *
     * When we enable rear display mode, we submit a request to {@link DeviceStateManager}
     * to override the device state to the state that corresponds to RearDisplay
     * mode. When the {@link DeviceStateRequest} is activated, we let the
     * consumer know that the session is active by sending
     * {@link WindowAreaComponent#SESSION_STATE_ACTIVE}.
     *
     * @param activity to provide updates to the client on
     * the status of the Session
     * @param rearDisplaySessionCallback to provide updates to the client on
     * the status of the Session
     */
    public void startRearDisplaySession(@NonNull Activity activity,
            @NonNull Consumer<@WindowAreaSessionState Integer> rearDisplaySessionCallback) {
        synchronized (mLock) {
            if (mDeviceStateRequest != null) {
                // Rear display session is already active
                throw new IllegalStateException(
                        "Unable to start new rear display session as one is already active");
            }
            mDeviceStateRequest = DeviceStateRequest.newBuilder(mRearDisplayState).build();
            mDeviceStateManager.requestState(
                    mDeviceStateRequest,
                    mExecutor,
                    new DeviceStateRequestCallbackAdapter(rearDisplaySessionCallback)
            );
        }
    }

    /**
     * Ends the current rear display session and provides updates to the
     * callback provided. Because this is being called from the OEM provided
     * extensions, we will post the result of the listener on the executor
     * provided by the developer.
     */
    public void endRearDisplaySession() {
        synchronized (mLock) {
            if (mDeviceStateRequest != null || isRearDisplayActive()) {
                mDeviceStateRequest = null;
                mDeviceStateManager.cancelStateRequest();
            } else {
                throw new IllegalStateException(
                        "Unable to cancel a rear display session as there is no active session");
            }
        }
    }

    @Override
    public void onBaseStateChanged(int state) {
        synchronized (mLock) {
            mCurrentDeviceBaseState = state;
            if (state == mCurrentDeviceState) {
                updateStatusConsumers(getCurrentStatus());
            }
        }
    }

    @Override
    public void onStateChanged(int state) {
        synchronized (mLock) {
            mCurrentDeviceState = state;
            updateStatusConsumers(getCurrentStatus());
        }
    }

    @GuardedBy("mLock")
    private int getCurrentStatus() {
        if (mRearDisplaySessionStatus == WindowAreaComponent.SESSION_STATE_ACTIVE
                || isRearDisplayActive()) {
            return WindowAreaComponent.STATUS_UNAVAILABLE;
        }
        return WindowAreaComponent.STATUS_AVAILABLE;
    }

    /**
     * Helper method to determine if a rear display session is currently active by checking
     * if the current device configuration matches that of rear display. This would be true
     * if there is a device override currently active (base state != current state) and the current
     * state is that which corresponds to {@code mRearDisplayState}
     * @return {@code true} if the device is in rear display mode and {@code false} if not
     */
    @GuardedBy("mLock")
    private boolean isRearDisplayActive() {
        return (mCurrentDeviceState != mCurrentDeviceBaseState) && (mCurrentDeviceState
                == mRearDisplayState);
    }

    @GuardedBy("mLock")
    private void updateStatusConsumers(@WindowAreaStatus int windowAreaStatus) {
        synchronized (mLock) {
            for (int i = 0; i < mRearDisplayStatusListeners.size(); i++) {
                mRearDisplayStatusListeners.valueAt(i).accept(windowAreaStatus);
            }
        }
    }

    /**
     * Callback for the {@link DeviceStateRequest} to be notified of when the request has been
     * activated or cancelled. This callback provides information to the client library
     * on the status of the RearDisplay session through {@code mRearDisplaySessionCallback}
     */
    private class DeviceStateRequestCallbackAdapter implements DeviceStateRequest.Callback {

        private final Consumer<Integer> mRearDisplaySessionCallback;

        DeviceStateRequestCallbackAdapter(@NonNull Consumer<Integer> callback) {
            mRearDisplaySessionCallback = callback;
        }

        @Override
        public void onRequestActivated(@NonNull DeviceStateRequest request) {
            synchronized (mLock) {
                if (request.equals(mDeviceStateRequest)) {
                    mRearDisplaySessionStatus = WindowAreaComponent.SESSION_STATE_ACTIVE;
                    mRearDisplaySessionCallback.accept(mRearDisplaySessionStatus);
                    updateStatusConsumers(getCurrentStatus());
                }
            }
        }

        @Override
        public void onRequestCanceled(DeviceStateRequest request) {
            synchronized (mLock) {
                if (request.equals(mDeviceStateRequest)) {
                    mDeviceStateRequest = null;
                }
                mRearDisplaySessionStatus = WindowAreaComponent.SESSION_STATE_INACTIVE;
                mRearDisplaySessionCallback.accept(mRearDisplaySessionStatus);
                updateStatusConsumers(getCurrentStatus());
            }
        }
    }
}
