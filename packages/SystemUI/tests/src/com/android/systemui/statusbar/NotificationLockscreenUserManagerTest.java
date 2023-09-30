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

import static android.app.admin.DevicePolicyManager.ACTION_DEVICE_POLICY_MANAGER_STATE_CHANGED;
import static android.app.admin.DevicePolicyManager.KEYGUARD_DISABLE_SECURE_NOTIFICATIONS;
import static android.app.admin.DevicePolicyManager.KEYGUARD_DISABLE_UNREDACTED_NOTIFICATIONS;
import static android.os.UserHandle.USER_ALL;
import static android.provider.Settings.Secure.LOCK_SCREEN_SHOW_NOTIFICATIONS;

import static junit.framework.Assert.assertFalse;
import static junit.framework.Assert.assertTrue;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.atMost;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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

import com.android.internal.widget.LockPatternUtils;
import com.android.systemui.Dependency;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.broadcast.BroadcastDispatcher;
import com.android.systemui.dump.DumpManager;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.recents.OverviewProxyService;
import com.android.systemui.settings.UserTracker;
import com.android.systemui.statusbar.NotificationLockscreenUserManager.NotificationStateChangedListener;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.notification.collection.NotificationEntryBuilder;
import com.android.systemui.statusbar.notification.collection.notifcollection.CommonNotifCollection;
import com.android.systemui.statusbar.notification.collection.render.NotificationVisibilityProvider;
import com.android.systemui.statusbar.policy.DeviceProvisionedController;
import com.android.systemui.statusbar.policy.KeyguardStateController;
import com.android.systemui.util.settings.FakeSettings;

import com.google.android.collect.Lists;

import org.junit.Before;
import org.junit.Ignore;
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
    @Mock
    private UserTracker mUserTracker;

    // Dependency mocks:
    @Mock
    private NotificationVisibilityProvider mVisibilityProvider;
    @Mock
    private CommonNotifCollection mNotifCollection;
    @Mock
    private DevicePolicyManager mDevicePolicyManager;
    @Mock
    private NotificationClickNotifier mClickNotifier;
    @Mock
    private OverviewProxyService mOverviewProxyService;
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
    private FakeSettings mSettings;
    private TestNotificationLockscreenUserManager mLockscreenUserManager;
    private NotificationEntry mCurrentUserNotif;
    private NotificationEntry mSecondaryUserNotif;
    private NotificationEntry mWorkProfileNotif;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);

        int currentUserId = ActivityManager.getCurrentUser();
        when(mUserTracker.getUserId()).thenReturn(currentUserId);
        mSettings = new FakeSettings();
        mSettings.setUserId(ActivityManager.getCurrentUser());
        mCurrentUser = new UserInfo(currentUserId, "", 0);
        mSecondaryUser = new UserInfo(currentUserId + 1, "", 0);
        mWorkUser = new UserInfo(currentUserId + 2, "" /* name */, null /* iconPath */, 0,
                UserManager.USER_TYPE_PROFILE_MANAGED);

        when(mKeyguardManager.getPrivateNotificationsAllowed()).thenReturn(true);
        when(mUserManager.getProfiles(currentUserId)).thenReturn(Lists.newArrayList(
                mCurrentUser, mWorkUser));
        when(mUserManager.getProfiles(mSecondaryUser.id)).thenReturn(Lists.newArrayList(
                mSecondaryUser));
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
    public void testLockScreenShowNotificationsFalse() {
        mSettings.putInt(LOCK_SCREEN_SHOW_NOTIFICATIONS, 0);
        mLockscreenUserManager.getLockscreenSettingsObserverForTest().onChange(false);
        assertFalse(mLockscreenUserManager.shouldShowLockscreenNotifications());
    }

    @Test
    public void testLockScreenShowNotificationsTrue() {
        mSettings.putInt(LOCK_SCREEN_SHOW_NOTIFICATIONS, 1);
        mLockscreenUserManager.getLockscreenSettingsObserverForTest().onChange(false);
        assertTrue(mLockscreenUserManager.shouldShowLockscreenNotifications());
    }

    @Test
    public void testLockScreenAllowPrivateNotificationsTrue() {
        mSettings.putInt(Settings.Secure.LOCK_SCREEN_ALLOW_PRIVATE_NOTIFICATIONS, 1);
        mLockscreenUserManager.getLockscreenSettingsObserverForTest().onChange(false);
        assertTrue(mLockscreenUserManager.userAllowsPrivateNotificationsInPublic(mCurrentUser.id));
    }

    @Test
    public void testLockScreenAllowPrivateNotificationsFalse() {
        mSettings.putIntForUser(Settings.Secure.LOCK_SCREEN_ALLOW_PRIVATE_NOTIFICATIONS, 0,
                mCurrentUser.id);
        mLockscreenUserManager.getLockscreenSettingsObserverForTest().onChange(false);
        assertFalse(mLockscreenUserManager.userAllowsPrivateNotificationsInPublic(mCurrentUser.id));
    }

    @Test
    public void testLockScreenAllowsWorkPrivateNotificationsFalse() {
        mSettings.putIntForUser(Settings.Secure.LOCK_SCREEN_ALLOW_PRIVATE_NOTIFICATIONS, 0,
                mWorkUser.id);
        mLockscreenUserManager.getLockscreenSettingsObserverForTest().onChange(false);
        assertFalse(mLockscreenUserManager.userAllowsPrivateNotificationsInPublic(mWorkUser.id));
    }

    @Test
    public void testLockScreenAllowsWorkPrivateNotificationsTrue() {
        mSettings.putIntForUser(Settings.Secure.LOCK_SCREEN_ALLOW_PRIVATE_NOTIFICATIONS, 1,
                mWorkUser.id);
        mLockscreenUserManager.getLockscreenSettingsObserverForTest().onChange(false);
        assertTrue(mLockscreenUserManager.userAllowsPrivateNotificationsInPublic(mWorkUser.id));
    }

    @Test
    public void testCurrentUserPrivateNotificationsNotRedacted() {
        // GIVEN current user doesn't allow private notifications to show
        mSettings.putIntForUser(Settings.Secure.LOCK_SCREEN_ALLOW_PRIVATE_NOTIFICATIONS, 0,
                mCurrentUser.id);
        mLockscreenUserManager.getLockscreenSettingsObserverForTest().onChange(false);

        // THEN current user's notification is redacted
        assertTrue(mLockscreenUserManager.needsRedaction(mCurrentUserNotif));
    }

    @Test
    public void testCurrentUserPrivateNotificationsRedacted() {
        // GIVEN current user allows private notifications to show
        mSettings.putIntForUser(Settings.Secure.LOCK_SCREEN_ALLOW_PRIVATE_NOTIFICATIONS, 1,
                mCurrentUser.id);
        mLockscreenUserManager.getLockscreenSettingsObserverForTest().onChange(false);

        // THEN current user's notification isn't redacted
        assertFalse(mLockscreenUserManager.needsRedaction(mCurrentUserNotif));
    }

    @Test
    public void testWorkPrivateNotificationsRedacted() {
        // GIVEN work profile doesn't private notifications to show
        mSettings.putIntForUser(Settings.Secure.LOCK_SCREEN_ALLOW_PRIVATE_NOTIFICATIONS, 0,
                mWorkUser.id);
        mLockscreenUserManager.getLockscreenSettingsObserverForTest().onChange(false);

        // THEN work profile notification is redacted
        assertTrue(mLockscreenUserManager.needsRedaction(mWorkProfileNotif));
        assertFalse(mLockscreenUserManager.allowsManagedPrivateNotificationsInPublic());
    }

    @Test
    public void testWorkPrivateNotificationsNotRedacted() {
        // GIVEN work profile allows private notifications to show
        mSettings.putIntForUser(Settings.Secure.LOCK_SCREEN_ALLOW_PRIVATE_NOTIFICATIONS, 1,
                mWorkUser.id);
        mLockscreenUserManager.getLockscreenSettingsObserverForTest().onChange(false);

        // THEN work profile notification isn't redacted
        assertFalse(mLockscreenUserManager.needsRedaction(mWorkProfileNotif));
        assertTrue(mLockscreenUserManager.allowsManagedPrivateNotificationsInPublic());
    }

    @Test
    public void testWorkPrivateNotificationsNotRedacted_otherUsersRedacted() {
        // GIVEN work profile allows private notifications to show but the other users don't
        mSettings.putIntForUser(Settings.Secure.LOCK_SCREEN_ALLOW_PRIVATE_NOTIFICATIONS, 1,
                mWorkUser.id);
        mSettings.putIntForUser(Settings.Secure.LOCK_SCREEN_ALLOW_PRIVATE_NOTIFICATIONS, 0,
                mCurrentUser.id);
        mSettings.putIntForUser(Settings.Secure.LOCK_SCREEN_ALLOW_PRIVATE_NOTIFICATIONS, 0,
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
        mSettings.putIntForUser(Settings.Secure.LOCK_SCREEN_ALLOW_PRIVATE_NOTIFICATIONS, 0,
                mWorkUser.id);
        mSettings.putIntForUser(Settings.Secure.LOCK_SCREEN_ALLOW_PRIVATE_NOTIFICATIONS, 1,
                mCurrentUser.id);
        mSettings.putIntForUser(Settings.Secure.LOCK_SCREEN_ALLOW_PRIVATE_NOTIFICATIONS, 1,
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
        mSettings.putIntForUser(Settings.Secure.LOCK_SCREEN_ALLOW_PRIVATE_NOTIFICATIONS, 0,
                mCurrentUser.id);
        mSettings.putIntForUser(Settings.Secure.LOCK_SCREEN_ALLOW_PRIVATE_NOTIFICATIONS, 1,
                mSecondaryUser.id);
        mLockscreenUserManager.getLockscreenSettingsObserverForTest().onChange(false);

        // THEN the secondary profile notification still needs to be redacted because the current
        // user's setting takes precedence
        assertTrue(mLockscreenUserManager.needsRedaction(mSecondaryUserNotif));
    }

    @Test
    public void testUserSwitchedCallsOnUserSwitching() {
        mLockscreenUserManager.getUserTrackerCallbackForTest().onUserChanging(mSecondaryUser.id,
                mContext);
        verify(mPresenter, times(1)).onUserSwitched(mSecondaryUser.id);
    }

    @Test
    public void testIsLockscreenPublicMode() {
        assertFalse(mLockscreenUserManager.isLockscreenPublicMode(mCurrentUser.id));
        mLockscreenUserManager.setLockscreenPublicMode(true, mCurrentUser.id);
        assertTrue(mLockscreenUserManager.isLockscreenPublicMode(mCurrentUser.id));
    }

    @Test
    public void testUpdateIsPublicMode() {
        when(mKeyguardStateController.isMethodSecure()).thenReturn(true);

        NotificationStateChangedListener listener = mock(NotificationStateChangedListener.class);
        mLockscreenUserManager.addNotificationStateChangedListener(listener);
        mLockscreenUserManager.mCurrentProfiles.append(0, mock(UserInfo.class));

        // first call explicitly sets user 0 to not public; notifies
        mLockscreenUserManager.updatePublicMode();
        assertFalse(mLockscreenUserManager.isLockscreenPublicMode(0));
        verify(listener).onNotificationStateChanged();
        clearInvocations(listener);

        // calling again has no changes; does not notify
        mLockscreenUserManager.updatePublicMode();
        assertFalse(mLockscreenUserManager.isLockscreenPublicMode(0));
        verify(listener, never()).onNotificationStateChanged();

        // Calling again with keyguard now showing makes user 0 public; notifies
        when(mKeyguardStateController.isShowing()).thenReturn(true);
        mLockscreenUserManager.updatePublicMode();
        assertTrue(mLockscreenUserManager.isLockscreenPublicMode(0));
        verify(listener).onNotificationStateChanged();
        clearInvocations(listener);

        // calling again has no changes; does not notify
        mLockscreenUserManager.updatePublicMode();
        assertTrue(mLockscreenUserManager.isLockscreenPublicMode(0));
        verify(listener, never()).onNotificationStateChanged();
    }

    @Test
    public void testDevicePolicyDoesNotAllowNotifications() {
        // User allows them
        mSettings.putIntForUser(LOCK_SCREEN_SHOW_NOTIFICATIONS, 1, mCurrentUser.id);
        mLockscreenUserManager.getLockscreenSettingsObserverForTest().onChange(false);

        // DevicePolicy hides notifs on lockscreen
        when(mDevicePolicyManager.getKeyguardDisabledFeatures(null, mCurrentUser.id))
                .thenReturn(KEYGUARD_DISABLE_SECURE_NOTIFICATIONS);

        BroadcastReceiver.PendingResult pr = mock(BroadcastReceiver.PendingResult.class);
        when(pr.getSendingUserId()).thenReturn(mCurrentUser.id);
        mLockscreenUserManager.mAllUsersReceiver.setPendingResult(pr);
        mLockscreenUserManager.mAllUsersReceiver.onReceive(mContext,
                new Intent(ACTION_DEVICE_POLICY_MANAGER_STATE_CHANGED));

        assertFalse(mLockscreenUserManager.userAllowsNotificationsInPublic(mCurrentUser.id));
    }

    @Ignore("b/286230167")
    @Test
    public void testDevicePolicyDoesNotAllowNotifications_userAll() {
        // User allows them
        mSettings.putIntForUser(LOCK_SCREEN_SHOW_NOTIFICATIONS, 1, mCurrentUser.id);
        mLockscreenUserManager.getLockscreenSettingsObserverForTest().onChange(false);

        // DevicePolicy hides notifications
        when(mDevicePolicyManager.getKeyguardDisabledFeatures(null, mCurrentUser.id))
                .thenReturn(KEYGUARD_DISABLE_SECURE_NOTIFICATIONS);

        BroadcastReceiver.PendingResult pr = mock(BroadcastReceiver.PendingResult.class);
        when(pr.getSendingUserId()).thenReturn(mCurrentUser.id);
        mLockscreenUserManager.mAllUsersReceiver.setPendingResult(pr);
        mLockscreenUserManager.mAllUsersReceiver.onReceive(mContext,
                new Intent(ACTION_DEVICE_POLICY_MANAGER_STATE_CHANGED));

        assertFalse(mLockscreenUserManager.userAllowsNotificationsInPublic(USER_ALL));
    }

    @Test
    @Ignore("b/286230167")
    public void testDevicePolicyDoesNotAllowNotifications_secondary() {
        // User allows notifications
        mSettings.putIntForUser(LOCK_SCREEN_SHOW_NOTIFICATIONS, 1, mSecondaryUser.id);
        mLockscreenUserManager.getLockscreenSettingsObserverForTest().onChange(false);

        // DevicePolicy hides notifications
        when(mDevicePolicyManager.getKeyguardDisabledFeatures(null, mSecondaryUser.id))
                .thenReturn(KEYGUARD_DISABLE_SECURE_NOTIFICATIONS);

        BroadcastReceiver.PendingResult pr = mock(BroadcastReceiver.PendingResult.class);
        when(pr.getSendingUserId()).thenReturn(mSecondaryUser.id);
        mLockscreenUserManager.mAllUsersReceiver.setPendingResult(pr);
        mLockscreenUserManager.mAllUsersReceiver.onReceive(mContext,
                new Intent(ACTION_DEVICE_POLICY_MANAGER_STATE_CHANGED));

        assertFalse(mLockscreenUserManager.userAllowsNotificationsInPublic(mSecondaryUser.id));

        // TODO (b/286230167): enable assertion
        verify(mDevicePolicyManager, atMost(1)).getKeyguardDisabledFeatures(any(), anyInt());
    }

    @Test
    public void testDevicePolicy_noPrivateNotifications() {
        // User allows notifications
        mSettings.putIntForUser(LOCK_SCREEN_SHOW_NOTIFICATIONS, 1, mCurrentUser.id);
        mLockscreenUserManager.getLockscreenSettingsObserverForTest().onChange(false);

        // DevicePolicy hides sensitive content
        when(mDevicePolicyManager.getKeyguardDisabledFeatures(null, mCurrentUser.id))
                .thenReturn(KEYGUARD_DISABLE_UNREDACTED_NOTIFICATIONS);

        BroadcastReceiver.PendingResult pr = mock(BroadcastReceiver.PendingResult.class);
        when(pr.getSendingUserId()).thenReturn(mCurrentUser.id);
        mLockscreenUserManager.mAllUsersReceiver.setPendingResult(pr);
        mLockscreenUserManager.mAllUsersReceiver.onReceive(mContext,
                new Intent(ACTION_DEVICE_POLICY_MANAGER_STATE_CHANGED));

        assertTrue(mLockscreenUserManager.needsRedaction(mCurrentUserNotif));

        // TODO (b/286230167): enable assertion. It's currently called 4 times.
        //verify(mDevicePolicyManager, atMost(1)).getKeyguardDisabledFeatures(any(), anyInt());
    }

    @Test
    public void testDevicePolicy_noPrivateNotifications_userAll() {
        // User allows notifications
        mSettings.putIntForUser(LOCK_SCREEN_SHOW_NOTIFICATIONS, 1, mCurrentUser.id);
        mLockscreenUserManager.getLockscreenSettingsObserverForTest().onChange(false);

        // DevicePolicy hides sensitive content
        when(mDevicePolicyManager.getKeyguardDisabledFeatures(null, mCurrentUser.id))
                .thenReturn(KEYGUARD_DISABLE_UNREDACTED_NOTIFICATIONS);

        BroadcastReceiver.PendingResult pr = mock(BroadcastReceiver.PendingResult.class);
        when(pr.getSendingUserId()).thenReturn(mCurrentUser.id);
        mLockscreenUserManager.mAllUsersReceiver.setPendingResult(pr);
        mLockscreenUserManager.mAllUsersReceiver.onReceive(mContext,
                new Intent(ACTION_DEVICE_POLICY_MANAGER_STATE_CHANGED));

        assertTrue(mLockscreenUserManager.needsRedaction(new NotificationEntryBuilder()
                .setNotification(new Notification())
                .setUser(UserHandle.ALL)
                .build()));
    }

    @Test
    public void testDevicePolicyPrivateNotifications_secondary() {
        // User allows notifications
        mSettings.putIntForUser(LOCK_SCREEN_SHOW_NOTIFICATIONS, 1, mSecondaryUser.id);
        mLockscreenUserManager.getLockscreenSettingsObserverForTest().onChange(false);

        // DevicePolicy hides sensitive content
        when(mDevicePolicyManager.getKeyguardDisabledFeatures(null, mSecondaryUser.id))
                .thenReturn(KEYGUARD_DISABLE_UNREDACTED_NOTIFICATIONS);

        BroadcastReceiver.PendingResult pr = mock(BroadcastReceiver.PendingResult.class);
        when(pr.getSendingUserId()).thenReturn(mSecondaryUser.id);
        mLockscreenUserManager.mAllUsersReceiver.setPendingResult(pr);
        mLockscreenUserManager.mAllUsersReceiver.onReceive(mContext,
                new Intent(ACTION_DEVICE_POLICY_MANAGER_STATE_CHANGED));

        assertTrue(mLockscreenUserManager.needsRedaction(mSecondaryUserNotif));

        // TODO (b/286230167): enable assertion. It's currently called 5 times.
        //verify(mDevicePolicyManager, atMost(1)).getKeyguardDisabledFeatures(any(), anyInt());
    }

    @Test
    public void testHideNotifications_primary() {
        mSettings.putIntForUser(LOCK_SCREEN_SHOW_NOTIFICATIONS, 0, mCurrentUser.id);
        mLockscreenUserManager.getLockscreenSettingsObserverForTest().onChange(false);

        assertFalse(mLockscreenUserManager.userAllowsNotificationsInPublic(mCurrentUser.id));
    }

    @Test
    public void testHideNotifications_secondary() {
        mSettings.putIntForUser(LOCK_SCREEN_SHOW_NOTIFICATIONS, 0, mSecondaryUser.id);
        mLockscreenUserManager.getLockscreenSettingsObserverForTest().onChange(false);

        assertFalse(mLockscreenUserManager.userAllowsNotificationsInPublic(mSecondaryUser.id));
    }

    @Ignore("b/286230167")
    @Test
    public void testHideNotifications_workProfile() {
        mSettings.putIntForUser(LOCK_SCREEN_SHOW_NOTIFICATIONS, 0, mWorkUser.id);
        mLockscreenUserManager.getLockscreenSettingsObserverForTest().onChange(false);

        assertFalse(mLockscreenUserManager.userAllowsNotificationsInPublic(mWorkUser.id));
    }

    @Test
    public void testHideNotifications_secondary_userSwitch() {
        mSettings.putIntForUser(LOCK_SCREEN_SHOW_NOTIFICATIONS, 0, mSecondaryUser.id);

        TestNotificationLockscreenUserManager lockscreenUserManager
                = new TestNotificationLockscreenUserManager(mContext);
        lockscreenUserManager.setUpWithPresenter(mPresenter);

        lockscreenUserManager.mUserChangedCallback.onUserChanging(mSecondaryUser.id, mContext);

        assertFalse(lockscreenUserManager.userAllowsNotificationsInPublic(mSecondaryUser.id));
    }

    @Test
    public void testShowNotifications_secondary_userSwitch() {
        mSettings.putIntForUser(LOCK_SCREEN_SHOW_NOTIFICATIONS, 1, mSecondaryUser.id);

        TestNotificationLockscreenUserManager lockscreenUserManager
                = new TestNotificationLockscreenUserManager(mContext);
        lockscreenUserManager.setUpWithPresenter(mPresenter);

        lockscreenUserManager.mUserChangedCallback.onUserChanging(mSecondaryUser.id, mContext);

        assertTrue(lockscreenUserManager.userAllowsNotificationsInPublic(mSecondaryUser.id));
    }

    @Ignore("b/286230167")
    @Test
    public void testShouldShowLockscreenNotifications_keyguardManagerNoPrivateNotifications() {
        // User allows notifications
        mSettings.putIntForUser(LOCK_SCREEN_SHOW_NOTIFICATIONS, 1, mCurrentUser.id);
        mLockscreenUserManager.getLockscreenSettingsObserverForTest().onChange(false);
        // DevicePolicy allows notifications
        when(mDevicePolicyManager.getKeyguardDisabledFeatures(null, mCurrentUser.id))
                .thenReturn(0);
        BroadcastReceiver.PendingResult pr = mock(BroadcastReceiver.PendingResult.class);
        when(pr.getSendingUserId()).thenReturn(mCurrentUser.id);
        mLockscreenUserManager.mAllUsersReceiver.setPendingResult(pr);
        mLockscreenUserManager.mAllUsersReceiver.onReceive(mContext,
                new Intent(ACTION_DEVICE_POLICY_MANAGER_STATE_CHANGED));

        // KeyguardManager does not
        when(mKeyguardManager.getPrivateNotificationsAllowed()).thenReturn(false);

        assertFalse(mLockscreenUserManager.shouldShowLockscreenNotifications());
    }

    @Test
    public void testUserAllowsNotificationsInPublic_keyguardManagerNoPrivateNotifications() {
        // User allows notifications
        mSettings.putIntForUser(LOCK_SCREEN_SHOW_NOTIFICATIONS, 1, mCurrentUser.id);
        mLockscreenUserManager.getLockscreenSettingsObserverForTest().onChange(false);
        // DevicePolicy allows notifications
        when(mDevicePolicyManager.getKeyguardDisabledFeatures(null, mCurrentUser.id))
                .thenReturn(0);
        BroadcastReceiver.PendingResult pr = mock(BroadcastReceiver.PendingResult.class);
        when(pr.getSendingUserId()).thenReturn(mCurrentUser.id);
        mLockscreenUserManager.mAllUsersReceiver.setPendingResult(pr);
        mLockscreenUserManager.mAllUsersReceiver.onReceive(mContext,
                new Intent(ACTION_DEVICE_POLICY_MANAGER_STATE_CHANGED));

        // KeyguardManager does not
        when(mKeyguardManager.getPrivateNotificationsAllowed()).thenReturn(false);

        assertFalse(mLockscreenUserManager.userAllowsNotificationsInPublic(mCurrentUser.id));
    }

    @Test
    public void testUserAllowsNotificationsInPublic_settingsChange() {
        // User allows notifications
        mSettings.putIntForUser(LOCK_SCREEN_SHOW_NOTIFICATIONS, 1, mCurrentUser.id);
        mLockscreenUserManager.getLockscreenSettingsObserverForTest().onChange(false);

        assertTrue(mLockscreenUserManager.userAllowsNotificationsInPublic(mCurrentUser.id));

        // User disables
        mSettings.putIntForUser(LOCK_SCREEN_SHOW_NOTIFICATIONS, 0, mCurrentUser.id);
        mLockscreenUserManager.getLockscreenSettingsObserverForTest().onChange(false);

        assertFalse(mLockscreenUserManager.userAllowsNotificationsInPublic(mCurrentUser.id));
    }

    @Ignore("b/286230167")
    @Test
    public void testUserAllowsNotificationsInPublic_devicePolicyChange() {
        // User allows notifications
        mSettings.putIntForUser(LOCK_SCREEN_SHOW_NOTIFICATIONS, 1, mCurrentUser.id);
        mLockscreenUserManager.getLockscreenSettingsObserverForTest().onChange(false);

        assertTrue(mLockscreenUserManager.userAllowsNotificationsInPublic(mCurrentUser.id));

        // DevicePolicy disables notifications
        when(mDevicePolicyManager.getKeyguardDisabledFeatures(null, mCurrentUser.id))
                .thenReturn(KEYGUARD_DISABLE_SECURE_NOTIFICATIONS);
        BroadcastReceiver.PendingResult pr = mock(BroadcastReceiver.PendingResult.class);
        when(pr.getSendingUserId()).thenReturn(mCurrentUser.id);
        mLockscreenUserManager.mAllUsersReceiver.setPendingResult(pr);
        mLockscreenUserManager.mAllUsersReceiver.onReceive(mContext,
                new Intent(ACTION_DEVICE_POLICY_MANAGER_STATE_CHANGED));

        assertFalse(mLockscreenUserManager.userAllowsNotificationsInPublic(mCurrentUser.id));
    }

    private class TestNotificationLockscreenUserManager
            extends NotificationLockscreenUserManagerImpl {
        public TestNotificationLockscreenUserManager(Context context) {
            super(
                    context,
                    mBroadcastDispatcher,
                    mDevicePolicyManager,
                    mUserManager,
                    mUserTracker,
                    (() -> mVisibilityProvider),
                    (() -> mNotifCollection),
                    mClickNotifier,
                    (() -> mOverviewProxyService),
                    NotificationLockscreenUserManagerTest.this.mKeyguardManager,
                    mStatusBarStateController,
                    Handler.createAsync(Looper.myLooper()),
                    mDeviceProvisionedController,
                    mKeyguardStateController,
                    mSettings,
                    mock(DumpManager.class),
                    mock(LockPatternUtils.class));
        }

        public BroadcastReceiver getBaseBroadcastReceiverForTest() {
            return mBaseBroadcastReceiver;
        }

        public UserTracker.Callback getUserTrackerCallbackForTest() {
            return mUserChangedCallback;
        }

        public ContentObserver getLockscreenSettingsObserverForTest() {
            return mLockscreenSettingsObserver;
        }

        public ContentObserver getSettingsObserverForTest() {
            return mSettingsObserver;
        }
    }
}
