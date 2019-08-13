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

package com.android.systemui.statusbar.notification.collection;

import static android.app.AppOpsManager.OP_ACCEPT_HANDOVER;
import static android.app.AppOpsManager.OP_CAMERA;
import static android.app.Notification.CATEGORY_ALARM;
import static android.app.Notification.CATEGORY_CALL;
import static android.app.Notification.CATEGORY_EVENT;
import static android.app.Notification.CATEGORY_MESSAGE;
import static android.app.Notification.CATEGORY_REMINDER;
import static android.app.NotificationManager.IMPORTANCE_DEFAULT;
import static android.app.NotificationManager.IMPORTANCE_LOW;
import static android.app.NotificationManager.IMPORTANCE_MIN;

import static com.android.systemui.statusbar.NotificationEntryHelper.modifySbn;
import static com.android.systemui.statusbar.notification.collection.NotificationDataTest.TestableNotificationData.OVERRIDE_CHANNEL;
import static com.android.systemui.statusbar.notification.collection.NotificationDataTest.TestableNotificationData.OVERRIDE_IMPORTANCE;
import static com.android.systemui.statusbar.notification.collection.NotificationDataTest.TestableNotificationData.OVERRIDE_RANK;
import static com.android.systemui.statusbar.notification.collection.NotificationDataTest.TestableNotificationData.OVERRIDE_VIS_EFFECTS;
import static com.android.systemui.statusbar.notification.stack.NotificationSectionsManager.BUCKET_ALERTING;
import static com.android.systemui.statusbar.notification.stack.NotificationSectionsManager.BUCKET_SILENT;

import static junit.framework.Assert.assertEquals;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Person;
import android.content.Context;
import android.content.Intent;
import android.content.pm.IPackageManager;
import android.content.pm.PackageManager;
import android.graphics.drawable.Icon;
import android.media.session.MediaSession;
import android.os.Bundle;
import android.os.Process;
import android.service.notification.NotificationListenerService;
import android.service.notification.NotificationListenerService.Ranking;
import android.service.notification.SnoozeCriterion;
import android.service.notification.StatusBarNotification;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.testing.TestableLooper.RunWithLooper;
import android.util.ArraySet;

import androidx.test.filters.SmallTest;

import com.android.systemui.Dependency;
import com.android.systemui.ForegroundServiceController;
import com.android.systemui.InitController;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.statusbar.NotificationEntryBuilder;
import com.android.systemui.statusbar.NotificationTestHelper;
import com.android.systemui.statusbar.RankingBuilder;
import com.android.systemui.statusbar.SbnBuilder;
import com.android.systemui.statusbar.notification.collection.NotificationData.KeyguardEnvironment;
import com.android.systemui.statusbar.notification.row.ExpandableNotificationRow;
import com.android.systemui.statusbar.phone.NotificationGroupManager;
import com.android.systemui.statusbar.phone.ShadeController;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

@SmallTest
@RunWith(AndroidTestingRunner.class)
@RunWithLooper
public class NotificationDataTest extends SysuiTestCase {

    private static final int UID_NORMAL = 123;
    private static final int UID_ALLOW_DURING_SETUP = 456;
    private static final NotificationChannel NOTIFICATION_CHANNEL =
            new NotificationChannel("id", "name", NotificationChannel.USER_LOCKED_IMPORTANCE);

    private NotificationEntry mEntry;

    @Mock
    ForegroundServiceController mFsc;
    @Mock
    NotificationData.KeyguardEnvironment mEnvironment;

    private final IPackageManager mMockPackageManager = mock(IPackageManager.class);
    private TestableNotificationData mNotificationData;
    private ExpandableNotificationRow mRow;

    @Before
    public void setUp() throws Exception {
        com.android.systemui.util.Assert.sMainLooper = TestableLooper.get(this).getLooper();
        MockitoAnnotations.initMocks(this);

        mEntry = new NotificationEntryBuilder()
                .setUid(UID_NORMAL)
                .build();

        when(mMockPackageManager.checkUidPermission(
                eq(Manifest.permission.NOTIFICATION_DURING_SETUP),
                eq(UID_NORMAL)))
                .thenReturn(PackageManager.PERMISSION_DENIED);
        when(mMockPackageManager.checkUidPermission(
                eq(Manifest.permission.NOTIFICATION_DURING_SETUP),
                eq(UID_ALLOW_DURING_SETUP)))
                .thenReturn(PackageManager.PERMISSION_GRANTED);

        mDependency.injectTestDependency(ForegroundServiceController.class, mFsc);
        mDependency.injectTestDependency(NotificationGroupManager.class,
                new NotificationGroupManager(mock(StatusBarStateController.class)));
        mDependency.injectMockDependency(ShadeController.class);
        mDependency.injectTestDependency(KeyguardEnvironment.class, mEnvironment);
        when(mEnvironment.isDeviceProvisioned()).thenReturn(true);
        when(mEnvironment.isNotificationForCurrentProfiles(any())).thenReturn(true);
        mNotificationData = new TestableNotificationData(mContext);
        mNotificationData.updateRanking(mock(NotificationListenerService.RankingMap.class));
        mRow = new NotificationTestHelper(getContext()).createRow();
        Dependency.get(InitController.class).executePostInitTasks();
    }

    @Test
    public void testChannelSetWhenAdded() {
        Bundle override = new Bundle();
        override.putParcelable(OVERRIDE_CHANNEL, NOTIFICATION_CHANNEL);
        mNotificationData.rankingOverrides.put(mRow.getEntry().key, override);
        mNotificationData.add(mRow.getEntry());
        assertEquals(NOTIFICATION_CHANNEL, mRow.getEntry().getChannel());
    }

    @Test
    public void testAllRelevantNotisTaggedWithAppOps() throws Exception {
        mNotificationData.add(mRow.getEntry());
        ExpandableNotificationRow row2 = new NotificationTestHelper(getContext()).createRow();
        mNotificationData.add(row2.getEntry());
        ExpandableNotificationRow diffPkg =
                new NotificationTestHelper(getContext()).createRow("pkg", 4000,
                        Process.myUserHandle());
        mNotificationData.add(diffPkg.getEntry());

        ArraySet<Integer> expectedOps = new ArraySet<>();
        expectedOps.add(OP_CAMERA);
        expectedOps.add(OP_ACCEPT_HANDOVER);

        for (int op : expectedOps) {
            mNotificationData.updateAppOp(op, NotificationTestHelper.UID,
                    NotificationTestHelper.PKG, mRow.getEntry().key, true);
            mNotificationData.updateAppOp(op, NotificationTestHelper.UID,
                    NotificationTestHelper.PKG, row2.getEntry().key, true);
        }
        for (int op : expectedOps) {
            assertTrue(mRow.getEntry().key + " doesn't have op " + op,
                    mNotificationData.get(mRow.getEntry().key).mActiveAppOps.contains(op));
            assertTrue(row2.getEntry().key + " doesn't have op " + op,
                    mNotificationData.get(row2.getEntry().key).mActiveAppOps.contains(op));
            assertFalse(diffPkg.getEntry().key + " has op " + op,
                    mNotificationData.get(diffPkg.getEntry().key).mActiveAppOps.contains(op));
        }
    }

    @Test
    public void testAppOpsRemoval() throws Exception {
        mNotificationData.add(mRow.getEntry());
        ExpandableNotificationRow row2 = new NotificationTestHelper(getContext()).createRow();
        mNotificationData.add(row2.getEntry());

        ArraySet<Integer> expectedOps = new ArraySet<>();
        expectedOps.add(OP_CAMERA);
        expectedOps.add(OP_ACCEPT_HANDOVER);

        for (int op : expectedOps) {
            mNotificationData.updateAppOp(op, NotificationTestHelper.UID,
                    NotificationTestHelper.PKG, row2.getEntry().key, true);
        }

        expectedOps.remove(OP_ACCEPT_HANDOVER);
        mNotificationData.updateAppOp(OP_ACCEPT_HANDOVER, NotificationTestHelper.UID,
                NotificationTestHelper.PKG, row2.getEntry().key, false);

        assertTrue(mRow.getEntry().key + " doesn't have op " + OP_CAMERA,
                mNotificationData.get(mRow.getEntry().key).mActiveAppOps.contains(OP_CAMERA));
        assertTrue(row2.getEntry().key + " doesn't have op " + OP_CAMERA,
                mNotificationData.get(row2.getEntry().key).mActiveAppOps.contains(OP_CAMERA));
        assertFalse(mRow.getEntry().key + " has op " + OP_ACCEPT_HANDOVER,
                mNotificationData.get(mRow.getEntry().key)
                        .mActiveAppOps.contains(OP_ACCEPT_HANDOVER));
        assertFalse(row2.getEntry().key + " has op " + OP_ACCEPT_HANDOVER,
                mNotificationData.get(row2.getEntry().key)
                        .mActiveAppOps.contains(OP_ACCEPT_HANDOVER));
    }

    @Test
    public void testGetNotificationsForCurrentUser_shouldFilterNonCurrentUserNotifications()
            throws Exception {
        mNotificationData.add(mRow.getEntry());
        ExpandableNotificationRow row2 = new NotificationTestHelper(getContext()).createRow();
        mNotificationData.add(row2.getEntry());

        when(mEnvironment.isNotificationForCurrentProfiles(
                mRow.getEntry().notification)).thenReturn(false);
        when(mEnvironment.isNotificationForCurrentProfiles(
                row2.getEntry().notification)).thenReturn(true);
        ArrayList<NotificationEntry> result =
                mNotificationData.getNotificationsForCurrentUser();

        assertEquals(result.size(), 1);
        junit.framework.Assert.assertEquals(result.get(0), row2.getEntry());
    }

    @Test
    public void testIsExemptFromDndVisualSuppression_foreground() {
        initStatusBarNotification(false);

        mEntry.sbn().getNotification().flags = Notification.FLAG_FOREGROUND_SERVICE;
        mEntry.setRow(mRow);
        mNotificationData.add(mEntry);
        Bundle override = new Bundle();
        override.putInt(OVERRIDE_VIS_EFFECTS, 255);
        mNotificationData.rankingOverrides.put(mEntry.key, override);

        assertTrue(mEntry.isExemptFromDndVisualSuppression());
        assertFalse(mEntry.shouldSuppressAmbient());
    }

    @Test
    public void testIsExemptFromDndVisualSuppression_media() {
        initStatusBarNotification(false);
        Notification n = mEntry.sbn().getNotification();
        Notification.Builder nb = Notification.Builder.recoverBuilder(mContext, n);
        nb.setStyle(new Notification.MediaStyle().setMediaSession(mock(MediaSession.Token.class)));
        n = nb.build();
        modifySbn(mEntry)
                .setNotification(n)
                .build();
        mEntry.setRow(mRow);
        mNotificationData.add(mEntry);
        Bundle override = new Bundle();
        override.putInt(OVERRIDE_VIS_EFFECTS, 255);
        mNotificationData.rankingOverrides.put(mEntry.key, override);

        assertTrue(mEntry.isExemptFromDndVisualSuppression());
        assertFalse(mEntry.shouldSuppressAmbient());
    }

    @Test
    public void testIsExemptFromDndVisualSuppression_system() {
        initStatusBarNotification(false);
        mEntry.setRow(mRow);
        mEntry.mIsSystemNotification = true;
        mNotificationData.add(mEntry);
        Bundle override = new Bundle();
        override.putInt(OVERRIDE_VIS_EFFECTS, 255);
        mNotificationData.rankingOverrides.put(mEntry.key, override);

        assertTrue(mEntry.isExemptFromDndVisualSuppression());
        assertFalse(mEntry.shouldSuppressAmbient());
    }

    @Test
    public void testIsNotExemptFromDndVisualSuppression_hiddenCategories() {
        initStatusBarNotification(false);
        NotificationEntry entry = new NotificationEntryBuilder()
                .setUid(UID_NORMAL)
                .build();
        entry.setRow(mRow);
        entry.mIsSystemNotification = true;
        Bundle override = new Bundle();
        override.putInt(OVERRIDE_VIS_EFFECTS, NotificationManager.Policy.SUPPRESSED_EFFECT_AMBIENT);
        mNotificationData.rankingOverrides.put(entry.key, override);
        mNotificationData.add(entry);

        modifySbn(entry)
                .setNotification(
                        new Notification.Builder(mContext, "").setCategory(CATEGORY_CALL).build())
                .build();
        assertFalse(entry.isExemptFromDndVisualSuppression());
        assertTrue(entry.shouldSuppressAmbient());

        modifySbn(entry)
                .setNotification(
                        new Notification.Builder(mContext, "")
                                .setCategory(CATEGORY_REMINDER)
                                .build())
                .build();
        assertFalse(entry.isExemptFromDndVisualSuppression());

        modifySbn(entry)
                .setNotification(
                        new Notification.Builder(mContext, "").setCategory(CATEGORY_ALARM).build())
                .build();
        assertFalse(entry.isExemptFromDndVisualSuppression());

        modifySbn(entry)
                .setNotification(
                        new Notification.Builder(mContext, "").setCategory(CATEGORY_EVENT).build())
                .build();
        assertFalse(entry.isExemptFromDndVisualSuppression());

        modifySbn(entry)
                .setNotification(
                        new Notification.Builder(mContext, "")
                                .setCategory(CATEGORY_MESSAGE)
                                .build())
                .build();
        assertFalse(entry.isExemptFromDndVisualSuppression());
    }

    @Test
    public void testCreateNotificationDataEntry_RankingUpdate() {
        StatusBarNotification sbn = new SbnBuilder().build();
        sbn.getNotification().actions =
                new Notification.Action[] { createContextualAction("appGeneratedAction") };

        ArrayList<Notification.Action> systemGeneratedSmartActions =
                createActions("systemGeneratedAction");

        SnoozeCriterion snoozeCriterion = new SnoozeCriterion("id", "explanation", "confirmation");
        ArrayList<SnoozeCriterion> snoozeCriterions = new ArrayList<>();
        snoozeCriterions.add(snoozeCriterion);

        Ranking ranking = new RankingBuilder()
                .setKey(sbn.getKey())
                .setSmartActions(systemGeneratedSmartActions)
                .setChannel(NOTIFICATION_CHANNEL)
                .setUserSentiment(Ranking.USER_SENTIMENT_NEGATIVE)
                .setSnoozeCriteria(snoozeCriterions)
                .build();

        NotificationEntry entry =
                new NotificationEntry(sbn, ranking);

        assertEquals(systemGeneratedSmartActions, entry.getSmartActions());
        assertEquals(NOTIFICATION_CHANNEL, entry.getChannel());
        assertEquals(Ranking.USER_SENTIMENT_NEGATIVE, entry.getUserSentiment());
        assertEquals(snoozeCriterions, entry.getSnoozeCriteria());
    }

    @Test
    public void notificationDataEntry_testIsLastMessageFromReply() {
        Person.Builder person = new Person.Builder()
                .setName("name")
                .setKey("abc")
                .setUri("uri")
                .setBot(true);

        // EXTRA_MESSAGING_PERSON is the same Person as the sender in last message in EXTRA_MESSAGES
        Bundle bundle = new Bundle();
        bundle.putParcelable(Notification.EXTRA_MESSAGING_PERSON, person.build());
        Bundle[] messagesBundle = new Bundle[]{ new Notification.MessagingStyle.Message(
                "text", 0, person.build()).toBundle() };
        bundle.putParcelableArray(Notification.EXTRA_MESSAGES, messagesBundle);

        Notification notification = new Notification.Builder(mContext, "test")
                .addExtras(bundle)
                .build();

        NotificationEntry entry = new NotificationEntryBuilder()
                .setPkg("pkg")
                .setOpPkg("pkg")
                .setTag("tag")
                .setNotification(notification)
                .setUser(mContext.getUser())
                .setOverrideGroupKey("")
                .build();
        entry.setHasSentReply();

        assertTrue(entry.isLastMessageFromReply());
    }

    @Test
    public void personHighPriority() {
        Person person = new Person.Builder()
                .setName("name")
                .setKey("abc")
                .setUri("uri")
                .setBot(true)
                .build();

        Notification notification = new Notification.Builder(mContext, "test")
                .addPerson(person)
                .build();

        StatusBarNotification sbn = new StatusBarNotification("pkg", "pkg", 0, "tag", 0, 0,
                notification, mContext.getUser(), "", 0);

        assertTrue(mNotificationData.isHighPriority(sbn));
    }

    @Test
    public void messagingStyleHighPriority() {

        Notification notification = new Notification.Builder(mContext, "test")
                .setStyle(new Notification.MessagingStyle(""))
                .build();

        StatusBarNotification sbn = new StatusBarNotification("pkg", "pkg", 0, "tag", 0, 0,
                notification, mContext.getUser(), "", 0);

        assertTrue(mNotificationData.isHighPriority(sbn));
    }

    @Test
    public void minForegroundNotHighPriority() {
        Notification notification = mock(Notification.class);
        when(notification.isForegroundService()).thenReturn(true);

        StatusBarNotification sbn = new StatusBarNotification("pkg", "pkg", 0, "tag", 0, 0,
                notification, mContext.getUser(), "", 0);

        Bundle override = new Bundle();
        override.putInt(OVERRIDE_IMPORTANCE, IMPORTANCE_MIN);
        mNotificationData.rankingOverrides.put(sbn.getKey(), override);

        assertFalse(mNotificationData.isHighPriority(sbn));
    }

    @Test
    public void lowForegroundHighPriority() {
        Notification notification = mock(Notification.class);
        when(notification.isForegroundService()).thenReturn(true);

        StatusBarNotification sbn = new StatusBarNotification("pkg", "pkg", 0, "tag", 0, 0,
                notification, mContext.getUser(), "", 0);

        Bundle override = new Bundle();
        override.putInt(OVERRIDE_IMPORTANCE, IMPORTANCE_LOW);
        mNotificationData.rankingOverrides.put(sbn.getKey(), override);

        assertTrue(mNotificationData.isHighPriority(sbn));
    }

    @Test
    public void userChangeTrumpsHighPriorityCharacteristics() {
        Person person = new Person.Builder()
                .setName("name")
                .setKey("abc")
                .setUri("uri")
                .setBot(true)
                .build();

        Notification notification = new Notification.Builder(mContext, "test")
                .addPerson(person)
                .setStyle(new Notification.MessagingStyle(""))
                .setFlag(Notification.FLAG_FOREGROUND_SERVICE, true)
                .build();

        StatusBarNotification sbn = new StatusBarNotification("pkg", "pkg", 0, "tag", 0, 0,
                notification, mContext.getUser(), "", 0);

        NotificationChannel channel = new NotificationChannel("a", "a", IMPORTANCE_LOW);
        channel.lockFields(NotificationChannel.USER_LOCKED_IMPORTANCE);

        Bundle override = new Bundle();
        override.putParcelable(OVERRIDE_CHANNEL, channel);
        mNotificationData.rankingOverrides.put(sbn.getKey(), override);

        assertFalse(mNotificationData.isHighPriority(sbn));
    }

    @Test
    public void testSort_highPriorityTrumpsNMSRank() {
        // NMS rank says A and then B. But A is not high priority and B is, so B should sort in
        // front
        Notification aN = new Notification.Builder(mContext, "test")
                .setStyle(new Notification.MessagingStyle(""))
                .build();
        NotificationEntry a = new NotificationEntryBuilder()
                .setPkg("pkg")
                .setOpPkg("pkg")
                .setTag("tag")
                .setNotification(aN)
                .setUser(mContext.getUser())
                .setOverrideGroupKey("")
                .build();
        a.setRow(mock(ExpandableNotificationRow.class));
        a.setIsHighPriority(false);

        Bundle override = new Bundle();
        override.putInt(OVERRIDE_IMPORTANCE, IMPORTANCE_LOW);
        override.putInt(OVERRIDE_RANK, 1);
        mNotificationData.rankingOverrides.put(a.key, override);

        Notification bN = new Notification.Builder(mContext, "test")
                .setStyle(new Notification.MessagingStyle(""))
                .build();
        NotificationEntry b = new NotificationEntryBuilder()
                .setPkg("pkg2")
                .setOpPkg("pkg2")
                .setTag("tag")
                .setNotification(bN)
                .setUser(mContext.getUser())
                .setOverrideGroupKey("")
                .build();
        b.setIsHighPriority(true);
        b.setRow(mock(ExpandableNotificationRow.class));

        Bundle bOverride = new Bundle();
        bOverride.putInt(OVERRIDE_IMPORTANCE, IMPORTANCE_LOW);
        bOverride.putInt(OVERRIDE_RANK, 2);
        mNotificationData.rankingOverrides.put(b.key, bOverride);

        assertEquals(1, mNotificationData.mRankingComparator.compare(a, b));
    }

    @Test
    public void testSort_samePriorityUsesNMSRank() {
        // NMS rank says A and then B, and they are the same priority so use that rank
        Notification aN = new Notification.Builder(mContext, "test")
                .setStyle(new Notification.MessagingStyle(""))
                .build();
        NotificationEntry a = new NotificationEntryBuilder()
                .setPkg("pkg")
                .setOpPkg("pkg")
                .setTag("tag")
                .setNotification(aN)
                .setUser(mContext.getUser())
                .setOverrideGroupKey("")
                .build();
        a.setRow(mock(ExpandableNotificationRow.class));
        a.setIsHighPriority(false);

        Bundle override = new Bundle();
        override.putInt(OVERRIDE_IMPORTANCE, IMPORTANCE_LOW);
        override.putInt(OVERRIDE_RANK, 1);
        mNotificationData.rankingOverrides.put(a.key, override);

        Notification bN = new Notification.Builder(mContext, "test")
                .setStyle(new Notification.MessagingStyle(""))
                .build();
        NotificationEntry b = new NotificationEntryBuilder()
                .setPkg("pkg2")
                .setOpPkg("pkg2")
                .setTag("tag")
                .setNotification(bN)
                .setUser(mContext.getUser())
                .setOverrideGroupKey("")
                .build();
        b.setRow(mock(ExpandableNotificationRow.class));
        b.setIsHighPriority(false);

        Bundle bOverride = new Bundle();
        bOverride.putInt(OVERRIDE_IMPORTANCE, IMPORTANCE_LOW);
        bOverride.putInt(OVERRIDE_RANK, 2);
        mNotificationData.rankingOverrides.put(b.key, bOverride);

        assertEquals(-1, mNotificationData.mRankingComparator.compare(a, b));
    }

    @Test
    public void testSort_properlySetsAlertingBucket() {
        Notification notification = new Notification.Builder(mContext, "test")
                .build();
        NotificationEntry entry = new NotificationEntryBuilder()
                .setPkg("pkg")
                .setOpPkg("pkg")
                .setTag("tag")
                .setNotification(notification)
                .setUser(mContext.getUser())
                .setOverrideGroupKey("")
                .build();

        Bundle override = new Bundle();
        override.putInt(OVERRIDE_IMPORTANCE, IMPORTANCE_DEFAULT);
        mNotificationData.rankingOverrides.put(entry.key(), override);

        entry.setRow(mRow);
        mNotificationData.add(entry);

        assertEquals(entry.getBucket(), BUCKET_ALERTING);
    }

    @Test
    public void testSort_properlySetsSilentBucket() {
        Notification notification = new Notification.Builder(mContext, "test")
                .build();

        NotificationEntry entry = new NotificationEntryBuilder()
                .setPkg("pkg")
                .setOpPkg("pkg")
                .setTag("tag")
                .setNotification(notification)
                .setUser(mContext.getUser())
                .setOverrideGroupKey("")
                .build();

        Bundle override = new Bundle();
        override.putInt(OVERRIDE_IMPORTANCE, IMPORTANCE_LOW);
        mNotificationData.rankingOverrides.put(entry.key(), override);

        entry.setRow(mRow);
        mNotificationData.add(entry);

        assertEquals(entry.getBucket(), BUCKET_SILENT);
    }

    private void initStatusBarNotification(boolean allowDuringSetup) {
        Bundle bundle = new Bundle();
        bundle.putBoolean(Notification.EXTRA_ALLOW_DURING_SETUP, allowDuringSetup);
        Notification notification = new Notification.Builder(mContext, "test")
                .addExtras(bundle)
                .build();
        modifySbn(mEntry)
                .setNotification(notification)
                .build();
    }

    public static class TestableNotificationData extends NotificationData {
        public TestableNotificationData(Context context) {
            super(context);
        }

        public static final String OVERRIDE_RANK = "r";
        public static final String OVERRIDE_DND = "dnd";
        public static final String OVERRIDE_VIS_OVERRIDE = "vo";
        public static final String OVERRIDE_VIS_EFFECTS = "ve";
        public static final String OVERRIDE_IMPORTANCE = "i";
        public static final String OVERRIDE_IMP_EXP = "ie";
        public static final String OVERRIDE_GROUP = "g";
        public static final String OVERRIDE_CHANNEL = "c";
        public static final String OVERRIDE_PEOPLE = "p";
        public static final String OVERRIDE_SNOOZE_CRITERIA = "sc";
        public static final String OVERRIDE_BADGE = "b";
        public static final String OVERRIDE_USER_SENTIMENT = "us";
        public static final String OVERRIDE_HIDDEN = "h";
        public static final String OVERRIDE_LAST_ALERTED = "la";
        public static final String OVERRIDE_NOISY = "n";
        public static final String OVERRIDE_SMART_ACTIONS = "sa";
        public static final String OVERRIDE_SMART_REPLIES = "sr";
        public static final String OVERRIDE_BUBBLE = "cb";
        public static final String OVERRIDE_VISUALLY_INTERRUPTIVE = "vi";

        public Map<String, Bundle> rankingOverrides = new HashMap<>();

        @Override
        protected boolean getRanking(String key, Ranking outRanking) {
            super.getRanking(key, outRanking);

            ArrayList<String> currentAdditionalPeople = new ArrayList<>();
            if (outRanking.getAdditionalPeople() != null) {
                currentAdditionalPeople.addAll(outRanking.getAdditionalPeople());
            }

            ArrayList<SnoozeCriterion> currentSnooze = new ArrayList<>();
            if (outRanking.getSnoozeCriteria() != null) {
                currentSnooze.addAll(outRanking.getSnoozeCriteria());
            }

            ArrayList<Notification.Action> currentActions = new ArrayList<>();
            if (outRanking.getSmartActions() != null) {
                currentActions.addAll(outRanking.getSmartActions());
            }

            ArrayList<CharSequence> currentReplies = new ArrayList<>();
            if (outRanking.getSmartReplies() != null) {
                currentReplies.addAll(outRanking.getSmartReplies());
            }

            if (rankingOverrides.get(key) != null) {
                Bundle overrides = rankingOverrides.get(key);
                outRanking.populate(key,
                        overrides.getInt(OVERRIDE_RANK, outRanking.getRank()),
                        overrides.getBoolean(OVERRIDE_DND, outRanking.matchesInterruptionFilter()),
                        overrides.getInt(OVERRIDE_VIS_OVERRIDE, outRanking.getVisibilityOverride()),
                        overrides.getInt(OVERRIDE_VIS_EFFECTS,
                                outRanking.getSuppressedVisualEffects()),
                        overrides.getInt(OVERRIDE_IMPORTANCE, outRanking.getImportance()),
                        overrides.getCharSequence(OVERRIDE_IMP_EXP,
                                outRanking.getImportanceExplanation()),
                        overrides.getString(OVERRIDE_GROUP, outRanking.getOverrideGroupKey()),
                        overrides.containsKey(OVERRIDE_CHANNEL)
                                ? (NotificationChannel) overrides.getParcelable(OVERRIDE_CHANNEL)
                                : outRanking.getChannel(),
                        overrides.containsKey(OVERRIDE_PEOPLE)
                                ? overrides.getStringArrayList(OVERRIDE_PEOPLE)
                                : currentAdditionalPeople,
                        overrides.containsKey(OVERRIDE_SNOOZE_CRITERIA)
                                ? overrides.getParcelableArrayList(OVERRIDE_SNOOZE_CRITERIA)
                                : currentSnooze,
                        overrides.getBoolean(OVERRIDE_BADGE, outRanking.canShowBadge()),
                        overrides.getInt(OVERRIDE_USER_SENTIMENT, outRanking.getUserSentiment()),
                        overrides.getBoolean(OVERRIDE_HIDDEN, outRanking.isSuspended()),
                        overrides.getLong(OVERRIDE_LAST_ALERTED,
                                outRanking.getLastAudiblyAlertedMillis()),
                        overrides.getBoolean(OVERRIDE_NOISY, outRanking.isNoisy()),
                        overrides.containsKey(OVERRIDE_SMART_ACTIONS)
                                ? overrides.getParcelableArrayList(OVERRIDE_SMART_ACTIONS)
                                : currentActions,
                        overrides.containsKey(OVERRIDE_SMART_REPLIES)
                                ? overrides.getCharSequenceArrayList(OVERRIDE_SMART_REPLIES)
                                : currentReplies,
                        overrides.getBoolean(OVERRIDE_BUBBLE, outRanking.canBubble()),
                        overrides.getBoolean(OVERRIDE_VISUALLY_INTERRUPTIVE,
                                outRanking.visuallyInterruptive()));
            } else {
                outRanking.populate(
                        new RankingBuilder()
                                .setKey(key)
                                .build());
            }
            return true;
        }
    }

    private Notification.Action createContextualAction(String title) {
        return new Notification.Action.Builder(
                Icon.createWithResource(getContext(), android.R.drawable.sym_def_app_icon),
                title,
                PendingIntent.getBroadcast(getContext(), 0, new Intent("Action"), 0))
                        .setContextual(true)
                        .build();
    }

    private Notification.Action createAction(String title) {
        return new Notification.Action.Builder(
                Icon.createWithResource(getContext(), android.R.drawable.sym_def_app_icon),
                title,
                PendingIntent.getBroadcast(getContext(), 0, new Intent("Action"), 0)).build();
    }

    private ArrayList<Notification.Action> createActions(String... titles) {
        ArrayList<Notification.Action> actions = new ArrayList<>();
        for (String title : titles) {
            actions.add(createAction(title));
        }
        return actions;
    }
}
