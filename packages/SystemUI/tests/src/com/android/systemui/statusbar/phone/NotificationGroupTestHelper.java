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

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.app.ActivityManager;
import android.app.Notification;
import android.content.Context;
import android.os.UserHandle;
import android.service.notification.StatusBarNotification;

import com.android.systemui.R;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
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
        return createSummaryNotification(Notification.GROUP_ALERT_ALL);
    }

    public NotificationEntry createSummaryNotification(int groupAlertBehavior) {
        return createEntry(true, groupAlertBehavior);
    }

    public NotificationEntry createChildNotification() {
        return createChildNotification(Notification.GROUP_ALERT_ALL);
    }

    public NotificationEntry createChildNotification(int groupAlertBehavior) {
        return createEntry(false, groupAlertBehavior);
    }

    public NotificationEntry createEntry(boolean isSummary, int groupAlertBehavior) {
        Notification notif = new Notification.Builder(mContext, TEST_CHANNEL_ID)
                .setContentTitle("Title")
                .setSmallIcon(R.drawable.ic_person)
                .setGroupAlertBehavior(groupAlertBehavior)
                .setGroupSummary(isSummary)
                .setGroup(TEST_GROUP_ID)
                .build();
        StatusBarNotification sbn = new StatusBarNotification(
                TEST_PACKAGE_NAME /* pkg */,
                TEST_PACKAGE_NAME,
                mId++,
                null /* tag */,
                0, /* uid */
                0 /* initialPid */,
                notif,
                new UserHandle(ActivityManager.getCurrentUser()),
                null /* overrideGroupKey */,
                0 /* postTime */);
        NotificationEntry entry = new NotificationEntry(sbn);
        ExpandableNotificationRow row = mock(ExpandableNotificationRow.class);
        entry.setRow(row);
        when(row.getEntry()).thenReturn(entry);
        when(row.getStatusBarNotification()).thenReturn(sbn);
        when(row.isInflationFlagSet(anyInt())).thenReturn(true);
        return entry;
    }
}
