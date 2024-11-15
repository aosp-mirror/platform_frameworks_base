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

package com.android.systemui.statusbar.notification.icon.ui.viewbinder

import com.android.systemui.display.domain.interactor.DisplayWindowPropertiesInteractor
import com.android.systemui.lifecycle.Activatable
import com.android.systemui.statusbar.StatusBarIconView
import com.android.systemui.statusbar.notification.collection.NotifCollection
import com.android.systemui.statusbar.notification.collection.NotifPipeline
import com.android.systemui.statusbar.notification.collection.NotificationEntry
import com.android.systemui.statusbar.notification.collection.notifcollection.NotifCollectionListener
import com.android.systemui.statusbar.notification.icon.IconManager
import com.android.systemui.statusbar.notification.icon.ui.viewbinder.NotificationIconContainerViewBinder.IconViewStore
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope

/** [IconViewStore] for the status bar on multiple displays. */
class ConnectedDisplaysStatusBarNotificationIconViewStore
@AssistedInject
constructor(
    @Assisted private val displayId: Int,
    private val notifCollection: NotifCollection,
    private val iconManager: IconManager,
    private val displayWindowPropertiesInteractor: DisplayWindowPropertiesInteractor,
    private val notifPipeline: NotifPipeline,
) : IconViewStore, Activatable {

    private val cachedIcons = ConcurrentHashMap<String, StatusBarIconView>()

    private val iconUpdateRequiredListener =
        object : IconManager.OnIconUpdateRequiredListener {
            override fun onIconUpdateRequired(entry: NotificationEntry) {
                val iconView = iconView(entry.key) ?: return
                iconManager.updateSbIcon(entry, iconView)
            }
        }

    private val notifCollectionListener =
        object : NotifCollectionListener {
            override fun onEntryRemoved(entry: NotificationEntry, reason: Int) {
                cachedIcons.remove(entry.key)
            }
        }

    override fun iconView(key: String): StatusBarIconView? {
        val entry = notifCollection.getEntry(key) ?: return null
        return cachedIcons.computeIfAbsent(key) {
            val context = displayWindowPropertiesInteractor.getForStatusBar(displayId).context
            iconManager.createSbIconView(context, entry)
        }
    }

    override suspend fun activate() = coroutineScope {
        start()
        try {
            awaitCancellation()
        } finally {
            stop()
        }
    }

    private fun start() {
        notifPipeline.addCollectionListener(notifCollectionListener)
        iconManager.addIconsUpdateListener(iconUpdateRequiredListener)
    }

    private fun stop() {
        notifPipeline.removeCollectionListener(notifCollectionListener)
        iconManager.removeIconsUpdateListener(iconUpdateRequiredListener)
    }

    @AssistedFactory
    interface Factory {
        fun create(displayId: Int): ConnectedDisplaysStatusBarNotificationIconViewStore
    }
}
