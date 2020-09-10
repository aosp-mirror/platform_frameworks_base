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
 * limitations under the License.
 */

package com.android.systemui.statusbar.phone;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.app.ActivityManager;
import android.app.Notification;
import android.content.Context;
import android.os.UserHandle;

import com.android.systemui.R;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.notification.collection.NotificationEntryBuilder;
import com.android.systemui.statusbar.notification.row.ExpandableNotificationRow;

/**
 * Helper class for creating groups/summaries without having to inflate them.
 */
public final class NotificationGroupTestHelper {
    private static final String TEST_CHANNEL_ID = "test_channel";
    private static final String TEST_GROUP_ID = "test_group";
    private static final String TEST_PACKAGE_NAME = "test_pkg";
    private int mId = 0;
    private final Context mContext;

    public NotificationGroupTestHelper(Context context) {
        mContext = context;
    }

    public NotificationEntry createSummaryNotification() {
        return createSummaryNotification(Notification.GROUP_ALERT_ALL, mId++);
    }

    public NotificationEntry createSummaryNotification(int groupAlertBehavior) {
        return createSummaryNotification(groupAlertBehavior, mId++);
    }

    public NotificationEntry createSummaryNotification(int groupAlertBehavior, int id) {
        return createEntry(id, true, groupAlertBehavior);
    }

    public NotificationEntry createChildNotification() {
        return createChildNotification(Notification.GROUP_ALERT_ALL);
    }

    public NotificationEntry createChildNotification(int groupAlertBehavior) {
        return createEntry(mId++, false, groupAlertBehavior);
    }

    public NotificationEntry createChildNotification(int groupAlertBehavior, int id) {
        return createEntry(id, false, groupAlertBehavior);
    }

    public NotificationEntry createEntry(int id, boolean isSummary, int groupAlertBehavior) {
        Notification notif = new Notification.Builder(mContext, TEST_CHANNEL_ID)
                .setContentTitle("Title")
                .setSmallIcon(R.drawable.ic_person)
                .setGroupAlertBehavior(groupAlertBehavior)
                .setGroupSummary(isSummary)
                .setGroup(TEST_GROUP_ID)
                .build();
        NotificationEntry entry = new NotificationEntryBuilder()
                .setPkg(TEST_PACKAGE_NAME)
                .setOpPkg(TEST_PACKAGE_NAME)
                .setId(id)
                .setNotification(notif)
                .setUser(new UserHandle(ActivityManager.getCurrentUser()))
                .build();

        ExpandableNotificationRow row = mock(ExpandableNotificationRow.class);
        entry.setRow(row);
        when(row.getEntry()).thenReturn(entry);
        return entry;
    }
}
