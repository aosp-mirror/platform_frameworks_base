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

package com.android.systemui.media.controls.resume

import android.content.ComponentName
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.log.dagger.MediaBrowserLog
import com.android.systemui.plugins.log.LogBuffer
import com.android.systemui.plugins.log.LogLevel
import javax.inject.Inject

/** A logger for events in [ResumeMediaBrowser]. */
@SysUISingleton
class ResumeMediaBrowserLogger @Inject constructor(@MediaBrowserLog private val buffer: LogBuffer) {
    /** Logs that we've initiated a connection to a [android.media.browse.MediaBrowser]. */
    fun logConnection(componentName: ComponentName, reason: String) =
        buffer.log(
            TAG,
            LogLevel.DEBUG,
            {
                str1 = componentName.toShortString()
                str2 = reason
            },
            { "Connecting browser for component $str1 due to $str2" }
        )

    /** Logs that we've disconnected from a [android.media.browse.MediaBrowser]. */
    fun logDisconnect(componentName: ComponentName) =
        buffer.log(
            TAG,
            LogLevel.DEBUG,
            { str1 = componentName.toShortString() },
            { "Disconnecting browser for component $str1" }
        )

    /**
     * Logs that we received a [android.media.session.MediaController.Callback.onSessionDestroyed]
     * event.
     *
     * @param isBrowserConnected true if there's a currently connected
     * ```
     *     [android.media.browse.MediaBrowser] and false otherwise.
     * @param componentName
     * ```
     * the component name for the [ResumeMediaBrowser] that triggered this log.
     */
    fun logSessionDestroyed(isBrowserConnected: Boolean, componentName: ComponentName) =
        buffer.log(
            TAG,
            LogLevel.DEBUG,
            {
                bool1 = isBrowserConnected
                str1 = componentName.toShortString()
            },
            { "Session destroyed. Active browser = $bool1. Browser component = $str1." }
        )
}

private const val TAG = "MediaBrowser"
