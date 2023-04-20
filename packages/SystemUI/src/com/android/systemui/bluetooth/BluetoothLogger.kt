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

package com.android.systemui.bluetooth

import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.log.dagger.BluetoothLog
import com.android.systemui.plugins.log.LogBuffer
import com.android.systemui.plugins.log.LogLevel
import javax.inject.Inject

/** Helper class for logging bluetooth events. */
@SysUISingleton
class BluetoothLogger @Inject constructor(@BluetoothLog private val logBuffer: LogBuffer) {
    fun logActiveDeviceChanged(address: String?, profileId: Int) =
        logBuffer.log(
            TAG,
            LogLevel.DEBUG,
            {
                str1 = address
                int1 = profileId
            },
            { "ActiveDeviceChanged. address=$str1 profileId=$int1" }
        )

    fun logDeviceConnectionStateChanged(address: String?, state: String) =
        logBuffer.log(
            TAG,
            LogLevel.DEBUG,
            {
                str1 = address
                str2 = state
            },
            { "DeviceConnectionStateChanged. address=$str1 state=$str2" }
        )

    fun logAclConnectionStateChanged(address: String, state: String) =
        logBuffer.log(
            TAG,
            LogLevel.DEBUG,
            {
                str1 = address
                str2 = state
            },
            { "AclConnectionStateChanged. address=$str1 state=$str2" }
        )

    fun logProfileConnectionStateChanged(address: String?, state: String, profileId: Int) =
        logBuffer.log(
            TAG,
            LogLevel.DEBUG,
            {
                str1 = address
                str2 = state
                int1 = profileId
            },
            { "ProfileConnectionStateChanged. address=$str1 state=$str2 profileId=$int1" }
        )

    fun logStateChange(state: String) =
        logBuffer.log(
            TAG,
            LogLevel.DEBUG,
            { str1 = state },
            { "BluetoothStateChanged. state=$str1" }
        )

    fun logBondStateChange(address: String, state: Int) =
        logBuffer.log(
            TAG,
            LogLevel.DEBUG,
            {
                str1 = address
                int1 = state
            },
            { "DeviceBondStateChanged. address=$str1 state=$int1" }
        )

    fun logDeviceAdded(address: String) =
        logBuffer.log(TAG, LogLevel.DEBUG, { str1 = address }, { "DeviceAdded. address=$str1" })

    fun logDeviceDeleted(address: String) =
        logBuffer.log(TAG, LogLevel.DEBUG, { str1 = address }, { "DeviceDeleted. address=$str1" })

    fun logDeviceAttributesChanged() =
        logBuffer.log(TAG, LogLevel.DEBUG, {}, { "DeviceAttributesChanged." })
}

private const val TAG = "BluetoothLog"
