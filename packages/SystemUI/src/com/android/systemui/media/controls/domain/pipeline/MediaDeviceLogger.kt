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

package com.android.systemui.media.controls.domain.pipeline

import android.media.session.MediaController
import com.android.settingslib.media.MediaDevice
import com.android.systemui.log.LogBuffer
import com.android.systemui.log.core.LogLevel
import com.android.systemui.log.dagger.MediaDeviceLog
import com.android.systemui.media.controls.shared.model.MediaDeviceData
import javax.inject.Inject

/** A [LogBuffer] for media device changes */
class MediaDeviceLogger @Inject constructor(@MediaDeviceLog private val buffer: LogBuffer) {

    fun logBroadcastEvent(event: String, reason: Int, broadcastId: Int) {
        buffer.log(
            TAG,
            LogLevel.DEBUG,
            {
                str1 = event
                int1 = reason
                int2 = broadcastId
            },
            { "$str1, reason = $int1, broadcastId = $int2" }
        )
    }

    fun logBroadcastEvent(event: String, reason: Int) {
        buffer.log(
            TAG,
            LogLevel.DEBUG,
            {
                str1 = event
                int1 = reason
            },
            { "$str1, reason = $int1" }
        )
    }

    fun logBroadcastMetadataChanged(broadcastId: Int, metadata: String) {
        buffer.log(
            TAG,
            LogLevel.DEBUG,
            {
                int1 = broadcastId
                str1 = metadata
            },
            { "onBroadcastMetadataChanged, broadcastId = $int1, metadata = $str1" }
        )
    }

    fun logNewDeviceName(name: String?) {
        buffer.log(TAG, LogLevel.DEBUG, { str1 = name }, { "New device name $str1" })
    }

    fun logLocalDevice(sassDevice: MediaDeviceData?, connectedDevice: MediaDeviceData?) {
        buffer.log(
            TAG,
            LogLevel.DEBUG,
            {
                str1 = sassDevice?.name?.toString()
                str2 = connectedDevice?.name?.toString()
            },
            { "Local device: $str1 or $str2" }
        )
    }

    fun logRemoteDevice(routingSessionName: CharSequence?, connectedDevice: MediaDeviceData?) {
        buffer.log(
            TAG,
            LogLevel.DEBUG,
            {
                str1 = routingSessionName?.toString()
                str2 = connectedDevice?.name?.toString()
            },
            { "Remote device: $str1 or $str2 or unknown" }
        )
    }

    fun logDeviceName(
        device: MediaDevice?,
        controller: MediaController?,
        routingSessionName: CharSequence?,
        selectedRouteName: CharSequence?
    ) {
        buffer.log(
            TAG,
            LogLevel.DEBUG,
            {
                str1 = "device $device, controller: $controller"
                str2 = routingSessionName?.toString()
                str3 = selectedRouteName?.toString()
            },
            { "$str1, routingSession $str2 or selected route $str3" }
        )
    }

    companion object {
        private const val TAG = "MediaDeviceLog"
    }
}
