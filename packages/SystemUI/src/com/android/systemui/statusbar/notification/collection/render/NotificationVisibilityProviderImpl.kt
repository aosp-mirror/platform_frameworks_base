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

package com.android.systemui.statusbar.notification.collection.render

import com.android.internal.statusbar.NotificationVisibility
import com.android.systemui.statusbar.notification.collection.NotifPipeline
import com.android.systemui.statusbar.notification.collection.NotificationEntry
import com.android.systemui.statusbar.notification.logging.NotificationLogger
import javax.inject.Inject

/** New pipeline implementation for getting [NotificationVisibility]. */
class NotificationVisibilityProviderImpl @Inject constructor(
    private val notifPipeline: NotifPipeline
) : NotificationVisibilityProvider {
    override fun obtain(entry: NotificationEntry, visible: Boolean): NotificationVisibility {
        val count: Int = notifPipeline.getShadeListCount()
        val rank = entry.ranking.rank
        val hasRow = entry.row != null
        val location = NotificationLogger.getNotificationLocation(entry)
        return NotificationVisibility.obtain(entry.key, rank, count, visible && hasRow, location)
    }

    override fun obtain(key: String, visible: Boolean): NotificationVisibility =
        notifPipeline.getEntry(key)?.let { return obtain(it, visible) }
            ?: NotificationVisibility.obtain(key, -1, notifPipeline.getShadeListCount(), false)

    override fun getLocation(key: String): NotificationVisibility.NotificationLocation =
            NotificationLogger.getNotificationLocation(notifPipeline.getEntry(key))
}