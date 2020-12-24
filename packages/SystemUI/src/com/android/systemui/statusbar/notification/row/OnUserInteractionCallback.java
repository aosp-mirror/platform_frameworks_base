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

package com.android.systemui.statusbar.notification.row;

import android.service.notification.NotificationListenerService;

import com.android.systemui.statusbar.notification.collection.NotificationEntry;

/**
 * Callbacks for when a user interacts with an {@link ExpandableNotificationRow}.
 */
public interface OnUserInteractionCallback {

    /**
     * Handle a user interaction that triggers a notification dismissal. Called when a user clicks
     * on an auto-cancelled notification or manually swipes to dismiss the notification.
     *
     * @param entry notification being dismissed
     * @param cancellationReason reason for the cancellation
     * @param groupSummaryToDismiss group summary to dismiss with `entry`.
     */
    void onDismiss(
            NotificationEntry entry,
            @NotificationListenerService.NotificationCancelReason int cancellationReason,
            NotificationEntry groupSummaryToDismiss);

    /**
     * Triggered after a user has changed the importance of the notification via its
     * {@link NotificationGuts}.
     */
    void onImportanceChanged(NotificationEntry entry);


    /**
     * @param entry being dismissed by the user
     * @return group summary that should be dismissed along with `entry`. Can be null if no
     * relevant group summary exists or the group summary should not be dismissed with `entry`.
     */
    NotificationEntry getGroupSummaryToDismiss(NotificationEntry entry);
}
