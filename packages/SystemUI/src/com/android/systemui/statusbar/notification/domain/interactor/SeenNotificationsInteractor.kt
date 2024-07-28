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

package com.android.systemui.statusbar.notification.domain.interactor

import android.os.UserHandle
import android.provider.Settings
import android.util.IndentingPrintWriter
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.statusbar.notification.collection.NotificationEntry
import com.android.systemui.statusbar.notification.data.repository.ActiveNotificationListRepository
import com.android.systemui.statusbar.notification.shared.NotificationMinimalismPrototype
import com.android.systemui.util.printSection
import com.android.systemui.util.settings.SecureSettings
import com.android.systemui.util.settings.SettingsProxyExt.observerFlow
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart

/** Interactor for business logic associated with the notification stack. */
@SysUISingleton
class SeenNotificationsInteractor
@Inject
constructor(
    @Background private val bgDispatcher: CoroutineDispatcher,
    private val notificationListRepository: ActiveNotificationListRepository,
    private val secureSettings: SecureSettings,
) {
    /** Are any already-seen notifications currently filtered out of the shade? */
    val hasFilteredOutSeenNotifications: StateFlow<Boolean> =
        notificationListRepository.hasFilteredOutSeenNotifications

    /** Set whether already-seen notifications are currently filtered out of the shade. */
    fun setHasFilteredOutSeenNotifications(value: Boolean) {
        notificationListRepository.hasFilteredOutSeenNotifications.value = value
    }

    /** Set the entry that is identified as the top ongoing notification. */
    fun setTopOngoingNotification(entry: NotificationEntry?) {
        if (NotificationMinimalismPrototype.isUnexpectedlyInLegacyMode()) return
        notificationListRepository.topOngoingNotificationKey.value = entry?.key
    }

    /** Determine if the given notification is the top ongoing notification. */
    fun isTopOngoingNotification(entry: NotificationEntry?): Boolean =
        if (NotificationMinimalismPrototype.isUnexpectedlyInLegacyMode()) false
        else
            entry != null && notificationListRepository.topOngoingNotificationKey.value == entry.key

    /** Set the entry that is identified as the top unseen notification. */
    fun setTopUnseenNotification(entry: NotificationEntry?) {
        if (NotificationMinimalismPrototype.isUnexpectedlyInLegacyMode()) return
        notificationListRepository.topUnseenNotificationKey.value = entry?.key
    }

    /** Determine if the given notification is the top unseen notification. */
    fun isTopUnseenNotification(entry: NotificationEntry?): Boolean =
        if (NotificationMinimalismPrototype.isUnexpectedlyInLegacyMode()) false
        else entry != null && notificationListRepository.topUnseenNotificationKey.value == entry.key

    fun dump(pw: IndentingPrintWriter) =
        with(pw) {
            printSection("SeenNotificationsInteractor") {
                print(
                    "hasFilteredOutSeenNotifications",
                    notificationListRepository.hasFilteredOutSeenNotifications.value
                )
                print(
                    "topOngoingNotificationKey",
                    notificationListRepository.topOngoingNotificationKey.value
                )
                print(
                    "topUnseenNotificationKey",
                    notificationListRepository.topUnseenNotificationKey.value
                )
            }
        }

    fun isLockScreenShowOnlyUnseenNotificationsEnabled(): Flow<Boolean> =
        secureSettings
            // emit whenever the setting has changed
            .observerFlow(
                UserHandle.USER_ALL,
                Settings.Secure.LOCK_SCREEN_SHOW_ONLY_UNSEEN_NOTIFICATIONS,
            )
            // perform a query immediately
            .onStart { emit(Unit) }
            // for each change, lookup the new value
            .map {
                secureSettings.getIntForUser(
                    name = Settings.Secure.LOCK_SCREEN_SHOW_ONLY_UNSEEN_NOTIFICATIONS,
                    default = 0,
                    userHandle = UserHandle.USER_CURRENT,
                ) == 1
            }
            // don't emit anything if nothing has changed
            .distinctUntilChanged()
            // perform lookups on the bg thread pool
            .flowOn(bgDispatcher)
            // only track the most recent emission, if events are happening faster than they can be
            // consumed
            .conflate()
}
