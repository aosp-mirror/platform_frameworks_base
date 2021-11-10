/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.systemui.statusbar.notification.collection.legacy

import com.android.internal.statusbar.NotificationVisibility
import com.android.systemui.statusbar.notification.NotificationEntryManager
import com.android.systemui.statusbar.notification.collection.NotificationEntry
import com.android.systemui.statusbar.notification.collection.render.NotificationVisibilityProvider
import com.android.systemui.statusbar.notification.logging.NotificationLogger
import javax.inject.Inject

/** Legacy pipeline implementation for getting [NotificationVisibility]. */
class LegacyNotificationVisibilityProvider @Inject constructor(
    private val notifEntryManager: NotificationEntryManager
) : NotificationVisibilityProvider {
    override fun obtain(entry: NotificationEntry, visible: Boolean): NotificationVisibility {
        val count: Int = notifEntryManager.activeNotificationsCount
        val rank = entry.ranking.rank
        val hasRow = entry.row != null
        val location = NotificationLogger.getNotificationLocation(entry)
        return NotificationVisibility.obtain(entry.key, rank, count, visible && hasRow, location)
    }

    override fun obtain(key: String, visible: Boolean): NotificationVisibility {
        val entry: NotificationEntry? = notifEntryManager.getActiveNotificationUnfiltered(key)
        val count: Int = notifEntryManager.activeNotificationsCount
        val rank = entry?.ranking?.rank ?: -1
        val hasRow = entry?.row != null
        val location = NotificationLogger.getNotificationLocation(entry)
        return NotificationVisibility.obtain(key, rank, count, visible && hasRow, location)
    }

    override fun getLocation(key: String): NotificationVisibility.NotificationLocation =
            NotificationLogger.getNotificationLocation(
                    notifEntryManager.getActiveNotificationUnfiltered(key))
}