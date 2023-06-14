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

package com.android.systemui.statusbar.notification.collection.coordinator

import com.android.systemui.log.LogBuffer
import com.android.systemui.log.core.LogLevel
import com.android.systemui.log.dagger.UnseenNotificationLog
import com.android.systemui.statusbar.notification.collection.NotificationEntry
import javax.inject.Inject

private const val TAG = "KeyguardCoordinator"

class KeyguardCoordinatorLogger
@Inject
constructor(
    @UnseenNotificationLog private val buffer: LogBuffer,
) {
    fun logSeenOnLockscreen(entry: NotificationEntry) =
        buffer.log(
            TAG,
            LogLevel.DEBUG,
            messageInitializer = { str1 = entry.key },
            messagePrinter = {
                "Notification [$str1] on lockscreen will be marked as seen when unlocked."
            },
        )

    fun logTrackingUnseen(trackingUnseen: Boolean) =
        buffer.log(
            TAG,
            LogLevel.DEBUG,
            messageInitializer = { bool1 = trackingUnseen },
            messagePrinter = { "${if (bool1) "Start" else "Stop"} tracking unseen notifications." },
        )

    fun logAllMarkedSeenOnUnlock(
        seenCount: Int,
        remainingUnseenCount: Int,
    ) =
        buffer.log(
            TAG,
            LogLevel.DEBUG,
            messageInitializer = {
                int1 = seenCount
                int2 = remainingUnseenCount
            },
            messagePrinter = {
                "$int1 Notifications have been marked as seen now that device is unlocked. " +
                    "$int2 notifications remain unseen."
            },
        )

    fun logShadeExpanded() =
        buffer.log(
            TAG,
            LogLevel.DEBUG,
            "Notifications have been marked as seen due to shade expansion."
        )

    fun logUnseenAdded(key: String) =
        buffer.log(
            TAG,
            LogLevel.DEBUG,
            messageInitializer = { str1 = key },
            messagePrinter = { "Unseen notif added: $str1" },
        )

    fun logUnseenUpdated(key: String) =
        buffer.log(
            TAG,
            LogLevel.DEBUG,
            messageInitializer = { str1 = key },
            messagePrinter = { "Unseen notif updated: $str1" },
        )

    fun logUnseenRemoved(key: String) =
        buffer.log(
            TAG,
            LogLevel.DEBUG,
            messageInitializer = { str1 = key },
            messagePrinter = { "Unseen notif removed: $str1" },
        )

    fun logProviderHasFilteredOutSeenNotifs(hasFilteredAnyNotifs: Boolean) =
        buffer.log(
            TAG,
            LogLevel.DEBUG,
            messageInitializer = { bool1 = hasFilteredAnyNotifs },
            messagePrinter = { "UI showing unseen filter treatment: $bool1" },
        )

    fun logUnseenHun(key: String) =
        buffer.log(
            TAG,
            LogLevel.DEBUG,
            messageInitializer = { str1 = key },
            messagePrinter = { "Unseen notif has become heads up: $str1" },
        )

    fun logTrackingLockscreenSeenDuration(unseenNotifications: Set<NotificationEntry>) {
        buffer.log(
            TAG,
            LogLevel.DEBUG,
            messageInitializer = {
                str1 = unseenNotifications.joinToString { it.key }
                int1 = unseenNotifications.size
            },
            messagePrinter = {
                "Tracking $int1 unseen notifications for lockscreen seen duration threshold: $str1"
            },
        )
    }

    fun logTrackingLockscreenSeenDuration(entry: NotificationEntry) {
        buffer.log(
            TAG,
            LogLevel.DEBUG,
            messageInitializer = { str1 = entry.key },
            messagePrinter = {
                "Tracking new notification for lockscreen seen duration threshold: $str1"
            },
        )
    }

    fun logStopTrackingLockscreenSeenDuration(entry: NotificationEntry) {
        buffer.log(
            TAG,
            LogLevel.DEBUG,
            messageInitializer = { str1 = entry.key },
            messagePrinter = {
                "Stop tracking removed notification for lockscreen seen duration threshold: $str1"
            },
        )
    }

    fun logResetSeenOnLockscreen(entry: NotificationEntry) {
        buffer.log(
            TAG,
            LogLevel.DEBUG,
            messageInitializer = { str1 = entry.key },
            messagePrinter = {
                "Reset tracking updated notification for lockscreen seen duration threshold: $str1"
            },
        )
    }

    fun logRemoveSeenOnLockscreen(entry: NotificationEntry) {
        buffer.log(
            TAG,
            LogLevel.DEBUG,
            messageInitializer = { str1 = entry.key },
            messagePrinter = { "Notification marked as seen on lockscreen removed: $str1" },
        )
    }
}
