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

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.verifyZeroInteractions;
import static org.mockito.Mockito.when;

import android.graphics.Color;
import android.support.test.filters.SmallTest;
import android.test.UiThreadTest;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;

import com.android.internal.widget.LockPatternUtils;
import com.android.keyguard.KeyguardHostView;
import com.android.keyguard.KeyguardSecurityModel;
import com.android.keyguard.ViewMediatorCallback;
import com.android.systemui.DejankUtils;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.classifier.FalsingManager;
import com.android.systemui.keyguard.DismissCallbackRegistry;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@SmallTest
@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper(setAsMainLooper = true)
public class KeyguardBouncerTest extends SysuiTestCase {

    @Mock
    private FalsingManager mFalsingManager;
    @Mock
    private ViewMediatorCallback mViewMediatorCallback;
    @Mock
    private LockPatternUtils mLockPatternUtils;
    @Mock
    private DismissCallbackRegistry mDismissCallbackRegistry;
    @Mock
    private KeyguardHostView mKeyguardHostView;
    @Mock
    private ViewTreeObserver mViewTreeObserver;

    private KeyguardBouncer mBouncer;

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);
        DejankUtils.setImmediate(true);
        final ViewGroup container = new FrameLayout(getContext());
        when(mKeyguardHostView.getViewTreeObserver()).thenReturn(mViewTreeObserver);
        when(mKeyguardHostView.getHeight()).thenReturn(500);
        mBouncer = new KeyguardBouncer(getContext(), mViewMediatorCallback,
                mLockPatternUtils, container, mDismissCallbackRegistry, mFalsingManager) {
            @Override
            protected void inflateView() {
                super.inflateView();
                mKeyguardView = mKeyguardHostView;
            }
        };
    }

    @Test
    public void testInflateView_doesntCrash() {
        mBouncer.inflateView();
    }

    @Test
    public void testShow_notifiesFalsingManager() {
        mBouncer.show(true);
        verify(mFalsingManager).onBouncerShown();

        mBouncer.show(true, false);
        verifyNoMoreInteractions(mFalsingManager);
    }

    /**
     * Regression test: Invisible bouncer when occluded.
     */
    @Test
    public void testShow_bouncerIsVisible() {
        // Expand notification panel as if we were in the keyguard.
        mBouncer.ensureView();
        mBouncer.setExpansion(1);

        reset(mKeyguardHostView);
        when(mKeyguardHostView.getHeight()).thenReturn(500);

        mBouncer.show(true);
        verify(mKeyguardHostView).setAlpha(eq(1f));
        verify(mKeyguardHostView).setTranslationY(eq(0f));
    }

    @Test
    public void testShow_notifiesVisibility() {
        mBouncer.show(true);
        verify(mViewMediatorCallback).onBouncerVisiblityChanged(eq(true));

        // Not called again when visible
        reset(mViewMediatorCallback);
        mBouncer.show(true);
        verifyNoMoreInteractions(mViewMediatorCallback);
    }

    @Test
    public void testShow_triesToDismissKeyguard() {
        mBouncer.show(true);
        verify(mKeyguardHostView).dismiss(anyInt());
    }

    @Test
    public void testShow_resetsSecuritySelection() {
        mBouncer.show(false);
        verify(mKeyguardHostView, never()).showPrimarySecurityScreen();

        mBouncer.hide(false);
        mBouncer.show(true);
        verify(mKeyguardHostView).showPrimarySecurityScreen();
    }

    @Test
    public void testShow_animatesKeyguardView() {
        mBouncer.show(true);
        verify(mKeyguardHostView).startAppearAnimation();
    }

    @Test
    public void testShow_showsErrorMessage() {
        final String errorMessage = "an error message";
        when(mViewMediatorCallback.consumeCustomMessage()).thenReturn(errorMessage);
        mBouncer.show(true);
        verify(mKeyguardHostView).showErrorMessage(eq(errorMessage));
    }

    @Test
    public void testOnFullyShown_notifiesFalsingManager() {
        mBouncer.ensureView();
        mBouncer.onFullyShown();
        verify(mFalsingManager).onBouncerShown();
    }

    @Test
    public void testOnFullyShown_notifiesKeyguardView() {
        mBouncer.ensureView();
        mBouncer.onFullyShown();
        verify(mKeyguardHostView).onResume();
    }

    @Test
    public void testOnFullyHidden_notifiesFalsingManager() {
        mBouncer.onFullyHidden();
        verify(mFalsingManager).onBouncerHidden();
    }

    @Test
    public void testHide_notifiesFalsingManager() {
        mBouncer.hide(false);
        verify(mFalsingManager).onBouncerHidden();
    }

    @Test
    public void testHide_notifiesVisibility() {
        mBouncer.hide(false);
        verify(mViewMediatorCallback).onBouncerVisiblityChanged(eq(false));
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
    public void testShowPromptReason_propagates() {
        mBouncer.ensureView();
        mBouncer.showPromptReason(1);
        verify(mKeyguardHostView).showPromptReason(eq(1));
    }

    @Test
    public void testShowMessage_propagates() {
        final String message = "a message";
        mBouncer.ensureView();
        mBouncer.showMessage(message, Color.GREEN);
        verify(mKeyguardHostView).showMessage(eq(message), eq(Color.GREEN));
    }

    @Test
    public void testShowOnDismissAction_showsBouncer() {
        final KeyguardHostView.OnDismissAction dismissAction = () -> false;
        final Runnable cancelAction = () -> {};
        mBouncer.showWithDismissAction(dismissAction, cancelAction);
        verify(mKeyguardHostView).setOnDismissAction(dismissAction, cancelAction);
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
        verify(mKeyguardHostView).startDisappearAnimation(r);
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
        verify(mKeyguardHostView).setAlpha(anyFloat());
        verify(mKeyguardHostView).setTranslationY(anyFloat());
    }

    @Test
    public void testNeedsFullscreenBouncer_asksKeyguardView() {
        mBouncer.ensureView();
        mBouncer.needsFullscreenBouncer();
        verify(mKeyguardHostView).getSecurityMode();
        verify(mKeyguardHostView, never()).getCurrentSecurityMode();
    }

    @Test
    public void testIsFullscreenBouncer_asksKeyguardView() {
        mBouncer.ensureView();
        mBouncer.isFullscreenBouncer();
        verify(mKeyguardHostView).getCurrentSecurityMode();
        verify(mKeyguardHostView, never()).getSecurityMode();
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
        reset(mKeyguardHostView);
        mBouncer.startPreHideAnimation(null /* runnable */);
        mBouncer.setExpansion(0.5f);
        verify(mKeyguardHostView, never()).setTranslationY(anyFloat());
        verify(mKeyguardHostView, never()).setAlpha(anyFloat());
    }

    @Test
    public void testIsSecure() {
        Assert.assertTrue("Bouncer is secure before inflating views", mBouncer.isSecure());

        mBouncer.ensureView();
        for (KeyguardSecurityModel.SecurityMode mode : KeyguardSecurityModel.SecurityMode.values()){
            reset(mKeyguardHostView);
            when(mKeyguardHostView.getSecurityMode()).thenReturn(mode);
            Assert.assertEquals("Security doesn't match for mode: " + mode,
                    mBouncer.isSecure(), mode != KeyguardSecurityModel.SecurityMode.None);
        }
    }

    @Test
    public void testWillDismissWithAction() {
        mBouncer.ensureView();
        Assert.assertFalse("Action not set yet", mBouncer.willDismissWithAction());
        when(mKeyguardHostView.hasDismissActions()).thenReturn(true);
        Assert.assertTrue("Action should exist", mBouncer.willDismissWithAction());
    }
}
