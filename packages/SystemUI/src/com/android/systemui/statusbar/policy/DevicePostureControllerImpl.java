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

import android.content.Context;
import android.hardware.devicestate.DeviceStateManager;
import android.util.SparseIntArray;

import androidx.annotation.NonNull;

import com.android.internal.R;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.util.Assert;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

import javax.inject.Inject;

/** Implementation of {@link DevicePostureController} using the DeviceStateManager. */
@SysUISingleton
public class DevicePostureControllerImpl implements DevicePostureController {
    private final List<Callback> mListeners = new ArrayList<>();
    private int mCurrentDevicePosture = DEVICE_POSTURE_UNKNOWN;

    private final SparseIntArray mDeviceStateToPostureMap = new SparseIntArray();

    @Inject
    public DevicePostureControllerImpl(
            Context context, DeviceStateManager deviceStateManager, @Main Executor executor) {
        // Most of this is borrowed from WindowManager/Jetpack/DeviceStateManagerPostureProducer.
        // Using the sidecar/extension libraries directly brings in a new dependency that it'd be
        // good to avoid (along with the fact that sidecar is deprecated, and extensions isn't fully
        // ready yet), and we'd have to make our own layer over the sidecar library anyway to easily
        // allow the implementation to change, so it was easier to just interface with
        // DeviceStateManager directly.
        String[] deviceStatePosturePairs = context.getResources()
                .getStringArray(R.array.config_device_state_postures);
        for (String deviceStatePosturePair : deviceStatePosturePairs) {
            String[] deviceStatePostureMapping = deviceStatePosturePair.split(":");
            if (deviceStatePostureMapping.length != 2) {
                continue;
            }

            int deviceState;
            int posture;
            try {
                deviceState = Integer.parseInt(deviceStatePostureMapping[0]);
                posture = Integer.parseInt(deviceStatePostureMapping[1]);
            } catch (NumberFormatException e) {
                continue;
            }

            mDeviceStateToPostureMap.put(deviceState, posture);
        }

        deviceStateManager.registerCallback(executor, state -> {
            Assert.isMainThread();
            mCurrentDevicePosture =
                    mDeviceStateToPostureMap.get(state, DEVICE_POSTURE_UNKNOWN);

            mListeners.forEach(l -> l.onPostureChanged(mCurrentDevicePosture));
        });
    }

    @Override
    public void addCallback(@NonNull Callback listener) {
        Assert.isMainThread();
        mListeners.add(listener);
    }

    @Override
    public void removeCallback(@NonNull Callback listener) {
        Assert.isMainThread();
        mListeners.remove(listener);
    }

    @Override
    public int getDevicePosture() {
        return mCurrentDevicePosture;
    }
}
