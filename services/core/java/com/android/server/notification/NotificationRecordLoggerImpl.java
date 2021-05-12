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

import com.android.internal.logging.InstanceId;
import com.android.internal.logging.UiEventLogger;
import com.android.internal.logging.UiEventLoggerImpl;
import com.android.internal.util.FrameworkStatsLog;

/**
 * Standard implementation of NotificationRecordLogger interface.
 * @hide
 */
public class NotificationRecordLoggerImpl implements NotificationRecordLogger {

    private UiEventLogger mUiEventLogger = new UiEventLoggerImpl();

    @Override
    public void maybeLogNotificationPosted(NotificationRecord r, NotificationRecord old,
            int position, int buzzBeepBlink,
            InstanceId groupId) {
        NotificationRecordPair p = new NotificationRecordPair(r, old);
        if (!p.shouldLogReported(buzzBeepBlink)) {
            return;
        }
        FrameworkStatsLog.write(FrameworkStatsLog.NOTIFICATION_REPORTED,
                /* int32 event_id = 1 */ NotificationReportedEvent.fromRecordPair(p).getId(),
                /* int32 uid = 2 */ r.getUid(),
                /* string package_name = 3 */ r.getSbn().getPackageName(),
                /* int32 instance_id = 4 */ p.getInstanceId(),
                /* int32 notification_id_hash = 5 */ p.getNotificationIdHash(),
                /* int32 channel_id_hash = 6 */ p.getChannelIdHash(),
                /* string group_id_hash = 7 */ p.getGroupIdHash(),
                /* int32 group_instance_id = 8 */ (groupId == null) ? 0 : groupId.getId(),
                /* bool is_group_summary = 9 */ r.getSbn().getNotification().isGroupSummary(),
                /* string category = 10 */ r.getSbn().getNotification().category,
                /* int32 style = 11 */ p.getStyle(),
                /* int32 num_people = 12 */ p.getNumPeople(),
                /* int32 position = 13 */ position,
                /* android.stats.sysui.NotificationImportance importance = 14 */
                NotificationRecordLogger.getLoggingImportance(r),
                /* int32 alerting = 15 */ buzzBeepBlink,
                /* NotificationImportanceExplanation importance_source = 16 */
                r.getImportanceExplanationCode(),
                /* android.stats.sysui.NotificationImportance importance_initial = 17 */
                r.getInitialImportance(),
                /* NotificationImportanceExplanation importance_initial_source = 18 */
                r.getInitialImportanceExplanationCode(),
                /* android.stats.sysui.NotificationImportance importance_asst = 19 */
                r.getAssistantImportance(),
                /* int32 assistant_hash = 20 */ p.getAssistantHash(),
                /* float assistant_ranking_score = 21 */ r.getRankingScore(),
                /* bool is_ongoing = 22 */ r.getSbn().isOngoing(),
                /* bool is_foreground_service = 23 */
                NotificationRecordLogger.isForegroundService(r),
                /* optional int64 timeout_millis = 24 */
                r.getSbn().getNotification().getTimeoutAfter()
        );
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
