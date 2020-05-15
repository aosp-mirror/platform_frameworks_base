/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.systemui.statusbar.notification.stack

import com.android.systemui.log.LogBuffer
import com.android.systemui.log.LogLevel
import com.android.systemui.log.dagger.NotificationSectionLog
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "NotifSections"

@Singleton
class NotificationSectionsLogger @Inject constructor(
    @NotificationSectionLog private val logBuffer: LogBuffer
) {

    fun logStartSectionUpdate(reason: String) = logBuffer.log(
            TAG,
            LogLevel.DEBUG,
            { str1 = reason },
            { "Updating section boundaries: $reason" }
    )

    fun logIncomingHeader(position: Int) = logPosition(position, "INCOMING HEADER")
    fun logMediaControls(position: Int) = logPosition(position, "MEDIA CONTROLS")
    fun logConversationsHeader(position: Int) = logPosition(position, "CONVERSATIONS HEADER")
    fun logAlertingHeader(position: Int) = logPosition(position, "ALERTING HEADER")
    fun logSilentHeader(position: Int) = logPosition(position, "SILENT HEADER")

    fun logOther(position: Int, clazz: Class<*>) = logBuffer.log(
            TAG,
            LogLevel.DEBUG,
            {
                int1 = position
                str1 = clazz.name
            },
            { "$int1: other ($str1)" }
    )

    fun logHeadsUp(position: Int) = logPosition(position, "Heads Up")
    fun logConversation(position: Int) = logPosition(position, "Conversation")
    fun logAlerting(position: Int) = logPosition(position, "Alerting")
    fun logSilent(position: Int) = logPosition(position, "Silent")
    fun logForegroundService(position: Int) = logPosition(position, "Foreground Service")

    fun logStr(str: String) = logBuffer.log(TAG, LogLevel.DEBUG, { str1 = str }, { "$str1" })

    private fun logPosition(position: Int, label: String) = logBuffer.log(
            TAG,
            LogLevel.DEBUG,
            {
                int1 = position
                str1 = label
            },
            { "$int1: $str1" }
    )
}
