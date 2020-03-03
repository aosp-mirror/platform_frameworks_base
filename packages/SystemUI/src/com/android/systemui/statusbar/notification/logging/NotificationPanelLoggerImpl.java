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

import com.android.systemui.shared.system.SysUiStatsLog;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.notification.logging.nano.Notifications;

import com.google.protobuf.nano.MessageNano;

import java.util.List;

/**
 * Normal implementation of NotificationPanelLogger.
 */
public class NotificationPanelLoggerImpl implements NotificationPanelLogger {
    @Override
    public void logPanelShown(boolean isLockscreen,
            List<NotificationEntry> visibleNotifications) {
        final Notifications.NotificationList proto = NotificationPanelLogger.toNotificationProto(
                visibleNotifications);
        SysUiStatsLog.write(SysUiStatsLog.NOTIFICATION_PANEL_REPORTED,
                /* int event_id */ NotificationPanelEvent.fromLockscreen(isLockscreen).getId(),
                /* int num_notifications*/ proto.notifications.length,
                /* byte[] notifications*/ MessageNano.toByteArray(proto));
    }
}
