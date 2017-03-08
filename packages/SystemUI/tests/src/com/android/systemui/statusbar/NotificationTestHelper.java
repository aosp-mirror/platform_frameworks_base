/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.systemui.statusbar;

import android.app.ActivityManager;
import android.app.Notification;
import android.content.Context;
import android.os.UserHandle;
import android.service.notification.StatusBarNotification;
import android.view.LayoutInflater;
import android.widget.RemoteViews;

import com.android.systemui.R;
import com.android.systemui.statusbar.notification.InflationException;
import com.android.systemui.statusbar.phone.NotificationGroupManager;

/**
 * A helper class to create {@link ExpandableNotificationRow}
 */
public class NotificationTestHelper {

    private final Context mContext;
    private int mId;
    private final NotificationGroupManager mGroupManager = new NotificationGroupManager();

    public NotificationTestHelper(Context context) {
        mContext = context;
    }

    public ExpandableNotificationRow createRow() {
        LayoutInflater inflater = (LayoutInflater) mContext.getSystemService(
                mContext.LAYOUT_INFLATER_SERVICE);
        ExpandableNotificationRow row = (ExpandableNotificationRow) inflater.inflate(
                R.layout.status_bar_notification_row,
                null, false);
        row.setGroupManager(mGroupManager);
        Notification publicVersion = new Notification.Builder(mContext).setSmallIcon(
                R.drawable.ic_person)
                .setCustomContentView(new RemoteViews(mContext.getPackageName(),
                        R.layout.custom_view_dark))
                .build();
        Notification notification = new Notification.Builder(mContext).setSmallIcon(
                R.drawable.ic_person)
                .setContentTitle("Title")
                .setContentText("Text")
                .setPublicVersion(publicVersion)
                .build();
        UserHandle mUser = UserHandle.of(ActivityManager.getCurrentUser());
        StatusBarNotification sbn = new StatusBarNotification("com.android.systemui",
                "com.android.systemui", mId++, null, 1000,
                2000, notification, mUser, null, System.currentTimeMillis());
        NotificationData.Entry entry = new NotificationData.Entry(sbn);
        entry.row = row;
        try {
            entry.createIcons(mContext, sbn);
            row.updateNotification(entry);
        } catch (InflationException e) {
            throw new RuntimeException(e.getMessage());
        }
        return row;
    }

    public ExpandableNotificationRow createGroup() {
        ExpandableNotificationRow row = createRow();
        row.addChildNotification(createRow());
        row.addChildNotification(createRow());
        return row;
    }
}
