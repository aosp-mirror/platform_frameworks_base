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

package com.android.systemui.media.controls.ui

import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.log.LogBuffer
import com.android.systemui.log.core.LogLevel
import com.android.systemui.log.dagger.MediaViewLog
import javax.inject.Inject

private const val TAG = "MediaView"

/** A buffered log for media view events that are too noisy for regular logging */
@SysUISingleton
class MediaViewLogger @Inject constructor(@MediaViewLog private val buffer: LogBuffer) {
    fun logMediaSize(reason: String, width: Int, height: Int) {
        buffer.log(
            TAG,
            LogLevel.DEBUG,
            {
                str1 = reason
                int1 = width
                int2 = height
            },
            { "size ($str1): $int1 x $int2" }
        )
    }

    fun logMediaLocation(reason: String, startLocation: Int, endLocation: Int) {
        buffer.log(
            TAG,
            LogLevel.DEBUG,
            {
                str1 = reason
                int1 = startLocation
                int2 = endLocation
            },
            { "location ($str1): $int1 -> $int2" }
        )
    }

    fun logMediaHostAttachment(host: Int) {
        buffer.log(TAG, LogLevel.DEBUG, { int1 = host }, { "Host (updateHostAttachment): $int1" })
    }
}
