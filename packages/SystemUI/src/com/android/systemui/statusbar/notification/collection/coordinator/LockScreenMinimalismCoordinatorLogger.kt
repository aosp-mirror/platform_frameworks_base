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

import com.android.systemui.log.LogBuffer
import com.android.systemui.log.core.LogLevel
import com.android.systemui.log.dagger.UnseenNotificationLog
import javax.inject.Inject

private const val TAG = "LockScreenMinimalismCoordinator"

class LockScreenMinimalismCoordinatorLogger
@Inject
constructor(
    @UnseenNotificationLog private val buffer: LogBuffer,
) {

    fun logTrackingUnseen(trackingUnseen: Boolean) =
        buffer.log(
            TAG,
            LogLevel.DEBUG,
            messageInitializer = { bool1 = trackingUnseen },
            messagePrinter = { "${if (bool1) "Start" else "Stop"} tracking unseen notifications." },
        )

    fun logShadeVisible(numUnseen: Int) {
        buffer.log(
            TAG,
            LogLevel.DEBUG,
            messageInitializer = { int1 = numUnseen },
            messagePrinter = { "Shade expanded. Notifications marked as seen: $int1" }
        )
    }

    fun logShadeHidden() {
        buffer.log(TAG, LogLevel.DEBUG, "Shade no longer expanded.")
    }

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

    fun logHunHasBeenSeen(key: String, wasUnseen: Boolean) =
        buffer.log(
            TAG,
            LogLevel.DEBUG,
            messageInitializer = {
                str1 = key
                bool1 = wasUnseen
            },
            messagePrinter = { "Heads up notif has been seen: $str1 wasUnseen=$bool1" },
        )

    fun logTopHeadsUpRow(key: String?, wasUnseenWhenPinned: Boolean) {
        buffer.log(
            TAG,
            LogLevel.DEBUG,
            messageInitializer = {
                str1 = key
                bool1 = wasUnseenWhenPinned
            },
            messagePrinter = { "New notif is top heads up: $str1 wasUnseen=$bool1" },
        )
    }
}
