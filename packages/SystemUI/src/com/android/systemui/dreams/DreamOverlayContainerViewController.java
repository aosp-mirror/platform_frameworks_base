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

import static android.service.dreams.Flags.dreamHandlesBeingObscured;

import static com.android.keyguard.BouncerPanelExpansionCalculator.aboutToShowBouncerProgress;
import static com.android.keyguard.BouncerPanelExpansionCalculator.getDreamAlphaScaledExpansion;
import static com.android.keyguard.BouncerPanelExpansionCalculator.getDreamYPositionScaledExpansion;
import static com.android.systemui.complication.ComplicationLayoutParams.POSITION_BOTTOM;
import static com.android.systemui.complication.ComplicationLayoutParams.POSITION_TOP;
import static com.android.systemui.doze.util.BurnInHelperKt.getBurnInOffset;
import static com.android.systemui.util.kotlin.JavaAdapterKt.collectFlow;
import static com.android.systemui.util.kotlin.JavaAdapterKt.combineFlows;

import android.animation.Animator;
import android.app.DreamManager;
import android.content.res.Resources;
import android.graphics.Region;
import android.os.Handler;
import android.util.MathUtils;
import android.view.View;
import android.view.ViewGroup;

import com.android.app.animation.Interpolators;
import com.android.dream.lowlight.LowLightTransitionCoordinator;
import com.android.systemui.ambient.statusbar.ui.AmbientStatusBarViewController;
import com.android.systemui.ambient.touch.scrim.BouncerlessScrimController;
import com.android.systemui.bouncer.domain.interactor.PrimaryBouncerCallbackInteractor;
import com.android.systemui.bouncer.domain.interactor.PrimaryBouncerCallbackInteractor.PrimaryBouncerExpansionCallback;
import com.android.systemui.communal.domain.interactor.CommunalInteractor;
import com.android.systemui.complication.ComplicationHostViewController;
import com.android.systemui.dagger.qualifiers.Background;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.dreams.dagger.DreamOverlayComponent;
import com.android.systemui.dreams.dagger.DreamOverlayModule;
import com.android.systemui.keyguard.domain.interactor.KeyguardTransitionInteractor;
import com.android.systemui.keyguard.shared.model.KeyguardState;
import com.android.systemui.res.R;
import com.android.systemui.scene.shared.model.Scenes;
import com.android.systemui.shade.ShadeExpansionChangeEvent;
import com.android.systemui.shade.domain.interactor.ShadeInteractor;
import com.android.systemui.statusbar.BlurUtils;
import com.android.systemui.touch.TouchInsetManager;
import com.android.systemui.util.ViewController;

import kotlinx.coroutines.CoroutineDispatcher;
import kotlinx.coroutines.DisposableHandle;
import kotlinx.coroutines.flow.FlowKt;

import java.util.Arrays;

import javax.inject.Inject;
import javax.inject.Named;

/**
 * View controller for {@link DreamOverlayContainerView}.
 */
@DreamOverlayComponent.DreamOverlayScope
public class DreamOverlayContainerViewController extends
        ViewController<DreamOverlayContainerView> implements
        LowLightTransitionCoordinator.LowLightEnterListener {
    private final AmbientStatusBarViewController mStatusBarViewController;
    private final TouchInsetManager.TouchInsetSession mTouchInsetSession;
    private final BlurUtils mBlurUtils;
    private final DreamOverlayAnimationsController mDreamOverlayAnimationsController;
    private final DreamOverlayStateController mStateController;

    private final LowLightTransitionCoordinator mLowLightTransitionCoordinator;
    private final KeyguardTransitionInteractor mKeyguardTransitionInteractor;
    private final ShadeInteractor mShadeInteractor;
    private final CommunalInteractor mCommunalInteractor;

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
    private final CoroutineDispatcher mBackgroundDispatcher;
    private final int mDreamOverlayMaxTranslationY;
    private final PrimaryBouncerCallbackInteractor mPrimaryBouncerCallbackInteractor;
    private final DreamManager mDreamManager;

    private long mJitterStartTimeMillis;

    private boolean mBouncerAnimating;
    private boolean mWakingUpFromSwipe;
    private boolean mAnyBouncerShowing;

    private final BouncerlessScrimController mBouncerlessScrimController;

    private final BouncerlessScrimController.Callback mBouncerlessExpansionCallback =
            new BouncerlessScrimController.Callback() {
        @Override
        public void onExpansion(ShadeExpansionChangeEvent event) {
            updateTransitionState(event.getFraction());
        }

        @Override
        public void onWakeup() {
            mWakingUpFromSwipe = true;
        }
    };

    private final PrimaryBouncerExpansionCallback
            mBouncerExpansionCallback =
            new PrimaryBouncerExpansionCallback() {

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
                    if (mBouncerAnimating) {
                        updateTransitionState(bouncerHideAmount);
                    }
                }

                @Override
                public void onVisibilityChanged(boolean isVisible) {
                    // The bouncer may be hidden abruptly without triggering onExpansionChanged.
                    // In this case, we should reset the transition state.
                    if (!isVisible) {
                        updateTransitionState(1f);
                    }
                }
            };

    /**
     * If {@code true}, the dream has just transitioned from the low light dream back to the user
     * dream and we should play an entry animation where the overlay slides in downwards from the
     * top instead of the typicla slide in upwards from the bottom.
     */
    private boolean mExitingLowLight;

    private final DreamOverlayStateController.Callback
            mDreamOverlayStateCallback =
            new DreamOverlayStateController.Callback() {
                @Override
                public void onExitLowLight() {
                    mExitingLowLight = true;
                }
            };

    private DisposableHandle mFlowHandle;

    @Inject
    public DreamOverlayContainerViewController(
            DreamOverlayContainerView containerView,
            ComplicationHostViewController complicationHostViewController,
            @Named(DreamOverlayModule.DREAM_OVERLAY_CONTENT_VIEW) ViewGroup contentView,
            AmbientStatusBarViewController statusBarViewController,
            LowLightTransitionCoordinator lowLightTransitionCoordinator,
            TouchInsetManager.TouchInsetSession touchInsetSession,
            BlurUtils blurUtils,
            @Main Handler handler,
            @Background CoroutineDispatcher backgroundDispatcher,
            @Main Resources resources,
            @Named(DreamOverlayModule.MAX_BURN_IN_OFFSET) int maxBurnInOffset,
            @Named(DreamOverlayModule.BURN_IN_PROTECTION_UPDATE_INTERVAL) long
                    burnInProtectionUpdateInterval,
            @Named(DreamOverlayModule.MILLIS_UNTIL_FULL_JITTER) long millisUntilFullJitter,
            PrimaryBouncerCallbackInteractor primaryBouncerCallbackInteractor,
            DreamOverlayAnimationsController animationsController,
            DreamOverlayStateController stateController,
            BouncerlessScrimController bouncerlessScrimController,
            KeyguardTransitionInteractor keyguardTransitionInteractor,
            ShadeInteractor shadeInteractor,
            CommunalInteractor communalInteractor,
            DreamManager dreamManager) {
        super(containerView);
        mDreamOverlayContentView = contentView;
        mStatusBarViewController = statusBarViewController;
        mTouchInsetSession = touchInsetSession;
        mBlurUtils = blurUtils;
        mDreamOverlayAnimationsController = animationsController;
        mStateController = stateController;
        mCommunalInteractor = communalInteractor;
        mLowLightTransitionCoordinator = lowLightTransitionCoordinator;

        mBouncerlessScrimController = bouncerlessScrimController;

        mKeyguardTransitionInteractor = keyguardTransitionInteractor;
        mShadeInteractor = shadeInteractor;

        mComplicationHostViewController = complicationHostViewController;
        mDreamOverlayMaxTranslationY = resources.getDimensionPixelSize(
                R.dimen.dream_overlay_y_offset);
        final View view = mComplicationHostViewController.getView();

        mDreamOverlayContentView.addView(view,
                new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT));

        mHandler = handler;
        mBackgroundDispatcher = backgroundDispatcher;
        mMaxBurnInOffset = maxBurnInOffset;
        mBurnInProtectionUpdateInterval = burnInProtectionUpdateInterval;
        mMillisUntilFullJitter = millisUntilFullJitter;
        mPrimaryBouncerCallbackInteractor = primaryBouncerCallbackInteractor;
        mDreamManager = dreamManager;
    }

    @Override
    protected void onInit() {
        mStateController.addCallback(mDreamOverlayStateCallback);
        mStatusBarViewController.init();
        mComplicationHostViewController.init();
        mDreamOverlayAnimationsController.init(mView);
        mLowLightTransitionCoordinator.setLowLightEnterListener(this);
    }

    @Override
    public void destroy() {
        mStateController.removeCallback(mDreamOverlayStateCallback);
        mStatusBarViewController.destroy();
        mComplicationHostViewController.destroy();
        mDreamOverlayAnimationsController.destroy();
        mLowLightTransitionCoordinator.setLowLightEnterListener(null);

        super.destroy();
    }

    @Override
    protected void onViewAttached() {
        mWakingUpFromSwipe = false;
        mJitterStartTimeMillis = System.currentTimeMillis();
        mHandler.postDelayed(this::updateBurnInOffsets, mBurnInProtectionUpdateInterval);
        mPrimaryBouncerCallbackInteractor.addBouncerExpansionCallback(mBouncerExpansionCallback);
        mBouncerlessScrimController.addCallback(mBouncerlessExpansionCallback);
        final Region emptyRegion = Region.obtain();
        mView.getRootSurfaceControl().setTouchableRegion(emptyRegion);
        emptyRegion.recycle();

        if (dreamHandlesBeingObscured()) {
            mFlowHandle = collectFlow(
                    mView,
                    FlowKt.distinctUntilChanged(combineFlows(
                            mKeyguardTransitionInteractor.isFinishedIn(
                                    Scenes.Bouncer, KeyguardState.PRIMARY_BOUNCER),
                            mKeyguardTransitionInteractor.isFinishedIn(
                                    KeyguardState.ALTERNATE_BOUNCER),
                            mShadeInteractor.isAnyExpanded(),
                            mCommunalInteractor.isCommunalShowing(),
                            (primaryBouncerShowing,
                                    alternateBouncerShowing,
                                    shadeExpanded,
                                    communalShowing) -> {
                                mAnyBouncerShowing = primaryBouncerShowing
                                        || alternateBouncerShowing;
                                return mAnyBouncerShowing || shadeExpanded || communalShowing;
                            })),
                    mDreamManager::setDreamIsObscured,
                    mBackgroundDispatcher);
        }

        // Start dream entry animations. Skip animations for low light clock.
        if (!mStateController.isLowLightActive()) {
            // If this is transitioning from the low light dream to the user dream, the overlay
            // should translate in downwards instead of upwards.
            mDreamOverlayAnimationsController.startEntryAnimations(mExitingLowLight);
            mExitingLowLight = false;
        }
    }

    @Override
    protected void onViewDetached() {
        if (mFlowHandle != null) {
            mFlowHandle.dispose();
            mFlowHandle = null;
        }
        mHandler.removeCallbacksAndMessages(null);
        mPrimaryBouncerCallbackInteractor.removeBouncerExpansionCallback(mBouncerExpansionCallback);
        mBouncerlessScrimController.removeCallback(mBouncerlessExpansionCallback);
        mTouchInsetSession.clear();

        mDreamOverlayAnimationsController.cancelAnimations();
    }

    View getContainerView() {
        return mView;
    }

    boolean isBouncerShowing() {
        return mAnyBouncerShowing;
    }

    private void updateBurnInOffsets() {
        // Make sure the offset starts at zero, to avoid a big jump in the overlay when it first
        // appears.
        final long millisSinceStart = System.currentTimeMillis() - mJitterStartTimeMillis;
        final int burnInOffset;
        if (millisSinceStart < mMillisUntilFullJitter) {
            float lerpAmount = (float) millisSinceStart / (float) mMillisUntilFullJitter;
            burnInOffset = Math.round(MathUtils.lerp(0f, mMaxBurnInOffset, lerpAmount));
        } else {
            burnInOffset = mMaxBurnInOffset;
        }

        // These translation values change slowly, and the set translation methods are idempotent,
        // so no translation occurs when the values don't change.
        final int halfBurnInOffset = burnInOffset / 2;
        final int burnInOffsetX = getBurnInOffset(burnInOffset, true) - halfBurnInOffset;
        final int burnInOffsetY = getBurnInOffset(burnInOffset, false) - halfBurnInOffset;
        mView.setTranslationX(burnInOffsetX);
        mView.setTranslationY(burnInOffsetY);

        mHandler.postDelayed(this::updateBurnInOffsets, mBurnInProtectionUpdateInterval);
    }

    private void updateTransitionState(float bouncerHideAmount) {
        for (int position : Arrays.asList(POSITION_TOP, POSITION_BOTTOM)) {
            final float alpha = getAlpha(position, bouncerHideAmount);
            final float translationY = getTranslationY(position, bouncerHideAmount);
            mComplicationHostViewController.getViewsAtPosition(position).forEach(v -> {
                v.setAlpha(alpha);
                v.setTranslationY(translationY);
            });
        }

        mBlurUtils.applyBlur(mView.getViewRootImpl(),
                (int) mBlurUtils.blurRadiusOfRatio(
                        1 - aboutToShowBouncerProgress(bouncerHideAmount)), false);
    }

    private static float getAlpha(int position, float expansion) {
        return Interpolators.LINEAR_OUT_SLOW_IN.getInterpolation(
                position == POSITION_TOP ? getDreamAlphaScaledExpansion(expansion)
                        : aboutToShowBouncerProgress(expansion + 0.03f));
    }

    private float getTranslationY(int position, float expansion) {
        final float fraction = Interpolators.LINEAR_OUT_SLOW_IN.getInterpolation(
                position == POSITION_TOP ? getDreamYPositionScaledExpansion(expansion)
                        : aboutToShowBouncerProgress(expansion + 0.03f));
        return MathUtils.lerp(-mDreamOverlayMaxTranslationY, 0, fraction);
    }

    /**
     * Handle the dream waking up.
     */
    public void onWakeUp() {
        // TODO(b/361872929): clean up this bool as it doesn't do anything anymore
        // When swiping causes wakeup, do not run any animations as the dream should exit as soon
        // as possible.
        if (mWakingUpFromSwipe) {
            return;
        }

        mDreamOverlayAnimationsController.onWakeUp();
    }

    @Override
    public Animator onBeforeEnterLowLight() {
        // Return the animator so that the transition coordinator waits for the overlay exit
        // animations to finish before entering low light, as otherwise the default DreamActivity
        // animation plays immediately and there's no time for this animation to play.
        return mDreamOverlayAnimationsController.startExitAnimations();
    }
}
