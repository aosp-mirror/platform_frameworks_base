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

package com.android.systemui.statusbar.notification.logging;

import android.os.SystemProperties;
import android.service.notification.NotificationListenerService.Ranking;
import android.service.notification.StatusBarNotification;

import com.android.systemui.DumpController;
import com.android.systemui.log.SysuiLog;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;

import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Logs systemui notification events for debugging and triaging purposes. Logs are dumped in
 * bugreports or on demand:
 *      adb shell dumpsys activity service com.android.systemui/.SystemUIService \
 *      dependency DumpController NotifLog
 */
@Singleton
public class NotifLog extends SysuiLog<NotifEvent> {
    private static final String TAG = "NotifLog";
    private static final boolean SHOW_NEM_LOGS =
            SystemProperties.getBoolean("persist.sysui.log.notif.nem", true);
    private static final boolean SHOW_LIST_BUILDER_LOGS =
            SystemProperties.getBoolean("persist.sysui.log.notif.listbuilder", true);

    private static final int MAX_DOZE_DEBUG_LOGS = 400;
    private static final int MAX_DOZE_LOGS = 50;

    private NotifEvent mRecycledEvent;

    @Inject
    public NotifLog(DumpController dumpController) {
        super(dumpController, TAG, MAX_DOZE_DEBUG_LOGS, MAX_DOZE_LOGS);
    }

    /**
     * Logs a {@link NotifEvent} with a notification, ranking and message.
     * Uses the last recycled event if available.
     * @return true if successfully logged, else false
     */
    public void log(@NotifEvent.EventType int eventType,
            StatusBarNotification sbn, Ranking ranking, String msg) {
        if (!mEnabled
                || (NotifEvent.isListBuilderEvent(eventType) && !SHOW_LIST_BUILDER_LOGS)
                || (NotifEvent.isNemEvent(eventType) && !SHOW_NEM_LOGS)) {
            return;
        }

        if (mRecycledEvent != null) {
            mRecycledEvent = log(mRecycledEvent.init(eventType, sbn, ranking, msg));
        } else {
            mRecycledEvent = log(new NotifEvent().init(eventType, sbn, ranking, msg));
        }
    }

    /**
     * Logs a {@link NotifEvent} with no extra information aside from the event type
     */
    public void log(@NotifEvent.EventType int eventType) {
        log(eventType, null, null, "");
    }

    /**
     * Logs a {@link NotifEvent} with a message
     */
    public void log(@NotifEvent.EventType int eventType, String msg) {
        log(eventType, null, null, msg);
    }

    /**
     * Logs a {@link NotifEvent} with a entry
     */
    public void log(@NotifEvent.EventType int eventType, NotificationEntry entry) {
        log(eventType, entry.getSbn(), entry.getRanking(), "");
    }

    /**
     * Logs a {@link NotifEvent} with a NotificationEntry and message
     */
    public void log(@NotifEvent.EventType int eventType, NotificationEntry entry, String msg) {
        log(eventType, entry.getSbn(), entry.getRanking(), msg);
    }

    /**
     * Logs a {@link NotifEvent} with a notification and message
     */
    public void log(@NotifEvent.EventType int eventType, StatusBarNotification sbn, String msg) {
        log(eventType, sbn, null, msg);
    }

    /**
     * Logs a {@link NotifEvent} with a ranking and message
     */
    public void log(@NotifEvent.EventType int eventType, Ranking ranking, String msg) {
        log(eventType, null, ranking, msg);
    }
}
