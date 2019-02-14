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

import static android.app.NotificationManager.IMPORTANCE_DEFAULT;
import static android.app.NotificationManager.IMPORTANCE_HIGH;

import android.annotation.Nullable;
import android.app.ActivityManager;
import android.app.Instrumentation;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Icon;
import android.os.UserHandle;
import android.service.notification.StatusBarNotification;
import android.support.test.InstrumentationRegistry;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.widget.RemoteViews;

import com.android.systemui.R;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.notification.row.ExpandableNotificationRow;
import com.android.systemui.statusbar.notification.row.NotificationContentInflater.InflationFlag;
import com.android.systemui.statusbar.notification.row.NotificationContentInflaterTest;
import com.android.systemui.statusbar.phone.HeadsUpManagerPhone;
import com.android.systemui.statusbar.phone.NotificationGroupManager;
import com.android.systemui.statusbar.policy.HeadsUpManager;

/**
 * A helper class to create {@link ExpandableNotificationRow} (for both individual and group
 * notifications).
 */
public class NotificationTestHelper {

    /** Package name for testing purposes. */
    public static final String PKG = "com.android.systemui";
    /** System UI id for testing purposes. */
    public static final int UID = 1000;
    /** Current {@link UserHandle} of the system. */
    public static final UserHandle USER_HANDLE = UserHandle.of(ActivityManager.getCurrentUser());

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
        mGroupManager.setHeadsUpManager(mHeadsUpManager);
    }

    /**
     * Creates a generic row.
     *
     * @return a generic row with no special properties.
     * @throws Exception
     */
    public ExpandableNotificationRow createRow() throws Exception {
        return createRow(PKG, UID, USER_HANDLE);
    }

    /**
     * Create a row with the package and user id specified.
     *
     * @param pkg package
     * @param uid user id
     * @return a row with a notification using the package and user id
     * @throws Exception
     */
    public ExpandableNotificationRow createRow(String pkg, int uid, UserHandle userHandle)
            throws Exception {
        return createRow(pkg, uid, userHandle, false /* isGroupSummary */, null /* groupKey */);
    }

    /**
     * Creates a row based off the notification given.
     *
     * @param notification the notification
     * @return a row built off the notification
     * @throws Exception
     */
    public ExpandableNotificationRow createRow(Notification notification) throws Exception {
        return generateRow(notification, PKG, UID, USER_HANDLE, 0 /* extraInflationFlags */);
    }

    /**
     * Create a row with the specified content views inflated in addition to the default.
     *
     * @param extraInflationFlags the flags corresponding to the additional content views that
     *                            should be inflated
     * @return a row with the specified content views inflated in addition to the default
     * @throws Exception
     */
    public ExpandableNotificationRow createRow(@InflationFlag int extraInflationFlags)
            throws Exception {
        return generateRow(createNotification(), PKG, UID, USER_HANDLE, extraInflationFlags);
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
        return createRow(PKG, UID, USER_HANDLE, true /* isGroupSummary */, groupkey);
    }

    private ExpandableNotificationRow createGroupChild(String groupkey) throws Exception {
        return createRow(PKG, UID, USER_HANDLE, false /* isGroupSummary */, groupkey);
    }

    /**
     * Returns an {@link ExpandableNotificationRow} that should be shown as a bubble.
     */
    public ExpandableNotificationRow createBubble() throws Exception {
        Notification n = createNotification(false /* isGroupSummary */,
                null /* groupKey */, true /* isBubble */);
        return generateRow(n, PKG, UID, USER_HANDLE, 0 /* extraInflationFlags */, IMPORTANCE_HIGH);
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
            UserHandle userHandle,
            boolean isGroupSummary,
            @Nullable String groupKey)
            throws Exception {
        Notification notif = createNotification(isGroupSummary, groupKey);
        return generateRow(notif, pkg, uid, userHandle, 0 /* inflationFlags */);
    }

    /**
     * Creates a generic notification.
     *
     * @return a notification with no special properties
     */
    private Notification createNotification() {
        return createNotification(false /* isGroupSummary */, null /* groupKey */);
    }

    /**
     * Creates a notification with the given parameters.
     *
     * @param isGroupSummary whether the notification is a group summary
     * @param groupKey the group key for the notification group used across notifications
     * @return a notification that is in the group specified or standalone if unspecified
     */
    private Notification createNotification(boolean isGroupSummary, @Nullable String groupKey) {
        return createNotification(isGroupSummary, groupKey, false /* isBubble */);
    }

    /**
     * Creates a notification with the given parameters.
     *
     * @param isGroupSummary whether the notification is a group summary
     * @param groupKey the group key for the notification group used across notifications
     * @param isBubble whether this notification should bubble
     * @return a notification that is in the group specified or standalone if unspecified
     */
    private Notification createNotification(boolean isGroupSummary,
            @Nullable String groupKey, boolean isBubble) {
        Notification publicVersion = new Notification.Builder(mContext).setSmallIcon(
                R.drawable.ic_person)
                .setCustomContentView(new RemoteViews(mContext.getPackageName(),
                        R.layout.custom_view_dark))
                .build();
        Notification.Builder notificationBuilder = new Notification.Builder(mContext, "channelId")
                .setSmallIcon(R.drawable.ic_person)
                .setContentTitle("Title")
                .setContentText("Text")
                .setPublicVersion(publicVersion)
                .setStyle(new Notification.BigTextStyle().bigText("Big Text"));
        if (isGroupSummary) {
            notificationBuilder.setGroupSummary(true);
        }
        if (!TextUtils.isEmpty(groupKey)) {
            notificationBuilder.setGroup(groupKey);
        }
        if (isBubble) {
            notificationBuilder.setBubbleMetadata(makeBubbleMetadata());
        }
        return notificationBuilder.build();
    }

    private ExpandableNotificationRow generateRow(
            Notification notification,
            String pkg,
            int uid,
            UserHandle userHandle,
            @InflationFlag int extraInflationFlags)
            throws Exception {
        return generateRow(notification, pkg, uid, userHandle, extraInflationFlags,
                IMPORTANCE_DEFAULT);
    }

    private ExpandableNotificationRow generateRow(
            Notification notification,
            String pkg,
            int uid,
            UserHandle userHandle,
            @InflationFlag int extraInflationFlags,
            int importance)
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
        StatusBarNotification sbn = new StatusBarNotification(
                pkg,
                pkg,
                mId++,
                null /* tag */,
                uid,
                2000 /* initialPid */,
                notification,
                userHandle,
                null /* overrideGroupKey */,
                System.currentTimeMillis());
        NotificationEntry entry = new NotificationEntry(sbn);
        entry.setRow(row);
        entry.createIcons(mContext, sbn);
        entry.channel = new NotificationChannel(
                notification.getChannelId(), notification.getChannelId(), importance);
        entry.channel.setBlockableSystem(true);
        row.setEntry(entry);
        row.getNotificationInflater().addInflationFlags(extraInflationFlags);
        NotificationContentInflaterTest.runThenWaitForInflation(
                () -> row.inflateViews(),
                row.getNotificationInflater());

        // This would be done as part of onAsyncInflationFinished, but we skip large amounts of
        // the callback chain, so we need to make up for not adding it to the group manager
        // here.
        mGroupManager.onEntryAdded(entry);
        return row;
    }

    private Notification.BubbleMetadata makeBubbleMetadata() {
        PendingIntent bubbleIntent = PendingIntent.getActivity(mContext, 0, new Intent(), 0);
        return new Notification.BubbleMetadata.Builder()
                .setIntent(bubbleIntent)
                .setTitle("bubble title")
                .setIcon(Icon.createWithResource(mContext, 1))
                .setDesiredHeight(314)
                .build();
    }
}
