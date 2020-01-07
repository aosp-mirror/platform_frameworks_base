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

import android.os.RemoteException;
import android.service.notification.NotificationStats;
import android.service.notification.StatusBarNotification;

import com.android.internal.statusbar.IStatusBarService;
import com.android.internal.statusbar.NotificationVisibility;
import com.android.systemui.statusbar.notification.InflationException;
import com.android.systemui.statusbar.notification.logging.NotificationLogger;
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

    private NotificationRowBinderImpl mNotificationRowBinder;
    private InflationCallback mExternalInflationCallback;

    @Inject
    public NotifInflaterImpl(
            IStatusBarService statusBarService,
            NotifCollection notifCollection) {
        mStatusBarService = statusBarService;
        mNotifCollection = notifCollection;
    }

    /**
     * Attaches the row binder for inflation.
     */
    public void setRowBinder(NotificationRowBinderImpl rowBinder) {
        mNotificationRowBinder = rowBinder;
        mNotificationRowBinder.setInflationCallback(mInflationCallback);
    }

    @Override
    public void setInflationCallback(InflationCallback callback) {
        mExternalInflationCallback = callback;
    }

    @Override
    public void rebindViews(NotificationEntry entry) {
        inflateViews(entry);
    }

    /**
     * Called to inflate the views of an entry.  Views are not considered inflated until all of its
     * views are bound.
     */
    @Override
    public void inflateViews(NotificationEntry entry) {
        try {
            entry.setHasInflationError(false);
            requireBinder().inflateViews(entry, getDismissCallback(entry));
        } catch (InflationException e) {
            // logged in mInflationCallback.handleInflationException
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
                /**
                 * TODO: determine dismissal surface (ie: shade / headsup / aod)
                 * see {@link NotificationLogger#logNotificationClear}
                 */
                mNotifCollection.dismissNotification(
                        entry,
                        0,
                        new DismissedByUserStats(
                                dismissalSurface,
                                DISMISS_SENTIMENT_NEUTRAL,
                                NotificationVisibility.obtain(entry.getKey(),
                                        entry.getRanking().getRank(),
                                        mNotifCollection.getNotifs().size(),
                                        true,
                                        NotificationLogger.getNotificationLocation(entry))
                        ));
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

    private final NotificationContentInflater.InflationCallback mInflationCallback =
            new NotificationContentInflater.InflationCallback() {
                @Override
                public void handleInflationException(
                        NotificationEntry entry,
                        Exception e) {
                    entry.setHasInflationError(true);
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
                }

                @Override
                public void onAsyncInflationFinished(
                        NotificationEntry entry,
                        int inflatedFlags) {
                    if (mExternalInflationCallback != null) {
                        mExternalInflationCallback.onInflationFinished(entry);
                    }
                }
            };
}
