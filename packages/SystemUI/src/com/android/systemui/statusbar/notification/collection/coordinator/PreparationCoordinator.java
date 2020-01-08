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

package com.android.systemui.statusbar.notification.collection.coordinator;

import com.android.systemui.statusbar.notification.collection.NotifCollection;
import com.android.systemui.statusbar.notification.collection.NotifCollectionListener;
import com.android.systemui.statusbar.notification.collection.NotifInflater;
import com.android.systemui.statusbar.notification.collection.NotifInflaterImpl;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.notification.collection.listbuilder.NotifListBuilder;
import com.android.systemui.statusbar.notification.collection.listbuilder.pluggable.NotifFilter;
import com.android.systemui.statusbar.notification.logging.NotifEvent;
import com.android.systemui.statusbar.notification.logging.NotifLog;

import java.util.ArrayList;
import java.util.List;

import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Kicks off notification inflation and view rebinding when a notification is added or updated.
 * Aborts inflation when a notification is removed.
 *
 * If a notification is not done inflating, this coordinator will filter the notification out
 * from the NotifListBuilder.
 */
@Singleton
public class PreparationCoordinator implements Coordinator {
    private static final String TAG = "PreparationCoordinator";

    private final NotifLog mNotifLog;
    private final NotifInflater mNotifInflater;
    private final List<NotificationEntry> mPendingNotifications = new ArrayList<>();

    @Inject
    public PreparationCoordinator(NotifLog notifLog, NotifInflaterImpl notifInflater) {
        mNotifLog = notifLog;
        mNotifInflater = notifInflater;
        mNotifInflater.setInflationCallback(mInflationCallback);
    }

    @Override
    public void attach(NotifCollection notifCollection, NotifListBuilder notifListBuilder) {
        notifCollection.addCollectionListener(mNotifCollectionListener);
        notifListBuilder.addPreRenderFilter(mNotifFilter);
    }

    private final NotifCollectionListener mNotifCollectionListener = new NotifCollectionListener() {
        @Override
        public void onEntryAdded(NotificationEntry entry) {
            inflateEntry(entry, "entryAdded");
        }

        @Override
        public void onEntryUpdated(NotificationEntry entry) {
            rebind(entry, "entryUpdated");
        }

        @Override
        public void onEntryRemoved(NotificationEntry entry, int reason, boolean removedByUser) {
            abortInflation(entry, "entryRemoved reason=" + reason);
        }
    };

    private final NotifFilter mNotifFilter = new NotifFilter(TAG) {
        @Override
        public boolean shouldFilterOut(NotificationEntry entry, long now) {
            return mPendingNotifications.contains(entry);
        }
    };

    private final NotifInflater.InflationCallback mInflationCallback =
            new NotifInflater.InflationCallback() {
        @Override
        public void onInflationFinished(NotificationEntry entry) {
            mNotifLog.log(NotifEvent.INFLATED, entry);
            mPendingNotifications.remove(entry);
            mNotifFilter.invalidateList();
        }
    };

    private void inflateEntry(NotificationEntry entry, String reason) {
        abortInflation(entry, reason);
        mPendingNotifications.add(entry);
        mNotifInflater.inflateViews(entry);
    }

    private void rebind(NotificationEntry entry, String reason) {
        mNotifInflater.rebindViews(entry);
    }

    private void abortInflation(NotificationEntry entry, String reason) {
        mNotifLog.log(NotifEvent.INFLATION_ABORTED, reason);
        entry.abortTask();
        mPendingNotifications.remove(entry);
    }
}
