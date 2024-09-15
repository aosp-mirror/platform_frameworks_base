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
import android.hardware.devicestate.DeviceState;
import android.hardware.devicestate.DeviceStateManager;
import android.os.Trace;
import android.util.IndentingPrintWriter;

import androidx.annotation.NonNull;

import com.android.settingslib.devicestate.DeviceStateRotationLockSettingsManager;
import com.android.systemui.Dumpable;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.dump.DumpManager;
import com.android.systemui.util.wrapper.RotationPolicyWrapper;

import java.io.PrintWriter;
import java.util.concurrent.Executor;

import javax.inject.Inject;

/**
 * Handles reading and writing of rotation lock settings per device state, as well as setting the
 * rotation lock when device state changes.
 */
@SysUISingleton
public final class DeviceStateRotationLockSettingController
        implements Listenable, RotationLockController.RotationLockControllerCallback, Dumpable {

    private final RotationPolicyWrapper mRotationPolicyWrapper;
    private final DeviceStateManager mDeviceStateManager;
    private final Executor mMainExecutor;
    private final DeviceStateRotationLockSettingsManager mDeviceStateRotationLockSettingsManager;
    private final DeviceStateRotationLockSettingControllerLogger mLogger;

    // On registration for DeviceStateCallback, we will receive a callback with the current state
    // and this will be initialized.
    private int mDeviceState = -1;
    @Nullable
    private DeviceStateManager.DeviceStateCallback mDeviceStateCallback;
    private DeviceStateRotationLockSettingsManager.DeviceStateRotationLockSettingsListener
            mDeviceStateRotationLockSettingsListener;

    @Inject
    public DeviceStateRotationLockSettingController(
            RotationPolicyWrapper rotationPolicyWrapper,
            DeviceStateManager deviceStateManager,
            @Main Executor executor,
            DeviceStateRotationLockSettingsManager deviceStateRotationLockSettingsManager,
            DeviceStateRotationLockSettingControllerLogger logger,
            DumpManager dumpManager) {
        mRotationPolicyWrapper = rotationPolicyWrapper;
        mDeviceStateManager = deviceStateManager;
        mMainExecutor = executor;
        mDeviceStateRotationLockSettingsManager = deviceStateRotationLockSettingsManager;
        mLogger = logger;
        dumpManager.registerDumpable(this);
    }

    @Override
    public void setListening(boolean listening) {
        mLogger.logListeningChange(listening);
        if (listening) {
            // Note that this is called once with the initial state of the device, even if there
            // is no user action.
            mDeviceStateCallback = this::updateDeviceState;
            mDeviceStateManager.registerCallback(mMainExecutor, mDeviceStateCallback);
            mDeviceStateRotationLockSettingsListener = () ->
                    readPersistedSetting("deviceStateRotationLockChange", mDeviceState);
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
    public void onRotationLockStateChanged(boolean newRotationLocked, boolean affordanceVisible) {
        int deviceState = mDeviceState;
        boolean currentRotationLocked = mDeviceStateRotationLockSettingsManager
                .isRotationLocked(deviceState);
        mLogger.logRotationLockStateChanged(deviceState, newRotationLocked, currentRotationLocked);
        if (deviceState == -1) {
            return;
        }
        if (newRotationLocked == currentRotationLocked) {
            return;
        }
        saveNewRotationLockSetting(newRotationLocked);
    }

    private void saveNewRotationLockSetting(boolean isRotationLocked) {
        int deviceState = mDeviceState;
        mLogger.logSaveNewRotationLockSetting(isRotationLocked, deviceState);
        mDeviceStateRotationLockSettingsManager.updateSetting(deviceState, isRotationLocked);
    }

    private void updateDeviceState(@NonNull DeviceState state) {
        mLogger.logUpdateDeviceState(mDeviceState, state.getIdentifier());
        try {
            if (Trace.isEnabled()) {
                Trace.traceBegin(Trace.TRACE_TAG_APP,
                        "updateDeviceState [state=" + state.getIdentifier() + "]");
            }
            if (mDeviceState == state.getIdentifier()) {
                return;
            }

            readPersistedSetting("updateDeviceState", state.getIdentifier());
        } finally {
            Trace.endSection();
        }
    }

    private void readPersistedSetting(String caller, int state) {
        int rotationLockSetting =
                mDeviceStateRotationLockSettingsManager.getRotationLockSetting(state);
        boolean shouldBeLocked = rotationLockSetting == DEVICE_STATE_ROTATION_LOCK_LOCKED;
        boolean isLocked = mRotationPolicyWrapper.isRotationLocked();

        mLogger.readPersistedSetting(caller, state, rotationLockSetting, shouldBeLocked, isLocked);

        if (rotationLockSetting == DEVICE_STATE_ROTATION_LOCK_IGNORED) {
            // This should not happen. Device states that have an ignored setting, should also
            // specify a fallback device state which is not ignored.
            // We won't handle this device state. The same rotation lock setting as before should
            // apply and any changes to the rotation lock setting will be written for the previous
            // valid device state.
            return;
        }

        // Accept the new state
        mDeviceState = state;

        // Update the rotation policy, if needed, for this new device state
        if (shouldBeLocked != isLocked) {
            mRotationPolicyWrapper.setRotationLock(shouldBeLocked,
                    /* caller= */"DeviceStateRotationLockSettingController#readPersistedSetting");
        }
    }

    @Override
    public void dump(@NonNull PrintWriter printWriter, @NonNull String[] args) {
        IndentingPrintWriter pw = new IndentingPrintWriter(printWriter);
        mDeviceStateRotationLockSettingsManager.dump(pw);
        pw.println("DeviceStateRotationLockSettingController");
        pw.increaseIndent();
        pw.println("mDeviceState: " + mDeviceState);
        pw.decreaseIndent();
    }
}
