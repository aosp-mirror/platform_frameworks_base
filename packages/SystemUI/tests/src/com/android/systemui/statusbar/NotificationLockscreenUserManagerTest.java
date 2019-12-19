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

import static android.app.NotificationManager.IMPORTANCE_LOW;
import static android.content.Intent.ACTION_USER_SWITCHED;
import static android.provider.Settings.Secure.NOTIFICATION_NEW_INTERRUPTION_MODEL;

import static com.android.systemui.statusbar.notification.stack.NotificationSectionsManager.BUCKET_SILENT;

import static junit.framework.Assert.assertFalse;
import static junit.framework.Assert.assertTrue;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.ActivityManager;
import android.app.KeyguardManager;
import android.app.Notification;
import android.app.admin.DevicePolicyManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.UserInfo;
import android.database.ContentObserver;
import android.os.Handler;
import android.os.Looper;
import android.os.UserManager;
import android.provider.Settings;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;

import androidx.test.filters.SmallTest;

import com.android.internal.statusbar.IStatusBarService;
import com.android.systemui.Dependency;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.broadcast.BroadcastDispatcher;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.statusbar.notification.NotificationEntryManager;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.notification.collection.NotificationEntryBuilder;
import com.android.systemui.statusbar.policy.DeviceProvisionedController;
import com.android.systemui.statusbar.policy.KeyguardStateController;

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
    @Mock
    private NotificationPresenter mPresenter;
    @Mock
    private UserManager mUserManager;

    // Dependency mocks:
    @Mock
    private NotificationEntryManager mEntryManager;
    @Mock
    private DevicePolicyManager mDevicePolicyManager;
    @Mock
    private IStatusBarService mIStatusBarService;
    @Mock
    private KeyguardManager mKeyguardManager;
    @Mock
    private DeviceProvisionedController mDeviceProvisionedController;
    @Mock
    private StatusBarStateController mStatusBarStateController;
    @Mock
    private BroadcastDispatcher mBroadcastDispatcher;
    @Mock
    private KeyguardStateController mKeyguardStateController;

    private int mCurrentUserId;
    private TestNotificationLockscreenUserManager mLockscreenUserManager;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mDependency.injectTestDependency(NotificationEntryManager.class, mEntryManager);

        mCurrentUserId = ActivityManager.getCurrentUser();

        when(mUserManager.getProfiles(mCurrentUserId)).thenReturn(Lists.newArrayList(
                new UserInfo(mCurrentUserId, "", 0), new UserInfo(mCurrentUserId + 1, "", 0)));
        mDependency.injectTestDependency(Dependency.MAIN_HANDLER,
                Handler.createAsync(Looper.myLooper()));

        mLockscreenUserManager = new TestNotificationLockscreenUserManager(mContext);
        mLockscreenUserManager.setUpWithPresenter(mPresenter);
    }

    @Test
    public void testLockScreenShowNotificationsChangeUpdatesNotifications() {
        mLockscreenUserManager.getLockscreenSettingsObserverForTest().onChange(false);
        verify(mEntryManager, times(1)).updateNotifications(anyString());
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
        verify(mEntryManager, times(1)).updateNotifications(anyString());
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

    @Test
    public void testShowSilentNotifications_settingSaysShow() {
        Settings.Secure.putInt(mContext.getContentResolver(),
                Settings.Secure.LOCK_SCREEN_SHOW_NOTIFICATIONS, 1);
        Settings.Secure.putInt(mContext.getContentResolver(),
                NOTIFICATION_NEW_INTERRUPTION_MODEL, 1);
        Settings.Secure.putInt(mContext.getContentResolver(),
                Settings.Secure.LOCK_SCREEN_SHOW_SILENT_NOTIFICATIONS, 1);

        NotificationEntry entry = new NotificationEntryBuilder()
                .setImportance(IMPORTANCE_LOW)
                .build();
        entry.setBucket(BUCKET_SILENT);

        assertTrue(mLockscreenUserManager.shouldShowOnKeyguard(entry));
    }

    @Test
    public void testShowSilentNotifications_settingSaysHide() {
        Settings.Secure.putInt(mContext.getContentResolver(),
                Settings.Secure.LOCK_SCREEN_SHOW_NOTIFICATIONS, 1);
        Settings.Secure.putInt(mContext.getContentResolver(),
                NOTIFICATION_NEW_INTERRUPTION_MODEL, 1);
        Settings.Secure.putInt(mContext.getContentResolver(),
                Settings.Secure.LOCK_SCREEN_SHOW_SILENT_NOTIFICATIONS, 0);

        final Notification notification = mock(Notification.class);
        when(notification.isForegroundService()).thenReturn(true);
        NotificationEntry entry = new NotificationEntryBuilder()
                .setImportance(IMPORTANCE_LOW)
                .setNotification(notification)
                .build();
        entry.setBucket(BUCKET_SILENT);
        assertFalse(mLockscreenUserManager.shouldShowOnKeyguard(entry));
    }

    private class TestNotificationLockscreenUserManager
            extends NotificationLockscreenUserManagerImpl {
        public TestNotificationLockscreenUserManager(Context context) {
            super(context, mBroadcastDispatcher, mDevicePolicyManager, mUserManager,
                    mIStatusBarService, NotificationLockscreenUserManagerTest.this.mKeyguardManager,
                    mStatusBarStateController, Handler.createAsync(Looper.myLooper()),
                    mDeviceProvisionedController, mKeyguardStateController);
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
