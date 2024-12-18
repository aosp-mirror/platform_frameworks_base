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

import static android.app.NotificationManager.IMPORTANCE_DEFAULT;
import static android.app.NotificationManager.IMPORTANCE_LOW;

import static android.platform.test.flag.junit.SetFlagsRule.DefaultInitValueType.DEVICE_DEFAULT;
import static com.google.common.truth.Truth.assertThat;
import static junit.framework.TestCase.assertEquals;

import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Matchers.anyInt;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.app.Flags;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.ContentProvider;
import android.content.Context;
import android.content.IContentProvider;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.media.AudioAttributes;
import android.net.Uri;
import android.os.Build;
import android.os.UserHandle;
import android.os.Vibrator;
import android.platform.test.annotations.DisableFlags;
import android.platform.test.annotations.EnableFlags;
import android.platform.test.flag.junit.SetFlagsRule;
import android.service.notification.StatusBarNotification;
import android.testing.TestableContentResolver;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.internal.compat.IPlatformCompat;
import com.android.server.UiServiceTestCase;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class RankingHelperTest extends UiServiceTestCase {
    private static final String UPDATED_PKG = "updatedmPkg";
    private static final int UID2 = 1111;
    private static final String SYSTEM_PKG = "android";
    private static final int SYSTEM_UID= 1000;
    private static final String TEST_CHANNEL_ID = "test_channel_id";
    private static final String TEST_AUTHORITY = "test";
    private static final Uri SOUND_URI =
            Uri.parse("content://" + TEST_AUTHORITY + "/internal/audio/media/10");
    private static final Uri CANONICAL_SOUND_URI =
            Uri.parse("content://" + TEST_AUTHORITY
                    + "/internal/audio/media/10?title=Test&canonical=1");

    @Mock NotificationUsageStats mUsageStats;
    @Mock RankingHandler mHandler;
    @Mock PackageManager mPm;
    @Mock IContentProvider mTestIContentProvider;
    @Mock Context mContext;
    @Mock ZenModeHelper mMockZenModeHelper;
    @Mock RankingConfig mConfig;
    @Mock Vibrator mVibrator;

    private NotificationManager.Policy mTestNotificationPolicy;
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
    private NotificationRecord mRecentlyIntrusive;
    private NotificationRecord mNewest;
    private RankingHelper mHelper;

    @Rule
    public final SetFlagsRule mSetFlagsRule = new SetFlagsRule(DEVICE_DEFAULT);

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        UserHandle mUser = UserHandle.ALL;

        final ApplicationInfo legacy = new ApplicationInfo();
        legacy.targetSdkVersion = Build.VERSION_CODES.N_MR1;
        final ApplicationInfo upgrade = new ApplicationInfo();
        upgrade.targetSdkVersion = Build.VERSION_CODES.O;
        when(mPm.getApplicationInfoAsUser(eq(mPkg), anyInt(), anyInt())).thenReturn(legacy);
        when(mPm.getApplicationInfoAsUser(eq(UPDATED_PKG), anyInt(), anyInt())).thenReturn(upgrade);
        when(mPm.getApplicationInfoAsUser(eq(SYSTEM_PKG), anyInt(), anyInt())).thenReturn(upgrade);
        when(mPm.getPackageUidAsUser(eq(mPkg), anyInt())).thenReturn(mUid);
        when(mPm.getPackageUidAsUser(eq(UPDATED_PKG), anyInt())).thenReturn(UID2);
        when(mPm.getPackageUidAsUser(eq(SYSTEM_PKG), anyInt())).thenReturn(SYSTEM_UID);
        PackageInfo info = mock(PackageInfo.class);
        info.signatures = new Signature[] {mock(Signature.class)};
        when(mPm.getPackageInfoAsUser(eq(SYSTEM_PKG), anyInt(), anyInt())).thenReturn(info);
        when(mPm.getPackageInfoAsUser(eq(mPkg), anyInt(), anyInt()))
                .thenReturn(mock(PackageInfo.class));
        when(mContext.getResources()).thenReturn(
                InstrumentationRegistry.getContext().getResources());
        when(mContext.getContentResolver()).thenReturn(
                InstrumentationRegistry.getContext().getContentResolver());
        when(mContext.getPackageManager()).thenReturn(mPm);
        when(mContext.getApplicationInfo()).thenReturn(legacy);
        when(mContext.getSystemService(Vibrator.class)).thenReturn(mVibrator);
        TestableContentResolver contentResolver = getContext().getContentResolver();
        contentResolver.setFallbackToExisting(false);

        ContentProvider testContentProvider = mock(ContentProvider.class);
        when(testContentProvider.getIContentProvider()).thenReturn(mTestIContentProvider);
        contentResolver.addProvider(TEST_AUTHORITY, testContentProvider);

        when(mTestIContentProvider.canonicalize(any(), eq(SOUND_URI)))
                .thenReturn(CANONICAL_SOUND_URI);
        when(mTestIContentProvider.canonicalize(any(), eq(CANONICAL_SOUND_URI)))
                .thenReturn(CANONICAL_SOUND_URI);
        when(mTestIContentProvider.uncanonicalize(any(), eq(CANONICAL_SOUND_URI)))
                .thenReturn(SOUND_URI);

        mTestNotificationPolicy = new NotificationManager.Policy(0, 0, 0, 0,
                NotificationManager.Policy.STATE_CHANNELS_BYPASSING_DND, 0);
        when(mMockZenModeHelper.getNotificationPolicy()).thenReturn(mTestNotificationPolicy);
        mHelper = new RankingHelper(getContext(), mHandler, mConfig, mMockZenModeHelper,
                mUsageStats, new String[] {ImportanceExtractor.class.getName()},
                mock(IPlatformCompat.class));

        mNotiGroupGSortA = new Notification.Builder(mContext, TEST_CHANNEL_ID)
                .setContentTitle("A")
                .setGroup("G")
                .setSortKey("A")
                .setWhen(1205)
                .build();
        mRecordGroupGSortA = new NotificationRecord(mContext, new StatusBarNotification(
                mPkg, mPkg, 1, null, 0, 0, mNotiGroupGSortA, mUser,
                null, System.currentTimeMillis()), getLowChannel());

        mNotiGroupGSortB = new Notification.Builder(mContext, TEST_CHANNEL_ID)
                .setContentTitle("B")
                .setGroup("G")
                .setSortKey("B")
                .setWhen(1200)
                .build();
        mRecordGroupGSortB = new NotificationRecord(mContext, new StatusBarNotification(
                mPkg, mPkg, 1, null, 0, 0, mNotiGroupGSortB, mUser,
                null, System.currentTimeMillis()), getLowChannel());

        mNotiNoGroup = new Notification.Builder(mContext, TEST_CHANNEL_ID)
                .setContentTitle("C")
                .setWhen(1201)
                .build();
        mRecordNoGroup = new NotificationRecord(mContext, new StatusBarNotification(
                mPkg, mPkg, 1, null, 0, 0, mNotiNoGroup, mUser,
                null, System.currentTimeMillis()), getLowChannel());

        mNotiNoGroup2 = new Notification.Builder(mContext, TEST_CHANNEL_ID)
                .setContentTitle("D")
                .setWhen(1202)
                .build();
        mRecordNoGroup2 = new NotificationRecord(mContext, new StatusBarNotification(
                mPkg, mPkg, 1, null, 0, 0, mNotiNoGroup2, mUser,
                null, System.currentTimeMillis()), getLowChannel());

        mNotiNoGroupSortA = new Notification.Builder(mContext, TEST_CHANNEL_ID)
                .setContentTitle("E")
                .setWhen(1201)
                .setSortKey("A")
                .build();
        mRecordNoGroupSortA = new NotificationRecord(mContext, new StatusBarNotification(
                mPkg, mPkg, 1, null, 0, 0, mNotiNoGroupSortA, mUser,
                null, System.currentTimeMillis()), getLowChannel());

        Notification n = new Notification.Builder(mContext, TEST_CHANNEL_ID)
                .setContentTitle("D")
                .build();
        mRecentlyIntrusive = new NotificationRecord(mContext, new StatusBarNotification(
                mPkg, mPkg, 1, null, 0, 0, n, mUser,
                null, 100), getDefaultChannel());
        mRecentlyIntrusive.setRecentlyIntrusive(true);

        mNewest = new NotificationRecord(mContext, new StatusBarNotification(
                mPkg, mPkg, 2, null, 0, 0, n, mUser,
                null, 10000), getDefaultChannel());
    }

    private NotificationChannel getLowChannel() {
        return new NotificationChannel(NotificationChannel.DEFAULT_CHANNEL_ID, "name",
                IMPORTANCE_LOW);
    }

    private NotificationChannel getDefaultChannel() {
        return new NotificationChannel(NotificationChannel.DEFAULT_CHANNEL_ID, "name",
                IMPORTANCE_DEFAULT);
    }

    @Test
    public void testSortShouldRespectCritical() throws Exception {
        ArrayList<NotificationRecord> notificationList = new ArrayList<NotificationRecord>(7);
        NotificationRecord critical = generateRecord(0);
        NotificationRecord critical_ish = generateRecord(1);
        NotificationRecord critical_notAtAll = generateRecord(100);

        notificationList.add(critical_ish);
        notificationList.add(mRecordGroupGSortA);
        notificationList.add(critical_notAtAll);
        notificationList.add(mRecordGroupGSortB);
        notificationList.add(mRecordNoGroup);
        notificationList.add(mRecordNoGroupSortA);
        notificationList.add(critical);
        mHelper.sort(notificationList);

        assertTrue(mHelper.indexOf(notificationList, critical) == 0);
        assertTrue(mHelper.indexOf(notificationList, critical_ish) == 1);
        assertTrue(mHelper.indexOf(notificationList, critical_notAtAll) == 6);
    }

    private NotificationRecord generateRecord(int criticality) {
        NotificationChannel channel = new NotificationChannel("a", "a", IMPORTANCE_LOW);
        final Notification.Builder builder = new Notification.Builder(getContext())
                .setContentTitle("foo")
                .setSmallIcon(android.R.drawable.sym_def_app_icon);
        Notification n = builder.build();
        StatusBarNotification sbn = new StatusBarNotification("", "", 0, "", 0,
                0, n, UserHandle.ALL, null, System.currentTimeMillis());
        NotificationRecord notificationRecord = new NotificationRecord(getContext(), sbn, channel);
        notificationRecord.setCriticality(criticality);
        return notificationRecord;
    }

    @Test
    public void testFindAfterRankingWithASplitGroup() throws Exception {
        ArrayList<NotificationRecord> notificationList = new ArrayList<NotificationRecord>(4);
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
    public void testGroupNotifications_highestIsProxy() {
        ArrayList<NotificationRecord> notificationList = new ArrayList<>();
        // this should be the last in the list, except it's in a group with a high child
        Notification lowSummaryN = new Notification.Builder(mContext, "")
                .setGroup("group")
                .setGroupSummary(true)
                .build();
        NotificationRecord lowSummary = new NotificationRecord(mContext, new StatusBarNotification(
                mPkg, mPkg, 1, "summary", 0, 0, lowSummaryN, mUser,
                null, System.currentTimeMillis()), getLowChannel());
        notificationList.add(lowSummary);

        Notification lowN = new Notification.Builder(mContext, "").build();
        NotificationRecord low = new NotificationRecord(mContext, new StatusBarNotification(
                mPkg, mPkg, 1, "low", 0, 0, lowN, mUser,
                null, System.currentTimeMillis()), getLowChannel());
        low.setContactAffinity(0.5f);
        notificationList.add(low);

        Notification highChildN = new Notification.Builder(mContext, "")
                .setGroup("group")
                .setGroupSummary(false)
                .build();
        NotificationRecord highChild = new NotificationRecord(mContext, new StatusBarNotification(
                mPkg, mPkg, 1, "child", 0, 0, highChildN, mUser,
                null, System.currentTimeMillis()), getDefaultChannel());
        notificationList.add(highChild);

        mHelper.sort(notificationList);

        assertEquals(lowSummary, notificationList.get(0));
        assertEquals(highChild, notificationList.get(1));
        assertEquals(low, notificationList.get(2));
    }

    @Test
    @DisableFlags({android.app.Flags.FLAG_SORT_SECTION_BY_TIME})
    public void testSortByIntrusivenessNotRecency() {
        ArrayList<NotificationRecord> expected = new ArrayList<>();
        expected.add(mRecentlyIntrusive);
        expected.add(mNewest);

        ArrayList<NotificationRecord> actual = new ArrayList<>();
        actual.addAll(expected);
        Collections.shuffle(actual);

        mHelper.sort(actual);
        assertThat(actual).containsExactlyElementsIn(expected).inOrder();
    }

    @Test
    @EnableFlags({android.app.Flags.FLAG_SORT_SECTION_BY_TIME})
    public void testSortByRecencyNotIntrusiveness() {
        ArrayList<NotificationRecord> expected = new ArrayList<>();
        expected.add(mNewest);
        expected.add(mRecentlyIntrusive);

        ArrayList<NotificationRecord> actual = new ArrayList<>();
        actual.addAll(expected);
        Collections.shuffle(actual);

        mHelper.sort(actual);
        assertThat(actual).containsExactlyElementsIn(expected).inOrder();
    }

    @Test
    @EnableFlags(android.app.Flags.FLAG_SORT_SECTION_BY_TIME)
    public void testSort_oldWhenChildren_unspecifiedSummary() {
        NotificationRecord child1 = new NotificationRecord(mContext,
                new StatusBarNotification(
                    mPkg, mPkg, 1, null, 0, 0,
                        new Notification.Builder(mContext, TEST_CHANNEL_ID)
                            .setGroup("G")
                            .setWhen(1200)
                            .build(),
                        mUser, null, System.currentTimeMillis()), getLowChannel());
        NotificationRecord child2 = new NotificationRecord(mContext,
                new StatusBarNotification(
                        mPkg, mPkg, 2, null, 0, 0,
                        new Notification.Builder(mContext, TEST_CHANNEL_ID)
                                .setGroup("G")
                                .setWhen(1300)
                                .build(),
                        mUser, null, System.currentTimeMillis()), getLowChannel());
        NotificationRecord summary = new NotificationRecord(mContext,
                new StatusBarNotification(
                        mPkg, mPkg, 3, null, 0, 0,
                        new Notification.Builder(mContext, TEST_CHANNEL_ID)
                                .setGroup("G")
                                .setGroupSummary(true)
                                .build(),
                        mUser, null, System.currentTimeMillis()), getLowChannel());

        // in time slightly before the children, but much earlier than the summary.
        // will only be sorted first if the summary is not the group proxy for group G.
        NotificationRecord unrelated = new NotificationRecord(mContext,
                new StatusBarNotification(
                        mPkg, mPkg, 11, null, 0, 0,
                        new Notification.Builder(mContext, TEST_CHANNEL_ID)
                                .setWhen(1500)
                                .build(),
                        mUser, null, System.currentTimeMillis()), getLowChannel());

        ArrayList<NotificationRecord> expected = new ArrayList<>();
        expected.add(unrelated);
        expected.add(summary);
        expected.add(child2);
        expected.add(child1);

        ArrayList<NotificationRecord> actual = new ArrayList<>();
        actual.addAll(expected);
        Collections.shuffle(actual);

        mHelper.sort(actual);
        assertThat(actual).containsExactlyElementsIn(expected).inOrder();
    }

    @Test
    @EnableFlags(android.app.Flags.FLAG_SORT_SECTION_BY_TIME)
    public void testSort_oldChildren_unspecifiedSummary() {
        NotificationRecord child1 = new NotificationRecord(mContext,
                new StatusBarNotification(
                        mPkg, mPkg, 1, null, 0, 0,
                        new Notification.Builder(mContext, TEST_CHANNEL_ID)
                                .setGroup("G")
                                .build(),
                        mUser, null, 1200), getLowChannel());
        NotificationRecord child2 = new NotificationRecord(mContext,
                new StatusBarNotification(
                        mPkg, mPkg, 2, null, 0, 0,
                        new Notification.Builder(mContext, TEST_CHANNEL_ID)
                                .setGroup("G")
                                .build(),
                        mUser, null, 1300), getLowChannel());
        NotificationRecord summary = new NotificationRecord(mContext,
                new StatusBarNotification(
                        mPkg, mPkg, 3, null, 0, 0,
                        new Notification.Builder(mContext, TEST_CHANNEL_ID)
                                .setGroup("G")
                                .setGroupSummary(true)
                                .build(),
                        mUser, null, System.currentTimeMillis()), getLowChannel());

        // in time slightly before the children, but much earlier than the summary.
        // will only be sorted first if the summary is not the group proxy for group G.
        NotificationRecord unrelated = new NotificationRecord(mContext,
                new StatusBarNotification(
                        mPkg, mPkg, 11, null, 0, 0,
                        new Notification.Builder(mContext, TEST_CHANNEL_ID)
                                .setWhen(1500)
                                .build(),
                        mUser, null, System.currentTimeMillis()), getLowChannel());

        ArrayList<NotificationRecord> expected = new ArrayList<>();
        expected.add(unrelated);
        expected.add(summary);
        expected.add(child2);
        expected.add(child1);

        ArrayList<NotificationRecord> actual = new ArrayList<>();
        actual.addAll(expected);
        Collections.shuffle(actual);

        mHelper.sort(actual);
        assertThat(actual).containsExactlyElementsIn(expected).inOrder();
    }

    @Test
    @EnableFlags(android.app.Flags.FLAG_SORT_SECTION_BY_TIME)
    public void testSort_oldChildren_oldSummary() {
        NotificationRecord child1 = new NotificationRecord(mContext,
                new StatusBarNotification(
                        mPkg, mPkg, 1, null, 0, 0,
                        new Notification.Builder(mContext, TEST_CHANNEL_ID)
                                .setGroup("G")
                                .build(),
                        mUser, null, 1200), getLowChannel());
        NotificationRecord child2 = new NotificationRecord(mContext,
                new StatusBarNotification(
                        mPkg, mPkg, 2, null, 0, 0,
                        new Notification.Builder(mContext, TEST_CHANNEL_ID)
                                .setGroup("G")
                                .build(),
                        mUser, null, 1300), getLowChannel());
        NotificationRecord summary = new NotificationRecord(mContext,
                new StatusBarNotification(
                        mPkg, mPkg, 3, null, 0, 0,
                        new Notification.Builder(mContext, TEST_CHANNEL_ID)
                                .setGroup("G")
                                .setGroupSummary(true)
                                .setWhen(1600)
                                .build(),
                        mUser, null, System.currentTimeMillis()), getLowChannel());

        // in time slightly before the children, but much earlier than the summary.
        // will only be sorted first if the summary is not the group proxy for group G.
        NotificationRecord unrelated = new NotificationRecord(mContext,
                new StatusBarNotification(
                        mPkg, mPkg, 11, null, 0, 0,
                        new Notification.Builder(mContext, TEST_CHANNEL_ID)
                                .setWhen(1500)
                                .build(),
                        mUser, null, System.currentTimeMillis()), getLowChannel());

        ArrayList<NotificationRecord> expected = new ArrayList<>();
        expected.add(unrelated);
        expected.add(summary);
        expected.add(child2);
        expected.add(child1);

        ArrayList<NotificationRecord> actual = new ArrayList<>();
        actual.addAll(expected);
        Collections.shuffle(actual);

        mHelper.sort(actual);
        assertThat(actual).containsExactlyElementsIn(expected).inOrder();
    }
}
