/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.keyguard;

import static com.android.keyguard.LockIconView.ICON_LOCK;
import static com.android.keyguard.LockIconView.ICON_UNLOCK;

import static org.mockito.Mockito.anyBoolean;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.graphics.Point;
import android.hardware.biometrics.BiometricSourceType;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.util.Pair;

import androidx.test.filters.SmallTest;

import com.android.systemui.doze.util.BurnInHelperKt;

import org.junit.Test;
import org.junit.runner.RunWith;

@SmallTest
@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper
public class LockIconViewControllerTest extends LockIconViewControllerBaseTest {

    @Test
    public void testUpdateFingerprintLocationOnInit() {
        // GIVEN fp sensor location is available pre-attached
        Pair<Float, Point> udfps = setupUdfps(); // first = radius, second = udfps location

        // WHEN lock icon view controller is initialized and attached
        init(/* useMigrationFlag= */false);

        // THEN lock icon view location is updated to the udfps location with UDFPS radius
        verify(mLockIconView).setCenterLocation(eq(udfps.second), eq(udfps.first),
                eq(PADDING));
    }

    @Test
    public void testUpdatePaddingBasedOnResolutionScale() {
        // GIVEN fp sensor location is available pre-attached & scaled resolution factor is 5
        Pair<Float, Point> udfps = setupUdfps(); // first = radius, second = udfps location
        when(mAuthController.getScaleFactor()).thenReturn(5f);

        // WHEN lock icon view controller is initialized and attached
        init(/* useMigrationFlag= */false);

        // THEN lock icon view location is updated with the scaled radius
        verify(mLockIconView).setCenterLocation(eq(udfps.second), eq(udfps.first),
                eq(PADDING * 5));
    }

    @Test
    public void testUpdateLockIconLocationOnAuthenticatorsRegistered() {
        // GIVEN fp sensor location is not available pre-init
        when(mKeyguardUpdateMonitor.isUdfpsSupported()).thenReturn(false);
        when(mAuthController.getFingerprintSensorLocation()).thenReturn(null);
        init(/* useMigrationFlag= */false);
        resetLockIconView(); // reset any method call counts for when we verify method calls later

        // GIVEN fp sensor location is available post-attached
        captureAuthControllerCallback();
        Pair<Float, Point> udfps = setupUdfps();

        // WHEN all authenticators are registered
        mAuthControllerCallback.onAllAuthenticatorsRegistered();
        mDelayableExecutor.runAllReady();

        // THEN lock icon view location is updated with the same coordinates as auth controller vals
        verify(mLockIconView).setCenterLocation(eq(udfps.second), eq(udfps.first),
                eq(PADDING));
    }

    @Test
    public void testUpdateLockIconLocationOnUdfpsLocationChanged() {
        // GIVEN fp sensor location is not available pre-init
        when(mKeyguardUpdateMonitor.isUdfpsSupported()).thenReturn(false);
        when(mAuthController.getFingerprintSensorLocation()).thenReturn(null);
        init(/* useMigrationFlag= */false);
        resetLockIconView(); // reset any method call counts for when we verify method calls later

        // GIVEN fp sensor location is available post-attached
        captureAuthControllerCallback();
        Pair<Float, Point> udfps = setupUdfps();

        // WHEN udfps location changes
        mAuthControllerCallback.onUdfpsLocationChanged();
        mDelayableExecutor.runAllReady();

        // THEN lock icon view location is updated with the same coordinates as auth controller vals
        verify(mLockIconView).setCenterLocation(eq(udfps.second), eq(udfps.first),
                eq(PADDING));
    }

    @Test
    public void testLockIconViewBackgroundEnabledWhenUdfpsIsSupported() {
        // GIVEN Udpfs sensor location is available
        setupUdfps();

        // WHEN the view is attached
        init(/* useMigrationFlag= */false);

        // THEN the lock icon view background should be enabled
        verify(mLockIconView).setUseBackground(true);
    }

    @Test
    public void testLockIconViewBackgroundDisabledWhenUdfpsIsNotSupported() {
        // GIVEN Udfps sensor location is not supported
        when(mKeyguardUpdateMonitor.isUdfpsSupported()).thenReturn(false);

        // WHEN the view is attached
        init(/* useMigrationFlag= */false);

        // THEN the lock icon view background should be disabled
        verify(mLockIconView).setUseBackground(false);
    }

    @Test
    public void testUnlockIconShows_biometricUnlockedTrue() {
        // GIVEN UDFPS sensor location is available
        setupUdfps();

        // GIVEN lock icon controller is initialized and view is attached
        init(/* useMigrationFlag= */false);
        captureKeyguardUpdateMonitorCallback();

        // GIVEN user has unlocked with a biometric auth (ie: face auth)
        when(mKeyguardUpdateMonitor.getUserUnlockedWithBiometric(anyInt())).thenReturn(true);
        reset(mLockIconView);

        // WHEN face auth's biometric running state changes
        mKeyguardUpdateMonitorCallback.onBiometricRunningStateChanged(false,
                BiometricSourceType.FACE);

        // THEN the unlock icon is shown
        verify(mLockIconView).setContentDescription(UNLOCKED_LABEL);
    }

    @Test
    public void testLockIconStartState() {
        // GIVEN lock icon state
        setupShowLockIcon();

        // WHEN lock icon controller is initialized
        init(/* useMigrationFlag= */false);

        // THEN the lock icon should show
        verify(mLockIconView).updateIcon(ICON_LOCK, false);
    }

    @Test
    public void testLockIcon_updateToUnlock() {
        // GIVEN starting state for the lock icon
        setupShowLockIcon();

        // GIVEN lock icon controller is initialized and view is attached
        init(/* useMigrationFlag= */false);
        captureKeyguardStateCallback();
        reset(mLockIconView);

        // WHEN the unlocked state changes to canDismissLockScreen=true
        when(mKeyguardStateController.canDismissLockScreen()).thenReturn(true);
        mKeyguardStateCallback.onUnlockedChanged();

        // THEN the unlock should show
        verify(mLockIconView).updateIcon(ICON_UNLOCK, false);
    }

    @Test
    public void testLockIcon_clearsIconOnAod_whenUdfpsNotEnrolled() {
        // GIVEN udfps not enrolled
        setupUdfps();
        when(mKeyguardUpdateMonitor.isUdfpsEnrolled()).thenReturn(false);

        // GIVEN starting state for the lock icon
        setupShowLockIcon();

        // GIVEN lock icon controller is initialized and view is attached
        init(/* useMigrationFlag= */false);
        captureStatusBarStateListener();
        reset(mLockIconView);

        // WHEN the dozing state changes
        mStatusBarStateListener.onDozingChanged(true /* isDozing */);

        // THEN the icon is cleared
        verify(mLockIconView).clearIcon();
    }

    @Test
    public void testLockIcon_updateToAodLock_whenUdfpsEnrolled() {
        // GIVEN udfps enrolled
        setupUdfps();
        when(mKeyguardUpdateMonitor.isUdfpsEnrolled()).thenReturn(true);

        // GIVEN starting state for the lock icon
        setupShowLockIcon();

        // GIVEN lock icon controller is initialized and view is attached
        init(/* useMigrationFlag= */false);
        captureStatusBarStateListener();
        reset(mLockIconView);

        // WHEN the dozing state changes
        mStatusBarStateListener.onDozingChanged(true /* isDozing */);

        // THEN the AOD lock icon should show
        verify(mLockIconView).updateIcon(ICON_LOCK, true);
    }

    @Test
    public void testBurnInOffsetsUpdated_onDozeAmountChanged() {
        // GIVEN udfps enrolled
        setupUdfps();
        when(mKeyguardUpdateMonitor.isUdfpsEnrolled()).thenReturn(true);

        // GIVEN burn-in offset = 5
        int burnInOffset = 5;
        when(BurnInHelperKt.getBurnInOffset(anyInt(), anyBoolean())).thenReturn(burnInOffset);

        // GIVEN starting state for the lock icon (keyguard)
        setupShowLockIcon();
        init(/* useMigrationFlag= */false);
        captureStatusBarStateListener();
        reset(mLockIconView);

        // WHEN dozing updates
        mStatusBarStateListener.onDozingChanged(true /* isDozing */);
        mStatusBarStateListener.onDozeAmountChanged(1f, 1f);

        // THEN the view's translation is updated to use the AoD burn-in offsets
        verify(mLockIconView).setTranslationY(burnInOffset);
        verify(mLockIconView).setTranslationX(burnInOffset);
        reset(mLockIconView);

        // WHEN the device is no longer dozing
        mStatusBarStateListener.onDozingChanged(false /* isDozing */);
        mStatusBarStateListener.onDozeAmountChanged(0f, 0f);

        // THEN the view is updated to NO translation (no burn-in offsets anymore)
        verify(mLockIconView).setTranslationY(0);
        verify(mLockIconView).setTranslationX(0);
    }

    @Test
    public void lockIconShows_afterBiometricsCleared() {
        // GIVEN lock icon controller is initialized and view is attached
        init(/* useMigrationFlag= */false);
        captureKeyguardUpdateMonitorCallback();

        // GIVEN user has unlocked with a biometric auth (ie: face auth)
        // and biometric running state changes
        when(mKeyguardUpdateMonitor.getUserUnlockedWithBiometric(anyInt())).thenReturn(true);
        mKeyguardUpdateMonitorCallback.onBiometricRunningStateChanged(false,
                BiometricSourceType.FACE);
        reset(mLockIconView);

        // WHEN biometrics are cleared
        when(mKeyguardUpdateMonitor.getUserUnlockedWithBiometric(anyInt())).thenReturn(false);
        mKeyguardUpdateMonitorCallback.onBiometricsCleared();

        // THEN the lock icon is shown
        verify(mLockIconView).setContentDescription(LOCKED_LABEL);
    }

    @Test
    public void lockIconShows_afterUnlockStateChanges() {
        // GIVEN lock icon controller is initialized and view is attached
        init(/* useMigrationFlag= */false);
        captureKeyguardStateCallback();
        captureKeyguardUpdateMonitorCallback();

        // GIVEN user has unlocked with a biometric auth (ie: face auth)
        // and biometric running state changes
        when(mKeyguardUpdateMonitor.getUserUnlockedWithBiometric(anyInt())).thenReturn(true);
        mKeyguardUpdateMonitorCallback.onBiometricRunningStateChanged(false,
                BiometricSourceType.FACE);
        reset(mLockIconView);

        // WHEN the unlocked state changes
        when(mKeyguardUpdateMonitor.getUserUnlockedWithBiometric(anyInt())).thenReturn(false);
        mKeyguardStateCallback.onUnlockedChanged();

        // THEN the lock icon is shown
        verify(mLockIconView).setContentDescription(LOCKED_LABEL);
    }
}
