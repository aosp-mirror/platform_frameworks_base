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

package com.android.systemui.statusbar.phone;

import static com.android.systemui.statusbar.phone.BiometricUnlockController.MODE_NONE;
import static com.android.systemui.statusbar.phone.BiometricUnlockController.MODE_WAKE_AND_UNLOCK;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.hardware.biometrics.BiometricSourceType;
import android.os.Handler;
import android.os.PowerManager;
import android.os.UserHandle;
import android.testing.TestableLooper.RunWithLooper;
import android.testing.TestableResources;
import android.view.ViewRootImpl;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import com.android.internal.logging.MetricsLogger;
import com.android.internal.util.LatencyTracker;
import com.android.keyguard.KeyguardUpdateMonitor;
import com.android.keyguard.logging.BiometricUnlockLogger;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.biometrics.AuthController;
import com.android.systemui.dump.DumpManager;
import com.android.systemui.keyguard.KeyguardViewMediator;
import com.android.systemui.keyguard.WakefulnessLifecycle;
import com.android.systemui.keyguard.domain.interactor.BiometricUnlockInteractor;
import com.android.systemui.keyguard.domain.interactor.KeyguardTransitionInteractor;
import com.android.systemui.keyguard.shared.model.BiometricUnlockSource;
import com.android.systemui.keyguard.shared.model.KeyguardState;
import com.android.systemui.keyguard.shared.model.TransitionState;
import com.android.systemui.keyguard.shared.model.TransitionStep;
import com.android.systemui.log.SessionTracker;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.statusbar.NotificationMediaManager;
import com.android.systemui.statusbar.NotificationShadeWindowController;
import com.android.systemui.statusbar.VibratorHelper;
import com.android.systemui.statusbar.policy.KeyguardStateController;
import com.android.systemui.user.domain.interactor.SelectedUserInteractor;
import com.android.systemui.util.kotlin.JavaAdapter;
import com.android.systemui.util.time.FakeSystemClock;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@SmallTest
@RunWith(AndroidJUnit4.class)
@RunWithLooper
public class BiometricsUnlockControllerTest extends SysuiTestCase {

    @Mock
    private DumpManager mDumpManager;
    @Mock
    private NotificationMediaManager mMediaManager;
    @Mock
    private PowerManager mPowerManager;
    @Mock
    private KeyguardUpdateMonitor mUpdateMonitor;
    @Mock
    private KeyguardUpdateMonitor.StrongAuthTracker mStrongAuthTracker;
    @Mock
    private StatusBarKeyguardViewManager mStatusBarKeyguardViewManager;
    @Mock
    private NotificationShadeWindowController mNotificationShadeWindowController;
    @Mock
    private DozeScrimController mDozeScrimController;
    @Mock
    private KeyguardViewMediator mKeyguardViewMediator;
    @Mock
    private BiometricUnlockController.BiometricUnlockEventsListener mBiometricUnlockEventsListener;
    @Mock
    private KeyguardStateController mKeyguardStateController;
    @Mock
    private Handler mHandler;
    @Mock
    private KeyguardBypassController mKeyguardBypassController;
    @Mock
    private AuthController mAuthController;
    @Mock
    private MetricsLogger mMetricsLogger;
    @Mock
    private NotificationMediaManager mNotificationMediaManager;
    @Mock
    private WakefulnessLifecycle mWakefulnessLifecycle;
    @Mock
    private StatusBarStateController mStatusBarStateController;
    @Mock
    private SessionTracker mSessionTracker;
    @Mock
    private LatencyTracker mLatencyTracker;
    @Mock
    private ScreenOffAnimationController mScreenOffAnimationController;
    @Mock
    private VibratorHelper mVibratorHelper;
    @Mock
    private BiometricUnlockLogger mLogger;
    @Mock
    private ViewRootImpl mViewRootImpl;
    @Mock
    private SelectedUserInteractor mSelectedUserInteractor;
    @Mock
    private BiometricUnlockInteractor mBiometricUnlockInteractor;
    @Mock
    private KeyguardTransitionInteractor mKeyguardTransitionInteractor;
    private final FakeSystemClock mSystemClock = new FakeSystemClock();
    private BiometricUnlockController mBiometricUnlockController;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        when(mKeyguardStateController.isShowing()).thenReturn(true);
        when(mUpdateMonitor.isDeviceInteractive()).thenReturn(true);
        when(mKeyguardStateController.isFaceEnrolledAndEnabled()).thenReturn(true);
        when(mKeyguardStateController.isUnlocked()).thenReturn(false);
        when(mKeyguardBypassController.onBiometricAuthenticated(any(), anyBoolean()))
                .thenReturn(true);
        when(mAuthController.isUdfpsFingerDown()).thenReturn(false);
        when(mVibratorHelper.hasVibrator()).thenReturn(true);
        mDependency.injectTestDependency(NotificationMediaManager.class, mMediaManager);
        mBiometricUnlockController = createController(false);
        when(mUpdateMonitor.getStrongAuthTracker()).thenReturn(mStrongAuthTracker);
        when(mStatusBarKeyguardViewManager.getViewRootImpl()).thenReturn(mViewRootImpl);
    }

    BiometricUnlockController createController(boolean orderUnlockAndWake) {
        TestableResources res = getContext().getOrCreateTestableResources();
        res.addOverride(com.android.internal.R.bool.config_orderUnlockAndWake, orderUnlockAndWake);
        BiometricUnlockController biometricUnlockController = new BiometricUnlockController(
                mDozeScrimController,
                mKeyguardViewMediator,
                mNotificationShadeWindowController, mKeyguardStateController, mHandler,
                mUpdateMonitor, res.getResources(), mKeyguardBypassController,
                mMetricsLogger, mDumpManager, mPowerManager, mLogger,
                mNotificationMediaManager, mWakefulnessLifecycle,
                mAuthController, mStatusBarStateController,
                mSessionTracker, mLatencyTracker, mScreenOffAnimationController, mVibratorHelper,
                mSystemClock,
                () -> mSelectedUserInteractor,
                mBiometricUnlockInteractor,
                mock(JavaAdapter.class),
                mKeyguardTransitionInteractor
        );
        biometricUnlockController.setKeyguardViewController(mStatusBarKeyguardViewManager);
        biometricUnlockController.addListener(mBiometricUnlockEventsListener);

        return biometricUnlockController;
    }

    @Test
    public void onBiometricAuthenticated_fingerprintAndBiometricsDisallowed_showPrimaryBouncer() {
        when(mUpdateMonitor.isUnlockingWithBiometricAllowed(true /* isStrongBiometric */))
                .thenReturn(false);
        mBiometricUnlockController.onBiometricAuthenticated(UserHandle.USER_CURRENT,
                BiometricSourceType.FINGERPRINT, true /* isStrongBiometric */);
        verify(mStatusBarKeyguardViewManager).showPrimaryBouncer(anyBoolean());
        verify(mStatusBarKeyguardViewManager, never()).notifyKeyguardAuthenticated(anyBoolean());
        assertThat(mBiometricUnlockController.getMode())
                .isEqualTo(BiometricUnlockController.MODE_SHOW_BOUNCER);
    }

    @Test
    public void onBiometricAuthenticated_fingerprint_nonStrongBioDisallowed_showPrimaryBouncer() {
        when(mUpdateMonitor.isUnlockingWithBiometricAllowed(false /* isStrongBiometric */))
                .thenReturn(false);
        mBiometricUnlockController.onBiometricAuthenticated(UserHandle.USER_CURRENT,
                BiometricSourceType.FINGERPRINT, false /* isStrongBiometric */);
        verify(mStatusBarKeyguardViewManager).showPrimaryBouncer(anyBoolean());
        assertThat(mBiometricUnlockController.getMode())
                .isEqualTo(BiometricUnlockController.MODE_SHOW_BOUNCER);
        assertThat(mBiometricUnlockController.getBiometricType())
                .isEqualTo(BiometricSourceType.FINGERPRINT);
    }

    @Test
    public void onBiometricAuthenticated_whenFingerprintAndNotInteractive_wakeAndUnlock() {
        reset(mUpdateMonitor);
        reset(mStatusBarKeyguardViewManager);
        when(mKeyguardStateController.isShowing()).thenReturn(true);
        when(mUpdateMonitor.isUnlockingWithBiometricAllowed(anyBoolean())).thenReturn(true);
        when(mDozeScrimController.isPulsing()).thenReturn(true);
        // the value of isStrongBiometric doesn't matter here since we only care about the returned
        // value of isUnlockingWithBiometricAllowed()
        mBiometricUnlockController.onBiometricAuthenticated(UserHandle.USER_CURRENT,
                BiometricSourceType.FINGERPRINT, true /* isStrongBiometric */);

        verify(mKeyguardViewMediator).onWakeAndUnlocking(false);
        assertThat(mBiometricUnlockController.getMode())
                .isEqualTo(BiometricUnlockController.MODE_WAKE_AND_UNLOCK_PULSING);
    }

    @Test
    public void onBiometricAuthenticated_whenDeviceIsAlreadyUnlocked_wakeAndUnlock() {
        reset(mUpdateMonitor);
        reset(mStatusBarKeyguardViewManager);
        when(mKeyguardStateController.isShowing()).thenReturn(false);
        when(mKeyguardStateController.isUnlocked()).thenReturn(true);
        when(mUpdateMonitor.isUnlockingWithBiometricAllowed(anyBoolean())).thenReturn(true);
        when(mDozeScrimController.isPulsing()).thenReturn(false);
        // the value of isStrongBiometric doesn't matter here since we only care about the returned
        // value of isUnlockingWithBiometricAllowed()
        mBiometricUnlockController.onBiometricAuthenticated(UserHandle.USER_CURRENT,
                BiometricSourceType.FINGERPRINT, true /* isStrongBiometric */);

        verify(mKeyguardViewMediator).onWakeAndUnlocking(false);
        assertThat(mBiometricUnlockController.getMode())
                .isEqualTo(MODE_WAKE_AND_UNLOCK);
    }

    @Test
    public void onBiometricAuthenticated_whenFingerprint_notifyKeyguardAuthenticated() {
        when(mUpdateMonitor.isUnlockingWithBiometricAllowed(anyBoolean())).thenReturn(true);
        // the value of isStrongBiometric doesn't matter here since we only care about the returned
        // value of isUnlockingWithBiometricAllowed()
        mBiometricUnlockController.onBiometricAuthenticated(UserHandle.USER_CURRENT,
                BiometricSourceType.FINGERPRINT, true /* isStrongBiometric */);

        verify(mStatusBarKeyguardViewManager, never()).showPrimaryBouncer(anyBoolean());
        verify(mStatusBarKeyguardViewManager).notifyKeyguardAuthenticated(eq(false));
        assertThat(mBiometricUnlockController.getMode())
                .isEqualTo(BiometricUnlockController.MODE_UNLOCK_COLLAPSING);
    }

    @Test
    public void onBiometricAuthenticated_whenFingerprintOnBouncer_dismissBouncer() {
        when(mUpdateMonitor.isUnlockingWithBiometricAllowed(anyBoolean())).thenReturn(true);
        when(mStatusBarKeyguardViewManager.primaryBouncerIsOrWillBeShowing()).thenReturn(true);
        // the value of isStrongBiometric doesn't matter here since we only care about the returned
        // value of isUnlockingWithBiometricAllowed()
        mBiometricUnlockController.onBiometricAuthenticated(UserHandle.USER_CURRENT,
                BiometricSourceType.FINGERPRINT, true /* isStrongBiometric */);

        verify(mStatusBarKeyguardViewManager).notifyKeyguardAuthenticated(eq(false));
        assertThat(mBiometricUnlockController.getMode())
                .isEqualTo(BiometricUnlockController.MODE_DISMISS_BOUNCER);
    }

    @Test
    public void onBiometricAuthenticated_whenFace_dontDismissKeyguard() {
        when(mUpdateMonitor.isUnlockingWithBiometricAllowed(anyBoolean())).thenReturn(true);
        // the value of isStrongBiometric doesn't matter here since we only care about the returned
        // value of isUnlockingWithBiometricAllowed()
        mBiometricUnlockController.onBiometricAuthenticated(UserHandle.USER_CURRENT,
                BiometricSourceType.FACE, true /* isStrongBiometric */);

        verify(mStatusBarKeyguardViewManager, never()).notifyKeyguardAuthenticated(anyBoolean());
        assertThat(mBiometricUnlockController.getMode())
                .isEqualTo(BiometricUnlockController.MODE_NONE);
    }

    @Test
    public void onBiometricAuthenticated_whenFace_andBypass_dismissKeyguard() {
        when(mKeyguardBypassController.getBypassEnabled()).thenReturn(true);
        mBiometricUnlockController.setKeyguardViewController(mStatusBarKeyguardViewManager);

        when(mUpdateMonitor.isUnlockingWithBiometricAllowed(anyBoolean())).thenReturn(true);
        // the value of isStrongBiometric doesn't matter here since we only care about the returned
        // value of isUnlockingWithBiometricAllowed()
        mBiometricUnlockController.onBiometricAuthenticated(UserHandle.USER_CURRENT,
                BiometricSourceType.FACE, true /* isStrongBiometric */);

        verify(mStatusBarKeyguardViewManager).notifyKeyguardAuthenticated(eq(false));
        assertThat(mBiometricUnlockController.getMode())
                .isEqualTo(BiometricUnlockController.MODE_UNLOCK_COLLAPSING);
    }

    @Test
    public void onBiometricAuthenticated_whenFace_andNonBypassAndUdfps_dismissKeyguard() {
        when(mKeyguardBypassController.getBypassEnabled()).thenReturn(false);
        when(mAuthController.isUdfpsFingerDown()).thenReturn(true);
        mBiometricUnlockController.setKeyguardViewController(mStatusBarKeyguardViewManager);

        when(mUpdateMonitor.isUnlockingWithBiometricAllowed(anyBoolean())).thenReturn(true);
        // the value of isStrongBiometric doesn't matter here since we only care about the returned
        // value of isUnlockingWithBiometricAllowed()
        mBiometricUnlockController.onBiometricAuthenticated(UserHandle.USER_CURRENT,
                BiometricSourceType.FACE, true /* isStrongBiometric */);

        verify(mStatusBarKeyguardViewManager).notifyKeyguardAuthenticated(eq(false));
        assertThat(mBiometricUnlockController.getMode())
            .isEqualTo(BiometricUnlockController.MODE_UNLOCK_COLLAPSING);
    }

    @Test
    public void onBiometricAuthenticated_whenFace_andBypass_encrypted_showPrimaryBouncer() {
        reset(mUpdateMonitor);
        when(mUpdateMonitor.getStrongAuthTracker()).thenReturn(mStrongAuthTracker);
        when(mKeyguardBypassController.getBypassEnabled()).thenReturn(true);
        mBiometricUnlockController.setKeyguardViewController(mStatusBarKeyguardViewManager);

        when(mUpdateMonitor.isUnlockingWithBiometricAllowed(anyBoolean())).thenReturn(false);
        // the value of isStrongBiometric doesn't matter here since we only care about the returned
        // value of isUnlockingWithBiometricAllowed()
        mBiometricUnlockController.onBiometricAuthenticated(UserHandle.USER_CURRENT,
                BiometricSourceType.FACE, true /* isStrongBiometric */);

        verify(mStatusBarKeyguardViewManager).showPrimaryBouncer(anyBoolean());
        assertThat(mBiometricUnlockController.getMode())
                .isEqualTo(BiometricUnlockController.MODE_SHOW_BOUNCER);
    }

    @Test
    public void onBiometricAuthenticated_onLockScreen() {
        // GIVEN not dozing
        when(mUpdateMonitor.isDeviceInteractive()).thenReturn(true);

        // WHEN we want to unlock collapse
        mBiometricUnlockController.startWakeAndUnlock(
                BiometricUnlockController.MODE_UNLOCK_COLLAPSING,
                BiometricUnlockSource.FINGERPRINT_SENSOR
        );

        verify(mStatusBarKeyguardViewManager).notifyKeyguardAuthenticated(
                /* strongAuth */ eq(false));
    }

    @Test
    public void onBiometricAuthenticated_whenFace_noBypass_encrypted_doNothing() {
        reset(mUpdateMonitor);
        when(mUpdateMonitor.getStrongAuthTracker()).thenReturn(mStrongAuthTracker);
        mBiometricUnlockController.setKeyguardViewController(mStatusBarKeyguardViewManager);

        when(mUpdateMonitor.isUnlockingWithBiometricAllowed(anyBoolean())).thenReturn(false);
        // the value of isStrongBiometric doesn't matter here since we only care about the returned
        // value of isUnlockingWithBiometricAllowed()
        mBiometricUnlockController.onBiometricAuthenticated(UserHandle.USER_CURRENT,
                BiometricSourceType.FACE, true /* isStrongBiometric */);

        verify(mStatusBarKeyguardViewManager, never()).showPrimaryBouncer(anyBoolean());
        assertThat(mBiometricUnlockController.getMode())
                .isEqualTo(BiometricUnlockController.MODE_NONE);
    }

    @Test
    public void onBiometricAuthenticated_whenFaceOnBouncer_dismissBouncer() {
        when(mUpdateMonitor.isUnlockingWithBiometricAllowed(anyBoolean())).thenReturn(true);
        when(mStatusBarKeyguardViewManager.primaryBouncerIsOrWillBeShowing()).thenReturn(true);
        // the value of isStrongBiometric doesn't matter here since we only care about the returned
        // value of isUnlockingWithBiometricAllowed()
        mBiometricUnlockController.onBiometricAuthenticated(UserHandle.USER_CURRENT,
                BiometricSourceType.FACE, true /* isStrongBiometric */);

        verify(mStatusBarKeyguardViewManager).notifyKeyguardAuthenticated(eq(false));
        assertThat(mBiometricUnlockController.getMode())
                .isEqualTo(BiometricUnlockController.MODE_DISMISS_BOUNCER);
        assertThat(mBiometricUnlockController.getBiometricType())
                .isEqualTo(BiometricSourceType.FACE);
    }

    @Test
    public void onBiometricAuthenticated_whenFaceOnAlternateBouncer_dismissBouncer() {
        when(mUpdateMonitor.isUnlockingWithBiometricAllowed(anyBoolean())).thenReturn(true);
        when(mStatusBarKeyguardViewManager.primaryBouncerIsOrWillBeShowing()).thenReturn(false);
        when(mKeyguardTransitionInteractor.getCurrentState())
                .thenReturn(KeyguardState.ALTERNATE_BOUNCER);
        // the value of isStrongBiometric doesn't matter here since we only care about the returned
        // value of isUnlockingWithBiometricAllowed()
        mBiometricUnlockController.onBiometricAuthenticated(UserHandle.USER_CURRENT,
                BiometricSourceType.FACE, true /* isStrongBiometric */);

        verify(mStatusBarKeyguardViewManager).notifyKeyguardAuthenticated(eq(false));
        assertThat(mBiometricUnlockController.getMode())
                .isEqualTo(BiometricUnlockController.MODE_DISMISS_BOUNCER);
        assertThat(mBiometricUnlockController.getBiometricType())
                .isEqualTo(BiometricSourceType.FACE);
    }

    @Test
    public void onBiometricAuthenticated_whenBypassOnBouncer_dismissBouncer() {
        reset(mKeyguardBypassController);
        when(mUpdateMonitor.isUnlockingWithBiometricAllowed(anyBoolean())).thenReturn(true);
        when(mKeyguardBypassController.getBypassEnabled()).thenReturn(true);
        when(mKeyguardBypassController.onBiometricAuthenticated(any(), anyBoolean()))
                .thenReturn(true);
        when(mStatusBarKeyguardViewManager.primaryBouncerIsOrWillBeShowing()).thenReturn(true);
        // the value of isStrongBiometric doesn't matter here since we only care about the returned
        // value of isUnlockingWithBiometricAllowed()
        mBiometricUnlockController.onBiometricAuthenticated(UserHandle.USER_CURRENT,
                BiometricSourceType.FACE, true /* isStrongBiometric */);

        verify(mStatusBarKeyguardViewManager).notifyKeyguardAuthenticated(eq(false));
        assertThat(mBiometricUnlockController.getMode())
                .isEqualTo(BiometricUnlockController.MODE_DISMISS_BOUNCER);
    }

    @Test
    public void onBiometricAuthenticated_whenFaceAndPulsing_dontDismissKeyguard() {
        reset(mUpdateMonitor);
        reset(mStatusBarKeyguardViewManager);
        when(mUpdateMonitor.isUnlockingWithBiometricAllowed(anyBoolean())).thenReturn(true);
        when(mDozeScrimController.isPulsing()).thenReturn(true);
        // the value of isStrongBiometric doesn't matter here since we only care about the returned
        // value of isUnlockingWithBiometricAllowed()
        mBiometricUnlockController.onBiometricAuthenticated(UserHandle.USER_CURRENT,
                BiometricSourceType.FACE, true /* isStrongBiometric */);

        assertThat(mBiometricUnlockController.getMode())
                .isEqualTo(BiometricUnlockController.MODE_ONLY_WAKE);
    }

    @Test
    public void onUdfpsConsecutivelyFailedThreeTimes_showPrimaryBouncer() {
        // GIVEN UDFPS is supported
        when(mUpdateMonitor.isUdfpsSupported()).thenReturn(true);

        // WHEN udfps fails once - then don't show the bouncer yet
        mBiometricUnlockController.onBiometricAuthFailed(BiometricSourceType.FINGERPRINT);
        verify(mStatusBarKeyguardViewManager, never()).showPrimaryBouncer(anyBoolean());

        // WHEN udfps fails the second time - then don't show the bouncer yet
        mBiometricUnlockController.onBiometricAuthFailed(BiometricSourceType.FINGERPRINT);
        verify(mStatusBarKeyguardViewManager, never()).showPrimaryBouncer(anyBoolean());

        // WHEN udpfs fails the third time
        mBiometricUnlockController.onBiometricAuthFailed(BiometricSourceType.FINGERPRINT);

        // THEN show the bouncer
        verify(mStatusBarKeyguardViewManager).showPrimaryBouncer(true);
    }

    @Test
    public void onFinishedGoingToSleep_authenticatesWhenPending() {
        when(mUpdateMonitor.isGoingToSleep()).thenReturn(true);
        mBiometricUnlockController.mWakefulnessObserver.onFinishedGoingToSleep();
        verify(mHandler, never()).post(any());

        ArgumentCaptor<Runnable> captor = ArgumentCaptor.forClass(Runnable.class);
        // the value of isStrongBiometric doesn't matter here since we only care about the returned
        // value of isUnlockingWithBiometricAllowed()
        mBiometricUnlockController.onBiometricAuthenticated(1 /* userId */,
                BiometricSourceType.FACE, true /* isStrongBiometric */);
        mBiometricUnlockController.mWakefulnessObserver.onFinishedGoingToSleep();
        verify(mHandler).post(captor.capture());
        captor.getValue().run();
    }

    @Test
    public void onFPFailureNoHaptics_notInteractive_showLockScreen() {
        // GIVEN no vibrator and device is not interactive
        when(mVibratorHelper.hasVibrator()).thenReturn(false);
        when(mUpdateMonitor.isDeviceInteractive()).thenReturn(false);
        when(mUpdateMonitor.isDreaming()).thenReturn(false);

        // WHEN FP fails
        mBiometricUnlockController.onBiometricAuthFailed(BiometricSourceType.FINGERPRINT);

        // THEN wakeup the device
        verify(mPowerManager).wakeUp(anyLong(), anyInt(), anyString());
    }

    @Test
    public void onFPFailureNoHaptics_dreaming_showLockScreen() {
        // GIVEN no vibrator and device is dreaming
        when(mVibratorHelper.hasVibrator()).thenReturn(false);
        when(mUpdateMonitor.isDeviceInteractive()).thenReturn(true);
        when(mUpdateMonitor.isDreaming()).thenReturn(true);

        // WHEN FP fails
        mBiometricUnlockController.onBiometricAuthFailed(BiometricSourceType.FINGERPRINT);

        // THEN never wakeup the device
        verify(mPowerManager, never()).wakeUp(anyLong(), anyInt(), anyString());
    }

    @Test
    public void biometricUnlockStateResetOnTransitionFromGone() {
        mBiometricUnlockController.consumeTransitionStepOnStartedKeyguardState(
                new TransitionStep(
                        KeyguardState.AOD,
                        KeyguardState.GONE,
                        .1f /* value */,
                        TransitionState.STARTED
                )
        );
        verify(mBiometricUnlockInteractor, never()).setBiometricUnlockState(anyInt(), any());

        mBiometricUnlockController.consumeTransitionStepOnStartedKeyguardState(
                new TransitionStep(
                        KeyguardState.GONE,
                        KeyguardState.AOD,
                        .1f /* value */,
                        TransitionState.STARTED
                )
        );
        verify(mBiometricUnlockInteractor).setBiometricUnlockState(eq(MODE_NONE), eq(null));
    }

    @Test
    public void onFingerprintDetect_showBouncer() {
        // WHEN fingerprint detect occurs
        mBiometricUnlockController.onBiometricDetected(UserHandle.USER_CURRENT,
                BiometricSourceType.FINGERPRINT, true /* isStrongBiometric */);

        // THEN shows primary bouncer
        verify(mStatusBarKeyguardViewManager).showPrimaryBouncer(anyBoolean());
    }

    @Test
    public void onFaceDetect_showBouncer() {
        // WHEN face detect occurs
        mBiometricUnlockController.onBiometricDetected(UserHandle.USER_CURRENT,
                BiometricSourceType.FACE, false /* isStrongBiometric */);

        // THEN shows primary bouncer
        verify(mStatusBarKeyguardViewManager).showPrimaryBouncer(anyBoolean());
    }

    private void givenFingerprintModeUnlockCollapsing() {
        when(mUpdateMonitor.isUnlockingWithBiometricAllowed(anyBoolean())).thenReturn(true);
        when(mUpdateMonitor.isDeviceInteractive()).thenReturn(true);
        when(mKeyguardStateController.isShowing()).thenReturn(true);
    }

    private void givenDreamingLocked() {
        when(mUpdateMonitor.isDreaming()).thenReturn(true);
        when(mUpdateMonitor.isUnlockingWithBiometricAllowed(anyBoolean())).thenReturn(true);
    }
    @Test
    public void onSideFingerprintSuccess_dreaming_unlockNoWake() {
        mBiometricUnlockController = createController(true);
        when(mAuthController.isSfpsEnrolled(anyInt())).thenReturn(true);
        when(mWakefulnessLifecycle.getLastWakeReason())
                .thenReturn(PowerManager.WAKE_REASON_POWER_BUTTON);
        givenDreamingLocked();
        when(mPowerManager.isInteractive()).thenReturn(true);
        mBiometricUnlockController.startWakeAndUnlock(BiometricSourceType.FINGERPRINT, true);
        verify(mKeyguardViewMediator).onWakeAndUnlocking(true);
        // Ensure that the power hasn't been told to wake up yet.
        verify(mPowerManager, never()).wakeUp(anyLong(), anyInt(), anyString());
    }
}
