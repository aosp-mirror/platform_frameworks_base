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

package com.android.systemui.keyguard.shared.model

import android.hardware.biometrics.BiometricSourceType

/** Biometric unlock sensor sources, which we use to play sensor-specific animations. */
enum class BiometricUnlockSource {
    /** The unlock was initiated by a fingerprint sensor authentication. */
    FINGERPRINT_SENSOR,

    /** The unlock was initiated by the front-facing camera or a nearby sensor. */
    FACE_SENSOR;

    companion object {
        fun fromBiometricSourceType(type: BiometricSourceType?): BiometricUnlockSource? {
            return when (type) {
                BiometricSourceType.FINGERPRINT -> FINGERPRINT_SENSOR
                BiometricSourceType.FACE -> FACE_SENSOR
                BiometricSourceType.IRIS -> FACE_SENSOR
                else -> null
            }
        }
    }
}
