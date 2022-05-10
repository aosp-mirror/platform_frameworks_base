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

package com.android.systemui.util.sensors;

import android.util.Log;

import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.statusbar.policy.DevicePostureController;
import com.android.systemui.util.concurrency.DelayableExecutor;
import com.android.systemui.util.concurrency.Execution;

import javax.inject.Inject;

/**
 * Proximity sensor that changes proximity sensor usage based on the current posture.
 * Posture -> prox sensor mapping can be found in SystemUI config overlays at:
 *   - proximity_sensor_posture_mapping
 *   - proximity_sensor_secondary_posture_mapping.
 * where the array indices correspond to the following postures:
 *     [UNKNOWN, CLOSED, HALF_OPENED, OPENED]
 */
class PostureDependentProximitySensor extends ProximitySensorImpl {
    private final ThresholdSensor[] mPostureToPrimaryProxSensorMap;
    private final ThresholdSensor[] mPostureToSecondaryProxSensorMap;

    private final DevicePostureController mDevicePostureController;

    @Inject
    PostureDependentProximitySensor(
            @PrimaryProxSensor ThresholdSensor[] postureToPrimaryProxSensorMap,
            @SecondaryProxSensor ThresholdSensor[] postureToSecondaryProxSensorMap,
            @Main DelayableExecutor delayableExecutor,
            Execution execution,
            DevicePostureController devicePostureController
    ) {
        super(
                postureToPrimaryProxSensorMap[0],
                postureToSecondaryProxSensorMap[0],
                delayableExecutor,
                execution
        );
        mPostureToPrimaryProxSensorMap = postureToPrimaryProxSensorMap;
        mPostureToSecondaryProxSensorMap = postureToSecondaryProxSensorMap;
        mDevicePostureController = devicePostureController;

        mDevicePosture = mDevicePostureController.getDevicePosture();
        mDevicePostureController.addCallback(mDevicePostureCallback);

        chooseSensors();
    }

    @Override
    public void destroy() {
        super.destroy();
        mDevicePostureController.removeCallback(mDevicePostureCallback);
    }

    private void chooseSensors() {
        if (mDevicePosture >= mPostureToPrimaryProxSensorMap.length
                || mDevicePosture >= mPostureToSecondaryProxSensorMap.length) {
            Log.e("PostureDependProxSensor",
                    "unsupported devicePosture=" + mDevicePosture);
            return;
        }

        ThresholdSensor newPrimaryProx = mPostureToPrimaryProxSensorMap[mDevicePosture];
        ThresholdSensor newSecondaryProx = mPostureToSecondaryProxSensorMap[mDevicePosture];

        if (newPrimaryProx != mPrimaryThresholdSensor
                || newSecondaryProx != mSecondaryThresholdSensor) {
            logDebug("Register new proximity sensors newPosture="
                    + DevicePostureController.devicePostureToString(mDevicePosture));
            unregisterInternal();

            if (mPrimaryThresholdSensor != null) {
                mPrimaryThresholdSensor.unregister(mPrimaryEventListener);
            }
            if (mSecondaryThresholdSensor != null) {
                mSecondaryThresholdSensor.unregister(mSecondaryEventListener);
            }

            mPrimaryThresholdSensor = newPrimaryProx;
            mSecondaryThresholdSensor = newSecondaryProx;

            mInitializedListeners = false;
            registerInternal();
        }
    }

    private final DevicePostureController.Callback mDevicePostureCallback =
            posture -> {
                if (mDevicePosture == posture) {
                    return;
                }

                mDevicePosture = posture;
                chooseSensors();
            };

    @Override
    public String toString() {
        return String.format("{posture=%s, proximitySensor=%s}",
                DevicePostureController.devicePostureToString(mDevicePosture), super.toString());
    }
}
