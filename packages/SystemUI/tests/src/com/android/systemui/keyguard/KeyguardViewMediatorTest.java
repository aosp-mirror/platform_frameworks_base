/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.systemui.keyguard;

import static android.view.WindowManagerPolicyConstants.OFF_BECAUSE_OF_USER;

import static com.android.internal.widget.LockPatternUtils.StrongAuthTracker.STRONG_AUTH_REQUIRED_AFTER_DPM_LOCK_NOW;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.admin.DevicePolicyManager;
import android.app.trust.TrustManager;
import android.os.PowerManager;
import android.os.PowerManager.WakeLock;
import android.telephony.TelephonyManager;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;

import androidx.test.filters.SmallTest;

import com.android.internal.jank.InteractionJankMonitor;
import com.android.internal.widget.LockPatternUtils;
import com.android.keyguard.KeyguardDisplayManager;
import com.android.keyguard.KeyguardSecurityView;
import com.android.keyguard.KeyguardUpdateMonitor;
import com.android.keyguard.mediator.ScreenOnCoordinator;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.animation.ActivityLaunchAnimator;
import com.android.systemui.broadcast.BroadcastDispatcher;
import com.android.systemui.classifier.FalsingCollectorFake;
import com.android.systemui.dreams.DreamOverlayStateController;
import com.android.systemui.dump.DumpManager;
import com.android.systemui.navigationbar.NavigationModeController;
import com.android.systemui.statusbar.NotificationShadeDepthController;
import com.android.systemui.statusbar.NotificationShadeWindowController;
import com.android.systemui.statusbar.SysuiStatusBarStateController;
import com.android.systemui.statusbar.phone.DozeParameters;
import com.android.systemui.statusbar.phone.ScreenOffAnimationController;
import com.android.systemui.statusbar.phone.StatusBarKeyguardViewManager;
import com.android.systemui.statusbar.policy.KeyguardStateController;
import com.android.systemui.statusbar.policy.UserSwitcherController;
import com.android.systemui.util.DeviceConfigProxy;
import com.android.systemui.util.DeviceConfigProxyFake;
import com.android.systemui.util.concurrency.FakeExecutor;
import com.android.systemui.util.time.FakeSystemClock;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import dagger.Lazy;

@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper
@SmallTest
public class KeyguardViewMediatorTest extends SysuiTestCase {
    private KeyguardViewMediator mViewMediator;

    private @Mock DevicePolicyManager mDevicePolicyManager;
    private @Mock LockPatternUtils mLockPatternUtils;
    private @Mock KeyguardUpdateMonitor mUpdateMonitor;
    private @Mock StatusBarKeyguardViewManager mStatusBarKeyguardViewManager;
    private @Mock BroadcastDispatcher mBroadcastDispatcher;
    private @Mock DismissCallbackRegistry mDismissCallbackRegistry;
    private @Mock DumpManager mDumpManager;
    private @Mock PowerManager mPowerManager;
    private @Mock TrustManager mTrustManager;
    private @Mock UserSwitcherController mUserSwitcherController;
    private @Mock NavigationModeController mNavigationModeController;
    private @Mock KeyguardDisplayManager mKeyguardDisplayManager;
    private @Mock DozeParameters mDozeParameters;
    private @Mock SysuiStatusBarStateController mStatusBarStateController;
    private @Mock KeyguardStateController mKeyguardStateController;
    private @Mock NotificationShadeDepthController mNotificationShadeDepthController;
    private @Mock KeyguardUnlockAnimationController mKeyguardUnlockAnimationController;
    private @Mock ScreenOffAnimationController mScreenOffAnimationController;
    private @Mock InteractionJankMonitor mInteractionJankMonitor;
    private @Mock ScreenOnCoordinator mScreenOnCoordinator;
    private @Mock Lazy<NotificationShadeWindowController> mNotificationShadeWindowControllerLazy;
    private @Mock DreamOverlayStateController mDreamOverlayStateController;
    private @Mock ActivityLaunchAnimator mActivityLaunchAnimator;
    private DeviceConfigProxy mDeviceConfig = new DeviceConfigProxyFake();
    private FakeExecutor mUiBgExecutor = new FakeExecutor(new FakeSystemClock());

    private FalsingCollectorFake mFalsingCollector;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        mFalsingCollector = new FalsingCollectorFake();

        when(mLockPatternUtils.getDevicePolicyManager()).thenReturn(mDevicePolicyManager);
        when(mPowerManager.newWakeLock(anyInt(), any())).thenReturn(mock(WakeLock.class));
        when(mInteractionJankMonitor.begin(any(), anyInt())).thenReturn(true);
        when(mInteractionJankMonitor.end(anyInt())).thenReturn(true);

        createAndStartViewMediator();
    }

    @Test
    public void testOnGoingToSleep_UpdatesKeyguardGoingAway() {
        mViewMediator.onStartedGoingToSleep(OFF_BECAUSE_OF_USER);
        verify(mUpdateMonitor).dispatchKeyguardGoingAway(false);
        verify(mStatusBarKeyguardViewManager, never()).setKeyguardGoingAwayState(anyBoolean());
    }

    @Test
    public void testRegisterDumpable() {
        verify(mDumpManager).registerDumpable(KeyguardViewMediator.class.getName(), mViewMediator);
        verify(mStatusBarKeyguardViewManager, never()).setKeyguardGoingAwayState(anyBoolean());
    }

    @Test
    public void testKeyguardGone_notGoingaway() {
        mViewMediator.mViewMediatorCallback.keyguardGone();
        verify(mStatusBarKeyguardViewManager).setKeyguardGoingAwayState(eq(false));
    }

    @Test
    public void testIsAnimatingScreenOff() {
        when(mDozeParameters.shouldControlUnlockedScreenOff()).thenReturn(true);
        when(mDozeParameters.shouldAnimateDozingChange()).thenReturn(true);

        mViewMediator.onFinishedGoingToSleep(OFF_BECAUSE_OF_USER, false);
        mViewMediator.setDozing(true);

        // Mid-doze, we should be animating the screen off animation.
        mViewMediator.onDozeAmountChanged(0.5f, 0.5f);
        assertTrue(mViewMediator.isAnimatingScreenOff());

        // Once we're 100% dozed, the screen off animation should be completed.
        mViewMediator.onDozeAmountChanged(1f, 1f);
        assertFalse(mViewMediator.isAnimatingScreenOff());
    }

    @Test
    @TestableLooper.RunWithLooper(setAsMainLooper = true)
    public void restoreBouncerWhenSimLockedAndKeyguardIsGoingAway() {
        // When showing and provisioned
        mViewMediator.onSystemReady();
        when(mUpdateMonitor.isDeviceProvisioned()).thenReturn(true);
        mViewMediator.setShowingLocked(true);

        // and a SIM becomes locked and requires a PIN
        mViewMediator.mUpdateCallback.onSimStateChanged(
                1 /* subId */,
                0 /* slotId */,
                TelephonyManager.SIM_STATE_PIN_REQUIRED);

        // and the keyguard goes away
        mViewMediator.setShowingLocked(false);
        when(mStatusBarKeyguardViewManager.isShowing()).thenReturn(false);
        mViewMediator.mUpdateCallback.onKeyguardVisibilityChanged(false);

        TestableLooper.get(this).processAllMessages();

        // then make sure it comes back
        verify(mStatusBarKeyguardViewManager, atLeast(1)).show(null);
    }

    @Test
    @TestableLooper.RunWithLooper(setAsMainLooper = true)
    public void restoreBouncerWhenSimLockedAndKeyguardIsGoingAway_initiallyNotShowing() {
        // When showing and provisioned
        mViewMediator.onSystemReady();
        when(mUpdateMonitor.isDeviceProvisioned()).thenReturn(true);
        mViewMediator.setShowingLocked(false);

        // and a SIM becomes locked and requires a PIN
        mViewMediator.mUpdateCallback.onSimStateChanged(
                1 /* subId */,
                0 /* slotId */,
                TelephonyManager.SIM_STATE_PIN_REQUIRED);

        // and the keyguard goes away
        mViewMediator.setShowingLocked(false);
        when(mStatusBarKeyguardViewManager.isShowing()).thenReturn(false);
        mViewMediator.mUpdateCallback.onKeyguardVisibilityChanged(false);

        TestableLooper.get(this).processAllMessages();

        // then make sure it comes back
        verify(mStatusBarKeyguardViewManager, atLeast(1)).show(null);
    }

    @Test
    public void testBouncerPrompt_deviceLockedByAdmin() {
        // GIVEN no trust agents enabled and biometrics aren't enrolled
        when(mUpdateMonitor.isTrustUsuallyManaged(anyInt())).thenReturn(false);
        when(mUpdateMonitor.isUnlockingWithBiometricsPossible(anyInt())).thenReturn(false);

        // WHEN the strong auth reason is AFTER_DPM_LOCK_NOW
        KeyguardUpdateMonitor.StrongAuthTracker strongAuthTracker =
                mock(KeyguardUpdateMonitor.StrongAuthTracker.class);
        when(mUpdateMonitor.getStrongAuthTracker()).thenReturn(strongAuthTracker);
        when(strongAuthTracker.getStrongAuthForUser(anyInt())).thenReturn(
                STRONG_AUTH_REQUIRED_AFTER_DPM_LOCK_NOW);

        // THEN the bouncer prompt reason should return PROMPT_REASON_DEVICE_ADMIN
        assertEquals(KeyguardSecurityView.PROMPT_REASON_DEVICE_ADMIN,
                mViewMediator.mViewMediatorCallback.getBouncerPromptReason());
    }

    @Test
    public void testHideSurfaceBehindKeyguardMarksKeyguardNotGoingAway() {
        mViewMediator.hideSurfaceBehindKeyguard();

        verify(mKeyguardStateController).notifyKeyguardGoingAway(false);
    }

    private void createAndStartViewMediator() {
        mViewMediator = new KeyguardViewMediator(
                mContext,
                mFalsingCollector,
                mLockPatternUtils,
                mBroadcastDispatcher,
                () -> mStatusBarKeyguardViewManager,
                mDismissCallbackRegistry,
                mUpdateMonitor,
                mDumpManager,
                mUiBgExecutor,
                mPowerManager,
                mTrustManager,
                mUserSwitcherController,
                mDeviceConfig,
                mNavigationModeController,
                mKeyguardDisplayManager,
                mDozeParameters,
                mStatusBarStateController,
                mKeyguardStateController,
                () -> mKeyguardUnlockAnimationController,
                mScreenOffAnimationController,
                () -> mNotificationShadeDepthController,
                mScreenOnCoordinator,
                mInteractionJankMonitor,
                mDreamOverlayStateController,
                mNotificationShadeWindowControllerLazy,
                () -> mActivityLaunchAnimator);
        mViewMediator.start();
    }
}
