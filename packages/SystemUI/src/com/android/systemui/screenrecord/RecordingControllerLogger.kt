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

package com.android.systemui.screenrecord

import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.log.LogBuffer
import com.android.systemui.log.core.LogLevel
import javax.inject.Inject

/** Helper class for logging events to [RecordingControllerLog] from Java. */
@SysUISingleton
class RecordingControllerLogger
@Inject
constructor(
    @RecordingControllerLog private val logger: LogBuffer,
) {
    fun logStateUpdated(isRecording: Boolean) =
        logger.log(
            TAG,
            LogLevel.DEBUG,
            { bool1 = isRecording },
            { "Updating state. isRecording=$bool1" },
        )

    fun logIntentStateUpdated(isRecording: Boolean) =
        logger.log(
            TAG,
            LogLevel.DEBUG,
            { bool1 = isRecording },
            { "Update intent has state. isRecording=$bool1" },
        )

    fun logIntentMissingState() =
        logger.log(TAG, LogLevel.ERROR, {}, { "Received update intent with no state" })

    fun logSentStartIntent() = logger.log(TAG, LogLevel.DEBUG, {}, { "Sent start intent" })

    fun logPendingIntentCancelled(e: Exception) =
        logger.log(TAG, LogLevel.ERROR, {}, { "Pending intent was cancelled" }, e)

    fun logCountdownCancelled() =
        logger.log(TAG, LogLevel.DEBUG, {}, { "Record countdown cancelled" })

    fun logCountdownCancelErrorNoTimer() =
        logger.log(TAG, LogLevel.ERROR, {}, { "Couldn't cancel countdown because timer was null" })

    fun logRecordingStopped() = logger.log(TAG, LogLevel.DEBUG, {}, { "Stopping recording" })

    fun logRecordingStopErrorNoStopIntent() =
        logger.log(
            TAG,
            LogLevel.ERROR,
            {},
            { "Couldn't stop recording because stop intent was null" },
        )

    fun logRecordingStopError(e: Exception) =
        logger.log(TAG, LogLevel.DEBUG, {}, { "Couldn't stop recording" }, e)

    companion object {
        private const val TAG = "RecordingController"
    }
}
