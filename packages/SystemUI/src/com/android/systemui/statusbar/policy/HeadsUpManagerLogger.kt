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

package com.android.systemui.statusbar.policy

import com.android.systemui.log.dagger.NotificationHeadsUpLog
import com.android.systemui.log.LogBuffer
import com.android.systemui.log.core.LogLevel.INFO
import com.android.systemui.log.core.LogLevel.VERBOSE
import com.android.systemui.statusbar.notification.collection.NotificationEntry
import com.android.systemui.statusbar.notification.logKey
import javax.inject.Inject

/** Logger for [HeadsUpManager]. */
class HeadsUpManagerLogger @Inject constructor(
    @NotificationHeadsUpLog private val buffer: LogBuffer
) {
    fun logPackageSnoozed(snoozeKey: String) {
        buffer.log(TAG, INFO, {
            str1 = snoozeKey
        }, {
            "package snoozed $str1"
        })
    }

    fun logPackageUnsnoozed(snoozeKey: String) {
        buffer.log(TAG, INFO, {
            str1 = snoozeKey
        }, {
            "package unsnoozed $str1"
        })
    }

    fun logIsSnoozedReturned(snoozeKey: String) {
        buffer.log(TAG, INFO, {
            str1 = snoozeKey
        }, {
            "package snoozed when queried $str1"
        })
    }

    fun logReleaseAllImmediately() {
        buffer.log(TAG, INFO, { }, {
            "release all immediately"
        })
    }

    fun logShowNotification(entry: NotificationEntry) {
        buffer.log(TAG, INFO, {
            str1 = entry.logKey
        }, {
            "show notification $str1"
        })
    }

    fun logAutoRemoveScheduled(entry: NotificationEntry, delayMillis: Long, reason: String) {
        buffer.log(TAG, INFO, {
            str1 = entry.logKey
            long1 = delayMillis
            str2 = reason
        }, {
            "schedule auto remove of $str1 in $long1 ms reason: $str2"
        })
    }

    fun logAutoRemoveRescheduled(entry: NotificationEntry, delayMillis: Long, reason: String) {
        buffer.log(TAG, INFO, {
            str1 = entry.logKey
            long1 = delayMillis
            str2 = reason
        }, {
            "reschedule auto remove of $str1 in $long1 ms reason: $str2"
        })
    }

    fun logAutoRemoveCanceled(entry: NotificationEntry, reason: String?) {
        buffer.log(TAG, INFO, {
            str1 = entry.logKey
            str2 = reason ?: "unknown"
        }, {
            "cancel auto remove of $str1 reason: $str2"
        })
    }

    fun logRemoveNotification(key: String, releaseImmediately: Boolean) {
        buffer.log(TAG, INFO, {
            str1 = logKey(key)
            bool1 = releaseImmediately
        }, {
            "remove notification $str1 releaseImmediately: $bool1"
        })
    }

    fun logNotificationActuallyRemoved(entry: NotificationEntry) {
        buffer.log(TAG, INFO, {
            str1 = entry.logKey
        }, {
            "notification removed $str1 "
        })
    }

    fun logUpdateNotification(key: String, alert: Boolean, hasEntry: Boolean) {
        buffer.log(TAG, INFO, {
            str1 = logKey(key)
            bool1 = alert
            bool2 = hasEntry
        }, {
            "update notification $str1 alert: $bool1 hasEntry: $bool2 reason: $str2"
        })
    }

    fun logUpdateEntry(entry: NotificationEntry, updatePostTime: Boolean, reason: String?) {
        buffer.log(TAG, INFO, {
            str1 = entry.logKey
            bool1 = updatePostTime
            str2 = reason ?: "unknown"
        }, {
            "update entry $str1 updatePostTime: $bool1 reason: $str2"
        })
    }

    fun logSnoozeLengthChange(packageSnoozeLengthMs: Int) {
        buffer.log(TAG, INFO, {
            int1 = packageSnoozeLengthMs
        }, {
            "snooze length changed: ${int1}ms"
        })
    }

    fun logSetEntryPinned(entry: NotificationEntry, isPinned: Boolean) {
        buffer.log(TAG, VERBOSE, {
            str1 = entry.logKey
            bool1 = isPinned
        }, {
            "set entry pinned $str1 pinned: $bool1"
        })
    }

    fun logUpdatePinnedMode(hasPinnedNotification: Boolean) {
        buffer.log(TAG, INFO, {
            bool1 = hasPinnedNotification
        }, {
            "has pinned notification changed to $bool1"
        })
    }
}

private const val TAG = "HeadsUpManager"