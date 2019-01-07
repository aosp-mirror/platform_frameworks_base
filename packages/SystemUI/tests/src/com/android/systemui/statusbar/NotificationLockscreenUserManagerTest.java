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

import static android.content.Intent.ACTION_USER_SWITCHED;

import static junit.framework.Assert.assertFalse;
import static junit.framework.Assert.assertTrue;

import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.ActivityManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.UserInfo;
import android.database.ContentObserver;
import android.os.Handler;
import android.os.Looper;
import android.os.UserManager;
import android.provider.Settings;
import android.support.test.filters.SmallTest;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;

import com.android.systemui.Dependency;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.statusbar.notification.NotificationEntryManager;
import com.android.systemui.statusbar.notification.collection.NotificationData;
import com.android.systemui.statusbar.phone.StatusBarKeyguardViewManager;
import com.android.systemui.statusbar.policy.DeviceProvisionedController;

import com.google.android.collect.Lists;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@SmallTest
@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper
public class NotificationLockscreenUserManagerTest extends SysuiTestCase {
    @Mock private NotificationPresenter mPresenter;
    @Mock private UserManager mUserManager;

    // Dependency mocks:
    @Mock private NotificationEntryManager mEntryManager;
    @Mock private NotificationData mNotificationData;
    @Mock private DeviceProvisionedController mDeviceProvisionedController;
    @Mock private StatusBarKeyguardViewManager mKeyguardViewManager;

    private int mCurrentUserId;
    private TestNotificationLockscreenUserManager mLockscreenUserManager;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mDependency.injectTestDependency(NotificationEntryManager.class, mEntryManager);
        mDependency.injectTestDependency(DeviceProvisionedController.class,
                mDeviceProvisionedController);

        mContext.addMockSystemService(UserManager.class, mUserManager);
        mCurrentUserId = ActivityManager.getCurrentUser();

        when(mUserManager.getProfiles(mCurrentUserId)).thenReturn(Lists.newArrayList(
                new UserInfo(mCurrentUserId, "", 0), new UserInfo(mCurrentUserId + 1, "", 0)));
        when(mEntryManager.getNotificationData()).thenReturn(mNotificationData);
        mDependency.injectTestDependency(Dependency.MAIN_HANDLER,
                Handler.createAsync(Looper.myLooper()));

        mLockscreenUserManager = new TestNotificationLockscreenUserManager(mContext);
        mLockscreenUserManager.setUpWithPresenter(mPresenter);
    }

    @Test
    public void testLockScreenShowNotificationsChangeUpdatesNotifications() {
        mLockscreenUserManager.getLockscreenSettingsObserverForTest().onChange(false);
        verify(mEntryManager, times(1)).updateNotifications();
    }

    @Test
    public void testLockScreenShowNotificationsFalse() {
        Settings.Secure.putInt(mContext.getContentResolver(),
                Settings.Secure.LOCK_SCREEN_SHOW_NOTIFICATIONS, 0);
        mLockscreenUserManager.getLockscreenSettingsObserverForTest().onChange(false);
        assertFalse(mLockscreenUserManager.shouldShowLockscreenNotifications());
    }

    @Test
    public void testLockScreenShowNotificationsTrue() {
        Settings.Secure.putInt(mContext.getContentResolver(),
                Settings.Secure.LOCK_SCREEN_SHOW_NOTIFICATIONS, 1);
        mLockscreenUserManager.getLockscreenSettingsObserverForTest().onChange(false);
        assertTrue(mLockscreenUserManager.shouldShowLockscreenNotifications());
    }

    @Test
    public void testLockScreenAllowPrivateNotificationsTrue() {
        Settings.Secure.putInt(mContext.getContentResolver(),
                Settings.Secure.LOCK_SCREEN_ALLOW_PRIVATE_NOTIFICATIONS, 1);
        mLockscreenUserManager.getLockscreenSettingsObserverForTest().onChange(false);
        assertTrue(mLockscreenUserManager.userAllowsPrivateNotificationsInPublic(mCurrentUserId));
    }

    @Test
    public void testLockScreenAllowPrivateNotificationsFalse() {
        Settings.Secure.putInt(mContext.getContentResolver(),
                Settings.Secure.LOCK_SCREEN_ALLOW_PRIVATE_NOTIFICATIONS, 0);
        mLockscreenUserManager.getLockscreenSettingsObserverForTest().onChange(false);
        assertFalse(mLockscreenUserManager.userAllowsPrivateNotificationsInPublic(mCurrentUserId));
    }

    @Test
    public void testSettingsObserverUpdatesNotifications() {
        when(mDeviceProvisionedController.isDeviceProvisioned()).thenReturn(true);
        mLockscreenUserManager.getSettingsObserverForTest().onChange(false);
        verify(mEntryManager, times(1)).updateNotifications();
    }

    @Test
    public void testActionUserSwitchedCallsOnUserSwitched() {
        Intent intent = new Intent()
                .setAction(ACTION_USER_SWITCHED)
                .putExtra(Intent.EXTRA_USER_HANDLE, mCurrentUserId + 1);
        mLockscreenUserManager.getBaseBroadcastReceiverForTest().onReceive(mContext, intent);
        verify(mPresenter, times(1)).onUserSwitched(mCurrentUserId + 1);
    }

    @Test
    public void testIsLockscreenPublicMode() {
        assertFalse(mLockscreenUserManager.isLockscreenPublicMode(mCurrentUserId));
        mLockscreenUserManager.setLockscreenPublicMode(true, mCurrentUserId);
        assertTrue(mLockscreenUserManager.isLockscreenPublicMode(mCurrentUserId));
    }

    private class TestNotificationLockscreenUserManager extends NotificationLockscreenUserManagerImpl {
        public TestNotificationLockscreenUserManager(Context context) {
            super(context);
        }

        public BroadcastReceiver getBaseBroadcastReceiverForTest() {
            return mBaseBroadcastReceiver;
        }

        public ContentObserver getLockscreenSettingsObserverForTest() {
            return mLockscreenSettingsObserver;
        }

        public ContentObserver getSettingsObserverForTest() {
            return mSettingsObserver;
        }
    }
}
