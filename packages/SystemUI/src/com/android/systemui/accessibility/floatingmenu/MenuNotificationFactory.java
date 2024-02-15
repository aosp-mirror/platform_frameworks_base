/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.systemui.accessibility.floatingmenu;

import android.app.Notification;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.UserHandle;

import com.android.systemui.res.R;
import com.android.systemui.util.NotificationChannels;

class MenuNotificationFactory {
    public static final String ACTION_UNDO =
            "com.android.systemui.accessibility.floatingmenu.action.UNDO";
    public static final String ACTION_DELETE =
            "com.android.systemui.accessibility.floatingmenu.action.DELETE";

    private final Context mContext;

    MenuNotificationFactory(Context context) {
        mContext = context;
    }

    public Notification createHiddenNotification() {
        final CharSequence title = mContext.getText(
                R.string.accessibility_floating_button_hidden_notification_title);
        final CharSequence content = mContext.getText(
                R.string.accessibility_floating_button_hidden_notification_text);

        return new Notification.Builder(mContext, NotificationChannels.ALERTS)
                .setContentTitle(title)
                .setContentText(content)
                .setSmallIcon(R.drawable.ic_settings_24dp)
                .setContentIntent(buildUndoIntent())
                .setDeleteIntent(buildDeleteIntent())
                .setColor(mContext.getResources().getColor(
                        com.android.internal.R.color.system_notification_accent_color))
                .setLocalOnly(true)
                .setCategory(Notification.CATEGORY_SYSTEM)
                .build();
    }

    private PendingIntent buildUndoIntent() {
        final Intent intent = new Intent(ACTION_UNDO);

        return PendingIntent.getBroadcast(mContext, /* requestCode= */ 0, intent,
                PendingIntent.FLAG_IMMUTABLE);

    }

    private PendingIntent buildDeleteIntent() {
        final Intent intent = new Intent(ACTION_DELETE);

        return PendingIntent.getBroadcastAsUser(mContext, /* requestCode= */ 0, intent,
                PendingIntent.FLAG_CANCEL_CURRENT | PendingIntent.FLAG_ONE_SHOT
                        | PendingIntent.FLAG_IMMUTABLE, UserHandle.CURRENT);

    }
}
