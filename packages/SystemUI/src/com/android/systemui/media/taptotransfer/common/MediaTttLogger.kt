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

import com.android.systemui.plugins.log.LogBuffer
import com.android.systemui.plugins.log.LogLevel
import com.android.systemui.temporarydisplay.TemporaryViewInfo
import com.android.systemui.temporarydisplay.TemporaryViewLogger

/**
 * A logger for media tap-to-transfer events.
 *
 * @param deviceTypeTag the type of device triggering the logs -- "Sender" or "Receiver".
 *
 * TODO(b/245610654): We should de-couple the sender and receiver loggers, since they're vastly
 * different experiences.
 */
class MediaTttLogger<T : TemporaryViewInfo>(
    deviceTypeTag: String,
    buffer: LogBuffer
) : TemporaryViewLogger<T>(buffer, BASE_TAG + deviceTypeTag) {
    /** Logs a change in the chip state for the given [mediaRouteId]. */
    fun logStateChange(stateName: String, mediaRouteId: String, packageName: String?) {
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

    /**
     * Logs an error in trying to update to [displayState].
     *
     * [displayState] is either a [android.app.StatusBarManager.MediaTransferSenderState] or
     * a [android.app.StatusBarManager.MediaTransferReceiverState].
     */
    fun logStateChangeError(displayState: Int) {
        buffer.log(
            tag,
            LogLevel.ERROR,
            { int1 = displayState },
            { "Cannot display state=$int1; aborting" }
        )
    }

    /**
     * Logs an invalid sender state transition error in trying to update to [desiredState].
     *
     * @param currentState the previous state of the chip.
     * @param desiredState the new state of the chip.
     */
    fun logInvalidStateTransitionError(
        currentState: String,
        desiredState: String
    ) {
        buffer.log(
                tag,
                LogLevel.ERROR,
                {
                    str1 = currentState
                    str2 = desiredState
                },
                { "Cannot display state=$str2 after state=$str1; invalid transition" }
        )
    }

    /** Logs that we couldn't find information for [packageName]. */
    fun logPackageNotFound(packageName: String) {
        buffer.log(
            tag,
            LogLevel.DEBUG,
            { str1 = packageName },
            { "Package $str1 could not be found" }
        )
    }

    /**
     * Logs that a removal request has been bypassed (ignored).
     *
     * @param removalReason the reason that the chip removal was requested.
     * @param bypassReason the reason that the request was bypassed.
     */
    fun logRemovalBypass(removalReason: String, bypassReason: String) {
        buffer.log(
            tag,
            LogLevel.DEBUG,
            {
                str1 = removalReason
                str2 = bypassReason
            },
            { "Chip removal requested due to $str1; however, removal was ignored because $str2" })
    }
}

private const val BASE_TAG = "MediaTtt"
