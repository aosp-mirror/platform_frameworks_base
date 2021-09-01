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

import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper.RunWithLooper;

import androidx.test.filters.SmallTest;

import com.android.keyguard.KeyguardUpdateMonitor;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.dump.DumpManager;
import com.android.systemui.keyguard.KeyguardViewMediator;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.statusbar.LockscreenShadeTransitionController;
import com.android.systemui.statusbar.StatusBarState;
import com.android.systemui.statusbar.phone.StatusBar;
import com.android.systemui.statusbar.phone.StatusBarKeyguardViewManager;
import com.android.systemui.statusbar.policy.ConfigurationController;
import com.android.systemui.statusbar.policy.KeyguardStateController;
import com.android.systemui.util.concurrency.DelayableExecutor;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.List;


@SmallTest
@RunWith(AndroidTestingRunner.class)
@RunWithLooper
public class UdfpsKeyguardViewControllerTest extends SysuiTestCase {
    // Dependencies
    @Mock
    private UdfpsKeyguardView mView;
    @Mock
    private Context mResourceContext;
    @Mock
    private StatusBarStateController mStatusBarStateController;
    @Mock
    private StatusBar mStatusBar;
    @Mock
    private StatusBarKeyguardViewManager mStatusBarKeyguardViewManager;
    @Mock
    private LockscreenShadeTransitionController mLockscreenShadeTransitionController;
    @Mock
    private DumpManager mDumpManager;
    @Mock
    private DelayableExecutor mExecutor;
    @Mock
    private KeyguardUpdateMonitor mKeyguardUpdateMonitor;
    @Mock
    private KeyguardStateController mKeyguardStateController;
    @Mock
    private KeyguardViewMediator mKeyguardViewMediator;
    @Mock
    private ConfigurationController mConfigurationController;
    @Mock
    private UdfpsController mUdfpsController;

    private UdfpsKeyguardViewController mController;

    // Capture listeners so that they can be used to send events
    @Captor private ArgumentCaptor<StatusBarStateController.StateListener> mStateListenerCaptor;
    private StatusBarStateController.StateListener mStatusBarStateListener;

    @Captor private ArgumentCaptor<StatusBar.ExpansionChangedListener> mExpansionListenerCaptor;
    private List<StatusBar.ExpansionChangedListener> mExpansionListeners;

    @Captor private ArgumentCaptor<StatusBarKeyguardViewManager.AlternateAuthInterceptor>
            mAltAuthInterceptorCaptor;
    private StatusBarKeyguardViewManager.AlternateAuthInterceptor mAltAuthInterceptor;

    @Captor private ArgumentCaptor<KeyguardStateController.Callback>
            mKeyguardStateControllerCallbackCaptor;
    private KeyguardStateController.Callback mKeyguardStateControllerCallback;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        when(mView.getContext()).thenReturn(mResourceContext);
        when(mResourceContext.getString(anyInt())).thenReturn("test string");
        when(mKeyguardViewMediator.isAnimatingScreenOff()).thenReturn(false);
        mController = new UdfpsKeyguardViewController(
                mView,
                mStatusBarStateController,
                mStatusBar,
                mStatusBarKeyguardViewManager,
                mKeyguardUpdateMonitor,
                mExecutor,
                mDumpManager,
                mKeyguardViewMediator,
                mLockscreenShadeTransitionController,
                mConfigurationController,
                mKeyguardStateController,
                mUdfpsController);
    }

    @Test
    public void testRegistersExpansionChangedListenerOnAttached() {
        mController.onViewAttached();
        captureExpansionListeners();
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
        verify(mStatusBarStateController).getDozeAmount();

        final float dozeAmount = .88f;
        when(mStatusBarStateController.getState()).thenReturn(StatusBarState.SHADE_LOCKED);
        when(mStatusBarStateController.getDozeAmount()).thenReturn(dozeAmount);
        captureStatusBarStateListeners();

        mController.onViewAttached();
        verify(mView, atLeast(1)).setPauseAuth(true);
        verify(mView).onDozeAmountChanged(dozeAmount, dozeAmount);
    }

    @Test
    public void testListenersUnregisteredOnDetached() {
        mController.onViewAttached();
        captureStatusBarStateListeners();
        captureExpansionListeners();
        captureKeyguardStateControllerCallback();
        mController.onViewDetached();

        verify(mStatusBarStateController).removeCallback(mStatusBarStateListener);
        for (StatusBar.ExpansionChangedListener listener : mExpansionListeners) {
            verify(mStatusBar).removeExpansionChangedListener(listener);
        }
        verify(mKeyguardStateController).removeCallback(mKeyguardStateControllerCallback);
    }

    @Test
    public void testDozeEventsSentToView() {
        mController.onViewAttached();
        captureStatusBarStateListeners();

        final float linear = .55f;
        final float eased = .65f;
        mStatusBarStateListener.onDozeAmountChanged(linear, eased);

        verify(mView).onDozeAmountChanged(linear, eased);
    }

    @Test
    public void testShouldPauseAuthBouncerShowing() {
        mController.onViewAttached();
        captureStatusBarStateListeners();

        sendStatusBarStateChanged(StatusBarState.KEYGUARD);

        assertFalse(mController.shouldPauseAuth());
    }

    @Test
    public void testShouldNotPauseAuthOnKeyguard() {
        mController.onViewAttached();
        captureStatusBarStateListeners();

        sendStatusBarStateChanged(StatusBarState.KEYGUARD);

        assertFalse(mController.shouldPauseAuth());
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
        captureAltAuthInterceptor();

        sendStatusBarStateChanged(StatusBarState.SHADE_LOCKED);
        assertTrue(mController.shouldPauseAuth());

        mAltAuthInterceptor.showAlternateAuthBouncer(); // force show
        assertFalse(mController.shouldPauseAuth());
        assertTrue(mAltAuthInterceptor.isShowingAlternateAuthBouncer());

        mAltAuthInterceptor.hideAlternateAuthBouncer(); // stop force show
        assertTrue(mController.shouldPauseAuth());
        assertFalse(mAltAuthInterceptor.isShowingAlternateAuthBouncer());
    }

    @Test
    public void testOnDetachedStateReset() {
        // GIVEN view is attached
        mController.onViewAttached();
        captureAltAuthInterceptor();

        // WHEN view is detached
        mController.onViewDetached();

        // THEN remove alternate auth interceptor
        verify(mStatusBarKeyguardViewManager).removeAlternateAuthInterceptor(mAltAuthInterceptor);
    }

    @Test
    public void testFadeInWithStatusBarExpansion() {
        // GIVEN view is attached
        mController.onViewAttached();
        captureExpansionListeners();
        captureKeyguardStateControllerCallback();
        reset(mView);

        // WHEN status bar expansion is 0
        updateStatusBarExpansion(0, true);

        // THEN alpha is 0
        verify(mView).setUnpausedAlpha(0);
    }

    @Test
    public void testShowUdfpsBouncer() {
        // GIVEN view is attached and status bar expansion is 0
        mController.onViewAttached();
        captureExpansionListeners();
        captureKeyguardStateControllerCallback();
        captureAltAuthInterceptor();
        updateStatusBarExpansion(0, true);
        reset(mView);
        when(mView.getContext()).thenReturn(mResourceContext);
        when(mResourceContext.getString(anyInt())).thenReturn("test string");

        // WHEN status bar expansion is 0 but udfps bouncer is requested
        mAltAuthInterceptor.showAlternateAuthBouncer();

        // THEN alpha is 0
        verify(mView).setUnpausedAlpha(255);
    }

    private void sendStatusBarStateChanged(int statusBarState) {
        mStatusBarStateListener.onStateChanged(statusBarState);
    }

    private void captureStatusBarStateListeners() {
        verify(mStatusBarStateController).addCallback(mStateListenerCaptor.capture());
        mStatusBarStateListener = mStateListenerCaptor.getValue();
    }

    private void captureExpansionListeners() {
        verify(mStatusBar, times(2))
                .addExpansionChangedListener(mExpansionListenerCaptor.capture());
        // first (index=0) is from super class, UdfpsAnimationViewController.
        // second (index=1) is from UdfpsKeyguardViewController
        mExpansionListeners = mExpansionListenerCaptor.getAllValues();
    }

    private void updateStatusBarExpansion(float expansion, boolean expanded) {
        for (StatusBar.ExpansionChangedListener listener : mExpansionListeners) {
            listener.onExpansionChanged(expansion, expanded);
        }
    }

    private void captureAltAuthInterceptor() {
        verify(mStatusBarKeyguardViewManager).setAlternateAuthInterceptor(
                mAltAuthInterceptorCaptor.capture());
        mAltAuthInterceptor = mAltAuthInterceptorCaptor.getValue();
    }

    private void captureKeyguardStateControllerCallback() {
        verify(mKeyguardStateController).addCallback(
                mKeyguardStateControllerCallbackCaptor.capture());
        mKeyguardStateControllerCallback = mKeyguardStateControllerCallbackCaptor.getValue();
    }
}
