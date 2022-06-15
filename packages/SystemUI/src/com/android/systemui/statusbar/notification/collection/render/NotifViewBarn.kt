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

package com.android.systemui.statusbar.notification.collection.render

import android.view.textclassifier.Log
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.statusbar.notification.collection.ListEntry
import com.android.systemui.statusbar.notification.row.ExpandableNotificationRowController
import javax.inject.Inject

/**
 * The ViewBarn is just a map from [ListEntry] to an instance of an
 * [ExpandableNotificationRowController].
 */
@SysUISingleton
class NotifViewBarn @Inject constructor() {
    private val rowMap = mutableMapOf<String, ExpandableNotificationRowController>()

    fun requireView(forEntry: ListEntry): ExpandableNotificationRowController {
        if (DEBUG) {
            Log.d(TAG, "requireView: $forEntry.key")
        }
        val li = rowMap[forEntry.key]
        if (li == null) {
            throw IllegalStateException("No view has been registered for entry: $forEntry")
        }

        return li
    }

    fun registerViewForEntry(entry: ListEntry, controller: ExpandableNotificationRowController) {
        if (DEBUG) {
            Log.d(TAG, "registerViewForEntry: $entry.key")
        }
        rowMap[entry.key] = controller
    }

    fun removeViewForEntry(entry: ListEntry) {
        if (DEBUG) {
            Log.d(TAG, "removeViewForEntry: $entry.key")
        }
        rowMap.remove(entry.key)
    }
}

private const val TAG = "NotifViewBarn"

private const val DEBUG = false