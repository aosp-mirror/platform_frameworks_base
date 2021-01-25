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

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.drawable.GradientDrawable;
import android.view.ContextThemeWrapper;
import android.view.ViewGroup;

import androidx.annotation.StyleRes;

import com.android.internal.graphics.ColorUtils;
import com.android.systemui.Interpolators;
import com.android.systemui.R;

/**
 * Provides background color and radius animations for key pad buttons.
 */
class NumPadAnimator {
    private ValueAnimator mAnimator;
    private GradientDrawable mBackground;
    private int mMargin;
    private int mNormalColor;
    private int mHighlightColor;
    private int mStyle;

    NumPadAnimator(Context context, final GradientDrawable background, @StyleRes int style) {
        mBackground = (GradientDrawable) background.mutate();
        mStyle = style;

        reloadColors(context);

        mMargin = context.getResources().getDimensionPixelSize(R.dimen.num_pad_key_margin);

        // Actual values will be updated later, usually during an onLayout() call
        mAnimator = ValueAnimator.ofFloat(0f);
        mAnimator.setDuration(250);
        mAnimator.setInterpolator(Interpolators.LINEAR);
        mAnimator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                public void onAnimationUpdate(ValueAnimator anim) {
                    mBackground.setCornerRadius((float) anim.getAnimatedValue());
                    mBackground.setColor(ColorUtils.blendARGB(mHighlightColor, mNormalColor,
                            anim.getAnimatedFraction()));
                }
        });

    }

    void updateMargin(ViewGroup.MarginLayoutParams lp) {
        lp.setMargins(mMargin, mMargin, mMargin, mMargin);
    }

    void onLayout(int height) {
        float startRadius = height / 10f;
        float endRadius = height / 2f;
        mBackground.setCornerRadius(endRadius);
        mAnimator.setFloatValues(startRadius, endRadius);
    }

    void start() {
        mAnimator.cancel();
        mAnimator.start();
    }

    /**
     * Reload colors from resources.
     **/
    void reloadColors(Context context) {
        int[] customAttrs = {android.R.attr.colorControlNormal,
                android.R.attr.colorControlHighlight};

        ContextThemeWrapper ctw = new ContextThemeWrapper(context, mStyle);
        TypedArray a = ctw.obtainStyledAttributes(customAttrs);
        mNormalColor = a.getColor(0, 0);
        mHighlightColor = a.getColor(1, 0);
        a.recycle();

        mBackground.setColor(mNormalColor);
    }
}

