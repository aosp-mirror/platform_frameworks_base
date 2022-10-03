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
import com.android.systemui.statusbar.notification.collection.listbuilder.pluggable.NotifFilter
import com.android.systemui.statusbar.notification.collection.listbuilder.pluggable.NotifPromoter

/**
 * Stores the state that [ShadeListBuilder] assigns to this [ListEntry]
 */
data class ListAttachState private constructor(
    /**
     * Null if not attached to the current shade list. If top-level, then the shade list root. If
     * part of a group, then that group's GroupEntry.
     */
    var parent: GroupEntry?,

    /**
     * The section that this ListEntry was sorted into. If the child of the group, this will be the
     * parent's section. Null if not attached to the list.
     */
    var section: NotifSection?,

    /**
     * If a [NotifFilter] is excluding this entry from the list, then that filter. Always null for
     * [GroupEntry]s.
     */
    var excludingFilter: NotifFilter?,

    /**
     * The [NotifPromoter] promoting this entry to top-level, if any. Always null for [GroupEntry]s.
     */
    var promoter: NotifPromoter?,

    /**
     * If an entry's group was pruned from the list by NotifPipeline logic, the reason is here.
     */
    var groupPruneReason: String?,

    /**
     * If the [VisualStabilityManager] is suppressing group or section changes for this entry,
     * suppressedChanges will contain the new parent or section that we would have assigned to
     * the entry had it not been suppressed by the VisualStabilityManager.
     */
    var suppressedChanges: SuppressedAttachState
) {

    /**
     * Identifies the notification order in the entire notification list.
     * NOTE: this property is intentionally excluded from equals calculation (by not making it a
     *  constructor arg) because its value changes based on the presence of other members in the
     *  list, rather than anything having to do with this entry's attachment.
     */
    var stableIndex: Int = -1

    /** Copies the state of another instance. */
    fun clone(other: ListAttachState) {
        parent = other.parent
        section = other.section
        excludingFilter = other.excludingFilter
        promoter = other.promoter
        groupPruneReason = other.groupPruneReason
        suppressedChanges.clone(other.suppressedChanges)
        stableIndex = other.stableIndex
    }

    /** Resets back to a "clean" state (the same as created by the factory method) */
    fun reset() {
        parent = null
        section = null
        excludingFilter = null
        promoter = null
        groupPruneReason = null
        suppressedChanges.reset()
        stableIndex = -1
    }

    /**
     * Erases bookkeeping traces stored on an entry when it is removed from the notif list.
     * This can happen if the entry is removed from a group that was broken up or if the entry was
     * filtered out during any of the filtering steps.
     */
    fun detach() {
        parent = null
        section = null
        promoter = null
        // stableIndex = -1  // TODO(b/241229236): Clear this once we fix the stability fragility
    }

    companion object {
        @JvmStatic
        fun create(): ListAttachState {
            return ListAttachState(
                null,
                null,
                null,
                null,
                null,
                SuppressedAttachState.create()
            )
        }
    }
}
