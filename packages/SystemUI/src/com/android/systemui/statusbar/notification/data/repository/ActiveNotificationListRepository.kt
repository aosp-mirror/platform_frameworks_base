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

import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.statusbar.notification.collection.render.NotifStats
import com.android.systemui.statusbar.notification.data.repository.ActiveNotificationsStore.Key
import com.android.systemui.statusbar.notification.shared.ActiveNotificationEntryModel
import com.android.systemui.statusbar.notification.shared.ActiveNotificationGroupModel
import com.android.systemui.statusbar.notification.shared.ActiveNotificationModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Repository of "active" notifications in the notification list.
 *
 * This repository serves as the boundary between the
 * [com.android.systemui.statusbar.notification.collection.NotifPipeline] and the modern
 * notifications presentation codebase.
 */
@SysUISingleton
class ActiveNotificationListRepository @Inject constructor() {
    /** Notifications actively presented to the user in the notification list. */
    val activeNotifications = MutableStateFlow(ActiveNotificationsStore())

    /** Are any already-seen notifications currently filtered out of the active list? */
    val hasFilteredOutSeenNotifications = MutableStateFlow(false)

    /** Stats about the list of notifications attached to the shade */
    val notifStats = MutableStateFlow(NotifStats.empty)

    /** The key of the top ongoing notification */
    val topOngoingNotificationKey = MutableStateFlow<String?>(null)

    /** The key of the top unseen notification */
    val topUnseenNotificationKey = MutableStateFlow<String?>(null)
}

/** Represents the notification list, comprised of groups and individual notifications. */
data class ActiveNotificationsStore(
    /** Notification groups, stored by key. */
    val groups: Map<String, ActiveNotificationGroupModel> = emptyMap(),
    /** All individual notifications, including top-level and group children, stored by key. */
    val individuals: Map<String, ActiveNotificationModel> = emptyMap(),
    /**
     * Ordered top-level list of entries in the notification list (either groups or individual),
     * represented as [Key]s. The associated [ActiveNotificationEntryModel] can be retrieved by
     * invoking [get].
     */
    val renderList: List<Key> = emptyList(),

    /**
     * Map of notification key to rank, where rank is the 0-based index of the notification on the
     * system server, meaning that in the unfiltered flattened list of notification entries.
     */
    val rankingsMap: Map<String, Int> = emptyMap()
) {
    operator fun get(key: Key): ActiveNotificationEntryModel? {
        return when (key) {
            is Key.Group -> groups[key.key]
            is Key.Individual -> individuals[key.key]
        }
    }

    /** Unique key identifying an [ActiveNotificationEntryModel] in the store. */
    sealed class Key {
        data class Individual(val key: String) : Key()

        data class Group(val key: String) : Key()
    }

    /** Mutable builder for an [ActiveNotificationsStore]. */
    class Builder {
        private val groups = mutableMapOf<String, ActiveNotificationGroupModel>()
        private val individuals = mutableMapOf<String, ActiveNotificationModel>()
        private val renderList = mutableListOf<Key>()
        private var rankingsMap: Map<String, Int> = emptyMap()

        fun build() = ActiveNotificationsStore(groups, individuals, renderList, rankingsMap)

        fun addEntry(entry: ActiveNotificationEntryModel) {
            when (entry) {
                is ActiveNotificationModel -> addIndividualNotif(entry)
                is ActiveNotificationGroupModel -> addNotifGroup(entry)
            }
        }

        fun addIndividualNotif(notif: ActiveNotificationModel) {
            renderList.add(Key.Individual(notif.key))
            individuals[notif.key] = notif
        }

        fun addNotifGroup(group: ActiveNotificationGroupModel) {
            renderList.add(Key.Group(group.key))
            groups[group.key] = group
            individuals[group.summary.key] = group.summary
            group.children.forEach { individuals[it.key] = it }
        }

        fun setRankingsMap(map: Map<String, Int>) {
            rankingsMap = map.toMap()
        }
    }
}
