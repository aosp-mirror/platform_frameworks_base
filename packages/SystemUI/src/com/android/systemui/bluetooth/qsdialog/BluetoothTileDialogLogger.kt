/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.systemui.bluetooth.qsdialog

import android.bluetooth.BluetoothDevice
import com.android.systemui.log.LogBuffer
import com.android.systemui.log.core.LogLevel.DEBUG
import com.android.systemui.log.dagger.BluetoothTileDialogLog
import javax.inject.Inject

private const val TAG = "BluetoothTileDialogLog"

enum class BluetoothStateStage {
    USER_TOGGLED,
    BLUETOOTH_STATE_VALUE_SET,
    BLUETOOTH_STATE_CHANGE_RECEIVED
}

enum class DeviceFetchTrigger {
    FIRST_LOAD,
    BLUETOOTH_STATE_CHANGE_RECEIVED,
    BLUETOOTH_CALLBACK_RECEIVED
}

enum class JobStatus {
    FINISHED,
    CANCELLED
}

class BluetoothTileDialogLogger
@Inject
constructor(@BluetoothTileDialogLog private val logBuffer: LogBuffer) {

    fun logBluetoothState(stage: BluetoothStateStage, state: String) =
        logBuffer.log(
            TAG,
            DEBUG,
            {
                str1 = stage.toString()
                str2 = state
            },
            { "BluetoothState. stage=$str1 state=$str2" }
        )

    fun logDeviceClick(address: String, type: DeviceItemType) =
        logBuffer.log(
            TAG,
            DEBUG,
            {
                str1 = address
                str2 = type.toString()
            },
            { "DeviceClick. address=$str1 type=$str2" }
        )

    fun logActiveDeviceChanged(address: String?, profileId: Int) =
        logBuffer.log(
            TAG,
            DEBUG,
            {
                str1 = address
                int1 = profileId
            },
            { "ActiveDeviceChanged. address=$str1 profileId=$int1" }
        )

    fun logProfileConnectionStateChanged(address: String, state: String, profileId: Int) =
        logBuffer.log(
            TAG,
            DEBUG,
            {
                str1 = address
                str2 = state
                int1 = profileId
            },
            { "ProfileConnectionStateChanged. address=$str1 state=$str2 profileId=$int1" }
        )

    fun logDeviceFetch(status: JobStatus, trigger: DeviceFetchTrigger, duration: Long) =
        logBuffer.log(
            TAG,
            DEBUG,
            {
                str1 = status.toString()
                str2 = trigger.toString()
                long1 = duration
            },
            { "DeviceFetch. status=$str1 trigger=$str2 duration=$long1" }
        )

    fun logDeviceUiUpdate(duration: Long) =
        logBuffer.log(TAG, DEBUG, { long1 = duration }, { "DeviceUiUpdate. duration=$long1" })

    fun logDeviceClickInAudioSharingWhenEnabled(inAudioSharing: Boolean) {
        logBuffer.log(
            TAG,
            DEBUG,
            { str1 = inAudioSharing.toString() },
            { "DeviceClick. in audio sharing=$str1" }
        )
    }

    fun logConnectedLeByGroupId(map: Map<Int, List<BluetoothDevice>>) {
        logBuffer.log(TAG, DEBUG, { str1 = map.toString() }, { "ConnectedLeByGroupId. map=$str1" })
    }

    fun logLaunchSettingsCriteriaMatched(criteria: String, deviceItem: DeviceItem) {
        logBuffer.log(
            TAG,
            DEBUG,
            {
                str1 = criteria
                str2 = deviceItem.toString()
            },
            { "$str1. deviceItem=$str2" }
        )
    }
}
