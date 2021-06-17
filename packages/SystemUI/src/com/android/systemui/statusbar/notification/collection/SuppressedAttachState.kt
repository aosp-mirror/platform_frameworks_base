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

import com.android.systemui.statusbar.notification.collection.listbuilder.NotifSection

/**
 * Stores the suppressed state that [ShadeListBuilder] assigned to this [ListEntry] before the
 * VisualStabilityManager suppressed group and section changes.
 */
data class SuppressedAttachState private constructor(
    /**
     * The suppressed section assignment for this ListEntry.
     * Null if no section change was suppressed.
     */
    var section: NotifSection?,

    /**
     * The suppressed parent assignment for this ListEntry.
     *  - Null if no parent change was suppressed.
     *  - Root if suppressing group change to top-level
     *  - GroupEntry if suppressing group change to a different group
     */
    var parent: GroupEntry?,

    /**
     * Whether the ListEntry would have been pruned had its group change not been suppressed.
     */
    var wasPruneSuppressed: Boolean
) {

    /** Copies the state of another instance. */
    fun clone(other: SuppressedAttachState) {
        parent = other.parent
        section = other.section
        wasPruneSuppressed = other.wasPruneSuppressed
    }

    /** Resets back to a "clean" state (the same as created by the factory method) */
    fun reset() {
        parent = null
        section = null
        wasPruneSuppressed = false
    }

    companion object {
        @JvmStatic
        fun create(): SuppressedAttachState {
            return SuppressedAttachState(
                null,
                null,
                false)
        }
    }
}
