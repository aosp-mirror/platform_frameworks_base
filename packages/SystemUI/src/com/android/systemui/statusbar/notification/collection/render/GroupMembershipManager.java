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

import android.annotation.NonNull;
import android.annotation.Nullable;

import com.android.systemui.statusbar.notification.collection.ListEntry;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;

import java.util.List;

/**
 * Helper that determines the group states (parent, summary, children) of a notification. This
 * generally assumes that the notification is attached (aka its parent is not null).
 */
public interface GroupMembershipManager {
    /**
     * @return whether a given notification is the summary in a group which has children
     */
    boolean isGroupSummary(@NonNull NotificationEntry entry);

    /**
     * Get the summary of a specified status bar notification. For an isolated notification this
     * returns null, but if called directly on a summary it returns itself.
     */
    @Nullable
    NotificationEntry getGroupSummary(@NonNull NotificationEntry entry);

    /**
     * Similar to {@link #getGroupSummary(NotificationEntry)} but doesn't get the visual summary
     * but the logical summary, i.e when a child is isolated, it still returns the summary as if
     * it wasn't isolated.
     * TODO: remove this when migrating to the new pipeline, this is taken care of in the
     * dismissal logic built into NotifCollection
     */
    @Nullable
    default NotificationEntry getLogicalGroupSummary(@NonNull NotificationEntry entry) {
        return getGroupSummary(entry);
    }

    /**
     * @return whether a given notification is a child in a group
     */
    boolean isChildInGroup(@NonNull NotificationEntry entry);

    /**
     * Whether this is the only child in a group
     */
    boolean isOnlyChildInGroup(@NonNull NotificationEntry entry);

    /**
     * Get the children that are in the summary's group, not including those isolated.
     *
     * @param summary summary of a group
     * @return list of the children
     */
    @Nullable
    List<NotificationEntry> getChildren(@NonNull ListEntry summary);
}
