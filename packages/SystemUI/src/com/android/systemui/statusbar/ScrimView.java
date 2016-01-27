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

package com.android.systemui.statusbar;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.Interpolator;

/**
 * A view which can draw a scrim
 */
public class ScrimView extends View
{
    private final Paint mPaint = new Paint();
    private int mScrimColor;
    private boolean mIsEmpty = true;
    private boolean mDrawAsSrc;
    private float mViewAlpha = 1.0f;
    private ValueAnimator mAlphaAnimator;
    private Rect mExcludedRect = new Rect();
    private boolean mHasExcludedArea;
    private ValueAnimator.AnimatorUpdateListener mAlphaUpdateListener
            = new ValueAnimator.AnimatorUpdateListener() {
        @Override
        public void onAnimationUpdate(ValueAnimator animation) {
            mViewAlpha = (float) animation.getAnimatedValue();
            invalidate();
        }
    };
    private AnimatorListenerAdapter mClearAnimatorListener = new AnimatorListenerAdapter() {
        @Override
        public void onAnimationEnd(Animator animation) {
            mAlphaAnimator = null;
        }
    };
    private Runnable mChangeRunnable;

    public ScrimView(Context context) {
        this(context, null);
    }

    public ScrimView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public ScrimView(Context context, AttributeSet attrs, int defStyleAttr) {
        this(context, attrs, defStyleAttr, 0);
    }

    public ScrimView(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (mDrawAsSrc || (!mIsEmpty && mViewAlpha > 0f)) {
            PorterDuff.Mode mode = mDrawAsSrc ? PorterDuff.Mode.SRC : PorterDuff.Mode.SRC_OVER;
            int color = getScrimColorWithAlpha();
            if (!mHasExcludedArea) {
                canvas.drawColor(color, mode);
            } else {
                mPaint.setColor(color);
                if (mExcludedRect.top > 0) {
                    canvas.drawRect(0, 0, getWidth(), mExcludedRect.top, mPaint);
                }
                if (mExcludedRect.left > 0) {
                    canvas.drawRect(0,  mExcludedRect.top, mExcludedRect.left, mExcludedRect.bottom,
                            mPaint);
                }
                if (mExcludedRect.right < getWidth()) {
                    canvas.drawRect(mExcludedRect.right,
                            mExcludedRect.top,
                            getWidth(),
                            mExcludedRect.bottom,
                            mPaint);
                }
                if (mExcludedRect.bottom < getHeight()) {
                    canvas.drawRect(0,  mExcludedRect.bottom, getWidth(), getHeight(), mPaint);
                }
            }
        }
    }

    public int getScrimColorWithAlpha() {
        int color = mScrimColor;
        color = Color.argb((int) (Color.alpha(color) * mViewAlpha), Color.red(color),
                Color.green(color), Color.blue(color));
        return color;
    }

    public void setDrawAsSrc(boolean asSrc) {
        mDrawAsSrc = asSrc;
        mPaint.setXfermode(new PorterDuffXfermode(mDrawAsSrc ? PorterDuff.Mode.SRC
                : PorterDuff.Mode.SRC_OVER));
        invalidate();
    }

    public void setScrimColor(int color) {
        if (color != mScrimColor) {
            mIsEmpty = Color.alpha(color) == 0;
            mScrimColor = color;
            invalidate();
            if (mChangeRunnable != null) {
                mChangeRunnable.run();
            }
        }
    }

    public int getScrimColor() {
        return mScrimColor;
    }

    @Override
    public boolean hasOverlappingRendering() {
        return false;
    }

    public void setViewAlpha(float alpha) {
        if (mAlphaAnimator != null) {
            mAlphaAnimator.cancel();
        }
        if (alpha != mViewAlpha) {
            mViewAlpha = alpha;
            invalidate();
            if (mChangeRunnable != null) {
                mChangeRunnable.run();
            }
        }
    }

    public void animateViewAlpha(float alpha, long durationOut, Interpolator interpolator) {
        if (mAlphaAnimator != null) {
            mAlphaAnimator.cancel();
        }
        mAlphaAnimator = ValueAnimator.ofFloat(mViewAlpha, alpha);
        mAlphaAnimator.addUpdateListener(mAlphaUpdateListener);
        mAlphaAnimator.addListener(mClearAnimatorListener);
        mAlphaAnimator.setInterpolator(interpolator);
        mAlphaAnimator.setDuration(durationOut);
        mAlphaAnimator.start();
    }

    public void setExcludedArea(Rect area) {
        if (area == null) {
            mHasExcludedArea = false;
            invalidate();
            return;
        }

        int left = Math.max(area.left, 0);
        int top = Math.max(area.top, 0);
        int right = Math.min(area.right, getWidth());
        int bottom = Math.min(area.bottom, getHeight());
        mExcludedRect.set(left, top, right, bottom);
        mHasExcludedArea = left < right && top < bottom;
        invalidate();
    }

    public void setChangeRunnable(Runnable changeRunnable) {
        mChangeRunnable = changeRunnable;
    }
}
