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
import android.util.IntArray;

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
    public void testSnoozeForTime() throws Exception {
        NotificationRecord r = getNotificationRecord("pkg", 1, "one", UserHandle.SYSTEM);
        mSnoozeHelper.snooze(r, 1000);
        ArgumentCaptor<Long> captor = ArgumentCaptor.forClass(Long.class);
        verify(mAm, times(1)).setExactAndAllowWhileIdle(
                anyInt(), captor.capture(), any(PendingIntent.class));
        long actualSnoozedUntilDuration = captor.getValue() - SystemClock.elapsedRealtime();
        assertTrue(Math.abs(actualSnoozedUntilDuration - 1000) < 250);
        assertTrue(mSnoozeHelper.isSnoozed(
                UserHandle.USER_SYSTEM, r.sbn.getPackageName(), r.getKey()));
    }

    @Test
    public void testSnooze() throws Exception {
        NotificationRecord r = getNotificationRecord("pkg", 1, "one", UserHandle.SYSTEM);
        mSnoozeHelper.snooze(r);
        verify(mAm, never()).setExactAndAllowWhileIdle(
                anyInt(), anyLong(), any(PendingIntent.class));
        assertTrue(mSnoozeHelper.isSnoozed(
                UserHandle.USER_SYSTEM, r.sbn.getPackageName(), r.getKey()));
    }

    @Test
    public void testCancelByApp() throws Exception {
        NotificationRecord r = getNotificationRecord("pkg", 1, "one", UserHandle.SYSTEM);
        NotificationRecord r2 = getNotificationRecord("pkg", 2, "two", UserHandle.SYSTEM);
        mSnoozeHelper.snooze(r, 1000);
        mSnoozeHelper.snooze(r2 , 1000);
        assertTrue(mSnoozeHelper.isSnoozed(
                UserHandle.USER_SYSTEM, r.sbn.getPackageName(), r.getKey()));
        assertTrue(mSnoozeHelper.isSnoozed(
                UserHandle.USER_SYSTEM, r2.sbn.getPackageName(), r2.getKey()));

        mSnoozeHelper.cancel(UserHandle.USER_SYSTEM, r.sbn.getPackageName(), "one", 1);
        // 2 = one for each snooze, above, zero for the cancel.
        verify(mAm, times(2)).cancel(any(PendingIntent.class));
        assertTrue(mSnoozeHelper.isSnoozed(
                UserHandle.USER_SYSTEM, r.sbn.getPackageName(), r.getKey()));
        assertTrue(mSnoozeHelper.isSnoozed(
                UserHandle.USER_SYSTEM, r2.sbn.getPackageName(), r2.getKey()));
    }

    @Test
    public void testCancelAllForUser() throws Exception {
        NotificationRecord r = getNotificationRecord("pkg", 1, "one", UserHandle.SYSTEM);
        NotificationRecord r2 = getNotificationRecord("pkg", 2, "two", UserHandle.SYSTEM);
        NotificationRecord r3 = getNotificationRecord("pkg", 3, "three", UserHandle.ALL);
        mSnoozeHelper.snooze(r,  1000);
        mSnoozeHelper.snooze(r2, 1000);
        mSnoozeHelper.snooze(r3, 1000);
        assertTrue(mSnoozeHelper.isSnoozed(
                UserHandle.USER_SYSTEM, r.sbn.getPackageName(), r.getKey()));
        assertTrue(mSnoozeHelper.isSnoozed(
                UserHandle.USER_SYSTEM, r2.sbn.getPackageName(), r2.getKey()));
        assertTrue(mSnoozeHelper.isSnoozed(
                UserHandle.USER_ALL, r3.sbn.getPackageName(), r3.getKey()));

        mSnoozeHelper.cancel(UserHandle.USER_SYSTEM, false);
        // 3 = once for each snooze above (3), only.
        verify(mAm, times(3)).cancel(any(PendingIntent.class));
        assertTrue(mSnoozeHelper.isSnoozed(
                UserHandle.USER_SYSTEM, r.sbn.getPackageName(), r.getKey()));
        assertTrue(mSnoozeHelper.isSnoozed(
                UserHandle.USER_SYSTEM, r2.sbn.getPackageName(), r2.getKey()));
        assertTrue(mSnoozeHelper.isSnoozed(
                UserHandle.USER_ALL, r3.sbn.getPackageName(), r3.getKey()));
    }

    @Test
    public void testCancelAllByApp() throws Exception {
        NotificationRecord r = getNotificationRecord("pkg", 1, "one", UserHandle.SYSTEM);
        NotificationRecord r2 = getNotificationRecord("pkg", 2, "two", UserHandle.SYSTEM);
        NotificationRecord r3 = getNotificationRecord("pkg2", 3, "three", UserHandle.SYSTEM);
        mSnoozeHelper.snooze(r, 1000);
        mSnoozeHelper.snooze(r2, 1000);
        mSnoozeHelper.snooze(r3, 1000);
        assertTrue(mSnoozeHelper.isSnoozed(
                UserHandle.USER_SYSTEM, r.sbn.getPackageName(), r.getKey()));
        assertTrue(mSnoozeHelper.isSnoozed(
                UserHandle.USER_SYSTEM, r2.sbn.getPackageName(), r2.getKey()));
        assertTrue(mSnoozeHelper.isSnoozed(
                UserHandle.USER_SYSTEM, r3.sbn.getPackageName(), r3.getKey()));

        mSnoozeHelper.cancel(UserHandle.USER_SYSTEM, "pkg2");
        // 3 = once for each snooze above (3), only.
        verify(mAm, times(3)).cancel(any(PendingIntent.class));
        assertTrue(mSnoozeHelper.isSnoozed(
                UserHandle.USER_SYSTEM, r.sbn.getPackageName(), r.getKey()));
        assertTrue(mSnoozeHelper.isSnoozed(
                UserHandle.USER_SYSTEM, r2.sbn.getPackageName(), r2.getKey()));
        assertTrue(mSnoozeHelper.isSnoozed(
                UserHandle.USER_SYSTEM, r3.sbn.getPackageName(), r3.getKey()));
    }

    @Test
    public void testCancelDoesNotUnsnooze() throws Exception {
        NotificationRecord r = getNotificationRecord("pkg", 1, "one", UserHandle.SYSTEM);
        mSnoozeHelper.snooze(r, 1000);
        assertTrue(mSnoozeHelper.isSnoozed(
                UserHandle.USER_SYSTEM, r.sbn.getPackageName(), r.getKey()));

        mSnoozeHelper.cancel(UserHandle.USER_SYSTEM, r.sbn.getPackageName(), "one", 1);

        assertTrue(mSnoozeHelper.isSnoozed(
                UserHandle.USER_SYSTEM, r.sbn.getPackageName(), r.getKey()));
    }

    @Test
    public void testCancelDoesNotRepost() throws Exception {
        NotificationRecord r = getNotificationRecord("pkg", 1, "one", UserHandle.SYSTEM);
        NotificationRecord r2 = getNotificationRecord("pkg", 2, "two", UserHandle.SYSTEM);
        mSnoozeHelper.snooze(r, 1000);
        mSnoozeHelper.snooze(r2 , 1000);
        assertTrue(mSnoozeHelper.isSnoozed(
                UserHandle.USER_SYSTEM, r.sbn.getPackageName(), r.getKey()));
        assertTrue(mSnoozeHelper.isSnoozed(
                UserHandle.USER_SYSTEM, r2.sbn.getPackageName(), r2.getKey()));

        mSnoozeHelper.cancel(UserHandle.USER_SYSTEM, r.sbn.getPackageName(), "one", 1);

        mSnoozeHelper.repost(r.getKey(), UserHandle.USER_SYSTEM);
        verify(mCallback, never()).repost(UserHandle.USER_SYSTEM, r);
    }

    @Test
    public void testRepost() throws Exception {
        NotificationRecord r = getNotificationRecord("pkg", 1, "one", UserHandle.SYSTEM);
        mSnoozeHelper.snooze(r, 1000);
        NotificationRecord r2 = getNotificationRecord("pkg", 2, "one", UserHandle.ALL);
        mSnoozeHelper.snooze(r2, 1000);
        mSnoozeHelper.repost(r.getKey(), UserHandle.USER_SYSTEM);
        verify(mCallback, times(1)).repost(UserHandle.USER_SYSTEM, r);
    }

    @Test
    public void testRepost_noUser() throws Exception {
        NotificationRecord r = getNotificationRecord("pkg", 1, "one", UserHandle.SYSTEM);
        mSnoozeHelper.snooze(r, 1000);
        NotificationRecord r2 = getNotificationRecord("pkg", 2, "one", UserHandle.ALL);
        mSnoozeHelper.snooze(r2, 1000);
        mSnoozeHelper.repost(r.getKey());
        verify(mCallback, times(1)).repost(UserHandle.USER_SYSTEM, r);
    }

    @Test
    public void testUpdate() throws Exception {
        NotificationRecord r = getNotificationRecord("pkg", 1, "one", UserHandle.SYSTEM);
        mSnoozeHelper.snooze(r , 1000);
        r.getNotification().category = "NEW CATEGORY";

        mSnoozeHelper.update(UserHandle.USER_SYSTEM, r);
        verify(mCallback, never()).repost(anyInt(), any(NotificationRecord.class));

        mSnoozeHelper.repost(r.getKey(), UserHandle.USER_SYSTEM);
        verify(mCallback, times(1)).repost(UserHandle.USER_SYSTEM, r);
    }

    @Test
    public void testGetSnoozedByUser() throws Exception {
        NotificationRecord r = getNotificationRecord("pkg", 1, "one", UserHandle.SYSTEM);
        NotificationRecord r2 = getNotificationRecord("pkg", 2, "two", UserHandle.SYSTEM);
        NotificationRecord r3 = getNotificationRecord("pkg2", 3, "three", UserHandle.SYSTEM);
        NotificationRecord r4 = getNotificationRecord("pkg2", 3, "three", UserHandle.CURRENT);
        mSnoozeHelper.snooze(r, 1000);
        mSnoozeHelper.snooze(r2, 1000);
        mSnoozeHelper.snooze(r3, 1000);
        mSnoozeHelper.snooze(r4, 1000);
        IntArray profileIds = new IntArray();
        profileIds.add(UserHandle.USER_SYSTEM);
        when(mUserProfiles.getCurrentProfileIds()).thenReturn(profileIds);
        assertEquals(3, mSnoozeHelper.getSnoozed().size());
        profileIds = new IntArray();
        profileIds.add(UserHandle.USER_CURRENT);
        when(mUserProfiles.getCurrentProfileIds()).thenReturn(profileIds);
        assertEquals(1, mSnoozeHelper.getSnoozed().size());
    }

    @Test
    public void testGetSnoozedByUser_managedProfiles() throws Exception {
        IntArray profileIds = new IntArray();
        profileIds.add(UserHandle.USER_CURRENT);
        profileIds.add(UserHandle.USER_SYSTEM);
        when(mUserProfiles.getCurrentProfileIds()).thenReturn(profileIds);
        NotificationRecord r = getNotificationRecord("pkg", 1, "one", UserHandle.SYSTEM);
        NotificationRecord r2 = getNotificationRecord("pkg", 2, "two", UserHandle.SYSTEM);
        NotificationRecord r3 = getNotificationRecord("pkg2", 3, "three", UserHandle.SYSTEM);
        NotificationRecord r4 = getNotificationRecord("pkg2", 3, "three", UserHandle.CURRENT);
        mSnoozeHelper.snooze(r, 1000);
        mSnoozeHelper.snooze(r2, 1000);
        mSnoozeHelper.snooze(r3, 1000);
        mSnoozeHelper.snooze(r4, 1000);
        assertEquals(4, mSnoozeHelper.getSnoozed().size());
    }

    @Test
    public void repostGroupSummary_onlyFellowGroupChildren() throws Exception {
        NotificationRecord r = getNotificationRecord(
                "pkg", 1, "one", UserHandle.SYSTEM, "group1", false);
        NotificationRecord r2 = getNotificationRecord(
                "pkg", 2, "two", UserHandle.SYSTEM, "group1", false);
        mSnoozeHelper.snooze(r, 1000);
        mSnoozeHelper.snooze(r2, 1000);
        mSnoozeHelper.repostGroupSummary("pkg", UserHandle.USER_SYSTEM, "group1");

        verify(mCallback, never()).repost(UserHandle.USER_SYSTEM, r);
    }

    @Test
    public void repostGroupSummary_repostsSummary() throws Exception {
        IntArray profileIds = new IntArray();
        profileIds.add(UserHandle.USER_SYSTEM);
        when(mUserProfiles.getCurrentProfileIds()).thenReturn(profileIds);
        NotificationRecord r = getNotificationRecord(
                "pkg", 1, "one", UserHandle.SYSTEM, "group1", true);
        NotificationRecord r2 = getNotificationRecord(
                "pkg", 2, "two", UserHandle.SYSTEM, "group1", false);
        mSnoozeHelper.snooze(r, 1000);
        mSnoozeHelper.snooze(r2, 1000);
        assertEquals(2, mSnoozeHelper.getSnoozed().size());
        assertEquals(2, mSnoozeHelper.getSnoozed(UserHandle.USER_SYSTEM, "pkg").size());

        mSnoozeHelper.repostGroupSummary("pkg", UserHandle.USER_SYSTEM, r.getGroupKey());

        verify(mCallback, times(1)).repost(UserHandle.USER_SYSTEM, r);
        verify(mCallback, never()).repost(UserHandle.USER_SYSTEM, r2);

        assertEquals(1, mSnoozeHelper.getSnoozed().size());
        assertEquals(1, mSnoozeHelper.getSnoozed(UserHandle.USER_SYSTEM, "pkg").size());
    }

    @Test
    public void testClearData() {
        // snooze 2 from same package
        NotificationRecord r = getNotificationRecord("pkg", 1, "one", UserHandle.SYSTEM);
        NotificationRecord r2 = getNotificationRecord("pkg", 2, "two", UserHandle.SYSTEM);
        mSnoozeHelper.snooze(r, 1000);
        mSnoozeHelper.snooze(r2, 1000);
        assertTrue(mSnoozeHelper.isSnoozed(
                UserHandle.USER_SYSTEM, r.sbn.getPackageName(), r.getKey()));
        assertTrue(mSnoozeHelper.isSnoozed(
                UserHandle.USER_SYSTEM, r2.sbn.getPackageName(), r2.getKey()));

        // clear data
        mSnoozeHelper.clearData(UserHandle.USER_SYSTEM, "pkg");

        // nothing snoozed; alarms canceled
        assertFalse(mSnoozeHelper.isSnoozed(
                UserHandle.USER_SYSTEM, r.sbn.getPackageName(), r.getKey()));
        assertFalse(mSnoozeHelper.isSnoozed(
                UserHandle.USER_SYSTEM, r2.sbn.getPackageName(), r2.getKey()));
        // twice for initial snooze, twice for canceling the snooze
        verify(mAm, times(4)).cancel(any(PendingIntent.class));
    }

    @Test
    public void testClearData_otherRecordsUntouched() {
        // 2 packages, 2 users
        NotificationRecord r = getNotificationRecord("pkg", 1, "one", UserHandle.SYSTEM);
        NotificationRecord r2 = getNotificationRecord("pkg", 2, "two", UserHandle.ALL);
        NotificationRecord r3 = getNotificationRecord("pkg2", 3, "three", UserHandle.SYSTEM);
        mSnoozeHelper.snooze(r, 1000);
        mSnoozeHelper.snooze(r2, 1000);
        mSnoozeHelper.snooze(r3, 1000);
        assertTrue(mSnoozeHelper.isSnoozed(
                UserHandle.USER_SYSTEM, r.sbn.getPackageName(), r.getKey()));
        assertTrue(mSnoozeHelper.isSnoozed(
                UserHandle.USER_ALL, r2.sbn.getPackageName(), r2.getKey()));
        assertTrue(mSnoozeHelper.isSnoozed(
                UserHandle.USER_SYSTEM, r3.sbn.getPackageName(), r3.getKey()));

        // clear data
        mSnoozeHelper.clearData(UserHandle.USER_SYSTEM, "pkg");

        assertFalse(mSnoozeHelper.isSnoozed(
                UserHandle.USER_SYSTEM, r.sbn.getPackageName(), r.getKey()));
        assertTrue(mSnoozeHelper.isSnoozed(
                UserHandle.USER_ALL, r2.sbn.getPackageName(), r2.getKey()));
        assertTrue(mSnoozeHelper.isSnoozed(
                UserHandle.USER_SYSTEM, r3.sbn.getPackageName(), r3.getKey()));
        // once for each initial snooze, once for canceling one snooze
        verify(mAm, times(4)).cancel(any(PendingIntent.class));
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
