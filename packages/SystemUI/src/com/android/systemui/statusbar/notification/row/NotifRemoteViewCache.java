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

import android.widget.RemoteViews;

import androidx.annotation.Nullable;

import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.notification.row.NotificationRowContentBinder.InflationFlag;

/**
 * Caches {@link RemoteViews} for a notification's content views.
 */
public interface NotifRemoteViewCache {

    /**
     * Whether the notification has the remote view cached
     *
     * @param entry notification
     * @param flag inflation flag for content view
     * @return true if the remote view is cached
     */
    boolean hasCachedView(NotificationEntry entry, @InflationFlag int flag);

    /**
     * Get the remote view for the content flag specified.
     *
     * @param entry notification
     * @param flag inflation flag for the content view
     * @return the remote view if it is cached, null otherwise
     */
    @Nullable RemoteViews getCachedView(NotificationEntry entry, @InflationFlag int flag);

    /**
     * Cache a remote view for a given content flag on a notification.
     *
     * @param entry notification
     * @param flag inflation flag for the content view
     * @param remoteView remote view to store
     */
    void putCachedView(
            NotificationEntry entry,
            @InflationFlag int flag,
            RemoteViews remoteView);

    /**
     * Remove a cached remote view for a given content flag on a notification.
     *
     * @param entry notification
     * @param flag inflation flag for the content view
     */
    void removeCachedView(NotificationEntry entry, @InflationFlag int flag);

    /**
     * Clear a notification's remote view cache.
     *
     * @param entry notification
     */
    void clearCache(NotificationEntry entry);
}
