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

package com.android.systemui.volume.panel.shared

import com.android.settingslib.volume.shared.model.AudioStream
import com.android.systemui.log.LogBuffer
import com.android.systemui.log.core.LogLevel
import com.android.systemui.log.dagger.VolumeLog
import com.android.systemui.volume.panel.dagger.scope.VolumePanelScope
import javax.inject.Inject

private const val TAG = "SysUI_VolumePanel"

/** Logs events related to the Volume Panel. */
@VolumePanelScope
class VolumePanelLogger @Inject constructor(@VolumeLog private val logBuffer: LogBuffer) {

    fun onSetVolumeRequested(audioStream: AudioStream, volume: Int) {
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

    fun onVolumeUpdateReceived(audioStream: AudioStream, volume: Int) {
        logBuffer.log(
            TAG,
            LogLevel.DEBUG,
            {
                str1 = audioStream.toString()
                int1 = volume
            },
            { "Volume update received: stream=$str1 volume=$int1" }
        )
    }
}
