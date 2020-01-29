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

import android.util.StatsLog;

/**
 * Standard implementation of NotificationRecordLogger interface.
 * @hide
 */
public class NotificationRecordLoggerImpl implements NotificationRecordLogger {

    @Override
    public void logNotificationReported(NotificationRecord r, NotificationRecord old,
            int position, int buzzBeepBlink) {
        NotificationRecordPair p = new NotificationRecordPair(r, old);
        if (!p.shouldLog(buzzBeepBlink)) {
            return;
        }
        StatsLog.write(StatsLog.NOTIFICATION_REPORTED,
                /* int32 event_id = 1 */ p.getUiEvent().getId(),
                /* int32 uid = 2 */ r.getUid(),
                /* string package_name = 3 */ r.sbn.getPackageName(),
                /* int32 instance_id = 4 */ p.getInstanceId(),
                /* int32 notification_id = 5 */ r.sbn.getId(),
                /* string notification_tag = 6 */ r.sbn.getTag(),
                /* string channel_id = 7 */ r.sbn.getChannelIdLogTag(),
                /* string group_id = 8 */ r.sbn.getGroupLogTag(),
                /* int32 group_instance_id = 9 */ 0, // TODO generate and fill instance ids
                /* bool is_group_summary = 10 */ r.sbn.getNotification().isGroupSummary(),
                /* string category = 11 */ r.sbn.getNotification().category,
                /* int32 style = 12 */ p.getStyle(),
                /* int32 num_people = 13 */ p.getNumPeople(),
                /* int32 position = 14 */ position,
                /* android.stats.sysui.NotificationImportance importance = 15 */ r.getImportance(),
                /* int32 alerting = 16 */ buzzBeepBlink,
                /* NotificationImportanceExplanation importance_source = 17 */
                r.getImportanceExplanationCode(),
                /* android.stats.sysui.NotificationImportance importance_initial = 18 */
                r.getInitialImportance(),
                /* NotificationImportanceExplanation importance_initial_source = 19 */
                r.getInitialImportanceExplanationCode(),
                /* android.stats.sysui.NotificationImportance importance_asst = 20 */
                r.getAssistantImportance(),
                /* int32 assistant_hash = 21 */ p.getAssistantHash(),
                /* float assistant_ranking_score = 22 */ 0  // TODO connect up ranking score
        );
    }






}
