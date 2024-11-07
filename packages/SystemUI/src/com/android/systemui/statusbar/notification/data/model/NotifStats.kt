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

package com.android.systemui.statusbar.notification.data.model

/** Information about the current list of notifications. */
data class NotifStats(
    val hasNonClearableAlertingNotifs: Boolean,
    val hasClearableAlertingNotifs: Boolean,
    val hasNonClearableSilentNotifs: Boolean,
    val hasClearableSilentNotifs: Boolean,
) {
    companion object {
        @JvmStatic
        val empty =
            NotifStats(
                hasNonClearableAlertingNotifs = false,
                hasClearableAlertingNotifs = false,
                hasNonClearableSilentNotifs = false,
                hasClearableSilentNotifs = false,
            )
    }
}
