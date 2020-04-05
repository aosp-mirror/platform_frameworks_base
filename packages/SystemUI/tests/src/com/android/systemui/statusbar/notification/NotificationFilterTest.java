/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.systemui.statusbar.notification;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.Manifest;
import android.app.Notification;
import android.content.pm.IPackageManager;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.service.notification.StatusBarNotification;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.testing.TestableLooper.RunWithLooper;

import androidx.test.annotation.UiThreadTest;
import androidx.test.filters.SmallTest;

import com.android.systemui.ForegroundServiceController;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.statusbar.NotificationLockscreenUserManager;
import com.android.systemui.statusbar.notification.NotificationEntryManager.KeyguardEnvironment;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.notification.collection.NotificationEntryBuilder;
import com.android.systemui.statusbar.notification.row.ExpandableNotificationRow;
import com.android.systemui.statusbar.notification.row.NotificationTestHelper;
import com.android.systemui.statusbar.phone.NotificationGroupManager;
import com.android.systemui.statusbar.phone.ShadeController;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@SmallTest
@RunWith(AndroidTestingRunner.class)
@RunWithLooper
public class NotificationFilterTest extends SysuiTestCase {

    private static final int UID_NORMAL = 123;
    private static final int UID_ALLOW_DURING_SETUP = 456;
    private static final String TEST_HIDDEN_NOTIFICATION_KEY = "testHiddenNotificationKey";

    private final StatusBarNotification mMockStatusBarNotification =
            mock(StatusBarNotification.class);

    @Mock
    ForegroundServiceController mFsc;
    @Mock
    KeyguardEnvironment mEnvironment;
    private final IPackageManager mMockPackageManager = mock(IPackageManager.class);

    private NotificationFilter mNotificationFilter;
    private ExpandableNotificationRow mRow;

    @Before
    public void setUp() throws Exception {
        allowTestableLooperAsMainThread();
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
        mDependency.injectTestDependency(NotificationGroupManager.class,
                new NotificationGroupManager(mock(StatusBarStateController.class)));
        mDependency.injectMockDependency(ShadeController.class);
        mDependency.injectMockDependency(NotificationLockscreenUserManager.class);
        mDependency.injectTestDependency(KeyguardEnvironment.class, mEnvironment);
        when(mEnvironment.isDeviceProvisioned()).thenReturn(true);
        when(mEnvironment.isNotificationForCurrentProfiles(any())).thenReturn(true);
        NotificationTestHelper testHelper = new NotificationTestHelper(
                mContext,
                mDependency,
                TestableLooper.get(this));
        mRow = testHelper.createRow();
        mNotificationFilter = new NotificationFilter(mock(StatusBarStateController.class));
    }

    @Test
    @UiThreadTest
    public void testShowNotificationEvenIfUnprovisioned_FalseIfNoExtra() {
        initStatusBarNotification(false);
        when(mMockStatusBarNotification.getUid()).thenReturn(UID_ALLOW_DURING_SETUP);

        assertFalse(
                NotificationFilter.showNotificationEvenIfUnprovisioned(
                        mMockPackageManager,
                        mMockStatusBarNotification));
    }

    @Test
    @UiThreadTest
    public void testShowNotificationEvenIfUnprovisioned_FalseIfNoPermission() {
        initStatusBarNotification(true);

        assertFalse(
                NotificationFilter.showNotificationEvenIfUnprovisioned(
                        mMockPackageManager,
                        mMockStatusBarNotification));
    }

    @Test
    @UiThreadTest
    public void testShowNotificationEvenIfUnprovisioned_TrueIfHasPermissionAndExtra() {
        initStatusBarNotification(true);
        when(mMockStatusBarNotification.getUid()).thenReturn(UID_ALLOW_DURING_SETUP);

        assertTrue(
                NotificationFilter.showNotificationEvenIfUnprovisioned(
                        mMockPackageManager,
                        mMockStatusBarNotification));
    }

    @Test
    public void testSuppressSystemAlertNotification() {
        when(mFsc.isSystemAlertWarningNeeded(anyInt(), anyString())).thenReturn(false);
        when(mFsc.isSystemAlertNotification(any())).thenReturn(true);
        StatusBarNotification sbn = mRow.getEntry().getSbn();
        Bundle bundle = new Bundle();
        bundle.putStringArray(Notification.EXTRA_FOREGROUND_APPS, new String[]{"something"});
        sbn.getNotification().extras = bundle;

        assertTrue(mNotificationFilter.shouldFilterOut(mRow.getEntry()));
    }

    @Test
    public void testDoNotSuppressSystemAlertNotification() {
        StatusBarNotification sbn = mRow.getEntry().getSbn();
        Bundle bundle = new Bundle();
        bundle.putStringArray(Notification.EXTRA_FOREGROUND_APPS, new String[]{"something"});
        sbn.getNotification().extras = bundle;

        when(mFsc.isSystemAlertWarningNeeded(anyInt(), anyString())).thenReturn(true);
        when(mFsc.isSystemAlertNotification(any())).thenReturn(true);

        assertFalse(mNotificationFilter.shouldFilterOut(mRow.getEntry()));

        when(mFsc.isSystemAlertWarningNeeded(anyInt(), anyString())).thenReturn(true);
        when(mFsc.isSystemAlertNotification(any())).thenReturn(false);

        assertFalse(mNotificationFilter.shouldFilterOut(mRow.getEntry()));

        when(mFsc.isSystemAlertWarningNeeded(anyInt(), anyString())).thenReturn(false);
        when(mFsc.isSystemAlertNotification(any())).thenReturn(false);

        assertFalse(mNotificationFilter.shouldFilterOut(mRow.getEntry()));
    }

    @Test
    public void testDoNotSuppressMalformedSystemAlertNotification() {
        when(mFsc.isSystemAlertWarningNeeded(anyInt(), anyString())).thenReturn(true);

        // missing extra
        assertFalse(mNotificationFilter.shouldFilterOut(mRow.getEntry()));

        StatusBarNotification sbn = mRow.getEntry().getSbn();
        Bundle bundle = new Bundle();
        bundle.putStringArray(Notification.EXTRA_FOREGROUND_APPS, new String[]{});
        sbn.getNotification().extras = bundle;

        // extra missing values
        assertFalse(mNotificationFilter.shouldFilterOut(mRow.getEntry()));
    }

    @Test
    public void testShouldFilterHiddenNotifications() {
        initStatusBarNotification(false);
        // setup
        when(mFsc.isSystemAlertWarningNeeded(anyInt(), anyString())).thenReturn(false);
        when(mFsc.isSystemAlertNotification(any())).thenReturn(false);

        // test should filter out hidden notifications:
        // hidden
        NotificationEntry entry = new NotificationEntryBuilder()
                .setSuspended(true)
                .build();

        assertTrue(mNotificationFilter.shouldFilterOut(entry));

        // not hidden
        entry = new NotificationEntryBuilder()
                .setSuspended(false)
                .build();
        assertFalse(mNotificationFilter.shouldFilterOut(entry));
    }

    private void initStatusBarNotification(boolean allowDuringSetup) {
        Bundle bundle = new Bundle();
        bundle.putBoolean(Notification.EXTRA_ALLOW_DURING_SETUP, allowDuringSetup);
        Notification notification = new Notification.Builder(mContext, "test")
                .addExtras(bundle)
                .build();
        when(mMockStatusBarNotification.getNotification()).thenReturn(notification);
    }
}
