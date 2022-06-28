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
package com.android.systemui.statusbar.notification.collection.listbuilder.pluggable

import com.android.systemui.statusbar.notification.collection.GroupEntry
import com.android.systemui.statusbar.notification.collection.ListEntry
import com.android.systemui.statusbar.notification.collection.NotificationEntry

/**
 * Pluggable for participating in notif stabilization. In particular, suppressing group and
 * section changes.
 *
 * The stability manager should be invalidated when previously suppressed a group or
 * section change is now allowed.
 */
abstract class NotifStabilityManager protected constructor(name: String) :
    Pluggable<NotifStabilityManager>(name) {

    /**
     * Called prior to running the pipeline to suppress any visual changes. Ex: collapse animation
     * is playing, moving stuff around simultaneously will look janky.
     *
     * Note: this is invoked *before* [onBeginRun], so that implementors can reference state
     * maintained from a previous run.
     */
    abstract fun isPipelineRunAllowed(): Boolean

    /**
     * Called at the beginning of every pipeline run to perform any necessary cleanup from the
     * previous run.
     */
    abstract fun onBeginRun()

    /**
     * Returns whether this notification can currently change groups/parents.
     * Per iteration of the notification pipeline, locally stores this information until the next
     * run of the pipeline. When this method returns false, it's expected that a group change for
     * this entry is being suppressed.
     */
    abstract fun isGroupChangeAllowed(entry: NotificationEntry): Boolean

    /**
     * Returns whether this notification group can be pruned for not having enough children.
     * Per iteration of the notification pipeline, locally stores this information until the next
     * run of the pipeline. When this method returns false, it's expected that a group prune for
     * this entry is being suppressed.
     */
    abstract fun isGroupPruneAllowed(entry: GroupEntry): Boolean

    /**
     * Returns whether this notification entry can currently change sections.
     * Per iteration of the notification pipeline, locally stores this information until the next
     * run of the pipeline. When this method returns false, it's expected that a section change is
     * being suppressed.
     */
    abstract fun isSectionChangeAllowed(entry: NotificationEntry): Boolean

    /**
     * Returns whether this list entry is allowed to be reordered within its section.
     * Unlike [isGroupChangeAllowed] or [isSectionChangeAllowed], this method is called on every
     * entry, so an implementation may not assume that returning false means an order change is
     * being suppressed. However, if an order change is suppressed, that will be reported to ths
     * implementation by calling [onEntryReorderSuppressed] after ordering is complete.
     */
    abstract fun isEntryReorderingAllowed(entry: ListEntry): Boolean

    /**
     * Called by the pipeline to determine if every call to the other stability methods would
     * return true, regardless of parameters.  This allows the pipeline to skip any pieces of
     * work related to stability.
     *
     * @return true if all other methods will return true for any parameters.
     */
    abstract fun isEveryChangeAllowed(): Boolean

    /**
     * Called by the pipeline to inform the stability manager that an entry reordering was indeed
     * suppressed as the result of a previous call to [.isEntryReorderingAllowed].
     */
    abstract fun onEntryReorderSuppressed()
}

/** The default, no-op instance of the stability manager which always allows all changes */
object DefaultNotifStabilityManager : NotifStabilityManager("DefaultNotifStabilityManager") {
    override fun isPipelineRunAllowed(): Boolean = true
    override fun onBeginRun() {}
    override fun isGroupChangeAllowed(entry: NotificationEntry): Boolean = true
    override fun isGroupPruneAllowed(entry: GroupEntry): Boolean = true
    override fun isSectionChangeAllowed(entry: NotificationEntry): Boolean = true
    override fun isEntryReorderingAllowed(entry: ListEntry): Boolean = true
    override fun isEveryChangeAllowed(): Boolean = true
    override fun onEntryReorderSuppressed() {}
}
