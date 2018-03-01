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
    public void testAdd_appOpsAdded() {
        ArraySet<Integer> expected = new ArraySet<>();
        expected.add(3);
        expected.add(235);
        expected.add(1);
        when(mFsc.getAppOps(mRow.getEntry().notification.getUserId(),
                mRow.getEntry().notification.getPackageName())).thenReturn(expected);

        mNotificationData.add(mRow.getEntry());
        assertEquals(expected.size(),
                mNotificationData.get(mRow.getEntry().key).mActiveAppOps.size());
        for (int op : expected) {
            assertTrue(" entry missing op " + op,
                    mNotificationData.get(mRow.getEntry().key).mActiveAppOps.contains(op));
        }
    }

    @Test
    public void testAdd_noExistingAppOps() {
        when(mFsc.getAppOps(mRow.getEntry().notification.getUserId(),
                mRow.getEntry().notification.getPackageName())).thenReturn(null);

        mNotificationData.add(mRow.getEntry());
        assertEquals(0, mNotificationData.get(mRow.getEntry().key).mActiveAppOps.size());
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
                    NotificationTestHelper.PKG, true);
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
                    NotificationTestHelper.PKG, true);
        }

        expectedOps.remove(OP_ACCEPT_HANDOVER);
        mNotificationData.updateAppOp(OP_ACCEPT_HANDOVER, NotificationTestHelper.UID,
                NotificationTestHelper.PKG, false);

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

        assertTrue(mNotificationData.shouldFilterOut(mRow.getEntry().notification));
    }

    @Test
    public void testDoNotSuppressSystemAlertNotification() {
        when(mFsc.isSystemAlertWarningNeeded(anyInt(), anyString())).thenReturn(true);
        when(mFsc.isSystemAlertNotification(any())).thenReturn(true);

        assertFalse(mNotificationData.shouldFilterOut(mRow.getEntry().notification));

        when(mFsc.isSystemAlertWarningNeeded(anyInt(), anyString())).thenReturn(true);
        when(mFsc.isSystemAlertNotification(any())).thenReturn(false);

        assertFalse(mNotificationData.shouldFilterOut(mRow.getEntry().notification));

        when(mFsc.isSystemAlertWarningNeeded(anyInt(), anyString())).thenReturn(false);
        when(mFsc.isSystemAlertNotification(any())).thenReturn(false);

        assertFalse(mNotificationData.shouldFilterOut(mRow.getEntry().notification));
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
            return true;
        }
    }
}
