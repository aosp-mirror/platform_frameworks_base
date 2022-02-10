/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.systemui.statusbar.policy;

import static android.provider.Settings.Secure.DEVICE_STATE_ROTATION_LOCK_IGNORED;
import static android.provider.Settings.Secure.DEVICE_STATE_ROTATION_LOCK_LOCKED;

import android.annotation.Nullable;
import android.hardware.devicestate.DeviceStateManager;
import android.os.Trace;
import android.util.Log;

import com.android.settingslib.devicestate.DeviceStateRotationLockSettingsManager;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.util.wrapper.RotationPolicyWrapper;

import java.util.concurrent.Executor;

import javax.inject.Inject;

/**
 * Handles reading and writing of rotation lock settings per device state, as well as setting the
 * rotation lock when device state changes.
 */
@SysUISingleton
public final class DeviceStateRotationLockSettingController
        implements Listenable, RotationLockController.RotationLockControllerCallback {

    private static final String TAG = "DSRotateLockSettingCon";

    private final RotationPolicyWrapper mRotationPolicyWrapper;
    private final DeviceStateManager mDeviceStateManager;
    private final Executor mMainExecutor;
    private final DeviceStateRotationLockSettingsManager mDeviceStateRotationLockSettingsManager;

    // On registration for DeviceStateCallback, we will receive a callback with the current state
    // and this will be initialized.
    private int mDeviceState = -1;
    @Nullable private DeviceStateManager.DeviceStateCallback mDeviceStateCallback;
    private DeviceStateRotationLockSettingsManager.DeviceStateRotationLockSettingsListener
            mDeviceStateRotationLockSettingsListener;

    @Inject
    public DeviceStateRotationLockSettingController(
            RotationPolicyWrapper rotationPolicyWrapper,
            DeviceStateManager deviceStateManager,
            @Main Executor executor,
            DeviceStateRotationLockSettingsManager deviceStateRotationLockSettingsManager) {
        mRotationPolicyWrapper = rotationPolicyWrapper;
        mDeviceStateManager = deviceStateManager;
        mMainExecutor = executor;
        mDeviceStateRotationLockSettingsManager = deviceStateRotationLockSettingsManager;
    }

    @Override
    public void setListening(boolean listening) {
        if (listening) {
            // Note that this is called once with the initial state of the device, even if there
            // is no user action.
            mDeviceStateCallback = this::updateDeviceState;
            mDeviceStateManager.registerCallback(mMainExecutor, mDeviceStateCallback);
            mDeviceStateRotationLockSettingsListener = () -> readPersistedSetting(mDeviceState);
            mDeviceStateRotationLockSettingsManager.registerListener(
                    mDeviceStateRotationLockSettingsListener);
        } else {
            if (mDeviceStateCallback != null) {
                mDeviceStateManager.unregisterCallback(mDeviceStateCallback);
            }
            if (mDeviceStateRotationLockSettingsListener != null) {
                mDeviceStateRotationLockSettingsManager.unregisterListener(
                        mDeviceStateRotationLockSettingsListener);
            }
        }
    }

    @Override
    public void onRotationLockStateChanged(boolean rotationLocked, boolean affordanceVisible) {
        if (mDeviceState == -1) {
            Log.wtf(TAG, "Device state was not initialized.");
            return;
        }

        if (rotationLocked
                == mDeviceStateRotationLockSettingsManager.isRotationLocked(mDeviceState)) {
            Log.v(TAG, "Rotation lock same as the current setting, no need to update.");
            return;
        }

        saveNewRotationLockSetting(rotationLocked);
    }

    private void saveNewRotationLockSetting(boolean isRotationLocked) {
        Log.v(
                TAG,
                "saveNewRotationLockSetting [state="
                        + mDeviceState
                        + "] [isRotationLocked="
                        + isRotationLocked
                        + "]");

        mDeviceStateRotationLockSettingsManager.updateSetting(mDeviceState, isRotationLocked);
    }

    private void updateDeviceState(int state) {
        Log.v(TAG, "updateDeviceState [state=" + state + "]");
        Trace.beginSection("updateDeviceState [state=" + state + "]");
        try {
            if (mDeviceState == state) {
                return;
            }

            readPersistedSetting(state);
        } finally {
            Trace.endSection();
        }
    }

    private void readPersistedSetting(int state) {
        int rotationLockSetting =
                mDeviceStateRotationLockSettingsManager.getRotationLockSetting(state);
        if (rotationLockSetting == DEVICE_STATE_ROTATION_LOCK_IGNORED) {
            // This should not happen. Device states that have an ignored setting, should also
            // specify a fallback device state which is not ignored.
            // We won't handle this device state. The same rotation lock setting as before should
            // apply and any changes to the rotation lock setting will be written for the previous
            // valid device state.
            Log.w(TAG, "Missing fallback. Ignoring new device state: " + state);
            return;
        }

        // Accept the new state
        mDeviceState = state;

        // Update the rotation policy, if needed, for this new device state
        boolean newRotationLockSetting = rotationLockSetting == DEVICE_STATE_ROTATION_LOCK_LOCKED;
        if (newRotationLockSetting != mRotationPolicyWrapper.isRotationLocked()) {
            mRotationPolicyWrapper.setRotationLock(newRotationLockSetting);
        }
    }
}
