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

import android.view.View
import android.view.ViewGroup
import com.android.systemui.statusbar.notification.stack.NotificationListItem

/**
 * Minimal interface of what [NotifViewManager] needs from [NotificationListContainer]
 */
interface SimpleNotificationListContainer {
    /** Called to signify that a top-level element is becoming a child in the shade */
    fun setChildTransferInProgress(b: Boolean)
    /** Used to generate a list of [NotificationListItem] */
    fun getContainerChildAt(i: Int): View
    /** Similar to above */
    fun getContainerChildCount(): Int
    /** Remove a [NotificationListItem] from the container */
    fun removeListItem(li: NotificationListItem)
    /** Add a [NotificationListItem] to the container */
    fun addListItem(li: NotificationListItem)
    /** Allows [NotifViewManager] to notify the container about a group child removal */
    fun notifyGroupChildRemoved(row: View, parent: ViewGroup)
    /** Allows [NotifViewManager] to notify the container about a group child addition */
    fun notifyGroupChildAdded(row: View)
    /** [NotifViewManager] calls this when the order of the children changes */
    fun generateChildOrderChangedEvent()
}
