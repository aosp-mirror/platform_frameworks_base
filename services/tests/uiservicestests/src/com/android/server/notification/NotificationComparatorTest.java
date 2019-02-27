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

import static org.hamcrest.Matchers.contains;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.mockito.Matchers.anyInt;
import static org.mockito.Matchers.anyString;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.when;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.media.session.MediaSession;
import android.os.Build;
import android.os.UserHandle;
import android.provider.Settings;
import android.service.notification.StatusBarNotification;
import android.telecom.TelecomManager;
import android.test.suitebuilder.annotation.SmallTest;

import androidx.test.runner.AndroidJUnit4;

import com.android.server.UiServiceTestCase;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class NotificationComparatorTest extends UiServiceTestCase {
    @Mock Context mContext;
    @Mock TelecomManager mTm;
    @Mock RankingHandler handler;
    @Mock PackageManager mPm;

    private final String callPkg = "com.android.server.notification";
    private final int callUid = 10;
    private String smsPkg;
    private final int smsUid = 11;
    private final String pkg2 = "pkg2";
    private final int uid2 = 1111111;
    private static final String TEST_CHANNEL_ID = "test_channel_id";

    private NotificationRecord mRecordMinCall;
    private NotificationRecord mRecordHighCall;
    private NotificationRecord mRecordDefaultMedia;
    private NotificationRecord mRecordEmail;
    private NotificationRecord mRecordInlineReply;
    private NotificationRecord mRecordSms;
    private NotificationRecord mRecordStarredContact;
    private NotificationRecord mRecordContact;
    private NotificationRecord mRecordUrgent;
    private NotificationRecord mRecordCheater;
    private NotificationRecord mRecordCheaterColorized;
    private NotificationRecord mNoMediaSessionMedia;
    private NotificationRecord mRecordColorized;
    private NotificationRecord mRecordColorizedCall;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        int userId = UserHandle.myUserId();

        when(mContext.getResources()).thenReturn(getContext().getResources());
        when(mContext.getContentResolver()).thenReturn(getContext().getContentResolver());
        when(mContext.getPackageManager()).thenReturn(mPm);
        when(mContext.getSystemService(eq(Context.TELECOM_SERVICE))).thenReturn(mTm);
        when(mTm.getDefaultDialerPackage()).thenReturn(callPkg);
        final ApplicationInfo legacy = new ApplicationInfo();
        legacy.targetSdkVersion = Build.VERSION_CODES.N_MR1;
        try {
            when(mPm.getApplicationInfoAsUser(anyString(), anyInt(), anyInt())).thenReturn(legacy);
            when(mContext.getApplicationInfo()).thenReturn(legacy);
        } catch (PackageManager.NameNotFoundException e) {
            // let's hope not
        }

        smsPkg = Settings.Secure.getString(mContext.getContentResolver(),
                Settings.Secure.SMS_DEFAULT_APPLICATION);

        Notification n1 = new Notification.Builder(mContext, TEST_CHANNEL_ID)
                .setCategory(Notification.CATEGORY_CALL)
                .setFlag(Notification.FLAG_FOREGROUND_SERVICE, true)
                .build();
        mRecordMinCall = new NotificationRecord(mContext, new StatusBarNotification(callPkg,
                callPkg, 1, "minCall", callUid, callUid, n1,
                new UserHandle(userId), "", 2000), getDefaultChannel());
        mRecordMinCall.setSystemImportance(NotificationManager.IMPORTANCE_MIN);

        Notification n2 = new Notification.Builder(mContext, TEST_CHANNEL_ID)
                .setCategory(Notification.CATEGORY_CALL)
                .setFlag(Notification.FLAG_FOREGROUND_SERVICE, true)
                .build();
        mRecordHighCall = new NotificationRecord(mContext, new StatusBarNotification(callPkg,
                callPkg, 1, "highcall", callUid, callUid, n2,
                new UserHandle(userId), "", 1999), getDefaultChannel());
        mRecordHighCall.setSystemImportance(NotificationManager.IMPORTANCE_HIGH);

        Notification n3 = new Notification.Builder(mContext, TEST_CHANNEL_ID)
                .setStyle(new Notification.MediaStyle()
                        .setMediaSession(new MediaSession.Token(null)))
                .build();
        mRecordDefaultMedia = new NotificationRecord(mContext, new StatusBarNotification(pkg2,
                pkg2, 1, "media", uid2, uid2, n3, new UserHandle(userId),
                "", 1499), getDefaultChannel());
        mRecordDefaultMedia.setSystemImportance(NotificationManager.IMPORTANCE_DEFAULT);

        Notification n4 = new Notification.Builder(mContext, TEST_CHANNEL_ID)
                .setStyle(new Notification.MessagingStyle("sender!")).build();
        mRecordInlineReply = new NotificationRecord(mContext, new StatusBarNotification(pkg2,
                pkg2, 1, "inlinereply", uid2, uid2, n4, new UserHandle(userId),
                "", 1599), getDefaultChannel());
        mRecordInlineReply.setSystemImportance(NotificationManager.IMPORTANCE_HIGH);
        mRecordInlineReply.setPackagePriority(Notification.PRIORITY_MAX);

        if (smsPkg != null) {
            Notification n5 = new Notification.Builder(mContext, TEST_CHANNEL_ID)
                    .setCategory(Notification.CATEGORY_MESSAGE).build();
            mRecordSms = new NotificationRecord(mContext, new StatusBarNotification(smsPkg,
                    smsPkg, 1, "sms", smsUid, smsUid, n5, new UserHandle(userId),
                    "", 1299), getDefaultChannel());
            mRecordSms.setSystemImportance(NotificationManager.IMPORTANCE_DEFAULT);
        }

        Notification n6 = new Notification.Builder(mContext, TEST_CHANNEL_ID).build();
        mRecordStarredContact = new NotificationRecord(mContext, new StatusBarNotification(pkg2,
                pkg2, 1, "starred", uid2, uid2, n6, new UserHandle(userId),
                "", 1259), getDefaultChannel());
        mRecordStarredContact.setContactAffinity(ValidateNotificationPeople.STARRED_CONTACT);
        mRecordStarredContact.setSystemImportance(NotificationManager.IMPORTANCE_DEFAULT);

        Notification n7 = new Notification.Builder(mContext, TEST_CHANNEL_ID).build();
        mRecordContact = new NotificationRecord(mContext, new StatusBarNotification(pkg2,
                pkg2, 1, "contact", uid2, uid2, n7, new UserHandle(userId),
                "", 1259), getDefaultChannel());
        mRecordContact.setContactAffinity(ValidateNotificationPeople.VALID_CONTACT);
        mRecordContact.setSystemImportance(NotificationManager.IMPORTANCE_DEFAULT);

        Notification n8 = new Notification.Builder(mContext, TEST_CHANNEL_ID).build();
        mRecordUrgent = new NotificationRecord(mContext, new StatusBarNotification(pkg2,
                pkg2, 1, "urgent", uid2, uid2, n8, new UserHandle(userId),
                "", 1258), getDefaultChannel());
        mRecordUrgent.setSystemImportance(NotificationManager.IMPORTANCE_HIGH);

        Notification n9 = new Notification.Builder(mContext, TEST_CHANNEL_ID)
                .setCategory(Notification.CATEGORY_MESSAGE)
                .setFlag(Notification.FLAG_ONGOING_EVENT
                        |Notification.FLAG_FOREGROUND_SERVICE, true)
                .build();
        mRecordCheater = new NotificationRecord(mContext, new StatusBarNotification(pkg2,
                pkg2, 1, "cheater", uid2, uid2, n9, new UserHandle(userId),
                "", 9258), getDefaultChannel());
        mRecordCheater.setSystemImportance(NotificationManager.IMPORTANCE_LOW);
        mRecordCheater.setPackagePriority(Notification.PRIORITY_MAX);

        Notification n10 = new Notification.Builder(mContext, TEST_CHANNEL_ID)
                .setStyle(new Notification.InboxStyle().setSummaryText("message!")).build();
        mRecordEmail = new NotificationRecord(mContext, new StatusBarNotification(pkg2,
                pkg2, 1, "email", uid2, uid2, n10, new UserHandle(userId),
                "", 1599), getDefaultChannel());
        mRecordEmail.setSystemImportance(NotificationManager.IMPORTANCE_HIGH);

        Notification n11 = new Notification.Builder(mContext, TEST_CHANNEL_ID)
                .setCategory(Notification.CATEGORY_MESSAGE)
                .setColorized(true)
                .build();
        mRecordCheaterColorized = new NotificationRecord(mContext, new StatusBarNotification(pkg2,
                pkg2, 1, "cheater", uid2, uid2, n11, new UserHandle(userId),
                "", 9258), getDefaultChannel());
        mRecordCheaterColorized.setSystemImportance(NotificationManager.IMPORTANCE_LOW);

        Notification n12 = new Notification.Builder(mContext, TEST_CHANNEL_ID)
                .setCategory(Notification.CATEGORY_MESSAGE)
                .setColorized(true)
                .setStyle(new Notification.MediaStyle())
                .build();
        mNoMediaSessionMedia = new NotificationRecord(mContext, new StatusBarNotification(
                pkg2, pkg2, 1, "cheater", uid2, uid2, n12, new UserHandle(userId),
                "", 9258), getDefaultChannel());
        mNoMediaSessionMedia.setSystemImportance(NotificationManager.IMPORTANCE_DEFAULT);

        Notification n13 = new Notification.Builder(mContext, TEST_CHANNEL_ID)
                .setFlag(Notification.FLAG_FOREGROUND_SERVICE, true)
                .setColorized(true /* colorized */)
                .build();
        mRecordColorized = new NotificationRecord(mContext, new StatusBarNotification(pkg2,
                pkg2, 1, "colorized", uid2, uid2, n13,
                new UserHandle(userId), "", 1999), getDefaultChannel());
        mRecordColorized.setSystemImportance(NotificationManager.IMPORTANCE_HIGH);

        Notification n14 = new Notification.Builder(mContext, TEST_CHANNEL_ID)
                .setCategory(Notification.CATEGORY_CALL)
                .setColorized(true)
                .setFlag(Notification.FLAG_FOREGROUND_SERVICE, true)
                .build();
        mRecordColorizedCall = new NotificationRecord(mContext, new StatusBarNotification(callPkg,
                callPkg, 1, "colorizedCall", callUid, callUid, n14,
                new UserHandle(userId), "", 1999), getDefaultChannel());
        mRecordColorizedCall.setSystemImportance(NotificationManager.IMPORTANCE_HIGH);
    }

    @Test
    public void testOrdering() {
        final List<NotificationRecord> expected = new ArrayList<>();
        expected.add(mRecordColorizedCall);
        expected.add(mRecordColorized);
        expected.add(mRecordDefaultMedia);
        expected.add(mRecordHighCall);
        expected.add(mRecordInlineReply);
        if (mRecordSms != null) {
            expected.add(mRecordSms);
        }
        expected.add(mRecordStarredContact);
        expected.add(mRecordContact);
        expected.add(mRecordEmail);
        expected.add(mRecordUrgent);
        expected.add(mNoMediaSessionMedia);
        expected.add(mRecordCheater);
        expected.add(mRecordCheaterColorized);
        expected.add(mRecordMinCall);

        List<NotificationRecord> actual = new ArrayList<>();
        actual.addAll(expected);
        Collections.shuffle(actual);

        Collections.sort(actual, new NotificationComparator(mContext));

        assertThat(actual, contains(expected.toArray()));
    }

    @Test
    public void testMessaging() {
        NotificationComparator comp = new NotificationComparator(mContext);
        assertTrue(comp.isImportantMessaging(mRecordInlineReply));
        if (mRecordSms != null) {
            assertTrue(comp.isImportantMessaging(mRecordSms));
        }
        assertFalse(comp.isImportantMessaging(mRecordEmail));
        assertFalse(comp.isImportantMessaging(mRecordCheater));
    }

    @Test
    public void testPeople() {
        NotificationComparator comp = new NotificationComparator(mContext);
        assertTrue(comp.isImportantPeople(mRecordStarredContact));
        assertTrue(comp.isImportantPeople(mRecordContact));
    }

    private NotificationChannel getDefaultChannel() {
        return new NotificationChannel(NotificationChannel.DEFAULT_CHANNEL_ID, "name",
                NotificationManager.IMPORTANCE_LOW);
    }
}
