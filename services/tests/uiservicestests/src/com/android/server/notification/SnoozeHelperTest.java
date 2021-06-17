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

import static com.android.server.notification.SnoozeHelper.EXTRA_KEY;

import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertFalse;
import static junit.framework.Assert.assertNull;
import static junit.framework.Assert.assertTrue;

import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyInt;
import static org.mockito.Matchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.os.SystemClock;
import android.os.UserHandle;
import android.service.notification.StatusBarNotification;
import android.test.suitebuilder.annotation.SmallTest;
import android.util.IntArray;
import android.util.TypedXmlPullParser;
import android.util.TypedXmlSerializer;
import android.util.Xml;

import androidx.test.runner.AndroidJUnit4;

import com.android.internal.util.FastXmlSerializer;
import com.android.server.UiServiceTestCase;
import com.android.server.pm.PackageManagerService;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlSerializer;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

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
    public void testWriteXMLformattedCorrectly_testReadingCorrectTime()
            throws XmlPullParserException, IOException {
        final String max_time_str = Long.toString(Long.MAX_VALUE);
        final String xml_string = "<snoozed-notifications>"
                + "<notification version=\"1\" user-id=\"0\" notification=\"notification\" "
                + "pkg=\"pkg\" key=\"key\" time=\"" + max_time_str + "\"/>"
                + "<notification version=\"1\" user-id=\"0\" notification=\"notification\" "
                + "pkg=\"pkg\" key=\"key2\" time=\"" + max_time_str + "\"/>"
                + "</snoozed-notifications>";
        TypedXmlPullParser parser = Xml.newFastPullParser();
        parser.setInput(new BufferedInputStream(
                new ByteArrayInputStream(xml_string.getBytes())), null);
        mSnoozeHelper.readXml(parser, 1);
        assertEquals((long) Long.MAX_VALUE, (long) mSnoozeHelper
                .getSnoozeTimeForUnpostedNotification(0, "pkg", "key"));
        verify(mAm, never()).setExactAndAllowWhileIdle(anyInt(), anyLong(), any());
    }

    @Test
    public void testWriteXML_afterReading_noNPE()
            throws XmlPullParserException, IOException {
        final String max_time_str = Long.toString(Long.MAX_VALUE);
        final String xml_string = "<snoozed-notifications>"
                + "<notification version=\"1\" user-id=\"0\" notification=\"notification\" "
                + "pkg=\"pkg\" key=\"key\" time=\"" + max_time_str + "\"/>"
                + "<notification version=\"1\" user-id=\"0\" notification=\"notification\" "
                + "pkg=\"pkg\" key=\"key2\" time=\"" + max_time_str + "\"/>"
                + "</snoozed-notifications>";
        TypedXmlPullParser parser = Xml.newFastPullParser();
        parser.setInput(new BufferedInputStream(
                new ByteArrayInputStream(xml_string.getBytes())), null);
        mSnoozeHelper.readXml(parser, 1);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        TypedXmlSerializer serializer = Xml.newFastSerializer();
        serializer.setOutput(new BufferedOutputStream(baos), "utf-8");
        serializer.startDocument(null, true);
        mSnoozeHelper.writeXml(serializer);
        serializer.endDocument();
        serializer.flush();
    }

    @Test
    public void testWriteXMLformattedCorrectly_testCorrectContextURI()
            throws XmlPullParserException, IOException {
        final String max_time_str = Long.toString(Long.MAX_VALUE);
        final String xml_string = "<snoozed-notifications>"
                + "<context version=\"1\" user-id=\"0\" notification=\"notification\" "
                + "pkg=\"pkg\" key=\"key\" id=\"uri\"/>"
                + "<context version=\"1\" user-id=\"0\" notification=\"notification\" "
                + "pkg=\"pkg\" key=\"key2\" id=\"uri\"/>"
                + "</snoozed-notifications>";
        TypedXmlPullParser parser = Xml.newFastPullParser();
        parser.setInput(new BufferedInputStream(
                new ByteArrayInputStream(xml_string.getBytes())), null);
        mSnoozeHelper.readXml(parser, 1);
        assertEquals("Should read the notification context from xml and it should be `uri",
                "uri", mSnoozeHelper.getSnoozeContextForUnpostedNotification(
                        0, "pkg", "key"));
    }

    @Test
    public void testReadValidSnoozedFromCorrectly_timeDeadline()
            throws XmlPullParserException, IOException {
        NotificationRecord r = getNotificationRecord("pkg", 1, "one", UserHandle.SYSTEM);
        mSnoozeHelper.snooze(r, 999999999);
        TypedXmlSerializer serializer = Xml.newFastSerializer();
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        serializer.setOutput(new BufferedOutputStream(baos), "utf-8");
        serializer.startDocument(null, true);
        mSnoozeHelper.writeXml(serializer);
        serializer.endDocument();
        serializer.flush();

        TypedXmlPullParser parser = Xml.newFastPullParser();
        parser.setInput(new BufferedInputStream(
                new ByteArrayInputStream(baos.toByteArray())), "utf-8");
        mSnoozeHelper.readXml(parser, 1);
        assertTrue("Should read the notification time from xml and it should be more than zero",
                0 < mSnoozeHelper.getSnoozeTimeForUnpostedNotification(
                        0, "pkg", r.getKey()).doubleValue());
    }


    @Test
    public void testReadExpiredSnoozedNotification() throws
            XmlPullParserException, IOException, InterruptedException {
        NotificationRecord r = getNotificationRecord("pkg", 1, "one", UserHandle.SYSTEM);
        mSnoozeHelper.snooze(r, 0);
       // Thread.sleep(100);
        TypedXmlSerializer serializer = Xml.newFastSerializer();
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        serializer.setOutput(new BufferedOutputStream(baos), "utf-8");
        serializer.startDocument(null, true);
        mSnoozeHelper.writeXml(serializer);
        serializer.endDocument();
        serializer.flush();
        Thread.sleep(10);
        TypedXmlPullParser parser = Xml.newFastPullParser();
        parser.setInput(new BufferedInputStream(
                new ByteArrayInputStream(baos.toByteArray())), "utf-8");
        mSnoozeHelper.readXml(parser, 2);
        int systemUser = UserHandle.SYSTEM.getIdentifier();
        assertTrue("Should see a past time returned",
                System.currentTimeMillis() >  mSnoozeHelper.getSnoozeTimeForUnpostedNotification(
                        systemUser, "pkg", r.getKey()).longValue());
    }

    @Test
    public void testCleanupContextShouldRemovePersistedRecord() {
        NotificationRecord r = getNotificationRecord("pkg", 1, "one", UserHandle.SYSTEM);
        mSnoozeHelper.snooze(r, "context");
        mSnoozeHelper.cleanupPersistedContext(r.getSbn().getKey());
        assertNull(mSnoozeHelper.getSnoozeContextForUnpostedNotification(
                r.getUser().getIdentifier(),
                r.getSbn().getPackageName(),
                r.getSbn().getKey()
        ));
    }

    @Test
    public void testReadNoneSnoozedNotification() throws XmlPullParserException,
            IOException, InterruptedException {
        NotificationRecord r = getNotificationRecord(
                "pkg", 1, "one", UserHandle.SYSTEM);
        mSnoozeHelper.snooze(r, 0);

        assertEquals("should see a zero value for unsnoozed notification",
                0L,
                mSnoozeHelper.getSnoozeTimeForUnpostedNotification(
                        UserHandle.SYSTEM.getIdentifier(),
                        "not_my_package", r.getKey()).longValue());
    }

    @Test
    public void testScheduleRepostsForPersistedNotifications() throws Exception {
        final String xml_string = "<snoozed-notifications>"
                + "<notification version=\"1\" user-id=\"0\" notification=\"notification\" "
                + "pkg=\"pkg\" key=\"key\" time=\"" + 10 + "\"/>"
                + "<notification version=\"1\" user-id=\"0\" notification=\"notification\" "
                + "pkg=\"pkg\" key=\"key2\" time=\"" + 15+ "\"/>"
                + "</snoozed-notifications>";
        TypedXmlPullParser parser = Xml.newFastPullParser();
        parser.setInput(new BufferedInputStream(
                new ByteArrayInputStream(xml_string.getBytes())), null);
        mSnoozeHelper.readXml(parser, 4);

        mSnoozeHelper.scheduleRepostsForPersistedNotifications(5);

        ArgumentCaptor<PendingIntent> captor = ArgumentCaptor.forClass(PendingIntent.class);
        verify(mAm).setExactAndAllowWhileIdle(anyInt(), eq((long) 10), captor.capture());
        assertEquals("key", captor.getValue().getIntent().getStringExtra(EXTRA_KEY));

        ArgumentCaptor<PendingIntent> captor2 = ArgumentCaptor.forClass(PendingIntent.class);
        verify(mAm).setExactAndAllowWhileIdle(anyInt(), eq((long) 15), captor2.capture());
        assertEquals("key2", captor2.getValue().getIntent().getStringExtra(EXTRA_KEY));
    }

    @Test
    public void testSnoozeForTime() throws Exception {
        NotificationRecord r = getNotificationRecord("pkg", 1, "one", UserHandle.SYSTEM);
        mSnoozeHelper.snooze(r, 1000);
        ArgumentCaptor<Long> captor = ArgumentCaptor.forClass(Long.class);
        verify(mAm, times(1)).setExactAndAllowWhileIdle(
                anyInt(), captor.capture(), any(PendingIntent.class));
        long actualSnoozedUntilDuration = captor.getValue() - System.currentTimeMillis();
        assertTrue(Math.abs(actualSnoozedUntilDuration - 1000) < 250);
        assertTrue(mSnoozeHelper.isSnoozed(
                UserHandle.USER_SYSTEM, r.getSbn().getPackageName(), r.getKey()));
    }

    @Test
    public void testSnoozeSentToAndroid() throws Exception {
        NotificationRecord r = getNotificationRecord("pkg", 1, "one", UserHandle.SYSTEM);
        mSnoozeHelper.snooze(r, 1000);
        ArgumentCaptor<PendingIntent> captor = ArgumentCaptor.forClass(PendingIntent.class);
        verify(mAm, times(1)).setExactAndAllowWhileIdle(
                anyInt(), anyLong(), captor.capture());
        assertEquals(PackageManagerService.PLATFORM_PACKAGE_NAME,
                captor.getValue().getIntent().getPackage());
    }

    @Test
    public void testSnooze() throws Exception {
        NotificationRecord r = getNotificationRecord("pkg", 1, "one", UserHandle.SYSTEM);
        mSnoozeHelper.snooze(r, (String) null);
        verify(mAm, never()).setExactAndAllowWhileIdle(
                anyInt(), anyLong(), any(PendingIntent.class));
        assertTrue(mSnoozeHelper.isSnoozed(
                UserHandle.USER_SYSTEM, r.getSbn().getPackageName(), r.getKey()));
    }

    @Test
    public void testCancelByApp() throws Exception {
        NotificationRecord r = getNotificationRecord("pkg", 1, "one", UserHandle.SYSTEM);
        NotificationRecord r2 = getNotificationRecord("pkg", 2, "two", UserHandle.SYSTEM);
        mSnoozeHelper.snooze(r, 1000);
        mSnoozeHelper.snooze(r2 , 1000);
        assertTrue(mSnoozeHelper.isSnoozed(
                UserHandle.USER_SYSTEM, r.getSbn().getPackageName(), r.getKey()));
        assertTrue(mSnoozeHelper.isSnoozed(
                UserHandle.USER_SYSTEM, r2.getSbn().getPackageName(), r2.getKey()));

        mSnoozeHelper.cancel(UserHandle.USER_SYSTEM, r.getSbn().getPackageName(), "one", 1);
        // 2 = one for each snooze, above, zero for the cancel.
        verify(mAm, times(2)).cancel(any(PendingIntent.class));
        assertTrue(mSnoozeHelper.isSnoozed(
                UserHandle.USER_SYSTEM, r.getSbn().getPackageName(), r.getKey()));
        assertTrue(mSnoozeHelper.isSnoozed(
                UserHandle.USER_SYSTEM, r2.getSbn().getPackageName(), r2.getKey()));
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
                UserHandle.USER_SYSTEM, r.getSbn().getPackageName(), r.getKey()));
        assertTrue(mSnoozeHelper.isSnoozed(
                UserHandle.USER_SYSTEM, r2.getSbn().getPackageName(), r2.getKey()));
        assertTrue(mSnoozeHelper.isSnoozed(
                UserHandle.USER_ALL, r3.getSbn().getPackageName(), r3.getKey()));

        mSnoozeHelper.cancel(UserHandle.USER_SYSTEM, false);
        // 3 = once for each snooze above (3), only.
        verify(mAm, times(3)).cancel(any(PendingIntent.class));
        assertTrue(mSnoozeHelper.isSnoozed(
                UserHandle.USER_SYSTEM, r.getSbn().getPackageName(), r.getKey()));
        assertTrue(mSnoozeHelper.isSnoozed(
                UserHandle.USER_SYSTEM, r2.getSbn().getPackageName(), r2.getKey()));
        assertTrue(mSnoozeHelper.isSnoozed(
                UserHandle.USER_ALL, r3.getSbn().getPackageName(), r3.getKey()));
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
                UserHandle.USER_SYSTEM, r.getSbn().getPackageName(), r.getKey()));
        assertTrue(mSnoozeHelper.isSnoozed(
                UserHandle.USER_SYSTEM, r2.getSbn().getPackageName(), r2.getKey()));
        assertTrue(mSnoozeHelper.isSnoozed(
                UserHandle.USER_SYSTEM, r3.getSbn().getPackageName(), r3.getKey()));

        mSnoozeHelper.cancel(UserHandle.USER_SYSTEM, "pkg2");
        // 3 = once for each snooze above (3), only.
        verify(mAm, times(3)).cancel(any(PendingIntent.class));
        assertTrue(mSnoozeHelper.isSnoozed(
                UserHandle.USER_SYSTEM, r.getSbn().getPackageName(), r.getKey()));
        assertTrue(mSnoozeHelper.isSnoozed(
                UserHandle.USER_SYSTEM, r2.getSbn().getPackageName(), r2.getKey()));
        assertTrue(mSnoozeHelper.isSnoozed(
                UserHandle.USER_SYSTEM, r3.getSbn().getPackageName(), r3.getKey()));
    }

    @Test
    public void testCancelDoesNotUnsnooze() throws Exception {
        NotificationRecord r = getNotificationRecord("pkg", 1, "one", UserHandle.SYSTEM);
        mSnoozeHelper.snooze(r, 1000);
        assertTrue(mSnoozeHelper.isSnoozed(
                UserHandle.USER_SYSTEM, r.getSbn().getPackageName(), r.getKey()));

        mSnoozeHelper.cancel(UserHandle.USER_SYSTEM, r.getSbn().getPackageName(), "one", 1);

        assertTrue(mSnoozeHelper.isSnoozed(
                UserHandle.USER_SYSTEM, r.getSbn().getPackageName(), r.getKey()));
    }

    @Test
    public void testCancelDoesNotRepost() throws Exception {
        NotificationRecord r = getNotificationRecord("pkg", 1, "one", UserHandle.SYSTEM);
        NotificationRecord r2 = getNotificationRecord("pkg", 2, "two", UserHandle.SYSTEM);
        mSnoozeHelper.snooze(r, 1000);
        mSnoozeHelper.snooze(r2 , 1000);
        assertTrue(mSnoozeHelper.isSnoozed(
                UserHandle.USER_SYSTEM, r.getSbn().getPackageName(), r.getKey()));
        assertTrue(mSnoozeHelper.isSnoozed(
                UserHandle.USER_SYSTEM, r2.getSbn().getPackageName(), r2.getKey()));

        mSnoozeHelper.cancel(UserHandle.USER_SYSTEM, r.getSbn().getPackageName(), "one", 1);

        mSnoozeHelper.repost(r.getKey(), UserHandle.USER_SYSTEM, false);
        verify(mCallback, never()).repost(UserHandle.USER_SYSTEM, r, false);
    }

    @Test
    public void testRepost() throws Exception {
        NotificationRecord r = getNotificationRecord("pkg", 1, "one", UserHandle.SYSTEM);
        mSnoozeHelper.snooze(r, 1000);
        NotificationRecord r2 = getNotificationRecord("pkg", 2, "one", UserHandle.ALL);
        mSnoozeHelper.snooze(r2, 1000);
        reset(mAm);
        mSnoozeHelper.repost(r.getKey(), UserHandle.USER_SYSTEM, false);
        verify(mCallback, times(1)).repost(UserHandle.USER_SYSTEM, r, false);
        ArgumentCaptor<PendingIntent> captor = ArgumentCaptor.forClass(PendingIntent.class);
        verify(mAm).cancel(captor.capture());
        assertEquals(r.getKey(), captor.getValue().getIntent().getStringExtra(EXTRA_KEY));
    }

    @Test
    public void testRepost_noUser() throws Exception {
        NotificationRecord r = getNotificationRecord("pkg", 1, "one", UserHandle.SYSTEM);
        mSnoozeHelper.snooze(r, 1000);
        NotificationRecord r2 = getNotificationRecord("pkg", 2, "one", UserHandle.ALL);
        mSnoozeHelper.snooze(r2, 1000);
        reset(mAm);
        mSnoozeHelper.repost(r.getKey(), false);
        verify(mCallback, times(1)).repost(UserHandle.USER_SYSTEM, r, false);
        verify(mAm).cancel(any(PendingIntent.class));
    }

    @Test
    public void testUpdate() throws Exception {
        NotificationRecord r = getNotificationRecord("pkg", 1, "one", UserHandle.SYSTEM);
        mSnoozeHelper.snooze(r , 1000);
        r.getNotification().category = "NEW CATEGORY";

        mSnoozeHelper.update(UserHandle.USER_SYSTEM, r);
        verify(mCallback, never()).repost(anyInt(), any(NotificationRecord.class), anyBoolean());

        mSnoozeHelper.repost(r.getKey(), UserHandle.USER_SYSTEM, false);
        verify(mCallback, times(1)).repost(UserHandle.USER_SYSTEM, r, false);
    }

    @Test
    public void testUpdateAfterCancel() throws Exception {
        // snooze a notification
        NotificationRecord r = getNotificationRecord("pkg", 1, "one", UserHandle.SYSTEM);
        mSnoozeHelper.snooze(r , 1000);

        // cancel the notification
        mSnoozeHelper.cancel(UserHandle.USER_SYSTEM, false);

        // update the notification
        r = getNotificationRecord("pkg", 1, "one", UserHandle.SYSTEM);
        mSnoozeHelper.update(UserHandle.USER_SYSTEM, r);

        // verify callback is called when repost (snooze is expired)
        verify(mCallback, never()).repost(anyInt(), any(NotificationRecord.class), anyBoolean());
        mSnoozeHelper.repost(r.getKey(), UserHandle.USER_SYSTEM, false);
        verify(mCallback, times(1)).repost(UserHandle.USER_SYSTEM, r, false);
        assertFalse(r.isCanceled);
    }

    @Test
    public void testReport_passesFlag() throws Exception {
        // snooze a notification
        NotificationRecord r = getNotificationRecord("pkg", 1, "one", UserHandle.SYSTEM);
        mSnoozeHelper.snooze(r , 1000);

        mSnoozeHelper.repost(r.getKey(), UserHandle.USER_SYSTEM, true);
        verify(mCallback, times(1)).repost(UserHandle.USER_SYSTEM, r, true);
    }

    @Test
    public void testGetSnoozedBy() throws Exception {
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
    public void testGetSnoozedGroupNotifications() throws Exception {
        IntArray profileIds = new IntArray();
        profileIds.add(UserHandle.USER_CURRENT);
        when(mUserProfiles.getCurrentProfileIds()).thenReturn(profileIds);
        NotificationRecord r = getNotificationRecord("pkg", 1, "tag",
                UserHandle.CURRENT, "group", true);
        NotificationRecord r2 = getNotificationRecord("pkg", 2, "tag",
                UserHandle.CURRENT, "group", true);
        NotificationRecord r3 = getNotificationRecord("pkg2", 3, "tag",
                UserHandle.CURRENT, "group", true);
        NotificationRecord r4 = getNotificationRecord("pkg2", 4, "tag",
                UserHandle.CURRENT, "group", true);
        mSnoozeHelper.snooze(r, 1000);
        mSnoozeHelper.snooze(r2, 1000);
        mSnoozeHelper.snooze(r3, 1000);
        mSnoozeHelper.snooze(r4, 1000);

        assertEquals(2,
                mSnoozeHelper.getNotifications("pkg", "group", UserHandle.USER_CURRENT).size());
    }

    @Test
    public void testGetSnoozedGroupNotifications_nonGrouped() throws Exception {
        IntArray profileIds = new IntArray();
        profileIds.add(UserHandle.USER_CURRENT);
        when(mUserProfiles.getCurrentProfileIds()).thenReturn(profileIds);
        NotificationRecord r = getNotificationRecord("pkg", 1, "tag",
                UserHandle.CURRENT, "group", true);
        NotificationRecord r2 = getNotificationRecord("pkg", 2, "tag",
                UserHandle.CURRENT, null, true);
        mSnoozeHelper.snooze(r, 1000);
        mSnoozeHelper.snooze(r2, 1000);

        assertEquals(1,
                mSnoozeHelper.getNotifications("pkg", "group", UserHandle.USER_CURRENT).size());
        // and no NPE
    }

    @Test
    public void testGetSnoozedNotificationByKey() throws Exception {
        IntArray profileIds = new IntArray();
        profileIds.add(UserHandle.USER_CURRENT);
        when(mUserProfiles.getCurrentProfileIds()).thenReturn(profileIds);
        NotificationRecord r = getNotificationRecord("pkg", 1, "tag",
                UserHandle.CURRENT, "group", true);
        NotificationRecord r2 = getNotificationRecord("pkg", 2, "tag",
                UserHandle.CURRENT, "group", true);
        NotificationRecord r3 = getNotificationRecord("pkg2", 3, "tag",
                UserHandle.CURRENT, "group", true);
        NotificationRecord r4 = getNotificationRecord("pkg2", 4, "tag",
                UserHandle.CURRENT, "group", true);
        mSnoozeHelper.snooze(r, 1000);
        mSnoozeHelper.snooze(r2, 1000);
        mSnoozeHelper.snooze(r3, 1000);
        mSnoozeHelper.snooze(r4, 1000);

        assertEquals(r, mSnoozeHelper.getNotification(r.getKey()));
    }

    @Test
    public void testGetUnSnoozedNotificationByKey() throws Exception {
        IntArray profileIds = new IntArray();
        profileIds.add(UserHandle.USER_CURRENT);
        when(mUserProfiles.getCurrentProfileIds()).thenReturn(profileIds);
        NotificationRecord r = getNotificationRecord("pkg", 1, "tag",
                UserHandle.CURRENT, "group", true);
        NotificationRecord r2 = getNotificationRecord("pkg", 2, "tag",
                UserHandle.CURRENT, "group", true);
        mSnoozeHelper.snooze(r2, 1000);

        assertEquals(null, mSnoozeHelper.getNotification(r.getKey()));
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

        verify(mCallback, never()).repost(eq(UserHandle.USER_SYSTEM), eq(r), anyBoolean());
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

        verify(mCallback, times(1)).repost(UserHandle.USER_SYSTEM, r, false);
        verify(mCallback, never()).repost(UserHandle.USER_SYSTEM, r2, false);

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
                UserHandle.USER_SYSTEM, r.getSbn().getPackageName(), r.getKey()));
        assertTrue(mSnoozeHelper.isSnoozed(
                UserHandle.USER_SYSTEM, r2.getSbn().getPackageName(), r2.getKey()));

        // clear data
        mSnoozeHelper.clearData(UserHandle.USER_SYSTEM, "pkg");

        // nothing snoozed; alarms canceled
        assertFalse(mSnoozeHelper.isSnoozed(
                UserHandle.USER_SYSTEM, r.getSbn().getPackageName(), r.getKey()));
        assertFalse(mSnoozeHelper.isSnoozed(
                UserHandle.USER_SYSTEM, r2.getSbn().getPackageName(), r2.getKey()));
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
                UserHandle.USER_SYSTEM, r.getSbn().getPackageName(), r.getKey()));
        assertTrue(mSnoozeHelper.isSnoozed(
                UserHandle.USER_ALL, r2.getSbn().getPackageName(), r2.getKey()));
        assertTrue(mSnoozeHelper.isSnoozed(
                UserHandle.USER_SYSTEM, r3.getSbn().getPackageName(), r3.getKey()));

        // clear data
        mSnoozeHelper.clearData(UserHandle.USER_SYSTEM, "pkg");

        assertFalse(mSnoozeHelper.isSnoozed(
                UserHandle.USER_SYSTEM, r.getSbn().getPackageName(), r.getKey()));
        assertTrue(mSnoozeHelper.isSnoozed(
                UserHandle.USER_ALL, r2.getSbn().getPackageName(), r2.getKey()));
        assertTrue(mSnoozeHelper.isSnoozed(
                UserHandle.USER_SYSTEM, r3.getSbn().getPackageName(), r3.getKey()));
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
