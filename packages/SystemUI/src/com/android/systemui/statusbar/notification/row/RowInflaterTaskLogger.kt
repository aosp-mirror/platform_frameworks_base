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

package com.android.systemui.statusbar.notification.row

import com.android.systemui.log.LogBuffer
import com.android.systemui.log.core.LogLevel
import com.android.systemui.log.dagger.NotifInflationLog
import com.android.systemui.statusbar.notification.collection.NotificationEntry
import com.android.systemui.statusbar.notification.logKey
import javax.inject.Inject

class RowInflaterTaskLogger @Inject constructor(@NotifInflationLog private val buffer: LogBuffer) {
    fun logInflateStart(entry: NotificationEntry) {
        buffer.log(
            TAG,
            LogLevel.DEBUG,
            { str1 = entry.logKey },
            { "started row inflation for $str1" }
        )
    }

    fun logCreatedRow(entry: NotificationEntry, elapsedMs: Long) {
        buffer.log(
            TAG,
            LogLevel.DEBUG,
            {
                str1 = entry.logKey
                long1 = elapsedMs
            },
            { "created row in $long1 ms for $str1" }
        )
    }

    fun logInflateFinish(entry: NotificationEntry, elapsedMs: Long, cancelled: Boolean) {
        buffer.log(
            TAG,
            LogLevel.DEBUG,
            {
                str1 = entry.logKey
                long1 = elapsedMs
                bool1 = cancelled
            },
            { "finished ${if (bool1) "cancelled " else ""}row inflation in $long1 ms for $str1" }
        )
    }
}

private const val TAG = "RowInflaterTask"
