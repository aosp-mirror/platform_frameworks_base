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
import android.hardware.devicestate.DeviceState
import android.hardware.devicestate.DeviceState.PROPERTY_FEATURE_REAR_DISPLAY
import android.hardware.devicestate.DeviceState.PROPERTY_FOLDABLE_DISPLAY_CONFIGURATION_INNER_PRIMARY
import android.hardware.devicestate.DeviceState.PROPERTY_FOLDABLE_DISPLAY_CONFIGURATION_OUTER_PRIMARY
import android.hardware.devicestate.DeviceState.PROPERTY_FOLDABLE_HARDWARE_CONFIGURATION_FOLD_IN_HALF_OPEN
import android.hardware.devicestate.DeviceStateManager
import android.provider.Settings.Secure.DEVICE_STATE_ROTATION_KEY_FOLDED
import android.provider.Settings.Secure.DEVICE_STATE_ROTATION_KEY_HALF_FOLDED
import android.provider.Settings.Secure.DEVICE_STATE_ROTATION_KEY_REAR_DISPLAY
import android.provider.Settings.Secure.DEVICE_STATE_ROTATION_KEY_UNFOLDED
import android.provider.Settings.Secure.DEVICE_STATE_ROTATION_KEY_UNKNOWN
import android.provider.Settings.Secure.DeviceStateRotationLockKey
import com.android.internal.R
import android.hardware.devicestate.feature.flags.Flags as DeviceStateManagerFlags

/** Helps to convert between device state and posture. */
class PosturesHelper(context: Context, deviceStateManager: DeviceStateManager?) {

    private val postures: Map<Int, List<Int>>

    init {
        if (deviceStateManager != null && DeviceStateManagerFlags.deviceStatePropertyMigration()) {
            postures =
                deviceStateManager.supportedDeviceStates.groupBy { it.toPosture() }
                    .filterKeys { it != DEVICE_STATE_ROTATION_KEY_UNKNOWN }
                    .mapValues { it.value.map { it.identifier }}
        } else {
            val foldedDeviceStates =
                context.resources.getIntArray(R.array.config_foldedDeviceStates).toList()
            val halfFoldedDeviceStates =
                context.resources.getIntArray(R.array.config_halfFoldedDeviceStates).toList()
            val unfoldedDeviceStates =
                context.resources.getIntArray(R.array.config_openDeviceStates).toList()
            val rearDisplayDeviceStates =
                context.resources.getIntArray(R.array.config_rearDisplayDeviceStates).toList()

            postures =
                mapOf(
                    DEVICE_STATE_ROTATION_KEY_FOLDED to foldedDeviceStates,
                    DEVICE_STATE_ROTATION_KEY_HALF_FOLDED to halfFoldedDeviceStates,
                    DEVICE_STATE_ROTATION_KEY_UNFOLDED to unfoldedDeviceStates,
                    DEVICE_STATE_ROTATION_KEY_REAR_DISPLAY to rearDisplayDeviceStates
                )
        }
    }

    @DeviceStateRotationLockKey
    fun deviceStateToPosture(deviceState: Int): Int {
        return postures.filterValues { it.contains(deviceState) }.keys.firstOrNull()
            ?: DEVICE_STATE_ROTATION_KEY_UNKNOWN
    }

    fun postureToDeviceState(@DeviceStateRotationLockKey posture: Int): Int? {
        return postures[posture]?.firstOrNull()
    }

    /**
     * Maps a [DeviceState] to the corresponding [DeviceStateRotationLockKey] value based on the
     * properties of the state.
     */
    @DeviceStateRotationLockKey
    private fun DeviceState.toPosture(): Int {
        return if (hasProperty(PROPERTY_FEATURE_REAR_DISPLAY)) {
            DEVICE_STATE_ROTATION_KEY_REAR_DISPLAY
        } else if (hasProperty(PROPERTY_FOLDABLE_DISPLAY_CONFIGURATION_OUTER_PRIMARY)) {
            DEVICE_STATE_ROTATION_KEY_FOLDED
        } else if (hasProperties(
                PROPERTY_FOLDABLE_DISPLAY_CONFIGURATION_INNER_PRIMARY,
                PROPERTY_FOLDABLE_HARDWARE_CONFIGURATION_FOLD_IN_HALF_OPEN
            )) {
            DEVICE_STATE_ROTATION_KEY_HALF_FOLDED
        } else if (hasProperty(PROPERTY_FOLDABLE_DISPLAY_CONFIGURATION_INNER_PRIMARY)) {
            DEVICE_STATE_ROTATION_KEY_UNFOLDED
        } else {
            DEVICE_STATE_ROTATION_KEY_UNKNOWN
        }
    }
}
