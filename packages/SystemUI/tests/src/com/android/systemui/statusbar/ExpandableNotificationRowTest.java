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

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.app.ActivityManager;
import android.app.Notification;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.UserHandle;
import android.service.notification.StatusBarNotification;
import android.support.test.InstrumentationRegistry;
import android.support.test.annotation.UiThreadTest;
import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.RemoteViews;

import com.android.systemui.R;
import com.android.systemui.statusbar.notification.InflationException;
import com.android.systemui.statusbar.notification.NotificationCustomViewWrapper;
import com.android.systemui.statusbar.notification.NotificationInflater;
import com.android.systemui.statusbar.notification.NotificationViewWrapper;
import com.android.systemui.statusbar.phone.NotificationGroupManager;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class ExpandableNotificationRowTest {

    private Context mContext;
    private ExpandableNotificationRow mRow;
    private NotificationGroupManager mGroupManager = new NotificationGroupManager();
    private int mId;

    @Before
    @UiThreadTest
    public void setUp() {
        mContext = InstrumentationRegistry.getTargetContext();
        mRow = createNotification();
    }

    private ExpandableNotificationRow createNotification() {
        LayoutInflater inflater = (LayoutInflater) mContext.getSystemService(
                Context.LAYOUT_INFLATER_SERVICE);
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

    @Test
    public void testGroupSummaryNotShowingIconWhenPublic() {
        mRow.setSensitive(true, true);
        mRow.addChildNotification(createNotification());
        mRow.addChildNotification(createNotification());
        mRow.setHideSensitive(true, false, 0, 0);
        Assert.assertTrue(mRow.isSummaryWithChildren());
        Assert.assertFalse(mRow.isShowingIcon());
    }

}
