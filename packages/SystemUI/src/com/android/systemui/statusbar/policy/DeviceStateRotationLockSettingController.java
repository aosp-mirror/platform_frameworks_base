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
import static android.provider.Settings.Secure.DEVICE_STATE_ROTATION_LOCK_UNLOCKED;

import static com.android.systemui.statusbar.policy.dagger.StatusBarPolicyModule.DEVICE_STATE_ROTATION_LOCK_DEFAULTS;

import android.annotation.Nullable;
import android.hardware.devicestate.DeviceStateManager;
import android.os.UserHandle;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
import android.util.SparseIntArray;

import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.util.settings.SecureSettings;
import com.android.systemui.util.wrapper.RotationPolicyWrapper;

import java.util.concurrent.Executor;

import javax.inject.Inject;
import javax.inject.Named;

/**
 * Handles reading and writing of rotation lock settings per device state, as well as setting
 * the rotation lock when device state changes.
 **/
@SysUISingleton
public final class DeviceStateRotationLockSettingController implements Listenable,
        RotationLockController.RotationLockControllerCallback {

    private static final String TAG = "DSRotateLockSettingCon";

    private static final String SEPARATOR_REGEX = ":";

    private final SecureSettings mSecureSettings;
    private final RotationPolicyWrapper mRotationPolicyWrapper;
    private final DeviceStateManager mDeviceStateManager;
    private final Executor mMainExecutor;
    private final String[] mDeviceStateRotationLockDefaults;

    private SparseIntArray mDeviceStateRotationLockSettings;
    // TODO(b/183001527): Add API to query current device state and initialize this.
    private int mDeviceState = -1;
    @Nullable
    private DeviceStateManager.DeviceStateCallback mDeviceStateCallback;


    @Inject
    public DeviceStateRotationLockSettingController(
            SecureSettings secureSettings,
            RotationPolicyWrapper rotationPolicyWrapper,
            DeviceStateManager deviceStateManager,
            @Main Executor executor,
            @Named(DEVICE_STATE_ROTATION_LOCK_DEFAULTS) String[] deviceStateRotationLockDefaults
    ) {
        mSecureSettings = secureSettings;
        mRotationPolicyWrapper = rotationPolicyWrapper;
        mDeviceStateManager = deviceStateManager;
        mMainExecutor = executor;
        mDeviceStateRotationLockDefaults = deviceStateRotationLockDefaults;
    }

    /**
     * Loads the settings from storage.
     */
    public void initialize() {
        String serializedSetting =
                mSecureSettings.getStringForUser(Settings.Secure.DEVICE_STATE_ROTATION_LOCK,
                        UserHandle.USER_CURRENT);
        if (TextUtils.isEmpty(serializedSetting)) {
            // No settings saved, we should load the defaults and persist them.
            fallbackOnDefaults();
            return;
        }
        String[] values = serializedSetting.split(SEPARATOR_REGEX);
        if (values.length % 2 != 0) {
            // Each entry should be a key/value pair, so this is corrupt.
            Log.wtf(TAG, "Can't deserialize saved settings, falling back on defaults");
            fallbackOnDefaults();
            return;
        }
        mDeviceStateRotationLockSettings = new SparseIntArray(values.length / 2);
        int key;
        int value;

        for (int i = 0; i < values.length - 1; ) {
            try {
                key = Integer.parseInt(values[i++]);
                value = Integer.parseInt(values[i++]);
                mDeviceStateRotationLockSettings.put(key, value);
            } catch (NumberFormatException e) {
                Log.wtf(TAG, "Error deserializing one of the saved settings", e);
                fallbackOnDefaults();
                return;
            }
        }
    }

    private void fallbackOnDefaults() {
        loadDefaults();
        persistSettings();
    }

    @Override
    public void setListening(boolean listening) {
        if (listening) {
            // Note that this is called once with the initial state of the device, even if there
            // is no user action.
            mDeviceStateCallback = this::updateDeviceState;
            mDeviceStateManager.registerCallback(mMainExecutor, mDeviceStateCallback);
        } else {
            if (mDeviceStateCallback != null) {
                mDeviceStateManager.unregisterCallback(mDeviceStateCallback);
            }
        }
    }

    @Override
    public void onRotationLockStateChanged(boolean rotationLocked, boolean affordanceVisible) {
        if (mDeviceState == -1) {
            Log.wtf(TAG, "Device state was not initialized.");
            return;
        }

        if (rotationLocked == isRotationLockedForCurrentState()) {
            Log.v(TAG, "Rotation lock same as the current setting, no need to update.");
            return;
        }

        saveNewRotationLockSetting(rotationLocked);
    }

    private void saveNewRotationLockSetting(boolean isRotationLocked) {
        Log.v(TAG, "saveNewRotationLockSetting [state=" + mDeviceState + "] [isRotationLocked="
                + isRotationLocked + "]");

        mDeviceStateRotationLockSettings.put(mDeviceState,
                isRotationLocked
                        ? DEVICE_STATE_ROTATION_LOCK_LOCKED
                        : DEVICE_STATE_ROTATION_LOCK_UNLOCKED);
        persistSettings();
    }

    private boolean isRotationLockedForCurrentState() {
        return mDeviceStateRotationLockSettings.get(mDeviceState,
                DEVICE_STATE_ROTATION_LOCK_IGNORED) == DEVICE_STATE_ROTATION_LOCK_LOCKED;
    }

    private void updateDeviceState(int state) {
        Log.v(TAG, "updateDeviceState [state=" + state + "]");
        if (mDeviceState == state) {
            return;
        }

        int rotationLockSetting =
                mDeviceStateRotationLockSettings.get(state, DEVICE_STATE_ROTATION_LOCK_IGNORED);
        if (rotationLockSetting == DEVICE_STATE_ROTATION_LOCK_IGNORED) {
            // We won't handle this device state. The same rotation lock setting as before should
            // apply and any changes to the rotation lock setting will be written for the previous
            // valid device state.
            Log.v(TAG, "Ignoring new device state: " + state);
            return;
        }

        // Accept the new state
        mDeviceState = state;

        // Update the rotation lock setting if needed for this new device state
        boolean newRotationLockSetting = rotationLockSetting == DEVICE_STATE_ROTATION_LOCK_LOCKED;
        if (newRotationLockSetting != mRotationPolicyWrapper.isRotationLocked()) {
            mRotationPolicyWrapper.setRotationLock(newRotationLockSetting);
        }
    }

    private void persistSettings() {
        if (mDeviceStateRotationLockSettings.size() == 0) {
            mSecureSettings.putStringForUser(Settings.Secure.DEVICE_STATE_ROTATION_LOCK,
                    /* value= */"", UserHandle.USER_CURRENT);
            return;
        }

        StringBuilder stringBuilder = new StringBuilder();
        stringBuilder.append(mDeviceStateRotationLockSettings.keyAt(0))
                .append(SEPARATOR_REGEX)
                .append(mDeviceStateRotationLockSettings.valueAt(0));

        for (int i = 1; i < mDeviceStateRotationLockSettings.size(); i++) {
            stringBuilder
                    .append(SEPARATOR_REGEX)
                    .append(mDeviceStateRotationLockSettings.keyAt(i))
                    .append(SEPARATOR_REGEX)
                    .append(mDeviceStateRotationLockSettings.valueAt(i));
        }
        mSecureSettings.putStringForUser(Settings.Secure.DEVICE_STATE_ROTATION_LOCK,
                stringBuilder.toString(), UserHandle.USER_CURRENT);
    }

    private void loadDefaults() {
        if (mDeviceStateRotationLockDefaults.length == 0) {
            Log.w(TAG, "Empty default settings");
            mDeviceStateRotationLockSettings = new SparseIntArray(/* initialCapacity= */0);
            return;
        }
        mDeviceStateRotationLockSettings =
                new SparseIntArray(mDeviceStateRotationLockDefaults.length);
        for (String serializedDefault : mDeviceStateRotationLockDefaults) {
            String[] entry = serializedDefault.split(SEPARATOR_REGEX);
            try {
                int key = Integer.parseInt(entry[0]);
                int value = Integer.parseInt(entry[1]);
                mDeviceStateRotationLockSettings.put(key, value);
            } catch (NumberFormatException e) {
                Log.wtf(TAG, "Error deserializing default settings", e);
            }
        }
    }

}
