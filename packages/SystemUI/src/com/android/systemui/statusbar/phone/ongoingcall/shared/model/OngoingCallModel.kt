/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.systemui.statusbar.phone.ongoingcall.shared.model

import android.app.PendingIntent
import com.android.systemui.statusbar.StatusBarIconView

/** Represents the state of any ongoing calls. */
sealed interface OngoingCallModel {
    /** There is no ongoing call. */
    data object NoCall : OngoingCallModel

    /**
     * There is an ongoing call but the call app is currently visible, so we don't need to show
     * the chip.
     */
    data object InCallWithVisibleApp : OngoingCallModel

    /**
     * There *is* an ongoing call.
     *
     * @property startTimeMs the time that the phone call started, based on the notification's
     *   `when` field. Importantly, this time is relative to
     *   [com.android.systemui.util.time.SystemClock.currentTimeMillis], **not**
     *   [com.android.systemui.util.time.SystemClock.elapsedRealtime]. This value can be 0 if the
     *   user has started an outgoing call that hasn't been answered yet - see b/192379214.
     * @property notificationIconView the [android.app.Notification.getSmallIcon] that's set on the
     *   call notification. We may use this icon in the chip instead of the default phone icon.
     * @property intent the intent associated with the call notification.
     */
    data class InCall(
        val startTimeMs: Long,
        val notificationIconView: StatusBarIconView?,
        val intent: PendingIntent?,
        val notificationKey: String,
    ) : OngoingCallModel
}
