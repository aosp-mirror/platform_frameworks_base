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

package com.android.systemui.statusbar.notification.collection.provider

import com.android.internal.statusbar.NotificationVisibility
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.statusbar.notification.collection.NotifLiveDataStore
import com.android.systemui.statusbar.notification.collection.NotificationEntry
import com.android.systemui.statusbar.notification.collection.notifcollection.CommonNotifCollection
import com.android.systemui.statusbar.notification.collection.render.NotificationVisibilityProvider
import com.android.systemui.statusbar.notification.domain.interactor.ActiveNotificationsInteractor
import com.android.systemui.statusbar.notification.logging.NotificationLogger
import com.android.systemui.statusbar.notification.shared.NotificationsLiveDataStoreRefactor
import javax.inject.Inject

/** pipeline-agnostic implementation for getting [NotificationVisibility]. */
@SysUISingleton
class NotificationVisibilityProviderImpl
@Inject
constructor(
    private val activeNotificationsInteractor: ActiveNotificationsInteractor,
    private val notifDataStore: NotifLiveDataStore,
    private val notifCollection: CommonNotifCollection
) : NotificationVisibilityProvider {

    override fun obtain(entry: NotificationEntry, visible: Boolean): NotificationVisibility {
        val count: Int = getCount()
        val rank = entry.ranking.rank
        val hasRow = entry.row != null
        val location = NotificationLogger.getNotificationLocation(entry)
        return NotificationVisibility.obtain(entry.key, rank, count, visible && hasRow, location)
    }

    override fun obtain(key: String, visible: Boolean): NotificationVisibility =
        notifCollection.getEntry(key)?.let { return obtain(it, visible) }
            ?: NotificationVisibility.obtain(key, -1, getCount(), false)

    override fun getLocation(key: String): NotificationVisibility.NotificationLocation =
        NotificationLogger.getNotificationLocation(notifCollection.getEntry(key))

    private fun getCount() =
        if (NotificationsLiveDataStoreRefactor.isEnabled) {
            activeNotificationsInteractor.allNotificationsCountValue
        } else {
            notifDataStore.activeNotifCount.value
        }
}
