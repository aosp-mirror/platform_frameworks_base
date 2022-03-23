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

import static android.app.NotificationManager.IMPORTANCE_HIGH;
import static android.app.NotificationManager.IMPORTANCE_LOW;
import static android.content.Intent.ACTION_USER_SWITCHED;

import static com.android.systemui.statusbar.notification.stack.NotificationPriorityBucketKt.BUCKET_ALERTING;
import static com.android.systemui.statusbar.notification.stack.NotificationPriorityBucketKt.BUCKET_MEDIA_CONTROLS;
import static com.android.systemui.statusbar.notification.stack.NotificationPriorityBucketKt.BUCKET_PEOPLE;
import static com.android.systemui.statusbar.notification.stack.NotificationPriorityBucketKt.BUCKET_SILENT;

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
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;

import androidx.test.filters.SmallTest;

import com.android.systemui.Dependency;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.broadcast.BroadcastDispatcher;
import com.android.systemui.dump.DumpManager;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.statusbar.NotificationLockscreenUserManager.KeyguardNotificationSuppressor;
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
    private NotificationClickNotifier mClickNotifier;
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

    private UserInfo mCurrentUser;
    private UserInfo mSecondaryUser;
    private UserInfo mWorkUser;
    private TestNotificationLockscreenUserManager mLockscreenUserManager;
    private NotificationEntry mCurrentUserNotif;
    private NotificationEntry mSecondaryUserNotif;
    private NotificationEntry mWorkProfileNotif;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mDependency.injectTestDependency(NotificationEntryManager.class, mEntryManager);

        int currentUserId = ActivityManager.getCurrentUser();
        mCurrentUser = new UserInfo(currentUserId, "", 0);
        mSecondaryUser = new UserInfo(currentUserId + 1, "", 0);
        mWorkUser = new UserInfo(currentUserId + 2, "" /* name */, null /* iconPath */, 0,
                UserManager.USER_TYPE_PROFILE_MANAGED);

        when(mUserManager.getProfiles(currentUserId)).thenReturn(Lists.newArrayList(
                mCurrentUser, mSecondaryUser, mWorkUser));
        mDependency.injectTestDependency(Dependency.MAIN_HANDLER,
                Handler.createAsync(Looper.myLooper()));

        Notification notifWithPrivateVisibility = new Notification();
        notifWithPrivateVisibility.visibility = Notification.VISIBILITY_PRIVATE;
        mCurrentUserNotif = new NotificationEntryBuilder()
                .setNotification(notifWithPrivateVisibility)
                .setUser(new UserHandle(mCurrentUser.id))
                .build();
        mSecondaryUserNotif = new NotificationEntryBuilder()
                .setNotification(notifWithPrivateVisibility)
                .setUser(new UserHandle(mSecondaryUser.id))
                .build();
        mWorkProfileNotif = new NotificationEntryBuilder()
                .setNotification(notifWithPrivateVisibility)
                .setUser(new UserHandle(mWorkUser.id))
                .build();

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
        assertTrue(mLockscreenUserManager.userAllowsPrivateNotificationsInPublic(mCurrentUser.id));
    }

    @Test
    public void testLockScreenAllowPrivateNotificationsFalse() {
        Settings.Secure.putIntForUser(mContext.getContentResolver(),
                Settings.Secure.LOCK_SCREEN_ALLOW_PRIVATE_NOTIFICATIONS, 0, mCurrentUser.id);
        mLockscreenUserManager.getLockscreenSettingsObserverForTest().onChange(false);
        assertFalse(mLockscreenUserManager.userAllowsPrivateNotificationsInPublic(mCurrentUser.id));
    }

    @Test
    public void testLockScreenAllowsWorkPrivateNotificationsFalse() {
        Settings.Secure.putIntForUser(mContext.getContentResolver(),
                Settings.Secure.LOCK_SCREEN_ALLOW_PRIVATE_NOTIFICATIONS, 0, mWorkUser.id);
        mLockscreenUserManager.getLockscreenSettingsObserverForTest().onChange(false);
        assertFalse(mLockscreenUserManager.userAllowsPrivateNotificationsInPublic(mWorkUser.id));
    }

    @Test
    public void testLockScreenAllowsWorkPrivateNotificationsTrue() {
        Settings.Secure.putIntForUser(mContext.getContentResolver(),
                Settings.Secure.LOCK_SCREEN_ALLOW_PRIVATE_NOTIFICATIONS, 1, mWorkUser.id);
        mLockscreenUserManager.getLockscreenSettingsObserverForTest().onChange(false);
        assertTrue(mLockscreenUserManager.userAllowsPrivateNotificationsInPublic(mWorkUser.id));
    }

    @Test
    public void testCurrentUserPrivateNotificationsNotRedacted() {
        // GIVEN current user doesn't allow private notifications to show
        Settings.Secure.putIntForUser(mContext.getContentResolver(),
                Settings.Secure.LOCK_SCREEN_ALLOW_PRIVATE_NOTIFICATIONS, 0, mCurrentUser.id);
        mLockscreenUserManager.getLockscreenSettingsObserverForTest().onChange(false);

        // THEN current user's notification is redacted
        assertTrue(mLockscreenUserManager.needsRedaction(mCurrentUserNotif));
    }

    @Test
    public void testCurrentUserPrivateNotificationsRedacted() {
        // GIVEN current user allows private notifications to show
        Settings.Secure.putIntForUser(mContext.getContentResolver(),
                Settings.Secure.LOCK_SCREEN_ALLOW_PRIVATE_NOTIFICATIONS, 1, mCurrentUser.id);
        mLockscreenUserManager.getLockscreenSettingsObserverForTest().onChange(false);

        // THEN current user's notification isn't redacted
        assertFalse(mLockscreenUserManager.needsRedaction(mCurrentUserNotif));
    }

    @Test
    public void testWorkPrivateNotificationsRedacted() {
        // GIVEN work profile doesn't private notifications to show
        Settings.Secure.putIntForUser(mContext.getContentResolver(),
                Settings.Secure.LOCK_SCREEN_ALLOW_PRIVATE_NOTIFICATIONS, 0, mWorkUser.id);
        mLockscreenUserManager.getLockscreenSettingsObserverForTest().onChange(false);

        // THEN work profile notification is redacted
        assertTrue(mLockscreenUserManager.needsRedaction(mWorkProfileNotif));
    }

    @Test
    public void testWorkPrivateNotificationsNotRedacted() {
        // GIVEN work profile allows private notifications to show
        Settings.Secure.putIntForUser(mContext.getContentResolver(),
                Settings.Secure.LOCK_SCREEN_ALLOW_PRIVATE_NOTIFICATIONS, 1, mWorkUser.id);
        mLockscreenUserManager.getLockscreenSettingsObserverForTest().onChange(false);

        // THEN work profile notification isn't redacted
        assertFalse(mLockscreenUserManager.needsRedaction(mWorkProfileNotif));
    }

    @Test
    public void testWorkPrivateNotificationsNotRedacted_otherUsersRedacted() {
        // GIVEN work profile allows private notifications to show but the other users don't
        Settings.Secure.putIntForUser(mContext.getContentResolver(),
                Settings.Secure.LOCK_SCREEN_ALLOW_PRIVATE_NOTIFICATIONS, 1, mWorkUser.id);
        Settings.Secure.putIntForUser(mContext.getContentResolver(),
                Settings.Secure.LOCK_SCREEN_ALLOW_PRIVATE_NOTIFICATIONS, 0, mCurrentUser.id);
        Settings.Secure.putIntForUser(mContext.getContentResolver(),
                Settings.Secure.LOCK_SCREEN_ALLOW_PRIVATE_NOTIFICATIONS, 0,
                mSecondaryUser.id);
        mLockscreenUserManager.getLockscreenSettingsObserverForTest().onChange(false);

        // THEN the work profile notification doesn't need to be redacted
        assertFalse(mLockscreenUserManager.needsRedaction(mWorkProfileNotif));

        // THEN the current user and secondary user notifications do need to be redacted
        assertTrue(mLockscreenUserManager.needsRedaction(mCurrentUserNotif));
        assertTrue(mLockscreenUserManager.needsRedaction(mSecondaryUserNotif));
    }

    @Test
    public void testWorkProfileRedacted_otherUsersNotRedacted() {
        // GIVEN work profile doesn't allow private notifications to show but the other users do
        Settings.Secure.putIntForUser(mContext.getContentResolver(),
                Settings.Secure.LOCK_SCREEN_ALLOW_PRIVATE_NOTIFICATIONS, 0, mWorkUser.id);
        Settings.Secure.putIntForUser(mContext.getContentResolver(),
                Settings.Secure.LOCK_SCREEN_ALLOW_PRIVATE_NOTIFICATIONS, 1, mCurrentUser.id);
        Settings.Secure.putIntForUser(mContext.getContentResolver(),
                Settings.Secure.LOCK_SCREEN_ALLOW_PRIVATE_NOTIFICATIONS, 1,
                mSecondaryUser.id);
        mLockscreenUserManager.getLockscreenSettingsObserverForTest().onChange(false);

        // THEN the work profile notification needs to be redacted
        assertTrue(mLockscreenUserManager.needsRedaction(mWorkProfileNotif));

        // THEN the current user and secondary user notifications don't need to be redacted
        assertFalse(mLockscreenUserManager.needsRedaction(mCurrentUserNotif));
        assertFalse(mLockscreenUserManager.needsRedaction(mSecondaryUserNotif));
    }

    @Test
    public void testSecondaryUserNotRedacted_currentUserRedacted() {
        // GIVEN secondary profile allows private notifications to show but the current user
        // doesn't allow private notifications to show
        Settings.Secure.putIntForUser(mContext.getContentResolver(),
                Settings.Secure.LOCK_SCREEN_ALLOW_PRIVATE_NOTIFICATIONS, 0, mCurrentUser.id);
        Settings.Secure.putIntForUser(mContext.getContentResolver(),
                Settings.Secure.LOCK_SCREEN_ALLOW_PRIVATE_NOTIFICATIONS, 1,
                mSecondaryUser.id);
        mLockscreenUserManager.getLockscreenSettingsObserverForTest().onChange(false);

        // THEN the secondary profile notification still needs to be redacted because the current
        // user's setting takes precedence
        assertTrue(mLockscreenUserManager.needsRedaction(mSecondaryUserNotif));
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
                .putExtra(Intent.EXTRA_USER_HANDLE, mSecondaryUser.id);
        mLockscreenUserManager.getBaseBroadcastReceiverForTest().onReceive(mContext, intent);
        verify(mPresenter, times(1)).onUserSwitched(mSecondaryUser.id);
    }

    @Test
    public void testIsLockscreenPublicMode() {
        assertFalse(mLockscreenUserManager.isLockscreenPublicMode(mCurrentUser.id));
        mLockscreenUserManager.setLockscreenPublicMode(true, mCurrentUser.id);
        assertTrue(mLockscreenUserManager.isLockscreenPublicMode(mCurrentUser.id));
    }

    @Test
    public void testShowSilentNotifications_settingSaysShow() {
        Settings.Secure.putInt(mContext.getContentResolver(),
                Settings.Secure.LOCK_SCREEN_SHOW_NOTIFICATIONS, 1);
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

    @Test
    public void testShowSilentNotificationsPeopleBucket_settingSaysHide() {
        Settings.Secure.putInt(mContext.getContentResolver(),
                Settings.Secure.LOCK_SCREEN_SHOW_NOTIFICATIONS, 1);
        Settings.Secure.putInt(mContext.getContentResolver(),
                Settings.Secure.LOCK_SCREEN_SHOW_SILENT_NOTIFICATIONS, 0);

        final Notification notification = mock(Notification.class);
        when(notification.isForegroundService()).thenReturn(true);
        NotificationEntry entry = new NotificationEntryBuilder()
                .setImportance(IMPORTANCE_LOW)
                .setNotification(notification)
                .build();
        entry.setBucket(BUCKET_PEOPLE);
        assertFalse(mLockscreenUserManager.shouldShowOnKeyguard(entry));
    }

    @Test
    public void testShowSilentNotificationsMediaBucket_settingSaysHide() {
        Settings.Secure.putInt(mContext.getContentResolver(),
                Settings.Secure.LOCK_SCREEN_SHOW_NOTIFICATIONS, 1);
        Settings.Secure.putInt(mContext.getContentResolver(),
                Settings.Secure.LOCK_SCREEN_SHOW_SILENT_NOTIFICATIONS, 0);

        final Notification notification = mock(Notification.class);
        when(notification.isForegroundService()).thenReturn(true);
        NotificationEntry entry = new NotificationEntryBuilder()
                .setImportance(IMPORTANCE_LOW)
                .setNotification(notification)
                .build();
        entry.setBucket(BUCKET_MEDIA_CONTROLS);
        // always show media controls, even if they're silent
        assertTrue(mLockscreenUserManager.shouldShowOnKeyguard(entry));
    }

    @Test
    public void testKeyguardNotificationSuppressors() {
        // GIVEN a notification that should be shown on the lockscreen
        Settings.Secure.putInt(mContext.getContentResolver(),
                Settings.Secure.LOCK_SCREEN_SHOW_NOTIFICATIONS, 1);
        final NotificationEntry entry = new NotificationEntryBuilder()
                .setImportance(IMPORTANCE_HIGH)
                .build();
        entry.setBucket(BUCKET_ALERTING);

        // WHEN a suppressor is added that filters out all entries
        FakeKeyguardSuppressor suppressor = new FakeKeyguardSuppressor();
        mLockscreenUserManager.addKeyguardNotificationSuppressor(suppressor);

        // THEN it's filtered out
        assertFalse(mLockscreenUserManager.shouldShowOnKeyguard(entry));

        // WHEN the suppressor no longer filters out entries
        suppressor.setShouldSuppress(false);

        // THEN it's no longer filtered out
        assertTrue(mLockscreenUserManager.shouldShowOnKeyguard(entry));
    }

    private class TestNotificationLockscreenUserManager
            extends NotificationLockscreenUserManagerImpl {
        public TestNotificationLockscreenUserManager(Context context) {
            super(
                    context,
                    mBroadcastDispatcher,
                    mDevicePolicyManager,
                    mUserManager,
                    mClickNotifier,
                    NotificationLockscreenUserManagerTest.this.mKeyguardManager,
                    mStatusBarStateController,
                    Handler.createAsync(Looper.myLooper()),
                    mDeviceProvisionedController,
                    mKeyguardStateController,
                    mock(DumpManager.class));
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

    private static class FakeKeyguardSuppressor implements KeyguardNotificationSuppressor {
        private boolean mShouldSuppress = true;

        @Override
        public boolean shouldSuppressOnKeyguard(NotificationEntry entry) {
            return mShouldSuppress;
        }

        public void setShouldSuppress(boolean shouldSuppress) {
            mShouldSuppress = shouldSuppress;
        }
    }
}
