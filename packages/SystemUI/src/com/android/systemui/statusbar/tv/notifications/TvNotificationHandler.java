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

package com.android.systemui.statusbar.tv.notifications;

import android.annotation.Nullable;
import android.app.Notification;
import android.content.Context;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Log;
import android.util.SparseArray;

import com.android.systemui.SystemUI;
import com.android.systemui.statusbar.NotificationListener;

import javax.inject.Inject;

/**
 * Keeps track of the notifications on TV.
 */
public class TvNotificationHandler extends SystemUI implements
        NotificationListener.NotificationHandler {
    private static final String TAG = "TvNotificationHandler";
    private final NotificationListener mNotificationListener;
    private final SparseArray<StatusBarNotification> mNotifications = new SparseArray<>();
    @Nullable
    private Listener mUpdateListener;

    @Inject
    public TvNotificationHandler(Context context, NotificationListener notificationListener) {
        super(context);
        mNotificationListener = notificationListener;
    }

    public SparseArray<StatusBarNotification> getCurrentNotifications() {
        return mNotifications;
    }

    public void setTvNotificationListener(Listener listener) {
        mUpdateListener = listener;
    }

    @Override
    public void start() {
        mNotificationListener.addNotificationHandler(this);
        mNotificationListener.registerAsSystemService();
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn,
            NotificationListenerService.RankingMap rankingMap) {
        if (!new Notification.TvExtender(sbn.getNotification()).isAvailableOnTv()) {
            Log.v(TAG, "Notification not added because it isn't relevant for tv");
            return;
        }

        mNotifications.put(sbn.getId(), sbn);
        if (mUpdateListener != null) {
            mUpdateListener.notificationsUpdated(mNotifications);
        }
        Log.d(TAG, "Notification added");
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn,
            NotificationListenerService.RankingMap rankingMap) {

        if (mNotifications.contains(sbn.getId())) {
            mNotifications.remove(sbn.getId());
            Log.d(TAG, "Notification removed");

            if (mUpdateListener != null) {
                mUpdateListener.notificationsUpdated(mNotifications);
            }
        }
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn,
            NotificationListenerService.RankingMap rankingMap, int reason) {
        onNotificationRemoved(sbn, rankingMap);
    }

    @Override
    public void onNotificationRankingUpdate(NotificationListenerService.RankingMap rankingMap) {
        // noop
    }

    @Override
    public void onNotificationsInitialized() {
        // noop
    }

    /**
     * Get notified when the notifications are updated.
     */
    interface Listener {
        void notificationsUpdated(SparseArray<StatusBarNotification> sbns);
    }

}
