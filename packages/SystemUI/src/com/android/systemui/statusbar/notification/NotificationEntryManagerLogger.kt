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

package com.android.systemui.statusbar.notification

import com.android.systemui.log.LogBuffer
import com.android.systemui.log.LogLevel.DEBUG
import com.android.systemui.log.LogLevel.INFO
import com.android.systemui.log.dagger.NotificationLog
import javax.inject.Inject

/** Logger for [NotificationEntryManager]. */
class NotificationEntryManagerLogger @Inject constructor(
    @NotificationLog private val buffer: LogBuffer
) {
    fun logNotifAdded(key: String) {
        buffer.log(TAG, INFO, {
            str1 = key
        }, {
            "NOTIF ADDED $str1"
        })
    }

    fun logNotifUpdated(key: String) {
        buffer.log(TAG, INFO, {
            str1 = key
        }, {
            "NOTIF UPDATED $str1"
        })
    }

    fun logInflationAborted(key: String, status: String, reason: String) {
        buffer.log(TAG, DEBUG, {
            str1 = key
            str2 = status
            str3 = reason
        }, {
            "NOTIF INFLATION ABORTED $str1 notifStatus=$str2 reason=$str3"
        })
    }

    fun logNotifInflated(key: String, isNew: Boolean) {
        buffer.log(TAG, DEBUG, {
            str1 = key
            bool1 = isNew
        }, {
            "NOTIF INFLATED $str1 isNew=$bool1}"
        })
    }

    fun logRemovalIntercepted(key: String) {
        buffer.log(TAG, INFO, {
            str1 = key
        }, {
            "NOTIF REMOVE INTERCEPTED for $str1"
        })
    }

    fun logLifetimeExtended(key: String, extenderName: String, status: String) {
        buffer.log(TAG, INFO, {
            str1 = key
            str2 = extenderName
            str3 = status
        }, {
            "NOTIF LIFETIME EXTENDED $str1 extender=$str2 status=$str3"
        })
    }

    fun logNotifRemoved(key: String, removedByUser: Boolean) {
        buffer.log(TAG, INFO, {
            str1 = key
            bool1 = removedByUser
        }, {
            "NOTIF REMOVED $str1 removedByUser=$bool1"
        })
    }

    fun logFilterAndSort(reason: String) {
        buffer.log(TAG, INFO, {
            str1 = reason
        }, {
            "FILTER AND SORT reason=$str1"
        })
    }
}

private const val TAG = "NotificationEntryMgr"