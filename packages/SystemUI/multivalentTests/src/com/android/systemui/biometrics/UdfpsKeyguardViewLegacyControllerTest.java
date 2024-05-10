/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.systemui.biometrics;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.testing.TestableLooper;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import com.android.systemui.statusbar.StatusBarState;

import org.junit.Test;
import org.junit.runner.RunWith;

@SmallTest
@RunWith(AndroidJUnit4.class)

@TestableLooper.RunWithLooper(setAsMainLooper = true)
public class UdfpsKeyguardViewLegacyControllerTest extends
        UdfpsKeyguardViewLegacyControllerBaseTest {
    @Override
    public UdfpsKeyguardViewControllerLegacy createUdfpsKeyguardViewController() {
        return createUdfpsKeyguardViewController(/* useModernBouncer */ false);
    }

    @Test
    public void testShouldPauseAuth_bouncerShowing() {
        mController.onViewAttached();
        captureStatusBarStateListeners();
        sendStatusBarStateChanged(StatusBarState.KEYGUARD);

        when(mStatusBarKeyguardViewManager.isBouncerShowing()).thenReturn(true);
        when(mStatusBarKeyguardViewManager.primaryBouncerIsOrWillBeShowing()).thenReturn(true);
        when(mView.getUnpausedAlpha()).thenReturn(0);
        assertTrue(mController.shouldPauseAuth());
    }

    @Test
    public void testRegistersStatusBarStateListenersOnAttached() {
        mController.onViewAttached();
        captureStatusBarStateListeners();
    }

    @Test
    public void testViewControllerQueriesSBStateOnAttached() {
        mController.onViewAttached();
        verify(mStatusBarStateController).getState();

        when(mStatusBarStateController.getState()).thenReturn(StatusBarState.SHADE_LOCKED);
        captureStatusBarStateListeners();

        mController.onViewAttached();
        verify(mView, atLeast(1)).setPauseAuth(true);
    }

    @Test
    public void testListenersUnregisteredOnDetached() {
        mController.onViewAttached();
        captureStatusBarStateListeners();
        captureKeyguardStateControllerCallback();
        mController.onViewDetached();

        verify(mStatusBarStateController).removeCallback(mStatusBarStateListener);
        verify(mKeyguardStateController).removeCallback(mKeyguardStateControllerCallback);
    }

    @Test
    public void testShouldPauseAuthUnpausedAlpha0() {
        mController.onViewAttached();
        captureStatusBarStateListeners();

        when(mView.getUnpausedAlpha()).thenReturn(0);
        sendStatusBarStateChanged(StatusBarState.KEYGUARD);

        assertTrue(mController.shouldPauseAuth());
    }

    @Test
    public void testShouldNotPauseAuthOnKeyguard() {
        mController.onViewAttached();
        captureStatusBarStateListeners();

        sendStatusBarStateChanged(StatusBarState.KEYGUARD);

        assertFalse(mController.shouldPauseAuth());
    }

    @Test
    public void onBiometricAuthenticated_pauseAuth() {
        // GIVEN view is attached and we're on the keyguard (see testShouldNotPauseAuthOnKeyguard)
        mController.onViewAttached();
        captureStatusBarStateListeners();
        sendStatusBarStateChanged(StatusBarState.KEYGUARD);

        // WHEN biometric is authenticated
        captureKeyguardStateControllerCallback();
        when(mKeyguardUpdateMonitor.getUserUnlockedWithBiometric(anyInt())).thenReturn(true);
        mKeyguardStateControllerCallback.onUnlockedChanged();

        // THEN pause auth
        assertTrue(mController.shouldPauseAuth());
    }

    @Test
    public void testShouldPauseAuthIsLaunchTransitionFadingAway() {
        // GIVEN view is attached and we're on the keyguard (see testShouldNotPauseAuthOnKeyguard)
        mController.onViewAttached();
        captureStatusBarStateListeners();
        sendStatusBarStateChanged(StatusBarState.KEYGUARD);

        // WHEN isLaunchTransitionFadingAway=true
        captureKeyguardStateControllerCallback();
        when(mKeyguardStateController.isLaunchTransitionFadingAway()).thenReturn(true);
        mKeyguardStateControllerCallback.onLaunchTransitionFadingAwayChanged();

        // THEN pause auth
        assertTrue(mController.shouldPauseAuth());
    }

    @Test
    public void testShouldPauseAuthOnShadeLocked() {
        mController.onViewAttached();
        captureStatusBarStateListeners();

        sendStatusBarStateChanged(StatusBarState.SHADE_LOCKED);

        assertTrue(mController.shouldPauseAuth());
    }

    @Test
    public void testShouldPauseAuthOnShade() {
        mController.onViewAttached();
        captureStatusBarStateListeners();

        // WHEN not on keyguard yet (shade = home)
        sendStatusBarStateChanged(StatusBarState.SHADE);

        // THEN pause auth
        assertTrue(mController.shouldPauseAuth());
    }

    @Test
    public void testShouldPauseAuthAnimatingScreenOffFromShade() {
        mController.onViewAttached();
        captureStatusBarStateListeners();

        // WHEN transitioning from home/shade => keyguard + animating screen off
        mStatusBarStateListener.onStatePreChange(StatusBarState.SHADE, StatusBarState.KEYGUARD);
        when(mKeyguardViewMediator.isAnimatingScreenOff()).thenReturn(true);

        // THEN pause auth
        assertTrue(mController.shouldPauseAuth());
    }

    @Test
    public void testDoNotPauseAuthAnimatingScreenOffFromLS() {
        mController.onViewAttached();
        captureStatusBarStateListeners();

        // WHEN animating screen off transition from LS => AOD
        sendStatusBarStateChanged(StatusBarState.KEYGUARD);
        when(mKeyguardViewMediator.isAnimatingScreenOff()).thenReturn(true);

        // THEN don't pause auth
        assertFalse(mController.shouldPauseAuth());
    }

    @Test
    public void testOverrideShouldPauseAuthOnShadeLocked() {
        mController.onViewAttached();
        captureStatusBarStateListeners();

        sendStatusBarStateChanged(StatusBarState.SHADE_LOCKED);
        assertTrue(mController.shouldPauseAuth());
    }
}
