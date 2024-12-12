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
import android.graphics.RectF;
import android.graphics.Xfermode;
import android.graphics.drawable.Drawable;
import android.view.animation.DecelerateInterpolator;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.graphics.ColorUtils;
import com.android.systemui.statusbar.notification.stack.StackStateAnimator;
import static com.android.systemui.Flags.notificationShadeBlur;

/**
 * Drawable used on SysUI scrims.
 */
public class ScrimDrawable extends Drawable {
    private static final String TAG = "ScrimDrawable";

    private boolean mShouldUseLargeScreenSize;
    private final Paint mPaint;
    private final Path mPath = new Path();
    private final RectF mBoundsRectF = new RectF();

    private int mAlpha = 255;
    private int mMainColor;
    private ValueAnimator mColorAnimation;
    private int mMainColorTo;
    private float mCornerRadius;
    private ConcaveInfo mConcaveInfo;
    private int mBottomEdgePosition;
    private float mBottomEdgeRadius = -1;
    private boolean mCornerRadiusEnabled;

    public ScrimDrawable() {
        mPaint = new Paint();
        mPaint.setStyle(Paint.Style.FILL);
        mShouldUseLargeScreenSize = false;
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
            anim.setDuration(StackStateAnimator.ANIMATION_DURATION_STANDARD);
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

    public void setShouldUseLargeScreenSize(boolean v) {
        mShouldUseLargeScreenSize = v;
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

    public void setBottomEdgeRadius(float radius) {
        if (mBottomEdgeRadius != radius) {
            mBottomEdgeRadius = radius;
            invalidateSelf();
        }
    }

    @Override
    public void draw(@NonNull Canvas canvas) {
        mPaint.setColor(mMainColor);
        mPaint.setAlpha(mAlpha);
        if (notificationShadeBlur()) {
            // TODO(b/370555223): Match the alpha to the visual spec when it is finalized.
            mPaint.setAlpha((int) (0.5f * mAlpha));
        }
        if (mConcaveInfo != null) {
            drawConcave(canvas);
        } else if (mCornerRadiusEnabled && mCornerRadius > 0) {
            float topEdgeRadius = mCornerRadius;
            float bottomEdgeRadius = mBottomEdgeRadius == -1.0 ? mCornerRadius : mBottomEdgeRadius;

            mBoundsRectF.set(getBounds());

            // When the back gesture causes the notification scrim to be scaled down,
            // this offset "reveals" the rounded bottom edge as it "pulls away".
            // We must *not* make this adjustment on largescreen shades (where the corner is sharp).
            if (!mShouldUseLargeScreenSize && mBottomEdgeRadius != -1) {
                mBoundsRectF.bottom -= bottomEdgeRadius;
            }

            // We need a box with rounded corners but its lower corners are not rounded on large
            // screen devices in "portrait" orientation.
            // Thus, we cannot draw a symmetric rounded rectangle via canvas.drawRoundRect()
            // and must build a box with different corner radii at the top and at the bottom.
            // Additionally, when the scrim is pushed to the very bottom of the screen, do not draw
            // anything (drawing a rounded box with these specifications is not possible).
            // TODO(b/271030611) perhaps this could be accomplished via Path.addRoundRect instead?
            if (mBoundsRectF.bottom - mBoundsRectF.top > bottomEdgeRadius) {
                mPath.reset();
                mPath.moveTo(mBoundsRectF.right, mBoundsRectF.top + topEdgeRadius);
                mPath.cubicTo(mBoundsRectF.right, mBoundsRectF.top + topEdgeRadius,
                        mBoundsRectF.right, mBoundsRectF.top,
                        mBoundsRectF.right - topEdgeRadius, mBoundsRectF.top);
                mPath.lineTo(mBoundsRectF.left + topEdgeRadius, mBoundsRectF.top);
                mPath.cubicTo(mBoundsRectF.left + topEdgeRadius, mBoundsRectF.top,
                        mBoundsRectF.left, mBoundsRectF.top,
                        mBoundsRectF.left, mBoundsRectF.top + topEdgeRadius);
                mPath.lineTo(mBoundsRectF.left, mBoundsRectF.bottom - bottomEdgeRadius);
                mPath.cubicTo(mBoundsRectF.left, mBoundsRectF.bottom - bottomEdgeRadius,
                        mBoundsRectF.left, mBoundsRectF.bottom,
                        mBoundsRectF.left + bottomEdgeRadius, mBoundsRectF.bottom);
                mPath.lineTo(mBoundsRectF.right - bottomEdgeRadius, mBoundsRectF.bottom);
                mPath.cubicTo(mBoundsRectF.right - bottomEdgeRadius, mBoundsRectF.bottom,
                        mBoundsRectF.right, mBoundsRectF.bottom,
                        mBoundsRectF.right, mBoundsRectF.bottom - bottomEdgeRadius);
                mPath.close();
                canvas.drawPath(mPath, mPaint);
            }
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
