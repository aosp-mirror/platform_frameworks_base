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

package com.android.systemui.statusbar.notification.stack.ui.view

import com.android.systemui.statusbar.notification.shared.ActiveNotificationModel
import java.util.concurrent.Callable

/**
 * Logs UI events of Notifications, in particular, logging which Notifications are visible and which
 * are not.
 */
interface NotificationStatsLogger : NotificationRowStatsLogger {
    fun onLockscreenOrShadeInteractive(
        isOnLockScreen: Boolean,
        activeNotifications: List<ActiveNotificationModel>,
    )
    fun onLockscreenOrShadeNotInteractive(activeNotifications: List<ActiveNotificationModel>)
    fun onNotificationLocationsChanged(
        locationsProvider: Callable<Map<String, Int>>,
        notificationRanks: Map<String, Int>,
    )
    override fun onNotificationExpansionChanged(
        key: String,
        isExpanded: Boolean,
        location: Int,
        isUserAction: Boolean
    )
    fun onNotificationRemoved(key: String)
    fun onNotificationUpdated(key: String)
}
