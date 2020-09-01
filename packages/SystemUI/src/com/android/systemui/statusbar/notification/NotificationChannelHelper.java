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

package com.android.systemui.statusbar.notification;

import android.app.INotificationManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.content.Context;
import android.os.Bundle;
import android.os.RemoteException;
import android.os.UserHandle;
import android.text.TextUtils;
import android.util.Slog;

import com.android.systemui.statusbar.notification.collection.NotificationEntry;

/**
 * Helps SystemUI create notification channels.
 */
public class NotificationChannelHelper {
    private static final String TAG = "NotificationChannelHelper";

    /** Creates a conversation channel based on the shortcut info or notification title. */
    public static NotificationChannel createConversationChannelIfNeeded(
            Context context,
            INotificationManager notificationManager,
            NotificationEntry entry,
            NotificationChannel channel) {
        if (!TextUtils.isEmpty(channel.getConversationId())) {
            return channel;
        }
        final String conversationId = entry.getSbn().getShortcutId();
        final String pkg = entry.getSbn().getPackageName();
        final int appUid = entry.getSbn().getUid();
        if (TextUtils.isEmpty(conversationId) || TextUtils.isEmpty(pkg)
            || entry.getRanking().getShortcutInfo() == null) {
            return channel;
        }

        // If this channel is not already a customized conversation channel, create
        // a custom channel
        try {
            channel.setName(getName(entry));
            notificationManager.createConversationNotificationChannelForPackage(
                    pkg, appUid, entry.getSbn().getKey(), channel,
                    conversationId);
            channel = notificationManager.getConversationNotificationChannel(
                    context.getOpPackageName(), UserHandle.getUserId(appUid), pkg,
                    channel.getId(), false, conversationId);
        } catch (RemoteException e) {
            Slog.e(TAG, "Could not create conversation channel", e);
        }
        return channel;
    }

    private static CharSequence getName(NotificationEntry entry) {
        if (entry.getRanking().getShortcutInfo().getLabel() != null) {
            return entry.getRanking().getShortcutInfo().getLabel().toString();
        }
        Bundle extras = entry.getSbn().getNotification().extras;
        CharSequence nameString = extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE);
        if (TextUtils.isEmpty(nameString)) {
            nameString = extras.getCharSequence(Notification.EXTRA_TITLE);
        }
        if (TextUtils.isEmpty(nameString)) {
            nameString = "fallback";
        }
        return nameString;
    }
}
