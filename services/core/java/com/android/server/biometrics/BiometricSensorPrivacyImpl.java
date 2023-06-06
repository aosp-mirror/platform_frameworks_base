/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.server.biometrics;

import static android.hardware.SensorPrivacyManager.Sensors.CAMERA;

import android.annotation.Nullable;
import android.hardware.SensorPrivacyManager;

public class BiometricSensorPrivacyImpl implements
        BiometricSensorPrivacy {
    private final SensorPrivacyManager mSensorPrivacyManager;

    public BiometricSensorPrivacyImpl(@Nullable SensorPrivacyManager sensorPrivacyManager) {
        mSensorPrivacyManager = sensorPrivacyManager;
    }

    @Override
    public boolean isCameraPrivacyEnabled() {
        return mSensorPrivacyManager != null && mSensorPrivacyManager
                .isSensorPrivacyEnabled(SensorPrivacyManager.TOGGLE_TYPE_SOFTWARE, CAMERA);
    }
}
