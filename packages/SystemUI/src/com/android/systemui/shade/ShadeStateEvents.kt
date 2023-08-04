/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.systemui.shade

/** Provides certain notification panel events. */
interface ShadeStateEvents {

    /** Registers callbacks to be invoked when notification panel events occur. */
    fun addShadeStateEventsListener(listener: ShadeStateEventsListener)

    /** Unregisters callbacks previously registered via [addShadeStateEventsListener] */
    fun removeShadeStateEventsListener(listener: ShadeStateEventsListener)

    /** Callbacks for certain notification panel events. */
    interface ShadeStateEventsListener {

        /** Invoked when the notification panel starts or stops collapsing. */
        fun onPanelCollapsingChanged(isCollapsing: Boolean) {}

        /**
         * Invoked when the notification panel starts or stops launching an [android.app.Activity].
         */
        fun onLaunchingActivityChanged(isLaunchingActivity: Boolean) {}

        /**
         * Invoked when the "expand immediate" attribute changes.
         *
         * An example of expanding immediately is when swiping down from the top with two fingers.
         * Instead of going to QQS, we immediately expand to full QS.
         *
         * Another example is when full QS is showing, and we swipe up from the bottom. Instead of
         * going to QQS, the panel fully collapses.
         */
        fun onExpandImmediateChanged(isExpandImmediateEnabled: Boolean) {}
    }
}
