/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.systemui.accessibility.floatingmenu;

import android.animation.Animator;
import android.animation.ValueAnimator;
import android.util.MathUtils;

import androidx.annotation.NonNull;

import com.android.internal.annotations.VisibleForTesting;

import java.util.Arrays;

/**
 * Manages the animation of the floating menu's radii.
 * <p>
 * There are 8 output values total. There are 4 corners,
 * and each corner has a value for the x and y axes.
 */
class RadiiAnimator {
    static final int RADII_COUNT = 8;

    private float[] mStartValues;
    private float[] mEndValues;
    private final ValueAnimator mAnimationDriver = ValueAnimator.ofFloat(0.0f, 1.0f);

    RadiiAnimator(float[] initialValues, IRadiiAnimationListener animationListener) {
        if (initialValues.length != RADII_COUNT) {
            initialValues = Arrays.copyOf(initialValues, RADII_COUNT);
        }

        mStartValues = initialValues;
        mEndValues = initialValues;

        mAnimationDriver.setRepeatCount(0);
        mAnimationDriver.addUpdateListener(
                animation -> animationListener.onRadiiAnimationUpdate(
                        evaluate(animation.getAnimatedFraction())));
        mAnimationDriver.addListener(new Animator.AnimatorListener() {
            @Override
            public void onAnimationStart(@NonNull Animator animation) {
                animationListener.onRadiiAnimationUpdate(evaluate(/* t = */ 0.0f));
                animationListener.onRadiiAnimationStart();
            }

            @Override
            public void onAnimationEnd(@NonNull Animator animation) {
                animationListener.onRadiiAnimationStop();
            }

            @Override
            public void onAnimationCancel(@NonNull Animator animation) {
                animationListener.onRadiiAnimationUpdate(
                        evaluate(mAnimationDriver.getAnimatedFraction()));
                animationListener.onRadiiAnimationStop();
            }

            @Override
            public void onAnimationRepeat(@NonNull Animator animation) {}
        });
        mAnimationDriver.setInterpolator(new android.view.animation.BounceInterpolator());
    }

    void startAnimation(float[] endValues) {
        if (mAnimationDriver.isStarted()) {
            mAnimationDriver.cancel();
            mStartValues = evaluate(mAnimationDriver.getAnimatedFraction());
        } else {
            mStartValues = mEndValues;
        }
        mEndValues = endValues;

        mAnimationDriver.start();
    }

    void skipAnimationToEnd() {
        mAnimationDriver.end();
    }

    @VisibleForTesting
    float[] evaluate(float time /* interpolator value between 0.0 and 1.0 */) {
        float[] out = new float[8];
        for (int i = 0; i < RADII_COUNT; i++) {
            out[i] = MathUtils.lerp(mStartValues[i], mEndValues[i], time);
        }
        return out;
    }

    boolean isStarted() {
        return mAnimationDriver.isStarted();
    }
}

