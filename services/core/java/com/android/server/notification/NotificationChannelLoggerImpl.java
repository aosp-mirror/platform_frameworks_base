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

import android.app.NotificationChannel;
import android.app.NotificationChannelGroup;

import com.android.internal.logging.UiEventLogger;
import com.android.internal.logging.UiEventLoggerImpl;
import com.android.internal.util.FrameworkStatsLog;

/**
 * Standard implementation of NotificationChannelLogger, which passes data through to StatsLog.
 * This layer is as skinny as possible, to maximize code coverage of unit tests.  Nontrivial code
 * should live in the interface so it can be tested.
 */
public class NotificationChannelLoggerImpl implements NotificationChannelLogger {
    UiEventLogger mUiEventLogger = new UiEventLoggerImpl();

    @Override
    public void logNotificationChannel(NotificationChannelEvent event,
            NotificationChannel channel, int uid, String pkg,
            int oldImportance, int newImportance) {
        FrameworkStatsLog.write(FrameworkStatsLog.NOTIFICATION_CHANNEL_MODIFIED,
                /* int event_id*/ event.getId(),
                /* int uid*/ uid,
                /* String package_name */ pkg,
                /* int32 channel_id_hash */ NotificationChannelLogger.getIdHash(channel),
                /* int old_importance*/ oldImportance,
                /* int importance*/ newImportance,
                /* bool is_conversation */ channel.isConversation(),
                /* int32 conversation_id_hash */
                NotificationChannelLogger.getConversationIdHash(channel),
                /* bool is_conversation_demoted */ channel.isDemoted(),
                /* bool is_conversation_priority */ channel.isImportantConversation());
    }

    @Override
    public void logNotificationChannelGroup(NotificationChannelEvent event,
            NotificationChannelGroup channelGroup, int uid, String pkg, boolean wasBlocked) {
        FrameworkStatsLog.write(FrameworkStatsLog.NOTIFICATION_CHANNEL_MODIFIED,
                /* int event_id*/ event.getId(),
                /* int uid*/ uid,
                /* String package_name */ pkg,
                /* int32 channel_id_hash */ NotificationChannelLogger.getIdHash(channelGroup),
                /* int old_importance*/ NotificationChannelLogger.getImportance(wasBlocked),
                /* int importance*/ NotificationChannelLogger.getImportance(channelGroup),
                /* bool is_conversation */ false,
                /* int32 conversation_id_hash */ 0,
                /* bool is_conversation_demoted */ false,
                /* bool is_conversation_priority */ false);
    }

    @Override
    public void logAppEvent(NotificationChannelEvent event, int uid, String pkg) {
        mUiEventLogger.log(event, uid, pkg);
    }
}
