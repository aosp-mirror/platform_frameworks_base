/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.systemui.statusbar.notification.collection

import com.android.systemui.log.LogBuffer
import com.android.systemui.log.core.LogLevel
import com.android.systemui.log.dagger.NotifInflationLog
import com.android.systemui.statusbar.notification.InflationException
import com.android.systemui.statusbar.notification.collection.inflation.NotifInflater.Params
import com.android.systemui.statusbar.notification.logKey
import javax.inject.Inject

class NotifInflaterLogger @Inject constructor(@NotifInflationLog private val buffer: LogBuffer) {
    fun logInflatingViews(entry: NotificationEntry, params: Params) {
        buffer.log(
            TAG,
            LogLevel.DEBUG,
            {
                str1 = entry.logKey
                str2 = params.reason
            },
            { "inflating views for $str1: $str2" }
        )
    }

    fun logInflatedViews(entry: NotificationEntry) {
        buffer.log(TAG, LogLevel.DEBUG, { str1 = entry.logKey }, { "inflated views for $str1" })
    }

    fun logRebindingViews(entry: NotificationEntry, params: Params) {
        buffer.log(
            TAG,
            LogLevel.DEBUG,
            {
                str1 = entry.logKey
                str2 = params.reason
            },
            { "rebinding views for $str1: $str2" }
        )
    }

    fun logReboundViews(entry: NotificationEntry) {
        buffer.log(TAG, LogLevel.DEBUG, { str1 = entry.logKey }, { "rebound views for $str1" })
    }

    fun logInflationException(entry: NotificationEntry, exc: InflationException) {
        buffer.log(
            TAG,
            LogLevel.WARNING,
            {
                str1 = entry.logKey
                str2 = exc.stackTraceToString()
            },
            { "exception inflating views for $str1: $str2" }
        )
    }

    fun logAbortInflationAbortedTask(entry: NotificationEntry) {
        buffer.log(
            TAG,
            LogLevel.DEBUG,
            { str1 = entry.logKey },
            { "aborted task to abort inflation for $str1" }
        )
    }

    fun logReleasingViews(entry: NotificationEntry) {
        buffer.log(TAG, LogLevel.DEBUG, { str1 = entry.logKey }, { "aborting inflation for $str1" })
    }
}

private const val TAG = "NotifInflater"
