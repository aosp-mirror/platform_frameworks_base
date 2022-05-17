/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.systemui.statusbar.phone

import android.app.PendingIntent
import com.android.systemui.log.LogBuffer
import com.android.systemui.log.LogLevel.DEBUG
import com.android.systemui.log.LogLevel.ERROR
import com.android.systemui.log.LogLevel.INFO
import com.android.systemui.log.LogLevel.WARNING
import com.android.systemui.log.dagger.NotifInteractionLog
import com.android.systemui.statusbar.notification.collection.NotificationEntry
import com.android.systemui.statusbar.notification.logKey
import javax.inject.Inject

class StatusBarNotificationActivityStarterLogger @Inject constructor(
    @NotifInteractionLog private val buffer: LogBuffer
) {
    fun logStartingActivityFromClick(entry: NotificationEntry) {
        buffer.log(TAG, DEBUG, {
            str1 = entry.logKey
        }, {
            "(1/5) onNotificationClicked: $str1"
        })
    }

    fun logHandleClickAfterKeyguardDismissed(entry: NotificationEntry) {
        buffer.log(TAG, DEBUG, {
            str1 = entry.logKey
        }, {
            "(2/5) handleNotificationClickAfterKeyguardDismissed: $str1"
        })
    }

    fun logHandleClickAfterPanelCollapsed(entry: NotificationEntry) {
        buffer.log(TAG, DEBUG, {
            str1 = entry.logKey
        }, {
            "(3/5) handleNotificationClickAfterPanelCollapsed: $str1"
        })
    }

    fun logStartNotificationIntent(entry: NotificationEntry) {
        buffer.log(TAG, INFO, {
            str1 = entry.logKey
        }, {
            "(4/5) startNotificationIntent: $str1"
        })
    }

    fun logSendPendingIntent(entry: NotificationEntry, pendingIntent: PendingIntent, result: Int) {
        buffer.log(TAG, INFO, {
            str1 = entry.logKey
            str2 = pendingIntent.intent.toString()
            int1 = result
        }, {
            "(5/5) Started intent $str2 for notification $str1 with result code $int1"
        })
    }

    fun logExpandingBubble(entry: NotificationEntry) {
        buffer.log(TAG, DEBUG, {
            str1 = entry.logKey
        }, {
            "Expanding bubble for $str1 (rather than firing intent)"
        })
    }

    fun logSendingIntentFailed(e: Exception) {
        buffer.log(TAG, WARNING, {
            str1 = e.toString()
        }, {
            "Sending contentIntentFailed: $str1"
        })
    }

    fun logNonClickableNotification(entry: NotificationEntry) {
        buffer.log(TAG, ERROR, {
            str1 = entry.logKey
        }, {
            "onNotificationClicked called for non-clickable notification! $str1"
        })
    }

    fun logFullScreenIntentSuppressedByDnD(entry: NotificationEntry) {
        buffer.log(TAG, DEBUG, {
            str1 = entry.logKey
        }, {
            "No Fullscreen intent: suppressed by DND: $str1"
        })
    }

    fun logFullScreenIntentNotImportantEnough(entry: NotificationEntry) {
        buffer.log(TAG, DEBUG, {
            str1 = entry.logKey
        }, {
            "No Fullscreen intent: not important enough: $str1"
        })
    }

    fun logSendingFullScreenIntent(entry: NotificationEntry, pendingIntent: PendingIntent) {
        buffer.log(TAG, INFO, {
            str1 = entry.logKey
            str2 = pendingIntent.intent.toString()
        }, {
            "Notification $str1 has fullScreenIntent; sending fullScreenIntent $str2"
        })
    }
}

private const val TAG = "NotifActivityStarter"
