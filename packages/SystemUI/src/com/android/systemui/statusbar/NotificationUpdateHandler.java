/*
 * Copyright (C) 2017 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.systemui.statusbar;

import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

/**
 * Interface for accepting notification updates from {@link NotificationListener}.
 */
public interface NotificationUpdateHandler {
    /**
     * Add a new notification and update the current notification ranking map.
     *
     * @param notification Notification to add
     * @param ranking RankingMap to update with
     */
    void addNotification(StatusBarNotification notification,
            NotificationListenerService.RankingMap ranking);

    /**
     * Remove a notification and update the current notification ranking map.
     *
     * @param key Key identifying the notification to remove
     * @param ranking RankingMap to update with
     */
    void removeNotification(String key, NotificationListenerService.RankingMap ranking);

    /**
     * Update a given notification and the current notification ranking map.
     *
     * @param notification Updated notification
     * @param ranking RankingMap to update with
     */
    void updateNotification(StatusBarNotification notification,
            NotificationListenerService.RankingMap ranking);

    /**
     * Update with a new notification ranking map.
     *
     * @param ranking RankingMap to update with
     */
    void updateNotificationRanking(NotificationListenerService.RankingMap ranking);
}
