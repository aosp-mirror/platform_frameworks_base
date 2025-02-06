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

package com.android.systemui.lowlightclock;

import android.animation.Animator;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.view.View;
import android.view.animation.Interpolator;

import com.android.app.animation.Interpolators;
import com.android.dream.lowlight.util.TruncatedInterpolator;
import com.android.systemui.lowlightclock.dagger.LowLightModule;
import com.android.systemui.statusbar.CrossFadeHelper;

import javax.inject.Inject;
import javax.inject.Named;

/***
 * A class that provides the animations used by the low-light clock.
 *
 * The entry and exit animations are opposites, with the only difference being a delay before the
 * text fades in on entry.
 */
public class LowLightClockAnimationProvider {
    private final int mYTranslationAnimationInStartOffset;
    private final long mYTranslationAnimationInDurationMillis;
    private final long mAlphaAnimationInStartDelayMillis;
    private final long mAlphaAnimationDurationMillis;

    /**
     * Custom interpolator used for the translate out animation, which uses an emphasized easing
     * like the translate in animation, but is scaled to match the length of the alpha animation.
     */
    private final Interpolator mTranslationOutInterpolator;

    @Inject
    public LowLightClockAnimationProvider(
            @Named(LowLightModule.Y_TRANSLATION_ANIMATION_OFFSET)
                    int yTranslationAnimationInStartOffset,
            @Named(LowLightModule.Y_TRANSLATION_ANIMATION_DURATION_MILLIS)
                    long yTranslationAnimationInDurationMillis,
            @Named(LowLightModule.ALPHA_ANIMATION_IN_START_DELAY_MILLIS)
                    long alphaAnimationInStartDelayMillis,
            @Named(LowLightModule.ALPHA_ANIMATION_DURATION_MILLIS)
                    long alphaAnimationDurationMillis) {
        mYTranslationAnimationInStartOffset = yTranslationAnimationInStartOffset;
        mYTranslationAnimationInDurationMillis = yTranslationAnimationInDurationMillis;
        mAlphaAnimationInStartDelayMillis = alphaAnimationInStartDelayMillis;
        mAlphaAnimationDurationMillis = alphaAnimationDurationMillis;

        mTranslationOutInterpolator = new TruncatedInterpolator(Interpolators.EMPHASIZED,
                /*originalDuration=*/ mYTranslationAnimationInDurationMillis,
                /*newDuration=*/ mAlphaAnimationDurationMillis);
    }

    /***
     * Provides an animation for when the given views become visible.
     * @param views Any number of views to animate in together.
     */
    public Animator provideAnimationIn(View... views) {
        final AnimatorSet animatorSet = new AnimatorSet();

        for (View view : views) {
            if (view == null) continue;
            // Set the alpha to 0 to start because the alpha animation has a start delay.
            CrossFadeHelper.fadeOut(view, 0f, false);

            final Animator alphaAnimator =
                    ObjectAnimator.ofFloat(view, View.ALPHA, 1f);
            alphaAnimator.setStartDelay(mAlphaAnimationInStartDelayMillis);
            alphaAnimator.setDuration(mAlphaAnimationDurationMillis);
            alphaAnimator.setInterpolator(Interpolators.LINEAR);

            final Animator positionAnimator = ObjectAnimator
                    .ofFloat(view, View.TRANSLATION_Y, mYTranslationAnimationInStartOffset, 0f);
            positionAnimator.setDuration(mYTranslationAnimationInDurationMillis);
            positionAnimator.setInterpolator(Interpolators.EMPHASIZED);

            // The position animator must be started first since the alpha animator has a start
            // delay.
            animatorSet.playTogether(positionAnimator, alphaAnimator);
        }

        return animatorSet;
    }

    /***
     * Provides an animation for when the given views are going out of view.
     * @param views Any number of views to animate out.
     */
    public Animator provideAnimationOut(View... views) {
        final AnimatorSet animatorSet = new AnimatorSet();

        for (View view : views) {
            if (view == null) continue;
            final Animator alphaAnimator =
                    ObjectAnimator.ofFloat(view, View.ALPHA, 0f);
            alphaAnimator.setDuration(mAlphaAnimationDurationMillis);
            alphaAnimator.setInterpolator(Interpolators.LINEAR);

            final Animator positionAnimator = ObjectAnimator
                    .ofFloat(view, View.TRANSLATION_Y, mYTranslationAnimationInStartOffset);
            // Use the same duration as the alpha animation plus our custom interpolator.
            positionAnimator.setDuration(mAlphaAnimationDurationMillis);
            positionAnimator.setInterpolator(mTranslationOutInterpolator);
            animatorSet.playTogether(alphaAnimator, positionAnimator);
        }

        return animatorSet;
    }

}
