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
import android.app.PendingIntent;
import android.app.Person;
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
import android.support.test.filters.SmallTest;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.testing.TestableLooper.RunWithLooper;
import android.util.ArraySet;

import com.android.systemui.Dependency;
import com.android.systemui.ForegroundServiceController;
import com.android.systemui.InitController;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.statusbar.NotificationTestHelper;
import com.android.systemui.statusbar.notification.collection.NotificationData;
import com.android.systemui.statusbar.notification.collection.NotificationData.KeyguardEnvironment;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.notification.row.ExpandableNotificationRow;
import com.android.systemui.statusbar.phone.NotificationGroupManager;
import com.android.systemui.statusbar.phone.ShadeController;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@SmallTest
@RunWith(AndroidTestingRunner.class)
@RunWithLooper
public class NotificationDataTest extends SysuiTestCase {

    private static final int UID_NORMAL = 123;
    private static final int UID_ALLOW_DURING_SETUP = 456;
    private static final String TEST_HIDDEN_NOTIFICATION_KEY = "testHiddenNotificationKey";
    private static final String TEST_EXEMPT_DND_VISUAL_SUPPRESSION_KEY = "exempt";
    private static final NotificationChannel NOTIFICATION_CHANNEL =
            new NotificationChannel("id", "name", NotificationChannel.USER_LOCKED_IMPORTANCE);

    private final StatusBarNotification mMockStatusBarNotification =
            mock(StatusBarNotification.class);
    @Mock
    ForegroundServiceController mFsc;
    @Mock
    NotificationData.KeyguardEnvironment mEnvironment;

    private final IPackageManager mMockPackageManager = mock(IPackageManager.class);
    private NotificationData mNotificationData;
    private ExpandableNotificationRow mRow;

    @Before
    public void setUp() throws Exception {
        com.android.systemui.util.Assert.sMainLooper = TestableLooper.get(this).getLooper();
        MockitoAnnotations.initMocks(this);
        when(mMockStatusBarNotification.getUid()).thenReturn(UID_NORMAL);
        when(mMockStatusBarNotification.cloneLight()).thenReturn(mMockStatusBarNotification);

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
                new NotificationGroupManager());
        mDependency.injectMockDependency(ShadeController.class);
        mDependency.injectTestDependency(KeyguardEnvironment.class, mEnvironment);
        when(mEnvironment.isDeviceProvisioned()).thenReturn(true);
        when(mEnvironment.isNotificationForCurrentProfiles(any())).thenReturn(true);
        mNotificationData = new TestableNotificationData();
        mNotificationData.updateRanking(mock(NotificationListenerService.RankingMap.class));
        mRow = new NotificationTestHelper(getContext()).createRow();
        Dependency.get(InitController.class).executePostInitTasks();
    }

    @Test
    public void testChannelSetWhenAdded() {
        mNotificationData.add(mRow.getEntry());
        assertEquals(NOTIFICATION_CHANNEL, mRow.getEntry().channel);
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
        when(mMockStatusBarNotification.getKey()).thenReturn(
                TEST_EXEMPT_DND_VISUAL_SUPPRESSION_KEY);
        Notification n = mMockStatusBarNotification.getNotification();
        n.flags = Notification.FLAG_FOREGROUND_SERVICE;
        NotificationEntry entry = new NotificationEntry(mMockStatusBarNotification);
        mNotificationData.add(entry);

        assertTrue(entry.isExemptFromDndVisualSuppression());
        assertFalse(entry.shouldSuppressAmbient());
    }

    @Test
    public void testIsExemptFromDndVisualSuppression_media() {
        initStatusBarNotification(false);
        when(mMockStatusBarNotification.getKey()).thenReturn(
                TEST_EXEMPT_DND_VISUAL_SUPPRESSION_KEY);
        Notification n = mMockStatusBarNotification.getNotification();
        Notification.Builder nb = Notification.Builder.recoverBuilder(mContext, n);
        nb.setStyle(new Notification.MediaStyle().setMediaSession(mock(MediaSession.Token.class)));
        n = nb.build();
        when(mMockStatusBarNotification.getNotification()).thenReturn(n);
        NotificationEntry entry = new NotificationEntry(mMockStatusBarNotification);
        mNotificationData.add(entry);

        assertTrue(entry.isExemptFromDndVisualSuppression());
        assertFalse(entry.shouldSuppressAmbient());
    }

    @Test
    public void testIsExemptFromDndVisualSuppression_system() {
        initStatusBarNotification(false);
        when(mMockStatusBarNotification.getKey()).thenReturn(
                TEST_EXEMPT_DND_VISUAL_SUPPRESSION_KEY);
        NotificationEntry entry = new NotificationEntry(mMockStatusBarNotification);
        entry.mIsSystemNotification = true;
        mNotificationData.add(entry);

        assertTrue(entry.isExemptFromDndVisualSuppression());
        assertFalse(entry.shouldSuppressAmbient());
    }

    @Test
    public void testIsNotExemptFromDndVisualSuppression_hiddenCategories() {
        initStatusBarNotification(false);
        when(mMockStatusBarNotification.getKey()).thenReturn(
                TEST_EXEMPT_DND_VISUAL_SUPPRESSION_KEY);
        NotificationEntry entry = new NotificationEntry(mMockStatusBarNotification);
        entry.mIsSystemNotification = true;
        mNotificationData.add(entry);

        when(mMockStatusBarNotification.getNotification()).thenReturn(
                new Notification.Builder(mContext, "").setCategory(CATEGORY_CALL).build());

        assertFalse(entry.isExemptFromDndVisualSuppression());
        assertTrue(entry.shouldSuppressAmbient());

        when(mMockStatusBarNotification.getNotification()).thenReturn(
                new Notification.Builder(mContext, "").setCategory(CATEGORY_REMINDER).build());

        assertFalse(entry.isExemptFromDndVisualSuppression());

        when(mMockStatusBarNotification.getNotification()).thenReturn(
                new Notification.Builder(mContext, "").setCategory(CATEGORY_ALARM).build());

        assertFalse(entry.isExemptFromDndVisualSuppression());

        when(mMockStatusBarNotification.getNotification()).thenReturn(
                new Notification.Builder(mContext, "").setCategory(CATEGORY_EVENT).build());

        assertFalse(entry.isExemptFromDndVisualSuppression());

        when(mMockStatusBarNotification.getNotification()).thenReturn(
                new Notification.Builder(mContext, "").setCategory(CATEGORY_MESSAGE).build());

        assertFalse(entry.isExemptFromDndVisualSuppression());
    }

    @Test
    public void testCreateNotificationDataEntry_RankingUpdate() {
        Ranking ranking = mock(Ranking.class);
        initStatusBarNotification(false);

        List<Notification.Action> appGeneratedSmartActions =
                Collections.singletonList(createContextualAction("appGeneratedAction"));
        mMockStatusBarNotification.getNotification().actions =
                appGeneratedSmartActions.toArray(new Notification.Action[0]);

        List<Notification.Action> systemGeneratedSmartActions =
                Collections.singletonList(createAction("systemGeneratedAction"));
        when(ranking.getSmartActions()).thenReturn(systemGeneratedSmartActions);

        when(ranking.getChannel()).thenReturn(NOTIFICATION_CHANNEL);

        when(ranking.getUserSentiment()).thenReturn(Ranking.USER_SENTIMENT_NEGATIVE);

        SnoozeCriterion snoozeCriterion = new SnoozeCriterion("id", "explanation", "confirmation");
        ArrayList<SnoozeCriterion> snoozeCriterions = new ArrayList<>();
        snoozeCriterions.add(snoozeCriterion);
        when(ranking.getSnoozeCriteria()).thenReturn(snoozeCriterions);

        NotificationEntry entry =
                new NotificationEntry(mMockStatusBarNotification, ranking);

        assertEquals(systemGeneratedSmartActions, entry.systemGeneratedSmartActions);
        assertEquals(NOTIFICATION_CHANNEL, entry.channel);
        assertEquals(Ranking.USER_SENTIMENT_NEGATIVE, entry.userSentiment);
        assertEquals(snoozeCriterions, entry.snoozeCriteria);
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
        StatusBarNotification sbn = new StatusBarNotification("pkg", "pkg", 0, "tag", 0, 0,
                notification, mContext.getUser(), "", 0);

        NotificationEntry entry = new NotificationEntry(sbn);
        entry.setHasSentReply();

        assertTrue(entry.isLastMessageFromReply());
    }

    private void initStatusBarNotification(boolean allowDuringSetup) {
        Bundle bundle = new Bundle();
        bundle.putBoolean(Notification.EXTRA_ALLOW_DURING_SETUP, allowDuringSetup);
        Notification notification = new Notification.Builder(mContext, "test")
                .addExtras(bundle)
                .build();
        when(mMockStatusBarNotification.getNotification()).thenReturn(notification);
    }

    private class TestableNotificationData extends NotificationData {
        public TestableNotificationData() {
            super();
        }

        @Override
        protected boolean getRanking(String key, Ranking outRanking) {
            super.getRanking(key, outRanking);
            if (key.equals(TEST_HIDDEN_NOTIFICATION_KEY)) {
                outRanking.populate(key, outRanking.getRank(),
                        outRanking.matchesInterruptionFilter(),
                        outRanking.getVisibilityOverride(), outRanking.getSuppressedVisualEffects(),
                        outRanking.getImportance(), outRanking.getImportanceExplanation(),
                        outRanking.getOverrideGroupKey(), outRanking.getChannel(), null, null,
                        outRanking.canShowBadge(), outRanking.getUserSentiment(), true,
                        -1, false, null, null);
            } else if (key.equals(TEST_EXEMPT_DND_VISUAL_SUPPRESSION_KEY)) {
                outRanking.populate(key, outRanking.getRank(),
                        outRanking.matchesInterruptionFilter(),
                        outRanking.getVisibilityOverride(), 255,
                        outRanking.getImportance(), outRanking.getImportanceExplanation(),
                        outRanking.getOverrideGroupKey(), outRanking.getChannel(), null, null,
                        outRanking.canShowBadge(), outRanking.getUserSentiment(), true, -1,
                        false, null, null);
            } else {
                outRanking.populate(key, outRanking.getRank(),
                        outRanking.matchesInterruptionFilter(),
                        outRanking.getVisibilityOverride(), outRanking.getSuppressedVisualEffects(),
                        outRanking.getImportance(), outRanking.getImportanceExplanation(),
                        outRanking.getOverrideGroupKey(), NOTIFICATION_CHANNEL, null, null,
                        outRanking.canShowBadge(), outRanking.getUserSentiment(), false, -1,
                        false, null, null);
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
}
