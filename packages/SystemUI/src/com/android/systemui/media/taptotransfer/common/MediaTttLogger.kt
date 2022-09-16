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

package com.android.systemui.media.taptotransfer.common

import com.android.systemui.log.LogBuffer
import com.android.systemui.log.LogLevel

/**
 * A logger for media tap-to-transfer events.
 *
 * @property deviceTypeTag the type of device triggering the logs -- "Sender" or "Receiver".
 */
class MediaTttLogger(
    private val deviceTypeTag: String,
    private val buffer: LogBuffer
){
    private val bufferTag = BASE_TAG + deviceTypeTag

    /** Logs a change in the chip state for the given [mediaRouteId]. */
    fun logStateChange(stateName: String, mediaRouteId: String, packageName: String?) {
        buffer.log(
            bufferTag,
            LogLevel.DEBUG,
            {
                str1 = stateName
                str2 = mediaRouteId
                str3 = packageName
            },
            { "State changed to $str1 for ID=$str2 package=$str3" }
        )
    }

    /** Logs that we removed the chip for the given [reason]. */
    fun logChipRemoval(reason: String) {
        buffer.log(
            bufferTag,
            LogLevel.DEBUG,
            { str1 = reason },
            { "Chip removed due to $str1" }
        )
    }

    /** Logs that we couldn't find information for [packageName]. */
    fun logPackageNotFound(packageName: String) {
        buffer.log(
            bufferTag,
            LogLevel.DEBUG,
            { str1 = packageName },
            { "Package $str1 could not be found" }
        )
    }
}

private const val BASE_TAG = "MediaTtt"
