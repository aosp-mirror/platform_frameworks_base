/*
 * Copyright (C) 2021 The Android Open Source Project
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

import com.android.systemui.statusbar.notification.FeedbackIcon

/** A view controller for a notification row */
interface NotifRowController {
    /**
     * This tells the row what the 'default expanded' state should be.  Once a user expands or
     * contracts a row, that will set the user expanded state, which takes precedence, but
     * collapsing the shade and re-opening it will clear the user expanded state.  This allows for
     * nice auto expansion of the next notification as users dismiss the top notification.
     */
    fun setSystemExpanded(systemExpanded: Boolean)

    /**
     * Sets the timestamp that the notification was last audibly alerted, which the row uses to
     * show a bell icon in the header which indicates to the user which notification made a noise.
     */
    fun setLastAudiblyAlertedMs(lastAudiblyAlertedMs: Long)

    /** Shows the given feedback icon, or hides the icon if null. */
    fun setFeedbackIcon(icon: FeedbackIcon?)
}
