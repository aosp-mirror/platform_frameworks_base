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

package com.android.systemui.statusbar.notification.stack

import com.android.systemui.log.LogBuffer
import com.android.systemui.log.LogLevel
import com.android.systemui.log.dagger.NotificationRenderLog
import com.android.systemui.statusbar.notification.collection.NotificationEntry
import com.android.systemui.statusbar.notification.logKey
import javax.inject.Inject

class NotificationChildrenContainerLogger
@Inject
constructor(@NotificationRenderLog private val notificationRenderBuffer: LogBuffer) {
    fun addTransientRow(
        childEntry: NotificationEntry,
        containerEntry: NotificationEntry,
        index: Int
    ) {
        notificationRenderBuffer.log(
            TAG,
            LogLevel.INFO,
            {
                str1 = childEntry.logKey
                str2 = containerEntry.logKey
                int1 = index
            },
            { "addTransientRow: childKey: $str1 -- containerKey: $str2 -- index: $int1" }
        )
    }

    fun removeTransientRow(
        childEntry: NotificationEntry,
        containerEntry: NotificationEntry,
    ) {
        notificationRenderBuffer.log(
            TAG,
            LogLevel.INFO,
            {
                str1 = childEntry.logKey
                str2 = containerEntry.logKey
            },
            { "removeTransientRow: childKey: $str1 -- containerKey: $str2" }
        )
    }

    companion object {
        private const val TAG = "NotifChildrenContainer"
    }
}
