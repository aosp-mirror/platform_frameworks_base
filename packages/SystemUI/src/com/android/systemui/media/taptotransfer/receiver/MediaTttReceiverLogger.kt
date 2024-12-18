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

package com.android.systemui.media.taptotransfer.receiver

import android.app.StatusBarManager
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.log.LogBuffer
import com.android.systemui.log.core.LogLevel
import com.android.systemui.media.taptotransfer.common.MediaTttLoggerUtils
import com.android.systemui.temporarydisplay.TemporaryViewLogger
import javax.inject.Inject

/** A logger for all events related to the media tap-to-transfer receiver experience. */
@SysUISingleton
class MediaTttReceiverLogger
@Inject
constructor(
    @MediaTttReceiverLogBuffer buffer: LogBuffer,
) : TemporaryViewLogger<ChipReceiverInfo>(buffer, TAG) {

    /** Logs a change in the chip state for the given [mediaRouteId]. */
    fun logStateChange(
        stateName: String,
        mediaRouteId: String,
        packageName: String?,
    ) {
        MediaTttLoggerUtils.logStateChange(buffer, TAG, stateName, mediaRouteId, packageName)
    }

    /** Logs an error in trying to update to [displayState]. */
    fun logStateChangeError(@StatusBarManager.MediaTransferReceiverState displayState: Int) {
        MediaTttLoggerUtils.logStateChangeError(buffer, TAG, displayState)
    }

    /** Logs that we couldn't find information for [packageName]. */
    fun logPackageNotFound(packageName: String) {
        MediaTttLoggerUtils.logPackageNotFound(buffer, TAG, packageName)
    }

    fun logRippleAnimationEnd(id: Int) {
        buffer.log(
            tag,
            LogLevel.DEBUG,
            { int1 = id },
            { "ripple animation for view with id: $int1 is ended" }
        )
    }

    companion object {
        private const val TAG = "MediaTttReceiver"
    }
}
