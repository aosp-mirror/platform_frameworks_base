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

package com.android.systemui.statusbar.notification.collection

import android.view.textclassifier.Log
import com.android.systemui.statusbar.notification.stack.NotificationListItem
import java.lang.IllegalStateException

import javax.inject.Inject
import javax.inject.Singleton

/**
 * The ViewBarn is just a map from [ListEntry] to an instance of [NotificationListItem] which is
 * usually just an [ExpandableNotificationRow]
 */
@Singleton
class NotifViewBarn @Inject constructor() {
    private val DEBUG = false

    private val rowMap = mutableMapOf<String, NotificationListItem>()

    fun requireView(forEntry: ListEntry): NotificationListItem {
        if (DEBUG) {
            Log.d(TAG, "requireView: $forEntry.key")
        }
        val li = rowMap[forEntry.key]
        if (li == null) {
            throw IllegalStateException("No view has been registered for entry: $forEntry")
        }

        return li
    }

    fun registerViewForEntry(entry: ListEntry, view: NotificationListItem) {
        if (DEBUG) {
            Log.d(TAG, "registerViewForEntry: $entry.key")
        }
        rowMap[entry.key] = view
    }

    fun removeViewForEntry(entry: ListEntry) {
        if (DEBUG) {
            Log.d(TAG, "removeViewForEntry: $entry.key")
        }
        rowMap.remove(entry.key)
    }
}

private const val TAG = "NotifViewBarn"