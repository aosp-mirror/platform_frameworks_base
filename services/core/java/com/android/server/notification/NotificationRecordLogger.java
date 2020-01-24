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
import android.app.Notification;
import android.app.Person;
import android.os.Bundle;

import com.android.internal.logging.UiEvent;
import com.android.internal.logging.UiEventLogger;

import java.util.ArrayList;
import java.util.Objects;

/**
 * Interface for writing NotificationReported atoms to statsd log.
 * @hide
 */
public interface NotificationRecordLogger {

    /**
     * Logs a NotificationReported atom reflecting the posting or update of a notification.
     * @param r The new NotificationRecord. If null, no action is taken.
     * @param old The previous NotificationRecord.  Null if there was no previous record.
     * @param position The position at which this notification is ranked.
     * @param buzzBeepBlink Logging code reflecting whether this notification alerted the user.
     */
    void logNotificationReported(@Nullable NotificationRecord r, @Nullable NotificationRecord old,
            int position, int buzzBeepBlink);

    /**
     * The UiEvent enums that this class can log.
     */
    enum NotificationReportedEvents implements UiEventLogger.UiEventEnum {
        INVALID(0),
        @UiEvent(doc = "New notification enqueued to post")
        NOTIFICATION_POSTED(162),
        @UiEvent(doc = "Notification substantially updated")
        NOTIFICATION_UPDATED(163);

        private final int mId;
        NotificationReportedEvents(int id) {
            mId = id;
        }
        @Override public int getId() {
            return mId;
        }
    }

    /**
     * A helper for extracting logging information from one or two NotificationRecords.
     */
    class NotificationRecordPair {
        public final NotificationRecord r, old;
         /**
         * Construct from one or two NotificationRecords.
         * @param r The new NotificationRecord.  If null, only shouldLog() method is usable.
         * @param old The previous NotificationRecord.  Null if there was no previous record.
         */
        NotificationRecordPair(@Nullable NotificationRecord r, @Nullable NotificationRecord old) {
            this.r = r;
            this.old = old;
        }

        /**
         * @return True if old is null, alerted, or important logged fields have changed.
         */
        boolean shouldLog(int buzzBeepBlink) {
            if (r == null) {
                return false;
            }
            if ((old == null) || (buzzBeepBlink > 0)) {
                return true;
            }

            return !(Objects.equals(r.sbn.getChannelIdLogTag(), old.sbn.getChannelIdLogTag())
                    && Objects.equals(r.sbn.getGroupLogTag(), old.sbn.getGroupLogTag())
                    && (r.sbn.getNotification().isGroupSummary()
                        == old.sbn.getNotification().isGroupSummary())
                    && Objects.equals(r.sbn.getNotification().category,
                        old.sbn.getNotification().category)
                    && (r.getImportance() == old.getImportance()));
        }

        NotificationReportedEvents getUiEvent() {
            return (old != null) ? NotificationReportedEvents.NOTIFICATION_UPDATED :
                    NotificationReportedEvents.NOTIFICATION_POSTED;
        }

        /**
         * @return hash code for the notification style class, or 0 if none exists.
         */
        public int getStyle() {
            return getStyle(r.sbn.getNotification().extras);
        }

        private int getStyle(@Nullable Bundle extras) {
            if (extras != null) {
                String template = extras.getString(Notification.EXTRA_TEMPLATE);
                if (template != null && !template.isEmpty()) {
                    return template.hashCode();
                }
            }
            return 0;
        }

        int getNumPeople() {
            return getNumPeople(r.sbn.getNotification().extras);
        }

        private int getNumPeople(@Nullable Bundle extras) {
            if (extras != null) {
                ArrayList<Person> people = extras.getParcelableArrayList(
                        Notification.EXTRA_PEOPLE_LIST);
                if (people != null && !people.isEmpty()) {
                    return people.size();
                }
            }
            return 0;
        }

        int getAssistantHash() {
            String assistant = r.getAdjustmentIssuer();
            return (assistant == null) ? 0 : assistant.hashCode();
        }
    }
}
