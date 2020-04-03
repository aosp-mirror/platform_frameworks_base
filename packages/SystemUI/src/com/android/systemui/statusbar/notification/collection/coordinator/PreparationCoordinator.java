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

import android.annotation.IntDef;
import android.os.RemoteException;
import android.service.notification.StatusBarNotification;
import android.util.ArrayMap;

import com.android.internal.statusbar.IStatusBarService;
import com.android.systemui.statusbar.notification.collection.GroupEntry;
import com.android.systemui.statusbar.notification.collection.ListEntry;
import com.android.systemui.statusbar.notification.collection.NotifInflaterImpl;
import com.android.systemui.statusbar.notification.collection.NotifPipeline;
import com.android.systemui.statusbar.notification.collection.NotifViewBarn;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.notification.collection.ShadeListBuilder;
import com.android.systemui.statusbar.notification.collection.inflation.NotifInflater;
import com.android.systemui.statusbar.notification.collection.listbuilder.OnBeforeFinalizeFilterListener;
import com.android.systemui.statusbar.notification.collection.listbuilder.pluggable.NotifFilter;
import com.android.systemui.statusbar.notification.collection.notifcollection.NotifCollectionListener;
import com.android.systemui.statusbar.notification.row.NotifInflationErrorManager;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Kicks off core notification inflation and view rebinding when a notification is added or updated.
 * Aborts inflation when a notification is removed.
 *
 * If a notification was uninflated, this coordinator will filter the notification out from the
 * {@link ShadeListBuilder} until it is inflated.
 */
@Singleton
public class PreparationCoordinator implements Coordinator {
    private static final String TAG = "PreparationCoordinator";

    private final PreparationCoordinatorLogger mLogger;
    private final NotifInflater mNotifInflater;
    private final NotifInflationErrorManager mNotifErrorManager;
    private final NotifViewBarn mViewBarn;
    private final Map<NotificationEntry, Integer> mInflationStates = new ArrayMap<>();
    private final IStatusBarService mStatusBarService;

    @Inject
    public PreparationCoordinator(
            PreparationCoordinatorLogger logger,
            NotifInflaterImpl notifInflater,
            NotifInflationErrorManager errorManager,
            NotifViewBarn viewBarn,
            IStatusBarService service
    ) {
        mLogger = logger;
        mNotifInflater = notifInflater;
        mNotifInflater.setInflationCallback(mInflationCallback);
        mNotifErrorManager = errorManager;
        mNotifErrorManager.addInflationErrorListener(mInflationErrorListener);
        mViewBarn = viewBarn;
        mStatusBarService = service;
    }

    @Override
    public void attach(NotifPipeline pipeline) {
        pipeline.addCollectionListener(mNotifCollectionListener);
        // Inflate after grouping/sorting since that affects what views to inflate.
        pipeline.addOnBeforeFinalizeFilterListener(mOnBeforeFinalizeFilterListener);
        pipeline.addFinalizeFilter(mNotifInflationErrorFilter);
        pipeline.addFinalizeFilter(mNotifInflatingFilter);
    }

    private final NotifCollectionListener mNotifCollectionListener = new NotifCollectionListener() {

        @Override
        public void onEntryInit(NotificationEntry entry) {
            mInflationStates.put(entry, STATE_UNINFLATED);
        }

        @Override
        public void onEntryUpdated(NotificationEntry entry) {
            @InflationState int state = getInflationState(entry);
            if (state == STATE_INFLATED) {
                mInflationStates.put(entry, STATE_INFLATED_INVALID);
            } else if (state == STATE_ERROR) {
                // Updated so maybe it won't error out now.
                mInflationStates.put(entry, STATE_UNINFLATED);
            }
        }

        @Override
        public void onEntryRemoved(NotificationEntry entry, int reason) {
            abortInflation(entry, "entryRemoved reason=" + reason);
        }

        @Override
        public void onEntryCleanUp(NotificationEntry entry) {
            mInflationStates.remove(entry);
            mViewBarn.removeViewForEntry(entry);
        }
    };

    private final OnBeforeFinalizeFilterListener mOnBeforeFinalizeFilterListener =
            entries -> inflateAllRequiredViews(entries);

    private final NotifFilter mNotifInflationErrorFilter = new NotifFilter(
            TAG + "InflationError") {
        /**
         * Filters out notifications that threw an error when attempting to inflate.
         */
        @Override
        public boolean shouldFilterOut(NotificationEntry entry, long now) {
            return getInflationState(entry) == STATE_ERROR;
        }
    };

    private final NotifFilter mNotifInflatingFilter = new NotifFilter(TAG + "Inflating") {
        /**
         * Filters out notifications that haven't been inflated yet
         */
        @Override
        public boolean shouldFilterOut(NotificationEntry entry, long now) {
            @InflationState int state = getInflationState(entry);
            return (state != STATE_INFLATED) && (state != STATE_INFLATED_INVALID);
        }
    };

    private final NotifInflater.InflationCallback mInflationCallback =
            new NotifInflater.InflationCallback() {
        @Override
        public void onInflationFinished(NotificationEntry entry) {
            mLogger.logNotifInflated(entry.getKey());
            mViewBarn.registerViewForEntry(entry, entry.getRow());
            mInflationStates.put(entry, STATE_INFLATED);
            mNotifInflatingFilter.invalidateList();
        }
    };

    private final NotifInflationErrorManager.NotifInflationErrorListener mInflationErrorListener =
            new NotifInflationErrorManager.NotifInflationErrorListener() {
        @Override
        public void onNotifInflationError(NotificationEntry entry, Exception e) {
            mViewBarn.removeViewForEntry(entry);
            mInflationStates.put(entry, STATE_ERROR);
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

    private void inflateAllRequiredViews(List<ListEntry> entries) {
        for (int i = 0, size = entries.size(); i < size; i++) {
            ListEntry entry = entries.get(i);
            if (entry instanceof GroupEntry) {
                GroupEntry groupEntry = (GroupEntry) entry;
                inflateNotifRequiredViews(groupEntry.getSummary());
                List<NotificationEntry> children = groupEntry.getChildren();
                for (int j = 0, groupSize = children.size(); j < groupSize; j++) {
                    inflateNotifRequiredViews(children.get(j));
                }
            } else {
                NotificationEntry notifEntry = (NotificationEntry) entry;
                inflateNotifRequiredViews(notifEntry);
            }
        }
    }

    private void inflateNotifRequiredViews(NotificationEntry entry) {
        @InflationState int state = mInflationStates.get(entry);
        switch (state) {
            case STATE_UNINFLATED:
                inflateEntry(entry, "entryAdded");
                break;
            case STATE_INFLATED_INVALID:
                rebind(entry, "entryUpdated");
                break;
            case STATE_INFLATED:
            case STATE_ERROR:
            default:
                // Nothing to do.
        }
    }

    private void inflateEntry(NotificationEntry entry, String reason) {
        abortInflation(entry, reason);
        mNotifInflater.inflateViews(entry);
    }

    private void rebind(NotificationEntry entry, String reason) {
        mNotifInflater.rebindViews(entry);
    }

    private void abortInflation(NotificationEntry entry, String reason) {
        mLogger.logInflationAborted(entry.getKey(), reason);
        entry.abortTask();
    }

    private @InflationState int getInflationState(NotificationEntry entry) {
        Integer stateObj = mInflationStates.get(entry);
        Objects.requireNonNull(stateObj,
                "Asking state of a notification preparation coordinator doesn't know about");
        return stateObj;
    }

    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = {"STATE_"},
            value = {STATE_UNINFLATED, STATE_INFLATED_INVALID, STATE_INFLATED, STATE_ERROR})
    @interface InflationState {}

    /** The notification has never been inflated before. */
    private static final int STATE_UNINFLATED = 0;

    /** The notification is inflated. */
    private static final int STATE_INFLATED = 1;

    /**
     * The notification is inflated, but its content may be out-of-date since the notification has
     * been updated.
     */
    private static final int STATE_INFLATED_INVALID = 2;

    /** The notification errored out while inflating */
    private static final int STATE_ERROR = -1;
}
