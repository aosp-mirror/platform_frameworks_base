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

package com.android.systemui.dreams;

import static kotlinx.coroutines.flow.FlowKt.emptyFlow;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.res.Resources;
import android.graphics.Region;
import android.os.Handler;
import android.testing.TestableLooper.RunWithLooper;
import android.view.AttachedSurfaceControl;
import android.view.ViewGroup;
import android.view.ViewRootImpl;
import android.view.ViewTreeObserver;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import com.android.dream.lowlight.LowLightTransitionCoordinator;
import com.android.keyguard.BouncerPanelExpansionCalculator;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.ambient.touch.scrim.BouncerlessScrimController;
import com.android.systemui.bouncer.domain.interactor.PrimaryBouncerCallbackInteractor;
import com.android.systemui.bouncer.domain.interactor.PrimaryBouncerCallbackInteractor.PrimaryBouncerExpansionCallback;
import com.android.systemui.complication.ComplicationHostViewController;
import com.android.systemui.keyguard.domain.interactor.KeyguardTransitionInteractor;
import com.android.systemui.statusbar.BlurUtils;

import kotlinx.coroutines.CoroutineDispatcher;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@SmallTest
@RunWith(AndroidJUnit4.class)
@RunWithLooper(setAsMainLooper = true)
public class DreamOverlayContainerViewControllerTest extends SysuiTestCase {
    private static final int MAX_BURN_IN_OFFSET = 20;
    private static final long BURN_IN_PROTECTION_UPDATE_INTERVAL = 10;
    private static final long MILLIS_UNTIL_FULL_JITTER = 240 * 1000;

    @Mock
    Resources mResources;

    @Mock
    ViewTreeObserver mViewTreeObserver;

    @Mock
    DreamOverlayStatusBarViewController mDreamOverlayStatusBarViewController;

    @Mock
    LowLightTransitionCoordinator mLowLightTransitionCoordinator;

    @Mock
    DreamOverlayContainerView mDreamOverlayContainerView;

    @Mock
    ComplicationHostViewController mComplicationHostViewController;

    @Mock
    AttachedSurfaceControl mAttachedSurfaceControl;

    @Mock
    ViewGroup mDreamOverlayContentView;

    @Mock
    Handler mHandler;

    @Mock
    CoroutineDispatcher mDispatcher;

    @Mock
    BlurUtils mBlurUtils;

    @Mock
    ViewRootImpl mViewRoot;

    @Mock
    PrimaryBouncerCallbackInteractor mPrimaryBouncerCallbackInteractor;

    @Mock
    DreamOverlayAnimationsController mAnimationsController;

    @Mock
    BouncerlessScrimController mBouncerlessScrimController;

    @Mock
    DreamOverlayStateController mStateController;
    @Mock
    KeyguardTransitionInteractor mKeyguardTransitionInteractor;

    DreamOverlayContainerViewController mController;

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);

        when(mDreamOverlayContainerView.getResources()).thenReturn(mResources);
        when(mDreamOverlayContainerView.getViewTreeObserver()).thenReturn(mViewTreeObserver);
        when(mDreamOverlayContainerView.getViewRootImpl()).thenReturn(mViewRoot);
        when(mDreamOverlayContainerView.getRootSurfaceControl())
                .thenReturn(mAttachedSurfaceControl);
        when(mKeyguardTransitionInteractor.isFinishedInStateWhere(any())).thenReturn(emptyFlow());

        mController = new DreamOverlayContainerViewController(
                mDreamOverlayContainerView,
                mComplicationHostViewController,
                mDreamOverlayContentView,
                mDreamOverlayStatusBarViewController,
                mLowLightTransitionCoordinator,
                mBlurUtils,
                mHandler,
                mDispatcher,
                mResources,
                MAX_BURN_IN_OFFSET,
                BURN_IN_PROTECTION_UPDATE_INTERVAL,
                MILLIS_UNTIL_FULL_JITTER,
                mPrimaryBouncerCallbackInteractor,
                mAnimationsController,
                mStateController,
                mBouncerlessScrimController,
                mKeyguardTransitionInteractor);
    }

    @Test
    public void testRootSurfaceControlInsetSetOnAttach() {
        mController.onViewAttached();
        verify(mAttachedSurfaceControl).setTouchableRegion(eq(Region.obtain()));
    }

    @Test
    public void testDreamOverlayStatusBarViewControllerInitialized() {
        mController.init();
        verify(mDreamOverlayStatusBarViewController).init();
    }

    @Test
    public void testBurnInProtectionStartsWhenContentViewAttached() {
        mController.onViewAttached();
        verify(mHandler).postDelayed(any(Runnable.class), eq(BURN_IN_PROTECTION_UPDATE_INTERVAL));
    }

    @Test
    public void testBurnInProtectionStopsWhenContentViewDetached() {
        mController.onViewDetached();
        verify(mHandler).removeCallbacks(any(Runnable.class));
    }

    @Test
    public void testBurnInProtectionOffsetsStartAtZero() {
        ArgumentCaptor<Runnable> runnableCaptor = ArgumentCaptor.forClass(Runnable.class);
        mController.onViewAttached();
        verify(mHandler).postDelayed(
                runnableCaptor.capture(), eq(BURN_IN_PROTECTION_UPDATE_INTERVAL));
        runnableCaptor.getValue().run();
        verify(mDreamOverlayContainerView).setTranslationX(0.f);
        verify(mDreamOverlayContainerView).setTranslationY(0.f);
    }

    @Test
    public void testBurnInProtectionReschedulesUpdate() {
        ArgumentCaptor<Runnable> runnableCaptor = ArgumentCaptor.forClass(Runnable.class);
        mController.onViewAttached();
        verify(mHandler).postDelayed(
                runnableCaptor.capture(), eq(BURN_IN_PROTECTION_UPDATE_INTERVAL));
        runnableCaptor.getValue().run();
        verify(mHandler).postDelayed(runnableCaptor.getValue(), BURN_IN_PROTECTION_UPDATE_INTERVAL);
    }

    @Test
    public void testBouncerAnimation_doesNotApply() {
        final ArgumentCaptor<PrimaryBouncerExpansionCallback> bouncerExpansionCaptor =
                ArgumentCaptor.forClass(PrimaryBouncerExpansionCallback.class);
        mController.onViewAttached();
        verify(mPrimaryBouncerCallbackInteractor).addBouncerExpansionCallback(
                bouncerExpansionCaptor.capture());

        bouncerExpansionCaptor.getValue().onExpansionChanged(0.5f);
        verify(mBlurUtils, never()).applyBlur(eq(mViewRoot), anyInt(), eq(false));
    }

    @Test
    public void testBouncerAnimation_updateBlur() {
        final ArgumentCaptor<PrimaryBouncerExpansionCallback> bouncerExpansionCaptor =
                ArgumentCaptor.forClass(PrimaryBouncerExpansionCallback.class);
        mController.onViewAttached();
        verify(mPrimaryBouncerCallbackInteractor).addBouncerExpansionCallback(
                bouncerExpansionCaptor.capture());

        final float blurRadius = 1337f;
        when(mBlurUtils.blurRadiusOfRatio(anyFloat())).thenReturn(blurRadius);

        bouncerExpansionCaptor.getValue().onStartingToShow();

        final float bouncerHideAmount = 0.05f;
        final float scaledFraction =
                BouncerPanelExpansionCalculator.aboutToShowBouncerProgress(bouncerHideAmount);

        bouncerExpansionCaptor.getValue().onExpansionChanged(bouncerHideAmount);
        verify(mBlurUtils).blurRadiusOfRatio(1 - scaledFraction);
        verify(mBlurUtils).applyBlur(mViewRoot, (int) blurRadius, false);
    }

    @Test
    public void testStartDreamEntryAnimationsOnAttachedNonLowLight() {
        when(mStateController.isLowLightActive()).thenReturn(false);

        mController.onViewAttached();

        verify(mAnimationsController).startEntryAnimations(false);
        verify(mAnimationsController, never()).cancelAnimations();
    }

    @Test
    public void testNeverStartDreamEntryAnimationsOnAttachedForLowLight() {
        when(mStateController.isLowLightActive()).thenReturn(true);

        mController.onViewAttached();

        verify(mAnimationsController, never()).startEntryAnimations(anyBoolean());
    }

    @Test
    public void testDownwardEntryAnimationsWhenExitingLowLight() {
        ArgumentCaptor<DreamOverlayStateController.Callback> callbackCaptor =
                ArgumentCaptor.forClass(DreamOverlayStateController.Callback.class);
        when(mStateController.isLowLightActive()).thenReturn(false);

        // Call onInit so that the callback is added.
        mController.onInit();
        verify(mStateController).addCallback(callbackCaptor.capture());

        // Send the signal that low light is exiting
        callbackCaptor.getValue().onExitLowLight();

        // View is attached to trigger animations.
        mController.onViewAttached();

        // Entry animations should be started then immediately ended to skip to the end.
        verify(mAnimationsController).startEntryAnimations(true);
    }

    @Test
    public void testStartsExitAnimationsBeforeEnteringLowLight() {
        mController.onBeforeEnterLowLight();

        verify(mAnimationsController).startExitAnimations();
    }

    @Test
    public void testCancelDreamEntryAnimationsOnDetached() {
        mController.onViewAttached();
        mController.onViewDetached();

        verify(mAnimationsController).cancelAnimations();
    }
}
