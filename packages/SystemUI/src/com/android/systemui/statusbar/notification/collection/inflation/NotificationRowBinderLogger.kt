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

package com.android.systemui.statusbar.notification.collection.inflation

import com.android.systemui.log.LogBuffer
import com.android.systemui.log.core.LogLevel
import com.android.systemui.log.dagger.NotifInflationLog
import com.android.systemui.statusbar.notification.collection.NotificationEntry
import com.android.systemui.statusbar.notification.collection.inflation.NotifInflater.Params
import com.android.systemui.statusbar.notification.logKey
import javax.inject.Inject

class NotificationRowBinderLogger
@Inject
constructor(@NotifInflationLog private val buffer: LogBuffer) {
    fun logCreatingRow(entry: NotificationEntry, params: Params) {
        buffer.log(
            TAG,
            LogLevel.DEBUG,
            {
                str1 = entry.logKey
                str2 = params.reason
            },
            { "creating row for $str1: $str2" }
        )
    }

    fun logInflatingRow(entry: NotificationEntry) {
        buffer.log(TAG, LogLevel.DEBUG, { str1 = entry.logKey }, { "inflating row for $str1" })
    }

    fun logInflatedRow(entry: NotificationEntry) {
        buffer.log(TAG, LogLevel.DEBUG, { str1 = entry.logKey }, { "inflated row for $str1" })
    }

    fun logUpdatingRow(entry: NotificationEntry, params: Params) {
        buffer.log(
            TAG,
            LogLevel.DEBUG,
            {
                str1 = entry.logKey
                str2 = params.reason
            },
            { "updating row for $str1: $str2" }
        )
    }

    fun logReleasingViews(entry: NotificationEntry) {
        buffer.log(TAG, LogLevel.DEBUG, { str1 = entry.logKey }, { "releasing views for $str1" })
    }

    fun logNotReleasingViewsRowDoesntExist(entry: NotificationEntry) {
        buffer.log(
            TAG,
            LogLevel.DEBUG,
            { str1 = entry.logKey },
            { "not releasing views for $str1: row doesn't exist" }
        )
    }

    fun logRequestingRebind(entry: NotificationEntry, params: Params) {
        buffer.log(
            TAG,
            LogLevel.DEBUG,
            {
                str1 = entry.key
                str2 = params.reason
            },
            { "requesting rebind for $str1: $str2" }
        )
    }

    fun logRebindComplete(entry: NotificationEntry) {
        buffer.log(TAG, LogLevel.DEBUG, { str1 = entry.key }, { "rebind complete for $str1" })
    }
}

private const val TAG = "NotificationRowBinder"
