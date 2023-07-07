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

import android.util.Log
import android.view.View
import android.view.View.OnClickListener
import com.android.systemui.flags.FeatureFlags
import com.android.systemui.flags.Flags
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

    /** Binds the shelf to the host [NotificationStackScrollLayout], via its Controller. */
    fun bind(
        ambientState: AmbientState,
        notificationStackScrollLayoutController: NotificationStackScrollLayoutController,
    )

    /** @see View.setOnClickListener */
    fun setOnClickListener(listener: OnClickListener)

    companion object {
        @JvmStatic
        fun assertRefactorFlagDisabled(featureFlags: FeatureFlags) {
            if (featureFlags.isEnabled(Flags.NOTIFICATION_SHELF_REFACTOR)) {
                throwIllegalFlagStateError(expected = false)
            }
        }

        @JvmStatic
        fun checkRefactorFlagEnabled(featureFlags: FeatureFlags): Boolean =
            featureFlags.isEnabled(Flags.NOTIFICATION_SHELF_REFACTOR).also { enabled ->
                if (!enabled) {
                    Log.wtf("NotifShelf", getErrorMessage(expected = true))
                }
            }

        @JvmStatic
        fun throwIllegalFlagStateError(expected: Boolean): Nothing =
            error(getErrorMessage(expected))

        private fun getErrorMessage(expected: Boolean): String =
            "Code path not supported when Flags.NOTIFICATION_SHELF_REFACTOR is " +
                if (expected) "disabled" else "enabled"
    }
}
