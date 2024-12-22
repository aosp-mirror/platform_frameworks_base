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
import static com.android.systemui.util.kotlin.JavaAdapterKt.collectFlow;

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

import androidx.annotation.NonNull;
import androidx.annotation.VisibleForTesting;
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.constraintlayout.widget.ConstraintSet;
import androidx.viewpager.widget.ViewPager;

import com.android.app.animation.Interpolators;
import com.android.internal.jank.InteractionJankMonitor;
import com.android.keyguard.KeyguardClockSwitch.ClockSize;
import com.android.keyguard.logging.KeyguardLogger;
import com.android.systemui.animation.ViewHierarchyAnimator;
import com.android.systemui.keyguard.MigrateClocksToBlueprint;
import com.android.systemui.keyguard.domain.interactor.KeyguardInteractor;
import com.android.systemui.plugins.clocks.ClockController;
import com.android.systemui.power.domain.interactor.PowerInteractor;
import com.android.systemui.power.shared.model.ScreenPowerState;
import com.android.systemui.res.R;
import com.android.systemui.statusbar.notification.AnimatableProperty;
import com.android.systemui.statusbar.notification.PropertyAnimator;
import com.android.systemui.statusbar.notification.stack.AnimationProperties;
import com.android.systemui.statusbar.notification.stack.StackStateAnimator;
import com.android.systemui.statusbar.phone.DozeParameters;
import com.android.systemui.statusbar.phone.ScreenOffAnimationController;
import com.android.systemui.statusbar.policy.ConfigurationController;
import com.android.systemui.statusbar.policy.KeyguardStateController;
import com.android.systemui.util.ViewController;

import kotlin.coroutines.CoroutineContext;
import kotlin.coroutines.EmptyCoroutineContext;

import javax.inject.Inject;

/**
 * Injectable controller for {@link KeyguardStatusView}.
 */
public class KeyguardStatusViewController extends ViewController<KeyguardStatusView> {
    private static final boolean DEBUG = KeyguardConstants.DEBUG;
    @VisibleForTesting static final String TAG = "KeyguardStatusViewController";
    private static final long STATUS_AREA_HEIGHT_ANIMATION_MILLIS = 133;

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
    private final InteractionJankMonitor mInteractionJankMonitor;
    private final Rect mClipBounds = new Rect();
    private final KeyguardInteractor mKeyguardInteractor;
    private final PowerInteractor mPowerInteractor;
    private final DozeParameters mDozeParameters;

    private View mStatusArea = null;
    private ValueAnimator mStatusAreaHeightAnimator = null;

    private Boolean mSplitShadeEnabled = false;
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

    private final View.OnLayoutChangeListener mStatusAreaLayoutChangeListener =
            new View.OnLayoutChangeListener() {
                @Override
                public void onLayoutChange(View v,
                        int left, int top, int right, int bottom,
                        int oldLeft, int oldTop, int oldRight, int oldBottom
                ) {
                    if (!mDozeParameters.getAlwaysOn()) {
                        return;
                    }

                    int oldHeight = oldBottom - oldTop;
                    int diff = v.getHeight() - oldHeight;
                    if (diff == 0) {
                        return;
                    }

                    int startValue = -1 * diff;
                    long duration = STATUS_AREA_HEIGHT_ANIMATION_MILLIS;
                    if (mStatusAreaHeightAnimator != null
                            && mStatusAreaHeightAnimator.isRunning()) {
                        duration += mStatusAreaHeightAnimator.getDuration()
                                - mStatusAreaHeightAnimator.getCurrentPlayTime();
                        startValue += (int) mStatusAreaHeightAnimator.getAnimatedValue();
                        mStatusAreaHeightAnimator.cancel();
                        mStatusAreaHeightAnimator = null;
                    }

                    mStatusAreaHeightAnimator = ValueAnimator.ofInt(startValue, 0);
                    mStatusAreaHeightAnimator.setDuration(duration);
                    final View nic = mKeyguardClockSwitchController.getAodNotifIconContainer();
                    if (nic != null) {
                        mStatusAreaHeightAnimator.addUpdateListener(anim -> {
                            nic.setTranslationY((int) anim.getAnimatedValue());
                        });
                    }
                    mStatusAreaHeightAnimator.start();
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
            InteractionJankMonitor interactionJankMonitor,
            KeyguardInteractor keyguardInteractor,
            PowerInteractor powerInteractor) {
        super(keyguardStatusView);
        mKeyguardSliceViewController = keyguardSliceViewController;
        mKeyguardClockSwitchController = keyguardClockSwitchController;
        mKeyguardUpdateMonitor = keyguardUpdateMonitor;
        mConfigurationController = configurationController;
        mDozeParameters = dozeParameters;
        mKeyguardVisibilityHelper = new KeyguardVisibilityHelper(mView, keyguardStateController,
                dozeParameters, screenOffAnimationController, /* animateYPos= */ true,
                logger.getBuffer());
        mInteractionJankMonitor = interactionJankMonitor;
        mKeyguardInteractor = keyguardInteractor;
        mPowerInteractor = powerInteractor;
    }

    @Override
    public void onInit() {
        mKeyguardClockSwitchController.init();
        final View mediaHostContainer = mView.findViewById(R.id.status_view_media_container);
        if (mediaHostContainer != null) {
            mKeyguardClockSwitchController.getView().addOnLayoutChangeListener(
                    (v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
                        if (!mSplitShadeEnabled
                                || mKeyguardClockSwitchController.getView().getSplitShadeCentered()
                                // Note: isKeyguardVisible() returns false after Launcher -> AOD.
                                || !mKeyguardUpdateMonitor.isKeyguardVisible()) {
                            return;
                        }

                        int oldHeight = oldBottom - oldTop;
                        if (v.getHeight() == oldHeight) return;

                        if (mediaHostContainer.getVisibility() != View.VISIBLE
                                // If the media is appearing, also don't do the transition.
                                || mediaHostContainer.getHeight() == 0) {
                            return;
                        }

                        ViewHierarchyAnimator.Companion.animateNextUpdate(mediaHostContainer,
                                Interpolators.STANDARD, /* duration= */ 500L,
                                /* animateChildren= */ false);
                    });
        }

        if (MigrateClocksToBlueprint.isEnabled()) {
            startCoroutines(EmptyCoroutineContext.INSTANCE);
            mView.setVisibility(View.GONE);
        }
    }

    void startCoroutines(CoroutineContext context) {
        collectFlow(mView, mKeyguardInteractor.getDozeTimeTick(),
                (Long millis) -> {
                        dozeTimeTick();
                }, context);

        collectFlow(mView, mPowerInteractor.getScreenPowerState(),
                (ScreenPowerState powerState) -> {
                    if (powerState == ScreenPowerState.SCREEN_TURNING_ON) {
                        dozeTimeTick();
                    }
                }, context);
    }

    public KeyguardStatusView getView() {
        return mView;
    }

    @Override
    protected void onViewAttached() {
        mStatusArea = mView.findViewById(R.id.keyguard_status_area);
        if (MigrateClocksToBlueprint.isEnabled()) {
            return;
        }

        mStatusArea.addOnLayoutChangeListener(mStatusAreaLayoutChangeListener);
        mKeyguardUpdateMonitor.registerCallback(mInfoCallback);
        mConfigurationController.addCallback(mConfigurationListener);
    }

    @Override
    protected void onViewDetached() {
        if (MigrateClocksToBlueprint.isEnabled()) {
            return;
        }

        mStatusArea.removeOnLayoutChangeListener(mStatusAreaLayoutChangeListener);
        mKeyguardUpdateMonitor.removeCallback(mInfoCallback);
        mConfigurationController.removeCallback(mConfigurationListener);
    }

    /** Sets the StatusView as shown on an external display. */
    public void setDisplayedOnSecondaryDisplay() {
        mKeyguardClockSwitchController.setShownOnSecondaryDisplay(true);
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
     *
     * We internally animate height changes to the status area to prevent discontinuities in the
     * doze animation introduced by the height suddenly changing due to smartpace.
     */
    public int getLockscreenHeight() {
        int heightAnimValue = mStatusAreaHeightAnimator == null ? 0 :
                (int) mStatusAreaHeightAnimator.getAnimatedValue();
        return mView.getHeight() + heightAnimValue
                - mKeyguardClockSwitchController.getNotificationIconAreaHeight();
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
            Slog.v(TAG, "onTimeChanged");
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
     * Set if the split shade is enabled
     */
    public void setSplitShadeEnabled(boolean enabled) {
        mKeyguardClockSwitchController.setSplitShadeEnabled(enabled);
        mSplitShadeEnabled = enabled;
    }

    /**
     * Updates the alignment of the KeyguardStatusView and animates the transition if requested.
     */
    public void updateAlignment(
            ConstraintLayout layout,
            boolean splitShadeEnabled,
            boolean shouldBeCentered,
            boolean animate) {
        if (MigrateClocksToBlueprint.isEnabled()) {
            mKeyguardInteractor.setClockShouldBeCentered(shouldBeCentered);
        } else {
            mKeyguardClockSwitchController.setSplitShadeCentered(
                    splitShadeEnabled && shouldBeCentered);
        }
        if (mStatusViewCentered == shouldBeCentered) {
            return;
        }

        mStatusViewCentered = shouldBeCentered;
        if (layout == null) {
            return;
        }

        ConstraintSet constraintSet = new ConstraintSet();
        constraintSet.clone(layout);
        int guideline;
        if (MigrateClocksToBlueprint.isEnabled()) {
            guideline = R.id.split_shade_guideline;
        } else {
            guideline = R.id.qs_edge_guideline;
        }

        int statusConstraint = shouldBeCentered ? PARENT_ID : guideline;
        constraintSet.connect(R.id.keyguard_status_view, END, statusConstraint, END);
        if (!animate) {
            constraintSet.applyTo(layout);
            return;
        }

        mInteractionJankMonitor.begin(mView, CUJ_LOCKSCREEN_CLOCK_MOVE_ANIMATION);
        /* This transition blocks any layout changes while running. For that reason
        * special logic with setting visibility was added to {@link BcSmartspaceView#setDozing}
        * for split shade to avoid jump of the media object. */
        ChangeBounds transition = new ChangeBounds();
        if (splitShadeEnabled) {
            // Excluding media from the transition on split-shade, as it doesn't transition
            // horizontally properly.
            transition.excludeTarget(R.id.status_view_media_container, true);

            // Exclude smartspace viewpager and its children from the transition.
            //     - Each step of the transition causes the ViewPager to invoke resize,
            //     which invokes scrolling to the recalculated position. The scrolling
            //     actions are congested, resulting in kinky translation, and
            //     delay in settling to the final position. (http://b/281620564#comment1)
            //     - Also, the scrolling is unnecessary in the transition. We just want
            //     the viewpager to stay on the same page.
            //     - Exclude by Class type instead of resource id, since the resource id
            //     isn't available for all devices, and probably better to exclude all
            //     ViewPagers any way.
            transition.excludeTarget(ViewPager.class, true);
            transition.excludeChildren(ViewPager.class, true);
        }

        transition.setInterpolator(Interpolators.FAST_OUT_SLOW_IN);
        transition.setDuration(StackStateAnimator.ANIMATION_DURATION_STANDARD);

        ClockController clock = mKeyguardClockSwitchController.getClock();
        boolean customClockAnimation = clock != null
                && clock.getLargeClock().getConfig().getHasCustomPositionUpdatedAnimation();
        // When migrateClocksToBlueprint is on, customized clock animation is conducted in
        // KeyguardClockViewBinder
        if (customClockAnimation && !MigrateClocksToBlueprint.isEnabled()) {
            // Find the clock, so we can exclude it from this transition.
            FrameLayout clockContainerView = mView.findViewById(R.id.lockscreen_clock_view_large);

            // The clock container can sometimes be null. If it is, just fall back to the
            // old animation rather than setting up the custom animations.
            if (clockContainerView == null || clockContainerView.getChildCount() == 0) {
                transition.addListener(mKeyguardStatusAlignmentTransitionListener);
                TransitionManager.beginDelayedTransition(layout, transition);
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

                if (splitShadeEnabled) {
                    // Exclude smartspace viewpager and its children from the transition set.
                    //     - This is necessary in addition to excluding them from the
                    //     ChangeBounds child transition.
                    //     - Without this, the viewpager is scrolled to the new position
                    //     (corresponding to its end size) before the size change is realized.
                    //     Note that the size change is realized at the end of the ChangeBounds
                    //     transition. With the "prescrolling", the viewpager ends up in a weird
                    //     position, then recovers smoothly during the transition, and ends at
                    //     the position for the current page.
                    //     - Exclude by Class type instead of resource id, since the resource id
                    //     isn't available for all devices, and probably better to exclude all
                    //     ViewPagers any way.
                    set.excludeTarget(ViewPager.class, true);
                    set.excludeChildren(ViewPager.class, true);
                }

                set.addListener(mKeyguardStatusAlignmentTransitionListener);
                TransitionManager.beginDelayedTransition(layout, set);
            }
        } else {
            transition.addListener(mKeyguardStatusAlignmentTransitionListener);
            TransitionManager.beginDelayedTransition(layout, transition);
        }

        constraintSet.applyTo(layout);
    }

    public ClockController getClockController() {
        return mKeyguardClockSwitchController.getClock();
    }

    String getInstanceName() {
        return TAG + "#" + hashCode();
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
        public Animator createAnimator(@NonNull ViewGroup sceneRoot,
                @Nullable TransitionValues startValues,
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
