/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.systemui.statusbar.notification.collection;

import com.android.systemui.statusbar.notification.collection.NotifCollection.CancellationReason;

/**
 * Listener interface for {@link NotifCollection}.
 */
public interface NotifCollectionListener {
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
     * Called immediately after a notification has been removed from the collection.
     */
    default void onEntryRemoved(
            NotificationEntry entry,
            @CancellationReason int reason,
            boolean removedByUser) {
    }
}
