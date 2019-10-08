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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.hardware.biometrics.BiometricSourceType;
import android.os.Handler;
import android.os.PowerManager;
import android.os.UserHandle;
import android.test.suitebuilder.annotation.SmallTest;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper.RunWithLooper;

import com.android.keyguard.KeyguardUpdateMonitor;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.keyguard.KeyguardViewMediator;
import com.android.systemui.statusbar.NotificationMediaManager;
import com.android.systemui.tuner.TunerService;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@SmallTest
@RunWith(AndroidTestingRunner.class)
@RunWithLooper
public class BiometricsUnlockControllerTest extends SysuiTestCase {

    @Mock
    private NotificationMediaManager mMediaManager;
    @Mock
    private PowerManager mPowerManager;
    @Mock
    private KeyguardUpdateMonitor mUpdateMonitor;
    @Mock
    private StatusBarKeyguardViewManager mStatusBarKeyguardViewManager;
    @Mock
    private StatusBarWindowController mStatusBarWindowController;
    @Mock
    private DozeScrimController mDozeScrimController;
    @Mock
    private KeyguardViewMediator mKeyguardViewMediator;
    @Mock
    private ScrimController mScrimController;
    @Mock
    private StatusBar mStatusBar;
    @Mock
    private UnlockMethodCache mUnlockMethodCache;
    @Mock
    private TunerService mTunerService;
    @Mock
    private Handler mHandler;
    private BiometricUnlockController mBiometricUnlockController;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        when(mStatusBarKeyguardViewManager.isShowing()).thenReturn(true);
        when(mUpdateMonitor.isDeviceInteractive()).thenReturn(true);
        mContext.addMockSystemService(PowerManager.class, mPowerManager);
        mDependency.injectTestDependency(NotificationMediaManager.class, mMediaManager);
        mDependency.injectTestDependency(StatusBarWindowController.class,
                mStatusBarWindowController);
        mBiometricUnlockController = new TestableBiometricUnlockController(
                false /* faceDismissesKeyguard */);
        mBiometricUnlockController.setStatusBarKeyguardViewManager(mStatusBarKeyguardViewManager);
    }

    @Test
    public void onBiometricAuthenticated_whenFingerprintAndBiometricsDisallowed_showBouncer() {
        mBiometricUnlockController.onBiometricAuthenticated(UserHandle.USER_CURRENT,
                BiometricSourceType.FINGERPRINT);
        verify(mStatusBarKeyguardViewManager).showBouncer(eq(false));
        verify(mStatusBarKeyguardViewManager).animateCollapsePanels(anyFloat());
    }

    @Test
    public void onBiometricAuthenticated_whenFingerprintAndNotInteractive_wakeAndUnlock() {
        reset(mUpdateMonitor);
        reset(mStatusBarKeyguardViewManager);
        when(mStatusBarKeyguardViewManager.isShowing()).thenReturn(true);
        when(mUpdateMonitor.isUnlockingWithBiometricAllowed()).thenReturn(true);
        when(mDozeScrimController.isPulsing()).thenReturn(true);
        mBiometricUnlockController.onBiometricAuthenticated(UserHandle.USER_CURRENT,
                BiometricSourceType.FINGERPRINT);

        verify(mKeyguardViewMediator).onWakeAndUnlocking();
    }

    @Test
    public void onBiometricAuthenticated_whenFingerprint_dismissKeyguard() {
        when(mUpdateMonitor.isUnlockingWithBiometricAllowed()).thenReturn(true);
        mBiometricUnlockController.onBiometricAuthenticated(UserHandle.USER_CURRENT,
                BiometricSourceType.FINGERPRINT);

        verify(mStatusBarKeyguardViewManager, never()).showBouncer(anyBoolean());
        verify(mStatusBarKeyguardViewManager).animateCollapsePanels(anyFloat());
    }

    @Test
    public void onBiometricAuthenticated_whenFingerprintOnBouncer_dismissBouncer() {
        when(mUpdateMonitor.isUnlockingWithBiometricAllowed()).thenReturn(true);
        when(mStatusBarKeyguardViewManager.isBouncerShowing()).thenReturn(true);
        mBiometricUnlockController.onBiometricAuthenticated(UserHandle.USER_CURRENT,
                BiometricSourceType.FINGERPRINT);

        verify(mStatusBarKeyguardViewManager).notifyKeyguardAuthenticated(eq(false));
    }

    @Test
    public void onBiometricAuthenticated_whenFace_dontDismissKeyguard() {
        when(mUpdateMonitor.isUnlockingWithBiometricAllowed()).thenReturn(true);
        mBiometricUnlockController.onBiometricAuthenticated(UserHandle.USER_CURRENT,
                BiometricSourceType.FACE);

        verify(mStatusBarKeyguardViewManager, never()).animateCollapsePanels(anyFloat());
    }

    @Test
    public void onBiometricAuthenticated_whenFace_dismissingKeyguard() {
        mBiometricUnlockController = new TestableBiometricUnlockController(
                true /* faceDismissesKeyguard */);
        mBiometricUnlockController.setStatusBarKeyguardViewManager(mStatusBarKeyguardViewManager);

        when(mUpdateMonitor.isUnlockingWithBiometricAllowed()).thenReturn(true);
        mBiometricUnlockController.onBiometricAuthenticated(UserHandle.USER_CURRENT,
                BiometricSourceType.FACE);

        verify(mStatusBarKeyguardViewManager).animateCollapsePanels(anyFloat());
    }

    @Test
    public void onBiometricAuthenticated_whenFaceOnBouncer_dismissBouncer() {
        when(mUpdateMonitor.isUnlockingWithBiometricAllowed()).thenReturn(true);
        when(mStatusBarKeyguardViewManager.isBouncerShowing()).thenReturn(true);
        mBiometricUnlockController.onBiometricAuthenticated(UserHandle.USER_CURRENT,
                BiometricSourceType.FACE);

        verify(mStatusBarKeyguardViewManager).notifyKeyguardAuthenticated(eq(false));
    }

    @Test
    public void onBiometricAuthenticated_whenFaceAndPulsing_dontDismissKeyguard() {
        reset(mUpdateMonitor);
        reset(mStatusBarKeyguardViewManager);
        when(mUpdateMonitor.isUnlockingWithBiometricAllowed()).thenReturn(true);
        when(mDozeScrimController.isPulsing()).thenReturn(true);
        mBiometricUnlockController.onBiometricAuthenticated(UserHandle.USER_CURRENT,
                BiometricSourceType.FACE);

        verify(mStatusBarKeyguardViewManager, never()).animateCollapsePanels(anyFloat());
    }

    @Test
    public void onFinishedGoingToSleep_authenticatesWhenPending() {
        when(mUpdateMonitor.isGoingToSleep()).thenReturn(true);
        mBiometricUnlockController.onFinishedGoingToSleep(-1);
        verify(mHandler, never()).post(any());

        mBiometricUnlockController.onBiometricAuthenticated(1 /* userId */,
                BiometricSourceType.FACE);
        mBiometricUnlockController.onFinishedGoingToSleep(-1);
        verify(mHandler).post(any());
    }

    private class TestableBiometricUnlockController extends BiometricUnlockController {

        TestableBiometricUnlockController(boolean faceDismissesKeyguard) {
            super(mContext, mDozeScrimController,
                    mKeyguardViewMediator, mScrimController, mStatusBar, mUnlockMethodCache,
                    mHandler, mUpdateMonitor, mTunerService, 0 /* wakeUpDelay */,
                    faceDismissesKeyguard);
            mFaceDismissesKeyguard = faceDismissesKeyguard;
        }
    }
}
