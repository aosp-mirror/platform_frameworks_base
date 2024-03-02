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

import static android.telecom.TelecomManager.EXTRA_CHANGE_DEFAULT_DIALER_PACKAGE_NAME;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Matchers.anyInt;
import static org.mockito.Matchers.anyString;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Person;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.graphics.Color;
import android.os.Build;
import android.os.UserHandle;
import android.os.Vibrator;
import android.provider.Settings;
import android.service.notification.StatusBarNotification;
import android.telecom.TelecomManager;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.internal.config.sysui.SystemUiSystemPropertiesFlags;
import com.android.server.UiServiceTestCase;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class NotificationComparatorTest extends UiServiceTestCase {
    @Mock Context mMockContext;
    @Mock TelecomManager mTm;
    @Mock RankingHandler handler;
    @Mock PackageManager mPm;
    @Mock Vibrator mVibrator;

    private final String callPkg = "com.android.server.notification";
    private final String sysPkg = "android";
    private final int callUid = 10;
    private String smsPkg;
    private final int smsUid = 11;
    private final String pkg2 = "pkg2";
    private final int uid2 = 1111111;
    private static final String TEST_CHANNEL_ID = "test_channel_id";

    private NotificationRecord mRecordMinCallNonInterruptive;
    private NotificationRecord mRecordMinCall;
    private NotificationRecord mRecordHighCall;
    private NotificationRecord mRecordHighCallStyle;
    private NotificationRecord mRecordEmail;
    private NotificationRecord mRecordSystemMax;
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

        final Resources res = mContext.getResources();
        when(mMockContext.getResources()).thenReturn(res);
        final Resources.Theme theme = mContext.getTheme();
        when(mMockContext.getTheme()).thenReturn(theme);
        final ContentResolver cr = mContext.getContentResolver();
        when(mMockContext.getContentResolver()).thenReturn(cr);
        when(mMockContext.getPackageManager()).thenReturn(mPm);
        when(mMockContext.getSystemService(eq(mMockContext.TELECOM_SERVICE))).thenReturn(mTm);
        when(mMockContext.getSystemService(Vibrator.class)).thenReturn(mVibrator);
        when(mMockContext.getString(anyInt())).thenCallRealMethod();
        when(mMockContext.getColor(anyInt())).thenCallRealMethod();
        when(mTm.getDefaultDialerPackage()).thenReturn(callPkg);
        final ApplicationInfo legacy = new ApplicationInfo();
        legacy.targetSdkVersion = Build.VERSION_CODES.N_MR1;
        try {
            when(mPm.getApplicationInfoAsUser(anyString(), anyInt(), anyInt())).thenReturn(legacy);
            when(mMockContext.getApplicationInfo()).thenReturn(legacy);
        } catch (PackageManager.NameNotFoundException e) {
            // let's hope not
        }

        smsPkg = Settings.Secure.getString(mMockContext.getContentResolver(),
                Settings.Secure.SMS_DEFAULT_APPLICATION);

        Notification nonInterruptiveNotif = new Notification.Builder(mMockContext, TEST_CHANNEL_ID)
                .setCategory(Notification.CATEGORY_CALL)
                .setFlag(Notification.FLAG_FOREGROUND_SERVICE, true)
                .build();
        mRecordMinCallNonInterruptive = new NotificationRecord(mMockContext,
                new StatusBarNotification(callPkg,
                        callPkg, 1, "mRecordMinCallNonInterruptive", callUid, callUid,
                        nonInterruptiveNotif,
                        new UserHandle(userId), "", 2001), getDefaultChannel());
        mRecordMinCallNonInterruptive.setSystemImportance(NotificationManager.IMPORTANCE_MIN);
        mRecordMinCallNonInterruptive.setInterruptive(false);

        Notification n1 = new Notification.Builder(mMockContext, TEST_CHANNEL_ID)
                .setCategory(Notification.CATEGORY_CALL)
                .setFlag(Notification.FLAG_FOREGROUND_SERVICE, true)
                .build();
        mRecordMinCall = new NotificationRecord(mMockContext, new StatusBarNotification(callPkg,
                callPkg, 1, "minCall", callUid, callUid, n1,
                new UserHandle(userId), "", 2000), getDefaultChannel());
        mRecordMinCall.setSystemImportance(NotificationManager.IMPORTANCE_MIN);
        mRecordMinCall.setInterruptive(true);

        Notification n2 = new Notification.Builder(mMockContext, TEST_CHANNEL_ID)
                .setCategory(Notification.CATEGORY_CALL)
                .setFlag(Notification.FLAG_FOREGROUND_SERVICE, true)
                .build();
        mRecordHighCall = new NotificationRecord(mMockContext, new StatusBarNotification(callPkg,
                callPkg, 1, "highcall", callUid, callUid, n2,
                new UserHandle(userId), "", 1999), getDefaultChannel());
        mRecordHighCall.setSystemImportance(NotificationManager.IMPORTANCE_HIGH);

        Notification nHighCallStyle = new Notification.Builder(mMockContext, TEST_CHANNEL_ID)
                .setStyle(Notification.CallStyle.forOngoingCall(
                        new Person.Builder().setName("caller").build(),
                        mock(PendingIntent.class)
                ))
                .setFlag(Notification.FLAG_FOREGROUND_SERVICE, true)
                .build();
        mRecordHighCallStyle = new NotificationRecord(mMockContext, new StatusBarNotification(
                callPkg, callPkg, 1, "highCallStyle", callUid, callUid, nHighCallStyle,
                new UserHandle(userId), "", 2000), getDefaultChannel());
        mRecordHighCallStyle.setSystemImportance(NotificationManager.IMPORTANCE_HIGH);
        mRecordHighCallStyle.setInterruptive(true);

        Notification n4 = new Notification.Builder(mMockContext, TEST_CHANNEL_ID)
                .setStyle(new Notification.MessagingStyle("sender!")).build();
        mRecordInlineReply = new NotificationRecord(mMockContext, new StatusBarNotification(pkg2,
                pkg2, 1, "inlinereply", uid2, uid2, n4, new UserHandle(userId),
                "", 1599), getDefaultChannel());
        mRecordInlineReply.setSystemImportance(NotificationManager.IMPORTANCE_HIGH);
        mRecordInlineReply.setPackagePriority(Notification.PRIORITY_MAX);

        if (smsPkg != null) {
            Notification n5 = new Notification.Builder(mMockContext, TEST_CHANNEL_ID)
                    .setCategory(Notification.CATEGORY_MESSAGE).build();
            mRecordSms = new NotificationRecord(mMockContext, new StatusBarNotification(smsPkg,
                    smsPkg, 1, "sms", smsUid, smsUid, n5, new UserHandle(userId),
                    "", 1299), getDefaultChannel());
            mRecordSms.setSystemImportance(NotificationManager.IMPORTANCE_DEFAULT);
        }

        Notification n6 = new Notification.Builder(mMockContext, TEST_CHANNEL_ID).build();
        mRecordStarredContact = new NotificationRecord(mMockContext, new StatusBarNotification(pkg2,
                pkg2, 1, "starred", uid2, uid2, n6, new UserHandle(userId),
                "", 1259), getDefaultChannel());
        mRecordStarredContact.setContactAffinity(ValidateNotificationPeople.STARRED_CONTACT);
        mRecordStarredContact.setSystemImportance(NotificationManager.IMPORTANCE_DEFAULT);

        Notification n7 = new Notification.Builder(mMockContext, TEST_CHANNEL_ID).build();
        mRecordContact = new NotificationRecord(mMockContext, new StatusBarNotification(pkg2,
                pkg2, 1, "contact", uid2, uid2, n7, new UserHandle(userId),
                "", 1259), getDefaultChannel());
        mRecordContact.setContactAffinity(ValidateNotificationPeople.VALID_CONTACT);
        mRecordContact.setSystemImportance(NotificationManager.IMPORTANCE_DEFAULT);

        Notification nSystemMax = new Notification.Builder(mMockContext, TEST_CHANNEL_ID).build();
        mRecordSystemMax = new NotificationRecord(mMockContext, new StatusBarNotification(sysPkg,
                sysPkg, 1, "systemmax", uid2, uid2, nSystemMax, new UserHandle(userId),
                "", 1244), getDefaultChannel());
        mRecordSystemMax.setSystemImportance(NotificationManager.IMPORTANCE_HIGH);

        Notification n8 = new Notification.Builder(mMockContext, TEST_CHANNEL_ID).build();
        mRecordUrgent = new NotificationRecord(mMockContext, new StatusBarNotification(pkg2,
                pkg2, 1, "urgent", uid2, uid2, n8, new UserHandle(userId),
                "", 1258), getDefaultChannel());
        mRecordUrgent.setSystemImportance(NotificationManager.IMPORTANCE_HIGH);

        Notification n9 = new Notification.Builder(mMockContext, TEST_CHANNEL_ID)
                .setCategory(Notification.CATEGORY_MESSAGE)
                .setFlag(Notification.FLAG_ONGOING_EVENT
                        |Notification.FLAG_FOREGROUND_SERVICE, true)
                .build();
        mRecordCheater = new NotificationRecord(mMockContext, new StatusBarNotification(pkg2,
                pkg2, 1, "cheater", uid2, uid2, n9, new UserHandle(userId),
                "", 9258), getDefaultChannel());
        mRecordCheater.setSystemImportance(NotificationManager.IMPORTANCE_LOW);
        mRecordCheater.setPackagePriority(Notification.PRIORITY_MAX);

        Notification n10 = new Notification.Builder(mMockContext, TEST_CHANNEL_ID)
                .setStyle(new Notification.InboxStyle().setSummaryText("message!")).build();
        mRecordEmail = new NotificationRecord(mMockContext, new StatusBarNotification(pkg2,
                pkg2, 1, "email", uid2, uid2, n10, new UserHandle(userId),
                "", 1599), getDefaultChannel());
        mRecordEmail.setSystemImportance(NotificationManager.IMPORTANCE_HIGH);

        Notification n11 = new Notification.Builder(mMockContext, TEST_CHANNEL_ID)
                .setCategory(Notification.CATEGORY_MESSAGE)
                .setColorized(true).setColor(Color.WHITE)
                .build();
        mRecordCheaterColorized = new NotificationRecord(mMockContext,
                new StatusBarNotification(pkg2,pkg2, 1, "cheaterColorized", uid2, uid2, n11,
                new UserHandle(userId), "", 9258), getDefaultChannel());
        mRecordCheaterColorized.setSystemImportance(NotificationManager.IMPORTANCE_LOW);

        Notification n12 = new Notification.Builder(mMockContext, TEST_CHANNEL_ID)
                .setCategory(Notification.CATEGORY_MESSAGE)
                .setColorized(true).setColor(Color.WHITE)
                .setStyle(new Notification.MediaStyle())
                .build();
        mNoMediaSessionMedia = new NotificationRecord(mMockContext, new StatusBarNotification(
                pkg2, pkg2, 1, "media", uid2, uid2, n12, new UserHandle(userId),
                "", 9258), getDefaultChannel());
        mNoMediaSessionMedia.setSystemImportance(NotificationManager.IMPORTANCE_DEFAULT);

        Notification n13 = new Notification.Builder(mMockContext, TEST_CHANNEL_ID)
                .setFlag(Notification.FLAG_FOREGROUND_SERVICE, true)
                .setColorized(true).setColor(Color.WHITE)
                .build();
        mRecordColorized = new NotificationRecord(mMockContext, new StatusBarNotification(pkg2,
                pkg2, 1, "colorized", uid2, uid2, n13,
                new UserHandle(userId), "", 1999), getDefaultChannel());
        mRecordColorized.setSystemImportance(NotificationManager.IMPORTANCE_HIGH);

        Notification n14 = new Notification.Builder(mMockContext, TEST_CHANNEL_ID)
                .setCategory(Notification.CATEGORY_CALL)
                .setColorized(true).setColor(Color.WHITE)
                .setFlag(Notification.FLAG_FOREGROUND_SERVICE, true)
                .build();
        mRecordColorizedCall = new NotificationRecord(mMockContext, new StatusBarNotification(
                callPkg, callPkg, 1, "colorizedCall", callUid, callUid, n14,
                new UserHandle(userId), "", 1999), getDefaultChannel());
        mRecordColorizedCall.setSystemImportance(NotificationManager.IMPORTANCE_HIGH);
    }

    @After
    public void tearDown() {
        SystemUiSystemPropertiesFlags.TEST_RESOLVER = null;
    }

    @Test
    public void testOrdering() {
        final List<NotificationRecord> expected = new ArrayList<>();
        expected.add(mRecordColorizedCall);
        expected.add(mRecordColorized);
        expected.add(mRecordHighCallStyle);
        expected.add(mRecordHighCall);
        expected.add(mRecordInlineReply);
        if (mRecordSms != null) {
            expected.add(mRecordSms);
        }
        expected.add(mRecordStarredContact);
        expected.add(mRecordContact);
        expected.add(mRecordSystemMax);
        expected.add(mRecordEmail);
        expected.add(mRecordUrgent);
        expected.add(mNoMediaSessionMedia);
        expected.add(mRecordCheater);
        expected.add(mRecordCheaterColorized);
        expected.add(mRecordMinCallNonInterruptive);
        expected.add(mRecordMinCall);

        List<NotificationRecord> actual = new ArrayList<>();
        actual.addAll(expected);
        Collections.shuffle(actual);

        Collections.sort(actual, new NotificationComparator(mMockContext));

        assertThat(actual).containsExactlyElementsIn(expected).inOrder();
    }

    @Test
    public void testRankingScoreOverrides() {
        NotificationComparator comp = new NotificationComparator(mMockContext);
        NotificationRecord recordMinCallNonInterruptive = spy(mRecordMinCallNonInterruptive);
        assertTrue(comp.compare(mRecordMinCall, recordMinCallNonInterruptive) > 0);

        when(recordMinCallNonInterruptive.getRankingScore()).thenReturn(1f);
        assertTrue(comp.compare(mRecordMinCall, recordMinCallNonInterruptive) > 0);
        assertTrue(comp.compare(mRecordCheater, recordMinCallNonInterruptive) > 0);
        assertTrue(comp.compare(mRecordColorizedCall, recordMinCallNonInterruptive) < 0);
    }

    @Test
    public void testMessaging() {
        NotificationComparator comp = new NotificationComparator(mMockContext);
        assertTrue(comp.isImportantMessaging(mRecordInlineReply));
        if (mRecordSms != null) {
            assertTrue(comp.isImportantMessaging(mRecordSms));
        }
        assertFalse(comp.isImportantMessaging(mRecordEmail));
        assertFalse(comp.isImportantMessaging(mRecordCheater));
    }

    @Test
    public void testPeople() {
        NotificationComparator comp = new NotificationComparator(mMockContext);
        assertTrue(comp.isImportantPeople(mRecordStarredContact));
        assertTrue(comp.isImportantPeople(mRecordContact));
    }

    @Test
    public void testChangeDialerPackageWhileSorting() throws InterruptedException {
        final int halfList = 100;
        int userId = UserHandle.myUserId();
        when(mTm.getDefaultDialerPackage()).thenReturn("B");

        ArgumentCaptor<BroadcastReceiver> broadcastReceiverCaptor = ArgumentCaptor.forClass(
                BroadcastReceiver.class);
        NotificationComparator comparator = new NotificationComparator(mMockContext);
        verify(mMockContext).registerReceiver(broadcastReceiverCaptor.capture(), any());
        BroadcastReceiver dialerChangedBroadcastReceiver = broadcastReceiverCaptor.getValue();

        ArrayList<NotificationRecord> records = new ArrayList<>();
        for (int i = 0; i < halfList; i++) {
            Notification notifCallFromPkgA = new Notification.Builder(mMockContext, TEST_CHANNEL_ID)
                    .setCategory(Notification.CATEGORY_CALL)
                    .setFlag(Notification.FLAG_FOREGROUND_SERVICE, true)
                    .build();
            records.add(new NotificationRecord(mMockContext,
                    new StatusBarNotification("A", "A", 2 * i, "callA", callUid, callUid,
                            notifCallFromPkgA, new UserHandle(userId), "", 0),
                    getDefaultChannel()));

            Notification notifCallFromPkgB = new Notification.Builder(mMockContext, TEST_CHANNEL_ID)
                    .setCategory(Notification.CATEGORY_CALL)
                    .setFlag(Notification.FLAG_FOREGROUND_SERVICE, true)
                    .build();
            records.add(new NotificationRecord(mMockContext,
                    new StatusBarNotification("B", "B", 2 * i + 1, "callB", callUid, callUid,
                            notifCallFromPkgB, new UserHandle(userId), "", 0),
                    getDefaultChannel()));
        }

        CountDownLatch allDone = new CountDownLatch(2);
        new Thread(() -> {
            // The lock prevents the other thread from changing the dialer package mid-sort, so:
            // 1) Results should be "all B before all A" (asserted below).
            // 2) No "IllegalArgumentException: Comparison method violates its general contract!"
            synchronized (comparator.mStateLock) {
                records.sort(comparator);
                allDone.countDown();
            }
        }).start();

        new Thread(() -> {
            String nextDialer = "A";
            while (allDone.getCount() == 2) {
                Intent dialerChangedIntent = new Intent();
                dialerChangedIntent.putExtra(EXTRA_CHANGE_DEFAULT_DIALER_PACKAGE_NAME, nextDialer);
                dialerChangedBroadcastReceiver.onReceive(mMockContext, dialerChangedIntent);
                nextDialer = nextDialer.equals("A") ? "B" : "A";
            }
            allDone.countDown();
        }).start();

        allDone.await();

        for (int i = 0; i < halfList; i++) {
            assertWithMessage("Wrong element in position #" + i)
                    .that(records.get(i).getSbn().getPackageName()).isEqualTo("B");
        }
        for (int i = halfList; i < 2 * halfList; i++) {
            assertWithMessage("Wrong element in position #" + i)
                    .that(records.get(i).getSbn().getPackageName()).isEqualTo("A");
        }
    }

    private NotificationChannel getDefaultChannel() {
        return new NotificationChannel(NotificationChannel.DEFAULT_CHANNEL_ID, "name",
                NotificationManager.IMPORTANCE_LOW);
    }
}
