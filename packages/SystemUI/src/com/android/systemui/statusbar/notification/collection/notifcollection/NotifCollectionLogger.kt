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

import com.android.systemui.log.LogBuffer
import com.android.systemui.log.LogLevel
import com.android.systemui.log.dagger.NotificationLog
import javax.inject.Inject

class NotifCollectionLogger @Inject constructor(
    @NotificationLog private val buffer: LogBuffer
) {
    fun logNotifPosted(key: String) {
        buffer.log(TAG, LogLevel.INFO, {
            str1 = key
        }, {
            "POSTED $str1"
        })
    }

    fun logNotifGroupPosted(groupKey: String, batchSize: Int) {
        buffer.log(TAG, LogLevel.INFO, {
            str1 = groupKey
            int1 = batchSize
        }, {
            "POSTED GROUP $str1 ($int1 events)"
        })
    }

    fun logNotifUpdated(key: String) {
        buffer.log(TAG, LogLevel.INFO, {
            str1 = key
        }, {
            "UPDATED $str1"
        })
    }

    fun logNotifRemoved(key: String, reason: Int) {
        buffer.log(TAG, LogLevel.INFO, {
            str1 = key
            int1 = reason
        }, {
            "REMOVED $str1 reason=$int1"
        })
    }
}

private const val TAG = "NotifCollection"