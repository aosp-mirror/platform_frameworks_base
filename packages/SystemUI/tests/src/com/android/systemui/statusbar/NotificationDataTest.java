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
 * limitations under the License.
 */

package com.android.systemui.statusbar;

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
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.content.pm.IPackageManager;
import android.content.pm.PackageManager;
import android.media.session.MediaSession;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.support.test.annotation.UiThreadTest;
import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;
import android.util.ArraySet;

import com.android.systemui.ForegroundServiceController;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.statusbar.phone.NotificationGroupManager;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class NotificationDataTest extends SysuiTestCase {

    private static final int UID_NORMAL = 123;
    private static final int UID_ALLOW_DURING_SETUP = 456;
    private static final String TEST_HIDDEN_NOTIFICATION_KEY = "testHiddenNotificationKey";
    private static final String TEST_EXEMPT_DND_VISUAL_SUPPRESSION_KEY = "exempt";

    private final StatusBarNotification mMockStatusBarNotification =
            mock(StatusBarNotification.class);
    @Mock
    ForegroundServiceController mFsc;
    @Mock
    NotificationData.Environment mEnvironment;

    private final IPackageManager mMockPackageManager = mock(IPackageManager.class);
    private NotificationData mNotificationData;
    private ExpandableNotificationRow mRow;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        when(mMockStatusBarNotification.getUid()).thenReturn(UID_NORMAL);

        when(mMockPackageManager.checkUidPermission(
                eq(Manifest.permission.NOTIFICATION_DURING_SETUP),
                eq(UID_NORMAL)))
                .thenReturn(PackageManager.PERMISSION_DENIED);
        when(mMockPackageManager.checkUidPermission(
                eq(Manifest.permission.NOTIFICATION_DURING_SETUP),
                eq(UID_ALLOW_DURING_SETUP)))
                .thenReturn(PackageManager.PERMISSION_GRANTED);

        mDependency.injectTestDependency(ForegroundServiceController.class, mFsc);
        when(mEnvironment.getGroupManager()).thenReturn(new NotificationGroupManager());
        when(mEnvironment.isDeviceProvisioned()).thenReturn(true);
        when(mEnvironment.isNotificationForCurrentProfiles(any())).thenReturn(true);
        mNotificationData = new TestableNotificationData(mEnvironment);
        mNotificationData.updateRanking(mock(NotificationListenerService.RankingMap.class));
        mRow = new NotificationTestHelper(getContext()).createRow();
    }

    @Test
    @UiThreadTest
    public void testShowNotificationEvenIfUnprovisioned_FalseIfNoExtra() {
        initStatusBarNotification(false);
        when(mMockStatusBarNotification.getUid()).thenReturn(UID_ALLOW_DURING_SETUP);

        assertFalse(
                NotificationData.showNotificationEvenIfUnprovisioned(
                        mMockPackageManager,
                        mMockStatusBarNotification));
    }

    @Test
    @UiThreadTest
    public void testShowNotificationEvenIfUnprovisioned_FalseIfNoPermission() {
        initStatusBarNotification(true);

        assertFalse(
                NotificationData.showNotificationEvenIfUnprovisioned(
                        mMockPackageManager,
                        mMockStatusBarNotification));
    }

    @Test
    @UiThreadTest
    public void testShowNotificationEvenIfUnprovisioned_TrueIfHasPermissionAndExtra() {
        initStatusBarNotification(true);
        when(mMockStatusBarNotification.getUid()).thenReturn(UID_ALLOW_DURING_SETUP);

        assertTrue(
                NotificationData.showNotificationEvenIfUnprovisioned(
                        mMockPackageManager,
                        mMockStatusBarNotification));
    }

    @Test
    public void testChannelSetWhenAdded() {
        mNotificationData.add(mRow.getEntry());
        Assert.assertTrue(mRow.getEntry().channel != null);
    }



    @Test
    public void testAllRelevantNotisTaggedWithAppOps() throws Exception {
        mNotificationData.add(mRow.getEntry());
        ExpandableNotificationRow row2 = new NotificationTestHelper(getContext()).createRow();
        mNotificationData.add(row2.getEntry());
        ExpandableNotificationRow diffPkg =
                new NotificationTestHelper(getContext()).createRow("pkg", 4000);
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
    public void testSuppressSystemAlertNotification() {
        when(mFsc.isSystemAlertWarningNeeded(anyInt(), anyString())).thenReturn(false);
        when(mFsc.isSystemAlertNotification(any())).thenReturn(true);
        StatusBarNotification sbn = mRow.getEntry().notification;
        Bundle bundle = new Bundle();
        bundle.putStringArray(Notification.EXTRA_FOREGROUND_APPS, new String[] {"something"});
        sbn.getNotification().extras = bundle;

        assertTrue(mNotificationData.shouldFilterOut(mRow.getEntry()));
    }

    @Test
    public void testDoNotSuppressSystemAlertNotification() {
        StatusBarNotification sbn = mRow.getEntry().notification;
        Bundle bundle = new Bundle();
        bundle.putStringArray(Notification.EXTRA_FOREGROUND_APPS, new String[] {"something"});
        sbn.getNotification().extras = bundle;

        when(mFsc.isSystemAlertWarningNeeded(anyInt(), anyString())).thenReturn(true);
        when(mFsc.isSystemAlertNotification(any())).thenReturn(true);

        assertFalse(mNotificationData.shouldFilterOut(mRow.getEntry()));

        when(mFsc.isSystemAlertWarningNeeded(anyInt(), anyString())).thenReturn(true);
        when(mFsc.isSystemAlertNotification(any())).thenReturn(false);

        assertFalse(mNotificationData.shouldFilterOut(mRow.getEntry()));

        when(mFsc.isSystemAlertWarningNeeded(anyInt(), anyString())).thenReturn(false);
        when(mFsc.isSystemAlertNotification(any())).thenReturn(false);

        assertFalse(mNotificationData.shouldFilterOut(mRow.getEntry()));
    }

    @Test
    public void testDoNotSuppressMalformedSystemAlertNotification() {
        when(mFsc.isSystemAlertWarningNeeded(anyInt(), anyString())).thenReturn(true);

        // missing extra
        assertFalse(mNotificationData.shouldFilterOut(mRow.getEntry()));

        StatusBarNotification sbn = mRow.getEntry().notification;
        Bundle bundle = new Bundle();
        bundle.putStringArray(Notification.EXTRA_FOREGROUND_APPS, new String[] {});
        sbn.getNotification().extras = bundle;

        // extra missing values
        assertFalse(mNotificationData.shouldFilterOut(mRow.getEntry()));
    }

    @Test
    public void testShouldFilterHiddenNotifications() {
        initStatusBarNotification(false);
        // setup
        when(mFsc.isSystemAlertWarningNeeded(anyInt(), anyString())).thenReturn(false);
        when(mFsc.isSystemAlertNotification(any())).thenReturn(false);

        // test should filter out hidden notifications:
        // hidden
        when(mMockStatusBarNotification.getKey()).thenReturn(TEST_HIDDEN_NOTIFICATION_KEY);
        NotificationData.Entry entry = new NotificationData.Entry(mMockStatusBarNotification);
        assertTrue(mNotificationData.shouldFilterOut(entry));

        // not hidden
        when(mMockStatusBarNotification.getKey()).thenReturn("not hidden");
        entry = new NotificationData.Entry(mMockStatusBarNotification);
        assertFalse(mNotificationData.shouldFilterOut(entry));
    }

    @Test
    public void testIsExemptFromDndVisualSuppression_foreground() {
        initStatusBarNotification(false);
        when(mMockStatusBarNotification.getKey()).thenReturn(
                TEST_EXEMPT_DND_VISUAL_SUPPRESSION_KEY);
        Notification n = mMockStatusBarNotification.getNotification();
        n.flags = Notification.FLAG_FOREGROUND_SERVICE;
        NotificationData.Entry entry = new NotificationData.Entry(mMockStatusBarNotification);

        assertTrue(mNotificationData.isExemptFromDndVisualSuppression(entry));
        assertFalse(mNotificationData.shouldSuppressAmbient(entry));
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
        NotificationData.Entry entry = new NotificationData.Entry(mMockStatusBarNotification);

        assertTrue(mNotificationData.isExemptFromDndVisualSuppression(entry));
        assertFalse(mNotificationData.shouldSuppressAmbient(entry));
    }

    @Test
    public void testIsExemptFromDndVisualSuppression_system() {
        initStatusBarNotification(false);
        when(mMockStatusBarNotification.getKey()).thenReturn(
                TEST_EXEMPT_DND_VISUAL_SUPPRESSION_KEY);
        NotificationData.Entry entry = new NotificationData.Entry(mMockStatusBarNotification);
        entry.mIsSystemNotification = true;

        assertTrue(mNotificationData.isExemptFromDndVisualSuppression(entry));
        assertFalse(mNotificationData.shouldSuppressAmbient(entry));
    }

    @Test
    public void testIsNotExemptFromDndVisualSuppression_hiddenCategories() {
        initStatusBarNotification(false);
        when(mMockStatusBarNotification.getKey()).thenReturn(
                TEST_EXEMPT_DND_VISUAL_SUPPRESSION_KEY);
        NotificationData.Entry entry = new NotificationData.Entry(mMockStatusBarNotification);
        entry.mIsSystemNotification = true;
        when(mMockStatusBarNotification.getNotification()).thenReturn(
                new Notification.Builder(mContext, "").setCategory(CATEGORY_CALL).build());

        assertFalse(mNotificationData.isExemptFromDndVisualSuppression(entry));
        assertTrue(mNotificationData.shouldSuppressAmbient(entry));

        when(mMockStatusBarNotification.getNotification()).thenReturn(
                new Notification.Builder(mContext, "").setCategory(CATEGORY_REMINDER).build());

        assertFalse(mNotificationData.isExemptFromDndVisualSuppression(entry));

        when(mMockStatusBarNotification.getNotification()).thenReturn(
                new Notification.Builder(mContext, "").setCategory(CATEGORY_ALARM).build());

        assertFalse(mNotificationData.isExemptFromDndVisualSuppression(entry));

        when(mMockStatusBarNotification.getNotification()).thenReturn(
                new Notification.Builder(mContext, "").setCategory(CATEGORY_EVENT).build());

        assertFalse(mNotificationData.isExemptFromDndVisualSuppression(entry));

        when(mMockStatusBarNotification.getNotification()).thenReturn(
                new Notification.Builder(mContext, "").setCategory(CATEGORY_MESSAGE).build());

        assertFalse(mNotificationData.isExemptFromDndVisualSuppression(entry));
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
        public TestableNotificationData(Environment environment) {
            super(environment);
        }

        @Override
        public NotificationChannel getChannel(String key) {
            return new NotificationChannel(null, null, 0);
        }

        @Override
        protected boolean getRanking(String key, NotificationListenerService.Ranking outRanking) {
            super.getRanking(key, outRanking);
            if (key.equals(TEST_HIDDEN_NOTIFICATION_KEY)) {
                outRanking.populate(key, outRanking.getRank(),
                        outRanking.matchesInterruptionFilter(),
                        outRanking.getVisibilityOverride(), outRanking.getSuppressedVisualEffects(),
                        outRanking.getImportance(), outRanking.getImportanceExplanation(),
                        outRanking.getOverrideGroupKey(), outRanking.getChannel(), null, null,
                        outRanking.canShowBadge(), outRanking.getUserSentiment(), true);
            } else if (key.equals(TEST_EXEMPT_DND_VISUAL_SUPPRESSION_KEY)) {
                outRanking.populate(key, outRanking.getRank(),
                        outRanking.matchesInterruptionFilter(),
                        outRanking.getVisibilityOverride(), 255,
                        outRanking.getImportance(), outRanking.getImportanceExplanation(),
                        outRanking.getOverrideGroupKey(), outRanking.getChannel(), null, null,
                        outRanking.canShowBadge(), outRanking.getUserSentiment(), true);
            } else {
                outRanking.populate(key, outRanking.getRank(),
                        outRanking.matchesInterruptionFilter(),
                        outRanking.getVisibilityOverride(), outRanking.getSuppressedVisualEffects(),
                        outRanking.getImportance(), outRanking.getImportanceExplanation(),
                        outRanking.getOverrideGroupKey(), outRanking.getChannel(), null, null,
                        outRanking.canShowBadge(), outRanking.getUserSentiment(), false);
            }
            return true;
        }
    }
}
