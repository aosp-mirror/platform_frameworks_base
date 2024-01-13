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

package com.android.systemui.statusbar.notification.stack.ui.viewbinder

import com.android.systemui.common.coroutine.ConflatedCallbackFlow
import com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayout
import com.android.systemui.statusbar.notification.stack.ui.view.NotificationStatsLogger
import com.android.systemui.statusbar.notification.stack.ui.viewmodel.NotificationLoggerViewModel
import com.android.systemui.util.kotlin.Utils
import com.android.systemui.util.kotlin.sample
import com.android.systemui.util.kotlin.throttle
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine

/**
 * Binds a [NotificationStatsLogger] to its [NotificationLoggerViewModel], and wires in
 * [NotificationStackScrollLayout.OnNotificationLocationsChangedListener] updates to it.
 */
object NotificationStatsLoggerBinder {

    /** minimum delay in ms between Notification location updates */
    private const val NOTIFICATION_UPDATE_PERIOD_MS = 500L

    suspend fun bindLogger(
        view: NotificationStackScrollLayout,
        logger: NotificationStatsLogger,
        viewModel: NotificationLoggerViewModel,
    ) {
        viewModel.isLockscreenOrShadeInteractive
            .sample(
                combine(viewModel.isOnLockScreen, viewModel.activeNotifications, ::Pair),
                Utils.Companion::toTriple
            )
            .collectLatest { (isPanelInteractive, isOnLockScreen, notifications) ->
                if (isPanelInteractive) {
                    logger.onLockscreenOrShadeInteractive(
                        isOnLockScreen = isOnLockScreen,
                        activeNotifications = notifications,
                    )
                    view.onNotificationsUpdated
                        // Delay the updates with [NOTIFICATION_UPDATES_PERIOD_MS]. If the original
                        // flow emits more than once during this period, only the latest value is
                        // emitted, meaning that we won't log the intermediate Notification states.
                        .throttle(NOTIFICATION_UPDATE_PERIOD_MS)
                        .sample(viewModel.activeNotificationRanks, ::Pair)
                        .collect { (locationsProvider, notificationRanks) ->
                            logger.onNotificationListUpdated(locationsProvider, notificationRanks)
                        }
                } else {
                    logger.onLockscreenOrShadeNotInteractive(
                        activeNotifications = notifications,
                    )
                }
            }
    }
}

private val NotificationStackScrollLayout.onNotificationsUpdated
    get() =
        ConflatedCallbackFlow.conflatedCallbackFlow {
            val callback =
                NotificationStackScrollLayout.OnNotificationLocationsChangedListener { callable ->
                    trySend(callable)
                }
            setNotificationLocationsChangedListener(callback)
            awaitClose { setNotificationLocationsChangedListener(null) }
        }
