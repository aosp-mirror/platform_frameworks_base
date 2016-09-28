/*
 * Copyright (C) 2014 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.systemui;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.view.View;

import java.util.ArrayList;

/**
 * Helper to invert the colors of views and fade between the states.
 */
public class ViewInvertHelper {

    private final Paint mDarkPaint = new Paint();
    private final ColorMatrix mMatrix = new ColorMatrix();
    private final ColorMatrix mGrayscaleMatrix = new ColorMatrix();
    private final long mFadeDuration;
    private final boolean mThemeInvert;
    private final ArrayList<View> mTargets = new ArrayList<>();

    public ViewInvertHelper(View v, long fadeDuration) {
        this(v.getContext(), fadeDuration);
        addTarget(v);
    }
    public ViewInvertHelper(Context context, long fadeDuration) {
        mFadeDuration = fadeDuration;
        mThemeInvert = context.getResources().getBoolean(com.android.internal.R.bool.config_invert_colors_on_doze);
    }

    private static ArrayList<View> constructArray(View target) {
        final ArrayList<View> views = new ArrayList<>();
        views.add(target);
        return views;
    }

    public void clearTargets() {
        mTargets.clear();
    }

    public void addTarget(View target) {
        mTargets.add(target);
    }

    public void fade(final boolean invert, long delay) {
        float startIntensity = invert ? 0f : 1f;
        float endIntensity = invert ? 1f : 0f;
        ValueAnimator animator = ValueAnimator.ofFloat(startIntensity, endIntensity);
        animator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                updateInvertPaint((Float) animation.getAnimatedValue());
                for (int i = 0; i < mTargets.size(); i++) {
                    mTargets.get(i).setLayerType(View.LAYER_TYPE_HARDWARE, mDarkPaint);
                }
            }
        });
        animator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                if (!invert) {
                    for (int i = 0; i < mTargets.size(); i++) {
                        mTargets.get(i).setLayerType(View.LAYER_TYPE_NONE, null);
                    }
                }
            }
        });
        animator.setDuration(mFadeDuration);
        animator.setInterpolator(Interpolators.LINEAR_OUT_SLOW_IN);
        animator.setStartDelay(delay);
        animator.start();
    }

    public void update(boolean invert) {
        if (invert) {
            updateInvertPaint(1f);
            for (int i = 0; i < mTargets.size(); i++) {
                mTargets.get(i).setLayerType(View.LAYER_TYPE_HARDWARE, mDarkPaint);
            }
        } else {
            for (int i = 0; i < mTargets.size(); i++) {
                mTargets.get(i).setLayerType(View.LAYER_TYPE_NONE, null);
            }
        }
    }

    private void updateInvertPaint(float intensity) {
        float components = 1 - 2 * intensity;
        final float[] invert = {
                components, 0f,         0f,         0f, 255f * intensity,
                0f,         components, 0f,         0f, 255f * intensity,
                0f,         0f,         components, 0f, 255f * intensity,
                0f,         0f,         0f,         1f, 0f
        };
        mMatrix.set(invert);
        mGrayscaleMatrix.setSaturation(1 - intensity);
        mMatrix.preConcat(mGrayscaleMatrix);
        mDarkPaint.setColorFilter(new ColorMatrixColorFilter(
                mThemeInvert ? mMatrix : mGrayscaleMatrix));

    }

    public void setInverted(boolean invert, boolean fade, long delay) {
        if (fade) {
            fade(invert, delay);
        } else {
            update(invert);
        }
    }
}