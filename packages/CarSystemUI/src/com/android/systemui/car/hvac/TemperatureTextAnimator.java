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

package com.android.systemui.car.hvac;

import static com.android.systemui.car.hvac.AnimatedTemperatureView.isHorizontal;
import static com.android.systemui.car.hvac.AnimatedTemperatureView.isLeft;

import android.annotation.NonNull;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.view.animation.AnimationSet;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.RotateAnimation;
import android.view.animation.TranslateAnimation;
import android.widget.TextSwitcher;

/**
 * Controls animating TemperatureView's text
 */
class TemperatureTextAnimator {

    private static final DecelerateInterpolator DECELERATE_INTERPOLATOR =
            new DecelerateInterpolator();
    private static final AccelerateDecelerateInterpolator ACCELERATE_DECELERATE_INTERPOLATOR =
            new AccelerateDecelerateInterpolator();

    private static final int ROTATION_DEGREES = 15;
    private static final int DURATION_MILLIS = 200;

    private AnimatedTemperatureView mParent;
    private final TextSwitcher mTextSwitcher;
    private final String mTempFormat;
    private final int mPivotOffset;
    private final CharSequence mMinText;
    private final CharSequence mMaxText;

    private Animation mTextInAnimationUp;
    private Animation mTextOutAnimationUp;
    private Animation mTextInAnimationDown;
    private Animation mTextOutAnimationDown;
    private Animation mTextFadeInAnimation;
    private Animation mTextFadeOutAnimation;

    private float mLastTemp = Float.NaN;

    TemperatureTextAnimator(AnimatedTemperatureView parent, TextSwitcher textSwitcher,
            String tempFormat, int pivotOffset,
            CharSequence minText, CharSequence maxText) {
        mParent = parent;
        mTextSwitcher = textSwitcher;
        mTempFormat = tempFormat;
        mPivotOffset = pivotOffset;
        mMinText = minText;
        mMaxText = maxText;

        mParent.addOnLayoutChangeListener(
                (v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) ->
                        setupAnimations(mParent.getGravity()));
    }

    void setTemp(float temp) {
        if (Float.isNaN(temp)) {
            mTextSwitcher.setInAnimation(mTextFadeInAnimation);
            mTextSwitcher.setOutAnimation(mTextFadeOutAnimation);
            mTextSwitcher.setText("--");
            mLastTemp = temp;
            return;
        }
        boolean isMinValue = mParent.isMinValue(temp);
        boolean isMaxValue = mParent.isMaxValue(temp);
        if (Float.isNaN(mLastTemp)) {
            mTextSwitcher.setInAnimation(mTextFadeInAnimation);
            mTextSwitcher.setOutAnimation(mTextFadeOutAnimation);
        } else if (!isMinValue && (isMaxValue || temp > mLastTemp)) {
            mTextSwitcher.setInAnimation(mTextInAnimationUp);
            mTextSwitcher.setOutAnimation(mTextOutAnimationUp);
        } else {
            mTextSwitcher.setInAnimation(mTextInAnimationDown);
            mTextSwitcher.setOutAnimation(mTextOutAnimationDown);
        }
        CharSequence text;
        if (isMinValue) {
            text = mMinText;
        } else if (isMaxValue) {
            text = mMaxText;
        } else {
            text = String.format(mTempFormat, temp);
        }
        mTextSwitcher.setText(text);
        mLastTemp = temp;
    }

    private void setupAnimations(int gravity) {
        mTextFadeInAnimation = createFadeAnimation(true);
        mTextFadeOutAnimation = createFadeAnimation(false);
        if (!isHorizontal(gravity)) {
            mTextInAnimationUp = createTranslateFadeAnimation(true, true);
            mTextOutAnimationUp = createTranslateFadeAnimation(false, true);
            mTextInAnimationDown = createTranslateFadeAnimation(true, false);
            mTextOutAnimationDown = createTranslateFadeAnimation(false, false);
        } else {
            boolean isLeft = isLeft(gravity, mTextSwitcher.getLayoutDirection());
            mTextInAnimationUp = createRotateFadeAnimation(true, isLeft, true);
            mTextOutAnimationUp = createRotateFadeAnimation(false, isLeft, true);
            mTextInAnimationDown = createRotateFadeAnimation(true, isLeft, false);
            mTextOutAnimationDown = createRotateFadeAnimation(false, isLeft, false);
        }
    }

    @NonNull
    private Animation createFadeAnimation(boolean in) {
        AnimationSet set = new AnimationSet(true);
        AlphaAnimation alphaAnimation = new AlphaAnimation(in ? 0 : 1, in ? 1 : 0);
        alphaAnimation.setDuration(DURATION_MILLIS);
        set.addAnimation(new RotateAnimation(0, 0)); // Undo any previous rotation
        set.addAnimation(alphaAnimation);
        return set;
    }

    @NonNull
    private Animation createTranslateFadeAnimation(boolean in, boolean up) {
        AnimationSet set = new AnimationSet(true);
        set.setInterpolator(ACCELERATE_DECELERATE_INTERPOLATOR);
        set.setDuration(DURATION_MILLIS);
        int fromYDelta = in ? (up ? 1 : -1) : 0;
        int toYDelta = in ? 0 : (up ? -1 : 1);
        set.addAnimation(
                new TranslateAnimation(Animation.RELATIVE_TO_SELF, 0, Animation.RELATIVE_TO_SELF, 0,
                        Animation.RELATIVE_TO_SELF, fromYDelta, Animation.RELATIVE_TO_SELF,
                        toYDelta));
        set.addAnimation(new AlphaAnimation(in ? 0 : 1, in ? 1 : 0));
        return set;
    }

    @NonNull
    private Animation createRotateFadeAnimation(boolean in, boolean isLeft, boolean up) {
        AnimationSet set = new AnimationSet(true);
        set.setInterpolator(DECELERATE_INTERPOLATOR);
        set.setDuration(DURATION_MILLIS);

        float degrees = isLeft == up ? -ROTATION_DEGREES : ROTATION_DEGREES;
        int pivotX = isLeft ? -mPivotOffset : mParent.getWidth() + mPivotOffset;
        set.addAnimation(
                new RotateAnimation(in ? -degrees : 0f, in ? 0f : degrees, Animation.ABSOLUTE,
                        pivotX, Animation.ABSOLUTE, 0f));
        set.addAnimation(new AlphaAnimation(in ? 0 : 1, in ? 1 : 0));
        return set;
    }
}
