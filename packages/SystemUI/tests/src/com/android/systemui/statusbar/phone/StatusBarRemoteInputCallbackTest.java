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

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.internal.verification.VerificationModeFactory.times;

import android.content.Intent;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;

import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.statusbar.ActionClickLogger;
import com.android.systemui.statusbar.CommandQueue;
import com.android.systemui.statusbar.NotificationLockscreenUserManager;
import com.android.systemui.statusbar.SysuiStatusBarStateController;
import com.android.systemui.statusbar.notification.NotificationEntryManager;
import com.android.systemui.statusbar.notification.collection.legacy.NotificationGroupManagerLegacy;
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
@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper
public class StatusBarRemoteInputCallbackTest extends SysuiTestCase {
    @Mock private NotificationEntryManager mEntryManager;
    @Mock private DeviceProvisionedController mDeviceProvisionedController;
    @Mock private ShadeController mShadeController;
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
        mDependency.injectTestDependency(NotificationEntryManager.class, mEntryManager);
        mDependency.injectTestDependency(DeviceProvisionedController.class,
                mDeviceProvisionedController);
        mDependency.injectTestDependency(ShadeController.class, mShadeController);
        mDependency.injectTestDependency(NotificationLockscreenUserManager.class,
                mNotificationLockscreenUserManager);

        mRemoteInputCallback = spy(new StatusBarRemoteInputCallback(mContext,
                mock(NotificationGroupManagerLegacy.class), mNotificationLockscreenUserManager,
                mKeyguardStateController, mStatusBarStateController, mStatusBarKeyguardViewManager,
                mActivityStarter, mShadeController, new CommandQueue(mContext),
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

}