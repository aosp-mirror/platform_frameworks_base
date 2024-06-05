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

import javax.inject.Inject

/** An interface by which the pipeline can make updates to the notification root view. */
interface NotifStackController {
    /** Provides stats about the list of notifications attached to the shade */
    fun setNotifStats(stats: NotifStats)
}

/** Data provided to the NotificationRootController whenever the pipeline runs */
data class NotifStats(
    // TODO(b/293167744): The count can be removed from here when we remove the FooterView flag.
    val numActiveNotifs: Int,
    val hasNonClearableAlertingNotifs: Boolean,
    val hasClearableAlertingNotifs: Boolean,
    val hasNonClearableSilentNotifs: Boolean,
    val hasClearableSilentNotifs: Boolean
) {
    companion object {
        @JvmStatic val empty = NotifStats(0, false, false, false, false)
    }
}

/**
 * An implementation of NotifStackController which provides default, no-op implementations of each
 * method. This is used by ArcSystemUI so that that implementation can opt-in to overriding methods,
 * rather than forcing us to add no-op implementations in their implementation every time a method
 * is added.
 */
open class DefaultNotifStackController @Inject constructor() : NotifStackController {
    override fun setNotifStats(stats: NotifStats) {}
}
