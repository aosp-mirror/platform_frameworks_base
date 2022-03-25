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

import static com.android.systemui.doze.util.BurnInHelperKt.getBurnInOffset;

import android.os.Handler;
import android.util.MathUtils;
import android.view.View;
import android.view.ViewGroup;

import com.android.keyguard.BouncerPanelExpansionCalculator;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.dreams.complication.ComplicationHostViewController;
import com.android.systemui.dreams.dagger.DreamOverlayComponent;
import com.android.systemui.dreams.dagger.DreamOverlayModule;
import com.android.systemui.statusbar.BlurUtils;
import com.android.systemui.statusbar.phone.KeyguardBouncer;
import com.android.systemui.statusbar.phone.StatusBarKeyguardViewManager;
import com.android.systemui.util.ViewController;

import javax.inject.Inject;
import javax.inject.Named;

/**
 * View controller for {@link DreamOverlayContainerView}.
 */
@DreamOverlayComponent.DreamOverlayScope
public class DreamOverlayContainerViewController extends ViewController<DreamOverlayContainerView> {
    private final DreamOverlayStatusBarViewController mStatusBarViewController;
    private final StatusBarKeyguardViewManager mStatusBarKeyguardViewManager;
    private final BlurUtils mBlurUtils;

    private final ComplicationHostViewController mComplicationHostViewController;

    // The dream overlay's content view, which is located below the status bar (in z-order) and is
    // the space into which widgets are placed.
    private final ViewGroup mDreamOverlayContentView;

    // The maximum translation offset to apply to the overlay container to avoid screen burn-in.
    private final int mMaxBurnInOffset;

    // The interval in milliseconds between burn-in protection updates.
    private final long mBurnInProtectionUpdateInterval;

    // Amount of time in milliseconds to linear interpolate toward the final jitter offset. Once
    // this time is achieved, the normal jitter algorithm applies in full.
    private final long mMillisUntilFullJitter;

    // Main thread handler used to schedule periodic tasks (e.g. burn-in protection updates).
    private final Handler mHandler;

    private long mJitterStartTimeMillis;

    private boolean mBouncerAnimating;

    private final KeyguardBouncer.BouncerExpansionCallback mBouncerExpansionCallback =
            new KeyguardBouncer.BouncerExpansionCallback() {

                @Override
                public void onStartingToShow() {
                    mBouncerAnimating = true;
                }

                @Override
                public void onStartingToHide() {
                    mBouncerAnimating = true;
                }

                @Override
                public void onFullyHidden() {
                    mBouncerAnimating = false;
                }

                @Override
                public void onFullyShown() {
                    mBouncerAnimating = false;
                }

                @Override
                public void onExpansionChanged(float bouncerHideAmount) {
                    if (!mBouncerAnimating) return;
                    final float scaledFraction =
                            BouncerPanelExpansionCalculator.getBackScrimScaledExpansion(
                                    bouncerHideAmount);
                    final int blurRadius =
                            (int) mBlurUtils.blurRadiusOfRatio(1 - scaledFraction);
                    updateTransitionState(blurRadius, scaledFraction);
                }

                @Override
                public void onVisibilityChanged(boolean isVisible) {
                    // The bouncer may be hidden abruptly without triggering onExpansionChanged.
                    // In this case, we should reset the transition state.
                    if (!isVisible) {
                        updateTransitionState(0, 1f);
                    }
                }
            };

    @Inject
    public DreamOverlayContainerViewController(
            DreamOverlayContainerView containerView,
            ComplicationHostViewController complicationHostViewController,
            @Named(DreamOverlayModule.DREAM_OVERLAY_CONTENT_VIEW) ViewGroup contentView,
            DreamOverlayStatusBarViewController statusBarViewController,
            StatusBarKeyguardViewManager statusBarKeyguardViewManager,
            BlurUtils blurUtils,
            @Main Handler handler,
            @Named(DreamOverlayModule.MAX_BURN_IN_OFFSET) int maxBurnInOffset,
            @Named(DreamOverlayModule.BURN_IN_PROTECTION_UPDATE_INTERVAL) long
                    burnInProtectionUpdateInterval,
            @Named(DreamOverlayModule.MILLIS_UNTIL_FULL_JITTER) long millisUntilFullJitter) {
        super(containerView);
        mDreamOverlayContentView = contentView;
        mStatusBarViewController = statusBarViewController;
        mStatusBarKeyguardViewManager = statusBarKeyguardViewManager;
        mBlurUtils = blurUtils;

        mComplicationHostViewController = complicationHostViewController;
        final View view = mComplicationHostViewController.getView();

        mDreamOverlayContentView.addView(view,
                new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT));

        mHandler = handler;
        mMaxBurnInOffset = maxBurnInOffset;
        mBurnInProtectionUpdateInterval = burnInProtectionUpdateInterval;
        mMillisUntilFullJitter = millisUntilFullJitter;
    }

    @Override
    protected void onInit() {
        mStatusBarViewController.init();
        mComplicationHostViewController.init();
    }

    @Override
    protected void onViewAttached() {
        mJitterStartTimeMillis = System.currentTimeMillis();
        mHandler.postDelayed(this::updateBurnInOffsets, mBurnInProtectionUpdateInterval);
        final KeyguardBouncer bouncer = mStatusBarKeyguardViewManager.getBouncer();
        if (bouncer != null) {
            bouncer.addBouncerExpansionCallback(mBouncerExpansionCallback);
        }
    }

    @Override
    protected void onViewDetached() {
        mHandler.removeCallbacks(this::updateBurnInOffsets);
        final KeyguardBouncer bouncer = mStatusBarKeyguardViewManager.getBouncer();
        if (bouncer != null) {
            bouncer.removeBouncerExpansionCallback(mBouncerExpansionCallback);
        }
    }

    View getContainerView() {
        return mView;
    }

    private void updateBurnInOffsets() {
        int burnInOffset = mMaxBurnInOffset;

        // Make sure the offset starts at zero, to avoid a big jump in the overlay when it first
        // appears.
        long millisSinceStart = System.currentTimeMillis() - mJitterStartTimeMillis;
        if (millisSinceStart < mMillisUntilFullJitter) {
            float lerpAmount = (float) millisSinceStart / (float) mMillisUntilFullJitter;
            burnInOffset = Math.round(MathUtils.lerp(0f, burnInOffset, lerpAmount));
        }

        // These translation values change slowly, and the set translation methods are idempotent,
        // so no translation occurs when the values don't change.
        int burnInOffsetX = getBurnInOffset(burnInOffset * 2, true)
                - burnInOffset;
        int burnInOffsetY = getBurnInOffset(burnInOffset * 2, false)
                - burnInOffset;
        mView.setTranslationX(burnInOffsetX);
        mView.setTranslationY(burnInOffsetY);

        mHandler.postDelayed(this::updateBurnInOffsets, mBurnInProtectionUpdateInterval);
    }

    private void updateTransitionState(int blurRadiusPixels, float alpha) {
        mBlurUtils.applyBlur(mView.getViewRootImpl(), blurRadiusPixels, false);
        mView.setAlpha(alpha);
    }
}
