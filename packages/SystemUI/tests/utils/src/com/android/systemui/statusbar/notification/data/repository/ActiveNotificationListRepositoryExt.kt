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

package com.android.systemui.statusbar.notification.data.repository

import com.android.systemui.statusbar.notification.data.model.activeNotificationModel

/**
 * Make the repository hold [count] active notifications for testing. The keys of the notifications
 * are "0", "1", ..., (count - 1).toString(). The ranks are the same values in Int.
 */
fun ActiveNotificationListRepository.setActiveNotifs(count: Int) {
    this.activeNotifications.value =
        ActiveNotificationsStore.Builder()
            .apply {
                val rankingsMap = mutableMapOf<String, Int>()
                repeat(count) { i ->
                    val key = "$i"
                    addEntry(activeNotificationModel(key = key))
                    rankingsMap[key] = i
                }

                setRankingsMap(rankingsMap)
            }
            .build()
}
