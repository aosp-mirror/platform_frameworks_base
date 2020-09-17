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

package com.android.systemui.statusbar.notification.collection;

import static android.service.notification.NotificationStats.DISMISS_SENTIMENT_NEUTRAL;

import android.service.notification.NotificationStats;

import com.android.internal.statusbar.IStatusBarService;
import com.android.internal.statusbar.NotificationVisibility;
import com.android.systemui.statusbar.notification.InflationException;
import com.android.systemui.statusbar.notification.collection.inflation.NotifInflater;
import com.android.systemui.statusbar.notification.collection.inflation.NotificationRowBinderImpl;
import com.android.systemui.statusbar.notification.collection.notifcollection.DismissedByUserStats;
import com.android.systemui.statusbar.notification.logging.NotificationLogger;
import com.android.systemui.statusbar.notification.row.NotifInflationErrorManager;
import com.android.systemui.statusbar.notification.row.NotificationContentInflater;

import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Handles notification inflating, rebinding, and inflation aborting.
 *
 * Currently a wrapper for NotificationRowBinderImpl.
 */
@Singleton
public class NotifInflaterImpl implements NotifInflater {

    private final IStatusBarService mStatusBarService;
    private final NotifCollection mNotifCollection;
    private final NotifInflationErrorManager mNotifErrorManager;
    private final NotifPipeline mNotifPipeline;

    private NotificationRowBinderImpl mNotificationRowBinder;

    @Inject
    public NotifInflaterImpl(
            IStatusBarService statusBarService,
            NotifCollection notifCollection,
            NotifInflationErrorManager errorManager,
            NotifPipeline notifPipeline) {
        mStatusBarService = statusBarService;
        mNotifCollection = notifCollection;
        mNotifErrorManager = errorManager;
        mNotifPipeline = notifPipeline;
    }

    /**
     * Attaches the row binder for inflation.
     */
    public void setRowBinder(NotificationRowBinderImpl rowBinder) {
        mNotificationRowBinder = rowBinder;
    }

    @Override
    public void rebindViews(NotificationEntry entry, InflationCallback callback) {
        inflateViews(entry, callback);
    }

    /**
     * Called to inflate the views of an entry.  Views are not considered inflated until all of its
     * views are bound.
     */
    @Override
    public void inflateViews(NotificationEntry entry, InflationCallback callback) {
        try {
            requireBinder().inflateViews(
                    entry,
                    getDismissCallback(entry),
                    wrapInflationCallback(callback));
        } catch (InflationException e) {
            mNotifErrorManager.setInflationError(entry, e);
        }
    }

    @Override
    public void abortInflation(NotificationEntry entry) {
        entry.abortTask();
    }

    private Runnable getDismissCallback(NotificationEntry entry) {
        return new Runnable() {
            @Override
            public void run() {
                int dismissalSurface = NotificationStats.DISMISSAL_SHADE;
                /*
                 * TODO: determine dismissal surface (ie: shade / headsup / aod)
                 * see {@link NotificationLogger#logNotificationClear}
                 */
                mNotifCollection.dismissNotification(
                        entry,
                        new DismissedByUserStats(
                                dismissalSurface,
                                DISMISS_SENTIMENT_NEUTRAL,
                                NotificationVisibility.obtain(entry.getKey(),
                                        entry.getRanking().getRank(),
                                        mNotifPipeline.getShadeListCount(),
                                        true,
                                        NotificationLogger.getNotificationLocation(entry))
                        ));
            }
        };
    }

    private NotificationContentInflater.InflationCallback wrapInflationCallback(
            InflationCallback callback) {
        return new NotificationContentInflater.InflationCallback() {
            @Override
            public void handleInflationException(
                    NotificationEntry entry,
                    Exception e) {
                mNotifErrorManager.setInflationError(entry, e);
            }

            @Override
            public void onAsyncInflationFinished(NotificationEntry entry) {
                mNotifErrorManager.clearInflationError(entry);
                if (callback != null) {
                    callback.onInflationFinished(entry);
                }
            }
        };
    }

    private NotificationRowBinderImpl requireBinder() {
        if (mNotificationRowBinder == null) {
            throw new RuntimeException("NotificationRowBinder must be attached before using "
                    + "NotifInflaterImpl.");
        }
        return mNotificationRowBinder;
    }
}
