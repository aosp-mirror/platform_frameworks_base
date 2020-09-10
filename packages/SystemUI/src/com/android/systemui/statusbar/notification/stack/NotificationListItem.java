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

package com.android.systemui.statusbar.notification.stack;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.view.View;

import com.android.systemui.statusbar.notification.VisualStabilityManager;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;

import java.util.List;

/**
* A NotificationListItem is a child view of the notification list that can yield a
* NotificationEntry when asked. I.e., it's an ExpandableNotificationRow but doesn't require us
* to strictly rely on ExpandableNotificationRow as our consumed type
 */
public interface NotificationListItem {
    /** @return entry for this item */
    @NonNull
    NotificationEntry getEntry();

    /** @return true if the blocking helper is showing */
    boolean isBlockingHelperShowing();

    /** @return true if this list item is a summary with children */
    boolean isSummaryWithChildren();

    // This generic is kind of ugly - we should change this once the old VHM is gone
    /** @return list of the children of this item */
    List<? extends NotificationListItem> getAttachedChildren();

    /** remove all children from this list item */
    void removeAllChildren();

    /** remove particular child */
    void removeChildNotification(NotificationListItem child);

    /** add an item as a child */
    void addChildNotification(NotificationListItem child, int childIndex);

    /** set the child count view should display */
    void setUntruncatedChildCount(int count);

    /** Update the order of the children with the new list */
    boolean applyChildOrder(
            List<? extends NotificationListItem> childOrderList,
            VisualStabilityManager vsm,
            @Nullable VisualStabilityManager.Callback callback);

    /** return the associated view for this list item */
    @NonNull
    View getView();
}
