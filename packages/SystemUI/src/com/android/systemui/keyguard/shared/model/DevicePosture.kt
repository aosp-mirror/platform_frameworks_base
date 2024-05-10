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

package com.android.systemui.keyguard.shared.model

import com.android.systemui.statusbar.policy.DevicePostureController

/** Represents the possible posture states of the device. */
enum class DevicePosture {
    UNKNOWN,
    CLOSED,
    HALF_OPENED,
    OPENED,
    FLIPPED;

    companion object {
        fun toPosture(@DevicePostureController.DevicePostureInt posture: Int): DevicePosture {
            return when (posture) {
                DevicePostureController.DEVICE_POSTURE_CLOSED -> CLOSED
                DevicePostureController.DEVICE_POSTURE_HALF_OPENED -> HALF_OPENED
                DevicePostureController.DEVICE_POSTURE_OPENED -> OPENED
                DevicePostureController.DEVICE_POSTURE_FLIPPED -> FLIPPED
                DevicePostureController.DEVICE_POSTURE_UNKNOWN -> UNKNOWN
                else -> UNKNOWN
            }
        }
    }
}
