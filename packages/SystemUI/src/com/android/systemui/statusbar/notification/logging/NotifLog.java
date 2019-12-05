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
public class NotifLog extends SysuiLog {
    private static final String TAG = "NotifLog";
    private static final int MAX_DOZE_DEBUG_LOGS = 400;
    private static final int MAX_DOZE_LOGS = 50;

    @Inject
    public NotifLog(DumpController dumpController) {
        super(dumpController, TAG, MAX_DOZE_DEBUG_LOGS, MAX_DOZE_LOGS);
    }

    /**
     * Logs a {@link NotifEvent} with a notification, ranking and message
     * @return true if successfully logged, else false
     */
    public boolean log(@NotifEvent.EventType int eventType, StatusBarNotification sbn,
            Ranking ranking, String msg) {
        return log(new NotifEvent.NotifEventBuilder()
                .setType(eventType)
                .setSbn(sbn)
                .setRanking(ranking)
                .setReason(msg)
                .build());
    }

    /**
     * Logs a {@link NotifEvent}
     * @return true if successfully logged, else false
     */
    public boolean log(@NotifEvent.EventType int eventType) {
        return log(eventType, null, null, null);
    }

    /**
     * Logs a {@link NotifEvent} with a message
     * @return true if successfully logged, else false
     */
    public boolean log(@NotifEvent.EventType int eventType, String msg) {
        return log(eventType, null, null, msg);
    }

    /**
     * Logs a {@link NotifEvent} with a notification
     * @return true if successfully logged, else false
     */
    public boolean log(@NotifEvent.EventType int eventType, StatusBarNotification sbn) {
        return log(eventType, sbn, null, "");
    }

    /**
     * Logs a {@link NotifEvent} with a notification
     * @return true if successfully logged, else false
     */
    public boolean log(@NotifEvent.EventType int eventType, StatusBarNotification sbn, String msg) {
        return log(eventType, sbn, null, msg);
    }

    /**
     * Logs a {@link NotifEvent} with a ranking
     * @return true if successfully logged, else false
     */
    public boolean log(@NotifEvent.EventType int eventType, Ranking ranking) {
        return log(eventType, null, ranking, "");
    }

    /**
     * Logs a {@link NotifEvent} with a notification and ranking
     * @return true if successfully logged, else false
     */
    public boolean log(@NotifEvent.EventType int eventType, StatusBarNotification sbn,
            Ranking ranking) {
        return log(eventType, sbn, ranking, "");
    }

    /**
     * Logs a {@link NotifEvent} with a notification entry
     * @return true if successfully logged, else false
     */
    public boolean log(@NotifEvent.EventType int eventType, NotificationEntry entry) {
        return log(eventType, entry.getSbn(), entry.getRanking(), "");
    }

    /**
     * Logs a {@link NotifEvent} with a notification entry
     * @return true if successfully logged, else false
     */
    public boolean log(@NotifEvent.EventType int eventType, NotificationEntry entry,
            String msg) {
        return log(eventType, entry.getSbn(), entry.getRanking(), msg);
    }
}
