/*
 * Copyright (C) 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.android.systemui.statusbar.phone.fragment

import android.app.StatusBarManager.DISABLE2_NONE
import android.app.StatusBarManager.DISABLE2_SYSTEM_ICONS
import android.app.StatusBarManager.DISABLE_CLOCK
import android.app.StatusBarManager.DISABLE_NONE
import android.app.StatusBarManager.DISABLE_NOTIFICATION_ICONS
import android.app.StatusBarManager.DISABLE_ONGOING_CALL_CHIP
import android.app.StatusBarManager.DISABLE_SYSTEM_INFO

/** A model for which parts of the status bar should be visible or not visible. */
data class StatusBarVisibilityModel(
    val showClock: Boolean,
    val showNotificationIcons: Boolean,
    val showPrimaryOngoingActivityChip: Boolean,
    val showSecondaryOngoingActivityChip: Boolean,
    val showSystemInfo: Boolean,
) {
    fun isOngoingActivityStatusDifferentFrom(other: StatusBarVisibilityModel): Boolean {
        return this.showPrimaryOngoingActivityChip != other.showPrimaryOngoingActivityChip ||
            this.showSecondaryOngoingActivityChip != other.showSecondaryOngoingActivityChip
    }

    companion object {
        /** Creates the default model. */
        @JvmStatic
        fun createDefaultModel(): StatusBarVisibilityModel {
            return createModelFromFlags(DISABLE_NONE, DISABLE2_NONE)
        }

        /** Creates a model that hides every piece of the status bar. */
        @JvmStatic
        fun createHiddenModel(): StatusBarVisibilityModel {
            return StatusBarVisibilityModel(
                showClock = false,
                showNotificationIcons = false,
                showPrimaryOngoingActivityChip = false,
                showSecondaryOngoingActivityChip = false,
                showSystemInfo = false,
            )
        }

        /**
         * Given a set of disabled flags, converts them into the correct visibility statuses.
         *
         * See [CommandQueue.Callbacks.disable].
         */
        @JvmStatic
        fun createModelFromFlags(disabled1: Int, disabled2: Int): StatusBarVisibilityModel {
            return StatusBarVisibilityModel(
                showClock = (disabled1 and DISABLE_CLOCK) == 0,
                showNotificationIcons = (disabled1 and DISABLE_NOTIFICATION_ICONS) == 0,
                // TODO(b/279899176): [CollapsedStatusBarFragment] always overwrites this with the
                //  value of [OngoingCallController]. Do we need to process the flag here?
                showPrimaryOngoingActivityChip = (disabled1 and DISABLE_ONGOING_CALL_CHIP) == 0,
                showSecondaryOngoingActivityChip = (disabled1 and DISABLE_ONGOING_CALL_CHIP) == 0,
                showSystemInfo =
                    (disabled1 and DISABLE_SYSTEM_INFO) == 0 &&
                        (disabled2 and DISABLE2_SYSTEM_ICONS) == 0
            )
        }
    }
}
