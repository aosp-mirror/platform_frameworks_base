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

package com.android.keyguard;

import static androidx.constraintlayout.widget.ConstraintSet.END;
import static androidx.constraintlayout.widget.ConstraintSet.PARENT_ID;

import static com.android.internal.jank.InteractionJankMonitor.CUJ_LOCKSCREEN_CLOCK_MOVE_ANIMATION;

import android.animation.Animator;
import android.animation.ValueAnimator;
import android.annotation.Nullable;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.transition.ChangeBounds;
import android.transition.Transition;
import android.transition.TransitionListenerAdapter;
import android.transition.TransitionManager;
import android.transition.TransitionSet;
import android.transition.TransitionValues;
import android.util.Slog;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.annotation.VisibleForTesting;
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.constraintlayout.widget.ConstraintSet;

import com.android.app.animation.Interpolators;
import com.android.internal.jank.InteractionJankMonitor;
import com.android.keyguard.KeyguardClockSwitch.ClockSize;
import com.android.keyguard.logging.KeyguardLogger;
import com.android.systemui.R;
import com.android.systemui.flags.FeatureFlags;
import com.android.systemui.plugins.ClockController;
import com.android.systemui.statusbar.notification.AnimatableProperty;
import com.android.systemui.statusbar.notification.PropertyAnimator;
import com.android.systemui.statusbar.notification.stack.AnimationProperties;
import com.android.systemui.statusbar.notification.stack.StackStateAnimator;
import com.android.systemui.statusbar.phone.DozeParameters;
import com.android.systemui.statusbar.phone.ScreenOffAnimationController;
import com.android.systemui.statusbar.policy.ConfigurationController;
import com.android.systemui.statusbar.policy.KeyguardStateController;
import com.android.systemui.util.ViewController;

import javax.inject.Inject;

/**
 * Injectable controller for {@link KeyguardStatusView}.
 */
public class KeyguardStatusViewController extends ViewController<KeyguardStatusView> {
    private static final boolean DEBUG = KeyguardConstants.DEBUG;
    private static final String TAG = "KeyguardStatusViewController";

    /**
     * Duration to use for the animator when the keyguard status view alignment changes, and a
     * custom clock animation is in use.
     */
    private static final int KEYGUARD_STATUS_VIEW_CUSTOM_CLOCK_MOVE_DURATION = 1000;

    public static final AnimationProperties CLOCK_ANIMATION_PROPERTIES =
            new AnimationProperties().setDuration(StackStateAnimator.ANIMATION_DURATION_STANDARD);

    private final KeyguardSliceViewController mKeyguardSliceViewController;
    private final KeyguardClockSwitchController mKeyguardClockSwitchController;
    private final KeyguardUpdateMonitor mKeyguardUpdateMonitor;
    private final ConfigurationController mConfigurationController;
    private final KeyguardVisibilityHelper mKeyguardVisibilityHelper;
    private final FeatureFlags mFeatureFlags;
    private final InteractionJankMonitor mInteractionJankMonitor;
    private final Rect mClipBounds = new Rect();

    private Boolean mStatusViewCentered = true;

    private final TransitionListenerAdapter mKeyguardStatusAlignmentTransitionListener =
            new TransitionListenerAdapter() {
                @Override
                public void onTransitionCancel(Transition transition) {
                    mInteractionJankMonitor.cancel(CUJ_LOCKSCREEN_CLOCK_MOVE_ANIMATION);
                }

                @Override
                public void onTransitionEnd(Transition transition) {
                    mInteractionJankMonitor.end(CUJ_LOCKSCREEN_CLOCK_MOVE_ANIMATION);
                }
            };

    @Inject
    public KeyguardStatusViewController(
            KeyguardStatusView keyguardStatusView,
            KeyguardSliceViewController keyguardSliceViewController,
            KeyguardClockSwitchController keyguardClockSwitchController,
            KeyguardStateController keyguardStateController,
            KeyguardUpdateMonitor keyguardUpdateMonitor,
            ConfigurationController configurationController,
            DozeParameters dozeParameters,
            ScreenOffAnimationController screenOffAnimationController,
            KeyguardLogger logger,
            FeatureFlags featureFlags,
            InteractionJankMonitor interactionJankMonitor) {
        super(keyguardStatusView);
        mKeyguardSliceViewController = keyguardSliceViewController;
        mKeyguardClockSwitchController = keyguardClockSwitchController;
        mKeyguardUpdateMonitor = keyguardUpdateMonitor;
        mConfigurationController = configurationController;
        mKeyguardVisibilityHelper = new KeyguardVisibilityHelper(mView, keyguardStateController,
                dozeParameters, screenOffAnimationController, /* animateYPos= */ true,
                logger.getBuffer());
        mInteractionJankMonitor = interactionJankMonitor;
        mFeatureFlags = featureFlags;
    }

    @Override
    public void onInit() {
        mKeyguardClockSwitchController.init();
    }

    @Override
    protected void onViewAttached() {
        mKeyguardUpdateMonitor.registerCallback(mInfoCallback);
        mConfigurationController.addCallback(mConfigurationListener);
    }

    @Override
    protected void onViewDetached() {
        mKeyguardUpdateMonitor.removeCallback(mInfoCallback);
        mConfigurationController.removeCallback(mConfigurationListener);
    }

    /**
     * Updates views on doze time tick.
     */
    public void dozeTimeTick() {
        refreshTime();
        mKeyguardSliceViewController.refresh();
    }

    /**
     * Set which clock should be displayed on the keyguard. The other one will be automatically
     * hidden.
     */
    public void displayClock(@ClockSize int clockSize, boolean animate) {
        mKeyguardClockSwitchController.displayClock(clockSize, animate);
    }

    /**
     * Performs fold to aod animation of the clocks (changes font weight from bold to thin).
     * This animation is played when AOD is enabled and foldable device is fully folded, it is
     * displayed on the outer screen
     * @param foldFraction current fraction of fold animation complete
     */
    public void animateFoldToAod(float foldFraction) {
        mKeyguardClockSwitchController.animateFoldToAod(foldFraction);
    }

    /**
     * Sets a translationY on the views on the keyguard, except on the media view.
     */
    public void setTranslationY(float translationY, boolean excludeMedia) {
        mView.setChildrenTranslationY(translationY, excludeMedia);
    }

    /**
     * Set keyguard status view alpha.
     */
    public void setAlpha(float alpha) {
        if (!mKeyguardVisibilityHelper.isVisibilityAnimating()) {
            mView.setAlpha(alpha);
        }
    }

    /**
     * Update the pivot position based on the parent view
     */
    public void updatePivot(float parentWidth, float parentHeight) {
        mView.setPivotX(parentWidth / 2f);
        mView.setPivotY(mKeyguardClockSwitchController.getClockHeight() / 2f);
    }

    /**
     * Get the height of the keyguard status view without the notification icon area, as that's
     * only visible on AOD.
     */
    public int getLockscreenHeight() {
        return mView.getHeight() - mKeyguardClockSwitchController.getNotificationIconAreaHeight();
    }

    /**
     * Get y-bottom position of the currently visible clock.
     */
    public int getClockBottom(int statusBarHeaderHeight) {
        return mKeyguardClockSwitchController.getClockBottom(statusBarHeaderHeight);
    }

    /**
     * @return true if the currently displayed clock is top aligned (as opposed to center aligned)
     */
    public boolean isClockTopAligned() {
        return mKeyguardClockSwitchController.isClockTopAligned();
    }

    /**
     * Pass top margin from ClockPositionAlgorithm in NotificationPanelViewController
     * Use for clock view in LS to compensate for top margin to align to the screen
     * Regardless of translation from AOD and unlock gestures
     */
    public void setLockscreenClockY(int clockY) {
        mKeyguardClockSwitchController.setLockscreenClockY(clockY);
    }

    /**
     * Set whether the view accessibility importance mode.
     */
    public void setStatusAccessibilityImportance(int mode) {
        mView.setImportantForAccessibility(mode);
    }

    @VisibleForTesting
    void setProperty(AnimatableProperty property, float value, boolean animate) {
        PropertyAnimator.setProperty(mView, property, value, CLOCK_ANIMATION_PROPERTIES, animate);
    }

    /**
     * Update position of the view with an optional animation
     */
    public void updatePosition(int x, int y, float scale, boolean animate) {
        setProperty(AnimatableProperty.Y, y, animate);

        ClockController clock = mKeyguardClockSwitchController.getClock();
        if (clock != null && clock.getConfig().getUseAlternateSmartspaceAODTransition()) {
            // If requested, scale the entire view instead of just the clock view
            mKeyguardClockSwitchController.updatePosition(x, 1f /* scale */,
                    CLOCK_ANIMATION_PROPERTIES, animate);
            setProperty(AnimatableProperty.SCALE_X, scale, animate);
            setProperty(AnimatableProperty.SCALE_Y, scale, animate);
        } else {
            mKeyguardClockSwitchController.updatePosition(x, scale,
                    CLOCK_ANIMATION_PROPERTIES, animate);
            setProperty(AnimatableProperty.SCALE_X, 1f, animate);
            setProperty(AnimatableProperty.SCALE_Y, 1f, animate);
        }
    }

    /**
     * Set the visibility of the keyguard status view based on some new state.
     */
    public void setKeyguardStatusViewVisibility(
            int statusBarState,
            boolean keyguardFadingAway,
            boolean goingToFullShade,
            int oldStatusBarState) {
        mKeyguardVisibilityHelper.setViewVisibility(
                statusBarState, keyguardFadingAway, goingToFullShade, oldStatusBarState);
    }

    private void refreshTime() {
        mKeyguardClockSwitchController.refresh();
    }

    private final ConfigurationController.ConfigurationListener mConfigurationListener =
            new ConfigurationController.ConfigurationListener() {
        @Override
        public void onLocaleListChanged() {
            refreshTime();
            mKeyguardClockSwitchController.onLocaleListChanged();
        }

        @Override
        public void onConfigChanged(Configuration newConfig) {
            mKeyguardClockSwitchController.onConfigChanged();
        }
    };

    private KeyguardUpdateMonitorCallback mInfoCallback = new KeyguardUpdateMonitorCallback() {
        @Override
        public void onTimeChanged() {
            refreshTime();
        }

        @Override
        public void onKeyguardVisibilityChanged(boolean visible) {
            if (visible) {
                if (DEBUG) Slog.v(TAG, "refresh statusview visible:true");
                refreshTime();
            }
        }
    };

    /**
     * Rect that specifies how KSV should be clipped, on its parent's coordinates.
     */
    public void setClipBounds(Rect clipBounds) {
        if (clipBounds != null) {
            mClipBounds.set(clipBounds.left, (int) (clipBounds.top - mView.getY()),
                    clipBounds.right, (int) (clipBounds.bottom - mView.getY()));
            mView.setClipBounds(mClipBounds);
        } else {
            mView.setClipBounds(null);
        }
    }

    /**
     * Returns true if the large clock will block the notification shelf in AOD
     */
    public boolean isLargeClockBlockingNotificationShelf() {
        ClockController clock = mKeyguardClockSwitchController.getClock();
        return clock != null && clock.getLargeClock().getConfig().getHasCustomWeatherDataDisplay();
    }

    /**
     * Updates the alignment of the KeyguardStatusView and animates the transition if requested.
     */
    public void updateAlignment(
            ConstraintLayout notifContainerParent,
            boolean splitShadeEnabled,
            boolean shouldBeCentered,
            boolean animate) {
        mKeyguardClockSwitchController.setSplitShadeCentered(splitShadeEnabled && shouldBeCentered);
        if (mStatusViewCentered == shouldBeCentered) {
            return;
        }

        mStatusViewCentered = shouldBeCentered;
        if (notifContainerParent == null) {
            return;
        }

        ConstraintSet constraintSet = new ConstraintSet();
        constraintSet.clone(notifContainerParent);
        int statusConstraint = shouldBeCentered ? PARENT_ID : R.id.qs_edge_guideline;
        constraintSet.connect(R.id.keyguard_status_view, END, statusConstraint, END);
        if (!animate) {
            constraintSet.applyTo(notifContainerParent);
            return;
        }

        mInteractionJankMonitor.begin(mView, CUJ_LOCKSCREEN_CLOCK_MOVE_ANIMATION);
        ChangeBounds transition = new ChangeBounds();
        if (splitShadeEnabled) {
            // Excluding media from the transition on split-shade, as it doesn't transition
            // horizontally properly.
            transition.excludeTarget(R.id.status_view_media_container, true);
        }

        transition.setInterpolator(Interpolators.FAST_OUT_SLOW_IN);
        transition.setDuration(StackStateAnimator.ANIMATION_DURATION_STANDARD);

        ClockController clock = mKeyguardClockSwitchController.getClock();
        boolean customClockAnimation = clock != null
                && clock.getLargeClock().getConfig().getHasCustomPositionUpdatedAnimation();

        if (customClockAnimation) {
            // Find the clock, so we can exclude it from this transition.
            FrameLayout clockContainerView = mView.findViewById(R.id.lockscreen_clock_view_large);

            // The clock container can sometimes be null. If it is, just fall back to the
            // old animation rather than setting up the custom animations.
            if (clockContainerView == null || clockContainerView.getChildCount() == 0) {
                transition.addListener(mKeyguardStatusAlignmentTransitionListener);
                TransitionManager.beginDelayedTransition(notifContainerParent, transition);
            } else {
                View clockView = clockContainerView.getChildAt(0);

                TransitionSet set = new TransitionSet();
                set.addTransition(transition);

                SplitShadeTransitionAdapter adapter =
                        new SplitShadeTransitionAdapter(mKeyguardClockSwitchController);

                // Use linear here, so the actual clock can pick its own interpolator.
                adapter.setInterpolator(Interpolators.LINEAR);
                adapter.setDuration(KEYGUARD_STATUS_VIEW_CUSTOM_CLOCK_MOVE_DURATION);
                adapter.addTarget(clockView);
                set.addTransition(adapter);
                set.addListener(mKeyguardStatusAlignmentTransitionListener);
                TransitionManager.beginDelayedTransition(notifContainerParent, set);
            }
        } else {
            transition.addListener(mKeyguardStatusAlignmentTransitionListener);
            TransitionManager.beginDelayedTransition(notifContainerParent, transition);
        }

        constraintSet.applyTo(notifContainerParent);
    }

    @VisibleForTesting
    static class SplitShadeTransitionAdapter extends Transition {
        private static final String PROP_BOUNDS_LEFT = "splitShadeTransitionAdapter:boundsLeft";
        private static final String PROP_BOUNDS_RIGHT = "splitShadeTransitionAdapter:boundsRight";
        private static final String PROP_X_IN_WINDOW = "splitShadeTransitionAdapter:xInWindow";
        private static final String[] TRANSITION_PROPERTIES = {
                PROP_BOUNDS_LEFT, PROP_BOUNDS_RIGHT, PROP_X_IN_WINDOW};

        private final KeyguardClockSwitchController mController;

        @VisibleForTesting
        SplitShadeTransitionAdapter(KeyguardClockSwitchController controller) {
            mController = controller;
        }

        private void captureValues(TransitionValues transitionValues) {
            transitionValues.values.put(PROP_BOUNDS_LEFT, transitionValues.view.getLeft());
            transitionValues.values.put(PROP_BOUNDS_RIGHT, transitionValues.view.getRight());
            int[] locationInWindowTmp = new int[2];
            transitionValues.view.getLocationInWindow(locationInWindowTmp);
            transitionValues.values.put(PROP_X_IN_WINDOW, locationInWindowTmp[0]);
        }

        @Override
        public void captureEndValues(TransitionValues transitionValues) {
            captureValues(transitionValues);
        }

        @Override
        public void captureStartValues(TransitionValues transitionValues) {
            captureValues(transitionValues);
        }

        @Nullable
        @Override
        public Animator createAnimator(ViewGroup sceneRoot, @Nullable TransitionValues startValues,
                @Nullable TransitionValues endValues) {
            if (startValues == null || endValues == null) {
                return null;
            }
            ValueAnimator anim = ValueAnimator.ofFloat(0, 1);

            int fromLeft = (int) startValues.values.get(PROP_BOUNDS_LEFT);
            int fromWindowX = (int) startValues.values.get(PROP_X_IN_WINDOW);
            int toWindowX = (int) endValues.values.get(PROP_X_IN_WINDOW);
            // Using windowX, to determine direction, instead of left, as in RTL the difference of
            // toLeft - fromLeft is always positive, even when moving left.
            int direction = toWindowX - fromWindowX > 0 ? 1 : -1;

            anim.addUpdateListener(animation -> {
                ClockController clock = mController.getClock();
                if (clock == null) {
                    return;
                }

                clock.getLargeClock().getAnimations()
                        .onPositionUpdated(fromLeft, direction, animation.getAnimatedFraction());
            });

            return anim;
        }

        @Override
        public String[] getTransitionProperties() {
            return TRANSITION_PROPERTIES;
        }
    }
}
