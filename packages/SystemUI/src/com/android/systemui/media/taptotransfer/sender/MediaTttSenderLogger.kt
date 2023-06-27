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

package com.android.systemui.media.taptotransfer.sender

import android.app.StatusBarManager
import com.android.internal.logging.InstanceId
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.log.LogBuffer
import com.android.systemui.log.core.LogLevel
import com.android.systemui.media.taptotransfer.common.MediaTttLoggerUtils
import javax.inject.Inject

/** A logger for all events related to the media tap-to-transfer sender experience. */
@SysUISingleton
class MediaTttSenderLogger
@Inject
constructor(
    @MediaTttSenderLogBuffer private val buffer: LogBuffer,
) {
    /** Logs a change in the chip state for the given [mediaRouteId]. */
    fun logStateChange(
        stateName: String,
        mediaRouteId: String,
        packageName: String?,
    ) {
        MediaTttLoggerUtils.logStateChange(buffer, TAG, stateName, mediaRouteId, packageName)
    }

    /** Logs an error in trying to update to [displayState]. */
    fun logStateChangeError(@StatusBarManager.MediaTransferSenderState displayState: Int) {
        MediaTttLoggerUtils.logStateChangeError(buffer, TAG, displayState)
    }

    /** Logs that we couldn't find information for [packageName]. */
    fun logPackageNotFound(packageName: String) {
        MediaTttLoggerUtils.logPackageNotFound(buffer, TAG, packageName)
    }

    /**
     * Logs an invalid sender state transition error in trying to update to [desiredState].
     *
     * @param currentState the previous state of the chip.
     * @param desiredState the new state of the chip.
     */
    fun logInvalidStateTransitionError(currentState: String, desiredState: String) {
        buffer.log(
            TAG,
            LogLevel.ERROR,
            {
                str1 = currentState
                str2 = desiredState
            },
            { "Cannot display state=$str2 after state=$str1; invalid transition" }
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
            TAG,
            LogLevel.DEBUG,
            {
                str1 = removalReason
                str2 = bypassReason
            },
            { "Chip removal requested due to $str1; however, removal was ignored because $str2" }
        )
    }

    /** Logs the current contents of the state map. */
    fun logStateMap(map: Map<String, Pair<InstanceId, ChipStateSender>>) {
        buffer.log(
            TAG,
            LogLevel.DEBUG,
            { str1 = map.toString() },
            { "Current sender states: $str1" }
        )
    }

    /** Logs that [id] has been removed from the state map due to [reason]. */
    fun logStateMapRemoval(id: String, reason: String) {
        buffer.log(
            TAG,
            LogLevel.DEBUG,
            {
                str1 = id
                str2 = reason
            },
            { "State removal: id=$str1 reason=$str2" }
        )
    }

    companion object {
        private const val TAG = "MediaTttSender"
    }
}
