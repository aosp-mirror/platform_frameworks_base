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

package com.android.server.notification;

import android.annotation.Nullable;

import com.android.internal.logging.InstanceId;
import com.android.internal.logging.UiEventLogger;
import com.android.internal.logging.UiEventLoggerImpl;
import com.android.internal.util.FrameworkStatsLog;

/**
 * Standard implementation of NotificationRecordLogger interface.
 * @hide
 */
class NotificationRecordLoggerImpl implements NotificationRecordLogger {

    private UiEventLogger mUiEventLogger = new UiEventLoggerImpl();

    @Override
    public void logNotificationPosted(NotificationReported nr) {
        writeNotificationReportedAtom(nr);
    }

    @Override
    public void logNotificationAdjusted(@Nullable NotificationRecord r,
            int position, int buzzBeepBlink,
            InstanceId groupId) {
        NotificationRecordPair p = new NotificationRecordPair(r, null);
        writeNotificationReportedAtom(
                new NotificationReported(p, NotificationReportedEvent.NOTIFICATION_ADJUSTED,
                        position, buzzBeepBlink, groupId));
    }

    private void writeNotificationReportedAtom(
            NotificationReported notificationReported) {
        FrameworkStatsLog.write(
                FrameworkStatsLog.NOTIFICATION_REPORTED,
                notificationReported.event_id,
                notificationReported.uid,
                notificationReported.package_name,
                notificationReported.instance_id,
                notificationReported.notification_id_hash,
                notificationReported.channel_id_hash,
                notificationReported.group_id_hash,
                notificationReported.group_instance_id,
                notificationReported.is_group_summary,
                notificationReported.category,
                notificationReported.style,
                notificationReported.num_people,
                notificationReported.position,
                notificationReported.importance,
                notificationReported.alerting,
                notificationReported.importance_source,
                notificationReported.importance_initial,
                notificationReported.importance_initial_source,
                notificationReported.importance_asst,
                notificationReported.assistant_hash,
                notificationReported.assistant_ranking_score,
                notificationReported.is_ongoing,
                notificationReported.is_foreground_service,
                notificationReported.timeout_millis,
                notificationReported.is_non_dismissible,
                notificationReported.post_duration_millis,
                notificationReported.fsi_state,
                notificationReported.is_locked,
                notificationReported.age_in_minutes);
    }

    @Override
    public void log(UiEventLogger.UiEventEnum event, NotificationRecord r) {
        if (r == null) {
            return;
        }
        mUiEventLogger.logWithInstanceId(event, r.getUid(), r.getSbn().getPackageName(),
                r.getSbn().getInstanceId());
    }

    @Override
    public void log(UiEventLogger.UiEventEnum event) {
        mUiEventLogger.log(event);
    }
}
