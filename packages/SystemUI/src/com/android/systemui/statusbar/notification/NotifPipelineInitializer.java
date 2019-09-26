/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.systemui.statusbar.notification;

import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Log;

import com.android.systemui.statusbar.NotificationListener;

import javax.inject.Inject;

/**
 * Initialization code for the new notification pipeline.
 */
public class NotifPipelineInitializer {

    @Inject
    public NotifPipelineInitializer() {
    }

    public void initialize(
            NotificationListener notificationService) {

        // TODO Put real code here
        notificationService.setDownstreamListener(new NotificationListener.NotifServiceListener() {
            @Override
            public void onNotificationPosted(StatusBarNotification sbn,
                    NotificationListenerService.RankingMap rankingMap) {
                Log.d(TAG, "onNotificationPosted " + sbn.getKey());
            }

            @Override
            public void onNotificationRemoved(StatusBarNotification sbn,
                    NotificationListenerService.RankingMap rankingMap) {
                Log.d(TAG, "onNotificationRemoved " + sbn.getKey());
            }

            @Override
            public void onNotificationRemoved(StatusBarNotification sbn,
                    NotificationListenerService.RankingMap rankingMap, int reason) {
                Log.d(TAG, "onNotificationRemoved " + sbn.getKey());
            }

            @Override
            public void onNotificationRankingUpdate(
                    NotificationListenerService.RankingMap rankingMap) {
                Log.d(TAG, "onNotificationRankingUpdate");
            }
        });
    }

    private static final String TAG = "NotifInitializer";
}
