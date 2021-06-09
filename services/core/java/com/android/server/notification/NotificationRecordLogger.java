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

import static android.service.notification.NotificationListenerService.REASON_CANCEL;
import static android.service.notification.NotificationListenerService.REASON_CLICK;
import static android.service.notification.NotificationListenerService.REASON_TIMEOUT;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.Person;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.NotificationStats;

import com.android.internal.logging.InstanceId;
import com.android.internal.logging.UiEvent;
import com.android.internal.logging.UiEventLogger;

import java.util.ArrayList;
import java.util.Objects;

/**
 * Interface for writing NotificationReported atoms to statsd log. Use NotificationRecordLoggerImpl
 * in production.  Use NotificationRecordLoggerFake for testing.
 * @hide
 */
public interface NotificationRecordLogger {

    // The high-level interface used by clients.

    /**
     * May log a NotificationReported atom reflecting the posting or update of a notification.
     * @param r The new NotificationRecord. If null, no action is taken.
     * @param old The previous NotificationRecord.  Null if there was no previous record.
     * @param position The position at which this notification is ranked.
     * @param buzzBeepBlink Logging code reflecting whether this notification alerted the user.
     * @param groupId The instance Id of the group summary notification, or null.
     */
    void maybeLogNotificationPosted(@Nullable NotificationRecord r,
            @Nullable NotificationRecord old,
            int position, int buzzBeepBlink,
            InstanceId groupId);

    /**
     * Logs a notification cancel / dismiss event using UiEventReported (event ids from the
     * NotificationCancelledEvents enum).
     * @param r The NotificationRecord. If null, no action is taken.
     * @param reason The reason the notification was canceled.
     * @param dismissalSurface The surface the notification was dismissed from.
     */
    default void logNotificationCancelled(@Nullable NotificationRecord r,
            @NotificationListenerService.NotificationCancelReason int reason,
            @NotificationStats.DismissalSurface int dismissalSurface) {
        log(NotificationCancelledEvent.fromCancelReason(reason, dismissalSurface), r);
    }

    /**
     * Logs a notification visibility change event using UiEventReported (event ids from the
     * NotificationEvents enum).
     * @param r The NotificationRecord. If null, no action is taken.
     * @param visible True if the notification became visible.
     */
    default void logNotificationVisibility(@Nullable NotificationRecord r, boolean visible) {
        log(NotificationEvent.fromVisibility(visible), r);
    }

    // The UiEventReported logging methods are implemented in terms of this lower-level interface.

    /** Logs a UiEventReported event for the given notification. */
    void log(UiEventLogger.UiEventEnum event, NotificationRecord r);

    /** Logs a UiEventReported event that is not associated with any notification. */
    void log(UiEventLogger.UiEventEnum event);

    /**
     * The UiEvent enums that this class can log.
     */
    enum NotificationReportedEvent implements UiEventLogger.UiEventEnum {
        @UiEvent(doc = "New notification enqueued to post")
        NOTIFICATION_POSTED(162),
        @UiEvent(doc = "Notification substantially updated, or alerted again.")
        NOTIFICATION_UPDATED(163);

        private final int mId;
        NotificationReportedEvent(int id) {
            mId = id;
        }
        @Override public int getId() {
            return mId;
        }

        public static NotificationReportedEvent fromRecordPair(NotificationRecordPair p) {
            return (p.old != null) ? NotificationReportedEvent.NOTIFICATION_UPDATED :
                            NotificationReportedEvent.NOTIFICATION_POSTED;
        }
    }

    enum NotificationCancelledEvent implements UiEventLogger.UiEventEnum {
        INVALID(0),
        @UiEvent(doc = "Notification was canceled due to a notification click.")
        NOTIFICATION_CANCEL_CLICK(164),
        @UiEvent(doc = "Notification was canceled due to a user dismissal, surface not specified.")
        NOTIFICATION_CANCEL_USER_OTHER(165),
        @UiEvent(doc = "Notification was canceled due to a user dismiss-all (from the notification"
                + " shade).")
        NOTIFICATION_CANCEL_USER_CANCEL_ALL(166),
        @UiEvent(doc = "Notification was canceled due to an inflation error.")
        NOTIFICATION_CANCEL_ERROR(167),
        @UiEvent(doc = "Notification was canceled by the package manager modifying the package.")
        NOTIFICATION_CANCEL_PACKAGE_CHANGED(168),
        @UiEvent(doc = "Notification was canceled by the owning user context being stopped.")
        NOTIFICATION_CANCEL_USER_STOPPED(169),
        @UiEvent(doc = "Notification was canceled by the user banning the package.")
        NOTIFICATION_CANCEL_PACKAGE_BANNED(170),
        @UiEvent(doc = "Notification was canceled by the app canceling this specific notification.")
        NOTIFICATION_CANCEL_APP_CANCEL(171),
        @UiEvent(doc = "Notification was canceled by the app cancelling all its notifications.")
        NOTIFICATION_CANCEL_APP_CANCEL_ALL(172),
        @UiEvent(doc = "Notification was canceled by a listener reporting a user dismissal.")
        NOTIFICATION_CANCEL_LISTENER_CANCEL(173),
        @UiEvent(doc = "Notification was canceled by a listener reporting a user dismiss all.")
        NOTIFICATION_CANCEL_LISTENER_CANCEL_ALL(174),
        @UiEvent(doc = "Notification was canceled because it was a member of a canceled group.")
        NOTIFICATION_CANCEL_GROUP_SUMMARY_CANCELED(175),
        @UiEvent(doc = "Notification was canceled because it was an invisible member of a group.")
        NOTIFICATION_CANCEL_GROUP_OPTIMIZATION(176),
        @UiEvent(doc = "Notification was canceled by the device administrator suspending the "
                + "package.")
        NOTIFICATION_CANCEL_PACKAGE_SUSPENDED(177),
        @UiEvent(doc = "Notification was canceled by the owning managed profile being turned off.")
        NOTIFICATION_CANCEL_PROFILE_TURNED_OFF(178),
        @UiEvent(doc = "Autobundled summary notification was canceled because its group was "
                + "unbundled")
        NOTIFICATION_CANCEL_UNAUTOBUNDLED(179),
        @UiEvent(doc = "Notification was canceled by the user banning the channel.")
        NOTIFICATION_CANCEL_CHANNEL_BANNED(180),
        @UiEvent(doc = "Notification was snoozed.")
        NOTIFICATION_CANCEL_SNOOZED(181),
        @UiEvent(doc = "Notification was canceled due to timeout")
        NOTIFICATION_CANCEL_TIMEOUT(182),
        // Values 183-189 reserved for future system dismissal reasons
        @UiEvent(doc = "Notification was canceled due to user dismissal of a peeking notification.")
        NOTIFICATION_CANCEL_USER_PEEK(190),
        @UiEvent(doc = "Notification was canceled due to user dismissal from the always-on display")
        NOTIFICATION_CANCEL_USER_AOD(191),
        @UiEvent(doc = "Notification was canceled due to user dismissal from the notification"
                + " shade.")
        NOTIFICATION_CANCEL_USER_SHADE(192),
        @UiEvent(doc = "Notification was canceled due to user dismissal from the lockscreen")
        NOTIFICATION_CANCEL_USER_LOCKSCREEN(193);

        private final int mId;
        NotificationCancelledEvent(int id) {
            mId = id;
        }
        @Override public int getId() {
            return mId;
        }

        public static NotificationCancelledEvent fromCancelReason(
                @NotificationListenerService.NotificationCancelReason int reason,
                @NotificationStats.DismissalSurface int surface) {
            // Shouldn't be possible to get a non-dismissed notification here.
            if (surface == NotificationStats.DISMISSAL_NOT_DISMISSED) {
                if (NotificationManagerService.DBG) {
                    throw new IllegalArgumentException("Unexpected surface " + surface);
                }
                return INVALID;
            }
            // Most cancel reasons do not have a meaningful surface. Reason codes map directly
            // to NotificationCancelledEvent codes.
            if (surface == NotificationStats.DISMISSAL_OTHER) {
                if ((REASON_CLICK <= reason) && (reason <= REASON_TIMEOUT)) {
                    return NotificationCancelledEvent.values()[reason];
                }
                if (NotificationManagerService.DBG) {
                    throw new IllegalArgumentException("Unexpected cancel reason " + reason);
                }
                return INVALID;
            }
            // User cancels have a meaningful surface, which we differentiate by. See b/149038335
            // for caveats.
            if (reason != REASON_CANCEL) {
                if (NotificationManagerService.DBG) {
                    throw new IllegalArgumentException("Unexpected cancel with surface " + reason);
                }
                return INVALID;
            }
            switch (surface) {
                case NotificationStats.DISMISSAL_PEEK:
                    return NOTIFICATION_CANCEL_USER_PEEK;
                case NotificationStats.DISMISSAL_AOD:
                    return NOTIFICATION_CANCEL_USER_AOD;
                case NotificationStats.DISMISSAL_SHADE:
                    return NOTIFICATION_CANCEL_USER_SHADE;
                default:
                    if (NotificationManagerService.DBG) {
                        throw new IllegalArgumentException("Unexpected surface for user-dismiss "
                                + reason);
                    }
                    return INVALID;
            }
        }
    }

    enum NotificationEvent implements UiEventLogger.UiEventEnum {
        @UiEvent(doc = "Notification became visible.")
        NOTIFICATION_OPEN(197),
        @UiEvent(doc = "Notification stopped being visible.")
        NOTIFICATION_CLOSE(198),
        @UiEvent(doc = "Notification was snoozed.")
        NOTIFICATION_SNOOZED(317),
        @UiEvent(doc = "Notification was not posted because its app is snoozed.")
        NOTIFICATION_NOT_POSTED_SNOOZED(319),
        @UiEvent(doc = "Notification was clicked.")
        NOTIFICATION_CLICKED(320),
        @UiEvent(doc = "Notification action was clicked; unexpected position.")
        NOTIFICATION_ACTION_CLICKED(321),
        @UiEvent(doc = "Notification detail was expanded due to non-user action.")
        NOTIFICATION_DETAIL_OPEN_SYSTEM(327),
        @UiEvent(doc = "Notification detail was collapsed due to non-user action.")
        NOTIFICATION_DETAIL_CLOSE_SYSTEM(328),
        @UiEvent(doc = "Notification detail was expanded due to user action.")
        NOTIFICATION_DETAIL_OPEN_USER(329),
        @UiEvent(doc = "Notification detail was collapsed due to user action.")
        NOTIFICATION_DETAIL_CLOSE_USER(330),
        @UiEvent(doc = "Notification direct reply action was used.")
        NOTIFICATION_DIRECT_REPLIED(331),
        @UiEvent(doc = "Notification smart reply action was used.")
        NOTIFICATION_SMART_REPLIED(332),
        @UiEvent(doc = "Notification smart reply action was visible.")
        NOTIFICATION_SMART_REPLY_VISIBLE(333),
        @UiEvent(doc = "App-generated notification action at position 0 was clicked.")
        NOTIFICATION_ACTION_CLICKED_0(450),
        @UiEvent(doc = "App-generated notification action at position 1 was clicked.")
        NOTIFICATION_ACTION_CLICKED_1(451),
        @UiEvent(doc = "App-generated notification action at position 2 was clicked.")
        NOTIFICATION_ACTION_CLICKED_2(452),
        @UiEvent(doc = "Contextual notification action at position 0 was clicked.")
        NOTIFICATION_CONTEXTUAL_ACTION_CLICKED_0(453),
        @UiEvent(doc = "Contextual notification action at position 1 was clicked.")
        NOTIFICATION_CONTEXTUAL_ACTION_CLICKED_1(454),
        @UiEvent(doc = "Contextual notification action at position 2 was clicked.")
        NOTIFICATION_CONTEXTUAL_ACTION_CLICKED_2(455),
        @UiEvent(doc = "Notification assistant generated notification action at 0 was clicked.")
        NOTIFICATION_ASSIST_ACTION_CLICKED_0(456),
        @UiEvent(doc = "Notification assistant generated notification action at 1 was clicked.")
        NOTIFICATION_ASSIST_ACTION_CLICKED_1(457),
        @UiEvent(doc = "Notification assistant generated notification action at 2 was clicked.")
        NOTIFICATION_ASSIST_ACTION_CLICKED_2(458);

        private final int mId;
        NotificationEvent(int id) {
            mId = id;
        }
        @Override public int getId() {
            return mId;
        }

        public static NotificationEvent fromVisibility(boolean visible) {
            return visible ? NOTIFICATION_OPEN : NOTIFICATION_CLOSE;
        }
        public static NotificationEvent fromExpanded(boolean expanded, boolean userAction) {
            if (userAction) {
                return expanded ? NOTIFICATION_DETAIL_OPEN_USER : NOTIFICATION_DETAIL_CLOSE_USER;
            }
            return expanded ? NOTIFICATION_DETAIL_OPEN_SYSTEM : NOTIFICATION_DETAIL_CLOSE_SYSTEM;
        }
        public static NotificationEvent fromAction(int index, boolean isAssistant,
                boolean isContextual) {
            if (index < 0 || index > 2) {
                return NOTIFICATION_ACTION_CLICKED;
            }
            if (isAssistant) {  // Assistant actions are contextual by definition
                return NotificationEvent.values()[
                        NOTIFICATION_ASSIST_ACTION_CLICKED_0.ordinal() + index];
            }
            if (isContextual) {
                return NotificationEvent.values()[
                        NOTIFICATION_CONTEXTUAL_ACTION_CLICKED_0.ordinal() + index];
            }
            return NotificationEvent.values()[NOTIFICATION_ACTION_CLICKED_0.ordinal() + index];
        }
    }

    enum NotificationPanelEvent implements UiEventLogger.UiEventEnum {
        @UiEvent(doc = "Notification panel became visible.")
        NOTIFICATION_PANEL_OPEN(325),
        @UiEvent(doc = "Notification panel stopped being visible.")
        NOTIFICATION_PANEL_CLOSE(326);

        private final int mId;
        NotificationPanelEvent(int id) {
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
        boolean shouldLogReported(int buzzBeepBlink) {
            if (r == null) {
                return false;
            }
            if ((old == null) || (buzzBeepBlink > 0)) {
                return true;
            }

            return !(Objects.equals(r.getSbn().getChannelIdLogTag(),
                        old.getSbn().getChannelIdLogTag())
                    && Objects.equals(r.getSbn().getGroupLogTag(), old.getSbn().getGroupLogTag())
                    && (r.getSbn().getNotification().isGroupSummary()
                        == old.getSbn().getNotification().isGroupSummary())
                    && Objects.equals(r.getSbn().getNotification().category,
                        old.getSbn().getNotification().category)
                    && (r.getImportance() == old.getImportance())
                    && (getLoggingImportance(r) == getLoggingImportance(old)));
        }

        /**
         * @return hash code for the notification style class, or 0 if none exists.
         */
        public int getStyle() {
            return getStyle(r.getSbn().getNotification().extras);
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
            return getNumPeople(r.getSbn().getNotification().extras);
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

        int getInstanceId() {
            return (r.getSbn().getInstanceId() == null ? 0 : r.getSbn().getInstanceId().getId());
        }

        /**
         * @return Small hash of the notification ID, and tag (if present).
         */
        int getNotificationIdHash() {
            return SmallHash.hash(Objects.hashCode(r.getSbn().getTag()) ^ r.getSbn().getId());
        }

        /**
         * @return Small hash of the channel ID, if present, or 0 otherwise.
         */
        int getChannelIdHash() {
            return SmallHash.hash(r.getSbn().getNotification().getChannelId());
        }

        /**
         * @return Small hash of the group ID, respecting group override if present. 0 otherwise.
         */
        int getGroupIdHash() {
            return SmallHash.hash(r.getSbn().getGroup());
        }

    }

    /**
     * @param r NotificationRecord
     * @return Logging importance of record, taking important conversation channels into account.
     */
    static int getLoggingImportance(@NonNull NotificationRecord r) {
        final int importance = r.getImportance();
        final NotificationChannel channel = r.getChannel();
        if (channel == null) {
            return importance;
        }
        return NotificationChannelLogger.getLoggingImportance(channel, importance);
    }

    /**
     * @param r NotificationRecord
     * @return Whether the notification is a foreground service notification.
     */
    static boolean isForegroundService(@NonNull NotificationRecord r) {
        if (r.getSbn() == null || r.getSbn().getNotification() == null) {
            return false;
        }
        return (r.getSbn().getNotification().flags & Notification.FLAG_FOREGROUND_SERVICE) != 0;
    }
}
