/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.internal.colorextraction.drawable;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.Xfermode;
import android.graphics.drawable.Drawable;
import android.view.animation.DecelerateInterpolator;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.colorextraction.ColorExtractor;
import com.android.internal.graphics.ColorUtils;

/**
 * Draws a gradient based on a Palette
 */
public class GradientDrawable extends Drawable {
    private static final String TAG = "GradientDrawable";

    private static final float CENTRALIZED_CIRCLE_1 = -2;
    private static final int GRADIENT_RADIUS = 480; // in dp
    private static final long COLOR_ANIMATION_DURATION = 2000;

    private int mAlpha = 255;

    private float mDensity;
    private final Paint mPaint;
    private final Rect mWindowBounds;
    private final Splat mSplat;

    private int mMainColor;
    private int mSecondaryColor;
    private ValueAnimator mColorAnimation;
    private int mMainColorTo;
    private int mSecondaryColorTo;

    public GradientDrawable(@NonNull Context context) {
        mDensity = context.getResources().getDisplayMetrics().density;
        mSplat = new Splat(0.50f, 1.00f, GRADIENT_RADIUS, CENTRALIZED_CIRCLE_1);
        mWindowBounds = new Rect();

        mPaint = new Paint();
        mPaint.setStyle(Paint.Style.FILL);
    }

    public void setColors(@NonNull ColorExtractor.GradientColors colors) {
        setColors(colors.getMainColor(), colors.getSecondaryColor(), true);
    }

    public void setColors(@NonNull ColorExtractor.GradientColors colors, boolean animated) {
        setColors(colors.getMainColor(), colors.getSecondaryColor(), animated);
    }

    public void setColors(int mainColor, int secondaryColor, boolean animated) {
        if (mainColor == mMainColorTo && secondaryColor == mSecondaryColorTo) {
            return;
        }

        if (mColorAnimation != null && mColorAnimation.isRunning()) {
            mColorAnimation.cancel();
        }

        mMainColorTo = mainColor;
        mSecondaryColorTo = mainColor;

        if (animated) {
            final int mainFrom = mMainColor;
            final int secFrom = mSecondaryColor;

            ValueAnimator anim = ValueAnimator.ofFloat(0, 1);
            anim.setDuration(COLOR_ANIMATION_DURATION);
            anim.addUpdateListener(animation -> {
                float ratio = (float) animation.getAnimatedValue();
                mMainColor = ColorUtils.blendARGB(mainFrom, mainColor, ratio);
                mSecondaryColor = ColorUtils.blendARGB(secFrom, secondaryColor, ratio);
                buildPaints();
                invalidateSelf();
            });
            anim.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation, boolean isReverse) {
                    if (mColorAnimation == animation) {
                        mColorAnimation = null;
                    }
                }
            });
            anim.setInterpolator(new DecelerateInterpolator());
            anim.start();
            mColorAnimation = anim;
        } else {
            mMainColor = mainColor;
            mSecondaryColor = secondaryColor;
            buildPaints();
            invalidateSelf();
        }
    }

    @Override
    public void setAlpha(int alpha) {
        if (alpha != mAlpha) {
            mAlpha = alpha;
            mPaint.setAlpha(mAlpha);
            invalidateSelf();
        }
    }

    @Override
    public int getAlpha() {
        return mAlpha;
    }

    @Override
    public void setXfermode(@Nullable Xfermode mode) {
        mPaint.setXfermode(mode);
        invalidateSelf();
    }

    @Override
    public void setColorFilter(ColorFilter colorFilter) {
        mPaint.setColorFilter(colorFilter);
    }

    @Override
    public ColorFilter getColorFilter() {
        return mPaint.getColorFilter();
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }

    public void setScreenSize(int width, int height) {
        mWindowBounds.set(0, 0, width, height);
        setBounds(0, 0, width, height);
        buildPaints();
    }

    private void buildPaints() {
        Rect bounds = mWindowBounds;
        if (bounds.width() == 0) {
            return;
        }

        float w = bounds.width();
        float h = bounds.height();

        float x = mSplat.x * w;
        float y = mSplat.y * h;

        float radius = mSplat.radius * mDensity;

        // When we have only a single alpha gradient, we increase quality
        // (avoiding banding) by merging the background solid color into
        // the gradient directly
        RadialGradient radialGradient = new RadialGradient(x, y, radius,
                mSecondaryColor, mMainColor, Shader.TileMode.CLAMP);
        mPaint.setShader(radialGradient);
    }

    @Override
    public void draw(@NonNull Canvas canvas) {
        Rect bounds = mWindowBounds;
        if (bounds.width() == 0) {
            throw new IllegalStateException("You need to call setScreenSize before drawing.");
        }

        // Splat each gradient
        float w = bounds.width();
        float h = bounds.height();

        float x = mSplat.x * w;
        float y = mSplat.y * h;

        float radius = Math.max(w, h);
        canvas.drawRect(x - radius, y - radius, x + radius, y + radius, mPaint);
    }

    @VisibleForTesting
    public int getMainColor() {
        return mMainColor;
    }

    @VisibleForTesting
    public int getSecondaryColor() {
        return mSecondaryColor;
    }

    static final class Splat {
        final float x;
        final float y;
        final float radius;
        final float colorIndex;

        Splat(float x, float y, float radius, float colorIndex) {
            this.x = x;
            this.y = y;
            this.radius = radius;
            this.colorIndex = colorIndex;
        }
    }
}