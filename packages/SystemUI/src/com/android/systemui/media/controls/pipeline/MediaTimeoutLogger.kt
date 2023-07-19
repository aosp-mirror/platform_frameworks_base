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

package com.android.systemui.media.controls.pipeline

import android.media.session.PlaybackState
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.log.dagger.MediaTimeoutListenerLog
import com.android.systemui.plugins.log.LogBuffer
import com.android.systemui.plugins.log.LogLevel
import javax.inject.Inject

private const val TAG = "MediaTimeout"

/** A buffered log for [MediaTimeoutListener] events */
@SysUISingleton
class MediaTimeoutLogger
@Inject
constructor(@MediaTimeoutListenerLog private val buffer: LogBuffer) {
    fun logReuseListener(key: String) =
        buffer.log(TAG, LogLevel.DEBUG, { str1 = key }, { "reuse listener: $str1" })

    fun logMigrateListener(oldKey: String?, newKey: String?, hadListener: Boolean) =
        buffer.log(
            TAG,
            LogLevel.DEBUG,
            {
                str1 = oldKey
                str2 = newKey
                bool1 = hadListener
            },
            { "migrate from $str1 to $str2, had listener? $bool1" }
        )

    fun logUpdateListener(key: String, wasPlaying: Boolean) =
        buffer.log(
            TAG,
            LogLevel.DEBUG,
            {
                str1 = key
                bool1 = wasPlaying
            },
            { "updating $str1, was playing? $bool1" }
        )

    fun logDelayedUpdate(key: String) =
        buffer.log(
            TAG,
            LogLevel.DEBUG,
            { str1 = key },
            { "deliver delayed playback state for $str1" }
        )

    fun logSessionDestroyed(key: String) =
        buffer.log(TAG, LogLevel.DEBUG, { str1 = key }, { "session destroyed $str1" })

    fun logPlaybackState(key: String, state: PlaybackState?) =
        buffer.log(
            TAG,
            LogLevel.VERBOSE,
            {
                str1 = key
                str2 = state?.toString()
            },
            { "state update: key=$str1 state=$str2" }
        )

    fun logStateCallback(key: String) =
        buffer.log(TAG, LogLevel.VERBOSE, { str1 = key }, { "dispatching state update for $key" })

    fun logScheduleTimeout(key: String, playing: Boolean, resumption: Boolean) =
        buffer.log(
            TAG,
            LogLevel.DEBUG,
            {
                str1 = key
                bool1 = playing
                bool2 = resumption
            },
            { "schedule timeout $str1, playing=$bool1 resumption=$bool2" }
        )

    fun logCancelIgnored(key: String) =
        buffer.log(TAG, LogLevel.DEBUG, { str1 = key }, { "cancellation already exists for $str1" })

    fun logTimeout(key: String) =
        buffer.log(TAG, LogLevel.DEBUG, { str1 = key }, { "execute timeout for $str1" })

    fun logTimeoutCancelled(key: String, reason: String) =
        buffer.log(
            TAG,
            LogLevel.VERBOSE,
            {
                str1 = key
                str2 = reason
            },
            { "media timeout cancelled for $str1, reason: $str2" }
        )
}
