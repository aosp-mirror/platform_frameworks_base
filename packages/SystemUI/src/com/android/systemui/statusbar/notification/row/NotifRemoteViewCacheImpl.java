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

import android.util.ArrayMap;
import android.util.SparseArray;
import android.widget.RemoteViews;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.internal.statusbar.NotificationVisibility;
import com.android.systemui.statusbar.notification.NotificationEntryListener;
import com.android.systemui.statusbar.notification.NotificationEntryManager;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.notification.row.NotificationRowContentBinder.InflationFlag;

import java.util.Map;

import javax.inject.Inject;

/**
 * Implementation of remote view cache that keeps remote views cached for all active notifications.
 */
public class NotifRemoteViewCacheImpl implements NotifRemoteViewCache {
    private final Map<NotificationEntry, SparseArray<RemoteViews>> mNotifCachedContentViews =
            new ArrayMap<>();

    @Inject
    NotifRemoteViewCacheImpl(NotificationEntryManager entryManager) {
        entryManager.addNotificationEntryListener(mEntryListener);
    }

    @Override
    public boolean hasCachedView(NotificationEntry entry, @InflationFlag int flag) {
        return getCachedView(entry, flag) != null;
    }

    @Override
    public @Nullable RemoteViews getCachedView(NotificationEntry entry, @InflationFlag int flag) {
        return getContentViews(entry).get(flag);
    }

    @Override
    public void putCachedView(
            NotificationEntry entry,
            @InflationFlag int flag,
            RemoteViews remoteView) {
        getContentViews(entry).put(flag, remoteView);
    }

    @Override
    public void removeCachedView(NotificationEntry entry, @InflationFlag int flag) {
        getContentViews(entry).remove(flag);
    }

    @Override
    public void clearCache(NotificationEntry entry) {
        getContentViews(entry).clear();
    }

    private @NonNull SparseArray<RemoteViews> getContentViews(NotificationEntry entry) {
        SparseArray<RemoteViews> contentViews = mNotifCachedContentViews.get(entry);
        if (contentViews == null) {
            throw new IllegalStateException(
                    String.format("Remote view cache was never created for notification %s",
                            entry.getKey()));
        }
        return contentViews;
    }

    private final NotificationEntryListener mEntryListener = new NotificationEntryListener() {
        @Override
        public void onPendingEntryAdded(NotificationEntry entry) {
            mNotifCachedContentViews.put(entry, new SparseArray<>());
        }

        @Override
        public void onEntryRemoved(
                NotificationEntry entry,
                @Nullable NotificationVisibility visibility,
                boolean removedByUser) {
            mNotifCachedContentViews.remove(entry);
        }
    };
}
