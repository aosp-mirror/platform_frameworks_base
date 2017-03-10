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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.Manifest;
import android.app.Notification;
import android.content.pm.IPackageManager;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.service.notification.StatusBarNotification;
import android.support.test.annotation.UiThreadTest;
import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;

import com.android.systemui.SysuiTestCase;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class NotificationDataTest extends SysuiTestCase {

    private static final int UID_NORMAL = 123;
    private static final int UID_ALLOW_DURING_SETUP = 456;

    private final StatusBarNotification mMockStatusBarNotification =
            mock(StatusBarNotification.class);

    private final IPackageManager mMockPackageManager = mock(IPackageManager.class);

    @Before
    public void setUp() throws Exception {
        when(mMockStatusBarNotification.getUid()).thenReturn(UID_NORMAL);

        when(mMockPackageManager.checkUidPermission(
                eq(Manifest.permission.NOTIFICATION_DURING_SETUP),
                eq(UID_NORMAL)))
                .thenReturn(PackageManager.PERMISSION_DENIED);
        when(mMockPackageManager.checkUidPermission(
                eq(Manifest.permission.NOTIFICATION_DURING_SETUP),
                eq(UID_ALLOW_DURING_SETUP)))
                .thenReturn(PackageManager.PERMISSION_GRANTED);
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

    private void initStatusBarNotification(boolean allowDuringSetup) {
        Bundle bundle = new Bundle();
        bundle.putBoolean(Notification.EXTRA_ALLOW_DURING_SETUP, allowDuringSetup);
        Notification notification = new Notification.Builder(mContext, "test")
                .addExtras(bundle)
                .build();
        when(mMockStatusBarNotification.getNotification()).thenReturn(notification);
    }
}
