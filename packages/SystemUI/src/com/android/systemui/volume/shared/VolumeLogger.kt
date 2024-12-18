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

package com.android.systemui.volume.shared

import com.android.settingslib.volume.shared.AudioLogger
import com.android.settingslib.volume.shared.AudioSharingLogger
import com.android.settingslib.volume.shared.model.AudioStream
import com.android.settingslib.volume.shared.model.AudioStreamModel
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.log.LogBuffer
import com.android.systemui.log.core.LogLevel
import com.android.systemui.log.dagger.VolumeLog
import javax.inject.Inject

private const val TAG = "SysUI_Volume"

/** Logs general System UI volume events. */
@SysUISingleton
class VolumeLogger @Inject constructor(@VolumeLog private val logBuffer: LogBuffer) :
    AudioLogger, AudioSharingLogger {

    override fun onSetVolumeRequested(audioStream: AudioStream, volume: Int) {
        logBuffer.log(
            TAG,
            LogLevel.DEBUG,
            {
                str1 = audioStream.toString()
                int1 = volume
            },
            { "Set volume: stream=$str1 volume=$int1" }
        )
    }

    override fun onVolumeUpdateReceived(audioStream: AudioStream, model: AudioStreamModel) {
        logBuffer.log(
            TAG,
            LogLevel.DEBUG,
            {
                str1 = audioStream.toString()
                int1 = model.volume
            },
            { "Volume update received: stream=$str1 volume=$int1" }
        )
    }

    override fun onAudioSharingStateChanged(state: Boolean) {
        logBuffer.log(
            TAG,
            LogLevel.DEBUG,
            { bool1 = state },
            { "Audio sharing state update: state=$bool1" }
        )
    }

    override fun onSecondaryGroupIdChanged(groupId: Int) {
        logBuffer.log(
            TAG,
            LogLevel.DEBUG,
            { int1 = groupId },
            { "Secondary group id in audio sharing update: groupId=$int1" }
        )
    }

    override fun onVolumeMapChanged(map: Map<Int, Int>) {
        logBuffer.log(
            TAG,
            LogLevel.DEBUG,
            { str1 = map.toString() },
            { "Volume map update: map=$str1" }
        )
    }

    override fun onSetDeviceVolumeRequested(volume: Int) {
        logBuffer.log(TAG, LogLevel.DEBUG, { int1 = volume }, { "Set device volume: volume=$int1" })
    }
}
