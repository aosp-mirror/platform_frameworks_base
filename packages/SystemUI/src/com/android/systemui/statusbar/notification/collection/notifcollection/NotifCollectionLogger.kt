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

package com.android.systemui.statusbar.notification.collection.notifcollection

import android.os.RemoteException
import android.service.notification.NotificationListenerService.RankingMap
import com.android.systemui.log.LogBuffer
import com.android.systemui.log.LogLevel.DEBUG
import com.android.systemui.log.LogLevel.ERROR
import com.android.systemui.log.LogLevel.INFO
import com.android.systemui.log.LogLevel.WARNING
import com.android.systemui.log.LogLevel.WTF
import com.android.systemui.log.dagger.NotificationLog
import com.android.systemui.statusbar.notification.collection.NotificationEntry
import javax.inject.Inject

class NotifCollectionLogger @Inject constructor(
    @NotificationLog private val buffer: LogBuffer
) {
    fun logNotifPosted(key: String) {
        buffer.log(TAG, INFO, {
            str1 = key
        }, {
            "POSTED $str1"
        })
    }

    fun logNotifGroupPosted(groupKey: String, batchSize: Int) {
        buffer.log(TAG, INFO, {
            str1 = groupKey
            int1 = batchSize
        }, {
            "POSTED GROUP $str1 ($int1 events)"
        })
    }

    fun logNotifUpdated(key: String) {
        buffer.log(TAG, INFO, {
            str1 = key
        }, {
            "UPDATED $str1"
        })
    }

    fun logNotifRemoved(key: String, reason: Int) {
        buffer.log(TAG, INFO, {
            str1 = key
            int1 = reason
        }, {
            "REMOVED $str1 reason=$int1"
        })
    }

    fun logNotifReleased(key: String) {
        buffer.log(TAG, INFO, {
            str1 = key
        }, {
            "RELEASED $str1"
        })
    }

    fun logNotifDismissed(key: String) {
        buffer.log(TAG, INFO, {
            str1 = key
        }, {
            "DISMISSED $str1"
        })
    }

    fun logChildDismissed(entry: NotificationEntry) {
        buffer.log(TAG, DEBUG, {
            str1 = entry.key
        }, {
            "CHILD DISMISSED (inferred): $str1"
        })
    }

    fun logDismissAll(userId: Int) {
        buffer.log(TAG, INFO, {
            int1 = userId
        }, {
            "DISMISS ALL notifications for user $int1"
        })
    }

    fun logDismissOnAlreadyCanceledEntry(entry: NotificationEntry) {
        buffer.log(TAG, DEBUG, {
            str1 = entry.key
        }, {
            "Dismiss on $str1, which was already canceled. Trying to remove..."
        })
    }

    fun logNotifDismissedIntercepted(key: String) {
        buffer.log(TAG, INFO, {
            str1 = key
        }, {
            "DISMISS INTERCEPTED $str1"
        })
    }

    fun logNotifClearAllDismissalIntercepted(key: String) {
        buffer.log(TAG, INFO, {
            str1 = key
        }, {
            "CLEAR ALL DISMISSAL INTERCEPTED $str1"
        })
    }

    fun logNoNotificationToRemoveWithKey(key: String) {
        buffer.log(TAG, ERROR, {
            str1 = key
        }, {
            "No notification to remove with key $str1"
        })
    }

    fun logRankingMissing(key: String, rankingMap: RankingMap) {
        buffer.log(TAG, WARNING, { str1 = key }, { "Ranking update is missing ranking for $str1" })
        buffer.log(TAG, DEBUG, {}, { "Ranking map contents:" })
        for (entry in rankingMap.orderedKeys) {
            buffer.log(TAG, DEBUG, { str1 = entry }, { "  $str1" })
        }
    }

    fun logRemoteExceptionOnNotificationClear(key: String, e: RemoteException) {
        buffer.log(TAG, WTF, {
            str1 = key
            str2 = e.toString()
        }, {
            "RemoteException while attempting to clear $str1:\n$str2"
        })
    }

    fun logRemoteExceptionOnClearAllNotifications(e: RemoteException) {
        buffer.log(TAG, WTF, {
            str1 = e.toString()
        }, {
            "RemoteException while attempting to clear all notifications:\n$str1"
        })
    }

    fun logLifetimeExtended(key: String, extender: NotifLifetimeExtender) {
        buffer.log(TAG, INFO, {
            str1 = key
            str2 = extender.name
        }, {
            "LIFETIME EXTENDED: $str1 by $str2"
        })
    }

    fun logLifetimeExtensionEnded(
        key: String,
        extender: NotifLifetimeExtender,
        totalExtenders: Int
    ) {
        buffer.log(TAG, INFO, {
            str1 = key
            str2 = extender.name
            int1 = totalExtenders
        }, {
            "LIFETIME EXTENSION ENDED for $str1 by '$str2'; $int1 remaining extensions"
        })
    }

    fun logIgnoredError(message: String?) {
        buffer.log(TAG, ERROR, {
            str1 = message
        }, {
            "ERROR suppressed due to initialization forgiveness: $str1"
        })
    }
}

private const val TAG = "NotifCollection"
