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

import android.os.RemoteException;
import android.service.notification.StatusBarNotification;

import com.android.internal.statusbar.IStatusBarService;
import com.android.systemui.statusbar.notification.collection.NotifInflaterImpl;
import com.android.systemui.statusbar.notification.collection.NotifPipeline;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.notification.collection.ShadeListBuilder;
import com.android.systemui.statusbar.notification.collection.inflation.NotifInflater;
import com.android.systemui.statusbar.notification.collection.listbuilder.pluggable.NotifFilter;
import com.android.systemui.statusbar.notification.collection.notifcollection.NotifCollectionListener;
import com.android.systemui.statusbar.notification.row.NotifInflationErrorManager;

import java.util.ArrayList;
import java.util.List;

import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Kicks off notification inflation and view rebinding when a notification is added or updated.
 * Aborts inflation when a notification is removed.
 *
 * If a notification is not done inflating, this coordinator will filter the notification out
 * from the {@link ShadeListBuilder}.
 */
@Singleton
public class PreparationCoordinator implements Coordinator {
    private static final String TAG = "PreparationCoordinator";

    private final PreparationCoordinatorLogger mLogger;
    private final NotifInflater mNotifInflater;
    private final NotifInflationErrorManager mNotifErrorManager;
    private final List<NotificationEntry> mPendingNotifications = new ArrayList<>();
    private final IStatusBarService mStatusBarService;

    @Inject
    public PreparationCoordinator(
            PreparationCoordinatorLogger logger,
            NotifInflaterImpl notifInflater,
            NotifInflationErrorManager errorManager,
            IStatusBarService service) {
        mLogger = logger;
        mNotifInflater = notifInflater;
        mNotifInflater.setInflationCallback(mInflationCallback);
        mNotifErrorManager = errorManager;
        mNotifErrorManager.addInflationErrorListener(mInflationErrorListener);
        mStatusBarService = service;
    }

    @Override
    public void attach(NotifPipeline pipeline) {
        pipeline.addCollectionListener(mNotifCollectionListener);
        pipeline.addFinalizeFilter(mNotifInflationErrorFilter);
        pipeline.addFinalizeFilter(mNotifInflatingFilter);
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
        public void onEntryRemoved(NotificationEntry entry, int reason) {
            abortInflation(entry, "entryRemoved reason=" + reason);
        }
    };

    private final NotifFilter mNotifInflationErrorFilter = new NotifFilter(
            TAG + "InflationError") {
        /**
         * Filters out notifications that threw an error when attempting to inflate.
         */
        @Override
        public boolean shouldFilterOut(NotificationEntry entry, long now) {
            if (mNotifErrorManager.hasInflationError(entry)) {
                return true;
            }
            return false;
        }
    };

    private final NotifFilter mNotifInflatingFilter = new NotifFilter(TAG + "Inflating") {
        /**
         * Filters out notifications that haven't been inflated yet
         */
        @Override
        public boolean shouldFilterOut(NotificationEntry entry, long now) {
            return mPendingNotifications.contains(entry);
        }
    };

    private final NotifInflater.InflationCallback mInflationCallback =
            new NotifInflater.InflationCallback() {
        @Override
        public void onInflationFinished(NotificationEntry entry) {
            mLogger.logNotifInflated(entry.getKey());
            mPendingNotifications.remove(entry);
            mNotifInflatingFilter.invalidateList();
        }
    };

    private final NotifInflationErrorManager.NotifInflationErrorListener mInflationErrorListener =
            new NotifInflationErrorManager.NotifInflationErrorListener() {
        @Override
        public void onNotifInflationError(NotificationEntry entry, Exception e) {
            mPendingNotifications.remove(entry);
            try {
                final StatusBarNotification sbn = entry.getSbn();
                // report notification inflation errors back up
                // to notification delegates
                mStatusBarService.onNotificationError(
                        sbn.getPackageName(),
                        sbn.getTag(),
                        sbn.getId(),
                        sbn.getUid(),
                        sbn.getInitialPid(),
                        e.getMessage(),
                        sbn.getUserId());
            } catch (RemoteException ex) {
            }
            mNotifInflationErrorFilter.invalidateList();
        }

        @Override
        public void onNotifInflationErrorCleared(NotificationEntry entry) {
            mNotifInflationErrorFilter.invalidateList();
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
        mLogger.logInflationAborted(entry.getKey(), reason);
        entry.abortTask();
        mPendingNotifications.remove(entry);
    }
}
