/*
 * Copyright (C) 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 *
 */

package com.android.systemui.statusbar.notification.domain.interactor

import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.statusbar.notification.collection.render.NotifStats
import com.android.systemui.statusbar.notification.data.repository.ActiveNotificationListRepository
import com.android.systemui.statusbar.notification.shared.ActiveNotificationGroupModel
import com.android.systemui.statusbar.notification.shared.ActiveNotificationModel
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

class ActiveNotificationsInteractor
@Inject
constructor(
    private val repository: ActiveNotificationListRepository,
    @Background private val backgroundDispatcher: CoroutineDispatcher,
) {
    /**
     * Top level list of Notifications actively presented to the user in the notification stack, in
     * order.
     */
    val topLevelRepresentativeNotifications: Flow<List<ActiveNotificationModel>> =
        repository.activeNotifications
            .map { store ->
                store.renderList.map { key ->
                    val entry =
                        store[key]
                            ?: error(
                                "Could not find notification with key $key in active notif store."
                            )
                    when (entry) {
                        is ActiveNotificationGroupModel -> entry.summary
                        is ActiveNotificationModel -> entry
                    }
                }
            }
            .flowOn(backgroundDispatcher)

    /**
     * Flattened list of Notifications actively presented to the user in the notification stack, in
     * order.
     */
    val allRepresentativeNotifications: Flow<Map<String, ActiveNotificationModel>> =
        repository.activeNotifications.map { store -> store.individuals }

    /** Size of the flattened list of Notifications actively presented in the stack. */
    val allNotificationsCount: Flow<Int> =
        repository.activeNotifications.map { store -> store.individuals.size }

    /**
     * The same as [allNotificationsCount], but without flows, for easy access in synchronous code.
     */
    val allNotificationsCountValue: Int = repository.activeNotifications.value.individuals.size

    /** Are any notifications being actively presented in the notification stack? */
    val areAnyNotificationsPresent: Flow<Boolean> =
        repository.activeNotifications
            .map { it.renderList.isNotEmpty() }
            .distinctUntilChanged()
            .flowOn(backgroundDispatcher)

    /**
     * The same as [areAnyNotificationsPresent], but without flows, for easy access in synchronous
     * code.
     */
    val areAnyNotificationsPresentValue: Boolean
        get() = repository.activeNotifications.value.renderList.isNotEmpty()

    /**
     * Map of notification key to rank, where rank is the 0-based index of the notification in the
     * system server, meaning that in the unfiltered flattened list of notification entries. Used
     * for logging purposes.
     *
     * @see [com.android.systemui.statusbar.notification.stack.ui.view.NotificationStatsLogger].
     */
    val activeNotificationRanks: Flow<Map<String, Int>> =
        repository.activeNotifications.map { store -> store.rankingsMap }

    /** Are there are any notifications that can be cleared by the "Clear all" button? */
    val hasClearableNotifications: Flow<Boolean> =
        repository.notifStats
            .map { it.hasClearableAlertingNotifs || it.hasClearableSilentNotifs }
            .distinctUntilChanged()
            .flowOn(backgroundDispatcher)

    fun setNotifStats(notifStats: NotifStats) {
        repository.notifStats.value = notifStats
    }
}
