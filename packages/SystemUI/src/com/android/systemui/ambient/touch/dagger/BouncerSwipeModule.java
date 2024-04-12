/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.systemui.ambient.touch.dagger;

import android.animation.ValueAnimator;
import android.content.res.Resources;
import android.util.TypedValue;
import android.view.VelocityTracker;

import com.android.systemui.ambient.touch.BouncerSwipeTouchHandler;
import com.android.systemui.ambient.touch.TouchHandler;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.res.R;
import com.android.systemui.shade.ShadeViewController;
import com.android.wm.shell.animation.FlingAnimationUtils;

import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;

import javax.inject.Named;
import javax.inject.Provider;

/**
 * This module captures the components associated with {@link BouncerSwipeTouchHandler}.
 */
@Module
public class BouncerSwipeModule {
    /**
     * The region, defined as the percentage of the screen, from which a touch gesture to start
     * swiping up to the bouncer can occur.
     */
    public static final String SWIPE_TO_BOUNCER_START_REGION = "swipe_to_bouncer_start_region";

    public static final String MIN_BOUNCER_ZONE_SCREEN_PERCENTAGE =
            "min_bouncer_zone_screen_percentage";

    /**
     * The {@link android.view.animation.AnimationUtils} for animating the bouncer closing.
     */
    public static final String SWIPE_TO_BOUNCER_FLING_ANIMATION_UTILS_CLOSING =
            "swipe_to_bouncer_fling_animation_utils_closing";

    /**
     * The {@link android.view.animation.AnimationUtils} for animating the bouncer opening.
     */
    public static final String SWIPE_TO_BOUNCER_FLING_ANIMATION_UTILS_OPENING =
                    "swipe_to_bouncer_fling_animation_utils_opening";

    /**
     * Provides {@link BouncerSwipeTouchHandler} for inclusion in touch handling over the dream.
     */
    @Provides
    @IntoSet
    public static TouchHandler providesBouncerSwipeTouchHandler(
            BouncerSwipeTouchHandler touchHandler) {
        return touchHandler;
    }

    /**
     * Provides {@link android.view.animation.AnimationUtils} for closing.
     */
    @Provides
    @Named(SWIPE_TO_BOUNCER_FLING_ANIMATION_UTILS_CLOSING)
    public static FlingAnimationUtils providesSwipeToBouncerFlingAnimationUtilsClosing(
            Provider<FlingAnimationUtils.Builder> flingAnimationUtilsBuilderProvider) {
        return flingAnimationUtilsBuilderProvider.get()
                .reset()
                .setMaxLengthSeconds(
                        ShadeViewController.FLING_CLOSING_MAX_LENGTH_SECONDS)
                .setSpeedUpFactor(ShadeViewController.FLING_SPEED_UP_FACTOR)
                .build();
    }

    /**
     * Provides {@link android.view.animation.AnimationUtils} for opening.
     */
    @Provides
    @Named(SWIPE_TO_BOUNCER_FLING_ANIMATION_UTILS_OPENING)
    public static FlingAnimationUtils providesSwipeToBouncerFlingAnimationUtilsOpening(
            Provider<FlingAnimationUtils.Builder> flingAnimationUtilsBuilderProvider) {
        return flingAnimationUtilsBuilderProvider.get()
                .reset()
                .setMaxLengthSeconds(ShadeViewController.FLING_MAX_LENGTH_SECONDS)
                .setSpeedUpFactor(ShadeViewController.FLING_SPEED_UP_FACTOR)
                .build();
    }

    /**
     * Provides the region to start swipe gestures from.
     */
    @Provides
    @Named(SWIPE_TO_BOUNCER_START_REGION)
    public static float providesSwipeToBouncerStartRegion(@Main Resources resources) {
        TypedValue typedValue = new TypedValue();
        resources.getValue(R.dimen.dream_overlay_bouncer_start_region_screen_percentage,
                typedValue, true);
        return typedValue.getFloat();
    }

    /**
     * Provides the minimum region to start wipe gestures from.
     */
    @Provides
    @Named(MIN_BOUNCER_ZONE_SCREEN_PERCENTAGE)
    public static float providesMinBouncerZoneScreenPercentage(@Main Resources resources) {
        TypedValue typedValue = new TypedValue();
        resources.getValue(R.dimen.dream_overlay_bouncer_min_region_screen_percentage,
                typedValue, true);
        return typedValue.getFloat();
    }

    /**
     * Provides the default {@link BouncerSwipeTouchHandler.ValueAnimatorCreator}, which is simply
     * a wrapper around {@link ValueAnimator}.
     */
    @Provides
    public static BouncerSwipeTouchHandler.ValueAnimatorCreator providesValueAnimatorCreator() {
        return (start, finish) -> ValueAnimator.ofFloat(start, finish);
    }

    /**
     * Provides the default {@link BouncerSwipeTouchHandler.VelocityTrackerFactory}. which is a
     * passthrough to {@link android.view.VelocityTracker}.
     */
    @Provides
    public static BouncerSwipeTouchHandler.VelocityTrackerFactory providesVelocityTrackerFactory() {
        return () -> VelocityTracker.obtain();
    }
}
