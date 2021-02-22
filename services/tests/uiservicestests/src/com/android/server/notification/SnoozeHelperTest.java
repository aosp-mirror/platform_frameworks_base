/*
 * Copyright (C) 2016 The Android Open Source Project
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
package com.android.server.notification;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.os.SystemClock;
import android.os.UserHandle;
import android.service.notification.StatusBarNotification;
import android.support.test.runner.AndroidJUnit4;
import android.test.suitebuilder.annotation.SmallTest;

import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertFalse;
import static junit.framework.Assert.assertTrue;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyInt;
import static org.mockito.Matchers.anyLong;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.android.server.UiServiceTestCase;


@SmallTest
@RunWith(AndroidJUnit4.class)
public class SnoozeHelperTest extends UiServiceTestCase {
    private static final String TEST_CHANNEL_ID = "test_channel_id";

    @Mock SnoozeHelper.Callback mCallback;
    @Mock AlarmManager mAm;
    @Mock ManagedServices.UserProfiles mUserProfiles;

    private SnoozeHelper mSnoozeHelper;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);

        mSnoozeHelper = new SnoozeHelper(getContext(), mCallback, mUserProfiles);
        mSnoozeHelper.setAlarmManager(mAm);
    }

    @Test
    public void testSnoozeSentToAndroid() throws Exception {
        NotificationRecord r = getNotificationRecord("pkg", 1, "one", UserHandle.SYSTEM);
        mSnoozeHelper.snooze(r, 1000);
        ArgumentCaptor<PendingIntent> captor = ArgumentCaptor.forClass(PendingIntent.class);
        verify(mAm, times(1)).setExactAndAllowWhileIdle(
                anyInt(), anyLong(), captor.capture());
        assertEquals("android", captor.getValue().getIntent().getPackage());
    }

    private NotificationRecord getNotificationRecord(String pkg, int id, String tag,
            UserHandle user, String groupKey, boolean groupSummary) {
        Notification n = new Notification.Builder(getContext(), TEST_CHANNEL_ID)
                .setContentTitle("A")
                .setGroup("G")
                .setSortKey("A")
                .setWhen(1205)
                .setGroup(groupKey)
                .setGroupSummary(groupSummary)
                .build();
        final NotificationChannel notificationChannel = new NotificationChannel(
                TEST_CHANNEL_ID, "name", NotificationManager.IMPORTANCE_LOW);
        return new NotificationRecord(getContext(), new StatusBarNotification(
                pkg, pkg, id, tag, 0, 0, n, user, null,
                System.currentTimeMillis()), notificationChannel);
    }

    private NotificationRecord getNotificationRecord(String pkg, int id, String tag,
            UserHandle user) {
        return getNotificationRecord(pkg, id, tag, user, null, false);
    }
}
