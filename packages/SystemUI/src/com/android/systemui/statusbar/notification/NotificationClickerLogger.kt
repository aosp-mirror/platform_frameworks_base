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

package com.android.systemui.statusbar.notification

import com.android.systemui.log.LogBuffer
import com.android.systemui.log.LogLevel
import com.android.systemui.log.dagger.NotifInteractionLog
import com.android.systemui.statusbar.notification.collection.NotificationEntry
import javax.inject.Inject

class NotificationClickerLogger @Inject constructor(
    @NotifInteractionLog private val buffer: LogBuffer
) {
    fun logOnClick(entry: NotificationEntry) {
        buffer.log(TAG, LogLevel.DEBUG, {
            str1 = entry.key
            str2 = entry.ranking.channel.id
        }, {
            "CLICK $str1 (channel=$str2)"
        })
    }

    fun logMenuVisible(entry: NotificationEntry) {
        buffer.log(TAG, LogLevel.DEBUG, {
            str1 = entry.key
        }, {
            "Ignoring click on $str1; menu is visible"
        })
    }

    fun logParentMenuVisible(entry: NotificationEntry) {
        buffer.log(TAG, LogLevel.DEBUG, {
            str1 = entry.key
        }, {
            "Ignoring click on $str1; parent menu is visible"
        })
    }

    fun logChildrenExpanded(entry: NotificationEntry) {
        buffer.log(TAG, LogLevel.DEBUG, {
            str1 = entry.key
        }, {
            "Ignoring click on $str1; children are expanded"
        })
    }

    fun logGutsExposed(entry: NotificationEntry) {
        buffer.log(TAG, LogLevel.DEBUG, {
            str1 = entry.key
        }, {
            "Ignoring click on $str1; guts are exposed"
        })
    }
}

private const val TAG = "NotificationClicker"
