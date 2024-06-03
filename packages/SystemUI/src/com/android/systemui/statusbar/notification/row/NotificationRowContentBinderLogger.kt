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

package com.android.systemui.statusbar.notification.row

import com.android.systemui.log.LogBuffer
import com.android.systemui.log.core.LogLevel
import com.android.systemui.log.dagger.NotifInflationLog
import com.android.systemui.statusbar.notification.collection.NotificationEntry
import com.android.systemui.statusbar.notification.logKey
import com.android.systemui.statusbar.notification.row.NotificationRowContentBinder.FLAG_CONTENT_VIEW_ALL
import com.android.systemui.statusbar.notification.row.NotificationRowContentBinder.FLAG_CONTENT_VIEW_CONTRACTED
import com.android.systemui.statusbar.notification.row.NotificationRowContentBinder.FLAG_CONTENT_VIEW_EXPANDED
import com.android.systemui.statusbar.notification.row.NotificationRowContentBinder.FLAG_CONTENT_VIEW_HEADS_UP
import com.android.systemui.statusbar.notification.row.NotificationRowContentBinder.FLAG_CONTENT_VIEW_PUBLIC
import com.android.systemui.statusbar.notification.row.NotificationRowContentBinder.FLAG_CONTENT_VIEW_SINGLE_LINE
import com.android.systemui.statusbar.notification.row.NotificationRowContentBinder.FLAG_GROUP_SUMMARY_HEADER
import com.android.systemui.statusbar.notification.row.NotificationRowContentBinder.FLAG_LOW_PRIORITY_GROUP_SUMMARY_HEADER
import com.android.systemui.statusbar.notification.row.NotificationRowContentBinder.InflationFlag
import javax.inject.Inject

class NotificationRowContentBinderLogger
@Inject
constructor(@NotifInflationLog private val buffer: LogBuffer) {
    fun logNotBindingRowWasRemoved(entry: NotificationEntry) {
        buffer.log(
            TAG,
            LogLevel.INFO,
            { str1 = entry.logKey },
            { "not inflating $str1: row was removed" }
        )
    }

    fun logBinding(entry: NotificationEntry, @InflationFlag flag: Int) {
        buffer.log(
            TAG,
            LogLevel.DEBUG,
            {
                str1 = entry.logKey
                int1 = flag
            },
            { "binding views ${flagToString(int1)} for $str1" }
        )
    }

    fun logCancelBindAbortedTask(entry: NotificationEntry) {
        buffer.log(
            TAG,
            LogLevel.INFO,
            { str1 = entry.logKey },
            { "aborted task to cancel binding $str1" }
        )
    }

    fun logUnbinding(entry: NotificationEntry, @InflationFlag flag: Int) {
        buffer.log(
            TAG,
            LogLevel.DEBUG,
            {
                str1 = entry.logKey
                int1 = flag
            },
            { "unbinding views ${flagToString(int1)} for $str1" }
        )
    }

    fun logAsyncTaskProgress(entry: NotificationEntry, progress: String) {
        buffer.log(
            TAG,
            LogLevel.DEBUG,
            {
                str1 = entry.logKey
                str2 = progress
            },
            { "async task for $str1: $str2" }
        )
    }

    fun logAsyncTaskException(entry: NotificationEntry, logContext: String, exception: Throwable) {
        buffer.log(
            TAG,
            LogLevel.DEBUG,
            {
                str1 = entry.logKey
                str2 = logContext
                str3 = exception.stackTraceToString()
            },
            { "async task for $str1 got exception $str2: $str3" }
        )
    }

    fun logInflateSingleLine(
        entry: NotificationEntry,
        @InflationFlag inflationFlags: Int,
        isConversation: Boolean
    ) {
        buffer.log(
            TAG,
            LogLevel.DEBUG,
            {
                str1 = entry.logKey
                int1 = inflationFlags
                bool1 = isConversation
            },
            {
                "inflateSingleLineView, inflationFlags: ${flagToString(int1)} for $str1, " +
                    "isConversation: $bool1"
            }
        )
    }

    companion object {
        fun flagToString(@InflationFlag flag: Int): String {
            if (flag == 0) {
                return "NONE"
            }
            if (flag == FLAG_CONTENT_VIEW_ALL) {
                return "ALL"
            }

            var l = mutableListOf<String>()
            if (flag and FLAG_CONTENT_VIEW_CONTRACTED != 0) {
                l.add("CONTRACTED")
            }
            if (flag and FLAG_CONTENT_VIEW_EXPANDED != 0) {
                l.add("EXPANDED")
            }
            if (flag and FLAG_CONTENT_VIEW_HEADS_UP != 0) {
                l.add("HEADS_UP")
            }
            if (flag and FLAG_CONTENT_VIEW_PUBLIC != 0) {
                l.add("PUBLIC")
            }
            if (flag and FLAG_CONTENT_VIEW_SINGLE_LINE != 0) {
                l.add("SINGLE_LINE")
            }
            if (flag and FLAG_GROUP_SUMMARY_HEADER != 0) {
                l.add("GROUP_SUMMARY_HEADER")
            }
            if (flag and FLAG_LOW_PRIORITY_GROUP_SUMMARY_HEADER != 0) {
                l.add("LOW_PRIORITY_GROUP_SUMMARY_HEADER")
            }
            return l.joinToString("|")
        }
    }
}

private const val TAG = "NotificationRowContentBinder"
