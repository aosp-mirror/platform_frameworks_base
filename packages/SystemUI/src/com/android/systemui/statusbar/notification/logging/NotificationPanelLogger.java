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

package com.android.systemui.statusbar.notification.logging;

import android.annotation.Nullable;
import android.service.notification.StatusBarNotification;

import com.android.internal.logging.UiEvent;
import com.android.internal.logging.UiEventLogger;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;

import java.util.List;

import nano.Notifications;

/**
 * Statsd logging for notification panel.
 */
public interface NotificationPanelLogger {

    /**
     * Log a NOTIFICATION_PANEL_REPORTED statsd event.
     * @param visibleNotifications as provided by NotificationEntryManager.getVisibleNotifications()
     */
    void logPanelShown(boolean isLockscreen,
            @Nullable List<NotificationEntry> visibleNotifications);

    enum NotificationPanelEvent implements UiEventLogger.UiEventEnum {
        @UiEvent(doc = "Notification panel shown from status bar.")
        NOTIFICATION_PANEL_OPEN_STATUS_BAR(200),
        @UiEvent(doc = "Notification panel shown from lockscreen.")
        NOTIFICATION_PANEL_OPEN_LOCKSCREEN(201);

        private final int mId;
        NotificationPanelEvent(int id) {
            mId = id;
        }
        @Override public int getId() {
            return mId;
        }

        public static NotificationPanelEvent fromLockscreen(boolean isLockscreen) {
            return isLockscreen ? NOTIFICATION_PANEL_OPEN_LOCKSCREEN :
                    NOTIFICATION_PANEL_OPEN_STATUS_BAR;
        }
    }

    /**
     * Composes a NotificationsList proto from the list of visible notifications.
     * @param visibleNotifications as provided by NotificationEntryManager.getVisibleNotifications()
     * @return NotificationList proto suitable for SysUiStatsLog.write(NOTIFICATION_PANEL_REPORTED)
     */
    static Notifications.NotificationList toNotificationProto(
            @Nullable List<NotificationEntry> visibleNotifications) {
        Notifications.NotificationList notificationList = new Notifications.NotificationList();
        if (visibleNotifications == null) {
            return notificationList;
        }
        final Notifications.Notification[] nn =
                new Notifications.Notification[visibleNotifications.size()];
        int i = 0;
        for (NotificationEntry ne : visibleNotifications) {
            StatusBarNotification n = ne.getSbn();
            Notifications.Notification np = new Notifications.Notification();
            np.uid = n.getUid();
            np.packageName = n.getPackageName();
            np.instanceId = n.getInstanceId().getId();
            // TODO set np.groupInstanceId
            np.isGroupSummary = n.getNotification().isGroupSummary();
            np.section = 1 + ne.getBucket();  // We want 0 to mean not set / unknown
            nn[i] = np;
            ++i;
        }
        notificationList.notifications = nn;
        return notificationList;
    }
}
