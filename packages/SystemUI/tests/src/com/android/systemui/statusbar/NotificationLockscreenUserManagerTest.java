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

import static android.app.Notification.VISIBILITY_PRIVATE;
import static android.app.NotificationManager.IMPORTANCE_HIGH;
import static android.app.NotificationManager.VISIBILITY_NO_OVERRIDE;
import static android.app.StatusBarManager.ACTION_KEYGUARD_PRIVATE_NOTIFICATIONS_CHANGED;
import static android.app.StatusBarManager.EXTRA_KM_PRIVATE_NOTIFS_ALLOWED;
import static android.app.admin.DevicePolicyManager.ACTION_DEVICE_POLICY_MANAGER_STATE_CHANGED;
import static android.app.admin.DevicePolicyManager.KEYGUARD_DISABLE_SECURE_NOTIFICATIONS;
import static android.app.admin.DevicePolicyManager.KEYGUARD_DISABLE_UNREDACTED_NOTIFICATIONS;
import static android.app.Flags.FLAG_KEYGUARD_PRIVATE_NOTIFICATIONS;
import static android.os.Flags.FLAG_ALLOW_PRIVATE_PROFILE;
import static android.os.UserHandle.USER_ALL;
import static android.provider.Settings.Secure.LOCK_SCREEN_ALLOW_PRIVATE_NOTIFICATIONS;
import static android.provider.Settings.Secure.LOCK_SCREEN_SHOW_NOTIFICATIONS;
import static com.android.systemui.util.concurrency.MockExecutorHandlerKt.mockExecutorHandler;
import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertFalse;
import static junit.framework.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
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
import android.app.NotificationChannel;
import android.app.admin.DevicePolicyManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.UserInfo;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Bundle;
import android.os.UserHandle;
import android.os.UserManager;
import android.platform.test.annotations.DisableFlags;
import android.platform.test.annotations.EnableFlags;
import android.platform.test.flag.junit.FlagsParameterization;
import android.provider.Settings;
import android.testing.TestableLooper;
import android.util.SparseArray;

import androidx.test.filters.SmallTest;

import com.android.internal.widget.LockPatternUtils;
import com.android.systemui.Dependency;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.broadcast.BroadcastDispatcher;
import com.android.systemui.dump.DumpManager;
import com.android.systemui.flags.FakeFeatureFlagsClassic;
import com.android.systemui.flags.Flags;
import com.android.systemui.log.LogWtfHandlerRule;
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
import com.android.systemui.util.concurrency.FakeExecutor;
import com.android.systemui.util.settings.FakeSettings;
import com.android.systemui.util.time.FakeSystemClock;
import com.google.android.collect.Lists;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.Executor;

import platform.test.runner.parameterized.ParameterizedAndroidJunit4;
import platform.test.runner.parameterized.Parameters;

@SmallTest
@RunWith(ParameterizedAndroidJunit4.class)
@TestableLooper.RunWithLooper
public class NotificationLockscreenUserManagerTest extends SysuiTestCase {

    @Parameters(name = "{0}")
    public static List<FlagsParameterization> getParams() {
        return FlagsParameterization.allCombinationsOf(
                FLAG_ALLOW_PRIVATE_PROFILE,
                FLAG_KEYGUARD_PRIVATE_NOTIFICATIONS);
    }

    public NotificationLockscreenUserManagerTest(FlagsParameterization flags) {
        mSetFlagsRule.setFlagsParameterization(flags);
    }

    private static final int TEST_PROFILE_USERHANDLE = 12;
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
    private UserInfo mCommunalUser;
    private FakeSettings mSettings;
    private TestNotificationLockscreenUserManager mLockscreenUserManager;
    private NotificationEntry mCurrentUserNotif;
    private NotificationEntry mSecondaryUserNotif;
    private NotificationEntry mWorkProfileNotif;
    private final FakeFeatureFlagsClassic mFakeFeatureFlags = new FakeFeatureFlagsClassic();
    private final FakeSystemClock mFakeSystemClock = new FakeSystemClock();
    private final FakeExecutor mBackgroundExecutor = new FakeExecutor(mFakeSystemClock);
    private final Executor mMainExecutor = Runnable::run; // Direct executor

    @Rule public final LogWtfHandlerRule wtfHandlerRule = new LogWtfHandlerRule();

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);

        mFakeFeatureFlags.set(Flags.NOTIF_LS_BACKGROUND_THREAD, true);

        int currentUserId = ActivityManager.getCurrentUser();
        when(mUserTracker.getUserId()).thenReturn(currentUserId);
        mSettings = new FakeSettings();
        mSettings.setUserId(ActivityManager.getCurrentUser());
        mCurrentUser = new UserInfo(currentUserId, "", 0);
        mSecondaryUser = new UserInfo(currentUserId + 1, "", 0);
        mWorkUser = new UserInfo(currentUserId + 2, "" /* name */, null /* iconPath */, 0,
                UserManager.USER_TYPE_PROFILE_MANAGED);
        mCommunalUser = new UserInfo(currentUserId + 3, "" /* name */, null /* iconPath */, 0,
                UserManager.USER_TYPE_PROFILE_COMMUNAL);

        when(mKeyguardManager.getPrivateNotificationsAllowed()).thenReturn(true);
        when(mUserManager.getProfiles(currentUserId)).thenReturn(Lists.newArrayList(
                mCurrentUser, mWorkUser));
        when(mUserManager.getProfilesIncludingCommunal(currentUserId)).thenReturn(
                Lists.newArrayList(mCurrentUser, mWorkUser, mCommunalUser));
        when(mUserManager.getUsers()).thenReturn(Lists.newArrayList(
                mCurrentUser, mWorkUser, mSecondaryUser, mCommunalUser));
        when(mUserManager.getProfiles(mSecondaryUser.id)).thenReturn(Lists.newArrayList(
                mSecondaryUser));
        when(mUserManager.getProfilesIncludingCommunal(mSecondaryUser.id)).thenReturn(
                Lists.newArrayList(mSecondaryUser, mCommunalUser));
        mDependency.injectTestDependency(Dependency.MAIN_HANDLER,
                mockExecutorHandler(mMainExecutor));

        Notification notifWithPrivateVisibility = new Notification();
        notifWithPrivateVisibility.visibility = VISIBILITY_PRIVATE;
        mCurrentUserNotif = new NotificationEntryBuilder()
                .setNotification(notifWithPrivateVisibility)
                .setUser(new UserHandle(mCurrentUser.id))
                .build();
        NotificationChannel channel = new NotificationChannel("1", "1", IMPORTANCE_HIGH);
        channel.setLockscreenVisibility(VISIBILITY_NO_OVERRIDE);
        mCurrentUserNotif.setRanking(new RankingBuilder(mCurrentUserNotif.getRanking())
                .setChannel(channel)
                .setVisibilityOverride(VISIBILITY_NO_OVERRIDE).build());
        when(mNotifCollection.getEntry(mCurrentUserNotif.getKey())).thenReturn(mCurrentUserNotif);
        mSecondaryUserNotif = new NotificationEntryBuilder()
                .setNotification(notifWithPrivateVisibility)
                .setUser(new UserHandle(mSecondaryUser.id))
                .build();
        mSecondaryUserNotif.setRanking(new RankingBuilder(mSecondaryUserNotif.getRanking())
                .setChannel(channel)
                .setVisibilityOverride(VISIBILITY_NO_OVERRIDE).build());
        when(mNotifCollection.getEntry(
                mSecondaryUserNotif.getKey())).thenReturn(mSecondaryUserNotif);
        mWorkProfileNotif = new NotificationEntryBuilder()
                .setNotification(notifWithPrivateVisibility)
                .setUser(new UserHandle(mWorkUser.id))
                .build();
        mWorkProfileNotif.setRanking(new RankingBuilder(mWorkProfileNotif.getRanking())
                .setChannel(channel)
                .setVisibilityOverride(VISIBILITY_NO_OVERRIDE).build());
        when(mNotifCollection.getEntry(mWorkProfileNotif.getKey())).thenReturn(mWorkProfileNotif);

        mLockscreenUserManager = new TestNotificationLockscreenUserManager(mContext);
        mLockscreenUserManager.setUpWithPresenter(mPresenter);

        mBackgroundExecutor.runAllReady();
    }

    @After
    public void tearDown() {
        // Validate that all tests processed all background posted code
        assertEquals(0, mBackgroundExecutor.numPending());
    }

    private void changeSetting(String setting) {
        final Collection<Uri> lockScreenUris = new ArrayList<>();
        lockScreenUris.add(Settings.Secure.getUriFor(setting));
        mLockscreenUserManager.getLockscreenSettingsObserverForTest().onChange(false,
            lockScreenUris, 0);
    }

    @Test
    @EnableFlags(FLAG_KEYGUARD_PRIVATE_NOTIFICATIONS)
    public void testInit() {
        when(mKeyguardManager.getPrivateNotificationsAllowed()).thenReturn(false);
        mLockscreenUserManager = new TestNotificationLockscreenUserManager(mContext);
        mLockscreenUserManager.setUpWithPresenter(mPresenter);

        mBackgroundExecutor.runAllReady();

        assertTrue(mLockscreenUserManager.needsRedaction(mCurrentUserNotif));
        assertTrue(mLockscreenUserManager.needsRedaction(mSecondaryUserNotif));
    }

    @Test
    public void testGetCurrentProfiles() {
        final SparseArray<UserInfo> expectedCurProfiles = new SparseArray<>();
        expectedCurProfiles.put(mCurrentUser.id, mCurrentUser);
        expectedCurProfiles.put(mWorkUser.id, mWorkUser);
        if (android.multiuser.Flags.supportCommunalProfile()) {
            expectedCurProfiles.put(mCommunalUser.id, mCommunalUser);
        }
        assertTrue(mLockscreenUserManager.getCurrentProfiles().contentEquals(expectedCurProfiles));

        mLockscreenUserManager.mUserChangedCallback.onUserChanging(mSecondaryUser.id, mContext);

        final SparseArray<UserInfo> expectedSecProfiles = new SparseArray<>();
        expectedSecProfiles.put(mSecondaryUser.id, mSecondaryUser);
        if (android.multiuser.Flags.supportCommunalProfile()) {
            expectedSecProfiles.put(mCommunalUser.id, mCommunalUser);
        }
        assertTrue(mLockscreenUserManager.getCurrentProfiles().contentEquals(expectedSecProfiles));
    }

    @Test
    public void testLockScreenShowNotificationsFalse() {
        mSettings.putInt(LOCK_SCREEN_SHOW_NOTIFICATIONS, 0);
        changeSetting(LOCK_SCREEN_SHOW_NOTIFICATIONS);
        assertFalse(mLockscreenUserManager.shouldShowLockscreenNotifications());
    }

    @Test
    public void testLockScreenShowNotificationsTrue() {
        mSettings.putInt(LOCK_SCREEN_SHOW_NOTIFICATIONS, 1);
        changeSetting(LOCK_SCREEN_SHOW_NOTIFICATIONS);
        assertTrue(mLockscreenUserManager.shouldShowLockscreenNotifications());
    }

    @Test
    public void testLockScreenAllowPrivateNotificationsTrue() {
        mSettings.putInt(LOCK_SCREEN_ALLOW_PRIVATE_NOTIFICATIONS, 1);
        changeSetting(LOCK_SCREEN_ALLOW_PRIVATE_NOTIFICATIONS);
        assertTrue(mLockscreenUserManager.userAllowsPrivateNotificationsInPublic(mCurrentUser.id));
    }

    @Test
    public void testLockScreenAllowPrivateNotificationsFalse() {
        mSettings.putIntForUser(LOCK_SCREEN_ALLOW_PRIVATE_NOTIFICATIONS, 0,
                mCurrentUser.id);
        changeSetting(LOCK_SCREEN_ALLOW_PRIVATE_NOTIFICATIONS);
        assertFalse(mLockscreenUserManager.userAllowsPrivateNotificationsInPublic(mCurrentUser.id));
    }

    @Test
    public void testLockScreenAllowsWorkPrivateNotificationsFalse() {
        mSettings.putIntForUser(LOCK_SCREEN_ALLOW_PRIVATE_NOTIFICATIONS, 0,
                mWorkUser.id);
        changeSetting(LOCK_SCREEN_ALLOW_PRIVATE_NOTIFICATIONS);
        assertFalse(mLockscreenUserManager.userAllowsPrivateNotificationsInPublic(mWorkUser.id));
    }

    @Test
    public void testLockScreenAllowsWorkPrivateNotificationsTrue() {
        mSettings.putIntForUser(LOCK_SCREEN_ALLOW_PRIVATE_NOTIFICATIONS, 1,
                mWorkUser.id);
        changeSetting(LOCK_SCREEN_ALLOW_PRIVATE_NOTIFICATIONS);
        assertTrue(mLockscreenUserManager.userAllowsPrivateNotificationsInPublic(mWorkUser.id));
    }

    @Test
    public void testCurrentUserPrivateNotificationsRedacted() {
        // GIVEN current user doesn't allow private notifications to show
        mSettings.putIntForUser(LOCK_SCREEN_ALLOW_PRIVATE_NOTIFICATIONS, 0,
                mCurrentUser.id);
        changeSetting(LOCK_SCREEN_ALLOW_PRIVATE_NOTIFICATIONS);

        // THEN current user's notification is redacted
        assertTrue(mLockscreenUserManager.needsRedaction(mCurrentUserNotif));
    }

    @Test
    public void testCurrentUserPrivateNotificationsNotRedacted() {
        // GIVEN current user allows private notifications to show
        mSettings.putIntForUser(LOCK_SCREEN_ALLOW_PRIVATE_NOTIFICATIONS, 1,
                mCurrentUser.id);
        changeSetting(LOCK_SCREEN_ALLOW_PRIVATE_NOTIFICATIONS);

        // THEN current user's notification isn't redacted
        assertFalse(mLockscreenUserManager.needsRedaction(mCurrentUserNotif));
    }

    @Test
    public void testCurrentUserPrivateNotificationsRedactedChannel() {
        // GIVEN current user allows private notifications to show
        mSettings.putIntForUser(LOCK_SCREEN_ALLOW_PRIVATE_NOTIFICATIONS, 1,
                mCurrentUser.id);
        changeSetting(LOCK_SCREEN_ALLOW_PRIVATE_NOTIFICATIONS);

        // but doesn't allow it at the channel level
        NotificationChannel channel = new NotificationChannel("1", "1", IMPORTANCE_HIGH);
        channel.setLockscreenVisibility(VISIBILITY_PRIVATE);
        mCurrentUserNotif.setRanking(new RankingBuilder(mCurrentUserNotif.getRanking())
                .setChannel(channel)
                .setVisibilityOverride(VISIBILITY_NO_OVERRIDE).build());
        // THEN the notification is redacted
        assertTrue(mLockscreenUserManager.needsRedaction(mCurrentUserNotif));
    }

    @Test
    public void testCurrentUserPrivateNotificationsNullChannel() {
        // GIVEN current user allows private notifications to show
        mSettings.putIntForUser(LOCK_SCREEN_ALLOW_PRIVATE_NOTIFICATIONS, 1,
                mCurrentUser.id);
        changeSetting(LOCK_SCREEN_ALLOW_PRIVATE_NOTIFICATIONS);

        mCurrentUserNotif.setRanking(new RankingBuilder(mCurrentUserNotif.getRanking())
                .setChannel(null)
                .setVisibilityOverride(VISIBILITY_NO_OVERRIDE).build());
        // THEN the notification is not redacted
        assertFalse(mLockscreenUserManager.needsRedaction(mCurrentUserNotif));
    }

    @Test
    public void testWorkPrivateNotificationsRedacted() {
        // GIVEN work profile doesn't private notifications to show
        mSettings.putIntForUser(LOCK_SCREEN_ALLOW_PRIVATE_NOTIFICATIONS, 0,
                mWorkUser.id);
        changeSetting(LOCK_SCREEN_ALLOW_PRIVATE_NOTIFICATIONS);

        // THEN work profile notification is redacted
        assertTrue(mLockscreenUserManager.needsRedaction(mWorkProfileNotif));
        assertFalse(mLockscreenUserManager.allowsManagedPrivateNotificationsInPublic());
    }

    @Test
    public void testWorkPrivateNotificationsNotRedacted() {
        // GIVEN work profile allows private notifications to show
        mSettings.putIntForUser(LOCK_SCREEN_ALLOW_PRIVATE_NOTIFICATIONS, 1,
                mWorkUser.id);
        changeSetting(LOCK_SCREEN_ALLOW_PRIVATE_NOTIFICATIONS);

        // THEN work profile notification isn't redacted
        assertFalse(mLockscreenUserManager.needsRedaction(mWorkProfileNotif));
        assertTrue(mLockscreenUserManager.allowsManagedPrivateNotificationsInPublic());
    }

    @Test
    public void testWorkPrivateNotificationsNotRedacted_otherUsersRedacted() {
        // GIVEN work profile allows private notifications to show but the other users don't
        mSettings.putIntForUser(LOCK_SCREEN_ALLOW_PRIVATE_NOTIFICATIONS, 1,
                mWorkUser.id);
        mSettings.putIntForUser(LOCK_SCREEN_ALLOW_PRIVATE_NOTIFICATIONS, 0,
                mCurrentUser.id);
        mSettings.putIntForUser(LOCK_SCREEN_ALLOW_PRIVATE_NOTIFICATIONS, 0,
                mSecondaryUser.id);
        changeSetting(LOCK_SCREEN_ALLOW_PRIVATE_NOTIFICATIONS);
        changeSetting(LOCK_SCREEN_ALLOW_PRIVATE_NOTIFICATIONS);
        changeSetting(LOCK_SCREEN_ALLOW_PRIVATE_NOTIFICATIONS);

        // THEN the work profile notification doesn't need to be redacted
        assertFalse(mLockscreenUserManager.needsRedaction(mWorkProfileNotif));

        // THEN the current user and secondary user notifications do need to be redacted
        assertTrue(mLockscreenUserManager.needsRedaction(mCurrentUserNotif));
        assertTrue(mLockscreenUserManager.needsRedaction(mSecondaryUserNotif));
    }

    @Test
    public void testWorkProfileRedacted_otherUsersNotRedacted() {
        // GIVEN work profile doesn't allow private notifications to show but the other users do
        mSettings.putIntForUser(LOCK_SCREEN_ALLOW_PRIVATE_NOTIFICATIONS, 0,
                mWorkUser.id);
        mSettings.putIntForUser(LOCK_SCREEN_ALLOW_PRIVATE_NOTIFICATIONS, 1,
                mCurrentUser.id);
        mSettings.putIntForUser(LOCK_SCREEN_ALLOW_PRIVATE_NOTIFICATIONS, 1,
                mSecondaryUser.id);
        changeSetting(LOCK_SCREEN_ALLOW_PRIVATE_NOTIFICATIONS);
        changeSetting(LOCK_SCREEN_ALLOW_PRIVATE_NOTIFICATIONS);
        changeSetting(LOCK_SCREEN_ALLOW_PRIVATE_NOTIFICATIONS);

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
        mSettings.putIntForUser(LOCK_SCREEN_ALLOW_PRIVATE_NOTIFICATIONS, 0,
                mCurrentUser.id);
        mSettings.putIntForUser(LOCK_SCREEN_ALLOW_PRIVATE_NOTIFICATIONS, 1,
                mSecondaryUser.id);
        changeSetting(LOCK_SCREEN_ALLOW_PRIVATE_NOTIFICATIONS);
        changeSetting(LOCK_SCREEN_ALLOW_PRIVATE_NOTIFICATIONS);

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
        when(mKeyguardStateController.isShowing()).thenReturn(false);

        NotificationStateChangedListener listener = mock(NotificationStateChangedListener.class);
        mLockscreenUserManager.addNotificationStateChangedListener(listener);
        mLockscreenUserManager.mCurrentProfiles.append(0, mock(UserInfo.class));

        // first call explicitly sets user 0 to not public; notifies
        mLockscreenUserManager.updatePublicMode();
        mBackgroundExecutor.runAllReady();
        assertFalse(mLockscreenUserManager.isLockscreenPublicMode(0));
        verify(listener).onNotificationStateChanged();
        clearInvocations(listener);

        // calling again has no changes; does not notify
        mLockscreenUserManager.updatePublicMode();
        mBackgroundExecutor.runAllReady();
        assertFalse(mLockscreenUserManager.isLockscreenPublicMode(0));
        verify(listener, never()).onNotificationStateChanged();

        // Calling again with keyguard now showing makes user 0 public; notifies
        when(mKeyguardStateController.isShowing()).thenReturn(true);
        mLockscreenUserManager.updatePublicMode();
        mBackgroundExecutor.runAllReady();
        assertTrue(mLockscreenUserManager.isLockscreenPublicMode(0));
        verify(listener).onNotificationStateChanged();
        clearInvocations(listener);

        // calling again has no changes; does not notify
        mLockscreenUserManager.updatePublicMode();
        mBackgroundExecutor.runAllReady();
        assertTrue(mLockscreenUserManager.isLockscreenPublicMode(0));
        verify(listener, never()).onNotificationStateChanged();
    }

    @Test
    public void testDevicePolicyDoesNotAllowNotifications() {
        // User allows them
        mSettings.putIntForUser(LOCK_SCREEN_SHOW_NOTIFICATIONS, 1, mCurrentUser.id);
        changeSetting(LOCK_SCREEN_SHOW_NOTIFICATIONS);

        // DevicePolicy hides notifs on lockscreen
        when(mDevicePolicyManager.getKeyguardDisabledFeatures(null, mCurrentUser.id))
                .thenReturn(KEYGUARD_DISABLE_SECURE_NOTIFICATIONS);

        BroadcastReceiver.PendingResult pr = new BroadcastReceiver.PendingResult(
                0, null, null, 0, true, false, null, mCurrentUser.id, 0);
        mLockscreenUserManager.mAllUsersReceiver.setPendingResult(pr);
        mLockscreenUserManager.mAllUsersReceiver.onReceive(mContext,
                new Intent(ACTION_DEVICE_POLICY_MANAGER_STATE_CHANGED));

        assertFalse(mLockscreenUserManager.userAllowsNotificationsInPublic(mCurrentUser.id));
    }

    @Test
    public void testDevicePolicyDoesNotAllowNotifications_deviceOwnerSetsForUserAll() {
        // User allows them
        mSettings.putIntForUser(LOCK_SCREEN_SHOW_NOTIFICATIONS, 1, mCurrentUser.id);
        mSettings.putIntForUser(LOCK_SCREEN_SHOW_NOTIFICATIONS, 1, mSecondaryUser.id);
        changeSetting(LOCK_SCREEN_SHOW_NOTIFICATIONS);

        // DevicePolicy hides notifs on lockscreen
        when(mDevicePolicyManager.getKeyguardDisabledFeatures(null, mCurrentUser.id))
                .thenReturn(KEYGUARD_DISABLE_SECURE_NOTIFICATIONS);
        when(mDevicePolicyManager.getKeyguardDisabledFeatures(null, mSecondaryUser.id))
                .thenReturn(KEYGUARD_DISABLE_SECURE_NOTIFICATIONS);

        BroadcastReceiver.PendingResult pr = new BroadcastReceiver.PendingResult(
                0, null, null, 0, true, false, null, USER_ALL, 0);
        mLockscreenUserManager.mAllUsersReceiver.setPendingResult(pr);
        mLockscreenUserManager.mAllUsersReceiver.onReceive(mContext,
                new Intent(ACTION_DEVICE_POLICY_MANAGER_STATE_CHANGED));

        assertFalse(mLockscreenUserManager.userAllowsNotificationsInPublic(mCurrentUser.id));
        assertFalse(mLockscreenUserManager.userAllowsNotificationsInPublic(mSecondaryUser.id));
    }

    @Test
    public void testDevicePolicyDoesNotAllowNotifications_userAll() {
        // User allows them
        mSettings.putIntForUser(LOCK_SCREEN_SHOW_NOTIFICATIONS, 1, mCurrentUser.id);
        changeSetting(LOCK_SCREEN_SHOW_NOTIFICATIONS);

        // DevicePolicy hides notifications
        when(mDevicePolicyManager.getKeyguardDisabledFeatures(null, mCurrentUser.id))
                .thenReturn(KEYGUARD_DISABLE_SECURE_NOTIFICATIONS);

        BroadcastReceiver.PendingResult pr = new BroadcastReceiver.PendingResult(
                0, null, null, 0, true, false, null, mCurrentUser.id, 0);
        mLockscreenUserManager.mAllUsersReceiver.setPendingResult(pr);
        mLockscreenUserManager.mAllUsersReceiver.onReceive(mContext,
                new Intent(ACTION_DEVICE_POLICY_MANAGER_STATE_CHANGED));

        assertFalse(mLockscreenUserManager.userAllowsNotificationsInPublic(USER_ALL));
    }

    @Test
    public void testDevicePolicyDoesNotAllowNotifications_secondary() {
        Mockito.clearInvocations(mDevicePolicyManager);
        // User allows notifications
        mSettings.putIntForUser(LOCK_SCREEN_SHOW_NOTIFICATIONS, 1, mSecondaryUser.id);
        changeSetting(LOCK_SCREEN_SHOW_NOTIFICATIONS);

        // DevicePolicy hides notifications
        when(mDevicePolicyManager.getKeyguardDisabledFeatures(null, mSecondaryUser.id))
                .thenReturn(KEYGUARD_DISABLE_SECURE_NOTIFICATIONS);

        BroadcastReceiver.PendingResult pr = new BroadcastReceiver.PendingResult(
                0, null, null, 0, true, false, null, mSecondaryUser.id, 0);
        mLockscreenUserManager.mAllUsersReceiver.setPendingResult(pr);
        mLockscreenUserManager.mAllUsersReceiver.onReceive(mContext,
                new Intent(ACTION_DEVICE_POLICY_MANAGER_STATE_CHANGED));

        assertFalse(mLockscreenUserManager.userAllowsNotificationsInPublic(mSecondaryUser.id));

        verify(mDevicePolicyManager, atMost(1)).getKeyguardDisabledFeatures(any(), anyInt());
    }

    @Test
    @EnableFlags(FLAG_KEYGUARD_PRIVATE_NOTIFICATIONS)
    public void testEarlyUserSwitch() {
        mLockscreenUserManager =
                new TestNotificationLockscreenUserManager(mContext);
        mBackgroundExecutor.runAllReady();
        mLockscreenUserManager.mUserChangedCallback.onUserChanging(
                mCurrentUser.id, mContext);
        // no crash!
    }

    @Test
    @EnableFlags(FLAG_KEYGUARD_PRIVATE_NOTIFICATIONS)
    public void testKeyguardManager_noPrivateNotifications() {
        Mockito.clearInvocations(mDevicePolicyManager);
        // User allows notifications
        mSettings.putIntForUser(LOCK_SCREEN_SHOW_NOTIFICATIONS, 1, mCurrentUser.id);
        changeSetting(LOCK_SCREEN_SHOW_NOTIFICATIONS);

        BroadcastReceiver.PendingResult pr = new BroadcastReceiver.PendingResult(
                0, null, null, 0, true, false, null, mCurrentUser.id, 0);
        mLockscreenUserManager.mAllUsersReceiver.setPendingResult(pr);
        mLockscreenUserManager.mAllUsersReceiver.onReceive(mContext,
                new Intent(ACTION_KEYGUARD_PRIVATE_NOTIFICATIONS_CHANGED)
                        .putExtra(EXTRA_KM_PRIVATE_NOTIFS_ALLOWED, true));

        assertTrue(mLockscreenUserManager.needsRedaction(mCurrentUserNotif));
        // it's a global field, confirm secondary too
        assertTrue(mLockscreenUserManager.needsRedaction(mSecondaryUserNotif));
        assertFalse(mLockscreenUserManager.userAllowsPrivateNotificationsInPublic(mCurrentUser.id));
        assertFalse(mLockscreenUserManager.userAllowsPrivateNotificationsInPublic(
                mSecondaryUser.id));
    }

    @Test
    public void testDevicePolicy_noPrivateNotifications() {
        Mockito.clearInvocations(mDevicePolicyManager);
        // User allows notifications
        mSettings.putIntForUser(LOCK_SCREEN_SHOW_NOTIFICATIONS, 1, mCurrentUser.id);
        changeSetting(LOCK_SCREEN_SHOW_NOTIFICATIONS);

        // DevicePolicy hides sensitive content
        when(mDevicePolicyManager.getKeyguardDisabledFeatures(null, mCurrentUser.id))
                .thenReturn(KEYGUARD_DISABLE_UNREDACTED_NOTIFICATIONS);

        BroadcastReceiver.PendingResult pr = new BroadcastReceiver.PendingResult(
                0, null, null, 0, true, false, null, mCurrentUser.id, 0);
        mLockscreenUserManager.mAllUsersReceiver.setPendingResult(pr);
        mLockscreenUserManager.mAllUsersReceiver.onReceive(mContext,
                new Intent(ACTION_DEVICE_POLICY_MANAGER_STATE_CHANGED));

        assertTrue(mLockscreenUserManager.needsRedaction(mCurrentUserNotif));

        verify(mDevicePolicyManager, atMost(1)).getKeyguardDisabledFeatures(any(), anyInt());
    }

    @Test
    public void testDevicePolicy_noPrivateNotifications_userAll() {
        NotificationChannel channel = new NotificationChannel("1", "1", IMPORTANCE_HIGH);
        channel.setLockscreenVisibility(VISIBILITY_NO_OVERRIDE);
        NotificationEntry notifEntry = new NotificationEntryBuilder()
                .setNotification(new Notification())
                .setUser(UserHandle.ALL)
                .build();
        notifEntry.setRanking(new RankingBuilder(notifEntry.getRanking())
                .setChannel(channel)
                .setVisibilityOverride(VISIBILITY_NO_OVERRIDE).build());
        when(mNotifCollection.getEntry(notifEntry.getKey())).thenReturn(notifEntry);
        // User allows notifications
        mSettings.putIntForUser(LOCK_SCREEN_SHOW_NOTIFICATIONS, 1, mCurrentUser.id);
        changeSetting(LOCK_SCREEN_SHOW_NOTIFICATIONS);

        // DevicePolicy hides sensitive content
        when(mDevicePolicyManager.getKeyguardDisabledFeatures(null, mCurrentUser.id))
                .thenReturn(KEYGUARD_DISABLE_UNREDACTED_NOTIFICATIONS);

        BroadcastReceiver.PendingResult pr = new BroadcastReceiver.PendingResult(
                0, null, null, 0, true, false, null, mCurrentUser.id, 0);
        mLockscreenUserManager.mAllUsersReceiver.setPendingResult(pr);
        mLockscreenUserManager.mAllUsersReceiver.onReceive(mContext,
                new Intent(ACTION_DEVICE_POLICY_MANAGER_STATE_CHANGED));

        assertTrue(mLockscreenUserManager.needsRedaction(notifEntry));
    }

    @Test
    public void testDevicePolicyPrivateNotifications_secondary() {
        Mockito.clearInvocations(mDevicePolicyManager);
        // User allows notifications
        mSettings.putIntForUser(LOCK_SCREEN_SHOW_NOTIFICATIONS, 1, mSecondaryUser.id);
        changeSetting(LOCK_SCREEN_SHOW_NOTIFICATIONS);

        // DevicePolicy hides sensitive content
        when(mDevicePolicyManager.getKeyguardDisabledFeatures(null, mSecondaryUser.id))
                .thenReturn(KEYGUARD_DISABLE_UNREDACTED_NOTIFICATIONS);

        BroadcastReceiver.PendingResult pr = new BroadcastReceiver.PendingResult(
                0, null, null, 0, true, false, null, mSecondaryUser.id, 0);
        mLockscreenUserManager.mAllUsersReceiver.setPendingResult(pr);
        mLockscreenUserManager.mAllUsersReceiver.onReceive(mContext,
                new Intent(ACTION_DEVICE_POLICY_MANAGER_STATE_CHANGED));

        mLockscreenUserManager.mUserChangedCallback.onUserChanging(mSecondaryUser.id, mContext);
        assertTrue(mLockscreenUserManager.needsRedaction(mSecondaryUserNotif));

        verify(mDevicePolicyManager, atMost(1)).getKeyguardDisabledFeatures(any(), anyInt());
    }

    @Test
    public void testHideNotifications_primary() {
        mSettings.putIntForUser(LOCK_SCREEN_SHOW_NOTIFICATIONS, 0, mCurrentUser.id);
        changeSetting(LOCK_SCREEN_SHOW_NOTIFICATIONS);

        assertFalse(mLockscreenUserManager.userAllowsNotificationsInPublic(mCurrentUser.id));
    }

    @Test
    public void testHideNotifications_secondary() {
        mSettings.putIntForUser(LOCK_SCREEN_SHOW_NOTIFICATIONS, 0, mSecondaryUser.id);
        changeSetting(LOCK_SCREEN_SHOW_NOTIFICATIONS);

        assertFalse(mLockscreenUserManager.userAllowsNotificationsInPublic(mSecondaryUser.id));
    }

    @Test
    public void testHideNotifications_workProfile() {
        mSettings.putIntForUser(LOCK_SCREEN_SHOW_NOTIFICATIONS, 0, mCurrentUser.id);
        mSettings.putIntForUser(LOCK_SCREEN_SHOW_NOTIFICATIONS, 1, mWorkUser.id);
        changeSetting(LOCK_SCREEN_SHOW_NOTIFICATIONS);
        changeSetting(LOCK_SCREEN_SHOW_NOTIFICATIONS);

        assertFalse(mLockscreenUserManager.userAllowsNotificationsInPublic(mWorkUser.id));
    }

    @Test
    public void testHideNotifications_secondary_userSwitch() {
        mSettings.putIntForUser(LOCK_SCREEN_SHOW_NOTIFICATIONS, 0, mSecondaryUser.id);
        changeSetting(LOCK_SCREEN_SHOW_NOTIFICATIONS);

        mLockscreenUserManager.mUserChangedCallback.onUserChanging(mSecondaryUser.id, mContext);

        assertFalse(mLockscreenUserManager.userAllowsNotificationsInPublic(mSecondaryUser.id));
    }

    @Test
    public void testShowNotifications_secondary_userSwitch() {
        mSettings.putIntForUser(LOCK_SCREEN_SHOW_NOTIFICATIONS, 1, mSecondaryUser.id);
        changeSetting(LOCK_SCREEN_SHOW_NOTIFICATIONS);

        mLockscreenUserManager.mUserChangedCallback.onUserChanging(mSecondaryUser.id, mContext);

        assertTrue(mLockscreenUserManager.userAllowsNotificationsInPublic(mSecondaryUser.id));
    }

    @Test
    @EnableFlags(FLAG_KEYGUARD_PRIVATE_NOTIFICATIONS)
    public void testShouldShowLockscreenNotifications_keyguardManagerNoPrivateNotifications_show() {
        // KeyguardManager does not allow notifications
        when(mKeyguardManager.getPrivateNotificationsAllowed()).thenReturn(false);
        // User allows notifications
        mSettings.putIntForUser(LOCK_SCREEN_SHOW_NOTIFICATIONS, 1, mCurrentUser.id);
        changeSetting(LOCK_SCREEN_SHOW_NOTIFICATIONS);
        // DevicePolicy allows notifications
        when(mDevicePolicyManager.getKeyguardDisabledFeatures(null, mCurrentUser.id))
                .thenReturn(0);
        BroadcastReceiver.PendingResult pr = new BroadcastReceiver.PendingResult(
                0, null, null, 0, true, false, null, mCurrentUser.id, 0);
        mLockscreenUserManager.mKeyguardReceiver.setPendingResult(pr);
        mLockscreenUserManager.mKeyguardReceiver.onReceive(mContext,
                new Intent(ACTION_KEYGUARD_PRIVATE_NOTIFICATIONS_CHANGED)
                        .putExtra(EXTRA_KM_PRIVATE_NOTIFS_ALLOWED, false));

        assertTrue(mLockscreenUserManager.shouldShowLockscreenNotifications());
        assertTrue(mLockscreenUserManager.userAllowsNotificationsInPublic(mCurrentUser.id));
    }

    @Test
    @DisableFlags(FLAG_KEYGUARD_PRIVATE_NOTIFICATIONS)
    public void testShouldShowLockscreenNotifications_keyguardManagerNoPrivateNotifications() {
        // KeyguardManager does not allow notifications
        when(mKeyguardManager.getPrivateNotificationsAllowed()).thenReturn(false);
        // User allows notifications
        mSettings.putIntForUser(LOCK_SCREEN_SHOW_NOTIFICATIONS, 1, mCurrentUser.id);
        changeSetting(LOCK_SCREEN_SHOW_NOTIFICATIONS);
        // DevicePolicy allows notifications
        when(mDevicePolicyManager.getKeyguardDisabledFeatures(null, mCurrentUser.id))
                .thenReturn(0);
        BroadcastReceiver.PendingResult pr = new BroadcastReceiver.PendingResult(
                0, null, null, 0, true, false, null, mCurrentUser.id, 0);
        mLockscreenUserManager.mAllUsersReceiver.setPendingResult(pr);
        mLockscreenUserManager.mAllUsersReceiver.onReceive(mContext,
                new Intent(ACTION_DEVICE_POLICY_MANAGER_STATE_CHANGED));

        assertFalse(mLockscreenUserManager.shouldShowLockscreenNotifications());
    }

    @Test
    @DisableFlags(FLAG_KEYGUARD_PRIVATE_NOTIFICATIONS)
    public void testUserAllowsNotificationsInPublic_keyguardManagerNoPrivateNotifications() {
        // DevicePolicy allows notifications
        when(mDevicePolicyManager.getKeyguardDisabledFeatures(null, mCurrentUser.id))
                .thenReturn(0);
        BroadcastReceiver.PendingResult pr = new BroadcastReceiver.PendingResult(
                0, null, null, 0, true, false, null, mCurrentUser.id, 0);
        mLockscreenUserManager.mAllUsersReceiver.setPendingResult(pr);
        mLockscreenUserManager.mAllUsersReceiver.onReceive(mContext,
                new Intent(ACTION_DEVICE_POLICY_MANAGER_STATE_CHANGED));

        // KeyguardManager does not allow notifications
        when(mKeyguardManager.getPrivateNotificationsAllowed()).thenReturn(false);

        // User allows notifications
        mSettings.putIntForUser(LOCK_SCREEN_SHOW_NOTIFICATIONS, 1, mCurrentUser.id);
        // We shouldn't need to call this method, but getPrivateNotificationsAllowed has no
        // callback, so it's only updated when the setting is
        changeSetting(LOCK_SCREEN_SHOW_NOTIFICATIONS);

        assertFalse(mLockscreenUserManager.userAllowsNotificationsInPublic(mCurrentUser.id));
    }

    @Test
    public void testUserAllowsNotificationsInPublic_settingsChange() {
        // User allows notifications
        mSettings.putIntForUser(LOCK_SCREEN_SHOW_NOTIFICATIONS, 1, mCurrentUser.id);
        changeSetting(LOCK_SCREEN_SHOW_NOTIFICATIONS);

        assertTrue(mLockscreenUserManager.userAllowsNotificationsInPublic(mCurrentUser.id));

        // User disables
        mSettings.putIntForUser(LOCK_SCREEN_SHOW_NOTIFICATIONS, 0, mCurrentUser.id);
        changeSetting(LOCK_SCREEN_SHOW_NOTIFICATIONS);

        assertFalse(mLockscreenUserManager.userAllowsNotificationsInPublic(mCurrentUser.id));
    }

    @Test
    public void testUserAllowsNotificationsInPublic_devicePolicyChange() {
        // User allows notifications
        mSettings.putIntForUser(LOCK_SCREEN_SHOW_NOTIFICATIONS, 1, mCurrentUser.id);
        changeSetting(LOCK_SCREEN_SHOW_NOTIFICATIONS);

        assertTrue(mLockscreenUserManager.userAllowsNotificationsInPublic(mCurrentUser.id));

        // DevicePolicy disables notifications
        when(mDevicePolicyManager.getKeyguardDisabledFeatures(null, mCurrentUser.id))
                .thenReturn(KEYGUARD_DISABLE_SECURE_NOTIFICATIONS);
        BroadcastReceiver.PendingResult pr = new BroadcastReceiver.PendingResult(
                0, null, null, 0, true, false, null, mCurrentUser.id, 0);
        mLockscreenUserManager.mAllUsersReceiver.setPendingResult(pr);
        mLockscreenUserManager.mAllUsersReceiver.onReceive(mContext,
                new Intent(ACTION_DEVICE_POLICY_MANAGER_STATE_CHANGED));

        assertFalse(mLockscreenUserManager.userAllowsNotificationsInPublic(mCurrentUser.id));
    }

    @Test
    public void testNewUserAdded() {
        int newUserId = 14;
        mSettings.putIntForUser(LOCK_SCREEN_SHOW_NOTIFICATIONS, 1, newUserId);
        mSettings.putIntForUser(LOCK_SCREEN_ALLOW_PRIVATE_NOTIFICATIONS, 1, newUserId);
        when(mDevicePolicyManager.getKeyguardDisabledFeatures(null, newUserId))
                .thenReturn(KEYGUARD_DISABLE_UNREDACTED_NOTIFICATIONS);

        BroadcastReceiver broadcastReceiver =
                mLockscreenUserManager.getBaseBroadcastReceiverForTest();
        final Bundle extras = new Bundle();
        final Intent intent = new Intent(Intent.ACTION_USER_ADDED);
        intent.putExtras(extras);
        intent.putExtra(Intent.EXTRA_USER_HANDLE, newUserId);
        broadcastReceiver.onReceive(mContext, intent);

        // One background task to run which will setup the new user
        assertEquals(1, mBackgroundExecutor.runAllReady());

        verify(mDevicePolicyManager, atMost(1)).getKeyguardDisabledFeatures(any(), eq(newUserId));

        assertTrue(mLockscreenUserManager.userAllowsNotificationsInPublic(newUserId));
        assertFalse(mLockscreenUserManager.userAllowsPrivateNotificationsInPublic(newUserId));
    }

    @Test
    @EnableFlags(FLAG_ALLOW_PRIVATE_PROFILE)
    public void testProfileAvailabilityIntent() {
        mLockscreenUserManager.mCurrentProfiles.clear();
        assertEquals(0, mLockscreenUserManager.mCurrentProfiles.size());
        mLockscreenUserManager.mCurrentProfiles.append(0, mock(UserInfo.class));
        simulateProfileAvailabilityActions(Intent.ACTION_PROFILE_AVAILABLE);
        int numProfiles = android.multiuser.Flags.supportCommunalProfile() ? 3 : 2;
        assertEquals(numProfiles, mLockscreenUserManager.mCurrentProfiles.size());
    }

    @Test
    @EnableFlags(FLAG_ALLOW_PRIVATE_PROFILE)
    public void testProfileUnAvailabilityIntent() {
        mLockscreenUserManager.mCurrentProfiles.clear();
        assertEquals(0, mLockscreenUserManager.mCurrentProfiles.size());
        mLockscreenUserManager.mCurrentProfiles.append(0, mock(UserInfo.class));
        simulateProfileAvailabilityActions(Intent.ACTION_PROFILE_UNAVAILABLE);
        int numProfiles = android.multiuser.Flags.supportCommunalProfile() ? 3 : 2;
        assertEquals(numProfiles, mLockscreenUserManager.mCurrentProfiles.size());
    }

    @Test
    @DisableFlags(FLAG_ALLOW_PRIVATE_PROFILE)
    public void testManagedProfileAvailabilityIntent() {
        mLockscreenUserManager.mCurrentProfiles.clear();
        mLockscreenUserManager.mCurrentManagedProfiles.clear();
        assertEquals(0, mLockscreenUserManager.mCurrentProfiles.size());
        assertEquals(0, mLockscreenUserManager.mCurrentManagedProfiles.size());
        mLockscreenUserManager.mCurrentProfiles.append(0, mock(UserInfo.class));
        simulateProfileAvailabilityActions(Intent.ACTION_MANAGED_PROFILE_AVAILABLE);
        int numProfiles = android.multiuser.Flags.supportCommunalProfile() ? 3 : 2;
        assertEquals(numProfiles, mLockscreenUserManager.mCurrentProfiles.size());
        assertEquals(1, mLockscreenUserManager.mCurrentManagedProfiles.size());
    }

    @Test
    @DisableFlags(FLAG_ALLOW_PRIVATE_PROFILE)
    public void testManagedProfileUnAvailabilityIntent() {
        mLockscreenUserManager.mCurrentProfiles.clear();
        mLockscreenUserManager.mCurrentManagedProfiles.clear();
        assertEquals(0, mLockscreenUserManager.mCurrentProfiles.size());
        assertEquals(0, mLockscreenUserManager.mCurrentManagedProfiles.size());
        mLockscreenUserManager.mCurrentProfiles.append(0, mock(UserInfo.class));
        simulateProfileAvailabilityActions(Intent.ACTION_MANAGED_PROFILE_UNAVAILABLE);
        int numProfiles = android.multiuser.Flags.supportCommunalProfile() ? 3 : 2;
        assertEquals(numProfiles, mLockscreenUserManager.mCurrentProfiles.size());
        assertEquals(1, mLockscreenUserManager.mCurrentManagedProfiles.size());
    }

    private void simulateProfileAvailabilityActions(String intentAction) {
        BroadcastReceiver broadcastReceiver =
                mLockscreenUserManager.getBaseBroadcastReceiverForTest();
        final Intent intent = new Intent(intentAction);
        intent.putExtra(Intent.EXTRA_USER_HANDLE, TEST_PROFILE_USERHANDLE);
        broadcastReceiver.onReceive(mContext, intent);
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
                    mockExecutorHandler(mMainExecutor),
                    mockExecutorHandler(mBackgroundExecutor),
                    mBackgroundExecutor,
                    mDeviceProvisionedController,
                    mKeyguardStateController,
                    mSettings,
                    mock(DumpManager.class),
                    mock(LockPatternUtils.class),
                    mFakeFeatureFlags);
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
