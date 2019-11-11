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

package com.android.systemui.bubbles;

import android.app.Notification;
import android.app.PendingIntent;
import android.content.Context;
import android.graphics.drawable.Icon;
import android.provider.Settings;

import com.android.systemui.statusbar.notification.collection.NotificationEntry;

/**
 * Common class for experiments controlled via secure settings.
 */
public class BubbleExperimentConfig {

    private static final String ALLOW_ANY_NOTIF_TO_BUBBLE = "allow_any_notif_to_bubble";
    private static final boolean ALLOW_ANY_NOTIF_TO_BUBBLE_DEFAULT = false;

    private static final String ALLOW_MESSAGE_NOTIFS_TO_BUBBLE = "allow_message_notifs_to_bubble";
    private static final boolean ALLOW_MESSAGE_NOTIFS_TO_BUBBLE_DEFAULT = false;

    /**
     * When true, if a notification has the information necessary to bubble (i.e. valid
     * contentIntent and an icon or image), then a {@link android.app.Notification.BubbleMetadata}
     * object will be created by the system and added to the notification.
     *
     * This does not produce a bubble, only adds the metadata. It should be used in conjunction
     * with {@see #allowNotifBubbleMenu} which shows an affordance to bubble notification content.
     */
    static boolean allowAnyNotifToBubble(Context context) {
        return Settings.Secure.getInt(context.getContentResolver(),
                ALLOW_ANY_NOTIF_TO_BUBBLE,
                ALLOW_ANY_NOTIF_TO_BUBBLE_DEFAULT ? 1 : 0) != 0;
    }

    /**
     * Same as {@link #allowAnyNotifToBubble(Context)} except it filters for notifications that
     * are using {@link Notification.MessagingStyle} and have remote input.
     */
    static boolean allowMessageNotifsToBubble(Context context) {
        return Settings.Secure.getInt(context.getContentResolver(),
                ALLOW_MESSAGE_NOTIFS_TO_BUBBLE,
                ALLOW_MESSAGE_NOTIFS_TO_BUBBLE_DEFAULT ? 1 : 0) != 0;
    }

    /**
     * If {@link #allowAnyNotifToBubble(Context)} is true, this method creates and adds
     * {@link android.app.Notification.BubbleMetadata} to the notification entry as long as
     * the notification has necessary info for BubbleMetadata.
     */
    static void adjustForExperiments(Context context, NotificationEntry entry,
            Bubble previousBubble) {
        if (entry.getBubbleMetadata() != null) {
            // Has metadata, nothing to do.
            return;
        }

        Notification notification = entry.getSbn().getNotification();
        boolean isMessage = Notification.MessagingStyle.class.equals(
                notification.getNotificationStyle());
        boolean bubbleNotifForExperiment = (isMessage && allowMessageNotifsToBubble(context))
                || allowAnyNotifToBubble(context);

        final PendingIntent intent = notification.contentIntent;
        if (bubbleNotifForExperiment
                && BubbleController.canLaunchIntentInActivityView(context, entry, intent)) {
            final Icon smallIcon = entry.getSbn().getNotification().getSmallIcon();
            Notification.BubbleMetadata.Builder metadata =
                    new Notification.BubbleMetadata.Builder()
                            .setDesiredHeight(10000)
                            .setIcon(smallIcon)
                            .setIntent(intent);
            entry.setBubbleMetadata(metadata.build());
        }

        if (previousBubble != null) {
            // Update to a previously user-created bubble, set its flag now so the update goes
            // to the bubble.
            entry.setFlagBubble(true);
        }
    }
}
