/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.systemui.statusbar.policy;
import android.app.ActivityManager;
import android.app.Notification;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.UserHandle;
import android.service.notification.StatusBarNotification;

import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.notification.collection.NotificationEntryBuilder;

/**
 * Test helper class for HeadsUpEntry creation.
 */
public class HeadsUpManagerTestUtil {

    private static final String TEST_PACKAGE_NAME = "HeadsUpManagerTestUtil";
    private static final int TEST_UID = 0;

    protected static StatusBarNotification createSbn(int id, Notification.Builder n) {
        return createSbn(id, n.build());
    }

    protected static StatusBarNotification createSbn(int id, Context context) {
        final Notification.Builder b = new Notification.Builder(context, "")
                .setSmallIcon(com.android.systemui.res.R.drawable.ic_person)
                .setContentTitle("Title")
                .setContentText("Text");
        return createSbn(id, b);
    }

    protected static StatusBarNotification createSbn(int id, Notification n) {
        return new StatusBarNotification(
                TEST_PACKAGE_NAME /* pkg */,
                TEST_PACKAGE_NAME,
                id,
                null /* tag */,
                TEST_UID,
                0 /* initialPid */,
                n,
                new UserHandle(ActivityManager.getCurrentUser()),
                null /* overrideGroupKey */,
                0 /* postTime */);
    }

    protected static NotificationEntry createEntry(int id, Notification n) {
        return new NotificationEntryBuilder().setSbn(createSbn(id, n)).build();
    }

    protected static NotificationEntry createEntry(int id, Context context) {
        return new NotificationEntryBuilder().setSbn(
                HeadsUpManagerTestUtil.createSbn(id, context)).build();
    }

    protected static NotificationEntry createFullScreenIntentEntry(int id, Context context) {
        final PendingIntent intent = PendingIntent.getActivity(
                context, 0, new Intent(),
                PendingIntent.FLAG_IMMUTABLE);

        final Notification notif = new Notification.Builder(context, "")
                .setSmallIcon(com.android.systemui.res.R.drawable.ic_person)
                .setFullScreenIntent(intent, /* highPriority */ true)
                .build();
        return HeadsUpManagerTestUtil.createEntry(id, notif);
    }
}
