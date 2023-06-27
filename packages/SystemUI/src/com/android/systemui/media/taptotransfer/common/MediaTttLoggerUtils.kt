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
import com.android.systemui.log.core.LogLevel

/** A helper for logging media tap-to-transfer events. */
object MediaTttLoggerUtils {
    fun logStateChange(
        buffer: LogBuffer,
        tag: String,
        stateName: String,
        mediaRouteId: String,
        packageName: String?,
    ) {
        buffer.log(
            tag,
            LogLevel.DEBUG,
            {
                str1 = stateName
                str2 = mediaRouteId
                str3 = packageName
            },
            { "State changed to $str1 for ID=$str2 package=$str3" }
        )
    }

    fun logStateChangeError(buffer: LogBuffer, tag: String, displayState: Int) {
        buffer.log(
            tag,
            LogLevel.ERROR,
            { int1 = displayState },
            { "Cannot display state=$int1; aborting" }
        )
    }

    fun logPackageNotFound(buffer: LogBuffer, tag: String, packageName: String) {
        buffer.log(
            tag,
            LogLevel.DEBUG,
            { str1 = packageName },
            { "Package $str1 could not be found" }
        )
    }
}
