/*
 * Copyright (C) 2012 The Android Open Source Project
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

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.animation.ValueAnimator.AnimatorUpdateListener;
import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.Interpolator;
import android.view.animation.LinearInterpolator;
import android.widget.LinearLayout;

/**
 * A layout which animates a strip of horizontal, pulsing dots on request. This is used
 * to indicate the presence of pages to the left / right.
 */
public class KeyguardGlowStripView extends LinearLayout {
    private static final int DURATION = 500;

    private static final float SLIDING_WINDOW_SIZE = 0.4f;
    private int mDotStripTop;
    private int mHorizontalDotGap;

    private int mDotSize;
    private int mNumDots;
    private Drawable mDotDrawable;
    private boolean mLeftToRight = true;

    private float mAnimationProgress = 0f;
    private boolean mDrawDots = false;
    private ValueAnimator mAnimator;
    private Interpolator mDotAlphaInterpolator = new DecelerateInterpolator(0.5f);

    public KeyguardGlowStripView(Context context) {
        this(context, null, 0);
    }

    public KeyguardGlowStripView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public KeyguardGlowStripView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);

        TypedArray a = context.obtainStyledAttributes(attrs, R.styleable.KeyguardGlowStripView);
        mDotSize = a.getDimensionPixelSize(R.styleable.KeyguardGlowStripView_dotSize, mDotSize);
        mNumDots = a.getInt(R.styleable.KeyguardGlowStripView_numDots, mNumDots);
        mDotDrawable = a.getDrawable(R.styleable.KeyguardGlowStripView_glowDot);
        mLeftToRight = a.getBoolean(R.styleable.KeyguardGlowStripView_leftToRight, mLeftToRight);
    }

    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        int availableWidth = w - getPaddingLeft() - getPaddingRight();
        mHorizontalDotGap = (availableWidth - mDotSize * mNumDots) /  (mNumDots - 1);
        mDotStripTop = getPaddingTop();
        invalidate();
    }

    @Override
    protected void dispatchDraw(Canvas canvas) {
        super.dispatchDraw(canvas);

        if (!mDrawDots) return;

        int xOffset = getPaddingLeft();
        mDotDrawable.setBounds(0, 0, mDotSize, mDotSize);

        for (int i = 0; i < mNumDots; i++) {
            // We fudge the relative position to provide a fade in of the first dot and a fade
            // out of the final dot.
            float relativeDotPosition = SLIDING_WINDOW_SIZE / 2 + ((1.0f * i) / (mNumDots - 1)) *
                    (1 - SLIDING_WINDOW_SIZE);
            float distance = Math.abs(relativeDotPosition - mAnimationProgress);
            float alpha = Math.max(0, 1 - distance / (SLIDING_WINDOW_SIZE / 2));

            alpha = mDotAlphaInterpolator.getInterpolation(alpha);

            canvas.save();
            canvas.translate(xOffset, mDotStripTop);
            mDotDrawable.setAlpha((int) (alpha * 255));
            mDotDrawable.draw(canvas);
            canvas.restore();
            xOffset += mDotSize + mHorizontalDotGap;
        }
    }

    public void makeEmGo() {
        if (mAnimator != null) {
            mAnimator.cancel();
        }
        float from = mLeftToRight ? 0f : 1f;
        float to = mLeftToRight ? 1f : 0f;
        mAnimator = ValueAnimator.ofFloat(from, to);
        mAnimator.setDuration(DURATION);
        mAnimator.setInterpolator(new LinearInterpolator());
        mAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                mDrawDots = false;
                // make sure we draw one frame at the end with everything gone.
                invalidate();
            }

            @Override
            public void onAnimationStart(Animator animation) {
                mDrawDots = true;
            }
        });
        mAnimator.addUpdateListener(new AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                mAnimationProgress = (Float) animation.getAnimatedValue();
                invalidate();
            }
        });
        mAnimator.start();
    }
}
