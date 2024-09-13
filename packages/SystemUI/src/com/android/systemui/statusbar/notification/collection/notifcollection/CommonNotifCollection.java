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

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.systemui.statusbar.notification.collection.NotifPipeline;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;

import java.util.Collection;

/**
 * A notification collection that manages the list of {@link NotificationEntry}s that will be
 * rendered.
 */
public interface CommonNotifCollection {
    /**
     * Registers a listener to be informed when notifications are created, added, updated, removed,
     * or deleted.
     */
    void addCollectionListener(@NonNull NotifCollectionListener listener);

    /**
     * Unregisters a listener previously added with {@link #addCollectionListener}
     */
    void removeCollectionListener(@NonNull NotifCollectionListener listener);

    /**
     * Returns the list of all known notifications, i.e. the notifications that are currently posted
     * to the phone. In general, this tracks closely to the list maintained by NotificationManager,
     * but it can diverge slightly due to lifetime extenders.
     *
     * The returned collection is read-only, unsorted, unfiltered, and ungrouped.
     */
    @NonNull Collection<NotificationEntry> getAllNotifs();

    /**
     * Returns the notification entry for the given notification key;
     * the returned entry (if present) may be in any state.
     */
    @Nullable NotificationEntry getEntry(@NonNull String key);
}
