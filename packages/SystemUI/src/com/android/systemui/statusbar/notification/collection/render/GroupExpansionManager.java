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

package com.android.systemui.statusbar.notification.collection.render;

import com.android.systemui.Dumpable;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.notification.row.ExpandableNotificationRow;

/**
 * Tracks expanded notification states for groups. This expanded state should not be confused by the
 * expanded/collapsed state of a single notification which is tracked within each
 * ExpandableNotificationRow.
 */
public interface GroupExpansionManager extends Dumpable {

    /**
     * Register a listener for group expansion changes
     */
    void registerGroupExpansionChangeListener(OnGroupExpansionChangeListener listener);

    /**
     * Whether the group associated with this notification is expanded.
     * If this notification is not part of a group, it will always return false.
     */
    boolean isGroupExpanded(NotificationEntry entry);

    /**
     * Set whether the group associated with this notification is expanded or not.
     */
    void setGroupExpanded(NotificationEntry entry, boolean expanded);

    /** @return group expansion state after toggling. */
    boolean toggleGroupExpansion(NotificationEntry entry);

    /**
     * Set expanded=false for all groups
     */
    void collapseGroups();

    /**
     * Listener for group expansion changes.
     */
    interface OnGroupExpansionChangeListener {
        /**
         * The expansion of a group has changed.
         *
         * @param changedRow the row for which the expansion has changed, which is also the summary
         * @param expanded a boolean indicating the new expanded state
         */
        void onGroupExpansionChange(ExpandableNotificationRow changedRow, boolean expanded);
    }
}
