/*
 * Copyright (C) 2018 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.android.systemui.statusbar.phone;

import static android.content.Intent.ACTION_DEVICE_LOCKED_CHANGED;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.internal.verification.VerificationModeFactory.times;

import android.content.Intent;
import android.platform.test.annotations.EnableFlags;
import android.testing.TestableLooper;
import android.view.View;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.settings.FakeDisplayTracker;
import com.android.systemui.shade.ShadeController;
import com.android.systemui.statusbar.ActionClickLogger;
import com.android.systemui.statusbar.CommandQueue;
import com.android.systemui.statusbar.NotificationLockscreenUserManager;
import com.android.systemui.statusbar.SysuiStatusBarStateController;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.notification.collection.render.GroupExpansionManager;
import com.android.systemui.statusbar.notification.row.ExpandableNotificationRow;
import com.android.systemui.statusbar.notification.row.NotificationContentView;
import com.android.systemui.statusbar.policy.DeviceProvisionedController;
import com.android.systemui.statusbar.policy.KeyguardStateController;
import com.android.systemui.util.concurrency.FakeExecutor;
import com.android.systemui.util.time.FakeSystemClock;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@SmallTest
@RunWith(AndroidJUnit4.class)
@TestableLooper.RunWithLooper
public class StatusBarRemoteInputCallbackTest extends SysuiTestCase {

    @Mock private GroupExpansionManager mGroupExpansionManager;
    @Mock private DeviceProvisionedController mDeviceProvisionedController;
    @Mock private com.android.systemui.shade.ShadeController mShadeController;
    @Mock private NotificationLockscreenUserManager mNotificationLockscreenUserManager;
    @Mock private KeyguardStateController mKeyguardStateController;
    @Mock private SysuiStatusBarStateController mStatusBarStateController;
    @Mock private StatusBarKeyguardViewManager mStatusBarKeyguardViewManager;
    @Mock private ActivityStarter mActivityStarter;
    private final FakeExecutor mFakeExecutor = new FakeExecutor(new FakeSystemClock());

    private int mCurrentUserId = 0;
    private StatusBarRemoteInputCallback mRemoteInputCallback;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mDependency.injectTestDependency(DeviceProvisionedController.class,
                mDeviceProvisionedController);
        mDependency.injectTestDependency(ShadeController.class, mShadeController);
        mDependency.injectTestDependency(NotificationLockscreenUserManager.class,
                mNotificationLockscreenUserManager);

        mRemoteInputCallback = spy(new StatusBarRemoteInputCallback(mContext,
                mGroupExpansionManager, mNotificationLockscreenUserManager,
                mKeyguardStateController, mStatusBarStateController, mStatusBarKeyguardViewManager,
                mActivityStarter, mShadeController,
                new CommandQueue(mContext, new FakeDisplayTracker(mContext)),
                mock(ActionClickLogger.class), mFakeExecutor));
        mRemoteInputCallback.mChallengeReceiver = mRemoteInputCallback.new ChallengeReceiver();
    }

    @Test
    public void testActionDeviceLockedChangedWithDifferentUserIdCallsOnWorkChallengeChanged() {
        when(mNotificationLockscreenUserManager.getCurrentUserId()).thenReturn(mCurrentUserId);
        when(mNotificationLockscreenUserManager.isCurrentProfile(anyInt())).thenReturn(true);
        Intent intent = new Intent()
                .setAction(ACTION_DEVICE_LOCKED_CHANGED)
                .putExtra(Intent.EXTRA_USER_HANDLE, mCurrentUserId + 1);
        mRemoteInputCallback.mChallengeReceiver.onReceive(mContext, intent);
        verify(mRemoteInputCallback, times(1)).onWorkChallengeChanged();
    }

    @Test
    public void testShowGenericBouncer_onLockedRemoteInput() {
        mRemoteInputCallback.onLockedRemoteInput(
                mock(ExpandableNotificationRow.class), mock(View.class));

        verify(mStatusBarKeyguardViewManager).showBouncer(true);
    }

    @Test
    public void onMakeExpandedVisibleForRemoteInput_collapsedGroup_expandGroupExpansion() {
        // GIVEN
        final Runnable onExpandedVisibleRunner = mock(Runnable.class);

        final ExpandableNotificationRow enr = mock(ExpandableNotificationRow.class);
        final NotificationContentView privateLayout = mock(NotificationContentView.class);
        final NotificationEntry enrEntry = mock(NotificationEntry.class);

        when(enr.getPrivateLayout()).thenReturn(privateLayout);
        when(enr.getEntry()).thenReturn(enrEntry);
        when(enr.isChildInGroup()).thenReturn(true);
        when(enr.areChildrenExpanded()).thenReturn(false);

        // WHEN
        mRemoteInputCallback.onMakeExpandedVisibleForRemoteInput(
                enr, mock(View.class), false, onExpandedVisibleRunner);

        // THEN
        verify(mGroupExpansionManager).toggleGroupExpansion(enrEntry);
        verify(enr).setUserExpanded(true);
        verify(privateLayout).setOnExpandedVisibleListener(onExpandedVisibleRunner);
    }

    @Test
    public void onMakeExpandedVisibleForRemoteInput_expandedGroup_setUserExpandedTrue() {
        // GIVEN
        final Runnable onExpandedVisibleRunner = mock(Runnable.class);

        final ExpandableNotificationRow enr = mock(ExpandableNotificationRow.class);
        final NotificationContentView privateLayout = mock(NotificationContentView.class);
        final NotificationEntry enrEntry = mock(NotificationEntry.class);

        when(enr.getPrivateLayout()).thenReturn(privateLayout);
        when(enr.getEntry()).thenReturn(enrEntry);
        when(enr.isChildInGroup()).thenReturn(true);
        when(enr.areChildrenExpanded()).thenReturn(true);

        // WHEN
        mRemoteInputCallback.onMakeExpandedVisibleForRemoteInput(
                enr, mock(View.class), false, onExpandedVisibleRunner);

        // THEN
        verify(mGroupExpansionManager, never()).toggleGroupExpansion(any());
        verify(enr).setUserExpanded(true);
        verify(privateLayout).setOnExpandedVisibleListener(onExpandedVisibleRunner);
    }

    @Test
    public void onMakeExpandedVisibleForRemoteInput_nonGroupNotifications_setUserExpandedTrue() {
        // GIVEN
        final Runnable onExpandedVisibleRunner = mock(Runnable.class);

        final ExpandableNotificationRow enr = mock(ExpandableNotificationRow.class);
        final NotificationContentView privateLayout = mock(NotificationContentView.class);
        final NotificationEntry enrEntry = mock(NotificationEntry.class);

        when(enr.getPrivateLayout()).thenReturn(privateLayout);
        when(enr.getEntry()).thenReturn(enrEntry);
        when(enr.isChildInGroup()).thenReturn(false);

        // WHEN
        mRemoteInputCallback.onMakeExpandedVisibleForRemoteInput(
                enr, mock(View.class), false, onExpandedVisibleRunner);

        // THEN
        verify(mGroupExpansionManager, never()).toggleGroupExpansion(any());
        verify(enr).setUserExpanded(true);
        verify(privateLayout).setOnExpandedVisibleListener(onExpandedVisibleRunner);
    }

    @Test
    @EnableFlags(ExpandHeadsUpOnInlineReply.FLAG_NAME)
    public void onMakeExpandedVisibleForRemoteInput_notExpandedGroup_toggleExpansion() {
        // GIVEN
        final Runnable onExpandedVisibleRunner = mock(Runnable.class);

        final ExpandableNotificationRow enr = mock(ExpandableNotificationRow.class);
        final NotificationContentView privateLayout = mock(NotificationContentView.class);
        final NotificationEntry enrEntry = mock(NotificationEntry.class);

        when(enr.getPrivateLayout()).thenReturn(privateLayout);
        when(enr.getEntry()).thenReturn(enrEntry);
        when(enr.isChildInGroup()).thenReturn(true);
        when(enr.areChildrenExpanded()).thenReturn(false);

        // WHEN
        mRemoteInputCallback.onMakeExpandedVisibleForRemoteInput(
                enr, mock(View.class), false, onExpandedVisibleRunner);

        // THEN
        verify(mGroupExpansionManager).toggleGroupExpansion(enrEntry);
        verify(enr, never()).setUserExpanded(anyBoolean());
        verify(privateLayout, never()).setOnExpandedVisibleListener(any());
    }

    @Test
    @EnableFlags(ExpandHeadsUpOnInlineReply.FLAG_NAME)
    public void onMakeExpandedVisibleForRemoteInput_expandedGroup_notToggleExpansion() {
        // GIVEN
        final Runnable onExpandedVisibleRunner = mock(Runnable.class);

        final ExpandableNotificationRow enr = mock(ExpandableNotificationRow.class);
        final NotificationContentView privateLayout = mock(NotificationContentView.class);
        final NotificationEntry enrEntry = mock(NotificationEntry.class);

        when(enr.getPrivateLayout()).thenReturn(privateLayout);
        when(enr.getEntry()).thenReturn(enrEntry);
        when(enr.isChildInGroup()).thenReturn(true);
        when(enr.areChildrenExpanded()).thenReturn(true);

        // WHEN
        mRemoteInputCallback.onMakeExpandedVisibleForRemoteInput(
                enr, mock(View.class), false, onExpandedVisibleRunner);

        // THEN
        verify(mGroupExpansionManager, never()).toggleGroupExpansion(enrEntry);
        verify(enr, never()).setUserExpanded(anyBoolean());
        verify(privateLayout, never()).setOnExpandedVisibleListener(any());
    }

    @Test
    @EnableFlags(ExpandHeadsUpOnInlineReply.FLAG_NAME)
    public void onMakeExpandedVisibleForRemoteInput_notExpandedNotification_toggleExpansion() {
        // GIVEN
        final Runnable onExpandedVisibleRunner = mock(Runnable.class);

        final ExpandableNotificationRow enr = mock(ExpandableNotificationRow.class);
        final NotificationContentView privateLayout = mock(NotificationContentView.class);
        final NotificationEntry enrEntry = mock(NotificationEntry.class);

        when(enr.getPrivateLayout()).thenReturn(privateLayout);
        when(enr.getEntry()).thenReturn(enrEntry);
        when(enr.isChildInGroup()).thenReturn(false);
        when(enr.isExpanded()).thenReturn(false);

        // WHEN
        mRemoteInputCallback.onMakeExpandedVisibleForRemoteInput(
                enr, mock(View.class), false, onExpandedVisibleRunner);

        // THEN
        verify(enr).toggleExpansionState();
        verify(privateLayout).setOnExpandedVisibleListener(onExpandedVisibleRunner);
        verify(enr, never()).setUserExpanded(anyBoolean());
        verify(mGroupExpansionManager, never()).toggleGroupExpansion(any());
    }

    @Test
    @EnableFlags(ExpandHeadsUpOnInlineReply.FLAG_NAME)
    public void onMakeExpandedVisibleForRemoteInput_expandedNotification_notToggleExpansion() {
        // GIVEN
        final Runnable onExpandedVisibleRunner = mock(Runnable.class);

        final ExpandableNotificationRow enr = mock(ExpandableNotificationRow.class);
        final NotificationContentView privateLayout = mock(NotificationContentView.class);
        final NotificationEntry enrEntry = mock(NotificationEntry.class);

        when(enr.getPrivateLayout()).thenReturn(privateLayout);
        when(enr.getEntry()).thenReturn(enrEntry);
        when(enr.isChildInGroup()).thenReturn(false);
        when(enr.isExpanded()).thenReturn(true);

        // WHEN
        mRemoteInputCallback.onMakeExpandedVisibleForRemoteInput(
                enr, mock(View.class), false, onExpandedVisibleRunner);

        // THEN
        verify(enr, never()).toggleExpansionState();
        verify(privateLayout, never()).setOnExpandedVisibleListener(onExpandedVisibleRunner);
        verify(enr, never()).setUserExpanded(anyBoolean());
        verify(mGroupExpansionManager, never()).toggleGroupExpansion(any());
    }
}
