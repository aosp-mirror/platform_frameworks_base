/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.systemui.statusbar

import android.view.View
import android.view.View.OnClickListener
import com.android.systemui.statusbar.notification.row.ActivatableNotificationView
import com.android.systemui.statusbar.notification.row.ActivatableNotificationView.OnActivatedListener
import com.android.systemui.statusbar.notification.row.ExpandableView
import com.android.systemui.statusbar.notification.stack.AmbientState
import com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayout
import com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayoutController
import com.android.systemui.statusbar.phone.NotificationIconContainer

/** Controller interface for [NotificationShelf]. */
interface NotificationShelfController {
    /** The [NotificationShelf] controlled by this Controller. */
    val view: NotificationShelf

    /** @see ExpandableView.getIntrinsicHeight */
    val intrinsicHeight: Int

    /** Container view for icons displayed in the shelf. */
    val shelfIcons: NotificationIconContainer

    /** Whether or not the shelf can modify the color of notifications in the shade. */
    fun canModifyColorOfNotifications(): Boolean

    /** @see ActivatableNotificationView.setOnActivatedListener */
    fun setOnActivatedListener(listener: OnActivatedListener)

    /** Binds the shelf to the host [NotificationStackScrollLayout], via its Controller. */
    fun bind(
        ambientState: AmbientState,
        notificationStackScrollLayoutController: NotificationStackScrollLayoutController,
    )

    /** @see View.setOnClickListener */
    fun setOnClickListener(listener: OnClickListener)
}
