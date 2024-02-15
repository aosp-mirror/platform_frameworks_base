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

package com.android.systemui.statusbar.notification.collection.coordinator

import com.android.systemui.statusbar.notification.collection.NotifPipeline
import com.android.systemui.statusbar.notification.collection.NotificationEntry
import com.android.systemui.statusbar.notification.collection.coordinator.dagger.CoordinatorScope
import com.android.systemui.statusbar.notification.collection.notifcollection.NotifCollectionListener
import com.android.systemui.statusbar.notification.shared.NotificationsLiveDataStoreRefactor
import com.android.systemui.statusbar.notification.stack.ui.view.NotificationStatsLogger
import java.util.Optional
import javax.inject.Inject

@CoordinatorScope
class NotificationStatsLoggerCoordinator
@Inject
constructor(private val loggerOptional: Optional<NotificationStatsLogger>) : Coordinator {

    private val collectionListener =
        object : NotifCollectionListener {
            override fun onEntryUpdated(entry: NotificationEntry) {
                super.onEntryUpdated(entry)
                loggerOptional.ifPresent { it.onNotificationUpdated(entry.key) }
            }

            override fun onEntryRemoved(entry: NotificationEntry, reason: Int) {
                super.onEntryRemoved(entry, reason)
                loggerOptional.ifPresent { it.onNotificationRemoved(entry.key) }
            }
        }
    override fun attach(pipeline: NotifPipeline) {
        if (NotificationsLiveDataStoreRefactor.isUnexpectedlyInLegacyMode()) {
            return
        }
        pipeline.addCollectionListener(collectionListener)
    }
}
