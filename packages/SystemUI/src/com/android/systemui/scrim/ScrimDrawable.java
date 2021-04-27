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

package com.android.systemui.scrim;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Xfermode;
import android.graphics.drawable.Drawable;
import android.view.animation.DecelerateInterpolator;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.graphics.ColorUtils;

/**
 * Drawable used on SysUI scrims.
 */
public class ScrimDrawable extends Drawable {
    private static final String TAG = "ScrimDrawable";
    private static final long COLOR_ANIMATION_DURATION = 2000;

    private final Paint mPaint;
    private int mAlpha = 255;
    private int mMainColor;
    private ValueAnimator mColorAnimation;
    private int mMainColorTo;
    private float mCornerRadius;
    private ConcaveInfo mConcaveInfo;
    private int mBottomEdgePosition;
    private boolean mCornerRadiusEnabled;

    public ScrimDrawable() {
        mPaint = new Paint();
        mPaint.setStyle(Paint.Style.FILL);
    }

    /**
     * Sets the background color.
     * @param mainColor the color.
     * @param animated if transition should be interpolated.
     */
    public void setColor(int mainColor, boolean animated) {
        if (mainColor == mMainColorTo) {
            return;
        }

        if (mColorAnimation != null && mColorAnimation.isRunning()) {
            mColorAnimation.cancel();
        }

        mMainColorTo = mainColor;

        if (animated) {
            final int mainFrom = mMainColor;

            ValueAnimator anim = ValueAnimator.ofFloat(0, 1);
            anim.setDuration(COLOR_ANIMATION_DURATION);
            anim.addUpdateListener(animation -> {
                float ratio = (float) animation.getAnimatedValue();
                mMainColor = ColorUtils.blendARGB(mainFrom, mainColor, ratio);
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
            invalidateSelf();
        }
    }

    @Override
    public void setAlpha(int alpha) {
        if (alpha != mAlpha) {
            mAlpha = alpha;
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

    /**
     * Corner radius used by either concave or convex corners.
     */
    public void setRoundedCorners(float radius) {
        if (radius == mCornerRadius) {
            return;
        }
        mCornerRadius = radius;
        if (mConcaveInfo != null) {
            mConcaveInfo.setCornerRadius(radius);
            updatePath();
        }
        invalidateSelf();
    }

    /**
     * If we should draw a rounded rect instead of a rect.
     */
    public void setRoundedCornersEnabled(boolean enabled) {
        if (mCornerRadiusEnabled == enabled) {
            return;
        }
        mCornerRadiusEnabled = enabled;
        invalidateSelf();
    }

    /**
     * If we should draw a concave rounded rect instead of a rect.
     */
    public void setBottomEdgeConcave(boolean enabled) {
        if (enabled && mConcaveInfo != null) {
            return;
        }
        if (!enabled) {
            mConcaveInfo = null;
        } else {
            mConcaveInfo = new ConcaveInfo();
            mConcaveInfo.setCornerRadius(mCornerRadius);
        }
        invalidateSelf();
    }

    /**
     * Location of concave edge.
     * @see #setBottomEdgeConcave(boolean)
     */
    public void setBottomEdgePosition(int y) {
        if (mBottomEdgePosition == y) {
            return;
        }
        mBottomEdgePosition = y;
        if (mConcaveInfo == null) {
            return;
        }
        updatePath();
        invalidateSelf();
    }

    @Override
    public void draw(@NonNull Canvas canvas) {
        mPaint.setColor(mMainColor);
        mPaint.setAlpha(mAlpha);
        if (mConcaveInfo != null) {
            drawConcave(canvas);
        } else if (mCornerRadiusEnabled && mCornerRadius > 0) {
            canvas.drawRoundRect(getBounds().left, getBounds().top, getBounds().right,
                    getBounds().bottom + mCornerRadius,
                    /* x radius*/ mCornerRadius, /* y radius*/ mCornerRadius, mPaint);
        } else {
            canvas.drawRect(getBounds().left, getBounds().top, getBounds().right,
                    getBounds().bottom, mPaint);
        }
    }

    @Override
    protected void onBoundsChange(Rect bounds) {
        updatePath();
    }

    private void drawConcave(Canvas canvas) {
        canvas.clipOutPath(mConcaveInfo.mPath);
        canvas.drawRect(getBounds().left, getBounds().top, getBounds().right,
                mBottomEdgePosition + mConcaveInfo.mPathOverlap, mPaint);
    }

    private void updatePath() {
        if (mConcaveInfo == null) {
            return;
        }
        mConcaveInfo.mPath.reset();
        float top = mBottomEdgePosition;
        float bottom = mBottomEdgePosition + mConcaveInfo.mPathOverlap;
        mConcaveInfo.mPath.addRoundRect(getBounds().left, top, getBounds().right, bottom,
                mConcaveInfo.mCornerRadii, Path.Direction.CW);
    }

    @VisibleForTesting
    public int getMainColor() {
        return mMainColor;
    }

    private static class ConcaveInfo {
        private float mPathOverlap;
        private final float[] mCornerRadii;
        private final Path mPath = new Path();

        ConcaveInfo() {
            mCornerRadii = new float[] {0, 0, 0, 0, 0, 0, 0, 0};
        }

        public void setCornerRadius(float radius) {
            mPathOverlap = radius;
            mCornerRadii[0] = radius;
            mCornerRadii[1] = radius;
            mCornerRadii[2] = radius;
            mCornerRadii[3] = radius;
        }
    }
}
