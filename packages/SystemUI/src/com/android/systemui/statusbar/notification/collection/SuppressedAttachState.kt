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

import com.android.systemui.statusbar.notification.collection.listbuilder.pluggable.NotifSection

/**
 * Stores the suppressed state that [ShadeListBuilder] assigned to this [ListEntry] before the
 * VisualStabilityManager suppressed group and section changes.
 */
data class SuppressedAttachState private constructor(
    /**
     * Null if not attached to the current shade list. If top-level, then the shade list root. If
     * part of a group, then that group's GroupEntry.
     */
    var parent: GroupEntry?,

    /**
     * The assigned section for this ListEntry. If the child of the group, this will be the
     * parent's section. Null if not attached to the list.
     */
    var section: NotifSection?,
    var sectionIndex: Int
) {

    /** Copies the state of another instance. */
    fun clone(other: SuppressedAttachState) {
        parent = other.parent
        section = other.section
        sectionIndex = other.sectionIndex
    }

    /** Resets back to a "clean" state (the same as created by the factory method) */
    fun reset() {
        parent = null
        section = null
        sectionIndex = -1
    }

    companion object {
        @JvmStatic
        fun create(): SuppressedAttachState {
            return SuppressedAttachState(
                null,
                null,
                -1)
        }
    }
}
