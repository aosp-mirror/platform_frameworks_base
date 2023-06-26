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

package com.android.systemui.statusbar.policy

import android.content.Context
import android.provider.Settings.Secure.DEVICE_STATE_ROTATION_LOCK_IGNORED
import android.provider.Settings.Secure.DEVICE_STATE_ROTATION_LOCK_LOCKED
import android.provider.Settings.Secure.DEVICE_STATE_ROTATION_LOCK_UNLOCKED
import com.android.internal.R
import com.android.systemui.log.dagger.DeviceStateAutoRotationLog
import com.android.systemui.plugins.log.LogBuffer
import com.android.systemui.plugins.log.LogLevel.VERBOSE
import javax.inject.Inject

class DeviceStateRotationLockSettingControllerLogger
@Inject
constructor(@DeviceStateAutoRotationLog private val logBuffer: LogBuffer, context: Context) {

    private val foldedStates = context.resources.getIntArray(R.array.config_foldedDeviceStates)
    private val halfFoldedStates =
        context.resources.getIntArray(R.array.config_halfFoldedDeviceStates)
    private val unfoldedStates = context.resources.getIntArray(R.array.config_openDeviceStates)

    fun logListeningChange(listening: Boolean) {
        logBuffer.log(TAG, VERBOSE, { bool1 = listening }, { "setListening: $bool1" })
    }

    fun logRotationLockStateChanged(
        state: Int,
        newRotationLocked: Boolean,
        currentRotationLocked: Boolean
    ) {
        logBuffer.log(
            TAG,
            VERBOSE,
            {
                int1 = state
                bool1 = newRotationLocked
                bool2 = currentRotationLocked
            },
            {
                "onRotationLockStateChanged: " +
                    "state=$int1 [${int1.toDevicePostureString()}], " +
                    "newRotationLocked=$bool1, " +
                    "currentRotationLocked=$bool2"
            }
        )
    }

    fun logSaveNewRotationLockSetting(isRotationLocked: Boolean, state: Int) {
        logBuffer.log(
            TAG,
            VERBOSE,
            {
                bool1 = isRotationLocked
                int1 = state
            },
            { "saveNewRotationLockSetting: isRotationLocked=$bool1, state=$int1" }
        )
    }

    fun logUpdateDeviceState(currentState: Int, newState: Int) {
        logBuffer.log(
            TAG,
            VERBOSE,
            {
                int1 = currentState
                int2 = newState
            },
            {
                "updateDeviceState: " +
                    "current=$int1 [${int1.toDevicePostureString()}], " +
                    "new=$int2 [${int2.toDevicePostureString()}]"
            }
        )
    }

    fun readPersistedSetting(
        caller: String,
        state: Int,
        rotationLockSetting: Int,
        shouldBeLocked: Boolean,
        isLocked: Boolean
    ) {
        logBuffer.log(
            TAG,
            VERBOSE,
            {
                str1 = caller
                int1 = state
                int2 = rotationLockSetting
                bool1 = shouldBeLocked
                bool2 = isLocked
            },
            {
                "readPersistedSetting: " +
                    "caller=$str1, " +
                    "state=$int1 [${int1.toDevicePostureString()}], " +
                    "rotationLockSettingForState: ${int2.toRotationLockSettingString()}, " +
                    "shouldBeLocked=$bool1, " +
                    "isLocked=$bool2"
            }
        )
    }

    private fun Int.toDevicePostureString(): String {
        return when (this) {
            in foldedStates -> "Folded"
            in unfoldedStates -> "Unfolded"
            in halfFoldedStates -> "Half-Folded"
            -1 -> "Uninitialized"
            else -> "Unknown"
        }
    }
}

private fun Int.toRotationLockSettingString(): String {
    return when (this) {
        DEVICE_STATE_ROTATION_LOCK_IGNORED -> "IGNORED"
        DEVICE_STATE_ROTATION_LOCK_LOCKED -> "LOCKED"
        DEVICE_STATE_ROTATION_LOCK_UNLOCKED -> "UNLOCKED"
        else -> "Unknown"
    }
}

private const val TAG = "DSRotateLockSettingCon"
