/*
 * Copyright (C) 2018 The Android Open Source Project
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
package com.android.systemui.statusbar.notification;

import android.annotation.Nullable;
import android.service.notification.StatusBarNotification;

import com.android.internal.statusbar.NotificationVisibility;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.notification.row.NotificationContentInflater.InflationFlag;

/**
 * Listener interface for changes sent by NotificationEntryManager.
 */
public interface NotificationEntryListener {
    /**
     * Called when a new notification is posted. At this point, the notification is "pending": its
     * views haven't been inflated yet and most of the system pretends like it doesn't exist yet.
     */
    default void onPendingEntryAdded(NotificationEntry entry) {
    }

    // TODO: Combine this with onPreEntryUpdated into "onBeforeEntryFiltered" or similar
    /**
     * Called when a new entry is created but before it has been filtered or displayed to the user.
     */
    default void onBeforeNotificationAdded(NotificationEntry entry) {
    }

    /**
     * Called when a new entry is created.
     */
    default void onNotificationAdded(NotificationEntry entry) {
    }

    /**
     * Called when a notification is about to be updated. Notification- and ranking-derived fields
     * on the entry have already been updated but the following have not yet occurred:
     * (a) View binding (i.e. the associated view has not yet been updated / inflation has not yet
     *      been kicked off.
     * (b) Notification filtering
     */
    default void onPreEntryUpdated(NotificationEntry entry) {
    }

    /**
     * Called when a notification was updated, after any filtering of notifications have occurred.
     */
    default void onPostEntryUpdated(NotificationEntry entry) {
    }

    /**
     * Called when a notification's views are inflated for the first time.
     */
    default void onEntryInflated(NotificationEntry entry, @InflationFlag int inflatedFlags) {
    }

    /**
     * Called when an existing notification's views are reinflated (usually due to an update being
     * posted to that notification).
     *
     * @param entry notification data entry that was reinflated.
     */
    default void onEntryReinflated(NotificationEntry entry) {
    }

    /**
     * Called when an error occurred inflating the views for a notification.
     */
    default void onInflationError(StatusBarNotification notification, Exception exception) {
    }

    /**
     * Called when a notification has been removed (either because the user swiped it away or
     * because the developer retracted it).
     * @param entry notification data entry that was removed.  Null if no entry existed for the
     *              removed key at the time of removal.
     * @param visibility logging data related to the visibility of the notification at the time of
     *                   removal, if it was removed by a user action.  Null if it was not removed by
     *                   a user action.
     * @param removedByUser true if the notification was removed by a user action
     */
    default void onEntryRemoved(
            NotificationEntry entry,
            @Nullable NotificationVisibility visibility,
            boolean removedByUser) {
    }
}
