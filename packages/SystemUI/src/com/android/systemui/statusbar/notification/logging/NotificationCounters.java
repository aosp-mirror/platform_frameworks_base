/*
 * Copyright (C) 2018 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.systemui.statusbar.notification.logging;

/**
 * Constants for counter tags for Notification-related actions/views.
 */
public class NotificationCounters {
    /** Counter tag for notification dismissal. */
    public static final String NOTIFICATION_DISMISSED = "notification_dismissed";

    /** Counter tag for when the blocking helper is shown to the user. */
    public static final String BLOCKING_HELPER_SHOWN = "blocking_helper_shown";
    /** Counter tag for when the blocking helper is dismissed via a miscellaneous interaction. */
    public static final String BLOCKING_HELPER_DISMISSED = "blocking_helper_dismissed";
    /** Counter tag for when the user hits 'stop notifications' in the blocking helper. */
    public static final String BLOCKING_HELPER_STOP_NOTIFICATIONS =
            "blocking_helper_stop_notifications";
    /** Counter tag for when the user hits 'deliver silently' in the blocking helper. */
    public static final String BLOCKING_HELPER_DELIVER_SILENTLY =
            "blocking_helper_deliver_silently";
    /** Counter tag for when the user hits 'show silently' in the blocking helper. */
    public static final String BLOCKING_HELPER_TOGGLE_SILENT =
            "blocking_helper_toggle_silent";
    /** Counter tag for when the user hits 'keep showing' in the blocking helper. */
    public static final String BLOCKING_HELPER_KEEP_SHOWING =
            "blocking_helper_keep_showing";
    /**
     * Counter tag for when the user hits undo in context of the blocking helper - this can happen
     * multiple times per view.
     */
    public static final String BLOCKING_HELPER_UNDO = "blocking_helper_undo";
    /** Counter tag for when the user hits the notification settings icon in the blocking helper. */
    public static final String BLOCKING_HELPER_NOTIF_SETTINGS =
            "blocking_helper_notif_settings";
}
