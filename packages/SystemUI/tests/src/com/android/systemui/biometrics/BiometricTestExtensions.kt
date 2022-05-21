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

package com.android.systemui.biometrics

import android.hardware.biometrics.ComponentInfoInternal
import android.hardware.biometrics.SensorLocationInternal
import android.hardware.biometrics.SensorProperties
import android.hardware.fingerprint.FingerprintSensorProperties
import android.hardware.fingerprint.FingerprintSensorPropertiesInternal

/** Creates properties from the sensor location with test values. */
fun SensorLocationInternal.asFingerprintSensorProperties(
    sensorId: Int = 22,
    @SensorProperties.Strength sensorStrength: Int = SensorProperties.STRENGTH_WEAK,
    @FingerprintSensorProperties.SensorType sensorType: Int =
        FingerprintSensorProperties.TYPE_UDFPS_OPTICAL,
    maxEnrollmentsPerUser: Int = 1,
    halControlsIllumination: Boolean = true,
    info: List<ComponentInfoInternal> = listOf(ComponentInfoInternal("a", "b", "c", "d", "e")),
    resetLockoutRequiresHardwareAuthToken: Boolean = false
) = FingerprintSensorPropertiesInternal(
    sensorId,
    sensorStrength,
    maxEnrollmentsPerUser,
    info,
    sensorType,
    halControlsIllumination,
    resetLockoutRequiresHardwareAuthToken,
    listOf(this)
)
