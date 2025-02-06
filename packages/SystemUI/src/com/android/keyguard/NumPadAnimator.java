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
package com.android.keyguard;

import android.animation.AnimatorSet;
import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.annotation.Nullable;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.view.ContextThemeWrapper;
import android.widget.TextView;

import androidx.annotation.StyleRes;

import com.android.systemui.Flags;
import com.android.systemui.bouncer.shared.constants.PinBouncerConstants.Animation;
import com.android.systemui.bouncer.shared.constants.PinBouncerConstants.Color;

/**
 * Provides background color and radius animations for key pad buttons.
 */
class NumPadAnimator {
    private ValueAnimator mExpandAnimator;
    private AnimatorSet mExpandAnimatorSet;
    private ValueAnimator mContractAnimator;
    private AnimatorSet mContractAnimatorSet;
    private GradientDrawable mBackground;
    private Drawable mImageButton;
    private TextView mDigitTextView;
    private int mNormalBackgroundColor;
    private int mPressedBackgroundColor;
    private int mTextColorPrimary;
    private int mTextColorPressed;
    private int mStyle;
    private float mStartRadius;
    private float mEndRadius;
    private int mHeight;
    private int mWidth;

    NumPadAnimator(Context context, final Drawable drawable,
            @StyleRes int style, Drawable buttonImage) {
        this(context, drawable, style, null, buttonImage);
    }

    NumPadAnimator(Context context, final Drawable drawable, @StyleRes int style,
            @Nullable TextView digitTextView, @Nullable Drawable buttonImage) {
        mStyle = style;
        mBackground = (GradientDrawable) drawable;
        mDigitTextView = digitTextView;
        mImageButton = buttonImage;

        reloadColors(context);
    }

    public void expand() {
        mExpandAnimatorSet.cancel();
        mContractAnimatorSet.cancel();
        mExpandAnimatorSet.start();
    }

    public void contract() {
        mExpandAnimatorSet.cancel();
        mContractAnimatorSet.cancel();
        mContractAnimatorSet.start();
    }

    public void setProgress(float progress) {
        mBackground.setCornerRadius(mEndRadius + (mStartRadius - mEndRadius) * progress);
        int height = (int) (mHeight * 0.7f + mHeight * 0.3 * progress);
        int difference = mHeight - height;

        int left = 0;
        int top = difference / 2;
        int right = mWidth;
        int bottom = mHeight - difference / 2;
        mBackground.setBounds(left, top, right, bottom);
    }

    void onLayout(int width, int height) {
        boolean shouldUpdateHeight = height != mHeight;
        mWidth = width;
        mHeight = height;
        mStartRadius = height / 2f;
        mEndRadius = height / 4f;
        mExpandAnimator.setFloatValues(mStartRadius, mEndRadius);
        mContractAnimator.setFloatValues(mEndRadius, mStartRadius);
        // Set initial corner radius.
        if (shouldUpdateHeight) {
            mBackground.setCornerRadius(mStartRadius);
        }
    }

    /**
     * Reload colors from resources.
     **/
    void reloadColors(Context context) {
        boolean isNumPadKey = mImageButton == null;

        if (!Flags.bouncerUiRevamp2()) {
            int[] customAttrs = {android.R.attr.colorControlNormal};
            ContextThemeWrapper ctw = new ContextThemeWrapper(context, mStyle);
            @SuppressLint("ResourceType") TypedArray a = ctw.obtainStyledAttributes(customAttrs);

            mNormalBackgroundColor = a.getColor(0, context.getColor(Color.digitBg));

            a.recycle();
        } else {
            mNormalBackgroundColor = context.getColor(isNumPadKey ? Color.digitBg : Color.actionBg);
        }

        mPressedBackgroundColor = context.getColor(Color.bgPressed);
        mTextColorPressed = context.getColor(Color.digitPressed);

        mBackground.setColor(mNormalBackgroundColor);
        mTextColorPrimary = context.getColor(isNumPadKey ? Color.digit : Color.action);
        createAnimators();
    }

    private void createAnimators() {
        // Actual values will be updated later, usually during an onLayout() call
        mExpandAnimator = ValueAnimator.ofFloat(0f, 1f);
        mExpandAnimator.setDuration(Animation.expansionDuration);
        mExpandAnimator.setInterpolator(Animation.expansionInterpolator);
        mExpandAnimator.addUpdateListener(
                anim -> mBackground.setCornerRadius((float) anim.getAnimatedValue()));

        ValueAnimator expandBackgroundColorAnimator = ValueAnimator.ofObject(new ArgbEvaluator(),
                mNormalBackgroundColor, mPressedBackgroundColor);
        expandBackgroundColorAnimator.setDuration(Animation.expansionColorDuration);
        expandBackgroundColorAnimator.setInterpolator(Animation.expansionInterpolator);
        expandBackgroundColorAnimator.addUpdateListener(
                animator -> mBackground.setColor((int) animator.getAnimatedValue()));

        ValueAnimator expandTextColorAnimator =
                ValueAnimator.ofObject(new ArgbEvaluator(),
                mTextColorPrimary, mTextColorPressed);
        expandTextColorAnimator.setInterpolator(Animation.expansionInterpolator);
        expandTextColorAnimator.setDuration(Animation.expansionColorDuration);
        expandTextColorAnimator.addUpdateListener(valueAnimator -> {
            if (mDigitTextView != null) {
                mDigitTextView.setTextColor((int) valueAnimator.getAnimatedValue());
            }
            if (mImageButton != null) {
                mImageButton.setTint((int) valueAnimator.getAnimatedValue());
            }
        });

        mExpandAnimatorSet = new AnimatorSet();
        mExpandAnimatorSet.playTogether(mExpandAnimator,
                expandBackgroundColorAnimator, expandTextColorAnimator);

        mContractAnimator = ValueAnimator.ofFloat(1f, 0f);
        mContractAnimator.setStartDelay(Animation.contractionStartDelay);
        mContractAnimator.setDuration(Animation.contractionDuration);
        mContractAnimator.setInterpolator(Animation.contractionRadiusInterpolator);
        mContractAnimator.addUpdateListener(
                anim -> mBackground.setCornerRadius((float) anim.getAnimatedValue()));
        ValueAnimator contractBackgroundColorAnimator = ValueAnimator.ofObject(new ArgbEvaluator(),
                mPressedBackgroundColor, mNormalBackgroundColor);
        contractBackgroundColorAnimator.setInterpolator(Animation.contractionColorInterpolator);
        contractBackgroundColorAnimator.setStartDelay(Animation.contractionStartDelay);
        contractBackgroundColorAnimator.setDuration(Animation.contractionDuration);
        contractBackgroundColorAnimator.addUpdateListener(
                animator -> mBackground.setColor((int) animator.getAnimatedValue()));

        ValueAnimator contractTextColorAnimator =
                ValueAnimator.ofObject(new ArgbEvaluator(), mTextColorPressed,
                mTextColorPrimary);
        contractTextColorAnimator.setInterpolator(Animation.contractionColorInterpolator);
        contractTextColorAnimator.setStartDelay(Animation.contractionStartDelay);
        contractTextColorAnimator.setDuration(Animation.contractionDuration);
        contractTextColorAnimator.addUpdateListener(valueAnimator -> {
            if (mDigitTextView != null) {
                mDigitTextView.setTextColor((int) valueAnimator.getAnimatedValue());
            }
            if (mImageButton != null) {
                mImageButton.setTint((int) valueAnimator.getAnimatedValue());
            }
        });

        mContractAnimatorSet = new AnimatorSet();
        mContractAnimatorSet.playTogether(mContractAnimator,
                contractBackgroundColorAnimator, contractTextColorAnimator);
    }
}

