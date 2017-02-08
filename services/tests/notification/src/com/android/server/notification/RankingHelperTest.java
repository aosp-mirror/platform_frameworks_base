/*
 * Copyright (C) 2014 The Android Open Source Project
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

import static android.app.NotificationManager.IMPORTANCE_LOW;

import static junit.framework.Assert.assertNull;
import static junit.framework.Assert.fail;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import com.android.internal.util.FastXmlSerializer;

import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlSerializer;

import android.app.Notification;
import android.app.NotificationChannelGroup;
import android.content.Context;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.media.AudioAttributes;
import android.net.Uri;
import android.os.Build;
import android.os.UserHandle;
import android.service.notification.StatusBarNotification;
import android.support.test.InstrumentationRegistry;
import android.support.test.runner.AndroidJUnit4;
import android.test.suitebuilder.annotation.SmallTest;
import android.util.Xml;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Matchers.anyInt;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.when;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class RankingHelperTest {
    @Mock
    NotificationUsageStats mUsageStats;
    @Mock
    RankingHandler handler;
    @Mock
    PackageManager mPm;

    private Notification mNotiGroupGSortA;
    private Notification mNotiGroupGSortB;
    private Notification mNotiNoGroup;
    private Notification mNotiNoGroup2;
    private Notification mNotiNoGroupSortA;
    private NotificationRecord mRecordGroupGSortA;
    private NotificationRecord mRecordGroupGSortB;
    private NotificationRecord mRecordNoGroup;
    private NotificationRecord mRecordNoGroup2;
    private NotificationRecord mRecordNoGroupSortA;
    private RankingHelper mHelper;
    private final String pkg = "com.android.server.notification";
    private final int uid = 0;
    private final String pkg2 = "pkg2";
    private final int uid2 = 1111111;
    private AudioAttributes mAudioAttributes;

    private Context getContext() {
        return InstrumentationRegistry.getTargetContext();
    }

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        UserHandle user = UserHandle.ALL;

        mHelper = new RankingHelper(getContext(), mPm, handler, mUsageStats,
                new String[]{ImportanceExtractor.class.getName()});

        mNotiGroupGSortA = new Notification.Builder(getContext())
                .setContentTitle("A")
                .setGroup("G")
                .setSortKey("A")
                .setWhen(1205)
                .build();
        mRecordGroupGSortA = new NotificationRecord(getContext(), new StatusBarNotification(
                "package", "package", 1, null, 0, 0, mNotiGroupGSortA, user,
                null, System.currentTimeMillis()), getDefaultChannel());

        mNotiGroupGSortB = new Notification.Builder(getContext())
                .setContentTitle("B")
                .setGroup("G")
                .setSortKey("B")
                .setWhen(1200)
                .build();
        mRecordGroupGSortB = new NotificationRecord(getContext(), new StatusBarNotification(
                "package", "package", 1, null, 0, 0, mNotiGroupGSortB, user,
                null, System.currentTimeMillis()), getDefaultChannel());

        mNotiNoGroup = new Notification.Builder(getContext())
                .setContentTitle("C")
                .setWhen(1201)
                .build();
        mRecordNoGroup = new NotificationRecord(getContext(), new StatusBarNotification(
                "package", "package", 1, null, 0, 0, mNotiNoGroup, user,
                null, System.currentTimeMillis()), getDefaultChannel());

        mNotiNoGroup2 = new Notification.Builder(getContext())
                .setContentTitle("D")
                .setWhen(1202)
                .build();
        mRecordNoGroup2 = new NotificationRecord(getContext(), new StatusBarNotification(
                "package", "package", 1, null, 0, 0, mNotiNoGroup2, user,
                null, System.currentTimeMillis()), getDefaultChannel());

        mNotiNoGroupSortA = new Notification.Builder(getContext())
                .setContentTitle("E")
                .setWhen(1201)
                .setSortKey("A")
                .build();
        mRecordNoGroupSortA = new NotificationRecord(getContext(), new StatusBarNotification(
                "package", "package", 1, null, 0, 0, mNotiNoGroupSortA, user,
                null, System.currentTimeMillis()), getDefaultChannel());

        mAudioAttributes = new AudioAttributes.Builder()
                .setContentType(AudioAttributes.CONTENT_TYPE_UNKNOWN)
                .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                .setFlags(AudioAttributes.FLAG_AUDIBILITY_ENFORCED)
                .build();

        final ApplicationInfo legacy = new ApplicationInfo();
        legacy.targetSdkVersion = Build.VERSION_CODES.N_MR1;
        final ApplicationInfo upgrade = new ApplicationInfo();
        upgrade.targetSdkVersion = Build.VERSION_CODES.N_MR1 + 1;
        try {
            when(mPm.getApplicationInfoAsUser(eq(pkg), anyInt(), anyInt())).thenReturn(legacy);
            when(mPm.getApplicationInfoAsUser(eq(pkg2), anyInt(), anyInt())).thenReturn(upgrade);
        } catch (PackageManager.NameNotFoundException e) {
        }
    }

    private NotificationChannel getDefaultChannel() {
        return new NotificationChannel(NotificationChannel.DEFAULT_CHANNEL_ID, "name",
                IMPORTANCE_LOW);
    }

    private ByteArrayOutputStream writeXmlAndPurge(String pkg, int uid, String... channelIds)
            throws Exception {
        XmlSerializer serializer = new FastXmlSerializer();
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        serializer.setOutput(new BufferedOutputStream(baos), "utf-8");
        serializer.startDocument(null, true);
        serializer.startTag(null, "ranking");
        mHelper.writeXml(serializer, false);
        serializer.endTag(null, "ranking");
        serializer.endDocument();
        serializer.flush();

        for (String channelId : channelIds) {
            mHelper.permanentlyDeleteNotificationChannel(pkg, uid, channelId);
        }
        return baos;
    }

    private void compareChannels(NotificationChannel expected, NotificationChannel actual) {
        assertEquals(expected.getId(), actual.getId());
        assertEquals(expected.getName(), actual.getName());
        assertEquals(expected.shouldVibrate(), actual.shouldVibrate());
        assertEquals(expected.shouldShowLights(), actual.shouldShowLights());
        assertEquals(expected.getImportance(), actual.getImportance());
        assertEquals(expected.getLockscreenVisibility(), actual.getLockscreenVisibility());
        assertEquals(expected.getSound(), actual.getSound());
        assertEquals(expected.canBypassDnd(), actual.canBypassDnd());
        assertTrue(Arrays.equals(expected.getVibrationPattern(), actual.getVibrationPattern()));
        assertEquals(expected.getGroup(), actual.getGroup());
        assertEquals(expected.getAudioAttributes(), actual.getAudioAttributes());
        assertEquals(expected.getLightColor(), actual.getLightColor());
    }

    @Test
    public void testFindAfterRankingWithASplitGroup() throws Exception {
        ArrayList<NotificationRecord> notificationList = new ArrayList<NotificationRecord>(3);
        notificationList.add(mRecordGroupGSortA);
        notificationList.add(mRecordGroupGSortB);
        notificationList.add(mRecordNoGroup);
        notificationList.add(mRecordNoGroupSortA);
        mHelper.sort(notificationList);
        assertTrue(mHelper.indexOf(notificationList, mRecordGroupGSortA) >= 0);
        assertTrue(mHelper.indexOf(notificationList, mRecordGroupGSortB) >= 0);
        assertTrue(mHelper.indexOf(notificationList, mRecordNoGroup) >= 0);
        assertTrue(mHelper.indexOf(notificationList, mRecordNoGroupSortA) >= 0);
    }

    @Test
    public void testSortShouldNotThrowWithPlainNotifications() throws Exception {
        ArrayList<NotificationRecord> notificationList = new ArrayList<NotificationRecord>(2);
        notificationList.add(mRecordNoGroup);
        notificationList.add(mRecordNoGroup2);
        mHelper.sort(notificationList);
    }

    @Test
    public void testSortShouldNotThrowOneSorted() throws Exception {
        ArrayList<NotificationRecord> notificationList = new ArrayList<NotificationRecord>(2);
        notificationList.add(mRecordNoGroup);
        notificationList.add(mRecordNoGroupSortA);
        mHelper.sort(notificationList);
    }

    @Test
    public void testSortShouldNotThrowOneNotification() throws Exception {
        ArrayList<NotificationRecord> notificationList = new ArrayList<NotificationRecord>(1);
        notificationList.add(mRecordNoGroup);
        mHelper.sort(notificationList);
    }

    @Test
    public void testSortShouldNotThrowOneSortKey() throws Exception {
        ArrayList<NotificationRecord> notificationList = new ArrayList<NotificationRecord>(1);
        notificationList.add(mRecordGroupGSortB);
        mHelper.sort(notificationList);
    }

    @Test
    public void testSortShouldNotThrowOnEmptyList() throws Exception {
        ArrayList<NotificationRecord> notificationList = new ArrayList<NotificationRecord>();
        mHelper.sort(notificationList);
    }

    @Test
    public void testChannelXml() throws Exception {
        NotificationChannelGroup ncg = new NotificationChannelGroup("1", "2");
        NotificationChannel channel1 =
                new NotificationChannel("id1", "name1", NotificationManager.IMPORTANCE_HIGH);
        NotificationChannel channel2 =
                new NotificationChannel("id2", "name2", IMPORTANCE_LOW);
        channel2.setSound(new Uri.Builder().scheme("test").build(), mAudioAttributes);
        channel2.enableLights(true);
        channel2.setBypassDnd(true);
        channel2.setLockscreenVisibility(Notification.VISIBILITY_SECRET);
        channel2.enableVibration(true);
        channel2.setGroup(ncg.getId());
        channel2.setVibrationPattern(new long[]{100, 67, 145, 156});
        channel2.setLightColor(Color.BLUE);

        mHelper.createNotificationChannelGroup(pkg, uid, ncg, true);
        mHelper.createNotificationChannel(pkg, uid, channel1, true);
        mHelper.createNotificationChannel(pkg, uid, channel2, false);

        mHelper.setShowBadge(pkg, uid, true);
        mHelper.setShowBadge(pkg2, uid2, false);

        ByteArrayOutputStream baos = writeXmlAndPurge(pkg, uid, channel1.getId(), channel2.getId(),
                NotificationChannel.DEFAULT_CHANNEL_ID);
        mHelper.onPackagesChanged(true, UserHandle.myUserId(), new String[]{pkg}, new int[]{uid});

        XmlPullParser parser = Xml.newPullParser();
        parser.setInput(new BufferedInputStream(new ByteArrayInputStream(baos.toByteArray())),
                null);
        parser.nextTag();
        mHelper.readXml(parser, false);

        assertFalse(mHelper.canShowBadge(pkg2, uid2));
        assertTrue(mHelper.canShowBadge(pkg, uid));
        assertEquals(channel1, mHelper.getNotificationChannel(pkg, uid, channel1.getId(), false));
        compareChannels(channel2,
                mHelper.getNotificationChannel(pkg, uid, channel2.getId(), false));
        assertNotNull(mHelper.getNotificationChannel(
                pkg, uid, NotificationChannel.DEFAULT_CHANNEL_ID, false));

        List<NotificationChannelGroup> actualGroups =
                mHelper.getNotificationChannelGroups(pkg, uid, false).getList();
        boolean foundNcg = false;
        for (NotificationChannelGroup actual : actualGroups) {
            if (ncg.getId().equals(actual.getId())) {
                foundNcg = true;
                 break;
            }
        }
        assertTrue(foundNcg);

        boolean foundChannel2Group = false;
        for (NotificationChannelGroup actual : actualGroups) {
            if (channel2.getGroup().equals(actual.getChannels().get(0).getGroup())) {
                foundChannel2Group = true;
                break;
            }
        }
        assertTrue(foundChannel2Group);
    }

    @Test
    public void testChannelXml_defaultChannelLegacyApp_noUserSettings() throws Exception {
        NotificationChannel channel1 =
                new NotificationChannel("id1", "name1", NotificationManager.IMPORTANCE_DEFAULT);

        mHelper.createNotificationChannel(pkg, uid, channel1, true);

        ByteArrayOutputStream baos = writeXmlAndPurge(pkg, uid, channel1.getId(),
                NotificationChannel.DEFAULT_CHANNEL_ID);

        XmlPullParser parser = Xml.newPullParser();
        parser.setInput(new BufferedInputStream(new ByteArrayInputStream(baos.toByteArray())),
                null);
        parser.nextTag();
        mHelper.readXml(parser, false);

        final NotificationChannel updated = mHelper.getNotificationChannel(
                pkg, uid, NotificationChannel.DEFAULT_CHANNEL_ID, false);
        assertEquals(NotificationManager.IMPORTANCE_UNSPECIFIED, updated.getImportance());
        assertFalse(updated.canBypassDnd());
        assertEquals(NotificationManager.VISIBILITY_NO_OVERRIDE, updated.getLockscreenVisibility());
        assertEquals(0, updated.getUserLockedFields());
    }

    @Test
    public void testChannelXml_defaultChannelUpdatedApp_userSettings() throws Exception {
        NotificationChannel channel1 =
                new NotificationChannel("id1", "name1", NotificationManager.IMPORTANCE_MIN);
        mHelper.createNotificationChannel(pkg, uid, channel1, true);

        final NotificationChannel defaultChannel = mHelper.getNotificationChannel(
                pkg, uid, NotificationChannel.DEFAULT_CHANNEL_ID, false);
        defaultChannel.setImportance(IMPORTANCE_LOW);
        mHelper.updateNotificationChannel(pkg, uid, defaultChannel);

        ByteArrayOutputStream baos = writeXmlAndPurge(pkg, uid, channel1.getId(),
                NotificationChannel.DEFAULT_CHANNEL_ID);

        XmlPullParser parser = Xml.newPullParser();
        parser.setInput(new BufferedInputStream(new ByteArrayInputStream(baos.toByteArray())),
                null);
        parser.nextTag();
        mHelper.readXml(parser, false);

        assertEquals(IMPORTANCE_LOW, mHelper.getNotificationChannel(
                pkg, uid, NotificationChannel.DEFAULT_CHANNEL_ID, false).getImportance());
    }

    @Test
    public void testChannelXml_upgradeCreateDefaultChannel() throws Exception {
        final String preupgradeXml = "<ranking version=\"1\">\n"
                + "<package name=\"" + pkg + "\" importance=\""
                + NotificationManager.IMPORTANCE_HIGH
                + "\" priority=\"" + Notification.PRIORITY_MAX + "\" visibility=\""
                + Notification.VISIBILITY_SECRET + "\"" + " uid=\"" + uid + "\" />\n"
                + "<package name=\"" + pkg2 + "\" uid=\"" + uid2 + "\" visibility=\""
                + Notification.VISIBILITY_PRIVATE + "\" />\n"
                + "</ranking>";
        XmlPullParser parser = Xml.newPullParser();
        parser.setInput(new BufferedInputStream(new ByteArrayInputStream(preupgradeXml.getBytes())),
                null);
        parser.nextTag();
        mHelper.readXml(parser, false);

        final NotificationChannel updated1 = mHelper.getNotificationChannel(
                pkg, uid, NotificationChannel.DEFAULT_CHANNEL_ID, false);
        assertEquals(NotificationManager.IMPORTANCE_HIGH, updated1.getImportance());
        assertTrue(updated1.canBypassDnd());
        assertEquals(Notification.VISIBILITY_SECRET, updated1.getLockscreenVisibility());
        assertEquals(NotificationChannel.USER_LOCKED_IMPORTANCE
                | NotificationChannel.USER_LOCKED_PRIORITY
                | NotificationChannel.USER_LOCKED_VISIBILITY, updated1.getUserLockedFields());

        final NotificationChannel updated2 = mHelper.getNotificationChannel(
                pkg2, uid2, NotificationChannel.DEFAULT_CHANNEL_ID, false);
        // clamped
        assertEquals(IMPORTANCE_LOW, updated2.getImportance());
        assertFalse(updated2.canBypassDnd());
        assertEquals(Notification.VISIBILITY_PRIVATE, updated2.getLockscreenVisibility());
        assertEquals(NotificationChannel.USER_LOCKED_VISIBILITY, updated2.getUserLockedFields());
    }

    @Test
    public void testCreateChannel_blocked() throws Exception {
        mHelper.setImportance(pkg, uid, NotificationManager.IMPORTANCE_NONE);

        try {
            mHelper.createNotificationChannel(pkg, uid,
                    new NotificationChannel(pkg, "", IMPORTANCE_LOW), true);
            fail("Channel creation should fail");
        } catch (IllegalArgumentException e) {
            // pass
        }
    }

    @Test
    public void testUpdate_userLockedImportance() throws Exception {
        // all fields locked by user
        final NotificationChannel channel =
                new NotificationChannel("id2", "name2", IMPORTANCE_LOW);
        channel.lockFields(NotificationChannel.USER_LOCKED_IMPORTANCE);

        mHelper.createNotificationChannel(pkg, uid, channel, false);

        // same id, try to update
        final NotificationChannel channel2 =
                new NotificationChannel("id2", "name2", NotificationManager.IMPORTANCE_HIGH);

        mHelper.updateNotificationChannelFromAssistant(pkg, uid, channel2);

        // no fields should be changed
        assertEquals(channel, mHelper.getNotificationChannel(pkg, uid, channel.getId(), false));
    }

    @Test
    public void testUpdate_userLockedVisibility() throws Exception {
        // all fields locked by user
        final NotificationChannel channel =
                new NotificationChannel("id2", "name2", IMPORTANCE_LOW);
        channel.setLockscreenVisibility(Notification.VISIBILITY_SECRET);
        channel.lockFields(NotificationChannel.USER_LOCKED_VISIBILITY);

        mHelper.createNotificationChannel(pkg, uid, channel, false);

        // same id, try to update
        final NotificationChannel channel2 =
                new NotificationChannel("id2", "name2", NotificationManager.IMPORTANCE_HIGH);
        channel2.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);

        mHelper.updateNotificationChannelFromAssistant(pkg, uid, channel2);

        // no fields should be changed
        assertEquals(channel, mHelper.getNotificationChannel(pkg, uid, channel.getId(), false));
    }

    @Test
    public void testUpdate_userLockedVibration() throws Exception {
        // all fields locked by user
        final NotificationChannel channel =
                new NotificationChannel("id2", "name2", IMPORTANCE_LOW);
        channel.enableLights(false);
        channel.lockFields(NotificationChannel.USER_LOCKED_VIBRATION);

        mHelper.createNotificationChannel(pkg, uid, channel, false);

        // same id, try to update
        final NotificationChannel channel2 =
                new NotificationChannel("id2", "name2", NotificationManager.IMPORTANCE_HIGH);
        channel2.enableVibration(true);
        channel2.setVibrationPattern(new long[]{100});

        mHelper.updateNotificationChannelFromAssistant(pkg, uid, channel2);

        // no fields should be changed
        assertEquals(channel, mHelper.getNotificationChannel(pkg, uid, channel.getId(), false));
    }

    @Test
    public void testUpdate_userLockedLights() throws Exception {
        // all fields locked by user
        final NotificationChannel channel =
                new NotificationChannel("id2", "name2", IMPORTANCE_LOW);
        channel.enableLights(false);
        channel.lockFields(NotificationChannel.USER_LOCKED_LIGHTS);

        mHelper.createNotificationChannel(pkg, uid, channel, false);

        // same id, try to update
        final NotificationChannel channel2 =
                new NotificationChannel("id2", "name2", NotificationManager.IMPORTANCE_HIGH);
        channel2.enableLights(true);

        mHelper.updateNotificationChannelFromAssistant(pkg, uid, channel2);

        // no fields should be changed
        assertEquals(channel, mHelper.getNotificationChannel(pkg, uid, channel.getId(), false));
    }

    @Test
    public void testUpdate_userLockedPriority() throws Exception {
        // all fields locked by user
        final NotificationChannel channel =
                new NotificationChannel("id2", "name2", IMPORTANCE_LOW);
        channel.setBypassDnd(true);
        channel.lockFields(NotificationChannel.USER_LOCKED_PRIORITY);

        mHelper.createNotificationChannel(pkg, uid, channel, false);

        // same id, try to update all fields
        final NotificationChannel channel2 =
                new NotificationChannel("id2", "name2", NotificationManager.IMPORTANCE_HIGH);
        channel2.setBypassDnd(false);

        mHelper.updateNotificationChannelFromAssistant(pkg, uid, channel2);

        // no fields should be changed
        assertEquals(channel, mHelper.getNotificationChannel(pkg, uid, channel.getId(), false));
    }

    @Test
    public void testUpdate_userLockedRingtone() throws Exception {
        // all fields locked by user
        final NotificationChannel channel =
                new NotificationChannel("id2", "name2", IMPORTANCE_LOW);
        channel.setSound(new Uri.Builder().scheme("test").build(), mAudioAttributes);
        channel.lockFields(NotificationChannel.USER_LOCKED_SOUND);

        mHelper.createNotificationChannel(pkg, uid, channel, false);

        // same id, try to update all fields
        final NotificationChannel channel2 =
                new NotificationChannel("id2", "name2", NotificationManager.IMPORTANCE_HIGH);
        channel2.setSound(new Uri.Builder().scheme("test2").build(), mAudioAttributes);

        mHelper.updateNotificationChannelFromAssistant(pkg, uid, channel2);

        // no fields should be changed
        assertEquals(channel, mHelper.getNotificationChannel(pkg, uid, channel.getId(), false));
    }

    @Test
    public void testUpdate_userLockedBadge() throws Exception {
        final NotificationChannel channel =
                new NotificationChannel("id2", "name2", IMPORTANCE_LOW);
        channel.setShowBadge(true);
        channel.lockFields(NotificationChannel.USER_LOCKED_SHOW_BADGE);

        mHelper.createNotificationChannel(pkg, uid, channel, false);

        final NotificationChannel channel2 =
                new NotificationChannel("id2", "name2", NotificationManager.IMPORTANCE_HIGH);
        channel2.setShowBadge(false);

        mHelper.updateNotificationChannelFromAssistant(pkg, uid, channel2);

        // no fields should be changed
        assertEquals(channel, mHelper.getNotificationChannel(pkg, uid, channel.getId(), false));
    }

    @Test
    public void testUpdate() throws Exception {
        // no fields locked by user
        final NotificationChannel channel =
                new NotificationChannel("id2", "name2", IMPORTANCE_LOW);
        channel.setSound(new Uri.Builder().scheme("test").build(), mAudioAttributes);
        channel.enableLights(true);
        channel.setBypassDnd(true);
        channel.setLockscreenVisibility(Notification.VISIBILITY_SECRET);

        mHelper.createNotificationChannel(pkg, uid, channel, false);

        // same id, try to update all fields
        final NotificationChannel channel2 =
                new NotificationChannel("id2", "name2", NotificationManager.IMPORTANCE_HIGH);
        channel2.setSound(new Uri.Builder().scheme("test2").build(), mAudioAttributes);
        channel2.enableLights(false);
        channel2.setBypassDnd(false);
        channel2.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);

        mHelper.updateNotificationChannel(pkg, uid, channel2);

        // all fields should be changed
        assertEquals(channel2, mHelper.getNotificationChannel(pkg, uid, channel.getId(), false));
    }

    @Test
    public void testGetChannelWithFallback() throws Exception {
        NotificationChannel channel =
                mHelper.getNotificationChannelWithFallback(pkg, uid, "garbage", false);
        assertEquals(NotificationChannel.DEFAULT_CHANNEL_ID, channel.getId());
    }

    @Test
    public void testCreateChannel_CannotChangeHiddenFields() throws Exception {
        final NotificationChannel channel =
                new NotificationChannel("id2", "name2", IMPORTANCE_LOW);
        channel.setSound(new Uri.Builder().scheme("test").build(), mAudioAttributes);
        channel.enableLights(true);
        channel.setBypassDnd(true);
        channel.setLockscreenVisibility(Notification.VISIBILITY_SECRET);
        channel.setShowBadge(true);
        int lockMask = 0;
        for (int i = 0; i < NotificationChannel.LOCKABLE_FIELDS.length; i++) {
            lockMask |= NotificationChannel.LOCKABLE_FIELDS[i];
        }
        channel.lockFields(lockMask);

        mHelper.createNotificationChannel(pkg, uid, channel, true);

        NotificationChannel savedChannel =
                mHelper.getNotificationChannel(pkg, uid, channel.getId(), false);

        assertEquals(channel.getName(), savedChannel.getName());
        assertEquals(channel.shouldShowLights(), savedChannel.shouldShowLights());
        assertFalse(savedChannel.canBypassDnd());
        assertFalse(Notification.VISIBILITY_SECRET == savedChannel.getLockscreenVisibility());
        assertEquals(channel.canShowBadge(), savedChannel.canShowBadge());
    }

    @Test
    public void testCreateChannel_CannotChangeHiddenFieldsAssistant() throws Exception {
        final NotificationChannel channel =
                new NotificationChannel("id2", "name2", IMPORTANCE_LOW);
        channel.setSound(new Uri.Builder().scheme("test").build(), mAudioAttributes);
        channel.enableLights(true);
        channel.setBypassDnd(true);
        channel.setLockscreenVisibility(Notification.VISIBILITY_SECRET);
        channel.setShowBadge(true);
        int lockMask = 0;
        for (int i = 0; i < NotificationChannel.LOCKABLE_FIELDS.length; i++) {
            lockMask |= NotificationChannel.LOCKABLE_FIELDS[i];
        }
        channel.lockFields(lockMask);

        mHelper.createNotificationChannel(pkg, uid, channel, true);

        NotificationChannel savedChannel =
                mHelper.getNotificationChannel(pkg, uid, channel.getId(), false);

        assertEquals(channel.getName(), savedChannel.getName());
        assertEquals(channel.shouldShowLights(), savedChannel.shouldShowLights());
        assertFalse(savedChannel.canBypassDnd());
        assertFalse(Notification.VISIBILITY_SECRET == savedChannel.getLockscreenVisibility());
        assertEquals(channel.canShowBadge(), savedChannel.canShowBadge());
    }

    @Test
    public void testGetDeletedChannel() throws Exception {
        NotificationChannel channel =
                new NotificationChannel("id2", "name2", IMPORTANCE_LOW);
        channel.setSound(new Uri.Builder().scheme("test").build(), mAudioAttributes);
        channel.enableLights(true);
        channel.setBypassDnd(true);
        channel.setLockscreenVisibility(Notification.VISIBILITY_SECRET);
        channel.enableVibration(true);
        channel.setVibrationPattern(new long[]{100, 67, 145, 156});

        mHelper.createNotificationChannel(pkg, uid, channel, true);
        mHelper.deleteNotificationChannel(pkg, uid, channel.getId());

        // Does not return deleted channel
        NotificationChannel response =
                mHelper.getNotificationChannel(pkg, uid, channel.getId(), false);
        assertNull(response);

        // Returns deleted channel
        response = mHelper.getNotificationChannel(pkg, uid, channel.getId(), true);
        compareChannels(channel, response);
        assertTrue(response.isDeleted());
    }

    @Test
    public void testGetDeletedChannels() throws Exception {
        Map<String, NotificationChannel> channelMap = new HashMap<>();
        NotificationChannel channel =
                new NotificationChannel("id2", "name2", IMPORTANCE_LOW);
        channel.setSound(new Uri.Builder().scheme("test").build(), mAudioAttributes);
        channel.enableLights(true);
        channel.setBypassDnd(true);
        channel.setLockscreenVisibility(Notification.VISIBILITY_PRIVATE);
        channel.enableVibration(true);
        channel.setVibrationPattern(new long[]{100, 67, 145, 156});
        channelMap.put(channel.getId(), channel);
        NotificationChannel channel2 =
                new NotificationChannel("id4", "a", NotificationManager.IMPORTANCE_HIGH);
        channelMap.put(channel2.getId(), channel2);
        mHelper.createNotificationChannel(pkg, uid, channel, true);
        mHelper.createNotificationChannel(pkg, uid, channel2, true);

        mHelper.deleteNotificationChannel(pkg, uid, channel.getId());

        // Returns only non-deleted channels
        List<NotificationChannel> channels =
                mHelper.getNotificationChannels(pkg, uid, false).getList();
        assertEquals(2, channels.size());   // Default channel + non-deleted channel
        for (NotificationChannel nc : channels) {
            if (!NotificationChannel.DEFAULT_CHANNEL_ID.equals(nc.getId())) {
                compareChannels(channel2, nc);
            }
        }

        // Returns deleted channels too
        channels = mHelper.getNotificationChannels(pkg, uid, true).getList();
        assertEquals(3, channels.size());               // Includes default channel
        for (NotificationChannel nc : channels) {
            if (!NotificationChannel.DEFAULT_CHANNEL_ID.equals(nc.getId())) {
                compareChannels(channelMap.get(nc.getId()), nc);
            }
        }
    }

    @Test
    public void testUpdateDeletedChannels() throws Exception {
        NotificationChannel channel =
                new NotificationChannel("id2", "name2", IMPORTANCE_LOW);
        mHelper.createNotificationChannel(pkg, uid, channel, true);

        mHelper.deleteNotificationChannel(pkg, uid, channel.getId());

        channel.setSound(new Uri.Builder().scheme("test").build(), mAudioAttributes);
        try {
            mHelper.updateNotificationChannel(pkg, uid, channel);
            fail("Updated deleted channel");
        } catch (IllegalArgumentException e) {
            // :)
        }

        try {
            mHelper.updateNotificationChannelFromAssistant(pkg, uid, channel);
            fail("Updated deleted channel");
        } catch (IllegalArgumentException e) {
            // :)
        }
    }

    @Test
    public void testCreateDeletedChannel() throws Exception {
        long[] vibration = new long[]{100, 67, 145, 156};
        NotificationChannel channel =
                new NotificationChannel("id2", "name2", IMPORTANCE_LOW);
        channel.setVibrationPattern(vibration);

        mHelper.createNotificationChannel(pkg, uid, channel, true);
        mHelper.deleteNotificationChannel(pkg, uid, channel.getId());

        NotificationChannel newChannel = new NotificationChannel(
                channel.getId(), channel.getName(), NotificationManager.IMPORTANCE_HIGH);
        newChannel.setVibrationPattern(new long[]{100});

        mHelper.createNotificationChannel(pkg, uid, newChannel, true);

        // No long deleted, using old settings
        compareChannels(channel,
                mHelper.getNotificationChannel(pkg, uid, newChannel.getId(), false));
    }

    @Test
    public void testCreateChannel_alreadyExists() throws Exception {
        long[] vibration = new long[]{100, 67, 145, 156};
        NotificationChannel channel =
                new NotificationChannel("id2", "name2", IMPORTANCE_LOW);
        channel.setVibrationPattern(vibration);

        mHelper.createNotificationChannel(pkg, uid, channel, true);

        NotificationChannel newChannel = new NotificationChannel(
                channel.getId(), channel.getName(), NotificationManager.IMPORTANCE_HIGH);
        newChannel.setVibrationPattern(new long[]{100});

        mHelper.createNotificationChannel(pkg, uid, newChannel, true);

        // Old settings not overridden
        compareChannels(channel,
                mHelper.getNotificationChannel(pkg, uid, newChannel.getId(), false));
    }

    @Test
    public void testPermanentlyDeleteChannels() throws Exception {
        NotificationChannel channel1 =
                new NotificationChannel("id1", "name1", NotificationManager.IMPORTANCE_HIGH);
        NotificationChannel channel2 =
                new NotificationChannel("id2", "name2", IMPORTANCE_LOW);

        mHelper.createNotificationChannel(pkg, uid, channel1, true);
        mHelper.createNotificationChannel(pkg, uid, channel2, false);

        mHelper.permanentlyDeleteNotificationChannels(pkg, uid);

        // Only default channel remains
        assertEquals(1, mHelper.getNotificationChannels(pkg, uid, true).getList().size());
    }

    @Test
    public void testOnPackageChanged_packageRemoval() throws Exception {
        // Deleted
        NotificationChannel channel1 =
                new NotificationChannel("id1", "name1", NotificationManager.IMPORTANCE_HIGH);
        mHelper.createNotificationChannel(pkg, uid, channel1, true);

        mHelper.onPackagesChanged(true, UserHandle.USER_SYSTEM, new String[]{pkg}, new int[]{uid});

        assertEquals(0, mHelper.getNotificationChannels(pkg, uid, true).getList().size());

        // Not deleted
        mHelper.createNotificationChannel(pkg, uid, channel1, true);

        mHelper.onPackagesChanged(false, UserHandle.USER_SYSTEM, new String[]{pkg}, new int[]{uid});
        assertEquals(2, mHelper.getNotificationChannels(pkg, uid, false).getList().size());
    }

    @Test
    public void testOnPackageChanged_packageRemoval_importance() throws Exception {
        mHelper.setImportance(pkg, uid, NotificationManager.IMPORTANCE_HIGH);

        mHelper.onPackagesChanged(true, UserHandle.USER_SYSTEM, new String[]{pkg}, new int[]{uid});

        assertEquals(NotificationManager.IMPORTANCE_UNSPECIFIED, mHelper.getImportance(pkg, uid));
    }

    @Test
    public void testOnPackageChanged_packageRemoval_groups() throws Exception {
        NotificationChannelGroup ncg = new NotificationChannelGroup("group1", "name1");
        mHelper.createNotificationChannelGroup(pkg, uid, ncg, true);
        NotificationChannelGroup ncg2 = new NotificationChannelGroup("group2", "name2");
        mHelper.createNotificationChannelGroup(pkg, uid, ncg2, true);

        mHelper.onPackagesChanged(true, UserHandle.USER_SYSTEM, new String[]{pkg}, new int[]{uid});

        assertEquals(0, mHelper.getNotificationChannelGroups(pkg, uid, true).getList().size());
    }

    @Test
    public void testRecordDefaults() throws Exception {
        assertEquals(NotificationManager.IMPORTANCE_UNSPECIFIED, mHelper.getImportance(pkg, uid));
        assertEquals(true, mHelper.canShowBadge(pkg, uid));
        assertEquals(1, mHelper.getNotificationChannels(pkg, uid, false).getList().size());
    }

    @Test
    public void testCreateGroup() throws Exception {
        NotificationChannelGroup ncg = new NotificationChannelGroup("group1", "name1");
        mHelper.createNotificationChannelGroup(pkg, uid, ncg, true);
        assertEquals(ncg, mHelper.getNotificationChannelGroups(pkg, uid).iterator().next());
    }

    @Test
    public void testCannotCreateChannel_badGroup() throws Exception {
        NotificationChannel channel1 =
                new NotificationChannel("id1", "name1", NotificationManager.IMPORTANCE_HIGH);
        channel1.setGroup("garbage");
        try {
            mHelper.createNotificationChannel(pkg, uid, channel1, true);
            fail("Created a channel with a bad group");
        } catch (IllegalArgumentException e) {
        }
    }

    @Test
    public void testCannotCreateChannel_goodGroup() throws Exception {
        NotificationChannelGroup ncg = new NotificationChannelGroup("group1", "name1");
        mHelper.createNotificationChannelGroup(pkg, uid, ncg, true);
        NotificationChannel channel1 =
                new NotificationChannel("id1", "name1", NotificationManager.IMPORTANCE_HIGH);
        channel1.setGroup(ncg.getId());
        mHelper.createNotificationChannel(pkg, uid, channel1, true);

        assertEquals(ncg.getId(),
                mHelper.getNotificationChannel(pkg, uid, channel1.getId(), false).getGroup());
    }

    @Test
    public void testGetChannelGroups() throws Exception {
        NotificationChannelGroup unused = new NotificationChannelGroup("unused", "s");
        mHelper.createNotificationChannelGroup(pkg, uid, unused, true);
        NotificationChannelGroup ncg = new NotificationChannelGroup("group1", "name1");
        mHelper.createNotificationChannelGroup(pkg, uid, ncg, true);
        NotificationChannelGroup ncg2 = new NotificationChannelGroup("group2", "name2");
        mHelper.createNotificationChannelGroup(pkg, uid, ncg2, true);

        NotificationChannel channel1 =
                new NotificationChannel("id1", "name1", NotificationManager.IMPORTANCE_HIGH);
        channel1.setGroup(ncg.getId());
        mHelper.createNotificationChannel(pkg, uid, channel1, true);
        NotificationChannel channel1a =
                new NotificationChannel("id1a", "name1", NotificationManager.IMPORTANCE_HIGH);
        channel1a.setGroup(ncg.getId());
        mHelper.createNotificationChannel(pkg, uid, channel1a, true);

        NotificationChannel channel2 =
                new NotificationChannel("id2", "name1", NotificationManager.IMPORTANCE_HIGH);
        channel2.setGroup(ncg2.getId());
        mHelper.createNotificationChannel(pkg, uid, channel2, true);

        NotificationChannel channel3 =
                new NotificationChannel("id3", "name1", NotificationManager.IMPORTANCE_HIGH);
        mHelper.createNotificationChannel(pkg, uid, channel3, true);

        List<NotificationChannelGroup> actual =
                mHelper.getNotificationChannelGroups(pkg, uid, true).getList();
        assertEquals(3, actual.size());
        for (NotificationChannelGroup group : actual) {
            if (group.getId() == null) {
                assertEquals(2, group.getChannels().size()); // misc channel too
                assertTrue(channel3.getId().equals(group.getChannels().get(0).getId())
                        || channel3.getId().equals(group.getChannels().get(1).getId()));
            } else if (group.getId().equals(ncg.getId())) {
                assertEquals(2, group.getChannels().size());
                if (group.getChannels().get(0).getId().equals(channel1.getId())) {
                    assertTrue(group.getChannels().get(1).getId().equals(channel1a.getId()));
                } else if (group.getChannels().get(0).getId().equals(channel1a.getId())) {
                    assertTrue(group.getChannels().get(1).getId().equals(channel1.getId()));
                } else {
                    fail("expected channel not found");
                }
            } else if (group.getId().equals(ncg2.getId())) {
                assertEquals(1, group.getChannels().size());
                assertEquals(channel2.getId(), group.getChannels().get(0).getId());
            }
        }
    }

    @Test
    public void testGetChannelGroups_noSideEffects() throws Exception {
        NotificationChannelGroup ncg = new NotificationChannelGroup("group1", "name1");
        mHelper.createNotificationChannelGroup(pkg, uid, ncg, true);

        NotificationChannel channel1 =
                new NotificationChannel("id1", "name1", NotificationManager.IMPORTANCE_HIGH);
        channel1.setGroup(ncg.getId());
        mHelper.createNotificationChannel(pkg, uid, channel1, true);
        mHelper.getNotificationChannelGroups(pkg, uid, true).getList();

        channel1.setImportance(IMPORTANCE_LOW);
        mHelper.updateNotificationChannel(pkg, uid, channel1);

        List<NotificationChannelGroup> actual =
                mHelper.getNotificationChannelGroups(pkg, uid, true).getList();

        assertEquals(2, actual.size());
        for (NotificationChannelGroup group : actual) {
            if (Objects.equals(group.getId(),ncg.getId())) {
                assertEquals(1, group.getChannels().size());
            }
        }
    }
}
