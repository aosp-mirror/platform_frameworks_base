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

package com.android.systemui.statusbar.notification.collection.inflation

import com.android.systemui.statusbar.notification.collection.NotificationEntry
import com.android.systemui.statusbar.notification.collection.render.NotifViewController

/**
 * Used by the [PreparationCoordinator]. When notifications are added or updated, the NotifInflater
 * is asked to (re)inflated and prepare their views. This inflation occurs off the main thread. When
 * the inflation is finished, NotifInflater will trigger its InflationCallback.
 */
interface NotifInflater {
    /**
     * Called to rebind the entry's views.
     *
     * @param callback callback called after inflation finishes
     */
    fun rebindViews(entry: NotificationEntry, params: Params, callback: InflationCallback)

    /**
     * Called to inflate the views of an entry. Views are not considered inflated until all of its
     * views are bound. Once all views are inflated, the InflationCallback is triggered.
     *
     * @param callback callback called after inflation finishes
     */
    fun inflateViews(entry: NotificationEntry, params: Params, callback: InflationCallback)

    /**
     * Request to stop the inflation of an entry. For example, called when a notification is removed
     * and no longer needs to be inflated. Returns whether anything may have been aborted.
     */
    fun abortInflation(entry: NotificationEntry): Boolean

    /** Called to let the system remove the content views from the notification row. */
    fun releaseViews(entry: NotificationEntry)

    /** Callback once all the views are inflated and bound for a given NotificationEntry. */
    interface InflationCallback {
        fun onInflationFinished(entry: NotificationEntry, controller: NotifViewController)
    }

    /** A class holding parameters used when inflating the notification row */
    class Params(
        val isLowPriority: Boolean,
        val reason: String,
        val showSnooze: Boolean,
        val isChildInGroup: Boolean = false,
    )
}
