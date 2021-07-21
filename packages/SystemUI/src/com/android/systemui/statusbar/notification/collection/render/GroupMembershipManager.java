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

import android.annotation.Nullable;

import com.android.systemui.statusbar.notification.collection.ListEntry;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;

import java.util.List;

/**
 * Helper that determines the group states (parent, summary, children) of a notification.
 */
public interface GroupMembershipManager {
    /**
     * @return whether a given notification is a top level entry or is the summary in a group which
     * has children
     */
    boolean isGroupSummary(NotificationEntry entry);

    /**
     * Get the summary of a specified status bar notification. For an isolated notification this
     * returns itself.
     */
    NotificationEntry getGroupSummary(NotificationEntry entry);

    /**
     * Similar to {@link #getGroupSummary(NotificationEntry)} but doesn't get the visual summary
     * but the logical summary, i.e when a child is isolated, it still returns the summary as if
     * it wasn't isolated.
     * TODO: remove this when migrating to the new pipeline, this is taken care of in the
     * dismissal logic built into NotifCollection
     */
    default NotificationEntry getLogicalGroupSummary(NotificationEntry entry) {
        return getGroupSummary(entry);
    }

    /**
     * @return whether a given notification is a child in a group
     */
    boolean isChildInGroup(NotificationEntry entry);

    /**
     * Whether this is the only child in a group
     */
    boolean isOnlyChildInGroup(NotificationEntry entry);

    /**
     * Get the children that are in the summary's group, not including those isolated.
     *
     * @param summary summary of a group
     * @return list of the children
     */
    @Nullable
    List<NotificationEntry> getChildren(ListEntry summary);
}
