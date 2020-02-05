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

package com.android.systemui.statusbar.notification.collection.notifcollection;

import android.service.notification.NotificationListenerService;

import com.android.systemui.statusbar.notification.collection.NotifCollection.CancellationReason;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;

/**
 * Listener interface for {@link NotificationEntry} events.
 */
public interface NotifCollectionListener {
    /**
     * Called whenever a new {@link NotificationEntry} is initialized. This should be used for
     * initializing any decorated state tied to the notification.
     *
     * Do not reference other registered {@link NotifCollectionListener} implementations here as
     * there is no guarantee of order and they may not have had a chance to initialize yet. Instead,
     * use {@link #onEntryAdded} which is called after all initialization.
     */
    default void onEntryInit(NotificationEntry entry) {
    }

    /**
     * Called whenever a notification with a new key is posted.
     */
    default void onEntryAdded(NotificationEntry entry) {
    }

    /**
     * Called whenever a notification with the same key as an existing notification is posted. By
     * the time this listener is called, the entry's SBN and Ranking will already have been updated.
     */
    default void onEntryUpdated(NotificationEntry entry) {
    }

    /**
     * Called whenever a notification is retracted by system server. This method is not called
     * immediately after a user dismisses a notification: we wait until we receive confirmation from
     * system server before considering the notification removed.
     */
    default void onEntryRemoved(NotificationEntry entry, @CancellationReason int reason) {
    }

    /**
     * Called whenever a {@link NotificationEntry} is considered deleted. This should be used for
     * cleaning up any state tied to the notification.
     *
     * This is the deletion parallel of {@link #onEntryInit} and similarly means that you cannot
     * expect other {@link NotifCollectionListener} implementations to still have valid state for
     * the entry during this call. Instead, use {@link #onEntryRemoved} which will be called before
     * deletion.
     */
    default void onEntryCleanUp(NotificationEntry entry) {
    }

    /**
     * Called whenever the RankingMap is updated by system server. By the time this listener is
     * called, the Rankings of all entries will have been updated.
     */
    default void onRankingUpdate(NotificationListenerService.RankingMap rankingMap) {
    }
}
