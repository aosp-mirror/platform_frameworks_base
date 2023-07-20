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

package com.android.server.wm;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.hardware.devicestate.DeviceStateManager;
import android.os.Handler;
import android.os.HandlerExecutor;

import com.android.internal.R;
import com.android.internal.util.ArrayUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Class that registers callbacks with the {@link DeviceStateManager} and responds to device
 * changes.
 */
final class DeviceStateController implements DeviceStateManager.DeviceStateCallback {

    @NonNull
    private final DeviceStateManager mDeviceStateManager;
    @NonNull
    private final int[] mOpenDeviceStates;
    @NonNull
    private final int[] mHalfFoldedDeviceStates;
    @NonNull
    private final int[] mFoldedDeviceStates;
    @NonNull
    private final int[] mRearDisplayDeviceStates;
    @NonNull
    private final int[] mReverseRotationAroundZAxisStates;
    @NonNull
    private final List<Consumer<DeviceState>> mDeviceStateCallbacks = new ArrayList<>();

    @Nullable
    private DeviceState mLastDeviceState;
    private int mCurrentState;

    public enum DeviceState {
        UNKNOWN, OPEN, FOLDED, HALF_FOLDED, REAR,
    }

    DeviceStateController(@NonNull Context context, @NonNull Handler handler) {
        mDeviceStateManager = context.getSystemService(DeviceStateManager.class);

        mOpenDeviceStates = context.getResources()
                .getIntArray(R.array.config_openDeviceStates);
        mHalfFoldedDeviceStates = context.getResources()
                .getIntArray(R.array.config_halfFoldedDeviceStates);
        mFoldedDeviceStates = context.getResources()
                .getIntArray(R.array.config_foldedDeviceStates);
        mRearDisplayDeviceStates = context.getResources()
                .getIntArray(R.array.config_rearDisplayDeviceStates);
        mReverseRotationAroundZAxisStates = context.getResources()
                .getIntArray(R.array.config_deviceStatesToReverseDefaultDisplayRotationAroundZAxis);

        if (mDeviceStateManager != null) {
            mDeviceStateManager.registerCallback(new HandlerExecutor(handler), this);
        }
    }

    void unregisterFromDeviceStateManager() {
        if (mDeviceStateManager != null) {
            mDeviceStateManager.unregisterCallback(this);
        }
    }

    void registerDeviceStateCallback(@NonNull Consumer<DeviceState> callback) {
        mDeviceStateCallbacks.add(callback);
    }

    /**
     * @return true if the rotation direction on the Z axis should be reversed.
     */
    boolean shouldReverseRotationDirectionAroundZAxis() {
        return ArrayUtils.contains(mReverseRotationAroundZAxisStates, mCurrentState);
    }

    @Override
    public void onStateChanged(int state) {
        mCurrentState = state;

        final DeviceState deviceState;
        if (ArrayUtils.contains(mHalfFoldedDeviceStates, state)) {
            deviceState = DeviceState.HALF_FOLDED;
        } else if (ArrayUtils.contains(mFoldedDeviceStates, state)) {
            deviceState = DeviceState.FOLDED;
        } else if (ArrayUtils.contains(mRearDisplayDeviceStates, state)) {
            deviceState = DeviceState.REAR;
        } else if (ArrayUtils.contains(mOpenDeviceStates, state)) {
            deviceState = DeviceState.OPEN;
        } else {
            deviceState = DeviceState.UNKNOWN;
        }

        if (mLastDeviceState == null || !mLastDeviceState.equals(deviceState)) {
            mLastDeviceState = deviceState;

            for (Consumer<DeviceState> callback : mDeviceStateCallbacks) {
                callback.accept(mLastDeviceState);
            }
        }
    }
}
