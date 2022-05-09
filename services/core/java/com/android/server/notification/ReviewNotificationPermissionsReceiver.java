/*
 * Copyright (C) 2022 The Android Open Source Project
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

import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.provider.Settings;
import android.util.Log;
import android.util.Slog;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.messages.nano.SystemMessageProto;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;

/**
 * Broadcast receiver for intents that come from the "review notification permissions" notification,
 * shown to users who upgrade to T from an earlier OS to inform them of notification setup changes
 * and invite them to review their notification permissions.
 */
public class ReviewNotificationPermissionsReceiver extends BroadcastReceiver {
    public static final String TAG = "ReviewNotifPermissions";
    static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

    // 7 days in millis, as the amount of time to wait before re-sending the notification
    private static final long JOB_RESCHEDULE_TIME = 1000 /* millis */ * 60 /* seconds */
            * 60 /* minutes */ * 24 /* hours */ * 7 /* days */;

    static IntentFilter getFilter() {
        IntentFilter filter = new IntentFilter();
        filter.addAction(NotificationManagerService.REVIEW_NOTIF_ACTION_REMIND);
        filter.addAction(NotificationManagerService.REVIEW_NOTIF_ACTION_DISMISS);
        filter.addAction(NotificationManagerService.REVIEW_NOTIF_ACTION_CANCELED);
        return filter;
    }

    // Cancels the "review notification permissions" notification.
    @VisibleForTesting
    protected void cancelNotification(Context context) {
        NotificationManager nm = context.getSystemService(NotificationManager.class);
        if (nm != null) {
            nm.cancel(NotificationManagerService.TAG,
                    SystemMessageProto.SystemMessage.NOTE_REVIEW_NOTIFICATION_PERMISSIONS);
        } else {
            Slog.w(TAG, "could not cancel notification: NotificationManager not found");
        }
    }

    @VisibleForTesting
    protected void rescheduleNotification(Context context) {
        ReviewNotificationPermissionsJobService.scheduleJob(context, JOB_RESCHEDULE_TIME);
        // log if needed
        if (DEBUG) {
            Slog.d(TAG, "Scheduled review permissions notification for on or after: "
                    + LocalDateTime.now(ZoneId.systemDefault())
                            .plus(JOB_RESCHEDULE_TIME, ChronoUnit.MILLIS));
        }
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (action.equals(NotificationManagerService.REVIEW_NOTIF_ACTION_REMIND)) {
            // Reschedule the notification for 7 days in the future
            rescheduleNotification(context);

            // note that the user has interacted; no longer needed to show the initial
            // notification
            Settings.Global.putInt(context.getContentResolver(),
                    Settings.Global.REVIEW_PERMISSIONS_NOTIFICATION_STATE,
                    NotificationManagerService.REVIEW_NOTIF_STATE_USER_INTERACTED);
            cancelNotification(context);
        } else if (action.equals(NotificationManagerService.REVIEW_NOTIF_ACTION_DISMISS)) {
            // user dismissed; write to settings so we don't show ever again
            Settings.Global.putInt(context.getContentResolver(),
                    Settings.Global.REVIEW_PERMISSIONS_NOTIFICATION_STATE,
                    NotificationManagerService.REVIEW_NOTIF_STATE_DISMISSED);
            cancelNotification(context);
        } else if (action.equals(NotificationManagerService.REVIEW_NOTIF_ACTION_CANCELED)) {
            // we may get here from the user swiping away the notification,
            // or from the notification being canceled in any other way.
            // only in the case that the user hasn't interacted with it in
            // any other way yet, reschedule
            int notifState = Settings.Global.getInt(context.getContentResolver(),
                    Settings.Global.REVIEW_PERMISSIONS_NOTIFICATION_STATE,
                    /* default */ NotificationManagerService.REVIEW_NOTIF_STATE_UNKNOWN);
            if (notifState == NotificationManagerService.REVIEW_NOTIF_STATE_SHOULD_SHOW) {
                // user hasn't interacted in the past, so reschedule once and then note that the
                // user *has* interacted now so we don't re-reschedule if they swipe again
                rescheduleNotification(context);
                Settings.Global.putInt(context.getContentResolver(),
                        Settings.Global.REVIEW_PERMISSIONS_NOTIFICATION_STATE,
                        NotificationManagerService.REVIEW_NOTIF_STATE_USER_INTERACTED);
            } else if (notifState == NotificationManagerService.REVIEW_NOTIF_STATE_RESHOWN) {
                // swiping away on a rescheduled notification; mark as interacted and
                // don't reschedule again.
                Settings.Global.putInt(context.getContentResolver(),
                        Settings.Global.REVIEW_PERMISSIONS_NOTIFICATION_STATE,
                        NotificationManagerService.REVIEW_NOTIF_STATE_USER_INTERACTED);
            }
        }
    }
}
