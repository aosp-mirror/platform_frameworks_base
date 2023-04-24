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

package com.android.settingslib.devicestate

import android.content.Context
import android.provider.Settings.Secure.DEVICE_STATE_ROTATION_KEY_FOLDED
import android.provider.Settings.Secure.DEVICE_STATE_ROTATION_KEY_HALF_FOLDED
import android.provider.Settings.Secure.DEVICE_STATE_ROTATION_KEY_REAR_DISPLAY
import android.provider.Settings.Secure.DEVICE_STATE_ROTATION_KEY_UNFOLDED
import android.provider.Settings.Secure.DEVICE_STATE_ROTATION_KEY_UNKNOWN
import android.provider.Settings.Secure.DeviceStateRotationLockKey
import com.android.internal.R

/** Helps to convert between device state and posture. */
class PosturesHelper(context: Context) {

    private val foldedDeviceStates =
        context.resources.getIntArray(R.array.config_foldedDeviceStates)
    private val halfFoldedDeviceStates =
        context.resources.getIntArray(R.array.config_halfFoldedDeviceStates)
    private val unfoldedDeviceStates =
        context.resources.getIntArray(R.array.config_openDeviceStates)
    private val rearDisplayDeviceStates =
        context.resources.getIntArray(R.array.config_rearDisplayDeviceStates)

    @DeviceStateRotationLockKey
    fun deviceStateToPosture(deviceState: Int): Int {
        return when (deviceState) {
            in foldedDeviceStates -> DEVICE_STATE_ROTATION_KEY_FOLDED
            in halfFoldedDeviceStates -> DEVICE_STATE_ROTATION_KEY_HALF_FOLDED
            in unfoldedDeviceStates -> DEVICE_STATE_ROTATION_KEY_UNFOLDED
            in rearDisplayDeviceStates -> DEVICE_STATE_ROTATION_KEY_REAR_DISPLAY
            else -> DEVICE_STATE_ROTATION_KEY_UNKNOWN
        }
    }

    fun postureToDeviceState(@DeviceStateRotationLockKey posture: Int): Int? {
        return when (posture) {
            DEVICE_STATE_ROTATION_KEY_FOLDED -> foldedDeviceStates.firstOrNull()
            DEVICE_STATE_ROTATION_KEY_HALF_FOLDED -> halfFoldedDeviceStates.firstOrNull()
            DEVICE_STATE_ROTATION_KEY_UNFOLDED -> unfoldedDeviceStates.firstOrNull()
            DEVICE_STATE_ROTATION_KEY_REAR_DISPLAY -> rearDisplayDeviceStates.firstOrNull()
            else -> null
        }
    }
}
