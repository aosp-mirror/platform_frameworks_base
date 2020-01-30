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

import androidx.annotation.Nullable;

import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.notification.collection.notifcollection.CommonNotifCollection;
import com.android.systemui.statusbar.notification.collection.notifcollection.NotifCollectionListener;
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
    NotifRemoteViewCacheImpl(CommonNotifCollection collection) {
        collection.addCollectionListener(mCollectionListener);
    }

    @Override
    public boolean hasCachedView(NotificationEntry entry, @InflationFlag int flag) {
        return getCachedView(entry, flag) != null;
    }

    @Override
    public @Nullable RemoteViews getCachedView(NotificationEntry entry, @InflationFlag int flag) {
        SparseArray<RemoteViews> contentViews = mNotifCachedContentViews.get(entry);
        if (contentViews == null) {
            return null;
        }
        return contentViews.get(flag);
    }

    @Override
    public void putCachedView(
            NotificationEntry entry,
            @InflationFlag int flag,
            RemoteViews remoteView) {
        /**
         * TODO: We should be more strict here in the future (i.e. throw an exception) if the
         * content views aren't created. We don't do that right now because we have edge cases
         * where we may bind/unbind content after a notification is removed.
         */
        SparseArray<RemoteViews> contentViews = mNotifCachedContentViews.get(entry);
        if (contentViews == null) {
            return;
        }
        contentViews.put(flag, remoteView);
    }

    @Override
    public void removeCachedView(NotificationEntry entry, @InflationFlag int flag) {
        SparseArray<RemoteViews> contentViews = mNotifCachedContentViews.get(entry);
        if (contentViews == null) {
            return;
        }
        contentViews.remove(flag);
    }

    @Override
    public void clearCache(NotificationEntry entry) {
        SparseArray<RemoteViews> contentViews = mNotifCachedContentViews.get(entry);
        if (contentViews == null) {
            return;
        }
        contentViews.clear();
    }

    private final NotifCollectionListener mCollectionListener = new NotifCollectionListener() {
        @Override
        public void onEntryInit(NotificationEntry entry) {
            mNotifCachedContentViews.put(entry, new SparseArray<>());
        }

        @Override
        public void onEntryCleanUp(NotificationEntry entry) {
            mNotifCachedContentViews.remove(entry);
        }
    };
}
