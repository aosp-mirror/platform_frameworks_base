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
package com.android.systemui.volume.dialog.shared

import com.android.settingslib.volume.shared.model.RingerMode
import com.android.systemui.log.LogBuffer
import com.android.systemui.log.core.LogLevel
import com.android.systemui.log.dagger.VolumeLog
import com.android.systemui.volume.Events
import javax.inject.Inject

private const val TAG = "SysUI_VolumeDialog"

/** Logs events related to the Volume Panel. */
class VolumeDialogLogger @Inject constructor(@VolumeLog private val logBuffer: LogBuffer) {

    fun onShow(reason: Int) {
        logBuffer.log(
            TAG,
            LogLevel.DEBUG,
            { int1 = reason },
            { "Show: ${Events.SHOW_REASONS[int1]}" },
        )
    }

    fun onDismiss(reason: Int) {
        logBuffer.log(
            TAG,
            LogLevel.DEBUG,
            { int1 = reason },
            { "Dismiss: ${Events.DISMISS_REASONS[int1]}" },
        )
    }

    fun onCurrentRingerModeIsUnsupported(ringerMode: RingerMode) {
        logBuffer.log(
            TAG,
            LogLevel.DEBUG,
            { int1 = ringerMode.value },
            { "Current ringer mode: $int1, ringer mode is unsupported in ringer drawer options" },
        )
    }
}
