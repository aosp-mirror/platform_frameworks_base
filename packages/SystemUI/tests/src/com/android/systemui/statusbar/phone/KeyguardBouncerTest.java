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
 * limitations under the License
 */

package com.android.systemui.statusbar.phone;

import static com.android.systemui.statusbar.phone.KeyguardBouncer.EXPANSION_HIDDEN;
import static com.android.systemui.statusbar.phone.KeyguardBouncer.EXPANSION_VISIBLE;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.verifyZeroInteractions;
import static org.mockito.Mockito.when;

import android.content.res.ColorStateList;
import android.graphics.Color;
import android.os.Handler;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.test.filters.SmallTest;

import com.android.keyguard.KeyguardHostViewController;
import com.android.keyguard.KeyguardSecurityModel;
import com.android.keyguard.KeyguardUpdateMonitor;
import com.android.keyguard.ViewMediatorCallback;
import com.android.keyguard.dagger.KeyguardBouncerComponent;
import com.android.systemui.DejankUtils;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.classifier.FalsingCollector;
import com.android.systemui.keyguard.DismissCallbackRegistry;
import com.android.systemui.plugins.ActivityStarter.OnDismissAction;
import com.android.systemui.statusbar.phone.KeyguardBouncer.BouncerExpansionCallback;
import com.android.systemui.statusbar.policy.KeyguardStateController;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

@SmallTest
@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper
public class KeyguardBouncerTest extends SysuiTestCase {

    @Mock
    private FalsingCollector mFalsingCollector;
    @Mock
    private ViewMediatorCallback mViewMediatorCallback;
    @Mock
    private DismissCallbackRegistry mDismissCallbackRegistry;
    @Mock
    private KeyguardHostViewController mKeyguardHostViewController;
    @Mock
    private BouncerExpansionCallback mExpansionCallback;
    @Mock
    private KeyguardUpdateMonitor mKeyguardUpdateMonitor;
    @Mock
    private KeyguardStateController mKeyguardStateController;
    @Mock
    private KeyguardBypassController mKeyguardBypassController;
    @Mock
    private Handler mHandler;
    @Mock
    private KeyguardSecurityModel mKeyguardSecurityModel;
    @Mock
    private KeyguardBouncerComponent.Factory mKeyguardBouncerComponentFactory;
    @Mock
    private KeyguardBouncerComponent mKeyguardBouncerComponent;
    private ViewGroup mContainer;
    @Rule
    public MockitoRule mRule = MockitoJUnit.rule();
    private Integer mRootVisibility = View.INVISIBLE;
    private KeyguardBouncer mBouncer;

    @Before
    public void setup() {
        allowTestableLooperAsMainThread();
        when(mKeyguardSecurityModel.getSecurityMode(anyInt()))
                .thenReturn(KeyguardSecurityModel.SecurityMode.None);
        DejankUtils.setImmediate(true);

        mContainer = spy(new FrameLayout(getContext()));
        when(mKeyguardBouncerComponentFactory.create(mContainer)).thenReturn(
                mKeyguardBouncerComponent);
        when(mKeyguardBouncerComponent.getKeyguardHostViewController())
                .thenReturn(mKeyguardHostViewController);

        mBouncer = new KeyguardBouncer.Factory(getContext(), mViewMediatorCallback,
                mDismissCallbackRegistry, mFalsingCollector,
                mKeyguardStateController, mKeyguardUpdateMonitor,
                mKeyguardBypassController, mHandler, mKeyguardSecurityModel,
                mKeyguardBouncerComponentFactory)
                .create(mContainer, mExpansionCallback);
    }

    @Test
    public void testInflateView_doesntCrash() {
        mBouncer.inflateView();
    }

    @Test
    public void testShow_notifiesFalsingManager() {
        mBouncer.show(true);
        verify(mFalsingCollector).onBouncerShown();

        mBouncer.show(true, false);
        verifyNoMoreInteractions(mFalsingCollector);
    }

    /**
     * Regression test: Invisible bouncer when occluded.
     */
    @Test
    public void testShow_bouncerIsVisible() {
        // Expand notification panel as if we were in the keyguard.
        mBouncer.ensureView();
        mBouncer.setExpansion(1);

        reset(mKeyguardHostViewController);

        mBouncer.show(true);
        verify(mKeyguardHostViewController).setExpansion(0);
    }

    @Test
    public void testShow_notifiesVisibility() {
        mBouncer.show(true);
        verify(mKeyguardStateController).notifyBouncerShowing(eq(true));
        verify(mExpansionCallback).onStartingToShow();

        // Not called again when visible
        reset(mViewMediatorCallback);
        mBouncer.show(true);
        verifyNoMoreInteractions(mViewMediatorCallback);
    }

    @Test
    public void testShow_triesToDismissKeyguard() {
        mBouncer.show(true);
        verify(mKeyguardHostViewController).dismiss(anyInt());
    }

    @Test
    public void testShow_resetsSecuritySelection() {
        mBouncer.show(false);
        verify(mKeyguardHostViewController, never()).showPrimarySecurityScreen();

        mBouncer.hide(false);
        mBouncer.show(true);
        verify(mKeyguardHostViewController).showPrimarySecurityScreen();
    }

    @Test
    public void testShow_animatesKeyguardView() {
        mBouncer.show(true);
        verify(mKeyguardHostViewController).appear(anyInt());
    }

    @Test
    public void testShow_showsErrorMessage() {
        final String errorMessage = "an error message";
        when(mViewMediatorCallback.consumeCustomMessage()).thenReturn(errorMessage);
        mBouncer.show(true);
        verify(mKeyguardHostViewController).showErrorMessage(eq(errorMessage));
    }

    @Test
    public void testSetExpansion_notifiesFalsingManager() {
        mBouncer.ensureView();
        mBouncer.setExpansion(0.5f);

        mBouncer.setExpansion(EXPANSION_HIDDEN);
        verify(mFalsingCollector).onBouncerHidden();
        verify(mExpansionCallback).onFullyHidden();

        mBouncer.setExpansion(EXPANSION_VISIBLE);
        verify(mFalsingCollector).onBouncerShown();
        verify(mExpansionCallback).onFullyShown();

        verify(mExpansionCallback, never()).onStartingToHide();
        verify(mKeyguardHostViewController, never()).onStartingToHide();
        mBouncer.setExpansion(0.9f);
        verify(mExpansionCallback).onStartingToHide();
        verify(mKeyguardHostViewController).onStartingToHide();
    }

    @Test
    public void testSetExpansion_notifiesKeyguardView() {
        mBouncer.ensureView();
        mBouncer.setExpansion(0.1f);

        mBouncer.setExpansion(0);
        verify(mKeyguardHostViewController).onResume();
        verify(mContainer).announceForAccessibility(any());
    }

    @Test
    public void testHide_notifiesFalsingManager() {
        mBouncer.hide(false);
        verify(mFalsingCollector).onBouncerHidden();
    }

    @Test
    public void testHide_notifiesVisibility() {
        mBouncer.hide(false);
        verify(mKeyguardStateController).notifyBouncerShowing(eq(false));
    }

    @Test
    public void testHide_notifiesDismissCallbackIfVisible() {
        mBouncer.hide(false);
        verifyZeroInteractions(mDismissCallbackRegistry);
        mBouncer.show(false);
        mBouncer.hide(false);
        verify(mDismissCallbackRegistry).notifyDismissCancelled();
    }

    @Test
    public void testHide_notShowingAnymore() {
        mBouncer.ensureView();
        mBouncer.show(false /* resetSecuritySelection */);
        mBouncer.hide(false /* destroyViews */);
        Assert.assertFalse("Not showing", mBouncer.isShowing());
    }

    @Test
    public void testShowPromptReason_propagates() {
        mBouncer.ensureView();
        mBouncer.showPromptReason(1);
        verify(mKeyguardHostViewController).showPromptReason(eq(1));
    }

    @Test
    public void testShowMessage_propagates() {
        final String message = "a message";
        mBouncer.ensureView();
        mBouncer.showMessage(message, ColorStateList.valueOf(Color.GREEN));
        verify(mKeyguardHostViewController).showMessage(
                eq(message), eq(ColorStateList.valueOf(Color.GREEN)));
    }

    @Test
    public void testShowOnDismissAction_showsBouncer() {
        final OnDismissAction dismissAction = () -> false;
        final Runnable cancelAction = () -> {};
        mBouncer.showWithDismissAction(dismissAction, cancelAction);
        verify(mKeyguardHostViewController).setOnDismissAction(dismissAction, cancelAction);
        Assert.assertTrue("Should be showing", mBouncer.isShowing());
    }

    @Test
    public void testStartPreHideAnimation_notifiesView() {
        final boolean[] ran = {false};
        final Runnable r = () -> ran[0] = true;
        mBouncer.startPreHideAnimation(r);
        Assert.assertTrue("Callback should have been invoked", ran[0]);

        ran[0] = false;
        mBouncer.ensureView();
        mBouncer.startPreHideAnimation(r);
        verify(mKeyguardHostViewController).startDisappearAnimation(r);
        Assert.assertFalse("Callback should have been deferred", ran[0]);
    }

    @Test
    public void testIsShowing_animated() {
        Assert.assertFalse("Show wasn't invoked yet", mBouncer.isShowing());
        mBouncer.show(true /* reset */);
        Assert.assertTrue("Should be showing", mBouncer.isShowing());
    }

    @Test
    public void testIsShowing_forSwipeUp() {
        mBouncer.setExpansion(1f);
        mBouncer.show(true /* reset */, false /* animated */);
        Assert.assertFalse("Should only be showing after collapsing notification panel",
                mBouncer.isShowing());
        mBouncer.setExpansion(0f);
        Assert.assertTrue("Should be showing", mBouncer.isShowing());
    }

    @Test
    public void testSetExpansion() {
        mBouncer.ensureView();
        mBouncer.setExpansion(0.5f);
        verify(mKeyguardHostViewController).setExpansion(0.5f);
    }

    @Test
    public void testIsFullscreenBouncer_asksKeyguardView() {
        mBouncer.ensureView();
        mBouncer.isFullscreenBouncer();
        verify(mKeyguardHostViewController).getCurrentSecurityMode();
    }

    @Test
    public void testIsHiding_preHideOrHide() {
        Assert.assertFalse("Should not be hiding on initial state", mBouncer.isAnimatingAway());
        mBouncer.startPreHideAnimation(null /* runnable */);
        Assert.assertTrue("Should be hiding during pre-hide", mBouncer.isAnimatingAway());
        mBouncer.hide(false /* destroyView */);
        Assert.assertFalse("Should be hidden after hide()", mBouncer.isAnimatingAway());
    }

    @Test
    public void testIsHiding_skipsTranslation() {
        mBouncer.show(false /* reset */);
        reset(mKeyguardHostViewController);
        mBouncer.startPreHideAnimation(null /* runnable */);
        mBouncer.setExpansion(0.5f);
        verify(mKeyguardHostViewController, never()).setExpansion(anyFloat());
    }

    @Test
    public void testIsSecure() {
        mBouncer.ensureView();
        for (KeyguardSecurityModel.SecurityMode mode : KeyguardSecurityModel.SecurityMode.values()){
            reset(mKeyguardSecurityModel);
            when(mKeyguardSecurityModel.getSecurityMode(anyInt())).thenReturn(mode);
            Assert.assertEquals("Security doesn't match for mode: " + mode,
                    mBouncer.isSecure(), mode != KeyguardSecurityModel.SecurityMode.None);
        }
    }

    @Test
    public void testIsShowingScrimmed_true() {
        doAnswer(invocation -> {
            assertThat(mBouncer.isScrimmed()).isTrue();
            return null;
        }).when(mExpansionCallback).onFullyShown();
        mBouncer.show(false /* resetSecuritySelection */, true /* animate */);
        assertThat(mBouncer.isScrimmed()).isTrue();
        mBouncer.hide(false /* destroyView */);
        assertThat(mBouncer.isScrimmed()).isFalse();
    }

    @Test
    public void testIsShowingScrimmed_false() {
        doAnswer(invocation -> {
            assertThat(mBouncer.isScrimmed()).isFalse();
            return null;
        }).when(mExpansionCallback).onFullyShown();
        mBouncer.show(false /* resetSecuritySelection */, false /* animate */);
        assertThat(mBouncer.isScrimmed()).isFalse();
    }

    @Test
    public void testWillDismissWithAction() {
        mBouncer.ensureView();
        Assert.assertFalse("Action not set yet", mBouncer.willDismissWithAction());
        when(mKeyguardHostViewController.hasDismissActions()).thenReturn(true);
        Assert.assertTrue("Action should exist", mBouncer.willDismissWithAction());
    }

    @Test
    public void testShow_delaysIfFaceAuthIsRunning() {
        when(mKeyguardStateController.isFaceAuthEnabled()).thenReturn(true);
        mBouncer.show(true /* reset */);

        ArgumentCaptor<Runnable> showRunnable = ArgumentCaptor.forClass(Runnable.class);
        verify(mHandler).postDelayed(showRunnable.capture(),
                eq(KeyguardBouncer.BOUNCER_FACE_DELAY));

        mBouncer.hide(false /* destroyView */);
        verify(mHandler).removeCallbacks(eq(showRunnable.getValue()));
    }
    @Test
    public void testShow_delaysIfFaceAuthIsRunning_unlessBypassEnabled() {
        when(mKeyguardStateController.isFaceAuthEnabled()).thenReturn(true);
        when(mKeyguardBypassController.getBypassEnabled()).thenReturn(true);
        mBouncer.show(true /* reset */);

        verify(mHandler, never()).postDelayed(any(), anyLong());
    }

    @Test
    public void testShow_delaysIfFaceAuthIsRunning_unlessFingerprintEnrolled() {
        when(mKeyguardStateController.isFaceAuthEnabled()).thenReturn(true);
        when(mKeyguardUpdateMonitor.getCachedIsUnlockWithFingerprintPossible(0))
                .thenReturn(true);
        mBouncer.show(true /* reset */);

        verify(mHandler, never()).postDelayed(any(), anyLong());
    }

    @Test
    public void testRegisterUpdateMonitorCallback() {
        verify(mKeyguardUpdateMonitor).registerCallback(any());
    }

    @Test
    public void testInTransit_whenTranslation() {
        mBouncer.show(true);
        mBouncer.setExpansion(EXPANSION_HIDDEN);
        assertThat(mBouncer.inTransit()).isFalse();
        mBouncer.setExpansion(0.5f);
        assertThat(mBouncer.inTransit()).isTrue();
        mBouncer.setExpansion(EXPANSION_VISIBLE);
        assertThat(mBouncer.inTransit()).isFalse();
    }

    @Test
    public void testUpdateResources_delegatesToRootView() {
        mBouncer.ensureView();
        mBouncer.updateResources();

        // This is mocked, so won't pick up on the call to updateResources via
        // mKeyguardViewController.init(), only updateResources above.
        verify(mKeyguardHostViewController).updateResources();
    }

    @Test
    public void testUpdateKeyguardPosition_delegatesToRootView() {
        mBouncer.ensureView();
        mBouncer.updateKeyguardPosition(1.0f);

        verify(mKeyguardHostViewController).updateKeyguardPosition(1.0f);
    }

    @Test
    public void testExpansion_notifiesCallback() {
        mBouncer.ensureView();
        mBouncer.setExpansion(0.5f);

        final BouncerExpansionCallback callback = mock(BouncerExpansionCallback.class);
        mBouncer.addBouncerExpansionCallback(callback);

        mBouncer.setExpansion(EXPANSION_HIDDEN);
        verify(callback).onFullyHidden();
        verify(callback).onExpansionChanged(EXPANSION_HIDDEN);

        Mockito.clearInvocations(callback);
        mBouncer.setExpansion(EXPANSION_VISIBLE);
        verify(callback).onFullyShown();
        verify(callback).onExpansionChanged(EXPANSION_VISIBLE);

        Mockito.clearInvocations(callback);
        float bouncerHideAmount = 0.9f;
        // Ensure the callback only triggers once despite multiple calls to setExpansion
        // with the same value.
        mBouncer.setExpansion(bouncerHideAmount);
        mBouncer.setExpansion(bouncerHideAmount);
        verify(callback, times(1)).onStartingToHide();
        verify(callback, times(1)).onExpansionChanged(bouncerHideAmount);

        Mockito.clearInvocations(callback);
        mBouncer.removeBouncerExpansionCallback(callback);
        bouncerHideAmount = 0.5f;
        mBouncer.setExpansion(bouncerHideAmount);
        verify(callback, never()).onExpansionChanged(bouncerHideAmount);
    }

    @Test
    public void testOnResumeCalledForFullscreenBouncerOnSecondShow() {
        // GIVEN a security mode which requires fullscreen bouncer
        when(mKeyguardSecurityModel.getSecurityMode(anyInt()))
                .thenReturn(KeyguardSecurityModel.SecurityMode.SimPin);
        mBouncer.show(true);

        // WHEN a second call to show occurs, the bouncer will already by visible
        reset(mKeyguardHostViewController);
        mBouncer.show(true);

        // THEN ensure the ViewController is told to resume
        verify(mKeyguardHostViewController).onResume();
    }
}
