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

import android.annotation.Nullable;
import android.app.ActivityManager;
import android.app.Instrumentation;
import android.app.Notification;
import android.content.Context;
import android.os.UserHandle;
import android.service.notification.StatusBarNotification;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.widget.RemoteViews;

import androidx.test.InstrumentationRegistry;

import com.android.systemui.R;
import com.android.systemui.statusbar.notification.NotificationInflaterTest;
import com.android.systemui.statusbar.phone.HeadsUpManagerPhone;
import com.android.systemui.statusbar.phone.NotificationGroupManager;
import com.android.systemui.statusbar.policy.HeadsUpManager;

/**
 * A helper class to create {@link ExpandableNotificationRow} (for both individual and group
 * notifications).
 */
public class NotificationTestHelper {

    static final String PKG = "com.android.systemui";
    static final int UID = 1000;
    private static final String GROUP_KEY = "gruKey";

    private final Context mContext;
    private final Instrumentation mInstrumentation;
    private int mId;
    private final NotificationGroupManager mGroupManager = new NotificationGroupManager();
    private ExpandableNotificationRow mRow;
    private HeadsUpManager mHeadsUpManager;

    public NotificationTestHelper(Context context) {
        mContext = context;
        mInstrumentation = InstrumentationRegistry.getInstrumentation();
        mHeadsUpManager = new HeadsUpManagerPhone(mContext, null, mGroupManager, null, null);
    }

    public ExpandableNotificationRow createRow() throws Exception {
        return createRow(PKG, UID);
    }

    public ExpandableNotificationRow createRow(String pkg, int uid) throws Exception {
        return createRow(pkg, uid, false /* isGroupSummary */, null /* groupKey */);
    }

    public ExpandableNotificationRow createRow(Notification notification) throws Exception {
        return generateRow(notification, PKG, UID, false /* isGroupRow */);
    }

    /**
     * Returns an {@link ExpandableNotificationRow} group with the given number of child
     * notifications.
     */
    public ExpandableNotificationRow createGroup(int numChildren) throws Exception {
        ExpandableNotificationRow row = createGroupSummary(GROUP_KEY);
        for (int i = 0; i < numChildren; i++) {
            ExpandableNotificationRow childRow = createGroupChild(GROUP_KEY);
            row.addChildNotification(childRow);
        }
        return row;
    }

    /** Returns a group notification with 2 child notifications. */
    public ExpandableNotificationRow createGroup() throws Exception {
        return createGroup(2);
    }

    private ExpandableNotificationRow createGroupSummary(String groupkey) throws Exception {
        return createRow(PKG, UID, true /* isGroupSummary */, groupkey);
    }

    private ExpandableNotificationRow createGroupChild(String groupkey) throws Exception {
        return createRow(PKG, UID, false /* isGroupSummary */, groupkey);
    }

    /**
     * Creates a notification row with the given details.
     *
     * @param pkg package used for creating a {@link StatusBarNotification}
     * @param uid uid used for creating a {@link StatusBarNotification}
     * @param isGroupSummary whether the notification row is a group summary
     * @param groupKey the group key for the notification group used across notifications
     * @return a row with that's either a standalone notification or a group notification if the
     *         groupKey is non-null
     * @throws Exception
     */
    private ExpandableNotificationRow createRow(
            String pkg,
            int uid,
            boolean isGroupSummary,
            @Nullable String groupKey)
            throws Exception {
        Notification publicVersion = new Notification.Builder(mContext).setSmallIcon(
                R.drawable.ic_person)
                .setCustomContentView(new RemoteViews(mContext.getPackageName(),
                        R.layout.custom_view_dark))
                .build();
        Notification.Builder notificationBuilder =
                new Notification.Builder(mContext)
                        .setSmallIcon(R.drawable.ic_person)
                        .setContentTitle("Title")
                        .setContentText("Text")
                        .setPublicVersion(publicVersion);

        // Group notification setup
        if (isGroupSummary) {
            notificationBuilder.setGroupSummary(true);
        }
        if (!TextUtils.isEmpty(groupKey)) {
            notificationBuilder.setGroup(groupKey);
        }

        return generateRow(notificationBuilder.build(), pkg, uid, !TextUtils.isEmpty(groupKey));
    }

    private ExpandableNotificationRow generateRow(
            Notification notification,
            String pkg,
            int uid,
            boolean isGroupRow)
            throws Exception {
        LayoutInflater inflater = (LayoutInflater) mContext.getSystemService(
                mContext.LAYOUT_INFLATER_SERVICE);
        mRow = (ExpandableNotificationRow) inflater.inflate(
                R.layout.status_bar_notification_row,
                null /* root */,
                false /* attachToRoot */);
        ExpandableNotificationRow row = mRow;
        row.setGroupManager(mGroupManager);
        row.setHeadsUpManager(mHeadsUpManager);
        row.setAboveShelfChangedListener(aboveShelf -> {});
        UserHandle mUser = UserHandle.of(ActivityManager.getCurrentUser());
        StatusBarNotification sbn = new StatusBarNotification(
                pkg,
                pkg,
                mId++,
                null /* tag */,
                uid,
                2000 /* initialPid */,
                notification,
                mUser,
                null /* overrideGroupKey */,
                System.currentTimeMillis());
        NotificationData.Entry entry = new NotificationData.Entry(sbn);
        entry.row = row;
        entry.createIcons(mContext, sbn);
        NotificationInflaterTest.runThenWaitForInflation(
                () -> row.updateNotification(entry),
                row.getNotificationInflater());

        // This would be done as part of onAsyncInflationFinished, but we skip large amounts of
        // the callback chain, so we need to make up for not adding it to the group manager
        // here.
        mGroupManager.onEntryAdded(entry);
        return row;
    }
}
